package io.tokenpilot.core.domain;

/**
 * Enumerates the bounded reasons a token estimator cannot provide a result.
 */
public enum TokenCountUnavailableReason {
    /**
     * The configured estimator cannot provide a token count result.
     */
    ESTIMATOR_UNAVAILABLE
}
