package io.tokenpilot.springai.internal;

import io.tokenpilot.budget.BudgetDecision;
import io.tokenpilot.budget.BudgetEvaluator;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.budget.exception.BudgetExceededException;
import io.tokenpilot.core.*;
import io.tokenpilot.core.domain.*;
import io.tokenpilot.springai.LedgerAdvisor;
import io.tokenpilot.springai.UsageExtractor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

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

    private final LedgerManager ledgerManager;
    private final UsageExtractor usageExtractor;
    private final BudgetEvaluator budgetEvaluator;
    private final BudgetStateStore budgetStateStore;
    private final CostCalculator costCalculator;
    private final PricingRegistry pricingRegistry;

    public DefaultLedgerAdvisor(LedgerManager ledgerManager, UsageExtractor usageExtractor) {
        this(ledgerManager, usageExtractor, null, null, null, null);
    }

    public DefaultLedgerAdvisor(LedgerManager ledgerManager, UsageExtractor usageExtractor,
                                BudgetEvaluator budgetEvaluator, BudgetStateStore budgetStateStore,
                                CostCalculator costCalculator, PricingRegistry pricingRegistry) {
        this.ledgerManager = ledgerManager;
        this.usageExtractor = usageExtractor;
        this.budgetEvaluator = budgetEvaluator;
        this.budgetStateStore = budgetStateStore;
        this.costCalculator = costCalculator;
        this.pricingRegistry = pricingRegistry;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (budgetEvaluator != null) {
            Map<String, String> tags = extractTagsFromRequest(request);
            BudgetDecision decision = budgetEvaluator.evaluate(tags);
            enforceExistingBlock(decision);
            return request.mutate()
                          .context(BUDGET_DECISION_CONTEXT, decision)
                          .build();
        }
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        TokenUsage usage = usageExtractor.extract(response);
        
        String modelId = extractModelId(response);
        Map<String, String> tags = extractTags(response);

        ledgerManager.record(modelId, usage, tags);

        // 예산 누적 처리
        if (budgetStateStore != null && costCalculator != null && pricingRegistry != null) {
            Optional<PricingPlan> plan = pricingRegistry.getPlan(modelId);
            if (plan.isPresent()) {
                Cost cost = costCalculator.calculate(usage, plan.get());
                BudgetDecision decision = extractBudgetDecision(response);
                budgetStateStore.addCost(
                    decision.key(),
                    decision.limit(),
                    cost
                );
            }
        }

        return response;
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
        if (response.chatResponse() != null && response.chatResponse().getMetadata() != null) {
            String model = response.chatResponse().getMetadata().getModel();
            if (model != null && !model.isBlank()) {
                return model;
            }
        }
        return "unknown-model";
    }

    private Map<String, String> extractTags(ChatClientResponse response) {
        Map<String, String> tags = new HashMap<>();
        
        Map<String, Object> context = response.context();
        if (context != null) {
            context.forEach((k, v) -> {
                if (v instanceof String s) {
                    tags.put(k, s);
                }
            });
        }

        return tags;
    }

    private BudgetDecision extractBudgetDecision(ChatClientResponse response) {
        Map<String, Object> context = response.context();
        Object value = context == null ? null : context.get(BUDGET_DECISION_CONTEXT);
        if (value instanceof BudgetDecision decision) {
            return decision;
        }
        throw new IllegalStateException("Resolved budget decision is missing from response context");
    }

    private Map<String, String> extractTagsFromRequest(ChatClientRequest request) {
        Map<String, String> tags = new HashMap<>();
        Map<String, Object> context = request.context();
        if (context != null) {
            context.forEach((k, v) -> {
                if (v instanceof String s) {
                    tags.put(k, s);
                }
            });
        }
        return tags;
    }
}
