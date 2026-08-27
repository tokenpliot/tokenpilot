package io.tokenpilot.budget.internal;

import io.tokenpilot.budget.ActualUsageCommand;
import io.tokenpilot.budget.ReservationActualTokens;
import io.tokenpilot.budget.ReservationId;
import io.tokenpilot.core.domain.PricingSnapshot;

import java.util.Objects;

/** Identifies duplicate actual callbacks without sensitive or unbounded metadata. */
record ActualUsageFingerprint(
        String requestId,
        String attemptId,
        ReservationId reservationId,
        ReservationActualTokens actualTokens,
        String responseModelId,
        PricingSnapshot pricingSnapshot
) {

    ActualUsageFingerprint {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        Objects.requireNonNull(actualTokens, "actualTokens must not be null");
        Objects.requireNonNull(responseModelId, "responseModelId must not be null");
        Objects.requireNonNull(pricingSnapshot, "pricingSnapshot must not be null");
    }

    static ActualUsageFingerprint from(
            ActualUsageCommand command,
            ReservationActualTokens actualTokens,
            PricingSnapshot pricingSnapshot
    ) {
        return new ActualUsageFingerprint(
                command.requestId(),
                command.attemptId(),
                command.reservationId(),
                actualTokens,
                command.responseModelId(),
                pricingSnapshot
        );
    }

    boolean sameCallback(ActualUsageFingerprint other) {
        Objects.requireNonNull(other, "other must not be null");
        return requestId.equals(other.requestId)
                && attemptId.equals(other.attemptId)
                && reservationId.equals(other.reservationId)
                && actualTokens.equals(other.actualTokens)
                && responseModelId.equals(other.responseModelId);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ActualUsageFingerprint that)) {
            return false;
        }
        return requestId.equals(that.requestId)
                && attemptId.equals(that.attemptId)
                && reservationId.equals(that.reservationId)
                && actualTokens.equals(that.actualTokens)
                && responseModelId.equals(that.responseModelId)
                && PricingSnapshot.haveSameBillingTerms(
                        pricingSnapshot,
                        that.pricingSnapshot
                );
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                requestId,
                attemptId,
                reservationId,
                actualTokens,
                responseModelId,
                pricingSnapshot.billingTermsHashCode()
        );
    }
}
