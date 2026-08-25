package io.tokenpilot.micrometer.internal;

import io.micrometer.core.instrument.MeterRegistry;

/** Budget notification owner의 선택 Micrometer publisher 팩토리입니다. */
public final class NotificationMicrometerComponents {

    private NotificationMicrometerComponents() {
    }

    public static NotificationMetricsPublisher notificationMetricsPublisher(
            MeterRegistry meterRegistry
    ) {
        return new NotificationMetricsPublisher(meterRegistry);
    }
}
