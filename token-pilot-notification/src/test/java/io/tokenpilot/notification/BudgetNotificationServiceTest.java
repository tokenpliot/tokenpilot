package io.tokenpilot.notification;

import io.tokenpilot.budget.BudgetDecision;
import io.tokenpilot.budget.BudgetDecision.EvaluationType;
import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetState;
import io.tokenpilot.budget.BudgetThreshold;
import io.tokenpilot.budget.BudgetWindow;
import io.tokenpilot.core.domain.Cost;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BudgetNotificationServiceTest {

  @Test
  void 같은_key에서는_증가한_threshold만_알린다() {
    BudgetNotificationHandler handler = mock(BudgetNotificationHandler.class);
    BudgetNotificationService service = new BudgetNotificationService(
        handler,
        new InMemoryNotificationStateStore()
    );
    BudgetKey key = key("2026-06");

    service.notifyIfNeeded(decision(key, BudgetThreshold.HALF, "50"), Map.of());
    service.notifyIfNeeded(decision(key, BudgetThreshold.HALF, "50"), Map.of());
    service.notifyIfNeeded(decision(key, BudgetThreshold.WARNING, "80"), Map.of());

    verify(handler, times(2)).handle(any());
  }

  @Test
  void 새_window에서는_같은_threshold를_다시_알린다() {
    BudgetNotificationHandler handler = mock(BudgetNotificationHandler.class);
    BudgetNotificationService service = new BudgetNotificationService(
        handler,
        new InMemoryNotificationStateStore()
    );

    service.notifyIfNeeded(decision(key("2026-06"), BudgetThreshold.HALF, "50"), Map.of());
    service.notifyIfNeeded(decision(key("2026-07"), BudgetThreshold.HALF, "50"), Map.of());

    verify(handler, times(2)).handle(any());
  }

  @Test
  @SuppressWarnings("removal")
  void event와_notification_store가_decision의_동일한_key를_사용한다() {
    BudgetNotificationHandler handler = mock(BudgetNotificationHandler.class);
    NotificationStateStore store = mock(NotificationStateStore.class);
    BudgetNotificationService service = new BudgetNotificationService(handler, store);
    BudgetKey key = key("2026-07");
    when(store.getLastNotifiedThreshold(key)).thenReturn(BudgetThreshold.NONE);

    service.notifyIfNeeded(decision(key, BudgetThreshold.HALF, "50"), Map.of());

    ArgumentCaptor<BudgetNotificationEvent> event = ArgumentCaptor.forClass(BudgetNotificationEvent.class);
    verify(handler).handle(event.capture());
    verify(store).getLastNotifiedThreshold(same(key));
    verify(store).updateLastNotifiedThreshold(same(key), same(BudgetThreshold.HALF));
    assertThat(event.getValue().key()).isSameAs(key);
    assertThat(event.getValue().projectedUsage())
        .isEqualTo(Cost.of(new BigDecimal("50"), Currency.getInstance("USD")));
    assertThat(event.getValue().currentUsage())
        .isEqualTo(event.getValue().projectedUsage());
  }

  private static BudgetKey key(String window) {
    return new BudgetKey("policy-a", "tenant", "tenant-a", BudgetWindow.parse(window));
  }

  private static BudgetDecision decision(
      BudgetKey key,
      BudgetThreshold threshold,
      String usage
  ) {
    return new BudgetDecision(
        key,
        EvaluationType.ADMISSION,
        BudgetState.WARN,
        threshold,
        threshold.name(),
        Cost.of(new BigDecimal(usage), Currency.getInstance("USD")),
        Cost.of(new BigDecimal(usage), Currency.getInstance("USD")),
        Cost.of(new BigDecimal("100"), Currency.getInstance("USD"))
    );
  }
}
