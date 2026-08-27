package io.tokenpilot.notification;

import io.tokenpilot.budget.BudgetDecision;
import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetReservationRequest;
import io.tokenpilot.budget.BudgetReservationResult;
import io.tokenpilot.budget.BudgetSnapshot;
import io.tokenpilot.budget.BudgetState;
import io.tokenpilot.budget.BudgetThreshold;
import io.tokenpilot.budget.ReservationAccountingEvent;
import io.tokenpilot.budget.ReservationAccountingListener;
import io.tokenpilot.budget.ReservationAccountingListenerType;
import io.tokenpilot.budget.ReservationAccountingReason;
import io.tokenpilot.budget.ReservationReconciliationRequiredEvent;
import io.tokenpilot.core.domain.Cost;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Best-effort listener that converts atomic budget/accounting results into threshold notifications.
 *
 * <p>Threshold claims for atomic callbacks complete before handlers are called.
 * A handler failure does not block the next handler or change an already applied
 * accounting/admission result, and failed delivery is not retried in the MVP
 * because there is no durable outbox. For compatibility, the legacy decision
 * path updates state only after successful delivery, so a failed call is retried
 * on the next call. Claims from both paths are serialized on the same store
 * monitor and do not deliver the same threshold twice when mixed.</p>
 */
public class BudgetNotificationService implements ReservationAccountingListener {

  private final List<BudgetNotificationHandler> handlers;
  private final NotificationStateStore store;
  private final AtomicNotificationStateStore atomicStore;
  private final BudgetNotificationErrorHook errorHook;
  private final Function<BudgetKey, BudgetSnapshot> snapshotResolver;
  private final List<BudgetNotificationLifecycleListener> lifecycleListeners;

  /**
   * @deprecated Compatibility constructor for legacy {@link BudgetDecision}
   *             notifications. New wiring should use the constructor accepting
   *             multiple handlers and an error hook.
   */
  @Deprecated(since = "0.1.0", forRemoval = false)
  public BudgetNotificationService(
      BudgetNotificationHandler handler,
      NotificationStateStore store
  ) {
    this(handler, store, List.of());
  }

  /** Compatibility constructor that adds lifecycle observation to legacy decision notifications. */
  public BudgetNotificationService(
      BudgetNotificationHandler handler,
      NotificationStateStore store,
      List<BudgetNotificationLifecycleListener> lifecycleListeners
  ) {
    this(
        List.of(Objects.requireNonNull(handler, "handler must not be null")),
        Objects.requireNonNull(store, "store must not be null"),
        store instanceof AtomicNotificationStateStore atomic ? atomic : null,
        null,
        BudgetNotificationErrorHook.noOp(),
        lifecycleListeners
    );
  }

  public BudgetNotificationService(
      List<BudgetNotificationHandler> handlers,
      AtomicNotificationStateStore store,
      Function<BudgetKey, BudgetSnapshot> snapshotResolver
  ) {
    this(
        handlers,
        store,
        snapshotResolver,
        BudgetNotificationErrorHook.noOp(),
        List.of()
    );
  }

  public BudgetNotificationService(
      List<BudgetNotificationHandler> handlers,
      AtomicNotificationStateStore store,
      Function<BudgetKey, BudgetSnapshot> snapshotResolver,
      BudgetNotificationErrorHook errorHook
  ) {
    this(handlers, store, snapshotResolver, errorHook, List.of());
  }

  public BudgetNotificationService(
      List<BudgetNotificationHandler> handlers,
      AtomicNotificationStateStore store,
      Function<BudgetKey, BudgetSnapshot> snapshotResolver,
      BudgetNotificationErrorHook errorHook,
      List<BudgetNotificationLifecycleListener> lifecycleListeners
  ) {
    this(
        handlers,
        store,
        store,
        Objects.requireNonNull(
            snapshotResolver,
            "snapshotResolver must not be null"
        ),
        errorHook,
        lifecycleListeners
    );
  }

  private BudgetNotificationService(
      List<BudgetNotificationHandler> handlers,
      NotificationStateStore store,
      AtomicNotificationStateStore atomicStore,
      Function<BudgetKey, BudgetSnapshot> snapshotResolver,
      BudgetNotificationErrorHook errorHook,
      List<BudgetNotificationLifecycleListener> lifecycleListeners
  ) {
    this.handlers = List.copyOf(
        Objects.requireNonNull(handlers, "handlers must not be null")
    );
    if (this.handlers.isEmpty()) {
      throw new IllegalArgumentException("handlers must not be empty");
    }
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.atomicStore = atomicStore;
    this.snapshotResolver = snapshotResolver;
    this.errorHook = Objects.requireNonNull(errorHook, "errorHook must not be null");
    this.lifecycleListeners = List.copyOf(
        Objects.requireNonNull(lifecycleListeners, "lifecycleListeners must not be null")
    );
  }

  /**
   * The former #37 callback reads the current bucket snapshot through the
   * injected resolver. The current budget store calls
   * {@link #onAccountingApplied(ReservationAccountingEvent, BudgetSnapshot)}
   * with the snapshot from the exact transition point.
   */
  @Override
  public void onCommitted(ReservationAccountingEvent event) {
    Objects.requireNonNull(event, "event must not be null");
    requireAtomicLifecycle();
    BudgetSnapshot snapshot;
    try {
      snapshot = Objects.requireNonNull(
          snapshotResolver.apply(event.reconciliation().budgetKey()),
          "snapshotResolver returned null"
      );
    } catch (RuntimeException failure) {
      report(BudgetNotificationError.stateFailure(failure));
      return;
    }
    processAppliedAccounting(event, snapshot);
  }

  @Override
  public void onAccountingApplied(
      ReservationAccountingEvent event,
      BudgetSnapshot snapshot
  ) {
    Objects.requireNonNull(event, "event must not be null");
    Objects.requireNonNull(snapshot, "snapshot must not be null");
    requireAtomicLifecycle();
    processAppliedAccounting(event, snapshot);
  }

  private void processAppliedAccounting(
      ReservationAccountingEvent event,
      BudgetSnapshot snapshot
  ) {
    ReservationAccountingReason reason = event.reconciliation().reason();
    BudgetNotificationSource source = reason
        == ReservationAccountingReason.LATE_ACTUAL_USAGE_REPORTED
        ? BudgetNotificationSource.LATE_RECONCILIATION
        : BudgetNotificationSource.ACCOUNTING_COMMIT;

    NotificationStateStore.NotificationClaim claim;
    try {
      claim = claimAtomic(
          () -> atomicStore.recordAppliedTransition(event, snapshot, source)
      );
    } catch (RuntimeException failure) {
      report(BudgetNotificationError.stateFailure(failure));
      return;
    }

    publish(
        event.reconciliation().budgetKey(),
        claim,
        reason.name(),
        source,
        snapshot.limit()
    );
  }

  @Override
  public void onReconciliationRequired(
      ReservationReconciliationRequiredEvent event
  ) {
    Objects.requireNonNull(event, "event must not be null");
    requireAtomicLifecycle();

    NotificationStateStore.NotificationClaim claim;
    try {
      claim = claimAtomic(() -> atomicStore.recordReconciliationRequired(event));
    } catch (RuntimeException failure) {
      report(BudgetNotificationError.stateFailure(failure));
      return;
    }
    publish(
        event.budgetKey(),
        claim,
        event.reason().name(),
        BudgetNotificationSource.RECONCILIATION_REQUIRED,
        event.snapshot().limit()
    );
  }

  @Override
  public void onReservationBlocked(
      BudgetReservationRequest request,
      BudgetReservationResult result
  ) {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(result, "result must not be null");
    requireAtomicLifecycle();

    NotificationStateStore.NotificationClaim claim;
    try {
      claim = claimAtomic(() -> atomicStore.recordBlockedReservation(request, result));
    } catch (RuntimeException failure) {
      report(BudgetNotificationError.stateFailure(failure));
      return;
    }
    publish(
        request.key(),
        claim,
        result.reason(),
        BudgetNotificationSource.RESERVATION_BLOCK,
        request.limit()
    );
  }

  /**
   * @deprecated Legacy evaluator results are not grounds for admission/accounting
   *             notifications. New code should wire a
   *             {@link ReservationAccountingListener}. Tags are retained as an
   *             immutable copy only for legacy events.
   */
  @Deprecated(since = "0.1.0", forRemoval = false)
  public void notifyIfNeeded(
      BudgetDecision decision,
      Map<String, String> tags
  ) {
    Objects.requireNonNull(decision, "decision must not be null");
    Objects.requireNonNull(tags, "tags must not be null");
    BudgetThreshold current = decision.threshold();
    if (current == BudgetThreshold.NONE) {
      return;
    }

    BudgetNotificationOutcome outcome;
    RuntimeException handlerFailure = null;
    synchronized (store) {
      BudgetThreshold last = store.getLastNotifiedThreshold(decision.key());
      if (current.compareTo(last) <= 0) {
        outcome = BudgetNotificationOutcome.DEDUPLICATED;
      } else {
        BudgetNotificationEvent event = new BudgetNotificationEvent(
            decision.key(),
            current,
            decision.state(),
            decision.reason(),
            decision.projectedUsage(),
            decision.limit(),
            tags
        );
        try {
          for (BudgetNotificationHandler handler : handlers) {
            handler.handle(event);
          }
          store.updateLastNotifiedThreshold(decision.key(), current);
          outcome = BudgetNotificationOutcome.SUCCESS;
        } catch (RuntimeException failure) {
          outcome = BudgetNotificationOutcome.FAILURE;
          handlerFailure = failure;
        }
      }
    }

    publishLifecycleBestEffort(outcome, current);
    if (handlerFailure != null) {
      throw handlerFailure;
    }
  }

  private void publish(
      BudgetKey key,
      NotificationStateStore.NotificationClaim claim,
      String reason,
      BudgetNotificationSource source,
      Cost limit
  ) {
    if (claim.thresholds().isEmpty()) {
      if (claim.observedThreshold() != BudgetThreshold.NONE) {
        publishLifecycleBestEffort(
            BudgetNotificationOutcome.DEDUPLICATED,
            claim.observedThreshold()
        );
      }
      return;
    }
    for (BudgetThreshold threshold : claim.thresholds()) {
      dispatchAtomic(new BudgetNotificationEvent(
          key,
          threshold,
          state(threshold),
          reason,
          claim.usage(),
          limit,
          source
      ));
    }
  }

  private void dispatchAtomic(BudgetNotificationEvent event) {
    for (BudgetNotificationHandler handler : handlers) {
      try {
        handler.handle(event);
        publishLifecycleBestEffort(
            BudgetNotificationOutcome.SUCCESS,
            event.threshold()
        );
      } catch (RuntimeException failure) {
        publishLifecycleBestEffort(
            BudgetNotificationOutcome.FAILURE,
            event.threshold()
        );
        report(BudgetNotificationError.handlerFailure(handler, failure));
      }
    }
  }

  @Override
  public ReservationAccountingListenerType listenerType() {
    return ReservationAccountingListenerType.NOTIFICATION;
  }

  private void report(BudgetNotificationError error) {
    try {
      errorHook.onError(error);
    } catch (RuntimeException ignored) {
      // The error hook is also best-effort and does not affect accounting or provider results.
    }
  }

  private NotificationStateStore.NotificationClaim claimAtomic(
      Supplier<NotificationStateStore.NotificationClaim> claim
  ) {
    synchronized (store) {
      return claim.get();
    }
  }

  private void requireAtomicLifecycle() {
    if (atomicStore == null || snapshotResolver == null) {
      throw new IllegalStateException(
          "atomic accounting callbacks require AtomicNotificationStateStore"
      );
    }
  }

  private static BudgetState state(BudgetThreshold threshold) {
    return switch (threshold) {
      case HALF -> BudgetState.ALLOW;
      case WARNING -> BudgetState.WARN;
      case EXCEEDED -> BudgetState.BLOCK;
      case NONE -> throw new IllegalArgumentException("NONE must not be published");
    };
  }

  private void publishLifecycleBestEffort(
      BudgetNotificationOutcome outcome,
      BudgetThreshold threshold
  ) {
    if (lifecycleListeners.isEmpty()) {
      return;
    }
    BudgetNotificationLifecycleEvent event = new BudgetNotificationLifecycleEvent(
        outcome,
        threshold
    );
    for (BudgetNotificationLifecycleListener listener : lifecycleListeners) {
      try {
        listener.onNotificationLifecycle(event);
      } catch (RuntimeException ignored) {
        // Optional observers do not alter notification delivery or failure propagation.
      }
    }
  }
}
