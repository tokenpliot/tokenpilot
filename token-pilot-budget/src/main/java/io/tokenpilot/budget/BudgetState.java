package io.tokenpilot.budget;

/**
 * Represents the result state of a budget evaluation.
 * <p>
 * ALLOW : call allowed
 * WARN  : budget warning
 * BLOCK : call blocked
 * CURRENCY_MISMATCH : budget and cost currencies differ
 */
public enum BudgetState {
  ALLOW,
  WARN,
  BLOCK,
  CURRENCY_MISMATCH
}
