package io.tokenpilot.notification;

import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetState;
import io.tokenpilot.budget.BudgetThreshold;
import io.tokenpilot.core.domain.Cost;

import java.util.Map;
import java.util.Objects;

/**
 * 예산 임계치 도달 시 발생하는 알림 이벤트입니다.
 *
 * @param key 예산 bucket 식별자
 * @param threshold 도달한 임계치
 * @param state 예산 상태
 * @param reason 상태 설명
 * @param usage 알림을 만든 accounting 또는 admission 결과의 사용량
 * @param limit 예산 한도
 * @param source 알림을 만든 원자적 결과 종류
 *
 * <p>이 이벤트는 prompt, raw provider response, API key, 임의 tag map을 포함하지 않습니다.
 * 기존 handler를 위한 {@link #projectedUsage()}, {@link #currentUsage()}, {@link #tags()}
 * 호환 accessor는 0.1.x 동안 유지하며 0.2.0에서 제거할 예정입니다.</p>
 */
public record BudgetNotificationEvent(
    BudgetKey key,
    BudgetThreshold threshold,
    BudgetState state,
    String reason,
    Cost usage,
    Cost limit,
    BudgetNotificationSource source
) {

  public BudgetNotificationEvent {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(threshold, "threshold must not be null");
    Objects.requireNonNull(state, "state must not be null");
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
    Objects.requireNonNull(usage, "usage must not be null");
    Objects.requireNonNull(limit, "limit must not be null");
    Objects.requireNonNull(source, "source must not be null");
    if (!usage.currency().equals(limit.currency())) {
      throw new IllegalArgumentException("usage and limit must use the same currency");
    }
  }

  /**
   * @deprecated legacy decision 기반 이벤트 생성 호환용입니다. tags는 보존하지 않습니다.
   */
  @Deprecated(since = "0.1.0", forRemoval = false)
  public BudgetNotificationEvent(
      BudgetKey key,
      BudgetThreshold threshold,
      BudgetState state,
      String reason,
      Cost projectedUsage,
      Cost limit,
      Map<String, String> tags
  ) {
    this(
        key,
        threshold,
        state,
        reason,
        projectedUsage,
        limit,
        BudgetNotificationSource.LEGACY_DECISION
    );
    Objects.requireNonNull(tags, "tags must not be null");
  }

  /**
   * @return {@link #usage()}와 동일한 accounting/admission 사용량
   * @deprecated source별 의미가 명확한 {@link #usage()}를 사용하세요.
   */
  @Deprecated(since = "0.1.0", forRemoval = true)
  public Cost projectedUsage() {
    return usage;
  }

  /**
   * @return {@link #usage()}와 동일한 accounting/admission 사용량
   * @deprecated source별 의미가 명확한 {@link #usage()}를 사용하세요.
   */
  @Deprecated(since = "0.1.0", forRemoval = true)
  public Cost currentUsage() {
    return usage;
  }

  /**
   * @return 민감 정보가 포함되지 않는 빈 map
   * @deprecated notification event는 임의 tags를 전달하지 않습니다.
   */
  @Deprecated(since = "0.1.0", forRemoval = true)
  public Map<String, String> tags() {
    return Map.of();
  }
}
