package io.tokenpilot.core.domain;

import java.util.Currency;
import java.util.Objects;

/**
 * Result of preflight cost calculation. A sealed hierarchy separates numeric
 * bounds from unavailable results so zero cost is not confused with missing pricing.
 */
public sealed interface PreflightCostResult
        permits PreflightCostResult.Bounded, PreflightCostResult.Unavailable {

    /**
     * Finite cost bound calculated from one immutable pricing snapshot.
     * Internal calculations are not rounded, and this does not imply that context admission passed.
     * The reservation layer must use {@code safeUpperBoundCost}.
     *
     * @param estimatedCost estimated cost for observation and display
     * @param safeUpperBoundCost conservative cost bound used for reservation
     * @param inputEstimatedTokens informational input token count
     * @param inputSafeUpperBoundTokens safe input token bound for conservative admission
     * @param reservedOutputTokens maximum output tokens reserved
     * @param canonicalModelId canonical model identifier
     * @param pricingPolicyId immutable pricing policy identifier
     * @param catalogVersion catalog version used for calculation
     * @param pricingSnapshot exact pricing snapshot passed to calculation, reservation, and settlement
     * @param estimatorDescriptor token estimator identity
     * @param tokenizationBasis validated tokenizer compatibility basis
     */
    record Bounded(
            Cost estimatedCost,
            Cost safeUpperBoundCost,
            long inputEstimatedTokens,
            long inputSafeUpperBoundTokens,
            long reservedOutputTokens,
            String canonicalModelId,
            String pricingPolicyId,
            String catalogVersion,
            PricingSnapshot pricingSnapshot,
            TokenEstimatorDescriptor estimatorDescriptor,
            TokenizationBasis tokenizationBasis
    ) implements PreflightCostResult {

        /**
         * Validates cost/token relationships and reproducibility metadata.
         */
        public Bounded {
            estimatedCost = Objects.requireNonNull(estimatedCost, "estimatedCost must not be null");
            safeUpperBoundCost = Objects.requireNonNull(
                    safeUpperBoundCost,
                    "safeUpperBoundCost must not be null"
            );
            validateTokens(inputEstimatedTokens, inputSafeUpperBoundTokens, reservedOutputTokens);
            canonicalModelId = requireText(canonicalModelId, "canonicalModelId");
            pricingPolicyId = requireText(pricingPolicyId, "pricingPolicyId");
            catalogVersion = requireText(catalogVersion, "catalogVersion");
            pricingSnapshot = Objects.requireNonNull(pricingSnapshot, "pricingSnapshot must not be null");
            if (!pricingSnapshot.modelId().equals(canonicalModelId)
                    || !pricingSnapshot.pricingPolicyId().equals(pricingPolicyId)
                    || !pricingSnapshot.catalogVersion().equals(catalogVersion)) {
                throw new IllegalArgumentException("pricingSnapshot identity must match result metadata");
            }
            estimatorDescriptor = Objects.requireNonNull(
                    estimatorDescriptor,
                    "estimatorDescriptor must not be null"
            );
            tokenizationBasis = Objects.requireNonNull(tokenizationBasis, "tokenizationBasis must not be null");

            if (!estimatedCost.currency().equals(safeUpperBoundCost.currency())) {
                throw new IllegalArgumentException("estimated and safe upper bound currencies must match");
            }
            if (!pricingSnapshot.currency().equals(safeUpperBoundCost.currency())) {
                throw new IllegalArgumentException("pricingSnapshot currency must match cost currency");
            }
            if (safeUpperBoundCost.compareTo(estimatedCost) < 0) {
                throw new IllegalArgumentException("safeUpperBoundCost must be greater than or equal to estimatedCost");
            }
        }

        /**
         * Returns the currency of the calculated costs.
         *
         * @return the currency shared by the estimated and safe upper-bound costs
         */
        public Currency currency() {
            return safeUpperBoundCost.currency();
        }
    }

    /**
     * Result that cannot be treated as a numeric cost at a fail-closed boundary.
     * It does not mean zero cost or an arbitrary default rate. Each control layer
     * must inspect the reason before deciding whether to continue with provider
     * invocation and reservation.
     *
     * @param reason bounded reason a numeric cost could not be produced
     * @param canonicalModelId canonical model identifier
     * @param pricingPolicyId referenced pricing policy identifier
     * @param catalogVersion referenced catalog version
     * @param currency cost currency required by the model
     * @param reservedOutputTokens requested maximum output token count
     * @param estimatorDescriptor token estimator identity
     * @param tokenizationBasis tokenizer compatibility basis of the token result
     */
    record Unavailable(
            PreflightCostUnavailableReason reason,
            String canonicalModelId,
            String pricingPolicyId,
            String catalogVersion,
            Currency currency,
            long reservedOutputTokens,
            TokenEstimatorDescriptor estimatorDescriptor,
            TokenizationBasis tokenizationBasis
    ) implements PreflightCostResult {

        /**
         * Validates the unavailable reason and diagnostic metadata.
         */
        public Unavailable {
            reason = Objects.requireNonNull(reason, "reason must not be null");
            canonicalModelId = requireText(canonicalModelId, "canonicalModelId");
            pricingPolicyId = requireText(pricingPolicyId, "pricingPolicyId");
            catalogVersion = requireText(catalogVersion, "catalogVersion");
            currency = Objects.requireNonNull(currency, "currency must not be null");
            if (reservedOutputTokens < 0) {
                throw new IllegalArgumentException("reservedOutputTokens must be non-negative");
            }
            estimatorDescriptor = Objects.requireNonNull(
                    estimatorDescriptor,
                    "estimatorDescriptor must not be null"
            );
            tokenizationBasis = Objects.requireNonNull(tokenizationBasis, "tokenizationBasis must not be null");
        }
    }

    private static void validateTokens(long estimated, long safeUpperBound, long reservedOutput) {
        if (estimated < 0 || safeUpperBound < 0 || reservedOutput < 0) {
            throw new IllegalArgumentException("token values must be non-negative");
        }
        if (safeUpperBound < estimated) {
            throw new IllegalArgumentException(
                    "inputSafeUpperBoundTokens must be greater than or equal to inputEstimatedTokens"
            );
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
