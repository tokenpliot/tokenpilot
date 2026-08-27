package io.tokenpilot.budget.internal;

import io.tokenpilot.budget.AccountingTransitionStatus;
import io.tokenpilot.budget.ReservationState;
import io.tokenpilot.budget.ReservationTransition;

import java.util.Objects;

import static io.tokenpilot.budget.AccountingTransitionStatus.CONFLICT;
import static io.tokenpilot.budget.AccountingTransitionStatus.REUSED;

/**
 * Stores the type of an applied release and the values used to evaluate repeated calls.
 */
record AppliedRelease(ReleaseType type) {

    AppliedRelease {
        Objects.requireNonNull(type, "type must not be null");
    }

    static AppliedRelease beforeDispatch() {
        return new AppliedRelease(ReleaseType.BEFORE_DISPATCH);
    }

    static AppliedRelease confirmedUnbilled() {
        return new AppliedRelease(ReleaseType.CONFIRMED_UNBILLED);
    }

    ReservationTransition evaluate(
            ReleaseType requestedType,
            ReservationState state
    ) {
        Objects.requireNonNull(requestedType, "requestedType must not be null");
        Objects.requireNonNull(state, "state must not be null");

        AccountingTransitionStatus status = type == requestedType ? REUSED : CONFLICT;
        return ReservationTransition.unchanged(state, status);
    }
}
