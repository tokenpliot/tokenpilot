package io.tokenpilot.notification;

import io.tokenpilot.budget.BudgetDecision;
import io.tokenpilot.budget.BudgetThreshold;

import java.util.Map;

/**
 * 예산 판단 결과를 기반으로 알림 이벤트를 발행하는 서비스
 */
public class BudgetNotificationService {

  private final BudgetNotificationHandler handler;
  private final NotificationStateStore store;

  public BudgetNotificationService(
      BudgetNotificationHandler handler,
      NotificationStateStore store
  ) {
    this.handler = handler;
    this.store = store;
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

    handler.handle(event);

    store.updateLastNotifiedThreshold(
        decision.key(),
        current
    );
  }
}
