package io.tokenpilot.springai.internal;

import io.tokenpilot.budget.ActualUsageCommand;
import io.tokenpilot.budget.BudgetDecision;
import io.tokenpilot.budget.BudgetEvaluator;
import io.tokenpilot.budget.BudgetReservationRequest;
import io.tokenpilot.budget.BudgetReservationResult;
import io.tokenpilot.budget.BudgetState;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.budget.IdempotencyKey;
import io.tokenpilot.budget.ReservationAccounting;
import io.tokenpilot.budget.ReservationAccountingReason;
import io.tokenpilot.budget.ReservationId;
import io.tokenpilot.budget.ReservationState;
import io.tokenpilot.budget.ReservationTokenEstimate;
import io.tokenpilot.budget.ReservationTransition;
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
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * Default {@link LedgerAdvisor} implementation.
 * Uses {@link UsageExtractor} to extract token usage and records the result in
 * {@link LedgerManager} as its core business logic. It also blocks budget
 * overages in advance through {@link BudgetEvaluator} and accumulates cost in
 * {@link BudgetStateStore} when a call succeeds.
 * <p>
 * {@link #before(ChatClientRequest, AdvisorChain)} is the state guard for the
 * budget-disabled ledger-only compatibility path. Budget-enabled calls perform
 * preflight with candidate cost and the atomic reservation lifecycle in
 * {@link #adviseCall(ChatClientRequest, CallAdvisorChain)}.
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
    private final RequestPreflight requestPreflight;
    private final ReservationAccounting reservationAccounting;
    private final RequestContextAccessor contextAccessor;
    private final IdempotencyKeyResolver idempotencyKeyResolver;
    private final StreamingRequestPolicy streamingRequestPolicy = new StreamingRequestPolicy();
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
                null,
                null,
                null,
                null,
                List.of()
        );
    }

    public DefaultLedgerAdvisor(
            LedgerManager ledgerManager,
            UsageExtractor usageExtractor,
            BudgetEvaluator budgetEvaluator,
            BudgetStateStore budgetStateStore,
            CostCalculator costCalculator,
            PricingRegistry pricingRegistry,
            PricingEvaluator pricingEvaluator,
            MissingPricingPolicy missingPricingPolicy,
            List<PricingMissingListener> pricingMissingListeners
    ) {
        this(
                ledgerManager,
                usageExtractor,
                budgetEvaluator,
                budgetStateStore,
                costCalculator,
                pricingRegistry,
                pricingEvaluator,
                missingPricingPolicy,
                null,
                null,
                null,
                null,
                pricingMissingListeners
        );
    }

    DefaultLedgerAdvisor(
            LedgerManager ledgerManager,
            UsageExtractor usageExtractor,
            BudgetEvaluator budgetEvaluator,
            BudgetStateStore budgetStateStore,
            CostCalculator costCalculator,
            PricingRegistry pricingRegistry,
            PricingEvaluator pricingEvaluator,
            MissingPricingPolicy missingPricingPolicy,
            RequestPreflight requestPreflight,
            ReservationAccounting reservationAccounting,
            RequestContextAccessor contextAccessor,
            IdempotencyKeyResolver idempotencyKeyResolver,
            List<PricingMissingListener> pricingMissingListeners
    ) {
        this.ledgerManager = ledgerManager;
        this.usageExtractor = Objects.requireNonNull(
                usageExtractor,
                "usageExtractor must not be null"
        );
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
        this.requestPreflight = requestPreflight;
        this.contextAccessor = contextAccessor;
        this.idempotencyKeyResolver = idempotencyKeyResolver;
        if (requestPreflight == null) {
            this.reservationAccounting = null;
            return;
        }
        Objects.requireNonNull(budgetEvaluator, "budgetEvaluator must not be null");
        Objects.requireNonNull(contextAccessor, "contextAccessor must not be null");
        Objects.requireNonNull(
                idempotencyKeyResolver,
                "idempotencyKeyResolver must not be null"
        );
        Objects.requireNonNull(
                budgetStateStore,
                "budgetStateStore must not be null"
        );
        this.reservationAccounting = Objects.requireNonNull(
                reservationAccounting,
                "reservationAccounting must not be null"
        );
    }

    DefaultLedgerAdvisor(
            UsageExtractor usageExtractor,
            BudgetEvaluator budgetEvaluator,
            BudgetStateStore budgetStateStore,
            ReservationAccounting reservationAccounting,
            RequestPreflight requestPreflight,
            RequestContextAccessor contextAccessor,
            IdempotencyKeyResolver idempotencyKeyResolver
    ) {
        this(
                null,
                usageExtractor,
                budgetEvaluator,
                budgetStateStore,
                null,
                null,
                LedgerComponents.defaultPricingEvaluator(),
                MissingPricingPolicy.FAIL_CLOSED,
                requestPreflight,
                reservationAccounting,
                contextAccessor,
                idempotencyKeyResolver,
                List.of()
        );
    }

    DefaultLedgerAdvisor(
            UsageExtractor usageExtractor,
            BudgetEvaluator budgetEvaluator,
            BudgetStateStore budgetStateStore,
            ReservationAccounting reservationAccounting,
            RequestPreflight requestPreflight,
            PricingEvaluator pricingEvaluator,
            RequestContextAccessor contextAccessor,
            IdempotencyKeyResolver idempotencyKeyResolver,
            List<PricingMissingListener> pricingMissingListeners
    ) {
        this(
                null,
                usageExtractor,
                budgetEvaluator,
                budgetStateStore,
                null,
                null,
                pricingEvaluator,
                MissingPricingPolicy.FAIL_CLOSED,
                requestPreflight,
                reservationAccounting,
                contextAccessor,
                idempotencyKeyResolver,
                pricingMissingListeners
        );
    }

    @Override
    public ChatClientResponse adviseCall(
            ChatClientRequest request,
            CallAdvisorChain chain
    ) {
        if (requestPreflight == null) {
            return adviseLegacyCall(request, chain);
        }
        return adviseAccountingCall(request, chain);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest request,
            StreamAdvisorChain chain
    ) {
        streamingRequestPolicy.requireSupported(requestPreflight != null);
        return LedgerAdvisor.super.adviseStream(request, chain);
    }

    private ChatClientResponse adviseLegacyCall(
            ChatClientRequest request,
            CallAdvisorChain chain
    ) {
        ChatClientRequest resolvedRequest = before(request, chain);
        ChatClientResponse response = chain.nextCall(resolvedRequest);
        return after(response, chain);
    }

    private ChatClientResponse adviseAccountingCall(
            ChatClientRequest request,
            CallAdvisorChain chain
    ) {
        IdempotencyKey idempotencyKey = idempotencyKeyResolver.resolve(request);
        ChatClientRequest correlatedRequest = contextAccessor.withIdempotencyKey(
                request,
                idempotencyKey
        );
        String requestId = requireCorrelation(
                contextAccessor.requestId(correlatedRequest),
                "request ID"
        );
        String attemptId = requireCorrelation(
                contextAccessor.attemptId(correlatedRequest),
                "attempt ID"
        );
        PreflightCostResult.Bounded costBound;
        try {
            costBound = requestPreflight.resolve(correlatedRequest);
        } catch (MissingPricingException exception) {
            publishPricingMissingBestEffort(exception.getResolution());
            throw exception;
        }
        BudgetDecision decision = budgetEvaluator.evaluate(
                extractTags(correlatedRequest.context()),
                costBound.safeUpperBoundCost()
        );
        validateAdmissionDecision(decision);

        BudgetReservationResult reservationResult = checkAndReserve(
                requestId,
                idempotencyKey,
                costBound,
                decision
        );
        enforceEvaluatorBlock(decision, reservationResult);
        requireAcceptedReservation(reservationResult);
        ReservationId reservationId = reservationResult.reservation().id();
        ChatClientRequest providerRequest = prepareDispatch(
                correlatedRequest,
                reservationResult
        );

        ChatClientResponse response;
        try {
            response = chain.nextCall(providerRequest);
        } catch (RuntimeException downstreamFailure) {
            preservePendingLiability(reservationId, downstreamFailure);
            throw downstreamFailure;
        }
        settle(
                response,
                requestId,
                attemptId,
                reservationId,
                costBound.pricingSnapshot()
        );
        return response;
    }

    private void validateAdmissionDecision(BudgetDecision decision) {
        if (!decision.isAdmissionDecision()) {
            throw new IllegalStateException("budget admission decision is required");
        }
        switch (decision.state()) {
            case ALLOW, WARN, BLOCK -> {
                return;
            }
            case CURRENCY_MISMATCH -> throw new IllegalStateException(
                    "Budget decision currency mismatch: " + decision.reason()
            );
        }
    }

    private void enforceEvaluatorBlock(
            BudgetDecision decision,
            BudgetReservationResult reservationResult
    ) {
        if (decision.state() != BudgetState.BLOCK) {
            return;
        }
        BudgetExceededException failure = new BudgetExceededException(decision);
        releaseCreatedBeforeDispatch(reservationResult, failure);
        throw failure;
    }

    private void requireAcceptedReservation(BudgetReservationResult result) {
        if (!result.isAccepted()) {
            throw new IllegalStateException(
                    "budget reservation rejected: " + result.reason()
            );
        }
    }

    private BudgetReservationResult checkAndReserve(
            String requestId,
            IdempotencyKey idempotencyKey,
            PreflightCostResult.Bounded costBound,
            BudgetDecision decision
    ) {
        BudgetReservationRequest reservation = new BudgetReservationRequest(
                decision.key(),
                decision.limit(),
                costBound.safeUpperBoundCost(),
                requestId,
                idempotencyKey,
                costBound.pricingSnapshot(),
                new ReservationTokenEstimate(
                        costBound.inputEstimatedTokens(),
                        costBound.inputSafeUpperBoundTokens(),
                        costBound.reservedOutputTokens()
                )
        );
        return budgetStateStore.checkAndReserve(reservation);
    }

    private ChatClientRequest prepareDispatch(
            ChatClientRequest request,
            BudgetReservationResult reservationResult
    ) {
        ReservationId reservationId = reservationResult.reservation().id();
        ChatClientRequest providerRequest;
        try {
            providerRequest = contextAccessor.withReservationId(
                    request,
                    reservationId
            );
        } catch (RuntimeException failure) {
            releaseCreatedBeforeDispatch(reservationResult, failure);
            throw failure;
        }

        ReservationTransition transition;
        try {
            transition = reservationAccounting.markInFlight(reservationId);
        } catch (RuntimeException failure) {
            releaseCreatedBeforeDispatch(reservationResult, failure);
            throw failure;
        }
        boolean claimed = transition.status().isApplied()
                && transition.previousState() == ReservationState.RESERVED
                && transition.resultingState() == ReservationState.IN_FLIGHT;
        if (!claimed) {
            IllegalStateException failure = new IllegalStateException(
                    "reservation dispatch was not claimed: " + transition.status()
            );
            if (transition.resultingState() == ReservationState.RESERVED) {
                releaseCreatedBeforeDispatch(reservationResult, failure);
            }
            throw failure;
        }
        return providerRequest;
    }

    private void releaseCreatedBeforeDispatch(
            BudgetReservationResult reservationResult,
            RuntimeException originalFailure
    ) {
        if (reservationResult.status() != io.tokenpilot.budget.ReservationStatus.CREATED) {
            return;
        }
        releaseBeforeDispatch(reservationResult.reservation().id(), originalFailure);
    }

    private void releaseBeforeDispatch(
            ReservationId reservationId,
            RuntimeException originalFailure
    ) {
        try {
            reservationAccounting.releaseBeforeDispatch(reservationId);
        } catch (RuntimeException releaseFailure) {
            originalFailure.addSuppressed(releaseFailure);
        }
    }

    private void preservePendingLiability(
            ReservationId reservationId,
            RuntimeException originalFailure
    ) {
        try {
            markReconciliationRequired(reservationId);
        } catch (RuntimeException reconciliationFailure) {
            originalFailure.addSuppressed(reconciliationFailure);
        }
    }

    private void settle(
            ChatClientResponse response,
            String requestId,
            String attemptId,
            ReservationId reservationId,
            PricingSnapshot pricingSnapshot
    ) {
        TokenUsage usage;
        try {
            usage = usageExtractor.extract(response);
        } catch (RuntimeException ignored) {
            markReconciliationRequiredAfterResponse(reservationId);
            return;
        }
        if (usage.source() == UsageSource.UNAVAILABLE) {
            markReconciliationRequiredAfterResponse(reservationId);
            return;
        }
        try {
            String responseModelId = requireResponseModelId(response);
            ActualUsageCommand actualCommand = new ActualUsageCommand(
                    requestId,
                    attemptId,
                    reservationId,
                    usage,
                    responseModelId
            );
            PricingReconciliationResult reconciliation =
                    pricingEvaluator.determineReconciliation(
                            Optional.of(pricingSnapshot),
                            responseModelId
            );
            if (reconciliation != PricingReconciliationResult.RECONCILED) {
                if (reconciliation
                        == PricingReconciliationResult.RECONCILIATION_REQUIRED) {
                    markPricingReconciliationRequired(
                            actualCommand,
                            ReservationAccountingReason.PRICING_RECONCILIATION_REQUIRED
                    );
                } else {
                    markReconciliationRequiredAfterResponse(
                            reservationId,
                            ReservationAccountingReason.PRICING_RECONCILIATION_REQUIRED
                    );
                }
                return;
            }
            reservationAccounting.commit(actualCommand);
        } catch (RuntimeException ignored) {
            markReconciliationRequiredAfterResponse(reservationId);
        }
    }

    private void markPricingReconciliationRequired(
            ActualUsageCommand command,
            ReservationAccountingReason reason
    ) {
        try {
            reservationAccounting.markReconciliationRequired(
                    command,
                    reason
            );
        } catch (RuntimeException ignored) {
            // Provider response is preserved; accounting listeners observe failures separately.
        }
    }

    private void markReconciliationRequired(ReservationId reservationId) {
        reservationAccounting.markReconciliationRequired(
                reservationId,
                ReservationAccountingReason.ACTUAL_USAGE_UNAVAILABLE
        );
    }

    private void markReconciliationRequiredAfterResponse(
            ReservationId reservationId
    ) {
        markReconciliationRequiredAfterResponse(
                reservationId,
                ReservationAccountingReason.ACTUAL_USAGE_UNAVAILABLE
        );
    }

    private void markReconciliationRequiredAfterResponse(
            ReservationId reservationId,
            ReservationAccountingReason reason
    ) {
        try {
            reservationAccounting.markReconciliationRequired(
                    reservationId,
                    reason
            );
        } catch (RuntimeException ignored) {
            // Provider response is preserved; accounting listeners observe failures separately.
        }
    }

    private String requireResponseModelId(ChatClientResponse response) {
        String modelId = extractMetadataModelId(response);
        if (modelId == null) {
            throw new IllegalStateException("response model ID is unavailable");
        }
        return modelId;
    }

    private String requireCorrelation(String value, String name) {
        if (value == null) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
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
                // budget-enabled adviseCall performs the atomic reservation immediately after this check.
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
