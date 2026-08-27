package io.tokenpilot.core;

import io.tokenpilot.core.domain.ModelDefinition;
import io.tokenpilot.core.internal.LedgerComponents;

import java.util.Collection;
import java.util.List;

/**
 * Framework-independent core component entry point.
 */
public final class CoreComponents {

    private CoreComponents() {
    }

    public static TokenEstimator utf8ByteHeuristicTokenEstimator() {
        return LedgerComponents.utf8ByteHeuristicTokenEstimator();
    }

    public static ModelRegistry inMemoryModelRegistry(Collection<ModelDefinition> definitions) {
        return LedgerComponents.inMemoryModelRegistry(definitions);
    }

    public static ModelRegistry defaultModelRegistry() {
        return LedgerComponents.defaultModelRegistry();
    }

    public static PreflightCostEstimator defaultPreflightCostEstimator() {
        return LedgerComponents.defaultPreflightCostEstimator();
    }

    public static TokenBudget tokenBudget(ModelRegistry modelRegistry) {
        return LedgerComponents.tokenBudget(modelRegistry);
    }

    public static TokenBudget tokenBudget(
            ModelRegistry modelRegistry,
            List<PreflightDecisionListener> listeners
    ) {
        return LedgerComponents.tokenBudget(modelRegistry, listeners);
    }
}
