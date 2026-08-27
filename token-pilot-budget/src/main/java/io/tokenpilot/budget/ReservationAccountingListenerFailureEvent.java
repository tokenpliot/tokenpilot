package io.tokenpilot.budget;

import java.util.Objects;

/**
 * Event carrying an accounting listener callback failure using only bounded information.
 *
 * <p>The original exception and message are excluded so prompts, provider
 * responses, and credentials do not reach the observation path.</p>
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
