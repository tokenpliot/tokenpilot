package io.tokenpilot.budget;

/**
 * Current accounting state of a budget reservation.
 */
public enum ReservationState {
    /** The safe upper-bound amount is reserved, but provider invocation has not started. */
    RESERVED,

    /** Provider invocation is in progress using the reservation. */
    IN_FLIGHT,

    /** Actual usage is unavailable and later reconciliation is required. */
    RECONCILIATION_REQUIRED,

    /** Terminal state with actual cost committed. */
    COMMITTED,

    /** Terminal state with unused reserved cost released. */
    RELEASED,

    /** Terminal state written off by policy because actual usage could not be confirmed. */
    WRITTEN_OFF;

    /**
     * Returns whether this is a terminal state that allows no further normal accounting transitions.
     *
     * <p>{@link #RECONCILIATION_REQUIRED} is not terminal because it is waiting for later reconciliation.</p>
     */
    public boolean isClosed() {
        return this == COMMITTED || this == RELEASED || this == WRITTEN_OFF;
    }
}
