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
 * @param tags legacy decision 알림에서만 보존하는 호환 tag
 *
 * <p>원자적 accounting 이벤트는 prompt, raw provider response, API key, 임의 tag map을
 * 포함하지 않습니다. legacy decision 생성자만 기존 handler 호환을 위해 불변 tag 복사본을
 * 보존합니다. {@link #projectedUsage()}와 {@link #currentUsage()} 호환 accessor는 0.1.x 동안
 * 유지하며 0.2.0에서 제거할 예정입니다.</p>
 */
public record BudgetNotificationEvent(
    BudgetKey key,
    BudgetThreshold threshold,
    BudgetState state,
    String reason,
    Cost usage,
    Cost limit,
    BudgetNotificationSource source,
    Map<String, String> tags
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
    tags = Map.copyOf(Objects.requireNonNull(tags, "tags must not be null"));
    if (!usage.currency().equals(limit.currency())) {
      throw new IllegalArgumentException("usage and limit must use the same currency");
    }
    if (source != BudgetNotificationSource.LEGACY_DECISION && !tags.isEmpty()) {
      throw new IllegalArgumentException(
          "atomic notification events must not contain arbitrary tags"
      );
    }
  }

  public BudgetNotificationEvent(
      BudgetKey key,
      BudgetThreshold threshold,
      BudgetState state,
      String reason,
      Cost usage,
      Cost limit,
      BudgetNotificationSource source
  ) {
    this(key, threshold, state, reason, usage, limit, source, Map.of());
  }

  /**
   * @deprecated legacy decision 기반 이벤트 생성 호환용입니다. tags는 불변 복사본으로 보존됩니다.
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
        BudgetNotificationSource.LEGACY_DECISION,
        tags
    );
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

}
