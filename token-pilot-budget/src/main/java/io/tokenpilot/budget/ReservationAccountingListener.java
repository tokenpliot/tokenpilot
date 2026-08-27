package io.tokenpilot.budget;

/**
 * Framework-independent contract for receiving reservation admission and
 * settlement results.
 *
 * <p>Only {@link #onCommitted} remains abstract for source compatibility with
 * existing lambda listeners. The other callbacks are optional extension points
 * for observing results confirmed by the reservation store. Accounting-change
 * callbacks are delivered only for newly applied transitions; reservation
 * callbacks can observe every evaluation and BLOCK result separately.</p>
 */
@FunctionalInterface
public interface ReservationAccountingListener {

    void onCommitted(ReservationAccountingEvent event);

    /**
     * Delivers the bucket snapshot at the same linearization point as the applied
     * accounting transition. Existing listeners delegate to
     * {@link #onCommitted(ReservationAccountingEvent)}.
     */
    default void onAccountingApplied(
            ReservationAccountingEvent event,
            BudgetSnapshot snapshot
    ) {
        onCommitted(event);
    }

    /** Delivers a result where an estimate with unconfirmed actual usage moved to pending liability. */
    default void onReconciliationRequired(
            ReservationReconciliationRequiredEvent event
    ) {
    }

    /** Delivers every result of an atomic reservation attempt. */
    default void onReservationEvaluated(
            BudgetReservationRequest request,
            BudgetReservationResult result
    ) {
    }
    /** Delivers a result blocked during atomic admission without changing state. */
    default void onReservationBlocked(
            BudgetReservationRequest request,
            BudgetReservationResult result
    ) {
    }

    /** Returns a bounded listener type safe to use as a metric tag. */
    default ReservationAccountingListenerType listenerType() {
        return ReservationAccountingListenerType.CUSTOM;
    }
}
