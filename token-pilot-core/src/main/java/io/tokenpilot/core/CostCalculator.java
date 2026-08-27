package io.tokenpilot.core;

import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.PricingPlan;
import io.tokenpilot.core.domain.PricingSnapshot;
import io.tokenpilot.core.domain.TokenUsage;

import java.util.Objects;

/**
 * Interface for calculating cost from usage and a pricing policy.
 */
public interface CostCalculator {
    /**
     * Calculates cost from token usage and a pricing policy.
     * @param usage token usage
     * @param plan  pricing policy
     * @return calculated cost
     */
    Cost calculate(TokenUsage usage, PricingPlan plan);

    /**
     * Calculates cost using the pricing snapshot fixed at reservation time.
     */
    default Cost calculate(TokenUsage usage, PricingSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        return calculate(
                usage,
                new PricingPlan(
                        snapshot.modelId(),
                        snapshot.pricingPolicyId(),
                        snapshot.rates(),
                        snapshot.currency()
                )
        );
    }
}
