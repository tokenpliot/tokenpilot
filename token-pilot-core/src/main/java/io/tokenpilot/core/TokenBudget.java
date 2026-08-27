package io.tokenpilot.core;

import io.tokenpilot.core.domain.BudgetResult;
import io.tokenpilot.core.domain.TokenCountResult;

/**
 * Determines context admission for input tokens and reserved output before provider invocation.
 */
public interface TokenBudget {

    /**
     * Fail-closed check that the request can fit within the model context window.
     *
     * @param modelId canonical model ID or exact alias
     * @param input input token count result
     * @param reservedOutputTokens maximum output tokens secured before the call
     * @return result distinguishing FITS, EXCEEDS, and INDETERMINATE
     * @throws IllegalArgumentException when reservedOutputTokens is negative
     */
    BudgetResult check(
            String modelId,
            TokenCountResult input,
            long reservedOutputTokens
    );

    /**
     * Throws an exception to prevent provider invocation when the result is not FITS.
     *
     * @return FITS result
     * @throws IllegalStateException when the admission result is not FITS
     */
    default BudgetResult requireFits(
            String modelId,
            TokenCountResult input,
            long reservedOutputTokens
    ) {
        BudgetResult result = check(modelId, input, reservedOutputTokens);
        if (!result.fits()) {
            throw new IllegalStateException("Token admission rejected: " + result.reason());
        }
        return result;
    }
}
