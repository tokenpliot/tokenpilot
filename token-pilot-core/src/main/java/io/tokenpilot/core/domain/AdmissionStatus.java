package io.tokenpilot.core.domain;

/**
 * Context admission status for input tokens and reserved output tokens.
 */
public enum AdmissionStatus {
    /** The complete request fits within the safe upper bound. */
    FITS,
    /** The declared conservative upper bound exceeds the context window. */
    EXCEEDS,
    /** There is not enough information to safely confirm admission or overflow. */
    INDETERMINATE
}
