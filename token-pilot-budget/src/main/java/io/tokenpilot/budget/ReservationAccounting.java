package io.tokenpilot.budget;

import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.PricingSnapshot;

import java.util.Objects;

/**
 * 예약의 회계 상태와 금액을 변경하는 단일 진입점입니다.
 *
 * <table>
 *   <caption>예약 회계 명령의 허용 전이와 금액 이동</caption>
 *   <thead>
 *     <tr>
 *       <th>명령</th>
 *       <th>허용 상태</th>
 *       <th>결과 상태</th>
 *       <th>금액 이동</th>
 *     </tr>
 *   </thead>
 *   <tbody>
 *     <tr>
 *       <td>{@link #markInFlight(ReservationId)}</td>
 *       <td>{@link ReservationState#RESERVED}</td>
 *       <td>{@link ReservationState#IN_FLIGHT}</td>
 *       <td>없음</td>
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
 * <p>표의 전이가 새로 적용되면 {@link AccountingTransitionStatus#APPLIED}입니다.
 * 동일한 종료 명령과 값 또는 정산 대기 명령의 재호출은 상태와 금액을 유지하고
 * {@link AccountingTransitionStatus#REUSED}, 다른 actual 또는 상충하는 종료 명령은
 * {@link AccountingTransitionStatus#CONFLICT}입니다. 아직 종료 명령이 적용되지 않았지만
 * 현재 상태가 표의 허용 상태가 아니면 {@link AccountingTransitionStatus#NOT_ALLOWED}입니다.</p>
 *
 * <p>존재하지 않는 예약과 명령에 허용되지 않은 reason은 상태를 변경하기 전에
 * {@link IllegalArgumentException}으로 거부합니다. 모든 상태와 금액 변경은 같은 budget
 * bucket의 임계 구역 안에서 함께 적용됩니다.</p>
 */
public interface ReservationAccounting {

    /** 예약을 사용한 provider 호출 시작을 기록합니다. */
    ReservationTransition markInFlight(ReservationId reservationId);

    /** provider 호출 전에 사용하지 않은 예약을 해제합니다. */
    default ReservationTransition releaseBeforeDispatch(ReservationId reservationId) {
        return release(
                reservationId,
                ReservationAccountingReason.CANCELLED_BEFORE_DISPATCH
        );
    }

    /** provider가 미과금을 확인한 진행 중 예약을 해제합니다. */
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
     * pricing snapshot과 token estimate가 없던 호환 예약을 caller가 계산한 actual 비용으로 확정합니다.
     *
     * <p>이 호환 경로는 token/model correlation을 포함한
     * {@link #commit(ActualUsageCommand)} 결과와 회계 이벤트를 만들 수 없으므로,
     * 신규 예약에는 usage 기반 API를 사용해야 합니다.</p>
     */
    ReservationTransition commitCost(ReservationId reservationId, Cost actualCost);

    /**
     * provider actual usage를 예약 시점 가격으로 계산하여 확정합니다.
     * 응답 모델이 예약 pricing snapshot과 다르면 다른 모델 가격을 request 가격으로
     * 확정하지 않도록 거부합니다.
     */
    ReservationReconciliation commit(ActualUsageCommand command);

    /** actual을 확보하지 못한 예약을 정산 대기로 전환합니다. */
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
     * response model과 actual usage를 보존하면서 pricing reconciliation 대기로 이동합니다.
     * 기존 구현은 command metadata를 보존하지 않는 호환 동작으로 위임할 수 있습니다.
     */
    default ReservationTransition markReconciliationRequired(
            ActualUsageCommand command,
            ReservationAccountingReason reason
    ) {
        Objects.requireNonNull(command, "command must not be null");
        return markReconciliationRequired(command.reservationId(), reason);
    }

    /**
     * 늦게 도착한 provider actual usage를 예약 시점 가격으로 계산하여 확정합니다.
     * 응답 모델은 예약 pricing snapshot의 모델과 같아야 합니다.
     */
    ReservationReconciliation reconcileLateActual(ActualUsageCommand command);

    /**
     * pending actual에 response model의 명시적 immutable pricing snapshot을 적용합니다.
     * 기본 구현은 기존 예약 snapshot만 지원하는 구현과의 호환을 위해 fail-closed합니다.
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
     * pricing snapshot과 token estimate가 없던 pending 호환 예약을 caller가 계산한 actual 비용으로 확정합니다.
     *
     * <p>신규 예약에는 {@link #reconcileLateActual(ActualUsageCommand)}를 사용해야 합니다.</p>
     */
    ReservationTransition reconcileLateActualCost(
            ReservationId reservationId,
            Cost actualCost
    );

    /** 후속 정산할 수 없는 pending 예약을 명시적으로 상각합니다. */
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
