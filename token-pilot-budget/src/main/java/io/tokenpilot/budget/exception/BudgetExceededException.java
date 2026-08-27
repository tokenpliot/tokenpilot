package io.tokenpilot.budget.exception;

import io.tokenpilot.budget.BudgetDecision;

import java.util.Objects;

/**
 * Exception thrown when a BLOCK decision is enforced at the provider boundary.
 * <p>
 * {@code BudgetEvaluator} does not throw this exception directly. The boundary
 * immediately before provider invocation throws it while preserving the original
 * decision to stop the call chain.
 */
public class BudgetExceededException extends RuntimeException {

  private final BudgetDecision decision;

  public BudgetExceededException(BudgetDecision decision) {
    super(Objects.requireNonNull(decision, "decision must not be null").reason());
    this.decision = decision;
  }

  public BudgetDecision getDecision() {
    return decision;
  }
}
