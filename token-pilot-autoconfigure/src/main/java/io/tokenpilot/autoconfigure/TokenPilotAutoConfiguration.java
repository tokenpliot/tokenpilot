package io.tokenpilot.autoconfigure;

import io.tokenpilot.core.CoreComponents;
import io.tokenpilot.core.CostCalculator;
import io.tokenpilot.core.LedgerListener;
import io.tokenpilot.core.LedgerManager;
import io.tokenpilot.core.ModelRegistry;
import io.tokenpilot.core.PreflightCostEstimator;
import io.tokenpilot.core.PreflightDecisionListener;
import io.tokenpilot.core.PricingEvaluator;
import io.tokenpilot.core.PricingProvider;
import io.tokenpilot.core.PricingRegistry;
import io.tokenpilot.core.TokenBudget;
import io.tokenpilot.core.TokenEstimator;
import io.tokenpilot.core.internal.LedgerComponents;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Auto-configuration for the Token Pilot framework-independent core. */
@AutoConfiguration
@ConditionalOnProperty(
        prefix = "token-pilot",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@EnableConfigurationProperties(TokenPilotProperties.class)
public class TokenPilotAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PricingProvider pricingProvider(TokenPilotProperties properties) {
        var plans = properties.toPricingPlans();
        return () -> plans;
    }

    @Bean
    @ConditionalOnMissingBean
    public PricingRegistry pricingRegistry(
            ObjectProvider<PricingProvider> pricingProviders
    ) {
        return LedgerComponents.inMemoryPricingRegistry(
                pricingProviders.orderedStream().toList()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public CostCalculator costCalculator() {
        return LedgerComponents.defaultCostCalculator();
    }

    @Bean
    @ConditionalOnMissingBean
    public PricingEvaluator pricingEvaluator() {
        return LedgerComponents.defaultPricingEvaluator();
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelRegistry modelRegistry() {
        return CoreComponents.defaultModelRegistry();
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenEstimator tokenEstimator() {
        return CoreComponents.utf8ByteHeuristicTokenEstimator();
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenBudget tokenBudget(
            ModelRegistry modelRegistry,
            ObjectProvider<PreflightDecisionListener> decisionListeners
    ) {
        return CoreComponents.tokenBudget(
                modelRegistry,
                decisionListeners.orderedStream().toList()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public PreflightCostEstimator preflightCostEstimator() {
        return CoreComponents.defaultPreflightCostEstimator();
    }

    @Bean
    @ConditionalOnMissingBean
    public LedgerManager ledgerManager(
            PricingRegistry pricingRegistry,
            CostCalculator costCalculator,
            ObjectProvider<LedgerListener> ledgerListeners
    ) {
        return LedgerComponents.defaultLedgerManager(
                pricingRegistry,
                costCalculator,
                ledgerListeners.orderedStream().toList()
        );
    }
}
