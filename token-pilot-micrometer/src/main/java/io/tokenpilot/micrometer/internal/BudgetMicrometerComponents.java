package io.tokenpilot.micrometer.internal;

import io.micrometer.core.instrument.MeterRegistry;

/** Atomic budget owner의 선택 Micrometer publisher 팩토리입니다. */
public final class BudgetMicrometerComponents {

    private BudgetMicrometerComponents() {
    }

    public static BudgetMetricsPublisher budgetMetricsPublisher(
            MeterRegistry meterRegistry
    ) {
        return new BudgetMetricsPublisher(meterRegistry);
    }
}
