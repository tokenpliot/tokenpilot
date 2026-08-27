package io.tokenpilot.budget;

/** Bounded reasons used by accounting commands and settlement results. */
public enum ReservationAccountingReason {
    ACTUAL_USAGE_REPORTED,
    LATE_ACTUAL_USAGE_REPORTED,
    ACTUAL_USAGE_UNAVAILABLE,
    PRICING_RECONCILIATION_REQUIRED,
    CALLBACK_TIMED_OUT,
    CANCELLED_BEFORE_DISPATCH,
    PROVIDER_CONFIRMED_UNBILLED,
    MANUAL_WRITE_OFF,
    ACTUAL_USAGE_UNRECOVERABLE
}
