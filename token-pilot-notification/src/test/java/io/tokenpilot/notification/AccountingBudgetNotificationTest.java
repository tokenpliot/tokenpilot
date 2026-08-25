package io.tokenpilot.notification;

import io.tokenpilot.budget.AccountingTransitionStatus;
import io.tokenpilot.budget.ActualUsageCommand;
import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetReservationRequest;
import io.tokenpilot.budget.BudgetReservationResult;
import io.tokenpilot.budget.BudgetSnapshot;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.budget.BudgetThreshold;
import io.tokenpilot.budget.BudgetWindow;
import io.tokenpilot.budget.IdempotencyKey;
import io.tokenpilot.budget.ReservationAccounting;
import io.tokenpilot.budget.ReservationAccountingEvent;
import io.tokenpilot.budget.ReservationId;
import io.tokenpilot.budget.ReservationReconciliation;
import io.tokenpilot.budget.ReservationStatus;
import io.tokenpilot.budget.ReservationTokenEstimate;
import io.tokenpilot.budget.internal.LedgerBudgetComponents;
import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.PricingSnapshot;
import io.tokenpilot.core.domain.TokenType;
import io.tokenpilot.core.domain.TokenUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class AccountingBudgetNotificationTest {

  private static final Currency USD = Currency.getInstance("USD");
  private static final Cost LIMIT = usd("100.00");
  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-08-24T00:00:00Z"),
      ZoneOffset.UTC
  );
  private static final ReservationTokenEstimate TOKEN_ESTIMATE =
      new ReservationTokenEstimate(1, 1, 1);

  @Test
  void NONE은_알리지_않는다() {
    List<BudgetNotificationEvent> events = new ArrayList<>();
    Fixture fixture = fixture(List.of(events::add), BudgetNotificationErrorHook.noOp());

    commit(fixture, key("tenant-a", "2026-08"), "request-1", 40);

    assertThat(events).isEmpty();
  }

  @Test
  void 같은_key와_threshold는_한_번만_전달한다() {
    List<BudgetNotificationEvent> events = new ArrayList<>();
    Fixture fixture = fixture(List.of(events::add), BudgetNotificationErrorHook.noOp());
    BudgetKey key = key("tenant-a", "2026-08");

    commit(fixture, key, "request-1", 50);
    commit(fixture, key, "request-2", 10);
    commit(fixture, key, "request-3", 10);

    assertThat(events)
        .extracting(BudgetNotificationEvent::threshold)
        .containsExactly(BudgetThreshold.HALF);
  }

  @Test
  void HALF_WARNING_EXCEEDED는_상승할_때_각각_한_번_전달한다() {
    List<BudgetNotificationEvent> events = new ArrayList<>();
    Fixture fixture = fixture(List.of(events::add), BudgetNotificationErrorHook.noOp());
    BudgetKey key = key("tenant-a", "2026-08");

    commit(fixture, key, "request-1", 50);
    commit(fixture, key, "request-2", 30);
    commit(fixture, key, "request-3", 20);

    assertThat(events)
        .extracting(BudgetNotificationEvent::threshold)
        .containsExactly(
            BudgetThreshold.HALF,
            BudgetThreshold.WARNING,
            BudgetThreshold.EXCEEDED
        );
    assertThat(events)
        .extracting(BudgetNotificationEvent::usage)
        .containsExactly(usd("50"), usd("80"), usd("100"));
    assertThat(events)
        .extracting(BudgetNotificationEvent::threshold, BudgetNotificationEvent::state)
        .containsExactly(
            tuple(BudgetThreshold.HALF, io.tokenpilot.budget.BudgetState.ALLOW),
            tuple(BudgetThreshold.WARNING, io.tokenpilot.budget.BudgetState.WARN),
            tuple(BudgetThreshold.EXCEEDED, io.tokenpilot.budget.BudgetState.BLOCK)
        );
  }

  @Test
  void commit은_notification_자체_누적이_아닌_atomic_snapshot으로_판정한다() {
    List<BudgetNotificationEvent> events = new ArrayList<>();
    Fixture fixture = fixture(List.of(events::add), BudgetNotificationErrorHook.noOp());
    BudgetKey key = key("tenant-a", "2026-08");
    BudgetReservationResult first = fixture.stateStore().checkAndReserve(
        request(key, "request-1", usd("40"))
    );
    BudgetReservationResult second = fixture.stateStore().checkAndReserve(
        request(key, "request-2", usd("40"))
    );
    fixture.accounting().markInFlight(first.reservationId());

    fixture.accounting().commit(command("request-1", first.reservationId(), 20));

    assertThat(first.status()).isEqualTo(ReservationStatus.CREATED);
    assertThat(second.status()).isEqualTo(ReservationStatus.CREATED);
    assertThat(events)
        .extracting(BudgetNotificationEvent::threshold)
        .containsExactly(BudgetThreshold.HALF);
    assertThat(events)
        .extracting(BudgetNotificationEvent::usage)
        .containsExactly(usd("60"));
  }

  @Test
  void actual_unavailable은_pending_snapshot에서_threshold를_판정한다() {
    List<BudgetNotificationEvent> events = new ArrayList<>();
    Fixture fixture = fixture(List.of(events::add), BudgetNotificationErrorHook.noOp());
    BudgetKey key = key("tenant-a", "2026-08");
    BudgetReservationResult reserved = fixture.stateStore().checkAndReserve(
        request(key, "request-1", usd("60"))
    );
    fixture.accounting().markInFlight(reserved.reservationId());

    var applied = fixture.accounting().markReconciliationRequired(
        reserved.reservationId()
    );
    var duplicate = fixture.accounting().markReconciliationRequired(
        reserved.reservationId()
    );

    assertThat(applied.status()).isEqualTo(AccountingTransitionStatus.APPLIED);
    assertThat(duplicate.status()).isEqualTo(AccountingTransitionStatus.REUSED);
    assertThat(fixture.stateStore().snapshot(key, LIMIT)
        .pendingReconciliationLiability()).isEqualTo(usd("60"));
    assertThat(events)
        .extracting(BudgetNotificationEvent::threshold)
        .containsExactly(BudgetThreshold.HALF);
    assertThat(events)
        .extracting(BudgetNotificationEvent::source)
        .containsExactly(BudgetNotificationSource.RECONCILIATION_REQUIRED);
  }

  @Test
  void 새_window에서는_같은_threshold를_다시_전달한다() {
    List<BudgetNotificationEvent> events = new ArrayList<>();
    Fixture fixture = fixture(List.of(events::add), BudgetNotificationErrorHook.noOp());

    commit(fixture, key("tenant-a", "2026-08"), "request-1", 50);
    commit(fixture, key("tenant-a", "2026-09"), "request-2", 50);

    assertThat(events)
        .extracting(event -> event.key().window())
        .containsExactly(BudgetWindow.parse("2026-08"), BudgetWindow.parse("2026-09"));
    assertThat(events)
        .extracting(BudgetNotificationEvent::threshold)
        .containsExactly(BudgetThreshold.HALF, BudgetThreshold.HALF);
  }

  @Test
  void duplicate_commit과_late_reconcile은_알림을_다시_만들지_않는다() {
    List<BudgetNotificationEvent> events = new ArrayList<>();
    Fixture fixture = fixture(List.of(events::add), BudgetNotificationErrorHook.noOp());

    BudgetKey directKey = key("tenant-a", "2026-08");
    ReservationId directId = reserve(fixture, directKey, "request-1");
    fixture.accounting().markInFlight(directId);
    ActualUsageCommand directCommand = command("request-1", directId, 50);
    ReservationReconciliation direct = fixture.accounting().commit(directCommand);
    ReservationReconciliation directDuplicate = fixture.accounting().commit(directCommand);

    BudgetKey lateKey = key("tenant-b", "2026-08");
    ReservationId lateId = reserve(fixture, lateKey, "request-2");
    fixture.accounting().markInFlight(lateId);
    fixture.accounting().markReconciliationRequired(lateId);
    ActualUsageCommand lateCommand = command("request-2", lateId, 50);
    ReservationReconciliation late = fixture.accounting().reconcileLateActual(lateCommand);
    ReservationReconciliation lateDuplicate = fixture.accounting()
        .reconcileLateActual(lateCommand);

    assertThat(direct.transition().status()).isEqualTo(AccountingTransitionStatus.APPLIED);
    assertThat(directDuplicate.transition().status())
        .isEqualTo(AccountingTransitionStatus.REUSED);
    assertThat(late.transition().status()).isEqualTo(AccountingTransitionStatus.APPLIED);
    assertThat(lateDuplicate.transition().status())
        .isEqualTo(AccountingTransitionStatus.REUSED);
    assertThat(events)
        .extracting(BudgetNotificationEvent::source)
        .containsExactly(
            BudgetNotificationSource.ACCOUNTING_COMMIT,
            BudgetNotificationSource.LATE_RECONCILIATION
        );
  }

  @Test
  void handler_실패는_다음_handler와_commit_BLOCK_결과에_영향을_주지_않는다() {
    List<BudgetNotificationEvent> delivered = new ArrayList<>();
    List<BudgetNotificationError> errors = new ArrayList<>();
    BudgetNotificationHandler failing = event -> {
      throw new IllegalStateException("api-key=secret\nraw-response");
    };
    Fixture fixture = fixture(List.of(failing, delivered::add), errors::add);
    BudgetKey committedKey = key("tenant-a", "2026-08");

    ReservationReconciliation committed = commit(
        fixture,
        committedKey,
        "request-1",
        50
    );
    BudgetKey blockedKey = key("tenant-b", "2026-08");
    BudgetReservationResult blocked = fixture.stateStore().checkAndReserve(
        request(blockedKey, "blocked-request", LIMIT)
    );

    assertThat(committed.transition().status())
        .isEqualTo(AccountingTransitionStatus.APPLIED);
    assertThat(fixture.stateStore().snapshot(committedKey, LIMIT).committedCost())
        .isEqualTo(usd("50"));
    assertThat(blocked.status()).isEqualTo(ReservationStatus.BLOCKED);
    assertThat(fixture.stateStore().snapshot(blockedKey, LIMIT).effectiveUsage())
        .isEqualTo(Cost.zero(USD));
    assertThat(delivered)
        .extracting(BudgetNotificationEvent::threshold)
        .containsExactly(BudgetThreshold.HALF, BudgetThreshold.EXCEEDED);
    assertThat(errors).hasSize(2).allSatisfy(error -> {
      assertThat(error.message()).hasSizeLessThanOrEqualTo(128);
      assertThat(error.toString()).doesNotContain("secret", "raw-response");
    });
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void 병렬_event에서도_threshold_dedup은_원자적으로_동작한다() throws Exception {
    List<AccountingDelivery> accountingEvents = new ArrayList<>();
    Fixture source = fixture(
        List.of(event -> { }),
        BudgetNotificationErrorHook.noOp(),
        accountingEvents
    );
    ReservationReconciliation applied = commit(
        source,
        key("tenant-a", "2026-08"),
        "request-1",
        50
    );
    assertThat(applied.transition().status()).isEqualTo(AccountingTransitionStatus.APPLIED);
    assertThat(accountingEvents).hasSize(1);

    AtomicInteger deliveries = new AtomicInteger();
    BudgetNotificationService service = new BudgetNotificationService(
        List.of(event -> deliveries.incrementAndGet()),
        new InMemoryNotificationStateStore(),
        ignored -> deliverySnapshot(accountingEvents)
    );
    AccountingDelivery delivery = accountingEvents.get(0);
    List<Callable<Void>> commands = java.util.stream.IntStream.range(0, 200)
        .mapToObj(ignored -> (Callable<Void>) () -> {
          service.onAccountingApplied(delivery.event(), delivery.snapshot());
          return null;
        })
        .toList();

    runConcurrently(commands);

    assertThat(deliveries).hasValue(1);
  }

  @Test
  void 기존_onCommitted_callback도_resolver_snapshot으로_알림을_만든다() {
    List<AccountingDelivery> accountingEvents = new ArrayList<>();
    Fixture source = fixture(
        List.of(event -> { }),
        BudgetNotificationErrorHook.noOp(),
        accountingEvents
    );
    commit(source, key("tenant-a", "2026-08"), "request-1", 50);
    AccountingDelivery delivery = accountingEvents.get(0);
    List<BudgetNotificationEvent> events = new ArrayList<>();
    BudgetNotificationService service = new BudgetNotificationService(
        List.of(events::add),
        new InMemoryNotificationStateStore(),
        ignored -> delivery.snapshot()
    );

    service.onCommitted(delivery.event());

    assertThat(events)
        .extracting(BudgetNotificationEvent::threshold)
        .containsExactly(BudgetThreshold.HALF);
  }

  @Test
  @SuppressWarnings("removal")
  void legacy_event는_tags를_불변_복사하고_atomic_event는_tags를_보존하지_않는다() {
    Map<String, String> legacyTags = new HashMap<>();
    legacyTags.put("tenant_id", "tenant-a");
    BudgetNotificationEvent event = new BudgetNotificationEvent(
        key("tenant-a", "2026-08"),
        BudgetThreshold.HALF,
        io.tokenpilot.budget.BudgetState.WARN,
        "threshold reached",
        usd("50"),
        LIMIT,
        legacyTags
    );
    legacyTags.put("tenant_id", "changed");

    assertThat(event.tags()).containsExactly(Map.entry("tenant_id", "tenant-a"));
    assertThatThrownBy(() -> event.tags().put("another", "value"))
        .isInstanceOf(UnsupportedOperationException.class);

    List<BudgetNotificationEvent> atomicEvents = new ArrayList<>();
    Fixture fixture = fixture(
        List.of(atomicEvents::add),
        BudgetNotificationErrorHook.noOp()
    );
    commit(fixture, key("tenant-b", "2026-08"), "request-atomic", 50);

    assertThat(atomicEvents).singleElement().satisfies(atomic -> {
      assertThat(atomic.tags()).isEmpty();
      assertThat(atomic.toString()).doesNotContain("tenant-a", "changed");
    });
  }

  private static ReservationReconciliation commit(
      Fixture fixture,
      BudgetKey key,
      String requestId,
      long actualCost
  ) {
    ReservationId reservationId = reserve(fixture, key, requestId);
    fixture.accounting().markInFlight(reservationId);
    return fixture.accounting().commit(command(requestId, reservationId, actualCost));
  }

  private static ReservationId reserve(
      Fixture fixture,
      BudgetKey key,
      String requestId
  ) {
    BudgetReservationResult result = fixture.stateStore().checkAndReserve(
        request(key, requestId, usd("1.00"))
    );
    assertThat(result.status()).isEqualTo(ReservationStatus.CREATED);
    return result.reservationId();
  }

  private static BudgetReservationRequest request(
      BudgetKey key,
      String requestId,
      Cost estimate
  ) {
    PricingSnapshot snapshot = pricingSnapshot();
    return new BudgetReservationRequest(
        key,
        LIMIT,
        estimate,
        requestId,
        new IdempotencyKey("idempotency-" + requestId),
        snapshot,
        TOKEN_ESTIMATE
    );
  }

  private static ActualUsageCommand command(
      String requestId,
      ReservationId reservationId,
      long actualCost
  ) {
    return new ActualUsageCommand(
        requestId,
        "attempt-" + requestId,
        reservationId,
        TokenUsage.from(actualCost, 0),
        "gpt-4o-mini-request"
    );
  }

  private static Fixture fixture(
      List<BudgetNotificationHandler> handlers,
      BudgetNotificationErrorHook errorHook
  ) {
    return fixture(handlers, errorHook, null);
  }

  private static Fixture fixture(
      List<BudgetNotificationHandler> handlers,
      BudgetNotificationErrorHook errorHook,
      List<AccountingDelivery> accountingEvents
  ) {
    AtomicReference<BudgetStateStore> stateStoreReference = new AtomicReference<>();
    BudgetNotificationService service = new BudgetNotificationService(
        handlers,
        new InMemoryNotificationStateStore(),
        key -> stateStoreReference.get().snapshot(key, LIMIT),
        errorHook
    );
    AtomicInteger sequence = new AtomicInteger();
    List<io.tokenpilot.budget.ReservationAccountingListener> listeners =
        accountingEvents == null
            ? List.of(service)
            : List.of(
                service,
                new io.tokenpilot.budget.ReservationAccountingListener() {
                  @Override
                  public void onCommitted(ReservationAccountingEvent event) {
                  }

                  @Override
                  public void onAccountingApplied(
                      ReservationAccountingEvent event,
                      BudgetSnapshot snapshot
                  ) {
                    accountingEvents.add(new AccountingDelivery(event, snapshot));
                  }
                }
            );
    BudgetStateStore stateStore = LedgerBudgetComponents.inMemoryBudgetStateStore(
        CLOCK,
        () -> new ReservationId("reservation-" + sequence.incrementAndGet()),
        (usage, plan) -> usd(Long.toString(usage.inputTokens())),
        listeners
    );
    stateStoreReference.set(stateStore);
    return new Fixture(
        stateStore,
        LedgerBudgetComponents.reservationAccounting(stateStore)
    );
  }

  private static PricingSnapshot pricingSnapshot() {
    return new PricingSnapshot(
        "gpt-4o-mini-request",
        "pricing-v1",
        "catalog-v1",
        CLOCK.instant(),
        Map.of(
            TokenType.PROMPT, BigDecimal.ONE,
            TokenType.COMPLETION, BigDecimal.ONE
        ),
        USD
    );
  }

  private static BudgetKey key(String tenantId, String window) {
    return new BudgetKey(
        "budget-policy",
        "tenant",
        tenantId,
        BudgetWindow.parse(window)
    );
  }

  private static Cost usd(String amount) {
    return Cost.of(new BigDecimal(amount), USD);
  }

  private static BudgetSnapshot deliverySnapshot(
      List<AccountingDelivery> deliveries
  ) {
    return deliveries.get(0).snapshot();
  }

  private static <T> void runConcurrently(List<? extends Callable<T>> commands)
      throws Exception {
    CountDownLatch ready = new CountDownLatch(commands.size());
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<T>> futures = new ArrayList<>(commands.size());
      for (Callable<T> command : commands) {
        futures.add(executor.submit(() -> {
          ready.countDown();
          if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("start barrier timed out");
          }
          return command.call();
        }));
      }
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (Future<T> future : futures) {
        future.get(5, TimeUnit.SECONDS);
      }
    } finally {
      start.countDown();
    }
  }

  private record Fixture(
      BudgetStateStore stateStore,
      ReservationAccounting accounting
  ) {
  }

  private record AccountingDelivery(
      ReservationAccountingEvent event,
      BudgetSnapshot snapshot
  ) {
  }
}
