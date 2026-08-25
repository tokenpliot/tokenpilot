package io.tokenpilot.notification;

import io.tokenpilot.budget.BudgetDecision;
import io.tokenpilot.budget.BudgetDecision.EvaluationType;
import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetReservationRequest;
import io.tokenpilot.budget.BudgetReservationResult;
import io.tokenpilot.budget.BudgetState;
import io.tokenpilot.budget.BudgetThreshold;
import io.tokenpilot.budget.BudgetWindow;
import io.tokenpilot.core.domain.Cost;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    service.notifyIfNeeded(
        decision(key, BudgetThreshold.HALF, "50"),
        Map.of("tenant_id", "acme")
    );
    service.notifyIfNeeded(
        decision(key, BudgetThreshold.HALF, "50"),
        Map.of("tenant_id", "acme")
    );
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

    service.notifyIfNeeded(
        decision(key, BudgetThreshold.HALF, "50"),
        Map.of("tenant_id", "acme")
    );

    ArgumentCaptor<BudgetNotificationEvent> event = ArgumentCaptor.forClass(BudgetNotificationEvent.class);
    verify(handler).handle(event.capture());
    verify(store).getLastNotifiedThreshold(same(key));
    verify(store).updateLastNotifiedThreshold(same(key), same(BudgetThreshold.HALF));
    assertThat(event.getValue().key()).isSameAs(key);
    assertThat(event.getValue().projectedUsage())
        .isEqualTo(Cost.of(new BigDecimal("50"), Currency.getInstance("USD")));
    assertThat(event.getValue().currentUsage())
        .isEqualTo(event.getValue().projectedUsage());
    assertThat(event.getValue().tags())
        .containsExactly(Map.entry("tenant_id", "acme"));
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void legacy_threshold_claim은_병렬_호출에서도_한_번만_전달한다() throws Exception {
    AtomicInteger deliveries = new AtomicInteger();
    CountDownLatch handlerEntered = new CountDownLatch(1);
    CountDownLatch releaseHandler = new CountDownLatch(1);
    List<BudgetNotificationLifecycleEvent> lifecycleEvents =
        new CopyOnWriteArrayList<>();
    BudgetNotificationService service = new BudgetNotificationService(
        event -> {
          deliveries.incrementAndGet();
          handlerEntered.countDown();
          try {
            if (!releaseHandler.await(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException("handler release timed out");
            }
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("handler interrupted", exception);
          }
        },
        new InMemoryNotificationStateStore(),
        List.of(lifecycleEvents::add)
    );
    BudgetDecision half = decision(key("2026-08"), BudgetThreshold.HALF, "50");

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> service.notifyIfNeeded(half, Map.of()));
      assertThat(handlerEntered.await(5, TimeUnit.SECONDS)).isTrue();
      var second = executor.submit(() -> service.notifyIfNeeded(half, Map.of()));
      releaseHandler.countDown();
      first.get(5, TimeUnit.SECONDS);
      second.get(5, TimeUnit.SECONDS);
    } finally {
      releaseHandler.countDown();
    }

    assertThat(deliveries).hasValue(1);
    assertThat(lifecycleEvents).containsExactlyInAnyOrder(
        new BudgetNotificationLifecycleEvent(
            BudgetNotificationOutcome.SUCCESS,
            BudgetThreshold.HALF
        ),
        new BudgetNotificationLifecycleEvent(
            BudgetNotificationOutcome.DEDUPLICATED,
            BudgetThreshold.HALF
        )
    );
  }

  @Test
  void atomic_claim도_legacy와_같은_store_monitor에서_직렬화한다() {
    AtomicBoolean monitorHeld = new AtomicBoolean();
    InMemoryNotificationStateStore store = new InMemoryNotificationStateStore() {
      @Override
      public NotificationClaim recordBlockedReservation(
          BudgetReservationRequest request,
          BudgetReservationResult result
      ) {
        monitorHeld.set(Thread.holdsLock(this));
        return NotificationClaim.none(
            Cost.zero(Currency.getInstance("USD"))
        );
      }
    };
    BudgetNotificationService service = new BudgetNotificationService(
        List.of(event -> { }),
        store,
        ignored -> null
    );
    BudgetReservationRequest request = mock(BudgetReservationRequest.class);
    BudgetReservationResult result = mock(BudgetReservationResult.class);
    when(request.key()).thenReturn(key("2026-08"));
    when(request.limit()).thenReturn(Cost.of(
        new BigDecimal("100"),
        Currency.getInstance("USD")
    ));
    when(result.reason()).thenReturn("budget exceeded");

    service.onReservationBlocked(request, result);

    assertThat(monitorHeld).isTrue();
  }

  @Test
  void handler_성공과_중복을_lifecycle_event로_발행한다() {
    BudgetNotificationHandler handler = mock(BudgetNotificationHandler.class);
    List<BudgetNotificationLifecycleEvent> lifecycleEvents = new CopyOnWriteArrayList<>();
    BudgetNotificationService service = new BudgetNotificationService(
        handler,
        new InMemoryNotificationStateStore(),
        List.of(lifecycleEvents::add)
    );
    BudgetDecision half = decision(key("2026-08"), BudgetThreshold.HALF, "50");

    service.notifyIfNeeded(half, Map.of());
    service.notifyIfNeeded(half, Map.of());

    assertThat(lifecycleEvents).containsExactly(
        new BudgetNotificationLifecycleEvent(
            BudgetNotificationOutcome.SUCCESS,
            BudgetThreshold.HALF
        ),
        new BudgetNotificationLifecycleEvent(
            BudgetNotificationOutcome.DEDUPLICATED,
            BudgetThreshold.HALF
        )
    );
    verify(handler).handle(any());
  }

  @Test
  void handler_실패를_발행하고_기존처럼_호출자에게_전파한다() {
    RuntimeException failure = new IllegalStateException("handler failed");
    BudgetNotificationHandler handler = event -> {
      throw failure;
    };
    NotificationStateStore store = mock(NotificationStateStore.class);
    List<BudgetNotificationLifecycleEvent> lifecycleEvents = new CopyOnWriteArrayList<>();
    BudgetNotificationService service = new BudgetNotificationService(
        handler,
        store,
        List.of(lifecycleEvents::add)
    );
    BudgetKey key = key("2026-08");
    when(store.getLastNotifiedThreshold(key)).thenReturn(BudgetThreshold.NONE);

    assertThatThrownBy(() -> service.notifyIfNeeded(
        decision(key, BudgetThreshold.WARNING, "80"),
        Map.of()
    )).isSameAs(failure);

    assertThat(lifecycleEvents).containsExactly(
        new BudgetNotificationLifecycleEvent(
            BudgetNotificationOutcome.FAILURE,
            BudgetThreshold.WARNING
        )
    );
    verify(store, never()).updateLastNotifiedThreshold(any(), any());
  }

  @Test
  void legacy_handler_실패는_claim하지_않아_다음_호출이_재시도한다() {
    AtomicInteger attempts = new AtomicInteger();
    BudgetNotificationService service = new BudgetNotificationService(
        event -> {
          if (attempts.incrementAndGet() == 1) {
            throw new IllegalStateException("first delivery failed");
          }
        },
        new InMemoryNotificationStateStore()
    );
    BudgetDecision warning = decision(
        key("2026-08"),
        BudgetThreshold.WARNING,
        "80"
    );

    assertThatThrownBy(() -> service.notifyIfNeeded(warning, Map.of()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("first delivery failed");

    service.notifyIfNeeded(warning, Map.of());

    assertThat(attempts).hasValue(2);
  }

  @Test
  void lifecycle_listener_실패는_handler와_다음_listener를_바꾸지_않는다() {
    BudgetNotificationHandler handler = mock(BudgetNotificationHandler.class);
    AtomicInteger failedDeliveries = new AtomicInteger();
    List<BudgetNotificationLifecycleEvent> received = new CopyOnWriteArrayList<>();
    BudgetNotificationService service = new BudgetNotificationService(
        handler,
        new InMemoryNotificationStateStore(),
        List.of(
            event -> {
              failedDeliveries.incrementAndGet();
              throw new IllegalStateException("listener failed");
            },
            received::add
        )
    );

    service.notifyIfNeeded(
        decision(key("2026-08"), BudgetThreshold.HALF, "50"),
        Map.of()
    );

    assertThat(failedDeliveries).hasValue(1);
    assertThat(received).containsExactly(
        new BudgetNotificationLifecycleEvent(
            BudgetNotificationOutcome.SUCCESS,
            BudgetThreshold.HALF
        )
    );
    verify(handler).handle(any());
  }

  @Test
  void NONE은_lifecycle_event를_발행하지_않는다() {
    List<BudgetNotificationLifecycleEvent> lifecycleEvents = new CopyOnWriteArrayList<>();
    BudgetNotificationHandler handler = mock(BudgetNotificationHandler.class);
    BudgetNotificationService service = new BudgetNotificationService(
        handler,
        new InMemoryNotificationStateStore(),
        List.of(lifecycleEvents::add)
    );

    service.notifyIfNeeded(
        decision(key("2026-08"), BudgetThreshold.NONE, "0"),
        Map.of()
    );

    assertThat(lifecycleEvents).isEmpty();
    verify(handler, never()).handle(any());
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
