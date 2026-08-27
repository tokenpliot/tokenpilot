package io.tokenpilot.core.domain;

/**
 * Optional usage details included in normalized inclusive input/output totals.
 * {@code null} means the provider did not report a value, while {@code 0}
 * means it reported the value but no tokens were used.
 *
 * @param cacheReadInputTokens     cache-read tokens included in total input, or {@code null} when unreported
 * @param cacheCreationInputTokens cache-creation tokens included in total input, or {@code null} when unreported
 * @param reasoningOutputTokens    reasoning tokens included in total output, or {@code null} when unreported
 */
public record TokenUsageDetails(
        Long cacheReadInputTokens,
        Long cacheCreationInputTokens,
        Long reasoningOutputTokens
) {
    /**
     * Validates that every detail token count is non-negative.
     *
     * @throws IllegalArgumentException when a detail token count is negative
     */
    public TokenUsageDetails {
        isEmptyToken(cacheReadInputTokens);
        isEmptyToken(cacheCreationInputTokens);
        isEmptyToken(reasoningOutputTokens);
    }

    /**
     * Creates a state in which the provider reported no usage details.
     *
     * @return an object with all details marked as unreported
     */
    public static TokenUsageDetails unreported() {
        return new TokenUsageDetails(null, null, null);
    }

    private void isEmptyToken(Long token){
        if(token != null && token < 0){
            throw new IllegalArgumentException("tokens must be non-negative");
        }
    }
}
