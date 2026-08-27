package io.tokenpilot.notification;

import java.util.Objects;

/**
 * Bounded error information passed to the notification failure hook.
 *
 * <p>The original exception message and notification event are not retained, so
 * prompts, provider responses, and API keys do not enter the error path. Each
 * string is limited to 128 characters.</p>
 */
public record BudgetNotificationError(
    String stage,
    String handlerType,
    String exceptionType,
    String message
) {

  private static final int MAX_LENGTH = 128;

  public BudgetNotificationError {
    stage = bounded(stage, "stage");
    handlerType = bounded(handlerType, "handlerType");
    exceptionType = bounded(exceptionType, "exceptionType");
    message = bounded(message, "message");
  }

  static BudgetNotificationError handlerFailure(
      BudgetNotificationHandler handler,
      RuntimeException failure
  ) {
    return new BudgetNotificationError(
        "HANDLER",
        typeName(handler),
        typeName(failure),
        "budget notification handler failed"
    );
  }

  static BudgetNotificationError stateFailure(RuntimeException failure) {
    return new BudgetNotificationError(
        "STATE",
        "notification-state-store",
        typeName(failure),
        "budget notification state update failed"
    );
  }

  private static String typeName(Object value) {
    Objects.requireNonNull(value, "value must not be null");
    String simpleName = value.getClass().getSimpleName();
    return simpleName.isBlank() ? "anonymous" : simpleName;
  }

  private static String bounded(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    String sanitized = value.replaceAll("[\\p{Cntrl}]", " ").trim();
    if (sanitized.isBlank()) {
      throw new IllegalArgumentException(name + " must contain visible text");
    }
    return sanitized.length() <= MAX_LENGTH
        ? sanitized
        : sanitized.substring(0, MAX_LENGTH);
  }
}
