package io.tokenpilot.core;

import io.tokenpilot.core.domain.PricingPlan;
import java.util.Collection;

/**
 * Provider interface for AI model pricing policies.
 * Pricing data can be loaded from local files (YAML), databases, or remote APIs.
 */
public interface PricingProvider {
    /**
     * Returns all available pricing policies.
     * @return collection of pricing policies
     */
    Collection<PricingPlan> getAllPlans();
}
