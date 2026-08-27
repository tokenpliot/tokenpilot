package io.tokenpilot.budget.internal;

import io.tokenpilot.budget.AccountingTransitionStatus;
import io.tokenpilot.budget.ReservationState;
import io.tokenpilot.budget.ReservationTransition;
import io.tokenpilot.core.domain.Cost;

import java.util.Objects;
import java.util.Optional;

import static io.tokenpilot.budget.AccountingTransitionStatus.CONFLICT;
import static io.tokenpilot.budget.AccountingTransitionStatus.REUSED;

/**
 * Stores the type of an applied commit and the values used to evaluate actual
 * usage and repeated calls.
 */
record AppliedCommit(
        CommitType type,
        Cost actualCost,
        boolean overLimit,
        Optional<ActualUsageFingerprint> fingerprint
) {

    AppliedCommit {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(actualCost, "actualCost must not be null");
        fingerprint = Objects.requireNonNull(
                fingerprint,
                "fingerprint must not be null"
        );
    }

    static AppliedCommit costOnly(CommitType type, Cost actualCost) {
        return new AppliedCommit(
                type,
                actualCost,
                false,
                Optional.empty()
        );
    }

    static AppliedCommit fromCallback(
            CommitType type,
            Cost actualCost,
            boolean overLimit,
            ActualUsageFingerprint fingerprint
    ) {
        return new AppliedCommit(
                type,
                actualCost,
                overLimit,
                Optional.of(fingerprint)
        );
    }

    boolean matches(
            CommitType requestedType,
            ActualUsageFingerprint requestedFingerprint
    ) {
        return type == requestedType
                && fingerprint.filter(requestedFingerprint::equals).isPresent();
    }

    ReservationTransition evaluate(
            CommitType requestedType,
            ReservationState state,
            Cost requestedActualCost,
            Optional<ActualUsageFingerprint> requestedFingerprint
    ) {
        Objects.requireNonNull(requestedType, "requestedType must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(requestedActualCost, "requestedActualCost must not be null");
        Objects.requireNonNull(
                requestedFingerprint,
                "requestedFingerprint must not be null"
        );

        AccountingTransitionStatus status = type == requestedType
                && actualCost.equals(requestedActualCost)
                && matchesFingerprintWhenProvided(requestedFingerprint)
                ? REUSED
                : CONFLICT;
        return ReservationTransition.unchanged(state, status);
    }

    private boolean matchesFingerprintWhenProvided(
            Optional<ActualUsageFingerprint> requestedFingerprint
    ) {
        if (fingerprint.isEmpty() || requestedFingerprint.isEmpty()) {
            return true;
        }
        return fingerprint.equals(requestedFingerprint);
    }
}
