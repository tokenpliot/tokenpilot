package io.tokenpilot.budget;

import io.tokenpilot.core.domain.Cost;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * 예산 bucket의 framework-independent 읽기 snapshot입니다.
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
     * 예약과 미해결 정산 부채를 포함한 admission 기준 사용량입니다.
     */
    public Cost effectiveUsage() {
        return committedCost
                .add(activeReservedCost)
                .add(pendingReconciliationLiability);
    }

    /**
     * 사용량을 반영한 남은 예산입니다. 초과 상태에서는 0입니다.
     */
    public Cost remaining() {
        Cost effectiveUsage = effectiveUsage();
        if (effectiveUsage.compareTo(limit) >= 0) {
            return Cost.zero(limit.currency());
        }
        return Cost.of(limit.value().subtract(effectiveUsage.value()), limit.currency());
    }
}
