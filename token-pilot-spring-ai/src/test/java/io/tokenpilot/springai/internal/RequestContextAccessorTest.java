package io.tokenpilot.springai.internal;

import io.tokenpilot.budget.IdempotencyKey;
import io.tokenpilot.budget.ReservationId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestContextAccessorTest {

    private final RequestContextAccessor accessor = new RequestContextAccessor();

    @Test
    @DisplayName("caller가 제공한 request correlation을 namespaced context에서 읽는다")
    void readsCallerProvidedRequestCorrelation() {
        IdempotencyKey idempotencyKey = new IdempotencyKey("idempotency-1");
        ChatClientRequest request = requestWithContext(Map.of(
                RequestContextAccessor.REQUEST_ID_CONTEXT_KEY, "request-1",
                RequestContextAccessor.ATTEMPT_ID_CONTEXT_KEY, "attempt-1",
                RequestContextAccessor.IDEMPOTENCY_CONTEXT_KEY, idempotencyKey
        ));

        assertThat(accessor.requestId(request)).isEqualTo("request-1");
        assertThat(accessor.attemptId(request)).isEqualTo("attempt-1");
        assertThat(accessor.idempotencyKey(request)).isEqualTo(idempotencyKey);
        assertThat(accessor.reservationId(request)).isNull();
    }

    @Test
    @DisplayName("context에 없는 correlation 값은 null이다")
    void returnsNullForMissingCorrelation() {
        ChatClientRequest request = requestWithContext(Map.of());

        assertThat(accessor.requestId(request)).isNull();
        assertThat(accessor.attemptId(request)).isNull();
        assertThat(accessor.idempotencyKey(request)).isNull();
        assertThat(accessor.reservationId(request)).isNull();
    }

    @Test
    @DisplayName("context에 존재하는 correlation 값의 타입이 다르면 거부한다")
    void rejectsCorrelationWithWrongType() {
        ChatClientRequest request = requestWithContext(Map.of(
                RequestContextAccessor.IDEMPOTENCY_CONTEXT_KEY,
                "idempotency-1"
        ));

        assertThatThrownBy(() -> accessor.idempotencyKey(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(RequestContextAccessor.IDEMPOTENCY_CONTEXT_KEY);
    }

    @Test
    @DisplayName("request mutation 이후에도 correlation과 application context를 보존한다")
    void preservesCorrelationAcrossRequestMutation() {
        IdempotencyKey idempotencyKey = new IdempotencyKey("idempotency-1");
        ChatClientRequest request = requestWithContext(Map.of(
                RequestContextAccessor.REQUEST_ID_CONTEXT_KEY, "request-1",
                RequestContextAccessor.ATTEMPT_ID_CONTEXT_KEY, "attempt-1",
                RequestContextAccessor.IDEMPOTENCY_CONTEXT_KEY, idempotencyKey,
                "application.context", "application-value"
        ));

        ChatClientRequest mutated = request.mutate()
                .context("application.mutated", true)
                .build();

        assertThat(accessor.requestId(mutated)).isEqualTo("request-1");
        assertThat(accessor.attemptId(mutated)).isEqualTo("attempt-1");
        assertThat(accessor.idempotencyKey(mutated)).isEqualTo(idempotencyKey);
        assertThat(mutated.context())
                .containsEntry("application.context", "application-value")
                .containsEntry("application.mutated", true);
    }

    @Test
    @DisplayName("reservation ID를 추가해도 기존 correlation과 원본 request를 보존한다")
    void addsReservationIdWithoutChangingExistingContext() {
        IdempotencyKey idempotencyKey = new IdempotencyKey("idempotency-1");
        ReservationId reservationId = new ReservationId("reservation-1");
        ChatClientRequest request = requestWithContext(Map.of(
                RequestContextAccessor.REQUEST_ID_CONTEXT_KEY, "request-1",
                RequestContextAccessor.ATTEMPT_ID_CONTEXT_KEY, "attempt-1",
                RequestContextAccessor.IDEMPOTENCY_CONTEXT_KEY, idempotencyKey,
                "application.context", "application-value"
        ));

        ChatClientRequest reservedRequest = accessor.withReservationId(
                request,
                reservationId
        );

        assertThat(accessor.reservationId(request)).isNull();
        assertThat(accessor.reservationId(reservedRequest)).isEqualTo(reservationId);
        assertThat(accessor.requestId(reservedRequest)).isEqualTo("request-1");
        assertThat(accessor.attemptId(reservedRequest)).isEqualTo("attempt-1");
        assertThat(accessor.idempotencyKey(reservedRequest)).isEqualTo(idempotencyKey);
        assertThat(reservedRequest.context())
                .containsEntry("application.context", "application-value");
    }

    @Test
    @DisplayName("같은 reservation ID를 다시 추가하면 기존 correlation을 유지한다")
    void preservesMatchingReservationId() {
        ReservationId reservationId = new ReservationId("reservation-1");
        ChatClientRequest reservedRequest = requestWithContext(Map.of(
                RequestContextAccessor.RESERVATION_ID_CONTEXT_KEY,
                reservationId
        ));

        ChatClientRequest result = accessor.withReservationId(
                reservedRequest,
                reservationId
        );

        assertThat(accessor.reservationId(result)).isEqualTo(reservationId);
    }

    @Test
    @DisplayName("다른 reservation ID로 기존 correlation을 변경할 수 없다")
    void rejectsConflictingReservationId() {
        ChatClientRequest reservedRequest = requestWithContext(Map.of(
                RequestContextAccessor.RESERVATION_ID_CONTEXT_KEY,
                new ReservationId("reservation-1")
        ));

        assertThatThrownBy(() -> accessor.withReservationId(
                reservedRequest,
                new ReservationId("reservation-2")
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RequestContextAccessor.RESERVATION_ID_CONTEXT_KEY);
    }

    private ChatClientRequest requestWithContext(Map<String, Object> context) {
        return new ChatClientRequest(
                new Prompt("user question"),
                new HashMap<>(context)
        );
    }
}
