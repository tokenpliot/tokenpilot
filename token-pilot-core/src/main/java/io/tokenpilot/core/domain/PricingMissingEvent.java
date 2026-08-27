package io.tokenpilot.core.domain;

import java.util.Objects;

/**
 * Framework-independent event carrying an explicit missing-pricing decision.
 *
 * @param policy policy for handling missing pricing
 * @param resolution missing-plan or missing-rate decision
 */
public record PricingMissingEvent(
        MissingPricingPolicy policy,
        PricingResolution resolution
) {

    public PricingMissingEvent {
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(resolution, "resolution must not be null");
        if (!resolution.isMissing()) {
            throw new IllegalArgumentException("resolution must identify missing pricing");
        }
    }
}
