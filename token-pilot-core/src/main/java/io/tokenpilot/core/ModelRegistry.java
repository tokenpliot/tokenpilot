package io.tokenpilot.core;

import io.tokenpilot.core.domain.ModelDefinition;

import java.util.Optional;

/**
 * Looks up versioned model definitions by canonical ID or exact alias.
 *
 * <p>Use {@link ModelDefinition#canonicalModelId()} and
 * {@link ModelDefinition#pricingPolicyId()} from the result together when
 * looking up pricing in {@link PricingRegistry}. Passing an alias directly to
 * pricing lookup could cause context and pricing to refer to different models.</p>
 */
public interface ModelRegistry {

    /**
     * Resolves a canonical model ID or exact alias to its canonical definition.
     *
     * @param modelIdOrAlias canonical ID or alias
     * @return registered model definition, or empty when not registered
     */
    Optional<ModelDefinition> find(String modelIdOrAlias);
}
