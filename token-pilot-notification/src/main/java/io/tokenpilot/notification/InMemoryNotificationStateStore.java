package io.tokenpilot.notification;

import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetReservationRequest;
import io.tokenpilot.budget.BudgetReservationResult;
import io.tokenpilot.budget.BudgetSnapshot;
import io.tokenpilot.budget.BudgetThreshold;
import io.tokenpilot.budget.ReservationAccountingEvent;
import io.tokenpilot.budget.ReservationId;
import io.tokenpilot.budget.ReservationReconciliationRequiredEvent;
import io.tokenpilot.budget.ReservationStatus;
import io.tokenpilot.core.domain.Cost;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 메모리 기반 accounting threshold dedup 저장소입니다.
 *
 * <p>dedup key는 {@code BudgetKey(policy/target/window) + BudgetThreshold}입니다.
 * 처리한 accounting transition은 같은 bucket의 {@link ReservationId}로 중복 claim을 막습니다.
 * 상태는 이 store 인스턴스의 생명주기 동안 보존되며 재시작 후 replay나 TTL 정리는 제공하지
 * 않습니다. 따라서 MVP 전달 보장은 process-local best-effort이며 durable exactly-once가 아닙니다.</p>
 */
@SuppressWarnings("deprecation")
public class InMemoryNotificationStateStore
    implements AtomicNotificationStateStore {

  private static final BigDecimal HALF_RATIO = new BigDecimal("0.5");
  private static final BigDecimal WARNING_RATIO = new BigDecimal("0.8");

  private final Map<BudgetKey, BucketState> store = new ConcurrentHashMap<>();

  @Override
  public NotificationClaim recordAppliedTransition(
      ReservationAccountingEvent event,
      BudgetSnapshot snapshot,
      BudgetNotificationSource source
  ) {
    Objects.requireNonNull(event, "event must not be null");
    Objects.requireNonNull(snapshot, "snapshot must not be null");
    var reconciliation = event.reconciliation();
    if (!reconciliation.transition().status().isApplied()) {
      return NotificationClaim.none(snapshot.effectiveUsage());
    }
    if (!reconciliation.budgetKey().equals(snapshot.key())) {
      throw new IllegalArgumentException(
          "accounting event and snapshot must use the same budget key"
      );
    }
    requireCurrency(snapshot.limit(), reconciliation.actual());
    requireAccountingSource(source);

    return recordTransition(
        reconciliation.reservationId(),
        reconciliation.budgetKey(),
        snapshot,
        source
    );
  }

  @Override
  public NotificationClaim recordReconciliationRequired(
      ReservationReconciliationRequiredEvent event
  ) {
    Objects.requireNonNull(event, "event must not be null");
    return recordTransition(
        event.reservationId(),
        event.budgetKey(),
        event.snapshot(),
        BudgetNotificationSource.RECONCILIATION_REQUIRED
    );
  }

  private NotificationClaim recordTransition(
      ReservationId reservationId,
      BudgetKey key,
      BudgetSnapshot snapshot,
      BudgetNotificationSource source
  ) {
    TransitionDedupKey dedupKey = new TransitionDedupKey(
        reservationId,
        source
    );

    AtomicReference<NotificationClaim> claim = new AtomicReference<>();
    store.compute(key, (ignored, existing) -> {
      BucketState state = state(existing, snapshot.limit());
      if (!state.processedTransitions.add(dedupKey)) {
        claim.set(NotificationClaim.none(snapshot.effectiveUsage()));
        return state;
      }

      List<BudgetThreshold> thresholds = newlyReached(
          state.lastNotifiedThreshold,
          snapshot.effectiveUsage(),
          state.limit
      );
      if (!thresholds.isEmpty()) {
        state.lastNotifiedThreshold = thresholds.get(thresholds.size() - 1);
      }
      claim.set(new NotificationClaim(snapshot.effectiveUsage(), thresholds));
      return state;
    });
    return Objects.requireNonNull(claim.get(), "notification claim must be set");
  }

  @Override
  public NotificationClaim recordBlockedReservation(
      BudgetReservationRequest request,
      BudgetReservationResult result
  ) {
    Objects.requireNonNull(request, "request must not be null");
    Objects.requireNonNull(result, "result must not be null");
    if (result.status() != ReservationStatus.BLOCKED) {
      return NotificationClaim.none(result.snapshot().effectiveUsage());
    }
    if (!request.key().equals(result.snapshot().key())
        || !request.limit().equals(result.snapshot().limit())) {
      throw new IllegalArgumentException("blocked result must match its reservation request");
    }

    Cost projectedUsage = result.snapshot()
        .effectiveUsage()
        .add(request.safeUpperBoundCost());
    if (projectedUsage.compareTo(request.limit()) < 0) {
      throw new IllegalArgumentException("blocked usage must reach the budget limit");
    }

    AtomicReference<NotificationClaim> claim = new AtomicReference<>();
    store.compute(request.key(), (ignored, existing) -> {
      BucketState state = state(existing, request.limit());
      if (state.lastNotifiedThreshold.compareTo(BudgetThreshold.EXCEEDED) >= 0) {
        claim.set(NotificationClaim.none(projectedUsage));
        return state;
      }
      state.lastNotifiedThreshold = BudgetThreshold.EXCEEDED;
      claim.set(new NotificationClaim(
          projectedUsage,
          List.of(BudgetThreshold.EXCEEDED)
      ));
      return state;
    });
    return Objects.requireNonNull(claim.get(), "notification claim must be set");
  }

  @Override
  public BudgetThreshold getLastNotifiedThreshold(BudgetKey key) {
    Objects.requireNonNull(key, "key must not be null");
    BucketState state = store.get(key);
    return state == null ? BudgetThreshold.NONE : state.lastNotifiedThreshold;
  }

  @Override
  public void updateLastNotifiedThreshold(
      BudgetKey key,
      BudgetThreshold threshold
  ) {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(threshold, "threshold must not be null");
    store.compute(key, (ignored, existing) -> {
      BucketState state = existing == null ? BucketState.legacy() : existing;
      if (threshold.compareTo(state.lastNotifiedThreshold) > 0) {
        state.lastNotifiedThreshold = threshold;
      }
      return state;
    });
  }

  private static BucketState state(BucketState existing, Cost limit) {
    if (existing == null) {
      return BucketState.accounting(limit);
    }
    if (existing.limit == null) {
      existing.limit = limit;
      return existing;
    }
    if (!existing.limit.equals(limit)) {
      throw new IllegalArgumentException(
          "notification budget limit snapshot changed for an existing key"
      );
    }
    return existing;
  }

  private static List<BudgetThreshold> newlyReached(
      BudgetThreshold last,
      Cost usage,
      Cost limit
  ) {
    List<BudgetThreshold> thresholds = new ArrayList<>(3);
    addIfReached(thresholds, last, BudgetThreshold.HALF, usage, limit, HALF_RATIO);
    addIfReached(
        thresholds,
        last,
        BudgetThreshold.WARNING,
        usage,
        limit,
        WARNING_RATIO
    );
    addIfReached(
        thresholds,
        last,
        BudgetThreshold.EXCEEDED,
        usage,
        limit,
        BigDecimal.ONE
    );
    return List.copyOf(thresholds);
  }

  private static void addIfReached(
      List<BudgetThreshold> thresholds,
      BudgetThreshold last,
      BudgetThreshold candidate,
      Cost usage,
      Cost limit,
      BigDecimal ratio
  ) {
    if (candidate.compareTo(last) <= 0) {
      return;
    }
    Cost boundary = Cost.of(
        limit.value().multiply(ratio),
        limit.currency()
    );
    if (usage.compareTo(boundary) >= 0) {
      thresholds.add(candidate);
    }
  }

  private static void requireCurrency(Cost limit, Cost amount) {
    if (!limit.currency().equals(amount.currency())) {
      throw new IllegalArgumentException(
          "notification usage and limit must use the same currency"
      );
    }
  }

  private static void requireAccountingSource(BudgetNotificationSource source) {
    Objects.requireNonNull(source, "source must not be null");
    if (source != BudgetNotificationSource.ACCOUNTING_COMMIT
        && source != BudgetNotificationSource.LATE_RECONCILIATION) {
      throw new IllegalArgumentException(
          "source must identify an applied accounting result"
      );
    }
  }

  private static final class BucketState {
    private volatile Cost limit;
    private volatile BudgetThreshold lastNotifiedThreshold;
    private final Set<TransitionDedupKey> processedTransitions;

    private BucketState(
        Cost limit,
        BudgetThreshold lastNotifiedThreshold
    ) {
      this.limit = limit;
      this.lastNotifiedThreshold = lastNotifiedThreshold;
      this.processedTransitions = new HashSet<>();
    }

    private static BucketState accounting(Cost limit) {
      return new BucketState(
          limit,
          BudgetThreshold.NONE
      );
    }

    private static BucketState legacy() {
      return new BucketState(null, BudgetThreshold.NONE);
    }
  }

  private record TransitionDedupKey(
      ReservationId reservationId,
      BudgetNotificationSource source
  ) {

    private TransitionDedupKey {
      Objects.requireNonNull(reservationId, "reservationId must not be null");
      Objects.requireNonNull(source, "source must not be null");
    }
  }
}
