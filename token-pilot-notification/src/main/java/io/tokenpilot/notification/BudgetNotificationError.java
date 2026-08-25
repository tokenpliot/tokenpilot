package io.tokenpilot.notification;

import java.util.Objects;

/**
 * 알림 실패 hook에 전달하는 제한된 오류 정보입니다.
 *
 * <p>원본 예외 메시지와 notification event를 보존하지 않아 prompt, provider response,
 * API key가 오류 경로로 전달되지 않습니다. 각 문자열은 최대 128자로 제한됩니다.</p>
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
