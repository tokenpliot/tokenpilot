package io.tokenpilot.core.domain;

/**
 * Enumerates the bounded reasons a conservative preflight cost bound cannot be
 * established as a number.
 */
public enum PreflightCostUnavailableReason {
    /** The token count result is unavailable. */
    COUNT_UNAVAILABLE,
    /** The token result does not cover the complete REQUEST scope. */
    INCOMPLETE_SCOPE,
    /** The estimator and model tokenizer bases are incompatible. */
    INCOMPATIBLE_TOKENIZER,
    /** The referenced pricing policy snapshot was not found. */
    PRICING_NOT_FOUND,
    /** A required base input or output rate is missing. */
    INCOMPLETE_PRICING,
    /** The pricing policy cannot provide a finite bound before the call. */
    UNBOUNDED_PRICING,
    /** The model and pricing snapshot use different currencies. */
    CURRENCY_MISMATCH,
    /** The canonical model, policy, or catalog identifier differs from the snapshot. */
    PRICING_SNAPSHOT_MISMATCH,
    /** Price precision exceeded the calculation range, so a structured cost result could not be created. */
    ARITHMETIC_FAILURE
}
