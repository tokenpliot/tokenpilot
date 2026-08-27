package io.tokenpilot.core.domain;

/**
 * Describes the accuracy of a counted token result.
 */
public enum TokenCountAccuracy {
    /**
     * The count is exact, so tokens and safeUpperBoundTokens are equal.
     */
    EXACT,

    /**
     * The count is estimated, so safeUpperBoundTokens is greater than or equal to tokens.
     */
    HEURISTIC
}
