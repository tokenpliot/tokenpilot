package io.tokenpilot.budget;

import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.PricingSnapshot;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * 한 예약의 estimate와 actual 비용을 연결한 회계 정산 결과입니다.
 */
public record ReservationReconciliation(
        String requestId,
        String attemptId,
        ReservationId reservationId,
        BudgetKey budgetKey,
        String responseModelId,
        /** 예약 시점에 estimate를 계산한 request pricing snapshot입니다. */
        PricingSnapshot pricingSnapshot,
        /** provider response model의 actual 비용 계산에 사용한 pricing snapshot입니다. */
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
     * 기존 request pricing snapshot만 사용한 정산 결과를 만드는 호환 생성자입니다.
     * request와 response model이 같은 기존 경로에서는 두 snapshot이 동일합니다.
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

    /** 예약 시점 pricing snapshot의 request model입니다. */
    public String requestModelId() {
        return pricingSnapshot.modelId();
    }

    /** actual provider usage에 적용한 response model pricing policy입니다. */
    public String actualPricingPolicyId() {
        return actualPricingSnapshot.pricingPolicyId();
    }

    /** actual provider usage에 적용한 response model catalog version입니다. */
    public String actualCatalogVersion() {
        return actualPricingSnapshot.catalogVersion();
    }

    /** 예약 시점 pricing policy 식별자입니다. */
    public String pricingPolicyId() {
        return pricingSnapshot.pricingPolicyId();
    }

    /** 예약 시점 model catalog version입니다. */
    public String catalogVersion() {
        return pricingSnapshot.catalogVersion();
    }

    /** actual에서 estimate를 뺀 signed 비용 차이입니다. */
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

    /** estimate와 actual이 사용하는 통화입니다. */
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
