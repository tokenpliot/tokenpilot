package io.tokenpilot.budget.internal;

import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetReservationRequest;
import io.tokenpilot.budget.BudgetReservationResult;
import io.tokenpilot.budget.BudgetSnapshot;
import io.tokenpilot.budget.BudgetWindow;
import io.tokenpilot.budget.IdempotencyKey;
import io.tokenpilot.budget.ReservationId;
import io.tokenpilot.budget.ReservationStatus;
import io.tokenpilot.budget.ReservationState;
import io.tokenpilot.core.domain.Cost;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetReservationStoreTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Cost LIMIT = usd("100.00");
    private static final BudgetKey KEY = new BudgetKey(
            "budget-policy",
            "tenant",
            "tenant-a",
            BudgetWindow.parse("2026-08")
    );
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-15T12:34:56Z"),
            ZoneOffset.UTC
    );

    @Test
    void safe_upper_bound를_원자적으로_예약하고_예약량을_snapshot에_반영한다() {
        InMemoryBudgetStateStore store = store();

        BudgetReservationResult result = store.checkAndReserve(
                KEY,
                LIMIT,
                usd("60.00"),
                "request-1"
        );

        assertThat(result.status()).isEqualTo(ReservationStatus.CREATED);
        assertThat(result.reservation()).isNotNull();
        assertThat(result.reservation().state()).isEqualTo(ReservationState.RESERVED);
        assertThat(result.reservation().amount()).isEqualTo(usd("60.00"));
        assertThat(result.reservation().createdAt()).isEqualTo(CLOCK.instant());
        assertThat(result.snapshot().activeReservedCost()).isEqualTo(usd("60.00"));
        assertThat(result.snapshot().effectiveUsage()).isEqualTo(usd("60.00"));
        assertThat(result.snapshot().remaining()).isEqualTo(usd("40.00"));
        assertThat(result.snapshot().activeReservationIds())
                .containsExactly(result.reservation().id());
    }

    @Test
    void safe_upper_bound가_한도에_도달하면_예약하지_않고_BLOCKED를_반환한다() {
        InMemoryBudgetStateStore store = store();

        BudgetReservationResult result = store.checkAndReserve(
                KEY,
                LIMIT,
                usd("100.00"),
                "request-1"
        );

        assertThat(result.status()).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(result.reservation()).isNull();
        assertThat(result.snapshot().effectiveUsage()).isEqualTo(Cost.zero(USD));
        assertThat(result.snapshot().activeReservationIds()).isEmpty();
        assertThat(store.snapshot(KEY, LIMIT).effectiveUsage()).isEqualTo(Cost.zero(USD));
    }

    @Test
    void 이미_예약된_금액까지_포함해_다음_예약을_BLOCKED한다() {
        InMemoryBudgetStateStore store = store();

        BudgetReservationResult first = store.checkAndReserve(
                KEY,
                LIMIT,
                usd("60.00"),
                "request-1"
        );
        BudgetReservationResult second = store.checkAndReserve(
                KEY,
                LIMIT,
                usd("40.00"),
                "request-2"
        );

        assertThat(first.status()).isEqualTo(ReservationStatus.CREATED);
        assertThat(second.status()).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(store.snapshot(KEY, LIMIT).activeReservedCost()).isEqualTo(usd("60.00"));
    }

    @Test
    void 같은_idempotency_key_재호출은_기존_예약을_REUSED한다() {
        InMemoryBudgetStateStore store = store();

        BudgetReservationResult created = store.checkAndReserve(
                request("request-1", "gpt-4o-mini", "pricing-v1", "catalog-v1", "60.00")
        );
        BudgetReservationResult reused = store.checkAndReserve(
                request("request-1", "gpt-4o-mini", "pricing-v1", "catalog-v1", "60.00")
        );

        assertThat(created.status()).isEqualTo(ReservationStatus.CREATED);
        assertThat(reused.status()).isEqualTo(ReservationStatus.REUSED);
        assertThat(reused.reservation().id()).isEqualTo(created.reservation().id());
        assertThat(store.snapshot(KEY, LIMIT).activeReservedCost()).isEqualTo(usd("60.00"));
        assertThat(store.snapshot(KEY, LIMIT).activeReservationIds()).hasSize(1);
    }

    @Test
    void 같은_idempotency_key의_다른_요청은_CONFLICT이고_기존_예약을_보존한다() {
        InMemoryBudgetStateStore store = store();

        BudgetReservationResult created = store.checkAndReserve(
                request("request-1", "gpt-4o-mini", "pricing-v1", "catalog-v1", "60.00")
        );
        BudgetReservationResult conflict = store.checkAndReserve(
                request("request-1", "gpt-4o", "pricing-v2", "catalog-v2", "70.00")
        );

        assertThat(conflict.status()).isEqualTo(ReservationStatus.CONFLICT);
        assertThat(conflict.reservation().id()).isEqualTo(created.reservation().id());
        assertThat(store.snapshot(KEY, LIMIT).activeReservedCost()).isEqualTo(usd("60.00"));
        assertThat(store.snapshot(KEY, LIMIT).activeReservationIds())
                .containsExactly(created.reservation().id());
    }

    @Test
    void 같은_idempotency_key를_다른_window에서_사용하면_CONFLICT다() {
        InMemoryBudgetStateStore store = store();

        BudgetReservationResult created = store.checkAndReserve(
                request("request-1", "gpt-4o-mini", "pricing-v1", "catalog-v1", "10.00")
        );
        BudgetReservationResult conflict = store.checkAndReserve(
                new BudgetReservationRequest(
                        new BudgetKey("budget-policy", "tenant", "tenant-a", BudgetWindow.parse("2026-09")),
                        LIMIT,
                        usd("10.00"),
                        new IdempotencyKey("request-1"),
                        "gpt-4o-mini",
                        "pricing-v1",
                        "catalog-v1"
                )
        );

        assertThat(conflict.status()).isEqualTo(ReservationStatus.CONFLICT);
        assertThat(conflict.reservation().id()).isEqualTo(created.reservation().id());
        assertThat(store.snapshot(KEY, LIMIT).activeReservedCost()).isEqualTo(usd("10.00"));
        assertThat(store.snapshot(
                new BudgetKey("budget-policy", "tenant", "tenant-a", BudgetWindow.parse("2026-09")),
                LIMIT
        ).effectiveUsage()).isEqualTo(Cost.zero(USD));
    }

    @Test
    void 통화가_다르면_CURRENCY_MISMATCH이며_상태를_변경하지_않는다() {
        InMemoryBudgetStateStore store = store();
        Currency krw = Currency.getInstance("KRW");

        BudgetReservationResult result = store.checkAndReserve(
                KEY,
                LIMIT,
                Cost.of(new BigDecimal("60.00"), krw),
                "request-1"
        );

        assertThat(result.status()).isEqualTo(ReservationStatus.CURRENCY_MISMATCH);
        assertThat(result.reservation()).isNull();
        assertThat(result.snapshot().effectiveUsage()).isEqualTo(Cost.zero(USD));
        assertThat(store.snapshot(KEY, LIMIT).effectiveUsage()).isEqualTo(Cost.zero(USD));
    }

    @Test
    void 기존_bucket과_다른_통화의_예약은_CURRENCY_MISMATCH이며_기존상태를_보존한다() {
        InMemoryBudgetStateStore store = store();
        Currency krw = Currency.getInstance("KRW");
        store.addCost(KEY, LIMIT, usd("10.00"));

        BudgetReservationResult result = store.checkAndReserve(
                KEY,
                Cost.of(new BigDecimal("1000.00"), krw),
                Cost.of(new BigDecimal("100.00"), krw),
                "request-1"
        );

        assertThat(result.status()).isEqualTo(ReservationStatus.CURRENCY_MISMATCH);
        assertThat(store.snapshot(KEY, LIMIT).effectiveUsage()).isEqualTo(usd("10.00"));
    }

    @Test
    void 기존_idempotency_key에_다른_통화를_사용하면_CONFLICT이고_기존예약을_보존한다() {
        InMemoryBudgetStateStore store = store();
        Currency krw = Currency.getInstance("KRW");
        BudgetReservationResult created = store.checkAndReserve(
                KEY,
                LIMIT,
                usd("60.00"),
                "request-1"
        );

        BudgetReservationResult conflict = store.checkAndReserve(
                KEY,
                LIMIT,
                Cost.of(new BigDecimal("60.00"), krw),
                "request-1"
        );

        assertThat(conflict.status()).isEqualTo(ReservationStatus.CONFLICT);
        assertThat(conflict.reservationId()).isEqualTo(created.reservationId());
        assertThat(store.snapshot(KEY, LIMIT).activeReservedCost()).isEqualTo(usd("60.00"));
    }

    @Test
    void 서로_다른_bucket의_동시_예약도_각각_격리된다() throws Exception {
        InMemoryBudgetStateStore store = store();
        BudgetKey otherKey = new BudgetKey(
                "budget-policy",
                "tenant",
                "tenant-b",
                BudgetWindow.parse("2026-08")
        );

        BudgetReservationResult tenantA = store.checkAndReserve(KEY, LIMIT, usd("60.00"), "request-a");
        BudgetReservationResult tenantB = store.checkAndReserve(otherKey, LIMIT, usd("60.00"), "request-b");

        assertThat(tenantA.status()).isEqualTo(ReservationStatus.CREATED);
        assertThat(tenantB.status()).isEqualTo(ReservationStatus.CREATED);
        assertThat(store.snapshot(KEY, LIMIT).activeReservedCost()).isEqualTo(usd("60.00"));
        assertThat(store.snapshot(otherKey, LIMIT).activeReservedCost()).isEqualTo(usd("60.00"));
    }

    @Test
    void 같은_bucket의_동시_예약은_한도보다_많이_예약하지_않는다() throws Exception {
        InMemoryBudgetStateStore store = store();
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<BudgetReservationResult> first = executor.submit(() -> {
                start.await();
                return store.checkAndReserve(KEY, LIMIT, usd("60.00"), "request-1");
            });
            Future<BudgetReservationResult> second = executor.submit(() -> {
                start.await();
                return store.checkAndReserve(KEY, LIMIT, usd("60.00"), "request-2");
            });
            start.countDown();

            Set<ReservationStatus> statuses = Set.of(
                    first.get(5, TimeUnit.SECONDS).status(),
                    second.get(5, TimeUnit.SECONDS).status()
            );
            assertThat(statuses).containsExactlyInAnyOrder(
                    ReservationStatus.CREATED,
                    ReservationStatus.BLOCKED
            );
            assertThat(store.snapshot(KEY, LIMIT).activeReservedCost()).isEqualTo(usd("60.00"));
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void snapshot은_legacy_누적비용과_예약비용을_분리해_보여준다() {
        InMemoryBudgetStateStore store = store();
        store.addCost(KEY, LIMIT, usd("10.00"));
        store.checkAndReserve(KEY, LIMIT, usd("20.00"), "request-1");

        BudgetSnapshot snapshot = store.snapshot(KEY, LIMIT);

        assertThat(snapshot.committedCost()).isEqualTo(usd("10.00"));
        assertThat(snapshot.activeReservedCost()).isEqualTo(usd("20.00"));
        assertThat(snapshot.pendingReconciliationLiability()).isEqualTo(Cost.zero(USD));
        assertThat(snapshot.effectiveUsage()).isEqualTo(usd("30.00"));
        assertThat(snapshot.remaining()).isEqualTo(usd("70.00"));
    }

    private static InMemoryBudgetStateStore store() {
        AtomicInteger sequence = new AtomicInteger();
        return new InMemoryBudgetStateStore(
                CLOCK,
                () -> new ReservationId("reservation-" + sequence.incrementAndGet())
        );
    }

    private static BudgetReservationRequest request(
            String idempotencyKey,
            String modelId,
            String pricingPolicyId,
            String catalogVersion,
            String amount
    ) {
        return new BudgetReservationRequest(
                KEY,
                LIMIT,
                usd(amount),
                new IdempotencyKey(idempotencyKey),
                modelId,
                pricingPolicyId,
                catalogVersion
        );
    }

    private static Cost usd(String amount) {
        return Cost.of(new BigDecimal(amount), USD);
    }
}
