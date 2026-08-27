package io.tokenpilot.budget;

/** Pre-call token estimate supporting the reserved cost. */
public record ReservationTokenEstimate(
        long inputEstimatedTokens,
        long inputSafeUpperBoundTokens,
        long reservedOutputTokens
) {

    public ReservationTokenEstimate {
        if (inputEstimatedTokens < 0
                || inputSafeUpperBoundTokens < 0
                || reservedOutputTokens < 0) {
            throw new IllegalArgumentException("estimated tokens must be non-negative");
        }
        if (inputSafeUpperBoundTokens < inputEstimatedTokens) {
            throw new IllegalArgumentException(
                    "inputSafeUpperBoundTokens must be greater than or equal to inputEstimatedTokens"
            );
        }
    }
}
