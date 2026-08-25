package io.tokenpilot.autoconfigure;

import io.tokenpilot.budget.BudgetEvaluator;
import io.tokenpilot.budget.AtomicBudgetStateStore;
import io.tokenpilot.core.CostCalculator;
import io.tokenpilot.core.LedgerManager;
import io.tokenpilot.core.ModelRegistry;
import io.tokenpilot.core.PreflightCostEstimator;
import io.tokenpilot.core.PricingEvaluator;
import io.tokenpilot.core.PricingMissingListener;
import io.tokenpilot.core.PricingRegistry;
import io.tokenpilot.core.TokenBudget;
import io.tokenpilot.core.TokenEstimator;
import io.tokenpilot.core.domain.MissingPricingPolicy;
import io.tokenpilot.springai.LedgerAdvisor;
import io.tokenpilot.springai.ReservedOutputTokensResolver;
import io.tokenpilot.springai.UsageExtractor;
import io.tokenpilot.springai.internal.LedgerSpringAiComponents;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Optional Spring AI adapter의 bean graph를 격리하는 자동 설정입니다. */
@AutoConfiguration(after = {
        TokenPilotAutoConfiguration.class,
        TokenPilotBudgetAutoConfiguration.class,
        TokenPilotCoreMetricsAutoConfiguration.class
})
@ConditionalOnClass({ChatClient.class, LedgerSpringAiComponents.class})
@ConditionalOnProperty(
        prefix = "token-pilot",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class TokenPilotSpringAiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public UsageExtractor usageExtractor() {
        return LedgerSpringAiComponents.defaultUsageExtractor();
    }

    @Bean(name = "ledgerAdvisor")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
            prefix = "token-pilot.budget",
            name = "enabled",
            havingValue = "false",
            matchIfMissing = true
    )
    public LedgerAdvisor ledgerOnlyAdvisor(
            LedgerManager ledgerManager,
            UsageExtractor usageExtractor,
            CostCalculator costCalculator,
            PricingRegistry pricingRegistry,
            PricingEvaluator pricingEvaluator,
            ObjectProvider<PricingMissingListener> pricingMissingListeners
    ) {
        return LedgerSpringAiComponents.defaultLedgerAdvisor(
                ledgerManager,
                usageExtractor,
                null,
                null,
                costCalculator,
                pricingRegistry,
                pricingEvaluator,
                MissingPricingPolicy.FAIL_OPEN,
                pricingMissingListeners.orderedStream().toList()
        );
    }

    @Bean(name = "ledgerAdvisor")
    @ConditionalOnMissingBean(LedgerAdvisor.class)
    @ConditionalOnProperty(
            prefix = "token-pilot.budget",
            name = "enabled",
            havingValue = "true"
    )
    public LedgerAdvisor accountingLedgerAdvisor(
            UsageExtractor usageExtractor,
            BudgetEvaluator budgetEvaluator,
            AtomicBudgetStateStore budgetStateStore,
            PricingRegistry pricingRegistry,
            PricingEvaluator pricingEvaluator,
            ModelRegistry modelRegistry,
            TokenEstimator tokenEstimator,
            TokenBudget tokenBudget,
            PreflightCostEstimator preflightCostEstimator,
            ObjectProvider<ReservedOutputTokensResolver> reservedOutputTokensResolver,
            ObjectProvider<PricingMissingListener> pricingMissingListeners,
            TokenPilotProperties properties
    ) {
        return LedgerSpringAiComponents.accountingLedgerAdvisor(
                usageExtractor,
                budgetEvaluator,
                budgetStateStore,
                budgetStateStore,
                pricingRegistry,
                modelRegistry,
                tokenEstimator,
                tokenBudget,
                preflightCostEstimator,
                pricingEvaluator,
                pricingMissingListeners.orderedStream().toList(),
                properties.getSpringAi().getDefaultModelId(),
                properties.getSpringAi().getDefaultReservedOutputTokens(),
                reservedOutputTokensResolver.getIfAvailable(),
                properties.getSpringAi().getFramingHeadroomTokens()
        );
    }

    @Bean
    @ConditionalOnBean(LedgerAdvisor.class)
    public LedgerChatClientBuilderCustomizer ledgerChatClientBuilderCustomizer(
            LedgerAdvisor ledgerAdvisor
    ) {
        return new LedgerChatClientBuilderCustomizer(ledgerAdvisor);
    }
}
