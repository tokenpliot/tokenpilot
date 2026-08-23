package io.tokenpilot.budget.internal;

import io.tokenpilot.budget.BudgetEvaluator;
import io.tokenpilot.budget.BudgetPolicy;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.budget.ReservationAccounting;
import io.tokenpilot.budget.ReservationAccountingListener;
import io.tokenpilot.budget.ReservationId;
import io.tokenpilot.core.CostCalculator;
import io.tokenpilot.core.internal.LedgerComponents;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 예산 제어 컴포넌트 생성을 위한 팩토리 클래스입니다.
 */
public final class LedgerBudgetComponents {

    private LedgerBudgetComponents() {
    }

    public static BudgetStateStore inMemoryBudgetStateStore() {
        return new InMemoryBudgetStateStore();
    }

    public static BudgetStateStore inMemoryBudgetStateStore(
        List<ReservationAccountingListener> accountingListeners
    ) {
        return new InMemoryBudgetStateStore(
            Clock.systemUTC(),
            ReservationId::random,
            LedgerComponents.defaultCostCalculator(),
            accountingListeners
        );
    }

    public static BudgetStateStore inMemoryBudgetStateStore(
        Clock clock,
        Supplier<ReservationId> reservationIdGenerator
    ) {
        return new InMemoryBudgetStateStore(clock, reservationIdGenerator);
    }

    public static BudgetStateStore inMemoryBudgetStateStore(
        Clock clock,
        Supplier<ReservationId> reservationIdGenerator,
        CostCalculator costCalculator
    ) {
        return new InMemoryBudgetStateStore(
            clock,
            reservationIdGenerator,
            costCalculator
        );
    }

    public static BudgetStateStore inMemoryBudgetStateStore(
        Clock clock,
        Supplier<ReservationId> reservationIdGenerator,
        CostCalculator costCalculator,
        List<ReservationAccountingListener> accountingListeners
    ) {
        return new InMemoryBudgetStateStore(
            clock,
            reservationIdGenerator,
            costCalculator,
            accountingListeners
        );
    }

    /**
     * 예약을 생성한 store와 동일한 객체의 회계 명령 진입점을 반환합니다.
     */
    public static ReservationAccounting reservationAccounting(
        BudgetStateStore stateStore
    ) {
        Objects.requireNonNull(stateStore, "stateStore must not be null");
        if (stateStore instanceof ReservationAccounting accounting) {
            return accounting;
        }
        throw new IllegalArgumentException(
            "stateStore must support reservation accounting"
        );
    }

    public static BudgetEvaluator defaultBudgetEvaluator(
        BudgetStateStore store,
        BudgetPolicy policy,
        Clock clock
    ) {
        return new DefaultBudgetEvaluator(store, policy, clock);
    }
}
