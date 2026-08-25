package io.tokenpilot.budget;

/** bounded 회계 listener 실패를 관찰하는 best-effort hook입니다. */
@FunctionalInterface
public interface ReservationAccountingListenerErrorHandler {

    void onFailure(ReservationAccountingListenerFailureEvent event);

    static ReservationAccountingListenerErrorHandler noOp() {
        return ignored -> { };
    }
}
