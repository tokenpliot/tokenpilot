package io.tokenpilot.notification;

/** Framework-independent contract for best-effort observation of the budget notification lifecycle. */
@FunctionalInterface
public interface BudgetNotificationLifecycleListener {

  void onNotificationLifecycle(BudgetNotificationLifecycleEvent event);
}
