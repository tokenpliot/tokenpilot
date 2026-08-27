package io.tokenpilot.core;

import io.tokenpilot.core.domain.PreflightCostResult;
import io.tokenpilot.core.domain.PreflightPricingContext;
import io.tokenpilot.core.domain.TokenCountResult;

/**
 * Core contract for converting a pre-call REQUEST-scope token bound into a cost bound.
 *
 * <p>This contract calculates cost only; it does not determine context-window
 * admission or authorize provider invocation. Callers must first ensure that
 * the request token result passes model context admission and that
 * {@link PreflightPricingContext} retains one immutable pricing snapshot for
 * the calculation. The calculator does not look up another snapshot from a registry.</p>
 *
 * <p>Even when calculation succeeds, only
 * {@link PreflightCostResult.Bounded#safeUpperBoundCost()} may support a reservation.
 * {@code estimatedCost} is for observation and display.</p>
 */
public interface PreflightCostEstimator {

    /**
     * Calculates the pre-call cost bound used for atomic reservation.
     * When the input is not REQUEST scope, the tokenizer basis is incompatible,
     * pricing is missing, or the context has no snapshot, returns a bounded
     * unavailable result instead of a numeric cost.
     *
     * @param pricingContext calculation context containing the canonical model,
     *                       pricing policy, catalog version, and validated pricing conditions
     * @param requestInput REQUEST-scope token result covering the complete request to be sent
     * @param reservedOutputTokens maximum output tokens secured before the call
     * @return calculable cost bound or an unavailable result containing the reason
     * @throws NullPointerException when a required argument is {@code null}
     * @throws IllegalArgumentException when reservedOutputTokens is negative
     */
    PreflightCostResult estimate(
            PreflightPricingContext pricingContext,
            TokenCountResult requestInput,
            long reservedOutputTokens
    );
}
