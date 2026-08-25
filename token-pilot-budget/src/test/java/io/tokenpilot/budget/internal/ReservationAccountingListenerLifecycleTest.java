package io.tokenpilot.budget.internal;

import io.tokenpilot.budget.ActualUsageCommand;
import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetReservationRequest;
import io.tokenpilot.budget.BudgetReservationResult;
import io.tokenpilot.budget.BudgetSnapshot;
import io.tokenpilot.budget.BudgetWindow;
import io.tokenpilot.budget.IdempotencyKey;
import io.tokenpilot.budget.ReservationAccountingEvent;
import io.tokenpilot.budget.ReservationAccountingListener;
import io.tokenpilot.budget.ReservationAccountingListenerFailureEvent;
import io.tokenpilot.budget.ReservationAccountingListenerPhase;
import io.tokenpilot.budget.ReservationAccountingListenerType;
import io.tokenpilot.budget.ReservationId;
import io.tokenpilot.budget.ReservationReconciliation;
import io.tokenpilot.budget.ReservationReconciliationRequiredEvent;
import io.tokenpilot.budget.ReservationStatus;
import io.tokenpilot.budget.ReservationTokenEstimate;
import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.PricingSnapshot;
import io.tokenpilot.core.domain.TokenType;
import io.tokenpilot.core.domain.TokenUsage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.tokenpilot.budget.AccountingTransitionStatus.APPLIED;
import static io.tokenpilot.budget.AccountingTransitionStatus.REUSED;
import static io.tokenpilot.budget.ReservationState.COMMITTED;
import static io.tokenpilot.budget.ReservationState.RECONCILIATION_REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;

class ReservationAccountingListenerLifecycleTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final Currency KRW = Currency.getInstance("KRW");
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
    private static final ReservationTokenEstimate TOKEN_ESTIMATE =
            new ReservationTokenEstimate(90, 100, 50);

    @Test
    void 모든_예약_결과를_평가_callback으로_전달하고_BLOCKED만_별도_전달한다() {
        List<ReservationStatus> evaluated = new ArrayList<>();
        List<ReservationStatus> blocked = new ArrayList<>();
        ReservationAccountingListener listener = new ReservationAccountingListener() {
            @Override
            public void onCommitted(ReservationAccountingEvent event) {
            }

            @Override
            public void onReservationEvaluated(
                    BudgetReservationRequest request,
                    BudgetReservationResult result
            ) {
                evaluated.add(result.status());
            }

            @Override
            public void onReservationBlocked(
                    BudgetReservationRequest request,
                    BudgetReservationResult result
            ) {
                blocked.add(result.status());
            }
        };
        InMemoryBudgetStateStore store = store(List.of(listener), List.of());

        store.checkAndReserve(legacyRequest("request-1", usd("20.00")));
        store.checkAndReserve(legacyRequest("request-1", usd("20.00")));
        store.checkAndReserve(legacyRequest("request-1", usd("30.00")));
        store.checkAndReserve(legacyRequest("request-2", usd("80.00")));
        store.checkAndReserve(legacyRequest(
                "request-3",
                Cost.of(new BigDecimal("10.00"), KRW)
        ));

        assertThat(evaluated).containsExactly(
                ReservationStatus.CREATED,
                ReservationStatus.REUSED,
                ReservationStatus.CONFLICT,
                ReservationStatus.BLOCKED,
                ReservationStatus.CURRENCY_MISMATCH
        );
        assertThat(blocked).containsExactly(ReservationStatus.BLOCKED);
    }

    @Test
    void RECONCILIATION_REQUIRED는_APPLIED_전이만_snapshot과_함께_한번_전달한다() {
        List<ReservationReconciliationRequiredEvent> events = new ArrayList<>();
        ReservationAccountingListener listener = new ReservationAccountingListener() {
            @Override
            public void onCommitted(ReservationAccountingEvent event) {
            }

            @Override
            public void onReconciliationRequired(
                    ReservationReconciliationRequiredEvent event
            ) {
                events.add(event);
            }
        };
        InMemoryBudgetStateStore store = store(List.of(listener), List.of());
        ReservationId reservationId = reserveUsage(store, "request-1", "dedupe-1", "60.00");
        store.markInFlight(reservationId);

        var applied = store.markReconciliationRequired(reservationId);
        var duplicate = store.markReconciliationRequired(reservationId);

        assertThat(applied.status()).isEqualTo(APPLIED);
        assertThat(duplicate.status()).isEqualTo(REUSED);
        assertThat(events).hasSize(1);
        ReservationReconciliationRequiredEvent event = events.getFirst();
        assertThat(event.transition()).isEqualTo(applied);
        assertThat(event.snapshot().activeReservedCost()).isEqualTo(usd("0.00"));
        assertThat(event.snapshot().pendingReconciliationLiability())
                .isEqualTo(usd("60.00"));
    }

    @Test
    void APPLIED_commit은_같은_linearization_point의_snapshot과_함께_한번_전달한다() {
        List<ReservationAccountingEvent> events = new ArrayList<>();
        AtomicReference<BudgetSnapshot> snapshot = new AtomicReference<>();
        ReservationAccountingListener listener = new ReservationAccountingListener() {
            @Override
            public void onCommitted(ReservationAccountingEvent event) {
            }

            @Override
            public void onAccountingApplied(
                    ReservationAccountingEvent event,
                    BudgetSnapshot accountingSnapshot
            ) {
                events.add(event);
                snapshot.set(accountingSnapshot);
            }
        };
        InMemoryBudgetStateStore store = store(List.of(listener), List.of());
        ReservationId reservationId = reserveUsage(store, "request-1", "dedupe-1", "60.00");
        store.markInFlight(reservationId);

        ReservationReconciliation applied = store.commit(command("request-1", reservationId));
        ReservationReconciliation duplicate = store.commit(command("request-1", reservationId));

        assertThat(applied.transition().status()).isEqualTo(APPLIED);
        assertThat(duplicate.transition().status()).isEqualTo(REUSED);
        assertThat(events).containsExactly(new ReservationAccountingEvent(applied));
        assertThat(snapshot.get().committedCost()).isEqualTo(usd("40.00"));
        assertThat(snapshot.get().activeReservedCost()).isEqualTo(usd("0.00"));
        assertThat(snapshot.get().pendingReconciliationLiability())
                .isEqualTo(usd("0.00"));
    }

    @Test
    void callback과_error_handler_실패를_격리하고_bounded_failure를_계속_전달한다() {
        List<ReservationAccountingListenerFailureEvent> failures = new ArrayList<>();
        AtomicInteger observedEvaluations = new AtomicInteger();
        AtomicInteger observedBlocks = new AtomicInteger();
        AtomicInteger observedPending = new AtomicInteger();
        AtomicInteger observedCommits = new AtomicInteger();

        ReservationAccountingListener failing = failingMetricsListener();
        ReservationAccountingListener following = new ReservationAccountingListener() {
            @Override
            public void onCommitted(ReservationAccountingEvent event) {
                observedCommits.incrementAndGet();
            }

            @Override
            public void onReconciliationRequired(
                    ReservationReconciliationRequiredEvent event
            ) {
                observedPending.incrementAndGet();
            }

            @Override
            public void onReservationEvaluated(
                    BudgetReservationRequest request,
                    BudgetReservationResult result
            ) {
                observedEvaluations.incrementAndGet();
            }

            @Override
            public void onReservationBlocked(
                    BudgetReservationRequest request,
                    BudgetReservationResult result
            ) {
                observedBlocks.incrementAndGet();
            }
        };
        InMemoryBudgetStateStore store = store(
                List.of(failing, following),
                List.of(
                        event -> {
                            throw new IllegalStateException("error handler failed");
                        },
                        failures::add
                )
        );

        BudgetReservationResult blocked = store.checkAndReserve(
                legacyRequest("blocked", usd("100.00"))
        );
        ReservationId pendingId = reserveUsage(
                store,
                "pending-request",
                "pending-dedupe",
                "60.00"
        );
        store.markInFlight(pendingId);
        store.markReconciliationRequired(pendingId);

        ReservationId committedId = reserveUsage(
                store,
                "committed-request",
                "committed-dedupe",
                "20.00"
        );
        store.markInFlight(committedId);
        ReservationReconciliation committed = store.commit(
                command("committed-request", committedId)
        );
        store.commit(command("committed-request", committedId));

        assertThat(blocked.status()).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(committed.transition().resultingState()).isEqualTo(COMMITTED);
        assertThat(store.snapshot(KEY, LIMIT).pendingReconciliationLiability())
                .isEqualTo(usd("60.00"));
        assertThat(store.snapshot(KEY, LIMIT).committedCost()).isEqualTo(usd("40.00"));
        assertThat(observedEvaluations).hasValue(3);
        assertThat(observedBlocks).hasValue(1);
        assertThat(observedPending).hasValue(1);
        assertThat(observedCommits).hasValue(1);
        assertThat(failures)
                .extracting(ReservationAccountingListenerFailureEvent::listenerType)
                .containsOnly(ReservationAccountingListenerType.METRICS);
        assertThat(failures)
                .extracting(ReservationAccountingListenerFailureEvent::phase)
                .contains(
                        ReservationAccountingListenerPhase.RESERVATION_EVALUATED,
                        ReservationAccountingListenerPhase.RESERVATION_BLOCKED,
                        ReservationAccountingListenerPhase.RECONCILIATION_REQUIRED,
                        ReservationAccountingListenerPhase.ACCOUNTING_APPLIED
                );
        assertThat(failures.stream()
                .filter(event -> event.phase()
                        == ReservationAccountingListenerPhase.ACCOUNTING_APPLIED))
                .hasSize(1);
    }

    private static ReservationAccountingListener failingMetricsListener() {
        return new ReservationAccountingListener() {
            @Override
            public void onCommitted(ReservationAccountingEvent event) {
                throw new IllegalStateException("commit listener failed");
            }

            @Override
            public void onReconciliationRequired(
                    ReservationReconciliationRequiredEvent event
            ) {
                throw new IllegalStateException("pending listener failed");
            }

            @Override
            public void onReservationEvaluated(
                    BudgetReservationRequest request,
                    BudgetReservationResult result
            ) {
                throw new IllegalStateException("reservation listener failed");
            }

            @Override
            public void onReservationBlocked(
                    BudgetReservationRequest request,
                    BudgetReservationResult result
            ) {
                throw new IllegalStateException("blocked listener failed");
            }

            @Override
            public ReservationAccountingListenerType listenerType() {
                return ReservationAccountingListenerType.METRICS;
            }
        };
    }

    private static InMemoryBudgetStateStore store(
            List<ReservationAccountingListener> listeners,
            List<io.tokenpilot.budget.ReservationAccountingListenerErrorHandler> errorHandlers
    ) {
        AtomicInteger sequence = new AtomicInteger();
        return new InMemoryBudgetStateStore(
                CLOCK,
                () -> new ReservationId("reservation-" + sequence.incrementAndGet()),
                (usage, plan) -> usd("40.00"),
                listeners,
                errorHandlers
        );
    }

    private static BudgetReservationRequest legacyRequest(String id, Cost amount) {
        return new BudgetReservationRequest(KEY, LIMIT, amount, id);
    }

    private static ReservationId reserveUsage(
            InMemoryBudgetStateStore store,
            String requestId,
            String deduplicationId,
            String amount
    ) {
        return store.checkAndReserve(new BudgetReservationRequest(
                KEY,
                LIMIT,
                usd(amount),
                requestId,
                new IdempotencyKey(deduplicationId),
                pricingSnapshot(),
                TOKEN_ESTIMATE
        )).reservationId();
    }

    private static ActualUsageCommand command(
            String requestId,
            ReservationId reservationId
    ) {
        return new ActualUsageCommand(
                requestId,
                "attempt-1",
                reservationId,
                TokenUsage.from(100, 50),
                "gpt-4o-mini-request"
        );
    }

    private static PricingSnapshot pricingSnapshot() {
        return new PricingSnapshot(
                "gpt-4o-mini-request",
                "pricing-v1",
                "catalog-v1",
                CLOCK.instant(),
                Map.of(
                        TokenType.PROMPT,
                        new BigDecimal("0.10"),
                        TokenType.COMPLETION,
                        new BigDecimal("0.20")
                ),
                USD
        );
    }

    private static Cost usd(String amount) {
        return Cost.of(new BigDecimal(amount), USD);
    }
}
