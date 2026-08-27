package io.tokenpilot.budget;

import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.PricingSnapshot;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable request for reserving a pre-call safe upper-bound cost in a budget bucket.
 *
 * <p>{@code limit} is the policy snapshot fixed when the bucket is created, and
 * {@code safeUpperBoundCost} is the amount to reserve. Model and pricing
 * identifiers form part of the fingerprint used to detect a changed request
 * payload under the same idempotency key.</p>
 */
public record BudgetReservationRequest(
        BudgetKey key,
        Cost limit,
        Cost safeUpperBoundCost,
        String requestId,
        IdempotencyKey idempotencyKey,
        String modelId,
        String pricingPolicyId,
        String catalogVersion,
        Optional<PricingSnapshot> pricingSnapshot,
        Optional<ReservationTokenEstimate> tokenEstimate
) {

    public BudgetReservationRequest {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(limit, "limit must not be null");
        Objects.requireNonNull(safeUpperBoundCost, "safeUpperBoundCost must not be null");
        requestId = requireText(requestId, "requestId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        if (limit.value().signum() <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
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
            modelId = snapshotText(modelId, snapshot.modelId(), "modelId");
            pricingPolicyId = snapshotText(
                    pricingPolicyId,
                    snapshot.pricingPolicyId(),
                    "pricingPolicyId"
            );
            catalogVersion = snapshotText(
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
     *             New calls should use the canonical constructor with both values explicit.
     */
    @Deprecated(since = "0.1.0", forRemoval = false)
    public BudgetReservationRequest(
            BudgetKey key,
            Cost limit,
            Cost safeUpperBoundCost,
            IdempotencyKey idempotencyKey,
            String modelId,
            String pricingPolicyId,
            String catalogVersion
    ) {
        this(
                key,
                limit,
                safeUpperBoundCost,
                idempotencyKey.value(),
                idempotencyKey,
                modelId,
                pricingPolicyId,
                catalogVersion,
                Optional.empty(),
                Optional.empty()
        );
    }

    /**
     * @deprecated Compatibility constructor that uses the same value for request ID and idempotency key.
     *             New calls should use the canonical constructor with both values explicit.
     */
    @Deprecated(since = "0.1.0", forRemoval = false)
    public BudgetReservationRequest(
            BudgetKey key,
            Cost limit,
            Cost safeUpperBoundCost,
            IdempotencyKey idempotencyKey,
            String modelId,
            String pricingPolicyId,
            String catalogVersion,
            Optional<PricingSnapshot> pricingSnapshot
    ) {
        this(
                key,
                limit,
                safeUpperBoundCost,
                idempotencyKey.value(),
                idempotencyKey,
                modelId,
                pricingPolicyId,
                catalogVersion,
                pricingSnapshot,
                Optional.empty()
        );
    }

    /**
     * @deprecated Compatibility constructor that uses the same value for request ID and idempotency key.
     */
    @Deprecated(since = "0.1.0", forRemoval = false)
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
                idempotencyKey,
                new IdempotencyKey(idempotencyKey),
                null,
                null,
                null,
                Optional.empty(),
                Optional.empty()
        );
    }

    /**
     * @deprecated Compatibility constructor that uses the same value for request ID and idempotency key.
     */
    @Deprecated(since = "0.1.0", forRemoval = false)
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
                idempotencyKey,
                new IdempotencyKey(idempotencyKey),
                modelId,
                pricingPolicyId,
                catalogVersion,
                Optional.empty(),
                Optional.empty()
        );
    }

    public BudgetReservationRequest(
            BudgetKey key,
            Cost limit,
            Cost safeUpperBoundCost,
            String requestId,
            IdempotencyKey idempotencyKey,
            String modelId,
            String pricingPolicyId,
            String catalogVersion,
            Optional<PricingSnapshot> pricingSnapshot
    ) {
        this(
                key,
                limit,
                safeUpperBoundCost,
                requestId,
                idempotencyKey,
                modelId,
                pricingPolicyId,
                catalogVersion,
                pricingSnapshot,
                Optional.empty()
        );
    }

    public BudgetReservationRequest(
            BudgetKey key,
            Cost limit,
            Cost safeUpperBoundCost,
            String requestId,
            IdempotencyKey idempotencyKey,
            PricingSnapshot pricingSnapshot,
            ReservationTokenEstimate tokenEstimate
    ) {
        this(
                key,
                limit,
                safeUpperBoundCost,
                requestId,
                idempotencyKey,
                pricingSnapshot.modelId(),
                pricingSnapshot.pricingPolicyId(),
                pricingSnapshot.catalogVersion(),
                Optional.of(pricingSnapshot),
                Optional.of(tokenEstimate)
        );
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

    private static String snapshotText(
            String value,
            String snapshotValue,
            String name
    ) {
        if (value != null && !value.equals(snapshotValue)) {
            throw new IllegalArgumentException(
                    name + " must match the pricing snapshot"
            );
        }
        return snapshotValue;
    }
}
