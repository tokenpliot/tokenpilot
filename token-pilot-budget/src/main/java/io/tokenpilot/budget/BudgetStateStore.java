package io.tokenpilot.budget;

import io.tokenpilot.core.domain.Cost;

/**
 * resolved {@link BudgetKey}별 누적 비용과 limit/currency snapshot을 관리합니다.
 */
public interface BudgetStateStore {

  /**
   * 기존 확정 비용 조회 API입니다. 예약 금액은 포함하지 않습니다.
   *
   * <p>새 provider admission 경계는 {@link #snapshot(BudgetKey, Cost)} 또는
   * {@link #checkAndReserve(BudgetKey, Cost, Cost, String)}를 사용해야 합니다.</p>
   */
  Cost getAccumulatedCost(BudgetKey key, Cost limit);

  /**
   * 기존 확정 비용 누적 API입니다. 예약 lifecycle을 변경하지 않습니다.
   */
  void addCost(BudgetKey key, Cost limit, Cost amount);

  /**
   * 조회와 안전 상한 예약을 하나의 원자적 연산으로 수행합니다.
   *
   * <p>기존 구현체와의 source 호환성을 위해 기본 구현은 지원하지 않음을 명시적으로
   * 반환합니다. 예약을 지원하는 구현체는 이 메서드 또는 요청 overload를 구현해야 합니다.</p>
   *
   * @param key 예산 bucket 식별자
   * @param limit bucket에 고정할 예산 limit snapshot
   * @param safeUpperBoundCost 예약할 보수적 비용 상한
   * @param idempotencyKey 중복 요청 식별자
   * @return 생성·재사용·차단·충돌·통화 불일치 결과
   */
  default BudgetReservationResult checkAndReserve(
      BudgetKey key,
      Cost limit,
      Cost safeUpperBoundCost,
      String idempotencyKey
  ) {
    return checkAndReserve(new BudgetReservationRequest(
        key,
        limit,
        safeUpperBoundCost,
        idempotencyKey
    ));
  }

  /**
   * typed idempotency key를 사용하는 원자적 예약 overload입니다.
   */
  default BudgetReservationResult checkAndReserve(
      BudgetKey key,
      Cost limit,
      Cost safeUpperBoundCost,
      IdempotencyKey idempotencyKey
  ) {
    return checkAndReserve(new BudgetReservationRequest(
        key,
        limit,
        safeUpperBoundCost,
        idempotencyKey,
        null,
        null,
        null
    ));
  }

  /**
   * 모델·가격 snapshot metadata를 포함한 원자적 예약 요청입니다.
   */
  default BudgetReservationResult checkAndReserve(BudgetReservationRequest request) {
    throw new UnsupportedOperationException(
        "This BudgetStateStore does not support atomic reservations"
    );
  }

  /**
   * 예약과 미해결 정산 부채를 포함한 읽기 snapshot입니다.
   * 기존 저장소는 확정 비용만 포함한 호환 snapshot을 반환할 수 있습니다.
   */
  default BudgetSnapshot snapshot(BudgetKey key, Cost limit) {
    Cost committedCost = getAccumulatedCost(key, limit);
    return new BudgetSnapshot(
        key,
        limit,
        committedCost,
        Cost.zero(limit.currency()),
        Cost.zero(limit.currency()),
        java.util.Set.of()
    );
  }

  /**
   * {@link #snapshot(BudgetKey, Cost)}의 명시적인 query alias입니다.
   */
  default BudgetSnapshot getSnapshot(BudgetKey key, Cost limit) {
    return snapshot(key, limit);
  }
}
