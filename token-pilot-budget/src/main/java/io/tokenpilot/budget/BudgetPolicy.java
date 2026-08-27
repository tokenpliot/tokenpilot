package io.tokenpilot.budget;

import io.tokenpilot.core.domain.Cost;

import java.time.ZoneId;
import java.util.Objects;

/**
 * Policy snapshot used to create monthly budget keys and validate amounts.
 * Evaluation fails closed when the target tag and {@code fallbackTargetId} are both absent.
 *
 * @param id policy identifier
 * @param targetType budget target type
 * @param targetTagKey tag key from which to read the target identifier
 * @param fallbackTargetId explicit fallback for a missing target, or {@code null} when unset
 * @param monthlyLimit monthly limit including its currency
 * @param zoneId time zone used to calculate month boundaries
 */
public record BudgetPolicy(
    String id,
    String targetType,
    String targetTagKey,
    String fallbackTargetId,
    Cost monthlyLimit,
    ZoneId zoneId
) {

  public BudgetPolicy {
    id = requireText(id, "id");
    targetType = requireText(targetType, "targetType");
    targetTagKey = requireText(targetTagKey, "targetTagKey");
    if (fallbackTargetId != null && fallbackTargetId.isBlank()) {
      throw new IllegalArgumentException("fallbackTargetId must not be blank");
    }
    Objects.requireNonNull(monthlyLimit, "monthlyLimit must not be null");
    if (monthlyLimit.value().signum() <= 0) {
      throw new IllegalArgumentException("monthlyLimit must be greater than zero");
    }
    Objects.requireNonNull(zoneId, "zoneId must not be null");
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
