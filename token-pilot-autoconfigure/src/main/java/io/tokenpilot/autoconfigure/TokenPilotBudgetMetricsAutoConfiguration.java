package io.tokenpilot.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.tokenpilot.budget.ReservationAccountingListener;
import io.tokenpilot.micrometer.internal.BudgetMicrometerComponents;
import io.tokenpilot.micrometer.internal.BudgetMetricsPublisher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Atomic reservation/accounting metrics의 조건부 자동 설정입니다. */
@AutoConfiguration(after = TokenPilotCoreMetricsAutoConfiguration.class)
@ConditionalOnClass({
        MeterRegistry.class,
        ReservationAccountingListener.class,
        BudgetMetricsPublisher.class
})
@ConditionalOnBean(MeterRegistry.class)
@ConditionalOnProperty(
        prefix = "token-pilot",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@ConditionalOnProperty(
        prefix = "token-pilot.metrics",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class TokenPilotBudgetMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public BudgetMetricsPublisher tokenPilotBudgetMetricsPublisher(
            MeterRegistry meterRegistry
    ) {
        return BudgetMicrometerComponents.budgetMetricsPublisher(meterRegistry);
    }
}
