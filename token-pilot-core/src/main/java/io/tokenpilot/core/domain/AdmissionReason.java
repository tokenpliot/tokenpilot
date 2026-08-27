package io.tokenpilot.core.domain;

/**
 * Enumerates the bounded reasons for a context admission decision.
 */
public enum AdmissionReason {
    /** The input and reserved output fit within the context window. */
    WITHIN_CONTEXT,
    /** The conservative input bound or reserved output exceeds the context window. */
    CONTEXT_EXCEEDED,
    /** The result does not include the complete request, such as a TEXT_ONLY result. */
    INCOMPLETE_SCOPE,
    /** The estimator result is incompatible with the model tokenizer basis. */
    INCOMPATIBLE_TOKENIZER,
    /** The model is not registered. */
    UNKNOWN_MODEL,
    /** The token count result is unavailable. */
    COUNT_UNAVAILABLE
}
