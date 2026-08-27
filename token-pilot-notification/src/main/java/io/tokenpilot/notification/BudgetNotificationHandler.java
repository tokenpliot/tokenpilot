package io.tokenpilot.notification;

/**
 * Interface for handling notification events.
 */
public interface BudgetNotificationHandler {

  void handle(BudgetNotificationEvent event);
}
