package io.tokenpilot.springai.internal;

import io.tokenpilot.budget.BudgetDecision;
import io.tokenpilot.budget.BudgetEvaluator;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.budget.exception.BudgetExceededException;
import io.tokenpilot.core.*;
import io.tokenpilot.core.domain.*;
import io.tokenpilot.core.exception.MissingPricingException;
import io.tokenpilot.core.internal.LedgerComponents;
import io.tokenpilot.springai.LedgerAdvisor;
import io.tokenpilot.springai.UsageExtractor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * 기본 {@link LedgerAdvisor} 구현체.
 * {@link UsageExtractor}를 사용하여 토큰 사용량을 추출하고,
 * 그 결과를 {@link LedgerManager}에 기록하는 핵심 비즈니스 로직을 수행합니다.
 * 또한 {@link BudgetEvaluator}를 통해 예산 초과 여부를 사전에 차단하고,
 * 호출 성공 시 {@link BudgetStateStore}에 비용을 누적합니다.
 * <p>
 * 현재 {@link #before(ChatClientRequest, AdvisorChain)}의 상태 조회는 이미 BLOCK인 예산의
 * provider 호출 회귀를 막는 legacy guard입니다. 후보 비용을 포함한 admission 근거가 아니며,
 * 최종 candidate-aware lifecycle은 #39의 {@code adviseCall()} 경계가 담당합니다.
 */
public class DefaultLedgerAdvisor implements LedgerAdvisor {

    static final String BUDGET_DECISION_CONTEXT = "tokenpilot.budget.decision";
    static final String MODEL_ID_CONTEXT = "tokenpilot.model.id";
    static final String PRICING_POLICY_ID_CONTEXT = "tokenpilot.pricing.policy.id";
    static final String PRICING_SNAPSHOT_CONTEXT = "tokenpilot.pricing.snapshot";
    static final String PRICING_RESOLUTION_CONTEXT = "tokenpilot.pricing.resolution";
    static final String PRICING_RECONCILIATION_RESULT_CONTEXT = "tokenpilot.pricing.reconciliation.result";

    private final LedgerManager ledgerManager;
    private final UsageExtractor usageExtractor;
    private final BudgetEvaluator budgetEvaluator;
    private final BudgetStateStore budgetStateStore;
    private final CostCalculator costCalculator;
    private final PricingRegistry pricingRegistry;
    private final PricingEvaluator pricingEvaluator;
    private final MissingPricingPolicy missingPricingPolicy;
    private final List<PricingMissingListener> pricingMissingListeners;

    public DefaultLedgerAdvisor(LedgerManager ledgerManager, UsageExtractor usageExtractor) {
        this(ledgerManager, usageExtractor, null, null, null, null);
    }

    public DefaultLedgerAdvisor(LedgerManager ledgerManager, UsageExtractor usageExtractor,
                                BudgetEvaluator budgetEvaluator, BudgetStateStore budgetStateStore,
                                CostCalculator costCalculator, PricingRegistry pricingRegistry) {
        this(
                ledgerManager,
                usageExtractor,
                budgetEvaluator,
                budgetStateStore,
                costCalculator,
                pricingRegistry,
                MissingPricingPolicy.FAIL_OPEN
        );
    }

    public DefaultLedgerAdvisor(LedgerManager ledgerManager, UsageExtractor usageExtractor,
                                BudgetEvaluator budgetEvaluator, BudgetStateStore budgetStateStore,
                                CostCalculator costCalculator, PricingRegistry pricingRegistry,
                                MissingPricingPolicy missingPricingPolicy) {
        this(
                ledgerManager,
                usageExtractor,
                budgetEvaluator,
                budgetStateStore,
                costCalculator,
                pricingRegistry,
                LedgerComponents.defaultPricingEvaluator(),
                missingPricingPolicy
        );
    }

    public DefaultLedgerAdvisor(LedgerManager ledgerManager, UsageExtractor usageExtractor,
                                BudgetEvaluator budgetEvaluator, BudgetStateStore budgetStateStore,
                                CostCalculator costCalculator, PricingRegistry pricingRegistry,
                                PricingEvaluator pricingEvaluator,
                                MissingPricingPolicy missingPricingPolicy) {
        this(
                ledgerManager,
                usageExtractor,
                budgetEvaluator,
                budgetStateStore,
                costCalculator,
                pricingRegistry,
                pricingEvaluator,
                missingPricingPolicy,
                List.of()
        );
    }

    public DefaultLedgerAdvisor(LedgerManager ledgerManager, UsageExtractor usageExtractor,
                                BudgetEvaluator budgetEvaluator, BudgetStateStore budgetStateStore,
                                CostCalculator costCalculator, PricingRegistry pricingRegistry,
                                PricingEvaluator pricingEvaluator,
                                MissingPricingPolicy missingPricingPolicy,
                                List<PricingMissingListener> pricingMissingListeners) {
        this.ledgerManager = ledgerManager;
        this.usageExtractor = usageExtractor;
        this.budgetEvaluator = budgetEvaluator;
        this.budgetStateStore = budgetStateStore;
        this.costCalculator = costCalculator;
        this.pricingRegistry = pricingRegistry;
        this.pricingEvaluator = Objects.requireNonNull(
                pricingEvaluator,
                "pricingEvaluator must not be null"
        );
        this.missingPricingPolicy = Objects.requireNonNull(
                missingPricingPolicy,
                "missingPricingPolicy must not be null"
        );
        this.pricingMissingListeners = List.copyOf(
                Objects.requireNonNull(
                        pricingMissingListeners,
                        "pricingMissingListeners must not be null"
                )
        );
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        ChatClientRequest resolvedRequest = request;

        if (budgetEvaluator != null) {
            Map<String, String> tags = extractTags(request.context());
            BudgetDecision decision = budgetEvaluator.evaluate(tags);
            enforceExistingBlock(decision);
            resolvedRequest = resolvedRequest.mutate()
                          .context(BUDGET_DECISION_CONTEXT, decision)
                          .build();
        }

        if (pricingRegistry != null) {
            resolvedRequest = resolvePricing(resolvedRequest);
        }

        return resolvedRequest;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        TokenUsage usage = usageExtractor.extract(response);

        String modelId = extractModelId(response);
        String responseModelId = extractResponseModelId(response);
        Map<String, String> tags = extractTags(response);

        Optional<PricingSnapshot> snapshot = extractPricingSnapshot(response);
        boolean hasPricingResolution = hasPricingResolution(response);
        if (snapshot.isPresent() || hasPricingResolution) {
            PricingReconciliationResult reconciliationResult = pricingEvaluator.determineReconciliation(
                    snapshot,
                    responseModelId
            );
            if (reconciliationResult != PricingReconciliationResult.RECONCILED) {
                return withReconciliationResult(response, reconciliationResult);
            }

            PricingSnapshot resolvedSnapshot = snapshot.orElseThrow(
                    () -> new IllegalStateException("Reconciled pricing snapshot is missing")
            );
            Cost cost;
            try {
                cost = ledgerManager.record(resolvedSnapshot, usage, tags);
            } catch (MissingPricingException exception) {
                publishPricingMissingBestEffort(exception.getResolution());
                return handleActualPricingFailure(response, exception);
            }
            ChatClientResponse reconciledResponse = withReconciliationResult(
                    response,
                    reconciliationResult
            );
            if (budgetStateStore != null) {
                BudgetDecision decision = extractBudgetDecision(reconciledResponse);
                budgetStateStore.addCost(
                    decision.key(),
                    decision.limit(),
                    cost
                );
            }
            return reconciledResponse;
        } else {
            try {
                ledgerManager.record(modelId, usage, tags);
            } catch (MissingPricingException exception) {
                publishPricingMissingBestEffort(exception.getResolution());
                throw exception;
            }
            recordLegacyBudgetCost(modelId, usage, response);
        }

        return response;
    }

    private ChatClientResponse handleActualPricingFailure(
            ChatClientResponse response,
            MissingPricingException exception
    ) {
        if (missingPricingPolicy == MissingPricingPolicy.FAIL_CLOSED) {
            throw exception;
        }

        Map<String, Object> context = copyContext(response);
        context.put(PRICING_RESOLUTION_CONTEXT, exception.getResolution());
        context.put(PRICING_RECONCILIATION_RESULT_CONTEXT, PricingReconciliationResult.UNPRICED);
        return new ChatClientResponse(response.chatResponse(), context);
    }

    private ChatClientResponse withReconciliationResult(
            ChatClientResponse response,
            PricingReconciliationResult result
    ) {
        Map<String, Object> context = copyContext(response);
        context.put(PRICING_RECONCILIATION_RESULT_CONTEXT, result);
        return new ChatClientResponse(response.chatResponse(), context);
    }

    private void recordLegacyBudgetCost(String modelId, TokenUsage usage, ChatClientResponse response) {
        if (budgetStateStore == null || costCalculator == null || pricingRegistry == null) {
            return;
        }

        Optional<PricingPlan> plan = pricingRegistry.getPlan(modelId);
        if (plan.isEmpty()) {
            return;
        }

        Cost cost = costCalculator.calculate(usage, plan.get());
        BudgetDecision decision = extractBudgetDecision(response);
        budgetStateStore.addCost(
            decision.key(),
            decision.limit(),
            cost
        );
    }

    private ChatClientRequest resolvePricing(ChatClientRequest request) {
        String modelId = extractModelId(request);
        String pricingPolicyId = extractPricingPolicyId(request);
        Optional<PricingSnapshot> snapshot = modelId == null
                ? Optional.empty()
                : pricingRegistry.resolveSnapshot(modelId, pricingPolicyId);
        PricingResolution resolution = pricingEvaluator.validateSnapshotRates(snapshot);
        if (!resolution.isResolved()) {
            publishPricingMissingBestEffort(resolution);
        }
        rejectMissingPricingIfFailClosed(resolution);

        return withPricingContext(request, pricingPolicyId, resolution, snapshot);
    }

    private ChatClientRequest withPricingContext(
            ChatClientRequest request,
            String pricingPolicyId,
            PricingResolution resolution,
            Optional<PricingSnapshot> snapshot
    ) {
        ChatClientRequest.Builder builder = request.mutate()
                .context(PRICING_POLICY_ID_CONTEXT, pricingPolicyId)
                .context(PRICING_RESOLUTION_CONTEXT, resolution);
        if (resolution.isResolved()) {
            snapshot.ifPresent(value -> builder.context(PRICING_SNAPSHOT_CONTEXT, value));
        }
        return builder.build();
    }

    private void rejectMissingPricingIfFailClosed(PricingResolution resolution) {
        if (missingPricingPolicy != MissingPricingPolicy.FAIL_CLOSED) {
            return;
        }
        if (resolution.isResolved()) {
            return;
        }
        throw new MissingPricingException(resolution);
    }

    private void publishPricingMissingBestEffort(PricingResolution resolution) {
        if (!resolution.isMissing() || pricingMissingListeners.isEmpty()) {
            return;
        }
        PricingMissingEvent event = new PricingMissingEvent(
                missingPricingPolicy,
                resolution
        );
        for (PricingMissingListener listener : pricingMissingListeners) {
            try {
                listener.onPricingMissing(event);
            } catch (RuntimeException ignored) {
                // Optional observers do not change pricing or provider-call semantics.
            }
        }
    }

    private String extractModelId(ChatClientRequest request) {
        Object contextValue = request.context().get(MODEL_ID_CONTEXT);
        if (contextValue instanceof String modelId && !modelId.isBlank()) {
            return modelId;
        }

        ChatOptions options = request.prompt().getOptions();
        if (options == null) {
            return null;
        }

        String modelId = options.getModel();
        if (modelId == null || modelId.isBlank()) {
            return null;
        }

        return modelId;
    }

    private void enforceExistingBlock(BudgetDecision decision) {
        switch (decision.state()) {
            case ALLOW, WARN -> {
                // Legacy boundary: candidate-aware admission is implemented by issue #39.
            }
            case BLOCK -> throw new BudgetExceededException(decision);
            case CURRENCY_MISMATCH -> throw new IllegalStateException(
                    "Budget decision currency mismatch: " + decision.reason()
            );
        }
    }

    private String extractModelId(ChatClientResponse response) {
        Object value = contextValue(response, MODEL_ID_CONTEXT);
        if (value instanceof String modelId && !modelId.isBlank()) {
            return modelId;
        }

        String metadataModelId = extractMetadataModelId(response);
        if (metadataModelId != null) {
            return metadataModelId;
        }
        return "unknown-model";
    }

    private String extractResponseModelId(ChatClientResponse response) {
        String metadataModelId = extractMetadataModelId(response);
        if (metadataModelId != null) {
            return metadataModelId;
        }
        return extractModelId(response);
    }

    private String extractMetadataModelId(ChatClientResponse response) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            return null;
        }

        String modelId = chatResponse.getMetadata()
                                     .getModel();

        if (modelId == null || modelId.isBlank()) {
            return null;
        }

        return modelId;
    }

    private String extractPricingPolicyId(ChatClientRequest request) {
        Object value = request.context().get(PRICING_POLICY_ID_CONTEXT);
        if (value instanceof String pricingPolicyId && !pricingPolicyId.isBlank()) {
            return pricingPolicyId;
        }
        return PricingPlan.DEFAULT_PRICING_POLICY_ID;
    }

    private Optional<PricingSnapshot> extractPricingSnapshot(ChatClientResponse response) {
        Object value = contextValue(response, PRICING_SNAPSHOT_CONTEXT);
        if (value instanceof PricingSnapshot snapshot) {
            return Optional.of(snapshot);
        }
        return Optional.empty();
    }

    private boolean hasPricingResolution(ChatClientResponse response) {
        return contextValue(response, PRICING_RESOLUTION_CONTEXT) instanceof PricingResolution;
    }

    private Map<String, String> extractTags(ChatClientResponse response) {
        return extractTags(response.context());
    }

    private BudgetDecision extractBudgetDecision(ChatClientResponse response) {
        Object value = contextValue(response, BUDGET_DECISION_CONTEXT);
        if (value instanceof BudgetDecision decision) {
            return decision;
        }
        throw new IllegalStateException("Resolved budget decision is missing from response context");
    }

    private Map<String, String> extractTags(Map<String, Object> context) {
        Map<String, String> tags = new HashMap<>();
        if (context == null) {
            return tags;
        }

        for (Map.Entry<String, Object> contextEntry : context.entrySet()) {
            if (contextEntry.getValue() instanceof String tagValue) {
                tags.put(contextEntry.getKey(), tagValue);
            }
        }
        return tags;
    }

    private Object contextValue(ChatClientResponse response, String key) {
        Map<String, Object> context = response.context();
        if (context == null) {
            return null;
        }
        return context.get(key);
    }

    private Map<String, Object> copyContext(ChatClientResponse response) {
        Map<String, Object> context = response.context();
        if (context == null) {
            return new HashMap<>();
        }
        return new HashMap<>(context);
    }
}
