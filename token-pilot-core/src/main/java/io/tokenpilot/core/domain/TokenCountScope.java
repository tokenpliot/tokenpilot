package io.tokenpilot.core.domain;

/**
 * Describes the input scope included in a token count.
 * An existing result cannot be relabeled with a different scope; a new result
 * must be created when a broader scope has been counted.
 */
public enum TokenCountScope {
    /**
     * Counts only the UTF-8 content of the supplied strings.
     * It excludes roles, tool schemas, media metadata, structured-output schemas,
     * and provider framing, so it cannot prove that the complete request fits in
     * the context window.
     */
    TEXT_ONLY,

    /**
     * Includes all tokenizable content and required framing/headroom for the
     * request that will actually be sent.
     */
    REQUEST
}
