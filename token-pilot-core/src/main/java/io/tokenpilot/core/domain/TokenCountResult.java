package io.tokenpilot.core.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Immutable snapshot of a token count performed before an LLM call.
 * Represents counted and unavailable results as mutually exclusive states.
 */
public final class TokenCountResult {
    private final ResultState state;
    private final TokenCountScope scope;
    private final TokenEstimatorDescriptor estimatorDescriptor;
    private final TokenizationBasis tokenizationBasis;

    private TokenCountResult(
            ResultState state,
            TokenCountScope scope,
            TokenEstimatorDescriptor estimatorDescriptor,
            TokenizationBasis tokenizationBasis
    ) {
        this.state = state;
        this.scope = scope;
        this.estimatorDescriptor = estimatorDescriptor;
        this.tokenizationBasis = tokenizationBasis;
    }

    /**
     * Creates a counted result containing a token count.
     *
     * @param tokens               informational token count
     * @param safeUpperBoundTokens safe upper bound for conservative admission decisions
     * @param accuracy             counting accuracy
     * @param scope                input scope included in the count
     * @param estimatorDescriptor estimator identity used for counting
     * @param tokenizationBasis   tokenization basis compared with the model encoding
     * @return a validated counted result
     * @throws IllegalArgumentException when token values or the accuracy-specific bound relationship is invalid
     * @throws NullPointerException when accuracy, scope, estimatorDescriptor, or tokenizationBasis is null
     */
    public static TokenCountResult counted(
            long tokens,
            long safeUpperBoundTokens,
            TokenCountAccuracy accuracy,
            TokenCountScope scope,
            TokenEstimatorDescriptor estimatorDescriptor,
            TokenizationBasis tokenizationBasis
    ) {
        validateCounted(
                tokens,
                safeUpperBoundTokens,
                accuracy,
                scope,
                estimatorDescriptor,
                tokenizationBasis
        );

        return new TokenCountResult(
                new Counted(tokens, safeUpperBoundTokens, accuracy),
                scope,
                estimatorDescriptor,
                tokenizationBasis
        );
    }

    private static void validateCounted(
            long tokens,
            long safeUpperBoundTokens,
            TokenCountAccuracy accuracy,
            TokenCountScope scope,
            TokenEstimatorDescriptor estimatorDescriptor,
            TokenizationBasis tokenizationBasis
    ) {
        Objects.requireNonNull(accuracy, "accuracy must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(estimatorDescriptor, "estimatorDescriptor must not be null");
        Objects.requireNonNull(tokenizationBasis, "tokenizationBasis must not be null");

        if (tokens < 0) {
            throw new IllegalArgumentException("tokens must be non-negative");
        }
        if (safeUpperBoundTokens < 0) {
            throw new IllegalArgumentException("safeUpperBoundTokens must be non-negative");
        }
        if (accuracy == TokenCountAccuracy.EXACT && tokens != safeUpperBoundTokens) {
            throw new IllegalArgumentException(
                    "EXACT requires tokens to equal safeUpperBoundTokens"
            );
        }
        if (accuracy == TokenCountAccuracy.HEURISTIC && safeUpperBoundTokens < tokens) {
            throw new IllegalArgumentException(
                    "HEURISTIC requires safeUpperBoundTokens to be greater than or equal to tokens"
            );
        }
    }

    /**
     * Creates an unavailable token count result.
     *
     * @param reason              bounded reason the count could not be provided
     * @param scope               input scope that was requested
     * @param estimatorDescriptor estimator identity used for the attempt
     * @param tokenizationBasis   tokenization basis declared by the estimator
     * @return an unavailable result
     * @throws NullPointerException when an argument is null
     */
    public static TokenCountResult unavailable(
            TokenCountUnavailableReason reason,
            TokenCountScope scope,
            TokenEstimatorDescriptor estimatorDescriptor,
            TokenizationBasis tokenizationBasis
    ) {
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(estimatorDescriptor, "estimatorDescriptor must not be null");
        Objects.requireNonNull(tokenizationBasis, "tokenizationBasis must not be null");

        return new TokenCountResult(
                new Unavailable(reason),
                scope,
                estimatorDescriptor,
                tokenizationBasis
        );
    }

    /**
     * Returns whether this result is a counted state containing a token value.
     *
     * @return {@code true} when this is a counted state
     */
    public boolean isCounted() {
        return state instanceof Counted;
    }

    /**
     * Returns whether the counted result is exact.
     *
     * @return {@code true} when accuracy is EXACT; {@code false} for HEURISTIC or unavailable
     */
    public boolean isExact() {
        if (state instanceof Counted counted) {
            return counted.accuracy() == TokenCountAccuracy.EXACT;
        }
        return false;
    }

    /**
     * Returns whether the token count is unavailable.
     *
     * @return {@code true} when this is an unavailable state
     */
    public boolean isUnavailable() {
        return state instanceof Unavailable;
    }

    /**
     * Returns the informational token count.
     *
     * @return the counted value, or empty for an unavailable state
     */
    public OptionalLong tokens() {
        if (state instanceof Counted counted) {
            return OptionalLong.of(counted.tokens());
        }
        return OptionalLong.empty();
    }

    /**
     * Returns the safe upper bound used for conservative admission decisions.
     *
     * @return the counted safe upper bound, or empty for an unavailable state
     */
    public OptionalLong safeUpperBoundTokens() {
        if (state instanceof Counted counted) {
            return OptionalLong.of(counted.safeUpperBoundTokens());
        }
        return OptionalLong.empty();
    }

    /**
     * Returns the counting accuracy of a counted result.
     *
     * @return the counted accuracy, or empty for an unavailable state
     */
    public Optional<TokenCountAccuracy> accuracy() {
        if (state instanceof Counted counted) {
            return Optional.of(counted.accuracy());
        }
        return Optional.empty();
    }

    /**
     * Returns the reason the token count could not be provided.
     *
     * @return the unavailable reason, or empty for a counted state
     */
    public Optional<TokenCountUnavailableReason> unavailableReason() {
        if (state instanceof Unavailable(TokenCountUnavailableReason reason)) {
            return Optional.of(reason);
        }
        return Optional.empty();
    }

    /**
     * Returns the input scope included in the token count.
     *
     * @return the scope at creation time
     */
    public TokenCountScope scope() {
        return scope;
    }

    /**
     * Returns the estimator identity used for token counting.
     *
     * @return the estimator identity at creation time
     */
    public TokenEstimatorDescriptor estimatorDescriptor() {
        return estimatorDescriptor;
    }

    /**
     * Returns the tokenization basis to compare with the model encoding.
     *
     * @return the tokenization basis at creation time
     */
    public TokenizationBasis tokenizationBasis() {
        return tokenizationBasis;
    }

    private sealed interface ResultState permits Counted, Unavailable {
    }

    private record Counted(
            long tokens,
            long safeUpperBoundTokens,
            TokenCountAccuracy accuracy
    ) implements ResultState {
    }

    private record Unavailable(
            TokenCountUnavailableReason reason
    ) implements ResultState {
    }
}
