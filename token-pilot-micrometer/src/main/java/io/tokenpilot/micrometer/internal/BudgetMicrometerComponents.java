package io.tokenpilot.micrometer.internal;

import io.micrometer.core.instrument.MeterRegistry;

/** Factory for the optional Micrometer publisher owned by the atomic budget component. */
public final class BudgetMicrometerComponents {

    private BudgetMicrometerComponents() {
    }

    public static BudgetMetricsPublisher budgetMetricsPublisher(
            MeterRegistry meterRegistry
    ) {
        return new BudgetMetricsPublisher(meterRegistry);
    }
}
