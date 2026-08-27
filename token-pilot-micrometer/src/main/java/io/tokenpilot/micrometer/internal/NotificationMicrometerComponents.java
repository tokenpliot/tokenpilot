package io.tokenpilot.micrometer.internal;

import io.micrometer.core.instrument.MeterRegistry;

/** Factory for the optional Micrometer publisher owned by budget notifications. */
public final class NotificationMicrometerComponents {

    private NotificationMicrometerComponents() {
    }

    public static NotificationMetricsPublisher notificationMetricsPublisher(
            MeterRegistry meterRegistry
    ) {
        return new NotificationMetricsPublisher(meterRegistry);
    }
}
