package io.tokenpilot.budget;

/**
 * 예약 승인과 후속 회계 전이를 같은 원자적 상태에서 처리하는 budget store입니다.
 *
 * <p>Spring 같은 DI container는 이 합성 계약을 단일 bean으로 노출해
 * {@link BudgetStateStore}와 {@link ReservationAccounting}이 서로 다른 저장소로
 * 연결되는 것을 방지할 수 있습니다.</p>
 */
public interface AtomicBudgetStateStore
    extends BudgetStateStore, ReservationAccounting {
}
