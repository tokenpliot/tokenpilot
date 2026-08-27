package io.tokenpilot.budget;

import java.util.UUID;

/**
 * Immutable ID identifying a budget reservation.
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
