package io.tokenpilot.micrometer.internal;

import io.micrometer.core.instrument.MeterRegistry;
import io.tokenpilot.core.LedgerListener;
import io.tokenpilot.micrometer.MetricsOptions;

import java.util.Set;

/**
 * Micrometer 메트릭 퍼블리셔 생성을 위한 팩토리 클래스입니다.
 */
public final class LedgerMicrometerComponents {

    private LedgerMicrometerComponents() {
    }

    public static LedgerListener microCostMetricsPublisher(MeterRegistry meterRegistry, Set<String> allowedTagKeys) {
        return microCostMetricsPublisher(meterRegistry, MetricsOptions.withAllowedTagKeys(allowedTagKeys));
    }

    public static LedgerListener microCostMetricsPublisher(MeterRegistry meterRegistry, MetricsOptions options) {
        return new MicroCostMetricsPublisher(meterRegistry, options);
    }

    public static MicroCostMetricsPublisher legacyMicroCostMetricsPublisher(
            MeterRegistry meterRegistry,
            MetricsOptions options
    ) {
        return new MicroCostMetricsPublisher(meterRegistry, options);
    }

    public static CoreMetricsPublisher coreMetricsPublisher(MeterRegistry meterRegistry) {
        return new CoreMetricsPublisher(meterRegistry);
    }

}
