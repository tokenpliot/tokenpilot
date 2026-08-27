package io.tokenpilot.core.internal;

import io.tokenpilot.core.PricingRegistry;
import io.tokenpilot.core.PricingProvider;
import io.tokenpilot.core.domain.PricingPlan;
import io.tokenpilot.core.domain.PricingResolution;
import io.tokenpilot.core.domain.PricingSnapshot;
import io.tokenpilot.core.domain.TokenType;

import java.time.Instant;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;


/**
 * In-memory pricing policy repository implementation.
 */
class InMemoryPricingRegistry implements PricingRegistry {
    private final Map<PricingPlanKey, PricingPlan> plans = new ConcurrentHashMap<>();

    public InMemoryPricingRegistry() {
    }

    public InMemoryPricingRegistry(List<PricingProvider> providers) {
        if (providers != null) {
            providers.stream()
                    .map(PricingProvider::getAllPlans)
                    .flatMap(Collection::stream)
                    .forEach(this::registerPlan);
        }
    }

    @Override
    public Optional<PricingPlan> getPlan(String modelId) {
        return getPlan(modelId, PricingPlan.DEFAULT_PRICING_POLICY_ID);
    }

    @Override
    public Optional<PricingPlan> getPlan(String modelId, String pricingPolicyId) {
        return Optional.ofNullable(plans.get(new PricingPlanKey(modelId, pricingPolicyId)));
    }

    @Override
    public Optional<PricingSnapshot> resolveSnapshot(String modelId, String pricingPolicyId) {
        return getPlan(modelId, pricingPolicyId)
                .map(plan -> PricingSnapshot.from(
                        plan,
                        PricingSnapshot.DEFAULT_CATALOG_VERSION,
                        Instant.now()
                ));
    }

    @Override
    public PricingResolution resolveRate(String modelId, TokenType tokenType) {
        return getPlan(modelId)
                .map(plan -> plan.resolveRate(tokenType))
                .orElse(PricingResolution.MISSING_PLAN);
    }

    @Override
    public PricingResolution resolveRate(String modelId, TokenType tokenType, Currency expectedCurrency) {
        Objects.requireNonNull(expectedCurrency, "expectedCurrency must not be null");

        return getPlan(modelId)
                .map(plan -> {
                    if (!plan.currency().equals(expectedCurrency)) {
                        return PricingResolution.CURRENCY_MISMATCH;
                    }

                    return plan.resolveRate(tokenType);
                })
                .orElse(PricingResolution.MISSING_PLAN);
    }

    @Override
    public void registerPlan(PricingPlan plan) {
        plans.put(new PricingPlanKey(plan.modelId(), plan.pricingPolicyId()), plan);
    }

    private record PricingPlanKey(String modelId, String pricingPolicyId) {
    }
}
