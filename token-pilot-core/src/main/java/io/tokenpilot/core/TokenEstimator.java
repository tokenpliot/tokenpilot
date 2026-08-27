package io.tokenpilot.core;

import io.tokenpilot.core.domain.TokenCountResult;

/**
 * Framework-independent contract for counting input tokens before an LLM call.
 * Results include not only the token count but also the count scope and
 * estimator/tokenization basis.
 */
public interface TokenEstimator {

    /**
     * Counts tokens in the supplied string.
     * An empty string must produce a counted result with zero tokens, not an unavailable result.
     *
     * @param text string to count
     * @return result preserving the count and counting basis
     * @throws NullPointerException when text is null
     */
    TokenCountResult estimate(String text);
}
