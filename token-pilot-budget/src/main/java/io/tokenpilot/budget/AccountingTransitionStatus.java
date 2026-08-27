package io.tokenpilot.budget;

/**
 * Result of an accounting state-change command after reservation creation.
 *
 * <p>Only {@link #APPLIED} means that a new accounting change was applied. All
 * other results leave the existing state and amounts unchanged, allowing callers
 * to decide on retries, conflict handling, or input correction without depending
 * on exception messages or store internals.</p>
 */
public enum AccountingTransitionStatus {
    /** The requested accounting change was newly applied. */
    APPLIED,

    /** The same accounting command and values were already applied, so the existing result was reused. */
    REUSED,

    /** An already applied terminal command or value conflicts with the new request. */
    CONFLICT,

    /** The target reservation was not found. */
    NOT_FOUND,

    /** The command amount uses a different currency from the reservation. */
    CURRENCY_MISMATCH,

    /** The command arguments do not satisfy the contract. */
    INVALID_ARGUMENT,

    /** The requested transition is not allowed from the current reservation state. */
    NOT_ALLOWED;

    /** Returns whether a new accounting change was applied. */
    public boolean isApplied() {
        return this == APPLIED;
    }
}
