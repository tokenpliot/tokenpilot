package io.tokenpilot.budget;

import java.util.Objects;
import java.util.Optional;

/** Atomic accounting result moved to pending liability because actual usage was unavailable. */
public record ReservationReconciliationRequiredEvent(
        ReservationId reservationId,
        BudgetKey budgetKey,
        ReservationTransition transition,
        ReservationAccountingReason reason,
        BudgetSnapshot snapshot,
        Optional<ActualUsageCommand> pendingActualUsage
) {

    public ReservationReconciliationRequiredEvent {
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        Objects.requireNonNull(budgetKey, "budgetKey must not be null");
        Objects.requireNonNull(transition, "transition must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        pendingActualUsage = Objects.requireNonNull(
                pendingActualUsage,
                "pendingActualUsage must not be null"
        );
        if (!transition.status().isApplied()
                || transition.resultingState()
                != ReservationState.RECONCILIATION_REQUIRED) {
            throw new IllegalArgumentException(
                    "event requires an applied RECONCILIATION_REQUIRED transition"
            );
        }
        if (reason != ReservationAccountingReason.ACTUAL_USAGE_UNAVAILABLE
                && reason != ReservationAccountingReason.PRICING_RECONCILIATION_REQUIRED
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
        if (pendingActualUsage.isPresent()) {
            ActualUsageCommand command = pendingActualUsage.orElseThrow();
            if (!reservationId.equals(command.reservationId())) {
                throw new IllegalArgumentException(
                        "pending actual and event must use the same reservation"
                );
            }
            if (reason != ReservationAccountingReason.PRICING_RECONCILIATION_REQUIRED) {
                throw new IllegalArgumentException(
                        "pending actual requires pricing reconciliation reason"
                );
            }
        }
    }

    /** Compatibility constructor for the former actual-unavailable event. */
    public ReservationReconciliationRequiredEvent(
            ReservationId reservationId,
            BudgetKey budgetKey,
            ReservationTransition transition,
            ReservationAccountingReason reason,
            BudgetSnapshot snapshot
    ) {
        this(
                reservationId,
                budgetKey,
                transition,
                reason,
                snapshot,
                Optional.empty()
        );
    }
}
