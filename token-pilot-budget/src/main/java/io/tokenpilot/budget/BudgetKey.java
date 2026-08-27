package io.tokenpilot.budget;

import java.util.Objects;

/**
 * Immutable key identifying a monthly budget bucket.
 *
 * @param policyId  budget policy identifier
 * @param targetType budget target type
 * @param targetId   budget target identifier
 * @param window     monthly budget period
 */
public record BudgetKey(
    String policyId,
    String targetType,
    String targetId,
    BudgetWindow window
) {

  public BudgetKey {
    policyId = requireText(policyId, "policyId");
    targetType = requireText(targetType, "targetType");
    targetId = requireText(targetId, "targetId");
    Objects.requireNonNull(window, "window must not be null");
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
