package io.tokenpilot.budget;

/**
 * Budget store that handles reservation admission and subsequent accounting
 * transitions in the same atomic state.
 *
 * <p>A dependency-injection container such as Spring can expose this composite
 * contract as one bean to prevent {@link BudgetStateStore} and
 * {@link ReservationAccounting} from being connected to different stores.</p>
 */
public interface AtomicBudgetStateStore
    extends BudgetStateStore, ReservationAccounting {
}
