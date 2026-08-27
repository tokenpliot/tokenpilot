package io.tokenpilot.budget;

import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.PricingSnapshot;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable reservation snapshot created in a budget bucket.
 */
public record BudgetReservation(
        ReservationId id,
        BudgetKey key,
        Cost limit,
        Cost amount,
        String requestId,
        IdempotencyKey idempotencyKey,
        String modelId,
        String pricingPolicyId,
        String catalogVersion,
        Optional<PricingSnapshot> pricingSnapshot,
        Optional<ReservationTokenEstimate> tokenEstimate,
        ReservationState state,
        Instant createdAt
) {

    public BudgetReservation {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(limit, "limit must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        requestId = requireText(requestId, "requestId");
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
        pricingSnapshot = Objects.requireNonNull(
                pricingSnapshot,
                "pricingSnapshot must not be null"
        );
        tokenEstimate = Objects.requireNonNull(
                tokenEstimate,
                "tokenEstimate must not be null"
        );
        if (pricingSnapshot.isPresent()) {
            PricingSnapshot snapshot = pricingSnapshot.orElseThrow();
            requireSnapshotText(modelId, snapshot.modelId(), "modelId");
            requireSnapshotText(
                    pricingPolicyId,
                    snapshot.pricingPolicyId(),
                    "pricingPolicyId"
            );
            requireSnapshotText(
                    catalogVersion,
                    snapshot.catalogVersion(),
                    "catalogVersion"
            );
            if (!limit.currency().equals(snapshot.currency())) {
                throw new IllegalArgumentException(
                        "pricing snapshot must use the budget currency"
                );
            }
        }
    }

    /**
     * @deprecated Compatibility constructor that uses the same value for request ID and idempotency key.
     *             Create new reservations with {@link #reserved(ReservationId, BudgetReservationRequest, Instant)}.
     */
    @Deprecated(since = "0.1.0", forRemoval = false)
    public BudgetReservation(
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
        this(
                id,
                key,
                limit,
                amount,
                idempotencyKey.value(),
                idempotencyKey,
                modelId,
                pricingPolicyId,
                catalogVersion,
                Optional.empty(),
                Optional.empty(),
                state,
                createdAt
        );
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
                request.requestId(),
                request.idempotencyKey(),
                request.modelId(),
                request.pricingPolicyId(),
                request.catalogVersion(),
                request.pricingSnapshot(),
                request.tokenEstimate(),
                ReservationState.RESERVED,
                createdAt
        );
    }

    /**
     * Compares whether a request using the same idempotency key represents the
     * same billing liability as the existing reservation.
     *
     * <p>{@link PricingSnapshot#checkedAt()} is observational metadata from the
     * pricing lookup time and is excluded from comparison. Values that could
     * change the actual price or reservation liability—including model, pricing
     * policy, catalog version, currency, rate, and token estimate—must all match.</p>
     */
    public boolean matches(BudgetReservationRequest request) {
        return key.equals(request.key())
                && limit.equals(request.limit())
                && amount.equals(request.safeUpperBoundCost())
                && requestId.equals(request.requestId())
                && idempotencyKey.equals(request.idempotencyKey())
                && Objects.equals(modelId, request.modelId())
                && Objects.equals(pricingPolicyId, request.pricingPolicyId())
                && Objects.equals(catalogVersion, request.catalogVersion())
                && hasSamePricingTerms(
                        pricingSnapshot,
                        request.pricingSnapshot()
                )
                && tokenEstimate.equals(request.tokenEstimate());
    }

    private static boolean hasSamePricingTerms(
            Optional<PricingSnapshot> existing,
            Optional<PricingSnapshot> candidate
    ) {
        if (existing.isEmpty() || candidate.isEmpty()) {
            return existing.isEmpty() && candidate.isEmpty();
        }

        PricingSnapshot existingSnapshot = existing.orElseThrow();
        PricingSnapshot candidateSnapshot = candidate.orElseThrow();
        return PricingSnapshot.haveSameBillingTerms(
                existingSnapshot,
                candidateSnapshot
        );
    }

    /** Returns whether this reservation belongs to the specified provider request. */
    public boolean belongsTo(String candidateRequestId) {
        return requestId.equals(candidateRequestId);
    }

    private static String optionalText(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireSnapshotText(
            String value,
            String snapshotValue,
            String name
    ) {
        if (!Objects.equals(value, snapshotValue)) {
            throw new IllegalArgumentException(
                    name + " must match the pricing snapshot"
            );
        }
    }
}
