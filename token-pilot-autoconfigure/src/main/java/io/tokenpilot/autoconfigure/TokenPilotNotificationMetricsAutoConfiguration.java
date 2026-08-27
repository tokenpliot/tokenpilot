package io.tokenpilot.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.tokenpilot.micrometer.internal.NotificationMetricsPublisher;
import io.tokenpilot.micrometer.internal.NotificationMicrometerComponents;
import io.tokenpilot.notification.BudgetNotificationLifecycleListener;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Conditional auto-configuration for budget notification lifecycle metrics. */
@AutoConfiguration(after = TokenPilotCoreMetricsAutoConfiguration.class)
@ConditionalOnClass({
        MeterRegistry.class,
        BudgetNotificationLifecycleListener.class,
        NotificationMetricsPublisher.class
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
public class TokenPilotNotificationMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public NotificationMetricsPublisher tokenPilotNotificationMetricsPublisher(
            MeterRegistry meterRegistry
    ) {
        return NotificationMicrometerComponents.notificationMetricsPublisher(
                meterRegistry
        );
    }
}
