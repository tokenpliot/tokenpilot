package io.tokenpilot.budget;

/**
 * 예약 정산과 admission 차단 결과를 수신하는 framework-independent 계약입니다.
 *
 * <p>회계 변경은 새로 적용된 commit/reconciliation에만 전달됩니다. 예약 결과 callback은
 * {@link ReservationStatus#BLOCKED} 결과에만 사용되며 기존 단일 추상 메서드를 유지하므로
 * 기존 lambda listener와 source 호환됩니다.</p>
 */
@FunctionalInterface
public interface ReservationAccountingListener {

    void onCommitted(ReservationAccountingEvent event);

    /**
     * 적용된 accounting transition과 같은 linearization point의 bucket snapshot을 전달합니다.
     * 기존 listener는 {@link #onCommitted(ReservationAccountingEvent)}로 위임됩니다.
     */
    default void onAccountingApplied(
            ReservationAccountingEvent event,
            BudgetSnapshot snapshot
    ) {
        onCommitted(event);
    }

    /** actual 미확정 estimate가 pending liability로 이동한 결과를 전달합니다. */
    default void onReconciliationRequired(
            ReservationReconciliationRequiredEvent event
    ) {
    }

    /** 원자적 admission에서 상태 변경 없이 차단된 결과를 전달합니다. */
    default void onReservationBlocked(
            BudgetReservationRequest request,
            BudgetReservationResult result
    ) {
    }
}
