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
import io.tokenpilot.budget.ReservationAccountingReason;
import io.tokenpilot.budget.ReservationReconciliationRequiredEvent;
import io.tokenpilot.core.domain.Cost;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 원자적 budget/accounting 결과를 threshold 알림으로 변환하는 best-effort listener입니다.
 *
 * <p>threshold claim은 handler 호출 전에 완료됩니다. handler 실패는 다음 handler 전달을
 * 막지 않고 이미 적용된 회계 또는 admission 결과를 변경하지 않으며, 실패한 전달은 durable
 * outbox가 없는 MVP에서 재시도되지 않습니다.</p>
 */
public class BudgetNotificationService implements ReservationAccountingListener {

  private final List<BudgetNotificationHandler> handlers;
  private final NotificationStateStore store;
  private final AtomicNotificationStateStore atomicStore;
  private final BudgetNotificationErrorHook errorHook;
  private final Function<BudgetKey, BudgetSnapshot> snapshotResolver;

  /**
   * @deprecated legacy {@link BudgetDecision} 알림 호환용 생성자입니다. 신규 연결은 복수
   *             handler와 error hook을 받는 생성자를 사용하세요.
   */
  @Deprecated(since = "0.1.0", forRemoval = false)
  public BudgetNotificationService(
      BudgetNotificationHandler handler,
      NotificationStateStore store
  ) {
    this.handlers = List.of(
        Objects.requireNonNull(handler, "handler must not be null")
    );
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.atomicStore = null;
    this.errorHook = BudgetNotificationErrorHook.noOp();
    this.snapshotResolver = null;
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
        BudgetNotificationErrorHook.noOp()
    );
  }

  public BudgetNotificationService(
      List<BudgetNotificationHandler> handlers,
      AtomicNotificationStateStore store,
      Function<BudgetKey, BudgetSnapshot> snapshotResolver,
      BudgetNotificationErrorHook errorHook
  ) {
    this.handlers = List.copyOf(
        Objects.requireNonNull(handlers, "handlers must not be null")
    );
    if (this.handlers.isEmpty()) {
      throw new IllegalArgumentException("handlers must not be empty");
    }
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.atomicStore = store;
    this.snapshotResolver = Objects.requireNonNull(
        snapshotResolver,
        "snapshotResolver must not be null"
    );
    this.errorHook = Objects.requireNonNull(errorHook, "errorHook must not be null");
  }

  /**
   * 기존 #37 callback은 주입된 resolver로 현재 bucket snapshot을 조회합니다. 현재 budget store는
   * 정확한 전이 시점 snapshot을 포함하는
   * {@link #onAccountingApplied(ReservationAccountingEvent, BudgetSnapshot)}를 호출합니다.
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
      claim = atomicStore.recordAppliedTransition(event, snapshot, source);
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
      claim = atomicStore.recordReconciliationRequired(event);
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
      claim = atomicStore.recordBlockedReservation(request, result);
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
   * @deprecated legacy evaluator 결과는 admission/accounting 알림 근거가 아닙니다. 신규 코드는
   *             {@link ReservationAccountingListener} 연결을 사용하세요. tags는 전달하지 않습니다.
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

    BudgetThreshold last = store.getLastNotifiedThreshold(decision.key());
    if (current.compareTo(last) <= 0) {
      return;
    }
    store.updateLastNotifiedThreshold(decision.key(), current);
    dispatch(new BudgetNotificationEvent(
        decision.key(),
        current,
        decision.state(),
        decision.reason(),
        decision.projectedUsage(),
        decision.limit(),
        BudgetNotificationSource.LEGACY_DECISION
    ));
  }

  private void publish(
      BudgetKey key,
      NotificationStateStore.NotificationClaim claim,
      String reason,
      BudgetNotificationSource source,
      Cost limit
  ) {
    for (BudgetThreshold threshold : claim.thresholds()) {
      dispatch(new BudgetNotificationEvent(
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

  private void dispatch(BudgetNotificationEvent event) {
    for (BudgetNotificationHandler handler : handlers) {
      try {
        handler.handle(event);
      } catch (RuntimeException failure) {
        report(BudgetNotificationError.handlerFailure(handler, failure));
      }
    }
  }

  private void report(BudgetNotificationError error) {
    try {
      errorHook.onError(error);
    } catch (RuntimeException ignored) {
      // Error hook도 best-effort이며 accounting/provider 결과에 영향을 주지 않습니다.
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
      case HALF, WARNING -> BudgetState.WARN;
      case EXCEEDED -> BudgetState.BLOCK;
      case NONE -> throw new IllegalArgumentException("NONE must not be published");
    };
  }
}
