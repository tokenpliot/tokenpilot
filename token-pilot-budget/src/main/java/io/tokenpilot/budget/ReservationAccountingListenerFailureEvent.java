package io.tokenpilot.budget;

import java.util.Objects;

/**
 * 회계 listener callback 실패를 bounded 정보만으로 전달하는 이벤트입니다.
 *
 * <p>원본 예외와 메시지를 포함하지 않아 prompt, provider 응답 또는 자격 증명이
 * 관측 경로로 전달되지 않습니다.</p>
 */
public record ReservationAccountingListenerFailureEvent(
        ReservationAccountingListenerType listenerType,
        ReservationAccountingListenerPhase phase
) {

    public ReservationAccountingListenerFailureEvent {
        Objects.requireNonNull(listenerType, "listenerType must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
    }
}
