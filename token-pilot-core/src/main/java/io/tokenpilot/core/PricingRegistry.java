package io.tokenpilot.core;

import io.tokenpilot.core.domain.PricingPlan;
import io.tokenpilot.core.domain.PricingResolution;
import io.tokenpilot.core.domain.PricingSnapshot;
import io.tokenpilot.core.domain.ModelDefinition;
import io.tokenpilot.core.domain.TokenType;

import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;

/**
 * Repository interface for managing pricing policies by AI model.
 */
public interface PricingRegistry {
    /**
     * Looks up a pricing policy registered for a model identifier.
     * @param modelId model identifier
     * @return pricing policy, or empty when none exists
     */
    Optional<PricingPlan> getPlan(String modelId);

    /**
     * Looks up a pricing policy registered for a model identifier and pricing policy ID.
     * @param modelId model identifier
     * @param pricingPolicyId pricing policy identifier
     * @return pricing policy, or empty when none exists
     */
    Optional<PricingPlan> getPlan(String modelId, String pricingPolicyId);

    /**
     * Resolves a request-scoped pricing snapshot by model identifier and pricing policy ID.
     * @param modelId model identifier
     * @param pricingPolicyId pricing policy identifier
     * @return pricing snapshot, or empty when none exists
     */
    Optional<PricingSnapshot> resolveSnapshot(String modelId, String pricingPolicyId);

    /**
     * Resolves a pricing snapshot using the catalog version associated with a
     * canonical model definition. An alias is used for pricing lookup only after
     * it has been converted to a canonical ID.
     *
     * @param modelDefinition canonical model metadata
     * @return pricing snapshot associated with the model definition
     */
    default Optional<PricingSnapshot> resolveSnapshot(ModelDefinition modelDefinition) {
        Objects.requireNonNull(modelDefinition, "modelDefinition must not be null");
        return getPlan(modelDefinition.canonicalModelId(), modelDefinition.pricingPolicyId())
                .filter(plan -> plan.modelId().equals(modelDefinition.canonicalModelId()))
                .filter(plan -> plan.pricingPolicyId().equals(modelDefinition.pricingPolicyId()))
                .filter(plan -> plan.currency().equals(modelDefinition.pricingCurrency()))
                .map(plan -> PricingSnapshot.from(
                        plan,
                        modelDefinition.catalogVersion(),
                        Instant.now()
                ));
    }

    /**
     * Routes alias pricing lookup through the canonical model registry so the
     * lookup is fixed to the canonical policy.
     *
     * @param modelRegistry canonical model lookup
     * @param modelIdOrAlias canonical ID or exact alias
     * @return pricing snapshot matching the canonical model definition
     */
    default Optional<PricingSnapshot> resolveSnapshot(
            ModelRegistry modelRegistry,
            String modelIdOrAlias
    ) {
        Objects.requireNonNull(modelRegistry, "modelRegistry must not be null");
        Objects.requireNonNull(modelIdOrAlias, "modelIdOrAlias must not be null");
        return modelRegistry.find(modelIdOrAlias).flatMap(this::resolveSnapshot);
    }

    /**
     * Resolves pricing for a model and token type.
     * @param modelId model identifier
     * @param tokenType token type
     * @return pricing resolution
     */
    PricingResolution resolveRate(String modelId, TokenType tokenType);

    /**
     * Resolves pricing for a model and token type against an expected currency.
     * @param modelId model identifier
     * @param tokenType token type
     * @param expectedCurrency expected currency
     * @return pricing resolution
     */
    PricingResolution resolveRate(String modelId, TokenType tokenType, Currency expectedCurrency);

    /**
     * Registers or updates a pricing policy.
     * @param plan pricing policy
     */
    void registerPlan(PricingPlan plan);
}
