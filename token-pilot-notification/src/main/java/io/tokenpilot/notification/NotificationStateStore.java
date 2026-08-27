package io.tokenpilot.notification;

import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetThreshold;
import io.tokenpilot.core.domain.Cost;

import java.util.List;
import java.util.Objects;

/**
 * Notification deduplication store for legacy decision-based notifications.
 * Atomic lifecycle integration requires implementing
 * {@link AtomicNotificationStateStore}.
 */
public interface NotificationStateStore {

  /** @deprecated Legacy decision-based read API. */
  @Deprecated(since = "0.1.0", forRemoval = false)
  BudgetThreshold getLastNotifiedThreshold(BudgetKey key);

  /** @deprecated Legacy decision-based update API. */
  @Deprecated(since = "0.1.0", forRemoval = false)
  void updateLastNotifiedThreshold(
      BudgetKey key,
      BudgetThreshold threshold
  );

  /** Atomically claimed thresholds and the usage from their decision. */
  record NotificationClaim(
      Cost usage,
      List<BudgetThreshold> thresholds,
      BudgetThreshold observedThreshold
  ) {

    public NotificationClaim {
      Objects.requireNonNull(usage, "usage must not be null");
      thresholds = List.copyOf(
          Objects.requireNonNull(thresholds, "thresholds must not be null")
      );
      if (thresholds.contains(BudgetThreshold.NONE)) {
        throw new IllegalArgumentException("NONE must not be claimed");
      }
      Objects.requireNonNull(
          observedThreshold,
          "observedThreshold must not be null"
      );
      if (!thresholds.isEmpty()
          && observedThreshold.compareTo(thresholds.get(thresholds.size() - 1)) < 0) {
        throw new IllegalArgumentException(
            "observedThreshold must include every claimed threshold"
        );
      }
    }

    public NotificationClaim(Cost usage, List<BudgetThreshold> thresholds) {
      this(
          usage,
          thresholds,
          thresholds.isEmpty()
              ? BudgetThreshold.NONE
              : thresholds.get(thresholds.size() - 1)
      );
    }

    public static NotificationClaim none(Cost usage) {
      return none(usage, BudgetThreshold.NONE);
    }

    public static NotificationClaim none(
        Cost usage,
        BudgetThreshold observedThreshold
    ) {
      return new NotificationClaim(usage, List.of(), observedThreshold);
    }
  }
}
