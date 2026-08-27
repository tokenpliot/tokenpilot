package io.tokenpilot.budget;

/**
 * Result of a budget reservation attempt.
 */
public enum ReservationStatus {
    CREATED,
    REUSED,
    BLOCKED,
    CONFLICT,
    CURRENCY_MISMATCH
}
