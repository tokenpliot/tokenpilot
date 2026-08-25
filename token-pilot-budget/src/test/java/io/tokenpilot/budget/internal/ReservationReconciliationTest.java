package io.tokenpilot.budget.internal;

import io.tokenpilot.budget.ActualUsageCommand;
import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetReservationRequest;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.budget.BudgetWindow;
import io.tokenpilot.budget.IdempotencyKey;
import io.tokenpilot.budget.ReservationId;
import io.tokenpilot.budget.ReservationAccounting;
import io.tokenpilot.budget.ReservationAccountingEvent;
import io.tokenpilot.budget.ReservationAccountingReason;
import io.tokenpilot.budget.ReservationActualTokens;
import io.tokenpilot.budget.ReservationReconciliation;
import io.tokenpilot.budget.ReservationReconciliationRequiredEvent;
import io.tokenpilot.budget.ReservationStatus;
import io.tokenpilot.budget.ReservationTokenEstimate;
import io.tokenpilot.core.CostCalculator;
import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.PricingPlan;
import io.tokenpilot.core.domain.PricingSnapshot;
import io.tokenpilot.core.domain.TokenType;
import io.tokenpilot.core.domain.TokenUsage;
import io.tokenpilot.core.domain.TokenUsageDetails;
import io.tokenpilot.core.domain.UsageSource;
import io.tokenpilot.core.internal.LedgerComponents;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.tokenpilot.budget.ReservationState.COMMITTED;
import static io.tokenpilot.budget.ReservationState.IN_FLIGHT;
import static io.tokenpilot.budget.ReservationState.RECONCILIATION_REQUIRED;
import static io.tokenpilot.budget.AccountingTransitionStatus.CONFLICT;
import static io.tokenpilot.budget.AccountingTransitionStatus.REUSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationReconciliationTest {

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
    private static final ReservationTokenEstimate TOKEN_ESTIMATE =
            new ReservationTokenEstimate(90, 100, 50);

    @Test
    @DisplayName("예약 시점 pricing snapshot으로 actual 비용을 한 번만 계산한다")
    void calculatesActualCostOnceWithReservedPricingSnapshot() {
        AtomicInteger calculationCount = new AtomicInteger();
        AtomicReference<PricingPlan> calculatedPlan = new AtomicReference<>();
        CostCalculator calculator = (usage, plan) -> {
            calculationCount.incrementAndGet();
            calculatedPlan.set(plan);
            return usd("40.00");
        };
        InMemoryBudgetStateStore store = store(calculator);
        PricingSnapshot snapshot = pricingSnapshot();
        ReservationId reservationId = reserve(store, snapshot, usd("60.00"));
        store.markInFlight(reservationId);

        ReservationReconciliation reconciliation = store.commit(
                new ActualUsageCommand(
                        "request-1",
                        "attempt-1",
                        reservationId,
                        TokenUsage.from(100, 50),
                        "gpt-4o-mini-request"
                )
        );

        assertThat(calculationCount).hasValue(1);
        assertThat(calculatedPlan.get().modelId()).isEqualTo(snapshot.modelId());
        assertThat(calculatedPlan.get().pricingPolicyId())
                .isEqualTo(snapshot.pricingPolicyId());
        assertThat(calculatedPlan.get().rates()).isEqualTo(snapshot.rates());
        assertThat(calculatedPlan.get().currency()).isEqualTo(snapshot.currency());
        assertThat(reconciliation.transition().previousState()).isEqualTo(IN_FLIGHT);
        assertThat(reconciliation.transition().resultingState()).isEqualTo(COMMITTED);
        assertThat(reconciliation.reason())
                .isEqualTo(ReservationAccountingReason.ACTUAL_USAGE_REPORTED);
    }

    @Test
    @DisplayName("actual이 estimate보다 작으면 음수 delta를 반환한다")
    void returnsNegativeDeltaWhenActualIsBelowEstimate() {
        ReservationReconciliation reconciliation = reconcile("60.00", "40.00");

        assertThat(reconciliation.delta()).isEqualByComparingTo("-20.00");
    }

    @Test
    @DisplayName("actual이 estimate와 같으면 0 delta를 반환한다")
    void returnsZeroDeltaWhenActualEqualsEstimate() {
        ReservationReconciliation reconciliation = reconcile("60.00", "60.00");

        assertThat(reconciliation.delta()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("actual이 estimate보다 크면 양수 delta를 반환한다")
    void returnsPositiveDeltaWhenActualIsAboveEstimate() {
        ReservationReconciliation reconciliation = reconcile("60.00", "80.00");

        assertThat(reconciliation.delta()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("actual 정산으로 limit을 넘으면 결과에 초과 상태를 남긴다")
    void reportsOverLimitWhenActualExceedsBudgetLimit() {
        ReservationReconciliation reconciliation = reconcile("60.00", "120.00");

        assertThat(reconciliation.overLimit()).isTrue();
    }

    @Test
    @DisplayName("actual 초과로 limit을 넘으면 비용을 반영하고 다음 예약을 차단한다")
    void blocksNextReservationAfterActualExceedsLimit() {
        InMemoryBudgetStateStore store = store((usage, plan) -> usd("120.00"));
        PricingSnapshot snapshot = pricingSnapshot();
        ReservationId reservationId = reserve(store, snapshot, usd("60.00"));
        store.markInFlight(reservationId);

        ReservationReconciliation reconciliation = store.commit(
                command(reservationId)
        );
        var blocked = store.checkAndReserve(
                new BudgetReservationRequest(
                        KEY,
                        LIMIT,
                        usd("1.00"),
                        "request-2",
                        new IdempotencyKey("deduplication-2"),
                        snapshot,
                        TOKEN_ESTIMATE
                )
        );

        assertThat(reconciliation.overLimit()).isTrue();
        assertThat(store.snapshot(KEY, LIMIT).committedCost())
                .isEqualTo(usd("120.00"));
        assertThat(blocked.status()).isEqualTo(ReservationStatus.BLOCKED);
    }

    @Test
    @DisplayName("callback timeout은 estimate를 pending에 유지해 다음 예약에 반영한다")
    void keepsTimedOutInFlightLiabilityInAdmission() {
        InMemoryBudgetStateStore store = store((usage, plan) -> usd("40.00"));
        PricingSnapshot snapshot = pricingSnapshot();
        ReservationId reservationId = reserve(store, snapshot, usd("60.00"));
        store.markInFlight(reservationId);

        var transition = store.markReconciliationRequired(
                reservationId,
                ReservationAccountingReason.CALLBACK_TIMED_OUT
        );
        var blocked = store.checkAndReserve(
                new BudgetReservationRequest(
                        KEY,
                        LIMIT,
                        usd("50.00"),
                        "request-2",
                        new IdempotencyKey("deduplication-2"),
                        snapshot,
                        TOKEN_ESTIMATE
                )
        );

        assertThat(transition.previousState()).isEqualTo(IN_FLIGHT);
        assertThat(transition.resultingState())
                .isEqualTo(RECONCILIATION_REQUIRED);
        assertThat(store.snapshot(KEY, LIMIT).pendingReconciliationLiability())
                .isEqualTo(usd("60.00"));
        assertThat(blocked.status()).isEqualTo(ReservationStatus.BLOCKED);
    }

    @Test
    @DisplayName("actual 정산이 limit과 같으면 초과로 표시하지 않는다")
    void doesNotReportOverLimitAtExactBudgetLimit() {
        ReservationReconciliation reconciliation = reconcile("60.00", "100.00");

        assertThat(reconciliation.overLimit()).isFalse();
    }

    @Test
    @DisplayName("정산 결과는 요청과 시도, 예약, 모델, pricing 정보를 연결한다")
    void correlatesRequestReservationModelsAndPricing() {
        InMemoryBudgetStateStore store = store((usage, plan) -> usd("40.00"));
        PricingSnapshot snapshot = pricingSnapshot();
        ReservationId reservationId = reserve(store, snapshot, usd("60.00"));
        store.markInFlight(reservationId);

        ReservationReconciliation reconciliation = store.commit(
                command(reservationId)
        );

        assertThat(reconciliation.requestId()).isEqualTo("request-1");
        assertThat(reconciliation.attemptId()).isEqualTo("attempt-1");
        assertThat(reconciliation.reservationId()).isEqualTo(reservationId);
        assertThat(reconciliation.budgetKey()).isEqualTo(KEY);
        assertThat(reconciliation.requestModelId()).isEqualTo(snapshot.modelId());
        assertThat(reconciliation.responseModelId())
                .isEqualTo("gpt-4o-mini-request");
        assertThat(reconciliation.pricingPolicyId())
                .isEqualTo(snapshot.pricingPolicyId());
        assertThat(reconciliation.catalogVersion())
                .isEqualTo(snapshot.catalogVersion());
        assertThat(reconciliation.estimate()).isEqualTo(usd("60.00"));
        assertThat(reconciliation.actual()).isEqualTo(usd("40.00"));
        assertThat(reconciliation.tokenEstimate()).isEqualTo(TOKEN_ESTIMATE);
        assertThat(reconciliation.actualTokens())
                .isEqualTo(ReservationActualTokens.from(TokenUsage.from(100, 50)));
        assertThat(reconciliation.inputTokenDelta()).isEqualTo(10);
        assertThat(reconciliation.outputTokenDelta()).isZero();
        assertThat(reconciliation.totalTokenDelta()).isEqualTo(10);
        assertThat(reconciliation.currency()).isEqualTo(USD);
        assertThat(reconciliation.transition().previousState()).isEqualTo(IN_FLIGHT);
        assertThat(reconciliation.transition().resultingState()).isEqualTo(COMMITTED);
        assertThat(reconciliation.reason())
                .isEqualTo(ReservationAccountingReason.ACTUAL_USAGE_REPORTED);
    }

    @Test
    @DisplayName("request ID와 idempotency key가 달라도 예약된 요청을 정산한다")
    void reconcilesWhenRequestIdDiffersFromIdempotencyKey() {
        InMemoryBudgetStateStore store = store((usage, plan) -> usd("40.00"));
        ReservationId reservationId = reserve(
                store,
                pricingSnapshot(),
                usd("60.00")
        );
        store.markInFlight(reservationId);

        ReservationReconciliation reconciliation = store.commit(
                command(reservationId)
        );

        assertThat(reconciliation.requestId()).isEqualTo("request-1");
    }

    @Test
    @DisplayName("late actual도 예약 시점 가격으로 한 번 계산해 pending을 정산한다")
    void calculatesLateActualOnceAndReconcilesPendingReservation() {
        AtomicInteger calculationCount = new AtomicInteger();
        InMemoryBudgetStateStore store = store((usage, plan) -> {
            calculationCount.incrementAndGet();
            return usd("40.00");
        });
        ReservationId reservationId = reserve(
                store,
                pricingSnapshot(),
                usd("60.00")
        );
        store.markInFlight(reservationId);
        store.markReconciliationRequired(reservationId);

        ReservationReconciliation reconciliation = store.reconcileLateActual(
                command(reservationId)
        );

        assertThat(calculationCount).hasValue(1);
        assertThat(reconciliation.transition().previousState())
                .isEqualTo(RECONCILIATION_REQUIRED);
        assertThat(reconciliation.transition().resultingState()).isEqualTo(COMMITTED);
        assertThat(reconciliation.reason())
                .isEqualTo(ReservationAccountingReason.LATE_ACTUAL_USAGE_REPORTED);
        assertThat(store.snapshot(KEY, LIMIT).pendingReconciliationLiability())
                .isEqualTo(usd("0.00"));
        assertThat(store.snapshot(KEY, LIMIT).committedCost()).isEqualTo(usd("40.00"));
    }

    @Test
    @DisplayName("pricing snapshot이 없는 예약은 usage 정산 전에 거부한다")
    void rejectsUsageReconciliationWithoutReservedPricingSnapshot() {
        AtomicInteger calculationCount = new AtomicInteger();
        InMemoryBudgetStateStore store = store((usage, plan) -> {
            calculationCount.incrementAndGet();
            return usd("40.00");
        });
        ReservationId reservationId = store.checkAndReserve(
                KEY,
                LIMIT,
                usd("60.00"),
                "request-1"
        ).reservationId();
        store.markInFlight(reservationId);
        var before = store.snapshot(KEY, LIMIT);

        assertThatThrownBy(() -> store.commit(command(reservationId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("reservation does not contain a pricing snapshot");

        assertThat(calculationCount).hasValue(0);
        assertThat(store.snapshot(KEY, LIMIT)).isEqualTo(before);
    }

    @Test
    @DisplayName("token estimate가 없는 예약은 비용 계산 전에 정산을 거부한다")
    void rejectsUsageReconciliationWithoutTokenEstimate() {
        AtomicInteger calculationCount = new AtomicInteger();
        InMemoryBudgetStateStore store = store((usage, plan) -> {
            calculationCount.incrementAndGet();
            return usd("40.00");
        });
        PricingSnapshot snapshot = pricingSnapshot();
        ReservationId reservationId = store.checkAndReserve(
                new BudgetReservationRequest(
                        KEY,
                        LIMIT,
                        usd("60.00"),
                        "request-1",
                        new IdempotencyKey("deduplication-1"),
                        snapshot.modelId(),
                        snapshot.pricingPolicyId(),
                        snapshot.catalogVersion(),
                        Optional.of(snapshot)
                )
        ).reservationId();
        store.markInFlight(reservationId);
        var before = store.snapshot(KEY, LIMIT);

        assertThatThrownBy(() -> store.commit(command(reservationId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("reservation does not contain a token estimate");

        assertThat(calculationCount).hasValue(0);
        assertThat(store.snapshot(KEY, LIMIT)).isEqualTo(before);
    }

    @Test
    @DisplayName("다른 request ID는 비용 계산 전에 거부한다")
    void rejectsMismatchedRequestIdBeforeCostCalculation() {
        AtomicInteger calculationCount = new AtomicInteger();
        InMemoryBudgetStateStore store = store((usage, plan) -> {
            calculationCount.incrementAndGet();
            return usd("40.00");
        });
        ReservationId reservationId = reserve(
                store,
                pricingSnapshot(),
                usd("60.00")
        );
        store.markInFlight(reservationId);
        var before = store.snapshot(KEY, LIMIT);

        assertThatThrownBy(
                () -> store.commit(
                        new ActualUsageCommand(
                                "different-request",
                                "attempt-1",
                                reservationId,
                                TokenUsage.from(100, 50),
                                "gpt-4o-mini-request"
                        )
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("requestId must match the reservation request");

        assertThat(calculationCount).hasValue(0);
        assertThat(store.snapshot(KEY, LIMIT)).isEqualTo(before);
    }

    @Test
    @DisplayName("다른 response model은 예약 가격으로 계산하기 전에 거부한다")
    void rejectsMismatchedResponseModelBeforeCostCalculation() {
        AtomicInteger calculationCount = new AtomicInteger();
        InMemoryBudgetStateStore store = store((usage, plan) -> {
            calculationCount.incrementAndGet();
            return usd("40.00");
        });
        ReservationId reservationId = reserve(
                store,
                pricingSnapshot(),
                usd("60.00")
        );
        store.markInFlight(reservationId);
        var before = store.snapshot(KEY, LIMIT);

        assertThatThrownBy(() -> store.commit(new ActualUsageCommand(
                "request-1",
                "attempt-1",
                reservationId,
                TokenUsage.from(100, 50),
                "provider-routed-model"
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "responseModelId must match the reservation pricing snapshot"
                );

        assertThat(calculationCount).hasValue(0);
        assertThat(store.snapshot(KEY, LIMIT)).isEqualTo(before);
    }

    @Test
    @DisplayName("pricing mismatch pending은 같은 잘못된 모델의 late actual로 확정하지 않는다")
    void rejectsLateActualThatStillMismatchesReservedPricing() {
        AtomicInteger calculationCount = new AtomicInteger();
        InMemoryBudgetStateStore store = store((usage, plan) -> {
            calculationCount.incrementAndGet();
            return usd("40.00");
        });
        ReservationId reservationId = reserve(
                store,
                pricingSnapshot(),
                usd("60.00")
        );
        store.markInFlight(reservationId);
        store.markReconciliationRequired(
                reservationId,
                ReservationAccountingReason.PRICING_RECONCILIATION_REQUIRED
        );
        var before = store.snapshot(KEY, LIMIT);

        assertThatThrownBy(() -> store.reconcileLateActual(
                new ActualUsageCommand(
                        "request-1",
                        "attempt-1",
                        reservationId,
                        TokenUsage.from(100, 50),
                        "provider-routed-model"
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "responseModelId must match the reservation pricing snapshot"
                );

        assertThat(calculationCount).hasValue(0);
        assertThat(store.snapshot(KEY, LIMIT)).isEqualTo(before);
        assertThat(before.pendingReconciliationLiability())
                .isEqualTo(usd("60.00"));
    }

    @Test
    @DisplayName("response model pricing snapshot으로 pending actual을 한 번만 재정산한다")
    void reconcilesPendingActualWithExplicitResponsePricingSnapshot() {
        AtomicInteger calculationCount = new AtomicInteger();
        List<ReservationAccountingEvent> events = new ArrayList<>();
        InMemoryBudgetStateStore store = new InMemoryBudgetStateStore(
                CLOCK,
                () -> new ReservationId("reservation-repriced"),
                (usage, plan) -> {
                    calculationCount.incrementAndGet();
                    assertThat(plan.modelId()).isEqualTo("provider-routed-model");
                    return usd("25.00");
                },
                List.of(events::add)
        );
        PricingSnapshot requestSnapshot = pricingSnapshot();
        PricingSnapshot responseSnapshot = new PricingSnapshot(
                "provider-routed-model",
                "provider-policy",
                "catalog-v2",
                CLOCK.instant().plusSeconds(1),
                Map.of(
                        TokenType.PROMPT, new BigDecimal("0.20"),
                        TokenType.COMPLETION, new BigDecimal("0.40")
                ),
                USD
        );
        ReservationId reservationId = reserve(store, requestSnapshot, usd("60.00"));
        store.markInFlight(reservationId);
        ActualUsageCommand command = new ActualUsageCommand(
                "request-1",
                "attempt-1",
                reservationId,
                TokenUsage.from(100, 50),
                "provider-routed-model"
        );
        store.markReconciliationRequired(
                command,
                ReservationAccountingReason.PRICING_RECONCILIATION_REQUIRED
        );
        var pendingBeforeInvalidCallback = store.snapshot(KEY, LIMIT);
        ActualUsageCommand conflictingCommand = new ActualUsageCommand(
                "request-1",
                "attempt-1",
                reservationId,
                TokenUsage.from(101, 50),
                "provider-routed-model"
        );
        assertThatThrownBy(() -> store.reconcileLateActual(
                conflictingCommand,
                responseSnapshot
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "actual usage callback conflicts with the pending pricing callback"
                );
        assertThat(store.snapshot(KEY, LIMIT)).isEqualTo(pendingBeforeInvalidCallback);

        assertThatThrownBy(() -> store.reconcileLateActual(
                command,
                new PricingSnapshot(
                        "wrong-model",
                        "provider-policy",
                        "catalog-v2",
                        CLOCK.instant(),
                        responseSnapshot.rates(),
                        USD
                )
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("responseModelId must match the actual pricing snapshot");
        assertThat(store.snapshot(KEY, LIMIT)).isEqualTo(pendingBeforeInvalidCallback);

        ReservationReconciliation applied = store.reconcileLateActual(
                command,
                responseSnapshot
        );
        ReservationReconciliation duplicate = store.reconcileLateActual(
                command,
                new PricingSnapshot(
                        "provider-routed-model",
                        "provider-policy",
                        "catalog-v2",
                        CLOCK.instant().plusSeconds(2),
                        responseSnapshot.rates(),
                        USD
                )
        );

        assertThat(calculationCount).hasValue(1);
        assertThat(applied.transition().status()).isEqualTo(
                io.tokenpilot.budget.AccountingTransitionStatus.APPLIED
        );
        assertThat(duplicate.transition().status()).isEqualTo(REUSED);
        assertThat(applied.pricingSnapshot()).isEqualTo(requestSnapshot);
        assertThat(applied.actualPricingSnapshot()).isEqualTo(responseSnapshot);
        assertThat(applied.requestModelId()).isEqualTo(requestSnapshot.modelId());
        assertThat(applied.responseModelId()).isEqualTo(responseSnapshot.modelId());
        assertThat(applied.actual()).isEqualTo(usd("25.00"));
        assertThat(events).containsExactly(new ReservationAccountingEvent(applied));
        assertThat(store.snapshot(KEY, LIMIT).pendingReconciliationLiability())
                .isEqualTo(usd("0.00"));
        assertThat(store.snapshot(KEY, LIMIT).committedCost())
                .isEqualTo(usd("25.00"));
    }

    @Test
    @DisplayName("pricing mismatch event는 provider actual과 response model을 보존한다")
    void preservesPendingActualInPricingReconciliationEvent() {
        List<ReservationReconciliationRequiredEvent> events = new ArrayList<>();
        InMemoryBudgetStateStore store = new InMemoryBudgetStateStore(
                CLOCK,
                () -> new ReservationId("reservation-pending-pricing"),
                LedgerComponents.defaultCostCalculator(),
                List.of(new io.tokenpilot.budget.ReservationAccountingListener() {
                    @Override
                    public void onCommitted(ReservationAccountingEvent event) {
                    }

                    @Override
                    public void onReconciliationRequired(
                            ReservationReconciliationRequiredEvent event
                    ) {
                        events.add(event);
                    }
                })
        );
        ReservationId reservationId = reserve(store, pricingSnapshot(), usd("60.00"));
        store.markInFlight(reservationId);
        ActualUsageCommand command = new ActualUsageCommand(
                "request-1",
                "attempt-1",
                reservationId,
                TokenUsage.from(100, 50),
                "provider-routed-model"
        );

        store.markReconciliationRequired(
                command,
                ReservationAccountingReason.PRICING_RECONCILIATION_REQUIRED
        );

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().pendingActualUsage()).contains(command);
        assertThat(events.getFirst().reason()).isEqualTo(
                ReservationAccountingReason.PRICING_RECONCILIATION_REQUIRED
        );
    }

    @Test
    @DisplayName("계산된 actual 통화가 예약 가격 통화와 다르면 상태를 변경하지 않는다")
    void rejectsCalculatedCostWithUnexpectedCurrency() {
        Currency eur = Currency.getInstance("EUR");
        InMemoryBudgetStateStore store = store(
                (usage, plan) -> Cost.of(new BigDecimal("40.00"), eur)
        );
        ReservationId reservationId = reserve(
                store,
                pricingSnapshot(),
                usd("60.00")
        );
        store.markInFlight(reservationId);
        var before = store.snapshot(KEY, LIMIT);

        assertThatThrownBy(() -> store.commit(command(reservationId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("calculated cost must use the pricing snapshot currency");

        assertThat(store.snapshot(KEY, LIMIT)).isEqualTo(before);
    }

    @Test
    @DisplayName("actual usage를 확인할 수 없으면 정산 명령으로 만들지 않는다")
    void rejectsUnavailableActualUsage() {
        assertThatThrownBy(
                () -> new ActualUsageCommand(
                        "request-1",
                        "attempt-1",
                        new ReservationId("reservation-1"),
                        TokenUsage.unavailable(Map.of()),
                        "gpt-4o-mini-request"
                )
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("usage must be available for actual reconciliation");
    }

    @ParameterizedTest
    @EnumSource(
            value = UsageSource.class,
            names = {"PROVIDER_REPORTED", "PROVIDER_DERIVED"},
            mode = EnumSource.Mode.EXCLUDE
    )
    @DisplayName("provider actual이 아닌 usage는 정산 명령으로 만들지 않는다")
    void rejectsNonProviderUsageSources(UsageSource source) {
        TokenUsage usage = new TokenUsage(
                100,
                50,
                TokenUsageDetails.unreported(),
                source,
                Map.of()
        );

        assertThatThrownBy(
                () -> new ActualUsageCommand(
                        "request-1",
                        "attempt-1",
                        new ReservationId("reservation-1"),
                        usage,
                        "gpt-4o-mini-request"
                )
        ).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("snapshot 없는 호환 예약은 공개 cost-only commit으로 정산한다")
    void settlesLegacyReservationThroughCostOnlyCommit() {
        InMemoryBudgetStateStore store = store((usage, plan) -> usd("40.00"));
        ReservationAccounting accounting =
                LedgerBudgetComponents.reservationAccounting(store);
        ReservationId reservationId = store.checkAndReserve(
                KEY,
                LIMIT,
                usd("60.00"),
                "legacy-request"
        ).reservationId();
        accounting.markInFlight(reservationId);

        var transition = accounting.commitCost(reservationId, usd("40.00"));

        assertThat(transition.previousState()).isEqualTo(IN_FLIGHT);
        assertThat(transition.resultingState()).isEqualTo(COMMITTED);
        assertThat(store.snapshot(KEY, LIMIT).activeReservedCost())
                .isEqualTo(usd("0.00"));
        assertThat(store.snapshot(KEY, LIMIT).committedCost())
                .isEqualTo(usd("40.00"));
    }

    @Test
    @DisplayName("snapshot 없는 pending 호환 예약은 공개 cost-only late actual로 정산한다")
    void settlesLegacyPendingReservationThroughCostOnlyLateActual() {
        InMemoryBudgetStateStore store = store((usage, plan) -> usd("40.00"));
        ReservationAccounting accounting =
                LedgerBudgetComponents.reservationAccounting(store);
        ReservationId reservationId = store.checkAndReserve(
                KEY,
                LIMIT,
                usd("60.00"),
                "legacy-request"
        ).reservationId();
        accounting.markInFlight(reservationId);
        accounting.markReconciliationRequired(reservationId);

        var transition = accounting.reconcileLateActualCost(
                reservationId,
                usd("40.00")
        );

        assertThat(transition.previousState()).isEqualTo(RECONCILIATION_REQUIRED);
        assertThat(transition.resultingState()).isEqualTo(COMMITTED);
        assertThat(store.snapshot(KEY, LIMIT).pendingReconciliationLiability())
                .isEqualTo(usd("0.00"));
        assertThat(store.snapshot(KEY, LIMIT).committedCost())
                .isEqualTo(usd("40.00"));
    }

    @Test
    @DisplayName("완전한 usage metadata 예약은 cost-only 경로로 우회 정산하지 않는다")
    void doesNotBypassUsageReconciliationWithCostOnlyCommit() {
        InMemoryBudgetStateStore store = store((usage, plan) -> usd("40.00"));
        ReservationAccounting accounting =
                LedgerBudgetComponents.reservationAccounting(store);
        ReservationId reservationId = reserve(
                store,
                pricingSnapshot(),
                usd("60.00")
        );
        accounting.markInFlight(reservationId);
        var before = store.snapshot(KEY, LIMIT);

        assertThatThrownBy(() -> accounting.commitCost(reservationId, usd("40.00")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "usage-based reconciliation is required for reservations with pricing and token metadata"
                );
        assertThat(store.snapshot(KEY, LIMIT)).isEqualTo(before);
    }

    @Test
    @DisplayName("동일 commit callback은 actual 비용을 다시 계산하지 않는다")
    void doesNotRecalculateCostForDuplicateCommitCallback() {
        AtomicInteger calculationCount = new AtomicInteger();
        InMemoryBudgetStateStore store = store((usage, plan) -> {
            calculationCount.incrementAndGet();
            return usd("40.00");
        });
        ReservationId reservationId = reserve(
                store,
                pricingSnapshot(),
                usd("60.00")
        );
        store.markInFlight(reservationId);

        ReservationReconciliation first = store.commit(command(reservationId));
        ReservationReconciliation duplicate = store.commit(command(reservationId));

        assertThat(calculationCount).hasValue(1);
        assertThat(first.transition().status().isApplied()).isTrue();
        assertThat(duplicate.transition().status()).isEqualTo(REUSED);
        assertThat(duplicate.actual()).isEqualTo(first.actual());
        assertThat(duplicate.overLimit()).isEqualTo(first.overLimit());
    }

    @Test
    @DisplayName("동일 late actual callback은 actual 비용을 다시 계산하지 않는다")
    void doesNotRecalculateCostForDuplicateLateActualCallback() {
        AtomicInteger calculationCount = new AtomicInteger();
        InMemoryBudgetStateStore store = store((usage, plan) -> {
            calculationCount.incrementAndGet();
            return usd("40.00");
        });
        ReservationId reservationId = reserve(
                store,
                pricingSnapshot(),
                usd("60.00")
        );
        store.markInFlight(reservationId);
        store.markReconciliationRequired(reservationId);

        ReservationReconciliation first = store.reconcileLateActual(
                command(reservationId)
        );
        ReservationReconciliation duplicate = store.reconcileLateActual(
                command(reservationId)
        );

        assertThat(calculationCount).hasValue(1);
        assertThat(first.transition().status().isApplied()).isTrue();
        assertThat(duplicate.transition().status()).isEqualTo(REUSED);
    }

    @Test
    @DisplayName("다른 callback payload는 비용을 계산한 뒤 기존 commit과 충돌한다")
    void calculatesChangedCallbackBeforeReportingConflict() {
        AtomicInteger calculationCount = new AtomicInteger();
        InMemoryBudgetStateStore store = store((usage, plan) -> {
            calculationCount.incrementAndGet();
            return usage.inputTokens() == 100 ? usd("40.00") : usd("50.00");
        });
        ReservationId reservationId = reserve(
                store,
                pricingSnapshot(),
                usd("60.00")
        );
        store.markInFlight(reservationId);
        store.commit(command(reservationId));

        ReservationReconciliation conflict = store.commit(
                new ActualUsageCommand(
                        "request-1",
                        "attempt-1",
                        reservationId,
                        TokenUsage.from(101, 50),
                        "gpt-4o-mini-request"
                )
        );

        assertThat(calculationCount).hasValue(2);
        assertThat(conflict.transition().status()).isEqualTo(CONFLICT);
        assertThat(store.snapshot(KEY, LIMIT).committedCost())
                .isEqualTo(usd("40.00"));
    }

    @Test
    @DisplayName("비용이 같아도 callback payload가 다르면 기존 commit과 충돌한다")
    void reportsConflictWhenChangedCallbackHasSameCost() {
        AtomicInteger calculationCount = new AtomicInteger();
        InMemoryBudgetStateStore store = store((usage, plan) -> {
            calculationCount.incrementAndGet();
            return usd("40.00");
        });
        ReservationId reservationId = reserve(
                store,
                pricingSnapshot(),
                usd("60.00")
        );
        store.markInFlight(reservationId);
        store.commit(command(reservationId));

        ReservationReconciliation conflict = store.commit(
                new ActualUsageCommand(
                        "request-1",
                        "attempt-1",
                        reservationId,
                        TokenUsage.from(101, 50),
                        "gpt-4o-mini-request"
                )
        );

        assertThat(calculationCount).hasValue(2);
        assertThat(conflict.transition().status()).isEqualTo(CONFLICT);
        assertThat(store.snapshot(KEY, LIMIT).committedCost())
                .isEqualTo(usd("40.00"));
    }

    @Test
    @DisplayName("새롭게 적용된 commit만 회계 이벤트를 한 번 생성한다")
    void publishesAccountingEventOnlyForNewlyAppliedCommit() {
        List<ReservationAccountingEvent> events = new ArrayList<>();
        AtomicInteger sequence = new AtomicInteger();
        BudgetStateStore stateStore = LedgerBudgetComponents.inMemoryBudgetStateStore(
                CLOCK,
                () -> new ReservationId(
                        "reservation-" + sequence.incrementAndGet()
                ),
                (usage, plan) -> usd("40.00"),
                List.of(events::add)
        );
        ReservationAccounting accounting =
                LedgerBudgetComponents.reservationAccounting(stateStore);
        ReservationId reservationId = reserve(
                stateStore,
                pricingSnapshot(),
                usd("60.00")
        );
        accounting.markInFlight(reservationId);

        ReservationReconciliation applied = accounting.commit(command(reservationId));
        accounting.commit(command(reservationId));
        accounting.commit(
                new ActualUsageCommand(
                        "request-1",
                        "attempt-1",
                        reservationId,
                        TokenUsage.from(101, 50),
                        "gpt-4o-mini-request"
                )
        );

        assertThat(events).containsExactly(
                new ReservationAccountingEvent(applied)
        );
    }

    @Test
    @DisplayName("새롭게 적용된 late actual만 회계 이벤트를 한 번 생성한다")
    void publishesAccountingEventOnlyForNewlyAppliedLateActual() {
        List<ReservationAccountingEvent> events = new ArrayList<>();
        AtomicInteger sequence = new AtomicInteger();
        BudgetStateStore stateStore = LedgerBudgetComponents.inMemoryBudgetStateStore(
                CLOCK,
                () -> new ReservationId(
                        "reservation-" + sequence.incrementAndGet()
                ),
                (usage, plan) -> usd("40.00"),
                List.of(events::add)
        );
        ReservationAccounting accounting =
                LedgerBudgetComponents.reservationAccounting(stateStore);
        ReservationId reservationId = reserve(
                stateStore,
                pricingSnapshot(),
                usd("60.00")
        );
        accounting.markInFlight(reservationId);
        accounting.markReconciliationRequired(reservationId);

        ReservationReconciliation applied = accounting.reconcileLateActual(
                command(reservationId)
        );
        accounting.reconcileLateActual(command(reservationId));

        assertThat(events).containsExactly(
                new ReservationAccountingEvent(applied)
        );
    }

    @Test
    @DisplayName("listener 실패는 commit을 되돌리지 않고 중복 callback에서 재시도하지 않는다")
    void preservesCommitAndDoesNotRetryEventAfterListenerFailure() {
        AtomicInteger deliveryCount = new AtomicInteger();
        AtomicInteger sequence = new AtomicInteger();
        BudgetStateStore stateStore = LedgerBudgetComponents.inMemoryBudgetStateStore(
                CLOCK,
                () -> new ReservationId(
                        "reservation-" + sequence.incrementAndGet()
                ),
                (usage, plan) -> usd("40.00"),
                List.of(event -> {
                    deliveryCount.incrementAndGet();
                    throw new IllegalStateException("listener failed");
                })
        );
        ReservationAccounting accounting =
                LedgerBudgetComponents.reservationAccounting(stateStore);
        ReservationId reservationId = reserve(
                stateStore,
                pricingSnapshot(),
                usd("60.00")
        );
        accounting.markInFlight(reservationId);

        ReservationReconciliation applied = accounting.commit(
                command(reservationId)
        );
        ReservationReconciliation duplicate = accounting.commit(
                command(reservationId)
        );

        assertThat(applied.transition().status().isApplied()).isTrue();
        assertThat(duplicate.transition().status()).isEqualTo(REUSED);
        assertThat(deliveryCount).hasValue(1);
        assertThat(stateStore.snapshot(KEY, LIMIT).committedCost())
                .isEqualTo(usd("40.00"));
    }

    @Test
    @DisplayName("한 listener의 실패가 다음 listener의 이벤트 수신을 막지 않는다")
    void continuesDeliveryAfterListenerFailure() {
        List<ReservationAccountingEvent> receivedEvents = new ArrayList<>();
        AtomicInteger failedDeliveryCount = new AtomicInteger();
        AtomicInteger sequence = new AtomicInteger();
        BudgetStateStore stateStore = LedgerBudgetComponents.inMemoryBudgetStateStore(
                CLOCK,
                () -> new ReservationId(
                        "reservation-" + sequence.incrementAndGet()
                ),
                (usage, plan) -> usd("40.00"),
                List.of(
                        event -> {
                            failedDeliveryCount.incrementAndGet();
                            throw new IllegalStateException("listener failed");
                        },
                        receivedEvents::add
                )
        );
        ReservationAccounting accounting =
                LedgerBudgetComponents.reservationAccounting(stateStore);
        ReservationId reservationId = reserve(
                stateStore,
                pricingSnapshot(),
                usd("60.00")
        );
        accounting.markInFlight(reservationId);

        ReservationReconciliation applied = accounting.commit(
                command(reservationId)
        );

        assertThat(failedDeliveryCount).hasValue(1);
        assertThat(receivedEvents).containsExactly(
                new ReservationAccountingEvent(applied)
        );
    }

    @Test
    @DisplayName("공개 factory는 예약 store와 같은 객체의 정산 진입점을 반환한다")
    void exposesAccountingForTheSameReservationStore() {
        AtomicInteger sequence = new AtomicInteger();
        BudgetStateStore stateStore = LedgerBudgetComponents.inMemoryBudgetStateStore(
                CLOCK,
                () -> new ReservationId(
                        "reservation-" + sequence.incrementAndGet()
                ),
                (usage, plan) -> usd("40.00")
        );
        ReservationAccounting accounting =
                LedgerBudgetComponents.reservationAccounting(stateStore);

        assertThat(accounting).isSameAs(stateStore);
    }

    private static ReservationReconciliation reconcile(
            String estimate,
            String actual
    ) {
        InMemoryBudgetStateStore store = store((usage, plan) -> usd(actual));
        ReservationId reservationId = reserve(
                store,
                pricingSnapshot(),
                usd(estimate)
        );
        store.markInFlight(reservationId);
        return store.commit(command(reservationId));
    }

    private static ActualUsageCommand command(ReservationId reservationId) {
        return new ActualUsageCommand(
                "request-1",
                "attempt-1",
                reservationId,
                TokenUsage.from(100, 50),
                "gpt-4o-mini-request"
        );
    }

    private static ReservationId reserve(
            BudgetStateStore store,
            PricingSnapshot snapshot,
            Cost estimate
    ) {
        return store.checkAndReserve(
                new BudgetReservationRequest(
                        KEY,
                        LIMIT,
                        estimate,
                        "request-1",
                        new IdempotencyKey("deduplication-1"),
                        snapshot,
                        TOKEN_ESTIMATE
                )
        ).reservationId();
    }

    private static InMemoryBudgetStateStore store(CostCalculator calculator) {
        AtomicInteger sequence = new AtomicInteger();
        return new InMemoryBudgetStateStore(
                CLOCK,
                () -> new ReservationId("reservation-" + sequence.incrementAndGet()),
                calculator
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
