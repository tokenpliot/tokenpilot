package io.tokenpilot.notification;

import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetState;
import io.tokenpilot.budget.BudgetThreshold;
import io.tokenpilot.core.domain.Cost;

import java.util.Map;

/**
 * 예산 임계치 도달 시 발생하는 알림 이벤트입니다.
 *
 * @param key 예산 bucket 식별자
 * @param threshold 도달한 임계치
 * @param state 예산 상태
 * @param reason 상태 설명
 * @param projectedUsage 후보 비용을 포함한 예상 사용량
 * @param limit 예산 한도
 * @param tags 알림에 전달할 태그
 *
 * <p><strong>Migration note:</strong> 후보 비용 포함 사용량의 의미를 명확히 하기 위해
 * record component 이름을 {@code currentUsage}에서 {@code projectedUsage}로 변경했습니다.
 * 기존 handler를 위한 {@link #currentUsage()} 호환 accessor는 0.1.x 동안 유지하며
 * 0.2.0에서 제거할 예정입니다.
 */
public record BudgetNotificationEvent(
    BudgetKey key,
    BudgetThreshold threshold,
    BudgetState state,
    String reason,
    Cost projectedUsage,
    Cost limit,
    Map<String, String> tags
) {

  /**
   * @return {@link #projectedUsage()}와 동일한 후보 비용 포함 예상 사용량
   * @deprecated 후보 비용 포함 사용량은 {@link #projectedUsage()}를 사용하세요.
   */
  @Deprecated(since = "0.1.0", forRemoval = true)
  public Cost currentUsage() {
    return projectedUsage;
  }
}
