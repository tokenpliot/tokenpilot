package io.tokenpilot.micrometer.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.tokenpilot.notification.BudgetNotificationLifecycleEvent;
import io.tokenpilot.notification.BudgetNotificationLifecycleListener;

import java.util.Objects;

/** Projects budget notification lifecycle events into Micrometer meters. */
public final class NotificationMetricsPublisher
        implements BudgetNotificationLifecycleListener {

    private final MeterRegistry meterRegistry;

    public NotificationMetricsPublisher(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(
                meterRegistry,
                "meterRegistry must not be null"
        );
    }

    @Override
    public void onNotificationLifecycle(BudgetNotificationLifecycleEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        Counter.builder(TokenPilotMeterMetadata.NOTIFICATION_EVENTS)
                .description(TokenPilotMeterMetadata.NOTIFICATION_EVENTS_DESCRIPTION)
                .baseUnit(TokenPilotMeterMetadata.EVENTS_BASE_UNIT)
                .tag("outcome", TokenPilotMeterMetadata.tagValue(event.outcome()))
                .tag("threshold", TokenPilotMeterMetadata.tagValue(event.threshold()))
                .register(meterRegistry)
                .increment();
    }
}
