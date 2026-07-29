package io.tokenpilot.budget.exception;

import io.tokenpilot.budget.BudgetDecision;

import java.util.Objects;

/**
 * Provider 경계에서 BLOCK decision을 집행할 때 던지는 예외입니다.
 * <p>
 * {@code BudgetEvaluator}는 이 예외를 직접 던지지 않습니다. Provider invocation 직전의
 * 경계가 원래 decision을 보존한 이 예외를 던져 호출 체인을 중단합니다.
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
