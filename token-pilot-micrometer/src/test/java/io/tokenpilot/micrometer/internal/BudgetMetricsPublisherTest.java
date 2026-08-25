package io.tokenpilot.micrometer.internal;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.tokenpilot.budget.AccountingTransitionStatus;
import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetReservationRequest;
import io.tokenpilot.budget.BudgetReservationResult;
import io.tokenpilot.budget.BudgetSnapshot;
import io.tokenpilot.budget.BudgetWindow;
import io.tokenpilot.budget.IdempotencyKey;
import io.tokenpilot.budget.ReservationAccountingEvent;
import io.tokenpilot.budget.ReservationAccountingListenerFailureEvent;
import io.tokenpilot.budget.ReservationAccountingListenerPhase;
import io.tokenpilot.budget.ReservationAccountingListenerType;
import io.tokenpilot.budget.ReservationAccountingReason;
import io.tokenpilot.budget.ReservationActualTokens;
import io.tokenpilot.budget.ReservationId;
import io.tokenpilot.budget.ReservationReconciliation;
import io.tokenpilot.budget.ReservationReconciliationRequiredEvent;
import io.tokenpilot.budget.ReservationState;
import io.tokenpilot.budget.ReservationTokenEstimate;
import io.tokenpilot.budget.ReservationTransition;
import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.PricingSnapshot;
import io.tokenpilot.core.domain.TokenType;
import io.tokenpilot.core.domain.TokenUsageDetails;
import io.tokenpilot.core.domain.UsageSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetMetricsPublisherTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final ReservationId RESERVATION_ID =
            new ReservationId("sensitive-reservation-id");
    private static final BudgetKey BUDGET_KEY = new BudgetKey(
            "monthly",
            "tenant",
            "sensitive-tenant-id",
            BudgetWindow.parse("2026-08")
    );
    private static final Cost LIMIT = usd("10.00");

    private SimpleMeterRegistry meterRegistry;
    private BudgetMetricsPublisher publisher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        publisher = new BudgetMetricsPublisher(meterRegistry);
    }

    @Test
    void publishesActualCostErrorAndOutcomeOnlyForAppliedCommit() {
        ReservationAccountingEvent applied = accountingEvent(
                ReservationTransition.applied(
                        ReservationState.IN_FLIGHT,
                        ReservationState.COMMITTED
                ),
                110,
                60,
                "0.80"
        );

        publisher.onAccountingApplied(applied, snapshot());

        var cost = meterRegistry.find("tokenpilot.cost.total")
                .tag("currency", "USD")
                .counter();
        assertThat(cost).isNotNull();
        assertThat(cost.count()).isEqualTo(0.8);
        assertThat(cost.getId().getType()).isEqualTo(Meter.Type.COUNTER);
        assertThat(cost.getId().getBaseUnit()).isEqualTo("currency");
        assertThat(cost.getId().getDescription())
                .isEqualTo("Total actual LLM cost committed by Token Pilot");
        assertThat(cost.getId().getTags()).extracting(tag -> tag.getKey())
                .containsExactly("currency");

        var error = meterRegistry.find("tokenpilot.reconciliation.error.tokens")
                .tag("direction", "underestimate")
                .summary();
        assertThat(error).isNotNull();
        assertThat(error.count()).isEqualTo(1L);
        assertThat(error.totalAmount()).isEqualTo(20.0);
        assertThat(error.getId().getType()).isEqualTo(Meter.Type.DISTRIBUTION_SUMMARY);
        assertThat(error.getId().getBaseUnit()).isEqualTo("tokens");
        assertThat(error.getId().getDescription()).isEqualTo(
                "Absolute difference between estimated and actual total tokens per reconciliation"
        );

        var outcome = meterRegistry.find("tokenpilot.reconciliation.outcomes")
                .tag("outcome", "committed")
                .tag("reason", "actual_usage_reported")
                .counter();
        assertThat(outcome).isNotNull();
        assertThat(outcome.count()).isEqualTo(1.0);
        assertThat(outcome.getId().getBaseUnit()).isEqualTo("reconciliations");
        assertThat(outcome.getId().getDescription())
                .isEqualTo("Total reservation reconciliation outcomes applied by Token Pilot");
    }

    @Test
    void ignoresReusedCommitSoCostAndOutcomeAreNotDuplicated() {
        ReservationAccountingEvent applied = accountingEvent(
                ReservationTransition.applied(
                        ReservationState.IN_FLIGHT,
                        ReservationState.COMMITTED
                ),
                100,
                50,
                "0.80"
        );
        ReservationAccountingEvent reused = accountingEvent(
                ReservationTransition.unchanged(
                        ReservationState.COMMITTED,
                        AccountingTransitionStatus.REUSED
                ),
                100,
                50,
                "0.80"
        );

        publisher.onCommitted(applied);
        publisher.onCommitted(reused);

        assertThat(meterRegistry.find("tokenpilot.cost.total").counter().count())
                .isEqualTo(0.8);
        assertThat(meterRegistry.find("tokenpilot.reconciliation.outcomes")
                .counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("tokenpilot.reconciliation.error.tokens")
                .summary().count()).isEqualTo(1L);
    }

    @Test
    void explicitZeroCommitRemainsDistinctFromMissingPricing() {
        publisher.onCommitted(accountingEvent(
                ReservationTransition.applied(
                        ReservationState.IN_FLIGHT,
                        ReservationState.COMMITTED
                ),
                100,
                50,
                "0.00"
        ));

        var cost = meterRegistry.find("tokenpilot.cost.total")
                .tag("currency", "USD")
                .counter();
        assertThat(cost).isNotNull();
        assertThat(cost.count()).isZero();
        assertThat(meterRegistry.find("tokenpilot.reconciliation.outcomes")
                .tag("outcome", "committed")
                .counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("tokenpilot.pricing.missing").meter())
                .isNull();
    }

    @Test
    void distinguishesOverestimateAndExactTokenErrors() {
        publisher.onCommitted(accountingEvent(
                ReservationTransition.applied(
                        ReservationState.IN_FLIGHT,
                        ReservationState.COMMITTED
                ),
                90,
                50,
                "0.40"
        ));
        publisher.onCommitted(accountingEvent(
                ReservationTransition.applied(
                        ReservationState.IN_FLIGHT,
                        ReservationState.COMMITTED
                ),
                100,
                50,
                "0.40"
        ));

        var overestimate = meterRegistry.find("tokenpilot.reconciliation.error.tokens")
                .tag("direction", "overestimate")
                .summary();
        var exact = meterRegistry.find("tokenpilot.reconciliation.error.tokens")
                .tag("direction", "exact")
                .summary();

        assertThat(overestimate.count()).isEqualTo(1L);
        assertThat(overestimate.totalAmount()).isEqualTo(10.0);
        assertThat(exact.count()).isEqualTo(1L);
        assertThat(exact.totalAmount()).isZero();
    }

    @Test
    void reconciliationRequiredPublishesOutcomeWithoutZeroCostOrError() {
        ReservationTransition transition = ReservationTransition.applied(
                ReservationState.IN_FLIGHT,
                ReservationState.RECONCILIATION_REQUIRED
        );
        publisher.onReconciliationRequired(new ReservationReconciliationRequiredEvent(
                RESERVATION_ID,
                BUDGET_KEY,
                transition,
                ReservationAccountingReason.ACTUAL_USAGE_UNAVAILABLE,
                snapshot()
        ));

        var outcome = meterRegistry.find("tokenpilot.reconciliation.outcomes")
                .tag("outcome", "reconciliation_required")
                .tag("reason", "actual_usage_unavailable")
                .counter();
        assertThat(outcome).isNotNull();
        assertThat(outcome.count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("tokenpilot.cost.total").meter()).isNull();
        assertThat(meterRegistry.find("tokenpilot.reconciliation.error.tokens")
                .meter()).isNull();
    }

    @Test
    void pricingMismatchPublishesBoundedPendingOutcomeWithoutCostOrError() {
        publisher.onReconciliationRequired(
                new ReservationReconciliationRequiredEvent(
                        RESERVATION_ID,
                        BUDGET_KEY,
                        ReservationTransition.applied(
                                ReservationState.IN_FLIGHT,
                                ReservationState.RECONCILIATION_REQUIRED
                        ),
                        ReservationAccountingReason.PRICING_RECONCILIATION_REQUIRED,
                        snapshot()
                )
        );

        var outcome = meterRegistry.find("tokenpilot.reconciliation.outcomes")
                .tag("outcome", "reconciliation_required")
                .tag("reason", "pricing_reconciliation_required")
                .counter();
        assertThat(outcome).isNotNull();
        assertThat(outcome.count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("tokenpilot.cost.total").meter()).isNull();
        assertThat(meterRegistry.find("tokenpilot.reconciliation.error.tokens")
                .meter()).isNull();
    }

    @Test
    void publishesEveryReservationEvaluationWithBoundedStateOnly() {
        BudgetReservationRequest request = new BudgetReservationRequest(
                BUDGET_KEY,
                LIMIT,
                usd("1.00"),
                "sensitive-request-id",
                new IdempotencyKey("sensitive-idempotency-id"),
                null,
                null,
                null,
                Optional.empty()
        );
        BudgetReservationResult result = BudgetReservationResult.blocked(
                snapshot(),
                "raw rejection message that must not become a tag"
        );

        publisher.onReservationEvaluated(request, result);

        var counter = meterRegistry.find("tokenpilot.budget.reservations")
                .tag("state", "blocked")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
        assertThat(counter.getId().getBaseUnit()).isEqualTo("reservations");
        assertThat(counter.getId().getDescription())
                .isEqualTo("Total budget reservation results produced by Token Pilot");
        assertThat(counter.getId().getTags()).extracting(tag -> tag.getKey())
                .containsExactly("state");
    }

    @Test
    void publishesBoundedListenerFailureAndIdentifiesAsMetricsListener() {
        publisher.onFailure(new ReservationAccountingListenerFailureEvent(
                ReservationAccountingListenerType.NOTIFICATION,
                ReservationAccountingListenerPhase.RECONCILIATION_REQUIRED
        ));

        var counter = meterRegistry.find("tokenpilot.listener.failures")
                .tag("listener", "notification")
                .tag("phase", "reconciliation_required")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
        assertThat(counter.getId().getBaseUnit()).isEqualTo("failures");
        assertThat(counter.getId().getDescription())
                .isEqualTo("Total listener failures isolated by Token Pilot");
        assertThat(publisher.listenerType())
                .isEqualTo(ReservationAccountingListenerType.METRICS);
    }

    @Test
    void neverCopiesRawIdentifiersModelsOrMessagesIntoMeterMetadata() {
        publisher.onCommitted(accountingEvent(
                ReservationTransition.applied(
                        ReservationState.IN_FLIGHT,
                        ReservationState.COMMITTED
                ),
                110,
                60,
                "0.80"
        ));
        BudgetReservationRequest request = new BudgetReservationRequest(
                BUDGET_KEY,
                LIMIT,
                usd("1.00"),
                "sensitive-request-id",
                new IdempotencyKey("sensitive-idempotency-id"),
                null,
                null,
                null,
                Optional.empty()
        );
        publisher.onReservationEvaluated(
                request,
                BudgetReservationResult.blocked(
                        snapshot(),
                        "raw rejection message that must not become metadata"
                )
        );

        assertThat(meterRegistry.getMeters())
                .extracting(meter -> meter.getId().toString())
                .allSatisfy(metadata -> assertThat(metadata)
                        .doesNotContain(
                                "sensitive-reservation-id",
                                "sensitive-tenant-id",
                                "sensitive-request-id",
                                "sensitive-idempotency-id",
                                "raw-request-model",
                                "raw-response-model",
                                "raw rejection message"
                        ));
    }

    private static ReservationAccountingEvent accountingEvent(
            ReservationTransition transition,
            long actualInput,
            long actualOutput,
            String actualCost
    ) {
        return new ReservationAccountingEvent(new ReservationReconciliation(
                "sensitive-request-id",
                "sensitive-attempt-id",
                RESERVATION_ID,
                BUDGET_KEY,
                "raw-response-model",
                pricingSnapshot(),
                new ReservationTokenEstimate(100, 120, 50),
                new ReservationActualTokens(
                        actualInput,
                        actualOutput,
                        TokenUsageDetails.unreported(),
                        UsageSource.PROVIDER_REPORTED
                ),
                usd("1.00"),
                usd(actualCost),
                false,
                transition,
                ReservationAccountingReason.ACTUAL_USAGE_REPORTED
        ));
    }

    private static PricingSnapshot pricingSnapshot() {
        return new PricingSnapshot(
                "raw-request-model",
                "pricing-policy",
                "catalog-version",
                Instant.parse("2026-08-25T00:00:00Z"),
                Map.of(
                        TokenType.PROMPT, new BigDecimal("0.001"),
                        TokenType.COMPLETION, new BigDecimal("0.002")
                ),
                USD
        );
    }

    private static BudgetSnapshot snapshot() {
        return new BudgetSnapshot(
                BUDGET_KEY,
                LIMIT,
                Cost.zero(USD),
                Cost.zero(USD),
                Cost.zero(USD),
                Set.of()
        );
    }

    private static Cost usd(String amount) {
        return Cost.of(new BigDecimal(amount), USD);
    }
}
