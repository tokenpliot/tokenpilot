package io.tokenpilot.budget;

import io.tokenpilot.core.domain.Cost;

import java.util.Map;


/**
 * 부수 효과 없이 예산 상태를 판단하는 인터페이스입니다.
 * <p>
 * 구현체는 판단 결과를 구조화된 {@link BudgetDecision}으로 반환하며 provider 호출을 직접
 * 차단하거나 알림/metric listener를 호출하지 않습니다. Provider 경계는 반환된 decision을
 * 별도로 집행해야 합니다.
 */
public interface BudgetEvaluator {

  /**
   * 현재 확정 사용량만 조회합니다.
   *
   * @return {@link BudgetDecision.EvaluationType#STATUS}인 조회 전용 결과. 후보 비용이 없으므로
   * provider 호출 허가의 근거로 사용할 수 없습니다.
   */
  BudgetDecision evaluate(Map<String, String> tags);

  /**
   * 후보 요청의 통화가 포함된 안전 상한 비용을 더해 admission 상태를 판단합니다.
   * <p>
   * {@code projectedUsage >= limit}이면 BLOCK입니다. BLOCK과 CURRENCY_MISMATCH도 예외를
   * 던지지 않고 decision으로 반환합니다.
   *
   * @return {@link BudgetDecision.EvaluationType#ADMISSION}인 판단 결과
   */
  BudgetDecision evaluate(
      Map<String, String> tags,
      Cost candidateCost
  );
}
