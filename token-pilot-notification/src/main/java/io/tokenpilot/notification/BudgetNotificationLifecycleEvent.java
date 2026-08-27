package io.tokenpilot.notification;

import io.tokenpilot.budget.BudgetThreshold;

import java.util.Objects;

/**
 * Framework-independent event carrying the result of notification handler
 * processing or deduplication.
 *
 * @param outcome success, failure, or process-local deduplication result
 * @param threshold bounded budget threshold being processed
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
