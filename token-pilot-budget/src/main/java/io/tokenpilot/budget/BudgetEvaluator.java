package io.tokenpilot.budget;

import io.tokenpilot.core.domain.Cost;

import java.util.Map;


/**
 * Interface for determining budget state without side effects.
 * <p>
 * Implementations return a structured {@link BudgetDecision}; they do not block
 * provider invocation or call notification/metric listeners directly. The
 * provider boundary must enforce the returned decision separately.
 */
public interface BudgetEvaluator {

  /**
   * Reads only the currently committed usage.
   *
   * @return query-only {@link BudgetDecision.EvaluationType#STATUS} result. It
   *         cannot authorize provider invocation because it has no candidate cost.
   */
  BudgetDecision evaluate(Map<String, String> tags);

  /**
   * Determines admission state after adding the candidate request's
   * currency-aware safe upper-bound cost.
   * <p>
   * {@code projectedUsage >= limit} produces BLOCK. BLOCK and CURRENCY_MISMATCH
   * are returned as decisions rather than thrown as exceptions.
   *
   * @return {@link BudgetDecision.EvaluationType#ADMISSION} decision result
   */
  BudgetDecision evaluate(
      Map<String, String> tags,
      Cost candidateCost
  );
}
