package io.tokenpilot.budget;

import java.util.Objects;

/** actual usage를 확보하지 못해 pending liability로 이동한 원자적 회계 결과입니다. */
public record ReservationReconciliationRequiredEvent(
        ReservationId reservationId,
        BudgetKey budgetKey,
        ReservationTransition transition,
        ReservationAccountingReason reason,
        BudgetSnapshot snapshot
) {

    public ReservationReconciliationRequiredEvent {
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        Objects.requireNonNull(budgetKey, "budgetKey must not be null");
        Objects.requireNonNull(transition, "transition must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (!transition.status().isApplied()
                || transition.resultingState()
                != ReservationState.RECONCILIATION_REQUIRED) {
            throw new IllegalArgumentException(
                    "event requires an applied RECONCILIATION_REQUIRED transition"
            );
        }
        if (reason != ReservationAccountingReason.ACTUAL_USAGE_UNAVAILABLE
                && reason != ReservationAccountingReason.CALLBACK_TIMED_OUT) {
            throw new IllegalArgumentException(
                    "reason must require later actual reconciliation"
            );
        }
        if (!budgetKey.equals(snapshot.key())) {
            throw new IllegalArgumentException(
                    "event and snapshot must use the same budget key"
            );
        }
    }
}
