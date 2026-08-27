package io.tokenpilot.budget;

/**
 *  Threshold definitions based on budget utilization.
 *
 * NONE     : threshold not reached
 * HALF     : 50% reached
 * WARNING  : 80% reached
 * EXCEEDED : 100% exceeded
 *
 *  Used by the notification module as the alerting criterion.
 */
public enum BudgetThreshold {

  NONE,       // No notification is needed yet.
  HALF,       // 50%
  WARNING,    // 80%
  EXCEEDED    // 100%
}
