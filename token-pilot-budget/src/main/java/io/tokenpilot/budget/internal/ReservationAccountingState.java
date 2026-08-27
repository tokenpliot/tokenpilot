package io.tokenpilot.budget.internal;

import io.tokenpilot.budget.BudgetReservation;
import io.tokenpilot.budget.ReservationStateMachine;
import io.tokenpilot.budget.ReservationTransition;
import io.tokenpilot.core.domain.Cost;

import java.util.Objects;
import java.util.Optional;

import static io.tokenpilot.budget.AccountingTransitionStatus.CONFLICT;
import static io.tokenpilot.budget.AccountingTransitionStatus.CURRENCY_MISMATCH;
import static io.tokenpilot.budget.AccountingTransitionStatus.REUSED;
import static io.tokenpilot.budget.ReservationState.RECONCILIATION_REQUIRED;
import static io.tokenpilot.budget.ReservationState.WRITTEN_OFF;

/**
 * Internal state used to evaluate accounting commands applied to one reservation.
 */
final class ReservationAccountingState {

    private final BudgetReservation reservation;
    private final Optional<AppliedCommit> appliedCommit;
    private final Optional<AppliedRelease> appliedRelease;

    private ReservationAccountingState(
            BudgetReservation reservation,
            Optional<AppliedCommit> appliedCommit,
            Optional<AppliedRelease> appliedRelease
    ) {
        this.reservation = Objects.requireNonNull(
                reservation,
                "reservation must not be null"
        );
        this.appliedCommit = Objects.requireNonNull(
                appliedCommit,
                "appliedCommit must not be null"
        );
        this.appliedRelease = Objects.requireNonNull(
                appliedRelease,
                "appliedRelease must not be null"
        );
        if (appliedCommit.isPresent() && appliedRelease.isPresent()) {
            throw new IllegalArgumentException("only one terminal command can be applied");
        }
    }

    static ReservationAccountingState reserved(BudgetReservation reservation) {
        return new ReservationAccountingState(
                reservation,
                Optional.empty(),
                Optional.empty()
        );
    }

    BudgetReservation reservation() {
        return reservation;
    }

    ReservationTransition evaluateCommit(
            Cost actualCost,
            Optional<ActualUsageFingerprint> fingerprint
    ) {
        Objects.requireNonNull(actualCost, "actualCost must not be null");
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        if (hasDifferentCurrency(actualCost)) {
            return ReservationTransition.unchanged(
                    reservation.state(),
                    CURRENCY_MISMATCH
            );
        }
        if (reservation.state() == WRITTEN_OFF) {
            return ReservationTransition.unchanged(WRITTEN_OFF, CONFLICT);
        }
        if (appliedRelease.isPresent()) {
            return ReservationTransition.unchanged(reservation.state(), CONFLICT);
        }
        return appliedCommit
                .map(commit -> commit.evaluate(
                        CommitType.DIRECT,
                        reservation.state(),
                        actualCost,
                        fingerprint
                ))
                .orElseGet(() -> ReservationStateMachine.commit(reservation.state()));
    }

    ReservationTransition evaluateLateActual(
            Cost actualCost,
            Optional<ActualUsageFingerprint> fingerprint
    ) {
        Objects.requireNonNull(actualCost, "actualCost must not be null");
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        if (hasDifferentCurrency(actualCost)) {
            return ReservationTransition.unchanged(
                    reservation.state(),
                    CURRENCY_MISMATCH
            );
        }
        if (reservation.state() == WRITTEN_OFF) {
            return ReservationTransition.unchanged(WRITTEN_OFF, CONFLICT);
        }
        if (appliedRelease.isPresent()) {
            return ReservationTransition.unchanged(reservation.state(), CONFLICT);
        }
        return appliedCommit
                .map(commit -> commit.evaluate(
                        CommitType.LATE_ACTUAL,
                        reservation.state(),
                        actualCost,
                        fingerprint
                ))
                .orElseGet(
                        () -> ReservationStateMachine.reconcileLateActual(
                                reservation.state()
                        )
                );
    }

    ReservationTransition evaluateReleaseBeforeDispatch() {
        if (appliedCommit.isPresent()) {
            return ReservationTransition.unchanged(reservation.state(), CONFLICT);
        }
        return appliedRelease
                .map(release -> release.evaluate(
                        ReleaseType.BEFORE_DISPATCH,
                        reservation.state()
                ))
                .orElseGet(() -> ReservationStateMachine.release(reservation.state()));
    }

    ReservationTransition evaluateConfirmedUnbilledRelease() {
        if (appliedCommit.isPresent()) {
            return ReservationTransition.unchanged(reservation.state(), CONFLICT);
        }
        return appliedRelease
                .map(release -> release.evaluate(
                        ReleaseType.CONFIRMED_UNBILLED,
                        reservation.state()
                ))
                .orElseGet(
                        () -> ReservationStateMachine.releaseConfirmedUnbilled(
                                reservation.state()
                        )
                );
    }

    ReservationTransition evaluateWriteOff() {
        if (reservation.state() == WRITTEN_OFF) {
            return ReservationTransition.unchanged(WRITTEN_OFF, REUSED);
        }
        if (reservation.state().isClosed()) {
            return ReservationTransition.unchanged(
                    reservation.state(),
                    CONFLICT
            );
        }
        return ReservationStateMachine.writeOff(reservation.state());
    }

    ReservationTransition evaluateReconciliationRequired() {
        if (reservation.state() == RECONCILIATION_REQUIRED) {
            return ReservationTransition.unchanged(
                    reservation.state(),
                    REUSED
            );
        }
        return ReservationStateMachine.markReconciliationRequired(
                reservation.state()
        );
    }

    ReservationAccountingState withReservation(BudgetReservation updatedReservation) {
        return new ReservationAccountingState(
                updatedReservation,
                appliedCommit,
                appliedRelease
        );
    }

    ReservationAccountingState releasedBeforeDispatch(
            BudgetReservation updatedReservation
    ) {
        return new ReservationAccountingState(
                updatedReservation,
                Optional.empty(),
                Optional.of(AppliedRelease.beforeDispatch())
        );
    }

    ReservationAccountingState confirmedUnbilledReleased(
            BudgetReservation updatedReservation
    ) {
        return new ReservationAccountingState(
                updatedReservation,
                Optional.empty(),
                Optional.of(AppliedRelease.confirmedUnbilled())
        );
    }

    ReservationAccountingState committed(
            BudgetReservation updatedReservation,
            Cost actualCost
    ) {
        return new ReservationAccountingState(
                updatedReservation,
                Optional.of(AppliedCommit.costOnly(CommitType.DIRECT, actualCost)),
                Optional.empty()
        );
    }

    ReservationAccountingState lateActualCommitted(
            BudgetReservation updatedReservation,
            Cost actualCost
    ) {
        return new ReservationAccountingState(
                updatedReservation,
                Optional.of(AppliedCommit.costOnly(
                        CommitType.LATE_ACTUAL,
                        actualCost
                )),
                Optional.empty()
        );
    }

    Optional<AppliedCommit> reusedCommit(
            CommitType type,
            ActualUsageFingerprint fingerprint
    ) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(fingerprint, "fingerprint must not be null");
        return appliedCommit.filter(commit -> commit.matches(type, fingerprint));
    }

    ReservationAccountingState committed(
            BudgetReservation updatedReservation,
            Cost actualCost,
            boolean overLimit,
            ActualUsageFingerprint fingerprint
    ) {
        return new ReservationAccountingState(
                updatedReservation,
                Optional.of(AppliedCommit.fromCallback(
                        CommitType.DIRECT,
                        actualCost,
                        overLimit,
                        fingerprint
                )),
                Optional.empty()
        );
    }

    ReservationAccountingState lateActualCommitted(
            BudgetReservation updatedReservation,
            Cost actualCost,
            boolean overLimit,
            ActualUsageFingerprint fingerprint
    ) {
        return new ReservationAccountingState(
                updatedReservation,
                Optional.of(AppliedCommit.fromCallback(
                        CommitType.LATE_ACTUAL,
                        actualCost,
                        overLimit,
                        fingerprint
                )),
                Optional.empty()
        );
    }

    private boolean hasDifferentCurrency(Cost actualCost) {
        return !reservation.amount().currency().equals(actualCost.currency());
    }
}
