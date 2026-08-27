package io.tokenpilot.budget;

import io.tokenpilot.core.domain.Cost;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Framework-independent read snapshot of a budget bucket.
 */
public record BudgetSnapshot(
        BudgetKey key,
        Cost limit,
        Cost committedCost,
        Cost activeReservedCost,
        Cost pendingReconciliationLiability,
        Set<ReservationId> activeReservationIds
) {

    public BudgetSnapshot {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(limit, "limit must not be null");
        Objects.requireNonNull(committedCost, "committedCost must not be null");
        Objects.requireNonNull(activeReservedCost, "activeReservedCost must not be null");
        Objects.requireNonNull(
                pendingReconciliationLiability,
                "pendingReconciliationLiability must not be null"
        );
        Objects.requireNonNull(activeReservationIds, "activeReservationIds must not be null");
        if (limit.value().signum() <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
        if (!limit.currency().equals(committedCost.currency())
                || !limit.currency().equals(activeReservedCost.currency())
                || !limit.currency().equals(pendingReconciliationLiability.currency())) {
            throw new IllegalArgumentException("budget snapshot costs must use the same currency");
        }
        activeReservationIds = Collections.unmodifiableSet(Set.copyOf(activeReservationIds));
    }

    public static BudgetSnapshot empty(BudgetKey key, Cost limit) {
        Cost zero = Cost.zero(limit.currency());
        return new BudgetSnapshot(key, limit, zero, zero, zero, Set.of());
    }

    /**
     * Usage used for admission, including reservations and unresolved reconciliation liability.
     */
    public Cost effectiveUsage() {
        return committedCost
                .add(activeReservedCost)
                .add(pendingReconciliationLiability);
    }

    /**
     * Remaining budget after usage. Returns zero when the budget is exceeded.
     */
    public Cost remaining() {
        Cost effectiveUsage = effectiveUsage();
        if (effectiveUsage.compareTo(limit) >= 0) {
            return Cost.zero(limit.currency());
        }
        return Cost.of(limit.value().subtract(effectiveUsage.value()), limit.currency());
    }
}
