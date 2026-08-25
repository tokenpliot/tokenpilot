package io.tokenpilot.micrometer.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.tokenpilot.micrometer.MetricsOptions;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerMicrometerComponentsTest {

    @Test
    void createsOwnerSpecificPublishers() {
        var meterRegistry = new SimpleMeterRegistry();

        assertThat(LedgerMicrometerComponents.coreMetricsPublisher(meterRegistry))
                .isInstanceOf(CoreMetricsPublisher.class);
        assertThat(BudgetMicrometerComponents.budgetMetricsPublisher(meterRegistry))
                .isInstanceOf(BudgetMetricsPublisher.class);
        assertThat(NotificationMicrometerComponents.notificationMetricsPublisher(meterRegistry))
                .isInstanceOf(NotificationMetricsPublisher.class);
        assertThat(LedgerMicrometerComponents.legacyMicroCostMetricsPublisher(
                meterRegistry,
                MetricsOptions.legacyDefaults()
        )).isInstanceOf(MicroCostMetricsPublisher.class);
    }

    @Test
    void coreFactoryMethodTableDoesNotReferenceOptionalOwners() {
        assertThat(LedgerMicrometerComponents.class.getDeclaredMethods())
                .allSatisfy(method -> {
                    assertThat(method.getReturnType().getName())
                            .doesNotContain("BudgetMetricsPublisher")
                            .doesNotContain("NotificationMetricsPublisher");
                    assertThat(method.getParameterTypes())
                            .extracting(Class::getName)
                            .noneMatch(name -> name.startsWith("io.tokenpilot.budget"))
                            .noneMatch(name -> name.startsWith("io.tokenpilot.notification"));
                });
    }
}
