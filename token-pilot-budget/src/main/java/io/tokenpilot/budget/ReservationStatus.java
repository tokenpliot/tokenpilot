package io.tokenpilot.budget;

/**
 * 예산 예약 시도 결과입니다.
 */
public enum ReservationStatus {
    CREATED,
    REUSED,
    BLOCKED,
    CONFLICT,
    CURRENCY_MISMATCH
}
