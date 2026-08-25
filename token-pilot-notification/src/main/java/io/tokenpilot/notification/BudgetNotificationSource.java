package io.tokenpilot.notification;

/** 알림을 만든 원자적 budget/accounting 결과의 종류입니다. */
public enum BudgetNotificationSource {
  ACCOUNTING_COMMIT,
  RECONCILIATION_REQUIRED,
  LATE_RECONCILIATION,
  RESERVATION_BLOCK,
  LEGACY_DECISION
}
