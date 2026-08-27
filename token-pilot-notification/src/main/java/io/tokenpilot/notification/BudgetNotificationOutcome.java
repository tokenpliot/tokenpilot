package io.tokenpilot.notification;

/** Bounded operational result of budget notification processing. */
public enum BudgetNotificationOutcome {
  SUCCESS,
  FAILURE,
  DEDUPLICATED
}
