package io.tokenpilot.core.domain;

/**
 * Describes the source and derivation method of token usage values.
 */
public enum UsageSource {
    /** Usage directly reported by the provider as inclusive totals. */
    PROVIDER_REPORTED,
    /** Usage normalized by an adapter from multiple provider-reported fields. */
    PROVIDER_DERIVED,
    /** Usage counted by a local tokenizer. */
    LOCAL_TOKENIZER,
    /** Usage approximately estimated by a heuristic. */
    HEURISTIC_ESTIMATE,
    /** Usage information was unavailable. */
    UNAVAILABLE
}
