package io.tokenpilot.budget;

import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.PricingSnapshot;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Accounting settlement result linking one reservation's estimate and actual cost.
 */
public record ReservationReconciliation(
        String requestId,
        String attemptId,
        ReservationId reservationId,
        BudgetKey budgetKey,
        String responseModelId,
        /** Request pricing snapshot used to calculate the estimate at reservation time. */
        PricingSnapshot pricingSnapshot,
        /** Pricing snapshot used to calculate actual cost for the provider response model. */
        PricingSnapshot actualPricingSnapshot,
        ReservationTokenEstimate tokenEstimate,
        ReservationActualTokens actualTokens,
        Cost estimate,
        Cost actual,
        boolean overLimit,
        ReservationTransition transition,
        ReservationAccountingReason reason
) {

    public ReservationReconciliation {
        requestId = requireText(requestId, "requestId");
        attemptId = requireText(attemptId, "attemptId");
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        Objects.requireNonNull(budgetKey, "budgetKey must not be null");
        responseModelId = requireText(responseModelId, "responseModelId");
        Objects.requireNonNull(pricingSnapshot, "pricingSnapshot must not be null");
        Objects.requireNonNull(
                actualPricingSnapshot,
                "actualPricingSnapshot must not be null"
        );
        Objects.requireNonNull(tokenEstimate, "tokenEstimate must not be null");
        Objects.requireNonNull(actualTokens, "actualTokens must not be null");
        Objects.requireNonNull(estimate, "estimate must not be null");
        Objects.requireNonNull(actual, "actual must not be null");
        Objects.requireNonNull(transition, "transition must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (!estimate.currency().equals(actual.currency())) {
            throw new IllegalArgumentException(
                    "estimate and actual must use the same currency"
            );
        }
        if (!estimate.currency().equals(pricingSnapshot.currency())
                || !actual.currency().equals(actualPricingSnapshot.currency())) {
            throw new IllegalArgumentException(
                    "reconciliation costs must use the pricing snapshot currency"
            );
        }
        if (!responseModelId.equals(actualPricingSnapshot.modelId())) {
            throw new IllegalArgumentException(
                    "responseModelId must match the actual pricing snapshot model"
            );
        }
    }

    /**
     * Compatibility constructor that creates a settlement result using only the
     * existing request pricing snapshot. In the existing path where request and
     * response models are the same, both snapshots are identical.
     */
    public ReservationReconciliation(
            String requestId,
            String attemptId,
            ReservationId reservationId,
            BudgetKey budgetKey,
            String responseModelId,
            PricingSnapshot pricingSnapshot,
            ReservationTokenEstimate tokenEstimate,
            ReservationActualTokens actualTokens,
            Cost estimate,
            Cost actual,
            boolean overLimit,
            ReservationTransition transition,
            ReservationAccountingReason reason
    ) {
        this(
                requestId,
                attemptId,
                reservationId,
                budgetKey,
                responseModelId,
                pricingSnapshot,
                actualSnapshotForResponseModel(pricingSnapshot, responseModelId),
                tokenEstimate,
                actualTokens,
                estimate,
                actual,
                overLimit,
                transition,
                reason
        );
    }

    private static PricingSnapshot actualSnapshotForResponseModel(
            PricingSnapshot requestSnapshot,
            String responseModelId
    ) {
        if (requestSnapshot.modelId().equals(responseModelId)) {
            return requestSnapshot;
        }
        return new PricingSnapshot(
                responseModelId,
                requestSnapshot.pricingPolicyId(),
                requestSnapshot.catalogVersion(),
                requestSnapshot.checkedAt(),
                requestSnapshot.rates(),
                requestSnapshot.currency()
        );
    }

    /** Request model in the reservation-time pricing snapshot. */
    public String requestModelId() {
        return pricingSnapshot.modelId();
    }

    /** Pricing policy applied to actual provider usage for the response model. */
    public String actualPricingPolicyId() {
        return actualPricingSnapshot.pricingPolicyId();
    }

    /** Catalog version applied to actual provider usage for the response model. */
    public String actualCatalogVersion() {
        return actualPricingSnapshot.catalogVersion();
    }

    /** Pricing policy identifier at reservation time. */
    public String pricingPolicyId() {
        return pricingSnapshot.pricingPolicyId();
    }

    /** Model catalog version at reservation time. */
    public String catalogVersion() {
        return pricingSnapshot.catalogVersion();
    }

    /** Signed cost difference obtained by subtracting the estimate from actual cost. */
    public BigDecimal delta() {
        return actual.value().subtract(estimate.value());
    }

    public long inputTokenDelta() {
        return Math.subtractExact(
                actualTokens.inputTokens(),
                tokenEstimate.inputEstimatedTokens()
        );
    }

    public long outputTokenDelta() {
        return Math.subtractExact(
                actualTokens.outputTokens(),
                tokenEstimate.reservedOutputTokens()
        );
    }

    public long totalTokenDelta() {
        long estimatedTotal = Math.addExact(
                tokenEstimate.inputEstimatedTokens(),
                tokenEstimate.reservedOutputTokens()
        );
        return Math.subtractExact(actualTokens.totalTokens(), estimatedTotal);
    }

    /** Currency used by the estimate and actual cost. */
    public Currency currency() {
        return actual.currency();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
