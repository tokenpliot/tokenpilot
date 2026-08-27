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
 * Factory for creating budget-control components.
 */
public final class LedgerBudgetComponents {

    private LedgerBudgetComponents() {
    }

    public static BudgetStateStore inMemoryBudgetStateStore() {
        return new InMemoryBudgetStateStore();
    }

    /** Default composite store exposing reservation admission and accounting as one object. */
    public static AtomicBudgetStateStore inMemoryAtomicBudgetStateStore() {
        return new InMemoryBudgetStateStore();
    }

    /** Composite store factory connecting reservation and accounting to the same atomic state. */
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

    /** Convenience factory compatible with the PR #68 accounting/notification listener wiring. */
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

    /** Convenience factory that also connects a listener-failure observation hook. */
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

    /** Factory allowing a DI container such as Spring to resolve listeners after store creation. */
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
     * Returns the accounting command entry point on the same object that created the reservation.
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
