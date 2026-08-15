package io.tokenpilot.budget;

import java.util.UUID;

/**
 * 예산 예약을 식별하는 불변 ID입니다.
 */
public record ReservationId(String value) {

    public ReservationId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    public static ReservationId random() {
        return new ReservationId(UUID.randomUUID().toString());
    }

    @Override
    public String toString() {
        return value;
    }
}
