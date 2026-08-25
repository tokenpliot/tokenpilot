package io.tokenpilot.springai.internal;

import io.tokenpilot.budget.IdempotencyKey;
import io.tokenpilot.budget.ReservationId;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;

import java.util.Objects;

/** Spring AI request context에서 Token Pilot correlation 값을 읽고 추가합니다. */
final class RequestContextAccessor {

    static final String REQUEST_ID_CONTEXT_KEY = "tokenpilot.request.id";
    static final String ATTEMPT_ID_CONTEXT_KEY = "tokenpilot.attempt.id";
    static final String IDEMPOTENCY_CONTEXT_KEY = "tokenpilot.idempotency.key";
    static final String RESERVATION_ID_CONTEXT_KEY = "tokenpilot.reservation.id";

    @Nullable String requestId(ChatClientRequest request) {
        return readContextValue(request, REQUEST_ID_CONTEXT_KEY, String.class);
    }

    @Nullable String attemptId(ChatClientRequest request) {
        return readContextValue(request, ATTEMPT_ID_CONTEXT_KEY, String.class);
    }

    @Nullable IdempotencyKey idempotencyKey(ChatClientRequest request) {
        return readContextValue(request, IDEMPOTENCY_CONTEXT_KEY, IdempotencyKey.class);
    }

    @Nullable ReservationId reservationId(ChatClientRequest request) {
        return readContextValue(request, RESERVATION_ID_CONTEXT_KEY, ReservationId.class);
    }

    ChatClientRequest withIdempotencyKey(
            ChatClientRequest request,
            IdempotencyKey idempotencyKey
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");

        IdempotencyKey existingIdempotencyKey = idempotencyKey(request);
        if (existingIdempotencyKey == null) {
            return request.mutate()
                    .context(IDEMPOTENCY_CONTEXT_KEY, idempotencyKey)
                    .build();
        }
        if (!existingIdempotencyKey.equals(idempotencyKey)) {
            throw new IllegalStateException(
                    IDEMPOTENCY_CONTEXT_KEY + " cannot be changed"
            );
        }
        return request;
    }

    ChatClientRequest withReservationId(
            ChatClientRequest request,
            ReservationId reservationId
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(reservationId, "reservationId must not be null");

        ReservationId existingReservationId = reservationId(request);
        if (existingReservationId == null) {
            return request.mutate()
                    .context(RESERVATION_ID_CONTEXT_KEY, reservationId)
                    .build();
        }
        if (!existingReservationId.equals(reservationId)) {
            throw new IllegalStateException(
                    RESERVATION_ID_CONTEXT_KEY + " cannot be changed"
            );
        }
        return request;
    }

    private <T> @Nullable T readContextValue(
            ChatClientRequest request,
            String key,
            Class<T> valueType
    ) {
        Objects.requireNonNull(request, "request must not be null");

        if (!request.context().containsKey(key)) {
            return null;
        }

        Object value = request.context().get(key);
        if (!valueType.isInstance(value)) {
            throw new IllegalArgumentException(
                    key + " must contain " + valueType.getSimpleName()
            );
        }
        return valueType.cast(value);
    }
}
