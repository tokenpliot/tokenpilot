package io.tokenpilot.budget.internal;

import io.tokenpilot.budget.BudgetEvaluator;
import io.tokenpilot.budget.BudgetPolicy;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.budget.AtomicBudgetStateStore;
import io.tokenpilot.budget.ReservationAccounting;
import io.tokenpilot.budget.ReservationAccountingListener;
import io.tokenpilot.budget.ReservationAccountingListenerErrorHandler;
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

    /** 예약 admission과 accounting을 한 객체로 노출하는 기본 합성 store입니다. */
    public static AtomicBudgetStateStore inMemoryAtomicBudgetStateStore() {
        return new InMemoryBudgetStateStore();
    }

    /** 예약과 회계를 같은 원자적 상태에 연결하는 합성 store factory입니다. */
    public static AtomicBudgetStateStore inMemoryAtomicBudgetStateStore(
        Supplier<List<ReservationAccountingListener>> accountingListenerSupplier,
        Supplier<List<ReservationAccountingListenerErrorHandler>>
            listenerErrorHandlerSupplier
    ) {
        return new InMemoryBudgetStateStore(
            Clock.systemUTC(),
            ReservationId::random,
            LedgerComponents.defaultCostCalculator(),
            accountingListenerSupplier,
            listenerErrorHandlerSupplier
        );
    }

    /** PR #68 accounting/notification listener 연결과 호환되는 convenience factory입니다. */
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

    /** listener 실패 관측 hook까지 연결하는 convenience factory입니다. */
    public static BudgetStateStore inMemoryBudgetStateStore(
        List<ReservationAccountingListener> accountingListeners,
        List<ReservationAccountingListenerErrorHandler> listenerErrorHandlers
    ) {
        return new InMemoryBudgetStateStore(
            Clock.systemUTC(),
            ReservationId::random,
            LedgerComponents.defaultCostCalculator(),
            accountingListeners,
            listenerErrorHandlers
        );
    }

    /** Spring 등 DI container가 listener를 store 생성 뒤 지연 해석할 수 있는 factory입니다. */
    public static BudgetStateStore inMemoryBudgetStateStore(
        Supplier<List<ReservationAccountingListener>> accountingListenerSupplier,
        Supplier<List<ReservationAccountingListenerErrorHandler>>
            listenerErrorHandlerSupplier
    ) {
        return new InMemoryBudgetStateStore(
            Clock.systemUTC(),
            ReservationId::random,
            LedgerComponents.defaultCostCalculator(),
            accountingListenerSupplier,
            listenerErrorHandlerSupplier
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

    public static BudgetStateStore inMemoryBudgetStateStore(
        Clock clock,
        Supplier<ReservationId> reservationIdGenerator,
        CostCalculator costCalculator,
        List<ReservationAccountingListener> accountingListeners,
        List<ReservationAccountingListenerErrorHandler> listenerErrorHandlers
    ) {
        return new InMemoryBudgetStateStore(
            clock,
            reservationIdGenerator,
            costCalculator,
            accountingListeners,
            listenerErrorHandlers
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
