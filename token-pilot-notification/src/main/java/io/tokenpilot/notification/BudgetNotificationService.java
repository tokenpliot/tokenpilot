package io.tokenpilot.notification;

import io.tokenpilot.budget.BudgetDecision;
import io.tokenpilot.budget.BudgetThreshold;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 예산 판단 결과를 기반으로 알림 이벤트를 발행하는 서비스
 */
public class BudgetNotificationService {

  private final BudgetNotificationHandler handler;
  private final NotificationStateStore store;
  private final List<BudgetNotificationLifecycleListener> lifecycleListeners;

  public BudgetNotificationService(
      BudgetNotificationHandler handler,
      NotificationStateStore store
  ) {
    this(handler, store, List.of());
  }

  public BudgetNotificationService(
      BudgetNotificationHandler handler,
      NotificationStateStore store,
      List<BudgetNotificationLifecycleListener> lifecycleListeners
  ) {
    this.handler = Objects.requireNonNull(handler, "handler must not be null");
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.lifecycleListeners = List.copyOf(
        Objects.requireNonNull(lifecycleListeners, "lifecycleListeners must not be null")
    );
  }

  /**
   * 임계치가 증가한 경우에만 이벤트를 발생시킨다
   */
  public void notifyIfNeeded(
      BudgetDecision decision,
      Map<String, String> tags
  ) {
    BudgetThreshold current = decision.threshold();

    if (current == BudgetThreshold.NONE) {
      return;
    }

    BudgetThreshold last = store.getLastNotifiedThreshold(decision.key());

    // 같은 window에서 중복 방지
    if (current.compareTo(last) <= 0) {
      publishLifecycleBestEffort(
          BudgetNotificationOutcome.DEDUPLICATED,
          current
      );
      return;
    }

    BudgetNotificationEvent event =
        new BudgetNotificationEvent(
            decision.key(),
            current,
            decision.state(),
            decision.reason(),
            decision.projectedUsage(),
            decision.limit(),
            tags
        );

    try {
      handler.handle(event);
    } catch (RuntimeException failure) {
      publishLifecycleBestEffort(
          BudgetNotificationOutcome.FAILURE,
          current
      );
      throw failure;
    }

    store.updateLastNotifiedThreshold(
        decision.key(),
        current
    );
    publishLifecycleBestEffort(
        BudgetNotificationOutcome.SUCCESS,
        current
    );
  }

  private void publishLifecycleBestEffort(
      BudgetNotificationOutcome outcome,
      BudgetThreshold threshold
  ) {
    if (lifecycleListeners.isEmpty()) {
      return;
    }
    BudgetNotificationLifecycleEvent event = new BudgetNotificationLifecycleEvent(
        outcome,
        threshold
    );
    for (BudgetNotificationLifecycleListener listener : lifecycleListeners) {
      try {
        listener.onNotificationLifecycle(event);
      } catch (RuntimeException ignored) {
        // Optional observers do not alter notification delivery or failure propagation.
      }
    }
  }
}
