package io.tokenpilot.budget;

import io.tokenpilot.core.domain.TokenUsage;
import io.tokenpilot.core.domain.UsageSource;

import java.util.Objects;

/**
 * Command for settling provider-reported actual usage against one reservation.
 */
public record ActualUsageCommand(
        String requestId,
        String attemptId,
        ReservationId reservationId,
        TokenUsage usage,
        String responseModelId
) {

    public ActualUsageCommand {
        requestId = requireText(requestId, "requestId");
        attemptId = requireText(attemptId, "attemptId");
        Objects.requireNonNull(reservationId, "reservationId must not be null");
        Objects.requireNonNull(usage, "usage must not be null");
        if (usage.source() == UsageSource.UNAVAILABLE) {
            throw new IllegalArgumentException(
                    "usage must be available for actual reconciliation"
            );
        }
        if (usage.source() != UsageSource.PROVIDER_REPORTED
                && usage.source() != UsageSource.PROVIDER_DERIVED) {
            throw new IllegalArgumentException(
                    "usage source must be provider-reported or provider-derived"
            );
        }
        responseModelId = requireText(responseModelId, "responseModelId");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
