package io.tokenpilot.notification;

import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetThreshold;
import io.tokenpilot.core.domain.Cost;

import java.util.List;
import java.util.Objects;

/**
 * legacy decision 기반 알림 dedup 저장소입니다.
 * Atomic lifecycle 연결은 {@link AtomicNotificationStateStore}를 구현해야 합니다.
 */
public interface NotificationStateStore {

  /** @deprecated legacy decision 기반 조회 API입니다. */
  @Deprecated(since = "0.1.0", forRemoval = false)
  BudgetThreshold getLastNotifiedThreshold(BudgetKey key);

  /** @deprecated legacy decision 기반 갱신 API입니다. */
  @Deprecated(since = "0.1.0", forRemoval = false)
  void updateLastNotifiedThreshold(
      BudgetKey key,
      BudgetThreshold threshold
  );

  /** 원자적으로 claim된 threshold와 해당 판단의 사용량입니다. */
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
