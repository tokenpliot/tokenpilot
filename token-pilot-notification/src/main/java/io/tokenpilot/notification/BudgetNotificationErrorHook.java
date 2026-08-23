package io.tokenpilot.notification;

/** bounded/sanitized notification 오류를 관찰하는 best-effort hook입니다. */
@FunctionalInterface
public interface BudgetNotificationErrorHook {

  void onError(BudgetNotificationError error);

  static BudgetNotificationErrorHook noOp() {
    return ignored -> { };
  }
}
