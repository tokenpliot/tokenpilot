package io.tokenpilot.budget;

import io.tokenpilot.core.domain.Cost;

import java.util.Objects;

/**
 * 호출 전 안전 상한 비용을 예산 bucket에 예약하기 위한 immutable 요청입니다.
 *
 * <p>{@code limit}은 bucket 생성 시 고정되는 정책 snapshot이고,
 * {@code safeUpperBoundCost}는 예약할 실제 금액입니다. 모델과 가격 식별자는
 * 같은 idempotency key의 요청 payload가 바뀌었는지 검증하는 fingerprint로 사용됩니다.</p>
 */
public record BudgetReservationRequest(
        BudgetKey key,
        Cost limit,
        Cost safeUpperBoundCost,
        IdempotencyKey idempotencyKey,
        String modelId,
        String pricingPolicyId,
        String catalogVersion
) {

    public BudgetReservationRequest {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(limit, "limit must not be null");
        Objects.requireNonNull(safeUpperBoundCost, "safeUpperBoundCost must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        if (limit.value().signum() <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
        modelId = optionalText(modelId, "modelId");
        pricingPolicyId = optionalText(pricingPolicyId, "pricingPolicyId");
        catalogVersion = optionalText(catalogVersion, "catalogVersion");
    }

    public BudgetReservationRequest(
            BudgetKey key,
            Cost limit,
            Cost safeUpperBoundCost,
            String idempotencyKey
    ) {
        this(
                key,
                limit,
                safeUpperBoundCost,
                new IdempotencyKey(idempotencyKey),
                null,
                null,
                null
        );
    }

    public BudgetReservationRequest(
            BudgetKey key,
            Cost limit,
            Cost safeUpperBoundCost,
            String idempotencyKey,
            String modelId,
            String pricingPolicyId,
            String catalogVersion
    ) {
        this(
                key,
                limit,
                safeUpperBoundCost,
                new IdempotencyKey(idempotencyKey),
                modelId,
                pricingPolicyId,
                catalogVersion
        );
    }

    private static String optionalText(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
