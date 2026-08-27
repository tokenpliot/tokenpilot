package io.tokenpilot.core.domain;

import java.util.Currency;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable pricing projection passed to preflight cost calculation after the
 * canonical model has been resolved.
 *
 * <p>An alias must first be resolved to a canonical model by ModelRegistry; this
 * context must not be assembled from arbitrary strings. {@code catalogVersion},
 * {@code pricingPolicyId}, currency, and tokenizer basis must be derived
 * together from the same model definition and pricing snapshot.</p>
 *
 * <p>A calculable context must retain the same immutable snapshot resolved once
 * from the registry. Looking up a mutable registry again during calculation,
 * reservation, or settlement could mix prices from different observations. A
 * context with no pricing can use an empty snapshot, which the calculator
 * returns as {@code PRICING_NOT_FOUND}. {@link UpperBoundCapability} must also
 * be the result of a validated pricing policy rather than an arbitrary caller
 * declaration.</p>
 *
 * @param canonicalModelId canonical model identifier
 * @param pricingPolicyId immutable pricing policy identifier
 * @param catalogVersion model and pricing catalog version
 * @param tokenizationBasis tokenizer compatibility basis accepted by the model
 * @param currency cost currency required by the model
 * @param upperBoundCapability result indicating whether the pricing policy can provide a finite cost bound
 * @param pricingSnapshot resolved pricing snapshot used for calculation, reservation, and settlement
 */
public record PreflightPricingContext(
        String canonicalModelId,
        String pricingPolicyId,
        String catalogVersion,
        TokenizationBasis tokenizationBasis,
        Currency currency,
        UpperBoundCapability upperBoundCapability,
        Optional<PricingSnapshot> pricingSnapshot
) {

    /** Validates that identifiers and compatibility metadata are not blank. */
    public PreflightPricingContext {
        canonicalModelId = requireText(canonicalModelId, "canonicalModelId");
        pricingPolicyId = requireText(pricingPolicyId, "pricingPolicyId");
        catalogVersion = requireText(catalogVersion, "catalogVersion");
        tokenizationBasis = Objects.requireNonNull(tokenizationBasis, "tokenizationBasis must not be null");
        currency = Objects.requireNonNull(currency, "currency must not be null");
        upperBoundCapability = Objects.requireNonNull(
                upperBoundCapability,
                "upperBoundCapability must not be null"
        );
        pricingSnapshot = Objects.requireNonNull(pricingSnapshot, "pricingSnapshot must not be null");
    }

    /**
     * Creates a context for which a pricing snapshot has not yet been found.
     * The calculator handles this context as {@code PRICING_NOT_FOUND}, not as a numeric cost.
     *
     * @param canonicalModelId canonical model identifier
     * @param pricingPolicyId pricing policy identifier
     * @param catalogVersion model and pricing catalog version
     * @param tokenizationBasis tokenizer compatibility basis
     * @param currency cost currency
     * @param upperBoundCapability whether the pricing policy can provide a cost bound
     */
    public PreflightPricingContext(
            String canonicalModelId,
            String pricingPolicyId,
            String catalogVersion,
            TokenizationBasis tokenizationBasis,
            Currency currency,
            UpperBoundCapability upperBoundCapability
    ) {
        this(
                canonicalModelId,
                pricingPolicyId,
                catalogVersion,
                tokenizationBasis,
                currency,
                upperBoundCapability,
                Optional.empty()
        );
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** Describes whether the pricing policy can provide a finite conservative bound before a call. */
    public enum UpperBoundCapability {
        /** Every applicable path has a finite maximum rate. */
        FINITE,
        /** At least one applicable path has no finite maximum rate. */
        UNBOUNDED
    }
}
