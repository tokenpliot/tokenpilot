package io.tokenpilot.core;

import io.tokenpilot.core.domain.PricingReconciliationResult;
import io.tokenpilot.core.domain.PricingResolution;
import io.tokenpilot.core.domain.PricingSnapshot;

import java.util.Optional;

/**
 * Policy contract for determining pricing snapshot availability and actual-model consistency.
 */
public interface PricingEvaluator {

    /**
     * Validates that the snapshot contains rates required to process the request.
     *
     * @param snapshot pricing snapshot to validate, or empty when it was not resolved
     * @return resolution of the snapshot and required rates
     */
    PricingResolution validateSnapshotRates(Optional<PricingSnapshot> snapshot);

    /**
     * Determines whether the pre-call snapshot can be applied to the actual response model.
     *
     * @param snapshot pricing snapshot fixed before the call, or empty when it was not fixed
     * @param actualModelId actual model ID returned by the provider
     * @return pricing reconciliation result
     */
    PricingReconciliationResult determineReconciliation(
            Optional<PricingSnapshot> snapshot,
            String actualModelId
    );
}
