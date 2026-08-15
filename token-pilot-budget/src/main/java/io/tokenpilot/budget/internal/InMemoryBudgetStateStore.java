package io.tokenpilot.budget.internal;

import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetReservation;
import io.tokenpilot.budget.BudgetReservationRequest;
import io.tokenpilot.budget.BudgetReservationResult;
import io.tokenpilot.budget.BudgetSnapshot;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.budget.IdempotencyKey;
import io.tokenpilot.budget.ReservationId;
import io.tokenpilot.core.domain.Cost;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
public class InMemoryBudgetStateStore implements BudgetStateStore {

    private final ConcurrentMap<BudgetKey, Bucket> store = new ConcurrentHashMap<>();
    private final ConcurrentMap<IdempotencyKey, BudgetReservation> idempotencyIndex =
            new ConcurrentHashMap<>();
    private final Clock clock;
    private final Supplier<ReservationId> reservationIdGenerator;

    public InMemoryBudgetStateStore() {
        this(Clock.systemUTC(), ReservationId::random);
    }

    public InMemoryBudgetStateStore(
            Clock clock,
            Supplier<ReservationId> reservationIdGenerator
    ) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.reservationIdGenerator = Objects.requireNonNull(
                reservationIdGenerator,
                "reservationIdGenerator must not be null"
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
        idempotencyIndex.compute(request.idempotencyKey(), (ignored, existing) -> {
            if (existing != null) {
                BudgetSnapshot existingSnapshot = snapshot(
                        existing.key(),
                        existing.limit()
                );
                if (existing.matches(request)) {
                    result.set(BudgetReservationResult.reused(existing, existingSnapshot));
                } else {
                    result.set(BudgetReservationResult.conflict(
                            existing,
                            existingSnapshot,
                            "동일 idempotency key에 다른 예약 요청이 사용되었습니다"
                    ));
                }
                return existing;
            }

            if (!request.limit().currency().equals(request.safeUpperBoundCost().currency())) {
                result.set(BudgetReservationResult.currencyMismatch(
                        BudgetSnapshot.empty(request.key(), request.limit())
                ));
                return null;
            }

            Bucket bucket = store.computeIfAbsent(
                    request.key(),
                    ignoredKey -> new Bucket(request.limit())
            );
            synchronized (bucket) {
                if (!bucket.limit.currency().equals(request.limit().currency())) {
                    result.set(BudgetReservationResult.currencyMismatch(
                            bucket.snapshot(request.key())
                    ));
                    return null;
                }
                if (!bucket.limit.equals(request.limit())) {
                    result.set(BudgetReservationResult.conflict(
                            null,
                            bucket.snapshot(request.key()),
                            "기존 budget bucket의 limit snapshot이 변경되었습니다"
                    ));
                    return null;
                }

                Cost projectedUsage = bucket.effectiveUsage().add(request.safeUpperBoundCost());
                if (projectedUsage.compareTo(request.limit()) >= 0) {
                    result.set(BudgetReservationResult.blocked(
                            bucket.snapshot(request.key()),
                            "예약 후 사용량이 예산 한도에 도달하거나 초과합니다"
                    ));
                    return null;
                }

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
                    throw new IllegalStateException(
                            "reservationIdGenerator returned a duplicate reservation id"
                    );
                }

                bucket.activeReservedCost = bucket.activeReservedCost.add(
                        request.safeUpperBoundCost()
                );
                bucket.reservationsById.put(reservationId, reservation);
                result.set(BudgetReservationResult.created(
                        reservation,
                        bucket.snapshot(request.key())
                ));
                return reservation;
            }
        });

        return Objects.requireNonNull(result.get(), "reservation result must be set");
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

    private static final class Bucket {
        private final Cost limit;
        private Cost committedCost;
        private Cost activeReservedCost;
        private Cost pendingReconciliationLiability;
        private final Map<ReservationId, BudgetReservation> reservationsById =
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
                    reservationsById.keySet()
            );
        }
    }
}
