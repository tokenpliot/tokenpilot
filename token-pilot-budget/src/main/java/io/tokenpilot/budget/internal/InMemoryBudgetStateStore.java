package io.tokenpilot.budget.internal;

import io.tokenpilot.budget.ActualUsageCommand;
import io.tokenpilot.budget.AccountingTransitionStatus;
import io.tokenpilot.budget.AtomicBudgetStateStore;
import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetReservation;
import io.tokenpilot.budget.BudgetReservationRequest;
import io.tokenpilot.budget.BudgetReservationResult;
import io.tokenpilot.budget.BudgetSnapshot;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.budget.IdempotencyKey;
import io.tokenpilot.budget.ReservationAccountingEvent;
import io.tokenpilot.budget.ReservationAccountingListener;
import io.tokenpilot.budget.ReservationAccountingListenerErrorHandler;
import io.tokenpilot.budget.ReservationAccountingListenerFailureEvent;
import io.tokenpilot.budget.ReservationAccountingListenerPhase;
import io.tokenpilot.budget.ReservationAccountingListenerType;
import io.tokenpilot.budget.ReservationAccountingReason;
import io.tokenpilot.budget.ReservationActualTokens;
import io.tokenpilot.budget.ReservationId;
import io.tokenpilot.budget.ReservationReconciliation;
import io.tokenpilot.budget.ReservationReconciliationRequiredEvent;
import io.tokenpilot.budget.ReservationState;
import io.tokenpilot.budget.ReservationStatus;
import io.tokenpilot.budget.ReservationStateMachine;
import io.tokenpilot.budget.ReservationTransition;
import io.tokenpilot.budget.ReservationTokenEstimate;
import io.tokenpilot.core.CostCalculator;
import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.PricingSnapshot;
import io.tokenpilot.core.internal.LedgerComponents;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * resolved {@link BudgetKey}별 확정 비용과 원자적 예약을 관리하는 인메모리 저장소입니다.
 *
 * <p>bucket별 monitor가 조회·통화 검증·한도 검증·예약 갱신을 함께 보호하고,
 * 별도의 idempotency index가 같은 요청의 중복 예약을 차단합니다.</p>
 */
public class InMemoryBudgetStateStore implements AtomicBudgetStateStore {

    private final ConcurrentMap<BudgetKey, Bucket> store = new ConcurrentHashMap<>();
    private final ConcurrentMap<IdempotencyKey, ReservationId> idempotencyIndex =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<ReservationId, BudgetKey> reservationIndex =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<ReservationId, ActualUsageFingerprint>
            pendingActualFingerprints = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Supplier<ReservationId> reservationIdGenerator;
    private final CostCalculator costCalculator;
    private final Supplier<List<ReservationAccountingListener>> accountingListenerSupplier;
    private final Supplier<List<ReservationAccountingListenerErrorHandler>>
            listenerErrorHandlerSupplier;

    public InMemoryBudgetStateStore() {
        this(
                Clock.systemUTC(),
                ReservationId::random,
                LedgerComponents.defaultCostCalculator()
        );
    }

    public InMemoryBudgetStateStore(
            Clock clock,
            Supplier<ReservationId> reservationIdGenerator
    ) {
        this(
                clock,
                reservationIdGenerator,
                LedgerComponents.defaultCostCalculator()
        );
    }

    public InMemoryBudgetStateStore(
            Clock clock,
            Supplier<ReservationId> reservationIdGenerator,
            CostCalculator costCalculator
    ) {
        this(clock, reservationIdGenerator, costCalculator, List.of(), List.of());
    }

    public InMemoryBudgetStateStore(
            Clock clock,
            Supplier<ReservationId> reservationIdGenerator,
            CostCalculator costCalculator,
            List<ReservationAccountingListener> accountingListeners
    ) {
        this(
                clock,
                reservationIdGenerator,
                costCalculator,
                accountingListeners,
                List.of()
        );
    }

    public InMemoryBudgetStateStore(
            Clock clock,
            Supplier<ReservationId> reservationIdGenerator,
            CostCalculator costCalculator,
            List<ReservationAccountingListener> accountingListeners,
            List<ReservationAccountingListenerErrorHandler> listenerErrorHandlers
    ) {
        this(
                clock,
                reservationIdGenerator,
                costCalculator,
                fixedListSupplier(
                        accountingListeners,
                        "accountingListeners must not be null"
                ),
                fixedListSupplier(
                        listenerErrorHandlers,
                        "listenerErrorHandlers must not be null"
                )
        );
    }

    public InMemoryBudgetStateStore(
            Clock clock,
            Supplier<ReservationId> reservationIdGenerator,
            CostCalculator costCalculator,
            Supplier<List<ReservationAccountingListener>> accountingListenerSupplier,
            Supplier<List<ReservationAccountingListenerErrorHandler>>
                    listenerErrorHandlerSupplier
    ) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.reservationIdGenerator = Objects.requireNonNull(
                reservationIdGenerator,
                "reservationIdGenerator must not be null"
        );
        this.costCalculator = Objects.requireNonNull(
                costCalculator,
                "costCalculator must not be null"
        );
        this.accountingListenerSupplier = Objects.requireNonNull(
                accountingListenerSupplier,
                "accountingListenerSupplier must not be null"
        );
        this.listenerErrorHandlerSupplier = Objects.requireNonNull(
                listenerErrorHandlerSupplier,
                "listenerErrorHandlerSupplier must not be null"
        );
    }

    @Override
    public Cost getAccumulatedCost(BudgetKey key, Cost limit) {
        validateArguments(key, limit);
        Bucket bucket = store.get(key);
        if (bucket == null) {
            return Cost.zero(limit.currency());
        }
        synchronized (bucket) {
            bucket.validate(limit);
            return bucket.committedCost;
        }
    }

    @Override
    public void addCost(BudgetKey key, Cost limit, Cost amount) {
        validateArguments(key, limit);
        Objects.requireNonNull(amount, "amount must not be null");
        validateCurrency(limit, amount);

        Bucket bucket = store.computeIfAbsent(key, ignored -> new Bucket(limit));
        synchronized (bucket) {
            bucket.validate(limit);
            bucket.committedCost = bucket.committedCost.add(amount);
        }
    }

    @Override
    public BudgetReservationResult checkAndReserve(BudgetReservationRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        AtomicReference<BudgetReservationResult> result = new AtomicReference<>();
        idempotencyIndex.compute(
                request.idempotencyKey(),
                (ignored, existingReservationId) -> reserveOrReturnExisting(
                        request,
                        existingReservationId,
                        result
                )
        );

        BudgetReservationResult reservationResult = Objects.requireNonNull(
                result.get(),
                "reservation result must be set"
        );
        publishReservationEvaluated(request, reservationResult);
        publishBlockedReservation(request, reservationResult);
        return reservationResult;
    }

    @Override
    public ReservationTransition markInFlight(ReservationId reservationId) {
        return updateState(reservationId, ReservationStateMachine::onDispatch);
    }

    @Override
    public ReservationTransition release(
            ReservationId reservationId,
            ReservationAccountingReason reason
    ) {
        Objects.requireNonNull(reason, "reason must not be null");
        return switch (reason) {
            case CANCELLED_BEFORE_DISPATCH -> releaseBeforeDispatch(
                    reservationId,
                    reason
            );
            case PROVIDER_CONFIRMED_UNBILLED -> releaseConfirmedUnbilled(
                    reservationId,
                    reason
            );
            default -> throw new IllegalArgumentException(
                    "reason is not valid for release"
            );
        };
    }

    private ReservationTransition releaseBeforeDispatch(
            ReservationId reservationId,
            ReservationAccountingReason reason
    ) {
        Bucket bucket = bucketFor(reservationId);
        synchronized (bucket) {
            ReservationAccountingState accountingState = accountingState(
                    bucket,
                    reservationId
            );
            BudgetReservation reservation = accountingState.reservation();
            ReservationTransition transition =
                    accountingState.evaluateReleaseBeforeDispatch();
            if (!transition.status().isApplied()) {
                return transition;
            }

            BudgetReservation updated = withState(
                    reservation,
                    transition.resultingState()
            );
            releaseActiveReservation(
                    bucket,
                    accountingState,
                    accountingState.releasedBeforeDispatch(updated)
            );
            return transition;
        }
    }

    private ReservationTransition releaseConfirmedUnbilled(
            ReservationId reservationId,
            ReservationAccountingReason reason
    ) {
        Bucket bucket = bucketFor(reservationId);
        synchronized (bucket) {
            ReservationAccountingState accountingState = accountingState(
                    bucket,
                    reservationId
            );
            BudgetReservation reservation = accountingState.reservation();
            ReservationTransition transition =
                    accountingState.evaluateConfirmedUnbilledRelease();
            if (!transition.status().isApplied()) {
                return transition;
            }

            BudgetReservation updated = withState(
                    reservation,
                    transition.resultingState()
            );
            releaseActiveReservation(
                    bucket,
                    accountingState,
                    accountingState.confirmedUnbilledReleased(updated)
            );
            return transition;
        }
    }

    @Override
    public ReservationTransition commitCost(ReservationId reservationId, Cost actualCost) {
        return applyCost(
                reservationId,
                actualCost,
                CommitType.DIRECT,
                Optional.empty()
        ).transition();
    }

    @Override
    public ReservationReconciliation commit(ActualUsageCommand command) {
        return reconcileUsage(
                command,
                CommitType.DIRECT,
                ReservationAccountingReason.ACTUAL_USAGE_REPORTED,
                Optional.empty()
        );
    }

    @Override
    public ReservationTransition markReconciliationRequired(
            ReservationId reservationId,
            ReservationAccountingReason reason
    ) {
        return markReconciliationRequiredInternal(
                reservationId,
                reason,
                Optional.empty()
        );
    }

    @Override
    public ReservationTransition markReconciliationRequired(
            ActualUsageCommand command,
            ReservationAccountingReason reason
    ) {
        Objects.requireNonNull(command, "command must not be null");
        if (reason != ReservationAccountingReason.PRICING_RECONCILIATION_REQUIRED) {
            throw new IllegalArgumentException(
                    "pending actual metadata requires pricing reconciliation reason"
            );
        }
        return markReconciliationRequiredInternal(
                command.reservationId(),
                reason,
                Optional.of(command)
        );
    }

    private ReservationTransition markReconciliationRequiredInternal(
            ReservationId reservationId,
            ReservationAccountingReason reason,
            Optional<ActualUsageCommand> pendingActualUsage
    ) {
        requireReconciliationRequiredReason(reason);
        Objects.requireNonNull(pendingActualUsage, "pendingActualUsage must not be null");
        Bucket bucket = bucketFor(reservationId);
        ReservationTransition transition;
        ReservationReconciliationRequiredEvent event;
        synchronized (bucket) {
            ReservationAccountingState accountingState = accountingState(
                    bucket,
                    reservationId
            );
            pendingActualUsage.ifPresent(command ->
                    validatePendingActual(command, accountingState.reservation())
            );
            Optional<ActualUsageFingerprint> pendingFingerprint =
                    pendingActualUsage.map(command -> pendingFingerprint(
                            command,
                            accountingState.reservation()
                    ));
            transition = accountingState.evaluateReconciliationRequired();
            if (!transition.status().isApplied()) {
                if (transition.status() == AccountingTransitionStatus.REUSED) {
                    pendingFingerprint.ifPresent(fingerprint ->
                            mergePendingActualFingerprint(reservationId, fingerprint)
                    );
                }
                return transition;
            }

            pendingFingerprint.ifPresent(fingerprint ->
                    mergePendingActualFingerprint(reservationId, fingerprint)
            );
            moveActiveReservationToPending(
                    bucket,
                    accountingState,
                    transition.resultingState()
            );
            event = new ReservationReconciliationRequiredEvent(
                    reservationId,
                    accountingState.reservation().key(),
                    transition,
                    reason,
                    bucket.snapshot(accountingState.reservation().key()),
                    pendingActualUsage
            );
        }
        publishReconciliationRequired(event);
        return transition;
    }

    private ActualUsageFingerprint pendingFingerprint(
            ActualUsageCommand command,
            BudgetReservation reservation
    ) {
        PricingSnapshot requestSnapshot = reservation.pricingSnapshot().orElseThrow(
                () -> new IllegalStateException(
                        "reservation does not contain a pricing snapshot"
                )
        );
        return ActualUsageFingerprint.from(
                command,
                ReservationActualTokens.from(command.usage()),
                requestSnapshot
        );
    }

    private void mergePendingActualFingerprint(
            ReservationId reservationId,
            ActualUsageFingerprint candidate
    ) {
        ActualUsageFingerprint existing = pendingActualFingerprints.putIfAbsent(
                reservationId,
                candidate
        );
        if (existing != null && !existing.sameCallback(candidate)) {
            throw new IllegalArgumentException(
                    "pending actual callback conflicts with the existing callback"
            );
        }
    }

    private static void validatePendingActual(
            ActualUsageCommand command,
            BudgetReservation reservation
    ) {
        if (!reservation.belongsTo(command.requestId())) {
            throw new IllegalArgumentException(
                    "requestId must match the reservation request"
            );
        }
        PricingSnapshot requestSnapshot = reservation.pricingSnapshot().orElseThrow(
                () -> new IllegalStateException(
                        "reservation does not contain a pricing snapshot"
                )
        );
        if (requestSnapshot.modelId().equals(command.responseModelId())) {
            throw new IllegalArgumentException(
                    "pricing reconciliation requires a different response model"
            );
        }
    }

    @Override
    public ReservationTransition reconcileLateActualCost(
            ReservationId reservationId,
            Cost actualCost
    ) {
        return applyCost(
                reservationId,
                actualCost,
                CommitType.LATE_ACTUAL,
                Optional.empty()
        ).transition();
    }

    private AccountingTransitionOutcome applyCost(
            ReservationId reservationId,
            Cost actualCost,
            CommitType type,
            Optional<ActualUsageFingerprint> fingerprint
    ) {
        Objects.requireNonNull(actualCost, "actualCost must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        Bucket bucket = bucketFor(reservationId);
        synchronized (bucket) {
            ReservationAccountingState accountingState = accountingState(
                    bucket,
                    reservationId
            );
            if (fingerprint.isEmpty()) {
                requireCostOnlyReservation(accountingState.reservation());
            }
            return applyCostInBucket(
                    bucket,
                    accountingState,
                    actualCost,
                    type,
                    fingerprint
            );
        }
    }

    private AccountingTransitionOutcome applyCostInBucket(
            Bucket bucket,
            ReservationAccountingState accountingState,
            Cost actualCost,
            CommitType type,
            Optional<ActualUsageFingerprint> fingerprint
    ) {
        ReservationTransition transition = type == CommitType.DIRECT
                ? accountingState.evaluateCommit(actualCost, fingerprint)
                : accountingState.evaluateLateActual(actualCost, fingerprint);
        if (!transition.status().isApplied()) {
            return outcome(bucket, transition);
        }

        boolean overLimit = type == CommitType.DIRECT
                ? commitActiveReservation(
                        bucket,
                        accountingState,
                        actualCost,
                        transition.resultingState(),
                        fingerprint
                )
                : commitPendingReservation(
                        bucket,
                        accountingState,
                        actualCost,
                        transition.resultingState(),
                        fingerprint
                );
        return new AccountingTransitionOutcome(transition, overLimit);
    }

    @Override
    public ReservationReconciliation reconcileLateActual(ActualUsageCommand command) {
        return reconcileUsage(
                command,
                CommitType.LATE_ACTUAL,
                ReservationAccountingReason.LATE_ACTUAL_USAGE_REPORTED,
                Optional.empty()
        );
    }

    @Override
    public ReservationReconciliation reconcileLateActual(
            ActualUsageCommand command,
            PricingSnapshot actualPricingSnapshot
    ) {
        return reconcileUsage(
                command,
                CommitType.LATE_ACTUAL,
                ReservationAccountingReason.LATE_ACTUAL_USAGE_REPORTED,
                Optional.of(Objects.requireNonNull(
                        actualPricingSnapshot,
                        "actualPricingSnapshot must not be null"
                ))
        );
    }

    @Override
    public ReservationTransition writeOff(
            ReservationId reservationId,
            ReservationAccountingReason reason
    ) {
        requireWriteOffReason(reason);
        Bucket bucket = bucketFor(reservationId);
        synchronized (bucket) {
            ReservationAccountingState accountingState = accountingState(
                    bucket,
                    reservationId
            );
            ReservationTransition transition = accountingState.evaluateWriteOff();
            if (!transition.status().isApplied()) {
                return transition;
            }

            writeOffPendingReservation(
                    bucket,
                    accountingState,
                    transition.resultingState()
            );
            pendingActualFingerprints.remove(reservationId);
            return transition;
        }
    }

    @Override
    public BudgetSnapshot snapshot(BudgetKey key, Cost limit) {
        validateArguments(key, limit);
        Bucket bucket = store.get(key);
        if (bucket == null) {
            return BudgetSnapshot.empty(key, limit);
        }
        synchronized (bucket) {
            bucket.validate(limit);
            return bucket.snapshot(key);
        }
    }

    private void validateArguments(BudgetKey key, Cost limit) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(limit, "limit must not be null");
        if (limit.value().signum() <= 0) {
            throw new IllegalArgumentException("limit must be greater than zero");
        }
    }

    private void validateCurrency(Cost limit, Cost amount) {
        if (!limit.currency().equals(amount.currency())) {
            throw new IllegalArgumentException("Budget currency does not match cost currency");
        }
    }

    private ReservationReconciliation reconcileUsage(
            ActualUsageCommand command,
            CommitType type,
            ReservationAccountingReason reason,
            Optional<PricingSnapshot> actualPricingSnapshot
    ) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(
                actualPricingSnapshot,
                "actualPricingSnapshot must not be null"
        );
        Bucket bucket = bucketFor(command.reservationId());
        ReservationReconciliation reconciliation;
        BudgetSnapshot accountingSnapshot;
        synchronized (bucket) {
            reconciliation = reconcileUsageInBucket(
                    bucket,
                    command,
                    type,
                    reason,
                    actualPricingSnapshot
            );
            accountingSnapshot = bucket.snapshot(reconciliation.budgetKey());
        }
        publishAccountingEvent(reconciliation, accountingSnapshot);
        return reconciliation;
    }

    private void publishAccountingEvent(
            ReservationReconciliation reconciliation,
            BudgetSnapshot accountingSnapshot
    ) {
        List<ReservationAccountingListener> accountingListeners =
                accountingListeners();
        if (!reconciliation.transition().status().isApplied()
                || accountingListeners.isEmpty()) {
            return;
        }
        ReservationAccountingEvent event = new ReservationAccountingEvent(
                reconciliation
        );
        for (ReservationAccountingListener listener : accountingListeners) {
            notifyBestEffort(
                    listener,
                    ReservationAccountingListenerPhase.ACCOUNTING_APPLIED,
                    () -> listener.onAccountingApplied(event, accountingSnapshot)
            );
        }
    }

    private void publishReservationEvaluated(
            BudgetReservationRequest request,
            BudgetReservationResult result
    ) {
        for (ReservationAccountingListener listener : accountingListeners()) {
            notifyBestEffort(
                    listener,
                    ReservationAccountingListenerPhase.RESERVATION_EVALUATED,
                    () -> listener.onReservationEvaluated(request, result)
            );
        }
    }

    private void publishBlockedReservation(
            BudgetReservationRequest request,
            BudgetReservationResult result
    ) {
        if (result.status() != ReservationStatus.BLOCKED) {
            return;
        }
        for (ReservationAccountingListener listener : accountingListeners()) {
            notifyBestEffort(
                    listener,
                    ReservationAccountingListenerPhase.RESERVATION_BLOCKED,
                    () -> listener.onReservationBlocked(request, result)
            );
        }
    }

    private void publishReconciliationRequired(
            ReservationReconciliationRequiredEvent event
    ) {
        for (ReservationAccountingListener listener : accountingListeners()) {
            notifyBestEffort(
                    listener,
                    ReservationAccountingListenerPhase.RECONCILIATION_REQUIRED,
                    () -> listener.onReconciliationRequired(event)
            );
        }
    }

    private void notifyBestEffort(
            ReservationAccountingListener listener,
            ReservationAccountingListenerPhase phase,
            Runnable callback
    ) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            publishListenerFailure(listenerType(listener), phase);
        }
    }

    private ReservationAccountingListenerType listenerType(
            ReservationAccountingListener listener
    ) {
        try {
            ReservationAccountingListenerType listenerType = listener.listenerType();
            return listenerType == null
                    ? ReservationAccountingListenerType.CUSTOM
                    : listenerType;
        } catch (RuntimeException ignored) {
            return ReservationAccountingListenerType.CUSTOM;
        }
    }

    private void publishListenerFailure(
            ReservationAccountingListenerType listenerType,
            ReservationAccountingListenerPhase phase
    ) {
        List<ReservationAccountingListenerErrorHandler> listenerErrorHandlers =
                listenerErrorHandlers();
        if (listenerErrorHandlers.isEmpty()) {
            return;
        }
        ReservationAccountingListenerFailureEvent event =
                new ReservationAccountingListenerFailureEvent(listenerType, phase);
        for (ReservationAccountingListenerErrorHandler errorHandler
                : listenerErrorHandlers) {
            try {
                errorHandler.onFailure(event);
            } catch (RuntimeException ignored) {
                // Error handler도 best-effort이며 회계/admission 결과에 영향을 주지 않습니다.
            }
        }
    }

    private List<ReservationAccountingListener> accountingListeners() {
        return suppliedList(accountingListenerSupplier);
    }

    private List<ReservationAccountingListenerErrorHandler> listenerErrorHandlers() {
        return suppliedList(listenerErrorHandlerSupplier);
    }

    private static <T> Supplier<List<T>> fixedListSupplier(
            List<T> values,
            String message
    ) {
        List<T> copy = List.copyOf(Objects.requireNonNull(values, message));
        return () -> copy;
    }

    private static <T> List<T> suppliedList(Supplier<List<T>> supplier) {
        try {
            List<T> values = supplier.get();
            return values == null ? List.of() : List.copyOf(values);
        } catch (RuntimeException ignored) {
            // Optional observer resolution cannot change reservation/accounting results.
            return List.of();
        }
    }

    private ReservationReconciliation reconcileUsageInBucket(
            Bucket bucket,
            ActualUsageCommand command,
            CommitType type,
            ReservationAccountingReason reason,
            Optional<PricingSnapshot> actualPricingSnapshot
    ) {
        ReservationAccountingState accountingState = accountingState(
                bucket,
                command.reservationId()
        );
        BudgetReservation reservation = accountingState.reservation();
        if (!reservation.belongsTo(command.requestId())) {
            throw new IllegalArgumentException(
                    "requestId must match the reservation request"
            );
        }

        PricingSnapshot snapshot = reservation.pricingSnapshot().orElseThrow(
                () -> new IllegalStateException(
                        "reservation does not contain a pricing snapshot"
                )
        );
        PricingSnapshot resolvedActualSnapshot = actualPricingSnapshot.orElse(snapshot);
        if (!resolvedActualSnapshot.modelId().equals(command.responseModelId())) {
            throw new IllegalArgumentException(
                    actualPricingSnapshot.isEmpty()
                            ? "responseModelId must match the reservation pricing snapshot"
                            : "responseModelId must match the actual pricing snapshot"
            );
        }
        if (!reservation.amount().currency().equals(resolvedActualSnapshot.currency())) {
            throw new IllegalArgumentException(
                    "actual pricing snapshot must use the reservation currency"
            );
        }
        if (actualPricingSnapshot.isPresent()
                && type != CommitType.LATE_ACTUAL) {
            throw new IllegalArgumentException(
                    "alternate pricing snapshot is only valid for late actual reconciliation"
            );
        }
        ReservationTokenEstimate tokenEstimate = reservation.tokenEstimate().orElseThrow(
                () -> new IllegalStateException(
                        "reservation does not contain a token estimate"
                )
        );
        ReservationActualTokens actualTokens = ReservationActualTokens.from(
                command.usage()
        );
        ActualUsageFingerprint fingerprint = ActualUsageFingerprint.from(
                command,
                actualTokens,
                resolvedActualSnapshot
        );
        ActualUsageFingerprint pendingFingerprint = pendingActualFingerprints.get(
                command.reservationId()
        );
        if (pendingFingerprint != null
                && !pendingFingerprint.sameCallback(fingerprint)) {
            throw new IllegalArgumentException(
                    "actual usage callback conflicts with the pending pricing callback"
            );
        }
        Optional<AppliedCommit> reusedCommit =
                accountingState.reusedCommit(type, fingerprint);
        if (reusedCommit.isPresent()) {
            AppliedCommit appliedCommit = reusedCommit.orElseThrow();
            AccountingTransitionOutcome reused = new AccountingTransitionOutcome(
                    ReservationTransition.unchanged(
                            reservation.state(),
                            AccountingTransitionStatus.REUSED
                    ),
                    appliedCommit.overLimit()
            );
            return reconciliation(
                    command,
                    reservation,
                    snapshot,
                    resolvedActualSnapshot,
                    tokenEstimate,
                    actualTokens,
                    appliedCommit.actualCost(),
                    reused,
                    reason
            );
        }

        Cost actualCost = calculateActualCost(command, resolvedActualSnapshot);
        AccountingTransitionOutcome outcome = applyCostInBucket(
                bucket,
                accountingState,
                actualCost,
                type,
                Optional.of(fingerprint)
        );
        if (outcome.transition().status().isApplied()) {
            pendingActualFingerprints.remove(command.reservationId());
        }
        return reconciliation(
                command,
                reservation,
                snapshot,
                resolvedActualSnapshot,
                tokenEstimate,
                actualTokens,
                actualCost,
                outcome,
                reason
        );
    }

    private Cost calculateActualCost(
            ActualUsageCommand command,
            PricingSnapshot snapshot
    ) {
        Cost actualCost = costCalculator.calculate(command.usage(), snapshot);
        if (!snapshot.currency().equals(actualCost.currency())) {
            throw new IllegalStateException(
                    "calculated cost must use the pricing snapshot currency"
            );
        }
        return actualCost;
    }

    private static ReservationReconciliation reconciliation(
            ActualUsageCommand command,
            BudgetReservation reservation,
            PricingSnapshot requestPricingSnapshot,
            PricingSnapshot actualPricingSnapshot,
            ReservationTokenEstimate tokenEstimate,
            ReservationActualTokens actualTokens,
            Cost actualCost,
            AccountingTransitionOutcome outcome,
            ReservationAccountingReason reason
    ) {
        return new ReservationReconciliation(
                command.requestId(),
                command.attemptId(),
                reservation.id(),
                reservation.key(),
                command.responseModelId(),
                requestPricingSnapshot,
                actualPricingSnapshot,
                tokenEstimate,
                actualTokens,
                reservation.amount(),
                actualCost,
                outcome.overLimit(),
                outcome.transition(),
                reason
        );
    }

    private static AccountingTransitionOutcome outcome(
            Bucket bucket,
            ReservationTransition transition
    ) {
        boolean overLimit = bucket.effectiveUsage().compareTo(bucket.limit) > 0;
        return new AccountingTransitionOutcome(transition, overLimit);
    }

    private ReservationId reserveOrReturnExisting(
            BudgetReservationRequest request,
            ReservationId existingReservationId,
            AtomicReference<BudgetReservationResult> result
    ) {
        if (existingReservationId != null) {
            result.set(resultForExistingReservation(existingReservationId, request));
            return existingReservationId;
        }

        BudgetReservationResult reservationResult = reserveNewRequest(request);
        result.set(reservationResult);
        return reservationResult.reservationId();
    }

    private BudgetReservationResult resultForExistingReservation(
            ReservationId existingReservationId,
            BudgetReservationRequest request
    ) {
        Bucket bucket = bucketFor(existingReservationId);
        synchronized (bucket) {
            BudgetReservation existing = accountingState(
                    bucket,
                    existingReservationId
            ).reservation();
            BudgetSnapshot existingSnapshot = bucket.snapshot(existing.key());
            if (existing.matches(request)) {
                return BudgetReservationResult.reused(existing, existingSnapshot);
            }
            return BudgetReservationResult.conflict(
                    existing,
                    existingSnapshot,
                    "동일 idempotency key에 다른 예약 요청이 사용되었습니다"
            );
        }
    }

    private BudgetReservationResult reserveNewRequest(BudgetReservationRequest request) {
        if (!request.limit().currency().equals(request.safeUpperBoundCost().currency())) {
            return BudgetReservationResult.currencyMismatch(
                    BudgetSnapshot.empty(request.key(), request.limit())
            );
        }

        Bucket bucket = store.computeIfAbsent(
                request.key(),
                ignored -> new Bucket(request.limit())
        );
        synchronized (bucket) {
            return reserveInBucket(bucket, request);
        }
    }

    private BudgetReservationResult reserveInBucket(
            Bucket bucket,
            BudgetReservationRequest request
    ) {
        if (!bucket.limit.currency().equals(request.limit().currency())) {
            return BudgetReservationResult.currencyMismatch(
                    bucket.snapshot(request.key())
            );
        }
        if (!bucket.limit.equals(request.limit())) {
            return BudgetReservationResult.conflict(
                    null,
                    bucket.snapshot(request.key()),
                    "기존 budget bucket의 limit snapshot이 변경되었습니다"
            );
        }

        Cost projectedUsage = bucket.effectiveUsage().add(request.safeUpperBoundCost());
        if (projectedUsage.compareTo(request.limit()) >= 0) {
            return BudgetReservationResult.blocked(
                    bucket.snapshot(request.key()),
                    "예약 후 사용량이 예산 한도에 도달하거나 초과합니다"
            );
        }

        return createReservation(bucket, request);
    }

    private BudgetReservationResult createReservation(
            Bucket bucket,
            BudgetReservationRequest request
    ) {
        ReservationId reservationId = Objects.requireNonNull(
                reservationIdGenerator.get(),
                "reservationIdGenerator returned null"
        );
        BudgetReservation reservation = BudgetReservation.reserved(
                reservationId,
                request,
                clock.instant()
        );
        if (bucket.reservationsById.containsKey(reservationId)) {
            throw duplicateReservationId();
        }
        if (reservationIndex.putIfAbsent(reservationId, request.key()) != null) {
            throw duplicateReservationId();
        }

        bucket.activeReservedCost = bucket.activeReservedCost.add(
                request.safeUpperBoundCost()
        );
        bucket.reservationsById.put(
                reservationId,
                ReservationAccountingState.reserved(reservation)
        );
        return BudgetReservationResult.created(
                reservation,
                bucket.snapshot(request.key())
        );
    }

    private static IllegalStateException duplicateReservationId() {
        return new IllegalStateException(
                "reservationIdGenerator returned a duplicate reservation id"
        );
    }

    private ReservationTransition updateState(
            ReservationId reservationId,
            java.util.function.Function<ReservationState, ReservationTransition> transitionRule
    ) {
        Objects.requireNonNull(transitionRule, "transitionRule must not be null");
        Bucket bucket = bucketFor(reservationId);
        synchronized (bucket) {
            ReservationAccountingState accountingState = accountingState(
                    bucket,
                    reservationId
            );
            BudgetReservation reservation = accountingState.reservation();
            ReservationTransition transition = Objects.requireNonNull(
                    transitionRule.apply(reservation.state()),
                    "transitionRule must return a transition"
            );
            if (transition.status().isApplied()) {
                BudgetReservation updated = withState(
                        reservation,
                        transition.resultingState()
                );
                replaceReservationSnapshot(
                        bucket,
                        accountingState,
                        accountingState.withReservation(updated)
                );
            }
            return transition;
        }
    }

    private boolean commitActiveReservation(
            Bucket bucket,
            ReservationAccountingState accountingState,
            Cost actualCost,
            ReservationState resultingState,
            Optional<ActualUsageFingerprint> fingerprint
    ) {
        BudgetReservation reservation = accountingState.reservation();
        Cost remainingReserved = subtract(
                bucket.activeReservedCost,
                reservation.amount()
        );
        Cost committed = bucket.committedCost.add(actualCost);
        BudgetReservation updated = withState(reservation, resultingState);

        boolean overLimit = remainingReserved
                .add(bucket.pendingReconciliationLiability)
                .add(committed)
                .compareTo(bucket.limit) > 0;
        ReservationAccountingState updatedAccountingState = fingerprint
                .map(appliedFingerprint -> accountingState.committed(
                        updated,
                        actualCost,
                        overLimit,
                        appliedFingerprint
                ))
                .orElseGet(() -> accountingState.committed(updated, actualCost));

        bucket.activeReservedCost = remainingReserved;
        bucket.committedCost = committed;
        replaceReservationSnapshot(
                bucket,
                accountingState,
                updatedAccountingState
        );
        return overLimit;
    }

    private void releaseActiveReservation(
            Bucket bucket,
            ReservationAccountingState accountingState,
            ReservationAccountingState updatedAccountingState
    ) {
        BudgetReservation reservation = accountingState.reservation();
        Cost remainingReserved = subtract(
                bucket.activeReservedCost,
                reservation.amount()
        );

        bucket.activeReservedCost = remainingReserved;
        replaceReservationSnapshot(
                bucket,
                accountingState,
                updatedAccountingState
        );
    }

    private void moveActiveReservationToPending(
            Bucket bucket,
            ReservationAccountingState accountingState,
            ReservationState resultingState
    ) {
        BudgetReservation reservation = accountingState.reservation();
        Cost remainingReserved = subtract(
                bucket.activeReservedCost,
                reservation.amount()
        );
        Cost pending = bucket.pendingReconciliationLiability.add(
                reservation.amount()
        );
        BudgetReservation updated = withState(reservation, resultingState);

        bucket.activeReservedCost = remainingReserved;
        bucket.pendingReconciliationLiability = pending;
        replaceReservationSnapshot(
                bucket,
                accountingState,
                accountingState.withReservation(updated)
        );
    }

    private boolean commitPendingReservation(
            Bucket bucket,
            ReservationAccountingState accountingState,
            Cost actualCost,
            ReservationState resultingState,
            Optional<ActualUsageFingerprint> fingerprint
    ) {
        BudgetReservation reservation = accountingState.reservation();
        Cost remainingPending = subtract(
                bucket.pendingReconciliationLiability,
                reservation.amount()
        );
        Cost committed = bucket.committedCost.add(actualCost);
        BudgetReservation updated = withState(reservation, resultingState);

        boolean overLimit = bucket.activeReservedCost
                .add(remainingPending)
                .add(committed)
                .compareTo(bucket.limit) > 0;
        ReservationAccountingState updatedAccountingState = fingerprint
                .map(appliedFingerprint -> accountingState.lateActualCommitted(
                        updated,
                        actualCost,
                        overLimit,
                        appliedFingerprint
                ))
                .orElseGet(
                        () -> accountingState.lateActualCommitted(
                                updated,
                                actualCost
                        )
                );

        bucket.pendingReconciliationLiability = remainingPending;
        bucket.committedCost = committed;
        replaceReservationSnapshot(
                bucket,
                accountingState,
                updatedAccountingState
        );
        return overLimit;
    }

    private void writeOffPendingReservation(
            Bucket bucket,
            ReservationAccountingState accountingState,
            ReservationState resultingState
    ) {
        BudgetReservation reservation = accountingState.reservation();
        Cost remainingPending = subtract(
                bucket.pendingReconciliationLiability,
                reservation.amount()
        );
        BudgetReservation updated = withState(reservation, resultingState);

        bucket.pendingReconciliationLiability = remainingPending;
        replaceReservationSnapshot(
                bucket,
                accountingState,
                accountingState.withReservation(updated)
        );
    }

    private static void requireReconciliationRequiredReason(
            ReservationAccountingReason reason
    ) {
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason != ReservationAccountingReason.ACTUAL_USAGE_UNAVAILABLE
                && reason != ReservationAccountingReason.PRICING_RECONCILIATION_REQUIRED
                && reason != ReservationAccountingReason.CALLBACK_TIMED_OUT) {
            throw new IllegalArgumentException(
                    "reason is not valid for markReconciliationRequired"
            );
        }
    }

    private static void requireWriteOffReason(ReservationAccountingReason reason) {
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason != ReservationAccountingReason.MANUAL_WRITE_OFF
                && reason != ReservationAccountingReason.ACTUAL_USAGE_UNRECOVERABLE) {
            throw new IllegalArgumentException("reason is not valid for writeOff");
        }
    }

    private void replaceReservationSnapshot(
            Bucket bucket,
            ReservationAccountingState previous,
            ReservationAccountingState updated
    ) {
        BudgetReservation previousReservation = previous.reservation();
        bucket.reservationsById.put(previousReservation.id(), updated);
    }

    private Bucket bucketFor(ReservationId reservationId) {
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        BudgetKey key = reservationIndex.get(reservationId);
        if (key == null) {
            throw new IllegalArgumentException("reservation does not exist");
        }
        return Objects.requireNonNull(store.get(key), "reservation bucket must exist");
    }

    private static ReservationAccountingState accountingState(
            Bucket bucket,
            ReservationId reservationId
    ) {
        return Objects.requireNonNull(
                bucket.reservationsById.get(reservationId),
                "reservation must exist in its bucket"
        );
    }

    private static void requireCostOnlyReservation(BudgetReservation reservation) {
        if (reservation.pricingSnapshot().isPresent()
                && reservation.tokenEstimate().isPresent()) {
            throw new IllegalStateException(
                    "usage-based reconciliation is required for reservations with pricing and token metadata"
            );
        }
    }

    private static Cost subtract(Cost total, Cost amount) {
        if (total.compareTo(amount) < 0) {
            throw new IllegalStateException("reserved cost must not become negative");
        }
        return Cost.of(total.value().subtract(amount.value()), total.currency());
    }

    private static BudgetReservation withState(
            BudgetReservation reservation,
            ReservationState state
    ) {
        return new BudgetReservation(
                reservation.id(),
                reservation.key(),
                reservation.limit(),
                reservation.amount(),
                reservation.requestId(),
                reservation.idempotencyKey(),
                reservation.modelId(),
                reservation.pricingPolicyId(),
                reservation.catalogVersion(),
                reservation.pricingSnapshot(),
                reservation.tokenEstimate(),
                state,
                reservation.createdAt()
        );
    }

    private static final class Bucket {
        private final Cost limit;
        private Cost committedCost;
        private Cost activeReservedCost;
        private Cost pendingReconciliationLiability;
        private final Map<ReservationId, ReservationAccountingState> reservationsById =
                new LinkedHashMap<>();

        private Bucket(Cost limit) {
            this.limit = limit;
            this.committedCost = Cost.zero(limit.currency());
            this.activeReservedCost = Cost.zero(limit.currency());
            this.pendingReconciliationLiability = Cost.zero(limit.currency());
        }

        private void validate(Cost expectedLimit) {
            if (!limit.equals(expectedLimit)) {
                throw new IllegalArgumentException(
                        "Budget policy snapshot changed for an existing key"
                );
            }
        }

        private Cost effectiveUsage() {
            return committedCost
                    .add(activeReservedCost)
                    .add(pendingReconciliationLiability);
        }

        private BudgetSnapshot snapshot(BudgetKey key) {
            return new BudgetSnapshot(
                    key,
                    limit,
                    committedCost,
                    activeReservedCost,
                    pendingReconciliationLiability,
                    activeReservationIds()
            );
        }

        private Set<ReservationId> activeReservationIds() {
            return reservationsById.entrySet().stream()
                    .filter(entry -> {
                        ReservationState state = entry.getValue().reservation().state();
                        return state == ReservationState.RESERVED
                                || state == ReservationState.IN_FLIGHT;
                    })
                    .map(Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    private record AccountingTransitionOutcome(
            ReservationTransition transition,
            boolean overLimit
    ) {

        private AccountingTransitionOutcome {
            Objects.requireNonNull(transition, "transition must not be null");
        }
    }

}
