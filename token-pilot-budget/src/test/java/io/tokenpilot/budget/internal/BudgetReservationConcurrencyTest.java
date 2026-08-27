package io.tokenpilot.budget.internal;

import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetReservationRequest;
import io.tokenpilot.budget.BudgetReservationResult;
import io.tokenpilot.budget.BudgetSnapshot;
import io.tokenpilot.budget.BudgetWindow;
import io.tokenpilot.budget.IdempotencyKey;
import io.tokenpilot.budget.ReservationId;
import io.tokenpilot.budget.ReservationStatus;
import io.tokenpilot.core.domain.Cost;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic concurrency regression test for the #36 reservation linearization point.
 *
 * <p>Runs competing commands from the same starting line and verifies only the
 * public snapshot. This test is not a mathematical proof of concurrency
 * correctness or a replacement for a linearizability checker. The #37
 * commit/release/reconciliation and #46 listener policies are extended after
 * their contracts are implemented.</p>
 */
class BudgetReservationConcurrencyTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Cost LIMIT = usd("1000.00");
    private static final BudgetKey KEY = key("tenant-a", "2026-08");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-18T00:00:00Z"),
            ZoneOffset.UTC
    );

    @RepeatedTest(3)
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void 서로_다른_idempotency_key의_동시_예약은_한도를_초과하지_않는다() throws Exception {
        StoreFixture fixture = fixture();
        Cost reservationAmount = usd("3.00");
        List<Callable<BudgetReservationResult>> commands = IntStream.range(0, 400)
                .mapToObj(index -> (Callable<BudgetReservationResult>) () ->
                        fixture.store().checkAndReserve(
                                KEY,
                                LIMIT,
                                reservationAmount,
                                "request-" + index
                        ))
                .toList();

        List<BudgetReservationResult> results = runConcurrently(commands);
        BudgetSnapshot snapshot = fixture.store().snapshot(KEY, LIMIT);
        Map<ReservationStatus, Long> statuses = statusCounts(results);

        assertThat(statuses)
                .as("snapshot=%s", snapshot)
                .containsEntry(ReservationStatus.CREATED, 333L)
                .containsEntry(ReservationStatus.BLOCKED, 67L);
        assertReservationOnlySnapshot(snapshot, usd("999.00"), 333);
        assertThat(snapshot.effectiveUsage().compareTo(snapshot.limit())).isNegative();
        assertThat(createdCost(results)).isEqualTo(snapshot.activeReservedCost());
        assertThat(fixture.generatedIds()).hasValue(333);
    }

    @RepeatedTest(3)
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void 이미_확정된_비용을_포함해_동시_예약은_한도를_초과하지_않는다() throws Exception {
        StoreFixture fixture = fixture();
        fixture.store().addCost(KEY, LIMIT, usd("400.00"));
        Cost reservationAmount = usd("2.00");
        List<Callable<BudgetReservationResult>> commands = IntStream.range(0, 300)
                .mapToObj(index -> (Callable<BudgetReservationResult>) () ->
                        fixture.store().checkAndReserve(
                                KEY,
                                LIMIT,
                                reservationAmount,
                                "committed-request-" + index
                        ))
                .toList();

        List<BudgetReservationResult> results = runConcurrently(commands);
        BudgetSnapshot snapshot = fixture.store().snapshot(KEY, LIMIT);

        assertThat(statusCounts(results))
                .as("snapshot=%s", snapshot)
                .containsEntry(ReservationStatus.CREATED, 299L)
                .containsEntry(ReservationStatus.BLOCKED, 1L);
        assertThat(snapshot.committedCost()).isEqualTo(usd("400.00"));
        assertThat(snapshot.activeReservedCost()).isEqualTo(usd("598.00"));
        assertThat(snapshot.effectiveUsage()).isEqualTo(usd("998.00"));
        assertThat(snapshot.remaining()).isEqualTo(usd("2.00"));
        assertThat(snapshot.activeReservationIds()).hasSize(299);
        assertThat(createdCost(results)).isEqualTo(snapshot.activeReservedCost());
        assertThat(fixture.generatedIds()).hasValue(299);
    }

    @RepeatedTest(3)
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void 같은_idempotency_key의_동시_예약은_한_번만_생성된다() throws Exception {
        StoreFixture fixture = fixture();
        BudgetReservationRequest request = request(
                KEY,
                "shared-request",
                "gpt-4o-mini",
                "10.00"
        );
        List<Callable<BudgetReservationResult>> commands = IntStream.range(0, 300)
                .mapToObj(ignored -> (Callable<BudgetReservationResult>) () ->
                        fixture.store().checkAndReserve(request))
                .toList();

        List<BudgetReservationResult> results = runConcurrently(commands);
        BudgetSnapshot snapshot = fixture.store().snapshot(KEY, LIMIT);

        assertThat(statusCounts(results))
                .as("snapshot=%s", snapshot)
                .containsEntry(ReservationStatus.CREATED, 1L)
                .containsEntry(ReservationStatus.REUSED, 299L);
        assertThat(reservationIds(results)).hasSize(1);
        assertReservationOnlySnapshot(snapshot, usd("10.00"), 1);
        assertThat(fixture.generatedIds()).hasValue(1);
    }

    @RepeatedTest(3)
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void 같은_idempotency_key의_서로_다른_payload는_하나만_생성되고_나머지는_CONFLICT다()
            throws Exception {
        StoreFixture fixture = fixture();
        BudgetReservationRequest firstPayload = request(
                KEY,
                "shared-request",
                "model-a",
                "10.00"
        );
        BudgetReservationRequest secondPayload = request(
                KEY,
                "shared-request",
                "model-b",
                "20.00"
        );
        List<Callable<BudgetReservationResult>> commands = IntStream.range(0, 300)
                .mapToObj(index -> (Callable<BudgetReservationResult>) () ->
                        fixture.store().checkAndReserve(
                                index % 2 == 0 ? firstPayload : secondPayload
                        ))
                .toList();

        List<BudgetReservationResult> results = runConcurrently(commands);
        BudgetSnapshot snapshot = fixture.store().snapshot(KEY, LIMIT);
        Cost acceptedCost = createdCost(results);

        assertThat(statusCounts(results))
                .as("snapshot=%s", snapshot)
                .containsEntry(ReservationStatus.CREATED, 1L)
                .containsEntry(ReservationStatus.REUSED, 149L)
                .containsEntry(ReservationStatus.CONFLICT, 150L);
        assertThat(reservationIds(results)).hasSize(1);
        assertThat(acceptedCost)
                .isIn(firstPayload.safeUpperBoundCost(), secondPayload.safeUpperBoundCost());
        assertReservationOnlySnapshot(snapshot, acceptedCost, 1);
        assertThat(fixture.generatedIds()).hasValue(1);
    }

    @RepeatedTest(3)
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void 다른_통화의_동시_예약은_bucket_합계를_변경하지_않는다() throws Exception {
        StoreFixture fixture = fixture();
        List<Callable<BudgetReservationResult>> commands = IntStream.range(0, 400)
                .mapToObj(index -> (Callable<BudgetReservationResult>) () -> {
                    Cost amount = index % 2 == 0
                            ? usd("2.00")
                            : Cost.of(new BigDecimal("2.00"), KRW);
                    return fixture.store().checkAndReserve(
                            KEY,
                            LIMIT,
                            amount,
                            "currency-request-" + index
                    );
                })
                .toList();

        List<BudgetReservationResult> results = runConcurrently(commands);
        BudgetSnapshot snapshot = fixture.store().snapshot(KEY, LIMIT);

        assertThat(statusCounts(results))
                .as("snapshot=%s", snapshot)
                .containsEntry(ReservationStatus.CREATED, 200L)
                .containsEntry(ReservationStatus.CURRENCY_MISMATCH, 200L);
        assertReservationOnlySnapshot(snapshot, usd("400.00"), 200);
        assertThat(createdCost(results)).isEqualTo(snapshot.activeReservedCost());
        assertThat(fixture.generatedIds()).hasValue(200);
    }

    @RepeatedTest(3)
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void tenant와_window가_다른_동시_예약은_bucket별로_격리된다() throws Exception {
        StoreFixture fixture = fixture();
        List<BudgetKey> keys = List.of(
                KEY,
                key("tenant-b", "2026-08"),
                key("tenant-a", "2026-09")
        );
        List<Callable<BudgetReservationResult>> commands = IntStream.range(0, 300)
                .mapToObj(index -> (Callable<BudgetReservationResult>) () -> {
                    BudgetKey key = keys.get(index % keys.size());
                    return fixture.store().checkAndReserve(
                            key,
                            LIMIT,
                            usd("2.00"),
                            "isolated-request-" + index
                    );
                })
                .toList();

        List<BudgetReservationResult> results = runConcurrently(commands);

        assertThat(statusCounts(results)).containsEntry(ReservationStatus.CREATED, 300L);
        for (BudgetKey key : keys) {
            BudgetSnapshot snapshot = fixture.store().snapshot(key, LIMIT);
            assertReservationOnlySnapshot(snapshot, usd("200.00"), 100);
        }
        assertThat(fixture.generatedIds()).hasValue(300);
    }

    @Test
    void 실패한_예약_시도는_idempotency_key를_점유하지_않는다() {
        StoreFixture fixture = fixture();

        BudgetReservationResult initial = fixture.store().checkAndReserve(
                KEY,
                LIMIT,
                usd("900.00"),
                "initial-request"
        );
        BudgetReservationResult blocked = fixture.store().checkAndReserve(
                KEY,
                LIMIT,
                usd("100.00"),
                "blocked-request"
        );
        BudgetReservationResult blockedRetry = fixture.store().checkAndReserve(
                KEY,
                LIMIT,
                usd("100.00"),
                "blocked-request"
        );
        BudgetReservationResult mismatch = fixture.store().checkAndReserve(
                KEY,
                LIMIT,
                Cost.of(new BigDecimal("1.00"), KRW),
                "currency-request"
        );
        BudgetReservationResult validCurrencyRetry = fixture.store().checkAndReserve(
                KEY,
                LIMIT,
                usd("1.00"),
                "currency-request"
        );

        assertThat(initial.status()).isEqualTo(ReservationStatus.CREATED);
        assertThat(blocked.status()).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(blocked.reservation()).isNull();
        assertThat(blockedRetry.status()).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(blockedRetry.reservation()).isNull();
        assertThat(mismatch.status()).isEqualTo(ReservationStatus.CURRENCY_MISMATCH);
        assertThat(mismatch.reservation()).isNull();
        assertThat(validCurrencyRetry.status()).isEqualTo(ReservationStatus.CREATED);
        assertThat(validCurrencyRetry.reservation().amount()).isEqualTo(usd("1.00"));
        assertThat(fixture.store().snapshot(KEY, LIMIT).activeReservedCost())
                .isEqualTo(usd("901.00"));
        assertThat(fixture.generatedIds()).hasValue(2);
    }

    private static <T> List<T> runConcurrently(List<? extends Callable<T>> commands)
            throws Exception {
        CountDownLatch ready = new CountDownLatch(commands.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>(commands.size());
        List<T> results = new ArrayList<>(commands.size());
        List<Throwable> failures = new ArrayList<>();
        var executor = Executors.newVirtualThreadPerTaskExecutor();

        try {
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
            executor.shutdown();

            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                for (Future<T> future : futures) {
                    if (!future.isDone()) {
                        future.cancel(true);
                    }
                }
            }

            for (int index = 0; index < futures.size(); index++) {
                Future<T> future = futures.get(index);
                if (future.isCancelled()) {
                    failures.add(new TimeoutException(
                            "concurrent command timed out: index=" + index
                    ));
                    continue;
                }
                try {
                    results.add(future.get());
                } catch (ExecutionException exception) {
                    failures.add(exception.getCause());
                }
            }

            assertThat(failures).as("task failures=%s", failures).isEmpty();
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void assertReservationOnlySnapshot(
            BudgetSnapshot snapshot,
            Cost expectedActiveReservedCost,
            int expectedActiveReservationCount
    ) {
        Cost zero = Cost.zero(snapshot.limit().currency());

        assertThat(snapshot.committedCost())
                .as("snapshot=%s", snapshot)
                .isEqualTo(zero);
        assertThat(snapshot.activeReservedCost())
                .as("snapshot=%s", snapshot)
                .isEqualTo(expectedActiveReservedCost);
        assertThat(snapshot.pendingReconciliationLiability())
                .as("snapshot=%s", snapshot)
                .isEqualTo(zero);
        assertThat(snapshot.effectiveUsage())
                .as("snapshot=%s", snapshot)
                .isEqualTo(expectedActiveReservedCost);
        assertThat(snapshot.activeReservationIds())
                .as("snapshot=%s", snapshot)
                .hasSize(expectedActiveReservationCount);
    }

    private static Map<ReservationStatus, Long> statusCounts(
            List<BudgetReservationResult> results
    ) {
        Map<ReservationStatus, Long> counts = new EnumMap<>(ReservationStatus.class);
        for (BudgetReservationResult result : results) {
            counts.merge(result.status(), 1L, Long::sum);
        }
        return counts;
    }

    private static List<ReservationId> reservationIds(List<BudgetReservationResult> results) {
        return results.stream()
                .map(BudgetReservationResult::reservationId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    private static Cost createdCost(List<BudgetReservationResult> results) {
        return results.stream()
                .filter(result -> result.status() == ReservationStatus.CREATED)
                .map(result -> result.reservation().amount())
                .reduce(Cost.zero(USD), Cost::add);
    }

    private static StoreFixture fixture() {
        AtomicInteger generatedIds = new AtomicInteger();
        InMemoryBudgetStateStore store = new InMemoryBudgetStateStore(
                CLOCK,
                () -> new ReservationId("reservation-" + generatedIds.incrementAndGet())
        );
        return new StoreFixture(store, generatedIds);
    }

    private static BudgetReservationRequest request(
            BudgetKey key,
            String idempotencyKey,
            String modelId,
            String amount
    ) {
        return new BudgetReservationRequest(
                key,
                LIMIT,
                usd(amount),
                new IdempotencyKey(idempotencyKey),
                modelId,
                "pricing-v1",
                "catalog-v1"
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

    private record StoreFixture(
            InMemoryBudgetStateStore store,
            AtomicInteger generatedIds
    ) {
    }
}
