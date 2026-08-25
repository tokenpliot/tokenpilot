package io.tokenpilot.budget;

/**
 * 예약 admission과 정산 결과를 수신하는 framework-independent 계약입니다.
 *
 * <p>기존 lambda listener와의 source 호환성을 위해 {@link #onCommitted}만 추상
 * 메서드로 유지합니다. 나머지 callback은 예약 저장소가 확정한 결과를 관찰하기 위한
 * 선택적 확장점입니다. 회계 변경 callback은 새로 적용된 전이에만 전달되고, 예약
 * callback은 모든 평가 결과와 BLOCK 결과를 각각 관찰할 수 있습니다.</p>
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

    /** 원자적 예약 시도의 모든 결과를 전달합니다. */
    default void onReservationEvaluated(
            BudgetReservationRequest request,
            BudgetReservationResult result
    ) {
    }
    /** 원자적 admission에서 상태 변경 없이 차단된 결과를 전달합니다. */
    default void onReservationBlocked(
            BudgetReservationRequest request,
            BudgetReservationResult result
    ) {
    }

    /** metric tag에 안전한 제한된 listener 종류를 반환합니다. */
    default ReservationAccountingListenerType listenerType() {
        return ReservationAccountingListenerType.CUSTOM;
    }
}
