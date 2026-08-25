package io.tokenpilot.autoconfigure;

import io.tokenpilot.budget.BudgetEvaluator;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.core.CostCalculator;
import io.tokenpilot.core.LedgerManager;
import io.tokenpilot.core.PricingEvaluator;
import io.tokenpilot.core.PricingMissingListener;
import io.tokenpilot.core.PricingRegistry;
import io.tokenpilot.core.domain.MissingPricingPolicy;
import io.tokenpilot.springai.LedgerAdvisor;
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

    @Bean
    @ConditionalOnMissingBean
    public LedgerAdvisor ledgerAdvisor(
            LedgerManager ledgerManager,
            UsageExtractor usageExtractor,
            ObjectProvider<BudgetEvaluator> budgetEvaluator,
            ObjectProvider<BudgetStateStore> budgetStateStore,
            CostCalculator costCalculator,
            PricingRegistry pricingRegistry,
            PricingEvaluator pricingEvaluator,
            ObjectProvider<PricingMissingListener> pricingMissingListeners
    ) {
        BudgetEvaluator evaluator = budgetEvaluator.getIfAvailable();
        BudgetStateStore stateStore = budgetStateStore.getIfAvailable();
        boolean completeBudgetGraph = evaluator != null && stateStore != null;
        BudgetEvaluator resolvedEvaluator = completeBudgetGraph ? evaluator : null;
        BudgetStateStore resolvedStateStore = completeBudgetGraph ? stateStore : null;
        MissingPricingPolicy missingPricingPolicy = completeBudgetGraph
                ? MissingPricingPolicy.FAIL_CLOSED
                : MissingPricingPolicy.FAIL_OPEN;

        return LedgerSpringAiComponents.defaultLedgerAdvisor(
                ledgerManager,
                usageExtractor,
                resolvedEvaluator,
                resolvedStateStore,
                costCalculator,
                pricingRegistry,
                pricingEvaluator,
                missingPricingPolicy,
                pricingMissingListeners.orderedStream().toList()
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
