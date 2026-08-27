package io.tokenpilot.budget;

import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.PricingSnapshot;

import java.util.Objects;

/**
 * Single entry point for changing a reservation's accounting state and amounts.
 *
 * <table>
 *   <caption>Allowed reservation accounting transitions and amount movements</caption>
 *   <thead>
 *     <tr>
 *       <th>Command</th>
 *       <th>Allowed state</th>
 *       <th>Resulting state</th>
 *       <th>Amount movement</th>
 *     </tr>
 *   </thead>
 *   <tbody>
 *     <tr>
 *       <td>{@link #markInFlight(ReservationId)}</td>
 *       <td>{@link ReservationState#RESERVED}</td>
 *       <td>{@link ReservationState#IN_FLIGHT}</td>
 *       <td>None</td>
 *     </tr>
 *     <tr>
 *       <td>{@code release(CANCELLED_BEFORE_DISPATCH)}</td>
 *       <td>{@link ReservationState#RESERVED}</td>
 *       <td>{@link ReservationState#RELEASED}</td>
 *       <td>{@code activeReservedCost -= estimate}</td>
 *     </tr>
 *     <tr>
 *       <td>{@code release(PROVIDER_CONFIRMED_UNBILLED)}</td>
 *       <td>{@link ReservationState#IN_FLIGHT}</td>
 *       <td>{@link ReservationState#RELEASED}</td>
 *       <td>{@code activeReservedCost -= estimate}</td>
 *     </tr>
 *     <tr>
 *       <td>{@link #commit(ActualUsageCommand)}</td>
 *       <td>{@link ReservationState#IN_FLIGHT}</td>
 *       <td>{@link ReservationState#COMMITTED}</td>
 *       <td>{@code activeReservedCost -= estimate; committedCost += actual}</td>
 *     </tr>
 *     <tr>
 *       <td>{@link #markReconciliationRequired(ReservationId, ReservationAccountingReason)}</td>
 *       <td>{@link ReservationState#IN_FLIGHT}</td>
 *       <td>{@link ReservationState#RECONCILIATION_REQUIRED}</td>
 *       <td>{@code activeReservedCost -= estimate; pendingReconciliationLiability += estimate}</td>
 *     </tr>
 *     <tr>
 *       <td>{@link #reconcileLateActual(ActualUsageCommand)}</td>
 *       <td>{@link ReservationState#RECONCILIATION_REQUIRED}</td>
 *       <td>{@link ReservationState#COMMITTED}</td>
 *       <td>{@code pendingReconciliationLiability -= estimate; committedCost += actual}</td>
 *     </tr>
 *     <tr>
 *       <td>{@link #writeOff(ReservationId, ReservationAccountingReason)}</td>
 *       <td>{@link ReservationState#RECONCILIATION_REQUIRED}</td>
 *       <td>{@link ReservationState#WRITTEN_OFF}</td>
 *       <td>{@code pendingReconciliationLiability -= estimate}</td>
 *     </tr>
 *   </tbody>
 * </table>
 *
 * <p>A transition newly applied from the table returns
 * {@link AccountingTransitionStatus#APPLIED}. Repeating the same terminal
 * command and values, or a reconciliation-pending command, preserves state and
 * amounts and returns {@link AccountingTransitionStatus#REUSED}. Different
 * actual usage or a conflicting terminal command returns
 * {@link AccountingTransitionStatus#CONFLICT}. If no terminal command has been
 * applied but the current state is not allowed by the table,
 * {@link AccountingTransitionStatus#NOT_ALLOWED} is returned.</p>
 *
 * <p>Missing reservations and reasons not allowed for a command are rejected
 * with {@link IllegalArgumentException} before state changes. Every state and
 * amount change is applied together inside the critical section for the same
 * budget bucket.</p>
 */
public interface ReservationAccounting {

    /** Records the start of provider invocation using the reservation. */
    ReservationTransition markInFlight(ReservationId reservationId);

    /** Releases an unused reservation before provider invocation. */
    default ReservationTransition releaseBeforeDispatch(ReservationId reservationId) {
        return release(
                reservationId,
                ReservationAccountingReason.CANCELLED_BEFORE_DISPATCH
        );
    }

    /** Releases an in-flight reservation after the provider confirms no charge. */
    default ReservationTransition releaseConfirmedUnbilled(ReservationId reservationId) {
        return release(
                reservationId,
                ReservationAccountingReason.PROVIDER_CONFIRMED_UNBILLED
        );
    }

    ReservationTransition release(
            ReservationId reservationId,
            ReservationAccountingReason reason
    );

    /**
     * Commits a compatible reservation without a pricing snapshot or token
     * estimate using actual cost calculated by the caller.
     *
     * <p>This compatibility path cannot create the token/model correlation and
     * accounting event produced by {@link #commit(ActualUsageCommand)}, so new
     * reservations must use the usage-based API.</p>
     */
    ReservationTransition commitCost(ReservationId reservationId, Cost actualCost);

    /**
     * Calculates provider actual usage at the reservation-time price and commits it.
     * Rejects a different response model so a different model's price is not
     * committed under the request price.
     */
    ReservationReconciliation commit(ActualUsageCommand command);

    /** Moves a reservation without actual usage into reconciliation pending. */
    default ReservationTransition markReconciliationRequired(
            ReservationId reservationId
    ) {
        return markReconciliationRequired(
                reservationId,
                ReservationAccountingReason.ACTUAL_USAGE_UNAVAILABLE
        );
    }

    ReservationTransition markReconciliationRequired(
            ReservationId reservationId,
            ReservationAccountingReason reason
    );

    /**
     * Moves a reservation into pricing reconciliation pending while preserving
     * the response model and actual usage. Existing implementations may delegate
     * to compatibility behavior that does not retain command metadata.
     */
    default ReservationTransition markReconciliationRequired(
            ActualUsageCommand command,
            ReservationAccountingReason reason
    ) {
        Objects.requireNonNull(command, "command must not be null");
        return markReconciliationRequired(command.reservationId(), reason);
    }

    /**
     * Calculates and commits late-arriving provider actual usage at the
     * reservation-time price. The response model must match the model in the
     * reservation pricing snapshot.
     */
    ReservationReconciliation reconcileLateActual(ActualUsageCommand command);

    /**
     * Applies an explicit immutable pricing snapshot for the response model to
     * pending actual usage. The default implementation fails closed for
     * compatibility with implementations that support only the existing
     * reservation snapshot.
     */
    default ReservationReconciliation reconcileLateActual(
            ActualUsageCommand command,
            PricingSnapshot actualPricingSnapshot
    ) {
        Objects.requireNonNull(command, "command must not be null");
        Objects.requireNonNull(
                actualPricingSnapshot,
                "actualPricingSnapshot must not be null"
        );
        throw new UnsupportedOperationException(
                "late reconciliation with an alternate pricing snapshot is unsupported"
        );
    }

    /**
     * Commits a pending compatible reservation without a pricing snapshot or
     * token estimate using actual cost calculated by the caller.
     *
     * <p>New reservations must use {@link #reconcileLateActual(ActualUsageCommand)}.</p>
     */
    ReservationTransition reconcileLateActualCost(
            ReservationId reservationId,
            Cost actualCost
    );

    /** Explicitly writes off a pending reservation that cannot be reconciled later. */
    default ReservationTransition writeOff(ReservationId reservationId) {
        return writeOff(
                reservationId,
                ReservationAccountingReason.MANUAL_WRITE_OFF
        );
    }

    ReservationTransition writeOff(
            ReservationId reservationId,
            ReservationAccountingReason reason
    );
}
