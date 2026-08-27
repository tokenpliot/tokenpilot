package io.tokenpilot.notification;

/** Type of atomic budget/accounting result that produced a notification. */
public enum BudgetNotificationSource {
  ACCOUNTING_COMMIT,
  RECONCILIATION_REQUIRED,
  LATE_RECONCILIATION,
  RESERVATION_BLOCK,
  LEGACY_DECISION
}
