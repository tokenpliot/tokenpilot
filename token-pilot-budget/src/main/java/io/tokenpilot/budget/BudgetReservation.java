package io.tokenpilot.budget;

import io.tokenpilot.core.domain.Cost;

import java.time.Instant;
import java.util.Objects;

/**
 * 예산 bucket에 생성된 immutable 예약 snapshot입니다.
 */
public record BudgetReservation(
        ReservationId id,
        BudgetKey key,
        Cost limit,
        Cost amount,
        IdempotencyKey idempotencyKey,
        String modelId,
        String pricingPolicyId,
        String catalogVersion,
        ReservationState state,
        Instant createdAt
) {

    public BudgetReservation {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(limit, "limit must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (limit.value().signum() <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
        if (!limit.currency().equals(amount.currency())) {
            throw new IllegalArgumentException("reservation costs must use the budget currency");
        }
        modelId = optionalText(modelId, "modelId");
        pricingPolicyId = optionalText(pricingPolicyId, "pricingPolicyId");
        catalogVersion = optionalText(catalogVersion, "catalogVersion");
    }

    public static BudgetReservation reserved(
            ReservationId id,
            BudgetReservationRequest request,
            Instant createdAt
    ) {
        Objects.requireNonNull(request, "request must not be null");
        return new BudgetReservation(
                id,
                request.key(),
                request.limit(),
                request.safeUpperBoundCost(),
                request.idempotencyKey(),
                request.modelId(),
                request.pricingPolicyId(),
                request.catalogVersion(),
                ReservationState.RESERVED,
                createdAt
        );
    }

    public boolean matches(BudgetReservationRequest request) {
        return key.equals(request.key())
                && limit.equals(request.limit())
                && amount.equals(request.safeUpperBoundCost())
                && idempotencyKey.equals(request.idempotencyKey())
                && Objects.equals(modelId, request.modelId())
                && Objects.equals(pricingPolicyId, request.pricingPolicyId())
                && Objects.equals(catalogVersion, request.catalogVersion());
    }

    private static String optionalText(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
