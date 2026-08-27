package io.tokenpilot.notification;

/** Best-effort hook for observing bounded and sanitized notification errors. */
@FunctionalInterface
public interface BudgetNotificationErrorHook {

  void onError(BudgetNotificationError error);

  static BudgetNotificationErrorHook noOp() {
    return ignored -> { };
  }
}
