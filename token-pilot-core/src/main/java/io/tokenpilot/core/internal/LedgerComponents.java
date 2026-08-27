package io.tokenpilot.core.internal;

import io.tokenpilot.core.CostCalculator;
import io.tokenpilot.core.LedgerListener;
import io.tokenpilot.core.LedgerManager;
import io.tokenpilot.core.ModelRegistry;
import io.tokenpilot.core.PricingEvaluator;
import io.tokenpilot.core.PricingProvider;
import io.tokenpilot.core.PricingRegistry;
import io.tokenpilot.core.PreflightCostEstimator;
import io.tokenpilot.core.PreflightDecisionListener;
import io.tokenpilot.core.TokenBudget;
import io.tokenpilot.core.TokenEstimator;
import io.tokenpilot.core.domain.ModelDefinition;

import java.util.Collection;
import java.util.List;

/**
 * Factory for creating Token Pilot core components.
 */
public final class LedgerComponents {

    private LedgerComponents() {
    }

    public static CostCalculator defaultCostCalculator() {
        return new DefaultCostCalculator();
    }

    public static PricingEvaluator defaultPricingEvaluator() {
        return new DefaultPricingEvaluator();
    }

    /**
     * Creates the default preflight cost-bound estimator.
     *
     * <p>The estimator requires a context retaining a REQUEST-scope token result
     * and one immutable pricing snapshot. This factory does not perform context
     * admission or atomic reservation.</p>
     *
     * @return default preflight cost-bound estimator
     */
    public static PreflightCostEstimator defaultPreflightCostEstimator() {
        return new DefaultPreflightCostEstimator();
    }

    /**
     * Creates a text-only heuristic token estimator based on UTF-8 byte length.
     *
     * @return UTF-8 byte heuristic estimator
     */
    public static TokenEstimator utf8ByteHeuristicTokenEstimator() {
        return new HeuristicTokenEstimator();
    }

    /**
     * Creates a context admission evaluator backed by a model registry.
     *
     * @param modelRegistry versioned model catalog
     * @return token context budget evaluator
     */
    public static TokenBudget tokenBudget(ModelRegistry modelRegistry) {
        return new DefaultTokenBudget(modelRegistry);
    }

    /**
     * Creates a context admission evaluator backed by a model registry and
     * best-effort decision listeners.
     */
    public static TokenBudget tokenBudget(
            ModelRegistry modelRegistry,
            List<PreflightDecisionListener> listeners
    ) {
        return new DefaultTokenBudget(modelRegistry, listeners);
    }

    /**
     * Creates an in-memory model registry from model definitions to register.
     *
     * @param definitions immutable model catalog definitions
     * @return in-memory model registry
     */
    public static ModelRegistry inMemoryModelRegistry(Collection<ModelDefinition> definitions) {
        return new InMemoryModelRegistry(definitions);
    }

    /**
     * Creates the minimal default catalog with public-document verification times
     * and encoding metadata fixed.
     *
     * @return versioned catalog for gpt-4o and gpt-4o-mini
     */
    public static ModelRegistry defaultModelRegistry() {
        return inMemoryModelRegistry(DefaultModelCatalog.definitions());
    }

    public static PricingRegistry inMemoryPricingRegistry(List<PricingProvider> providers) {
        return new InMemoryPricingRegistry(providers);
    }

    public static LedgerManager defaultLedgerManager(
            PricingRegistry pricingRegistry,
            CostCalculator costCalculator,
            List<LedgerListener> listeners
    ) {
        return new DefaultLedgerManager(pricingRegistry, costCalculator, listeners);
    }
}
