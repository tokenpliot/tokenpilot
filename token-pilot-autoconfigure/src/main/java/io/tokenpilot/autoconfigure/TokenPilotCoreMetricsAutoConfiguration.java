package io.tokenpilot.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.tokenpilot.micrometer.MetricsOptions;
import io.tokenpilot.micrometer.internal.CoreMetricsPublisher;
import io.tokenpilot.micrometer.internal.LedgerMicrometerComponents;
import io.tokenpilot.micrometer.internal.MicroCostMetricsPublisher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Core control events와 legacy ledger metrics의 조건부 자동 설정입니다. */
@AutoConfiguration(after = TokenPilotAutoConfiguration.class, afterName = {
        "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"
})
@ConditionalOnClass({MeterRegistry.class, CoreMetricsPublisher.class})
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
@EnableConfigurationProperties(TokenPilotProperties.class)
public class TokenPilotCoreMetricsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CoreMetricsPublisher tokenPilotCoreMetricsPublisher(
            MeterRegistry meterRegistry
    ) {
        return LedgerMicrometerComponents.coreMetricsPublisher(meterRegistry);
    }

    @Bean(name = "microCostMetricsPublisher")
    @ConditionalOnMissingBean(MicroCostMetricsPublisher.class)
    @ConditionalOnProperty(
            prefix = "token-pilot.metrics",
            name = "legacy-ai-token-metrics-enabled",
            havingValue = "true"
    )
    public MicroCostMetricsPublisher microCostMetricsPublisher(
            MeterRegistry meterRegistry,
            TokenPilotProperties properties
    ) {
        return LedgerMicrometerComponents.legacyMicroCostMetricsPublisher(
                meterRegistry,
                new MetricsOptions(
                        properties.getMetrics().getTagWhitelist(),
                        true
                )
        );
    }
}
