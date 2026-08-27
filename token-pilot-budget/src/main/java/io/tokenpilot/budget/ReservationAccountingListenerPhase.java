package io.tokenpilot.budget;

/** Bounded callback phases used to observe accounting listener failures. */
public enum ReservationAccountingListenerPhase {
    ACCOUNTING_APPLIED,
    RECONCILIATION_REQUIRED,
    RESERVATION_EVALUATED,
    RESERVATION_BLOCKED
}
