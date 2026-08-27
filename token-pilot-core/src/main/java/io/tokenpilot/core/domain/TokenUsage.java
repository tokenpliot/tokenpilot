package io.tokenpilot.core.domain;

import java.util.Map;
import java.util.Objects;

/**
 * Token usage information produced by an AI model call.
 * Manages inclusive input/output totals and usage details.
 *
 * @param inputTokens  total input tokens
 * @param outputTokens total output tokens
 * @param details      detailed token usage
 * @param source       source of the usage values
 * @param metadata     additional metadata, such as model information
 */
public record TokenUsage(
        long inputTokens,
        long outputTokens,
        TokenUsageDetails details,
        UsageSource source,
        Map<String, Object> metadata
) {
    /**
     * Validates containment between token totals and details, then makes an
     * immutable copy of metadata.
     *
     * @throws IllegalArgumentException when token counts are negative or details exceed their totals
     * @throws NullPointerException when details/source is null or metadata contains a null key/value
     */
    public TokenUsage {
        if (inputTokens < 0) {
            throw new IllegalArgumentException(
                    "inputTokens must be non-negative"
            );
        }

        if (outputTokens < 0) {
            throw new IllegalArgumentException(
                    "outputTokens must be non-negative"
            );
        }

        details = Objects.requireNonNull(
                details,
                "details must not be null"
        );
        source = Objects.requireNonNull(
                source,
                "source must not be null"
        );

        long cacheRead = countOrZero(details.cacheReadInputTokens());
        long cacheCreation = countOrZero(details.cacheCreationInputTokens());
        if (cacheRead > inputTokens
                || cacheCreation > inputTokens - cacheRead) {
            throw new IllegalArgumentException(
                    "Input details must not exceed inputTokens"
            );
        }

        long reasoning = countOrZero(details.reasoningOutputTokens());
        if (reasoning > outputTokens) {
            throw new IllegalArgumentException(
                    "reasoningOutputTokens must not exceed outputTokens"
            );
        }

        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * Creates {@link TokenUsage} with basic input and output token totals.
     *
     * @param prompt total input tokens
     * @param completion total output tokens
     * @return usage with no details or metadata
     */
    public static TokenUsage from(long prompt, long completion) {
        return new TokenUsage(
                prompt,
                completion,
                TokenUsageDetails.unreported(),
                UsageSource.PROVIDER_REPORTED,
                Map.of()
        );
    }

    /**
     * Creates {@link TokenUsage} with input, output, and reasoning tokens.
     *
     * @param prompt total input tokens
     * @param completion total output tokens
     * @param reasoning reasoning tokens included in total output
     * @return usage containing reasoning details
     */
    public static TokenUsage from(long prompt, long completion, long reasoning) {
        return new TokenUsage(
                prompt,
                completion,
                new TokenUsageDetails(null, null, reasoning),
                UsageSource.PROVIDER_REPORTED,
                Map.of()
        );
    }

    /**
     * Creates a state in which usage information was unavailable in the provider response.
     *
     * @param metadata response metadata to preserve
     * @return zero-token usage with source {@link UsageSource#UNAVAILABLE}
     */
    public static TokenUsage unavailable(Map<String, Object> metadata) {
        return new TokenUsage(
                0,
                0,
                TokenUsageDetails.unreported(),
                UsageSource.UNAVAILABLE,
                metadata
        );
    }

    /**
     * Returns the total number of input tokens across all input categories.
     *
     * @return total input tokens
     */
    public long promptTokens() {
        return inputTokens;
    }

    /**
     * Returns the total number of output tokens, including reasoning tokens.
     *
     * @return total output tokens
     */
    public long completionTokens() {
        return outputTokens;
    }

    /**
     * Returns the total number of used tokens.
     *
     * @return the sum of total input and output tokens
     * @throws ArithmeticException when the sum exceeds the {@code long} range
     */
    public long totalTokens() {
        return Math.addExact(inputTokens, outputTokens);
    }

    /**
     * Returns the total or detailed usage for a token type.
     * Unreported details are returned as {@code 0} in this compatibility
     * projection; use the nullable fields in {@link #details()} to distinguish
     * unreported values.
     *
     * @param type token type to retrieve
     * @return usage for the requested token type
     */
    public long getCount(TokenType type) {
        return switch (type) {
            case PROMPT -> inputTokens;
            case COMPLETION -> outputTokens;
            case REASONING -> countOrZero(details.reasoningOutputTokens());
            case CACHE_READ_PROMPT -> countOrZero(details.cacheReadInputTokens());
            case CACHE_CREATION_PROMPT -> countOrZero(details.cacheCreationInputTokens());
        };
    }

    private static long countOrZero(Long count) {
        return count == null ? 0L : count;
    }
}
