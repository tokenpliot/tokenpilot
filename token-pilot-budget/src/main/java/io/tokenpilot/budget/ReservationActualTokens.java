package io.tokenpilot.budget;

import io.tokenpilot.core.domain.TokenUsage;
import io.tokenpilot.core.domain.TokenUsageDetails;
import io.tokenpilot.core.domain.UsageSource;

import java.util.Map;
import java.util.Objects;

/** Accounting token result derived from provider actual usage without unbounded metadata. */
public record ReservationActualTokens(
        long inputTokens,
        long outputTokens,
        TokenUsageDetails details,
        UsageSource source
) {

    public ReservationActualTokens {
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("actual tokens must be non-negative");
        }
        Objects.requireNonNull(details, "details must not be null");
        Objects.requireNonNull(source, "source must not be null");
        if (source == UsageSource.UNAVAILABLE) {
            throw new IllegalArgumentException(
                    "actual token source must be available"
            );
        }
        new TokenUsage(inputTokens, outputTokens, details, source, Map.of());
    }

    public static ReservationActualTokens from(TokenUsage usage) {
        Objects.requireNonNull(usage, "usage must not be null");
        return new ReservationActualTokens(
                usage.inputTokens(),
                usage.outputTokens(),
                usage.details(),
                usage.source()
        );
    }

    public long totalTokens() {
        return Math.addExact(inputTokens, outputTokens);
    }
}
