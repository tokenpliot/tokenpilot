package io.tokenpilot.budget;

/** 회계 listener 실패 관측에 사용하는 제한된 callback 단계입니다. */
public enum ReservationAccountingListenerPhase {
    ACCOUNTING_APPLIED,
    RECONCILIATION_REQUIRED,
    RESERVATION_EVALUATED,
    RESERVATION_BLOCKED
}
