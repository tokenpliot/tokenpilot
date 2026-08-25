package io.tokenpilot.notification;

/** Budget notification lifecycle을 best-effort로 관찰하는 framework-independent 계약입니다. */
@FunctionalInterface
public interface BudgetNotificationLifecycleListener {

  void onNotificationLifecycle(BudgetNotificationLifecycleEvent event);
}
