package io.tokenpilot.autoconfigure;

import io.tokenpilot.budget.BudgetEvaluator;
import io.tokenpilot.budget.AtomicBudgetStateStore;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.budget.ReservationAccountingListener;
import io.tokenpilot.budget.ReservationAccountingListenerErrorHandler;
import io.tokenpilot.budget.internal.LedgerBudgetComponents;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/** Optional budget module의 bean graph를 격리하는 자동 설정입니다. */
@AutoConfiguration(after = {
        TokenPilotAutoConfiguration.class,
        TokenPilotBudgetMetricsAutoConfiguration.class
})
@ConditionalOnClass(LedgerBudgetComponents.class)
@ConditionalOnProperty(
        prefix = "token-pilot",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@ConditionalOnProperty(
        prefix = "token-pilot.budget",
        name = "enabled",
        havingValue = "true"
)
public class TokenPilotBudgetAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(BudgetStateStore.class)
    public AtomicBudgetStateStore budgetStateStore(
            ObjectProvider<ReservationAccountingListener> accountingListeners,
            ObjectProvider<ReservationAccountingListenerErrorHandler> listenerErrorHandlers
    ) {
        return LedgerBudgetComponents.inMemoryAtomicBudgetStateStore(
                () -> accountingListeners.orderedStream().toList(),
                () -> listenerErrorHandlers.orderedStream().toList()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public BudgetEvaluator budgetEvaluator(
            BudgetStateStore budgetStateStore,
            TokenPilotProperties properties,
            ObjectProvider<Clock> clock
    ) {
        return LedgerBudgetComponents.defaultBudgetEvaluator(
                budgetStateStore,
                TokenPilotBudgetPolicyFactory.from(properties),
                clock.getIfAvailable(Clock::systemUTC)
        );
    }
}
