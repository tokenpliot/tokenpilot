package io.tokenpilot.budget;

/** Best-effort hook for observing bounded accounting listener failures. */
@FunctionalInterface
public interface ReservationAccountingListenerErrorHandler {

    void onFailure(ReservationAccountingListenerFailureEvent event);

    static ReservationAccountingListenerErrorHandler noOp() {
        return ignored -> { };
    }
}
