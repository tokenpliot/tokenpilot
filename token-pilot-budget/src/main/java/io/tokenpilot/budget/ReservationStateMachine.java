package io.tokenpilot.budget;

import java.util.Objects;

/**
 * Determines whether an accounting state transition is allowed for a reservation.
 */
public final class ReservationStateMachine {

    private ReservationStateMachine() {
    }

    /** Starts provider invocation for a reserved request. */
    public static ReservationTransition onDispatch(ReservationState currentState) {
        return transitionFrom(
                currentState,
                ReservationState.RESERVED,
                ReservationState.IN_FLIGHT
        );
    }

    /** Releases a reservation before provider invocation. */
    public static ReservationTransition release(ReservationState currentState) {
        return transitionFrom(
                currentState,
                ReservationState.RESERVED,
                ReservationState.RELEASED
        );
    }

    /** Releases an in-flight reservation after the provider confirms no charge. */
    public static ReservationTransition releaseConfirmedUnbilled(
            ReservationState currentState
    ) {
        return transitionFrom(
                currentState,
                ReservationState.IN_FLIGHT,
                ReservationState.RELEASED
        );
    }

    /** Determines the state transition needed to commit the received actual usage. */
    public static ReservationTransition commit(ReservationState currentState) {
        return transitionFrom(
                currentState,
                ReservationState.IN_FLIGHT,
                ReservationState.COMMITTED
        );
    }

    /** Determines whether a reservation without actual usage can enter reconciliation pending. */
    public static ReservationTransition markReconciliationRequired(ReservationState currentState) {
        return transitionFrom(
                currentState,
                ReservationState.IN_FLIGHT,
                ReservationState.RECONCILIATION_REQUIRED
        );
    }

    /** Determines whether late actual usage can be committed while reconciliation is pending. */
    public static ReservationTransition reconcileLateActual(ReservationState currentState) {
        return transitionFrom(
                currentState,
                ReservationState.RECONCILIATION_REQUIRED,
                ReservationState.COMMITTED
        );
    }

    /** Explicitly writes off a reservation pending reconciliation. */
    public static ReservationTransition writeOff(ReservationState currentState) {
        return transitionFrom(
                currentState,
                ReservationState.RECONCILIATION_REQUIRED,
                ReservationState.WRITTEN_OFF
        );
    }

    private static ReservationTransition transitionFrom(
            ReservationState currentState,
            ReservationState requiredState,
            ReservationState resultingState
    ) {
        Objects.requireNonNull(currentState, "currentState must not be null");

        if (currentState == requiredState) {
            return ReservationTransition.applied(currentState, resultingState);
        }

        return ReservationTransition.unchanged(
                currentState,
                AccountingTransitionStatus.NOT_ALLOWED
        );
    }
}
