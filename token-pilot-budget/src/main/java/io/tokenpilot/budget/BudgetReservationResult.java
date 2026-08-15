package io.tokenpilot.budget;

import java.util.Objects;

/**
 * 원자적 예산 예약 시도의 결과입니다.
 */
public record BudgetReservationResult(
        ReservationStatus status,
        BudgetReservation reservation,
        BudgetSnapshot snapshot,
        String reason
) {

    public BudgetReservationResult {
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if ((status == ReservationStatus.CREATED || status == ReservationStatus.REUSED)
                && reservation == null) {
            throw new IllegalArgumentException(status + " result must include a reservation");
        }
    }

    public ReservationId reservationId() {
        return reservation == null ? null : reservation.id();
    }

    public boolean isAccepted() {
        return status == ReservationStatus.CREATED || status == ReservationStatus.REUSED;
    }

    public static BudgetReservationResult created(
            BudgetReservation reservation,
            BudgetSnapshot snapshot
    ) {
        return new BudgetReservationResult(
                ReservationStatus.CREATED,
                reservation,
                snapshot,
                "예산 예약이 생성되었습니다"
        );
    }

    public static BudgetReservationResult reused(
            BudgetReservation reservation,
            BudgetSnapshot snapshot
    ) {
        return new BudgetReservationResult(
                ReservationStatus.REUSED,
                reservation,
                snapshot,
                "동일 idempotency key의 기존 예약을 재사용했습니다"
        );
    }

    public static BudgetReservationResult blocked(
            BudgetSnapshot snapshot,
            String reason
    ) {
        return new BudgetReservationResult(ReservationStatus.BLOCKED, null, snapshot, reason);
    }

    public static BudgetReservationResult conflict(
            BudgetReservation reservation,
            BudgetSnapshot snapshot,
            String reason
    ) {
        return new BudgetReservationResult(ReservationStatus.CONFLICT, reservation, snapshot, reason);
    }

    public static BudgetReservationResult currencyMismatch(
            BudgetSnapshot snapshot
    ) {
        return new BudgetReservationResult(
                ReservationStatus.CURRENCY_MISMATCH,
                null,
                snapshot,
                "예산 통화와 예약 비용 통화가 일치하지 않습니다"
        );
    }
}
