package io.tokenpilot.micrometer.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.tokenpilot.budget.AccountingTransitionStatus;
import io.tokenpilot.budget.BudgetReservationRequest;
import io.tokenpilot.budget.BudgetReservationResult;
import io.tokenpilot.budget.BudgetSnapshot;
import io.tokenpilot.budget.ReservationAccountingEvent;
import io.tokenpilot.budget.ReservationAccountingListener;
import io.tokenpilot.budget.ReservationAccountingListenerErrorHandler;
import io.tokenpilot.budget.ReservationAccountingListenerFailureEvent;
import io.tokenpilot.budget.ReservationAccountingListenerType;
import io.tokenpilot.budget.ReservationAccountingReason;
import io.tokenpilot.budget.ReservationReconciliation;
import io.tokenpilot.budget.ReservationReconciliationRequiredEvent;
import io.tokenpilot.budget.ReservationState;

import java.math.BigInteger;
import java.util.Objects;

/** Atomic budget/accounting 결과를 Token Pilot 고유 Micrometer meter로 투영합니다. */
public final class BudgetMetricsPublisher
        implements ReservationAccountingListener, ReservationAccountingListenerErrorHandler {

    private final MeterRegistry meterRegistry;

    public BudgetMetricsPublisher(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(
                meterRegistry,
                "meterRegistry must not be null"
        );
    }

    @Override
    public void onCommitted(ReservationAccountingEvent event) {
        recordCommitted(event);
    }

    @Override
    public void onAccountingApplied(
            ReservationAccountingEvent event,
            BudgetSnapshot snapshot
    ) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        recordCommitted(event);
    }

    @Override
    public void onReconciliationRequired(
            ReservationReconciliationRequiredEvent event
    ) {
        Objects.requireNonNull(event, "event must not be null");
        if (event.transition().status() != AccountingTransitionStatus.APPLIED
                || event.transition().resultingState()
                != ReservationState.RECONCILIATION_REQUIRED) {
            return;
        }
        recordReconciliationOutcome(
                event.transition().resultingState(),
                event.reason()
        );
    }

    @Override
    public void onReservationEvaluated(
            BudgetReservationRequest request,
            BudgetReservationResult result
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Counter.builder(TokenPilotMeterMetadata.BUDGET_RESERVATIONS)
                .description(TokenPilotMeterMetadata.BUDGET_RESERVATIONS_DESCRIPTION)
                .baseUnit(TokenPilotMeterMetadata.RESERVATIONS_BASE_UNIT)
                .tag("state", TokenPilotMeterMetadata.tagValue(result.status()))
                .register(meterRegistry)
                .increment();
    }

    @Override
    public ReservationAccountingListenerType listenerType() {
        return ReservationAccountingListenerType.METRICS;
    }

    @Override
    public void onFailure(ReservationAccountingListenerFailureEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        Counter.builder(TokenPilotMeterMetadata.LISTENER_FAILURES)
                .description(TokenPilotMeterMetadata.LISTENER_FAILURES_DESCRIPTION)
                .baseUnit(TokenPilotMeterMetadata.FAILURES_BASE_UNIT)
                .tag("listener", TokenPilotMeterMetadata.tagValue(event.listenerType()))
                .tag("phase", TokenPilotMeterMetadata.tagValue(event.phase()))
                .register(meterRegistry)
                .increment();
    }

    private void recordCommitted(ReservationAccountingEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        ReservationReconciliation reconciliation = event.reconciliation();
        if (reconciliation.transition().status() != AccountingTransitionStatus.APPLIED
                || reconciliation.transition().resultingState()
                != ReservationState.COMMITTED) {
            return;
        }

        Counter.builder(TokenPilotMeterMetadata.COST_TOTAL)
                .description(TokenPilotMeterMetadata.COST_TOTAL_DESCRIPTION)
                .baseUnit(TokenPilotMeterMetadata.COST_BASE_UNIT)
                .tag("currency", reconciliation.currency().getCurrencyCode())
                .register(meterRegistry)
                .increment(reconciliation.actual().value().doubleValue());

        BigInteger delta = totalTokenDelta(reconciliation);
        String direction = delta.signum() > 0
                ? "underestimate"
                : delta.signum() < 0 ? "overestimate" : "exact";
        DistributionSummary.builder(TokenPilotMeterMetadata.RECONCILIATION_ERROR_TOKENS)
                .description(TokenPilotMeterMetadata.RECONCILIATION_ERROR_TOKENS_DESCRIPTION)
                .baseUnit(TokenPilotMeterMetadata.TOKENS_BASE_UNIT)
                .tag("direction", direction)
                .register(meterRegistry)
                .record(delta.abs().doubleValue());

        recordReconciliationOutcome(
                reconciliation.transition().resultingState(),
                reconciliation.reason()
        );
    }

    private void recordReconciliationOutcome(
            ReservationState outcome,
            ReservationAccountingReason reason
    ) {
        Counter.builder(TokenPilotMeterMetadata.RECONCILIATION_OUTCOMES)
                .description(TokenPilotMeterMetadata.RECONCILIATION_OUTCOMES_DESCRIPTION)
                .baseUnit(TokenPilotMeterMetadata.RECONCILIATIONS_BASE_UNIT)
                .tag("outcome", TokenPilotMeterMetadata.tagValue(outcome))
                .tag("reason", TokenPilotMeterMetadata.tagValue(reason))
                .register(meterRegistry)
                .increment();
    }

    private static BigInteger totalTokenDelta(
            ReservationReconciliation reconciliation
    ) {
        BigInteger actual = BigInteger.valueOf(
                        reconciliation.actualTokens().inputTokens()
                )
                .add(BigInteger.valueOf(
                        reconciliation.actualTokens().outputTokens()
                ));
        BigInteger estimated = BigInteger.valueOf(
                        reconciliation.tokenEstimate().inputEstimatedTokens()
                )
                .add(BigInteger.valueOf(
                        reconciliation.tokenEstimate().reservedOutputTokens()
                ));
        return actual.subtract(estimated);
    }
}
