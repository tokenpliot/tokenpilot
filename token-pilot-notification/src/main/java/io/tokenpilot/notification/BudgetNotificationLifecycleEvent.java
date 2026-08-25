package io.tokenpilot.notification;

import io.tokenpilot.budget.BudgetThreshold;

import java.util.Objects;

/**
 * notification handler 처리 또는 dedup 결과를 전달하는 framework-independent 이벤트입니다.
 *
 * @param outcome success, failure 또는 process-local dedup 결과
 * @param threshold 처리 대상인 bounded budget threshold
 */
public record BudgetNotificationLifecycleEvent(
    BudgetNotificationOutcome outcome,
    BudgetThreshold threshold
) {

  public BudgetNotificationLifecycleEvent {
    Objects.requireNonNull(outcome, "outcome must not be null");
    Objects.requireNonNull(threshold, "threshold must not be null");
    if (threshold == BudgetThreshold.NONE) {
      throw new IllegalArgumentException("threshold must identify a notification boundary");
    }
  }
}
