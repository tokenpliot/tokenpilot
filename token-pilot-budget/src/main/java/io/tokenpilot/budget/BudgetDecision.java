package io.tokenpilot.budget;

import io.tokenpilot.core.domain.Cost;

import java.util.Objects;

/**
 * Side-effect-free budget evaluation result.
 *
 * @param key budget bucket identifier fixed at evaluation time
 * @param evaluationType whether candidate cost is included
 * @param state ALLOW / WARN / BLOCK / CURRENCY_MISMATCH state
 * @param threshold budget threshold reached by projected usage
 * @param reason state explanation
 * @param committedUsage currently committed cumulative usage
 * @param projectedUsage projected usage including candidate cost. It equals
 *                       {@code committedUsage} for status queries or currency mismatches.
 * @param limit total budget including its currency
 *
 * <p><strong>Migration note:</strong> {@link EvaluationType#STATUS} results are
 * for current-state queries and are not grounds for authorizing provider calls.
 * Use {@code committedUsage} for committed usage and {@code projectedUsage} for
 * usage including a candidate instead of the former ambiguous {@code currentUsage}.
 */
public record BudgetDecision(
    BudgetKey key,
    EvaluationType evaluationType,
    BudgetState state,
    BudgetThreshold threshold,
    String reason,
    Cost committedUsage,
    Cost projectedUsage,
    Cost limit
) {

  public BudgetDecision {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(evaluationType, "evaluationType must not be null");
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(threshold, "threshold must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
    Objects.requireNonNull(committedUsage, "committedUsage must not be null");
    Objects.requireNonNull(projectedUsage, "projectedUsage must not be null");
    Objects.requireNonNull(limit, "limit must not be null");

    if (!committedUsage.currency().equals(projectedUsage.currency())
        || !committedUsage.currency().equals(limit.currency())) {
      throw new IllegalArgumentException(
          "committedUsage, projectedUsage and limit must use the same currency"
      );
    }
    if (evaluationType == EvaluationType.STATUS
        && committedUsage.compareTo(projectedUsage) != 0) {
      throw new IllegalArgumentException(
          "STATUS decisions must not include candidate cost"
      );
    }
  }

  /**
   * Returns whether this result evaluates provider admission with candidate cost included.
   */
  public boolean isAdmissionDecision() {
    return evaluationType == EvaluationType.ADMISSION;
  }

  public enum EvaluationType {
    /**
     * Query-only result without candidate cost. It cannot authorize provider invocation.
     */
    STATUS,

    /**
     * Admission decision that includes candidate cost.
     */
    ADMISSION
  }
}
