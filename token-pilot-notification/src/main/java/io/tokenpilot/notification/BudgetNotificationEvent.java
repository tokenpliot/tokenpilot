package io.tokenpilot.notification;

import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetState;
import io.tokenpilot.budget.BudgetThreshold;
import io.tokenpilot.core.domain.Cost;

import java.util.Map;
import java.util.Objects;

/**
 * Notification event emitted when a budget threshold is reached.
 *
 * @param key budget bucket identifier
 * @param threshold reached threshold
 * @param state budget state
 * @param reason state description
 * @param usage usage from the accounting or admission result that produced the notification
 * @param limit budget limit
 * @param source atomic result type that produced the notification
 * @param tags compatibility tags retained only for legacy decision notifications
 *
 * <p>Atomic accounting events do not contain prompts, raw provider responses,
 * API keys, or arbitrary tag maps. Only the legacy decision constructor retains
 * an immutable tag copy for handler compatibility. The compatibility accessors
 * {@link #projectedUsage()} and {@link #currentUsage()} remain through 0.1.x
 * and are scheduled for removal in 0.2.0.</p>
 */
public record BudgetNotificationEvent(
    BudgetKey key,
    BudgetThreshold threshold,
    BudgetState state,
    String reason,
    Cost usage,
    Cost limit,
    BudgetNotificationSource source,
    Map<String, String> tags
) {

  public BudgetNotificationEvent {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(threshold, "threshold must not be null");
    Objects.requireNonNull(state, "state must not be null");
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("reason must not be blank");
    }
    Objects.requireNonNull(usage, "usage must not be null");
    Objects.requireNonNull(limit, "limit must not be null");
    Objects.requireNonNull(source, "source must not be null");
    tags = Map.copyOf(Objects.requireNonNull(tags, "tags must not be null"));
    if (!usage.currency().equals(limit.currency())) {
      throw new IllegalArgumentException("usage and limit must use the same currency");
    }
    if (source != BudgetNotificationSource.LEGACY_DECISION && !tags.isEmpty()) {
      throw new IllegalArgumentException(
          "atomic notification events must not contain arbitrary tags"
      );
    }
  }

  public BudgetNotificationEvent(
      BudgetKey key,
      BudgetThreshold threshold,
      BudgetState state,
      String reason,
      Cost usage,
      Cost limit,
      BudgetNotificationSource source
  ) {
    this(key, threshold, state, reason, usage, limit, source, Map.of());
  }

  /**
   * @deprecated Compatibility constructor for legacy decision-based event creation.
   *             Tags are retained as an immutable copy.
   */
  @Deprecated(since = "0.1.0", forRemoval = false)
  public BudgetNotificationEvent(
      BudgetKey key,
      BudgetThreshold threshold,
      BudgetState state,
      String reason,
      Cost projectedUsage,
      Cost limit,
      Map<String, String> tags
  ) {
    this(
        key,
        threshold,
        state,
        reason,
        projectedUsage,
        limit,
        BudgetNotificationSource.LEGACY_DECISION,
        tags
    );
  }

  /**
   * @return accounting/admission usage identical to {@link #usage()}
   * @deprecated Use {@link #usage()}, whose meaning is clear for each source.
   */
  @Deprecated(since = "0.1.0", forRemoval = true)
  public Cost projectedUsage() {
    return usage;
  }

  /**
   * @return accounting/admission usage identical to {@link #usage()}
   * @deprecated Use {@link #usage()}, whose meaning is clear for each source.
   */
  @Deprecated(since = "0.1.0", forRemoval = true)
  public Cost currentUsage() {
    return usage;
  }

}
