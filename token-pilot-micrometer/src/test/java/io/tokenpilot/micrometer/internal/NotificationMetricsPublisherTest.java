package io.tokenpilot.micrometer.internal;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.tokenpilot.budget.BudgetThreshold;
import io.tokenpilot.notification.BudgetNotificationLifecycleEvent;
import io.tokenpilot.notification.BudgetNotificationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationMetricsPublisherTest {

    private SimpleMeterRegistry meterRegistry;
    private NotificationMetricsPublisher publisher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        publisher = new NotificationMetricsPublisher(meterRegistry);
    }

    @Test
    void publishesBoundedNotificationLifecycleOutcome() {
        publisher.onNotificationLifecycle(new BudgetNotificationLifecycleEvent(
                BudgetNotificationOutcome.FAILURE,
                BudgetThreshold.WARNING
        ));

        var counter = meterRegistry.find("tokenpilot.notification.events")
                .tag("outcome", "failure")
                .tag("threshold", "warning")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
        assertThat(counter.getId().getType()).isEqualTo(Meter.Type.COUNTER);
        assertThat(counter.getId().getBaseUnit()).isEqualTo("events");
        assertThat(counter.getId().getDescription())
                .isEqualTo("Total budget notification lifecycle events observed by Token Pilot");
        assertThat(counter.getId().getTags()).extracting(tag -> tag.getKey())
                .containsExactly("outcome", "threshold");
    }

    @Test
    void distinguishesSuccessFailureAndDeduplicatedOutcomes() {
        publisher.onNotificationLifecycle(new BudgetNotificationLifecycleEvent(
                BudgetNotificationOutcome.SUCCESS,
                BudgetThreshold.HALF
        ));
        publisher.onNotificationLifecycle(new BudgetNotificationLifecycleEvent(
                BudgetNotificationOutcome.FAILURE,
                BudgetThreshold.HALF
        ));
        publisher.onNotificationLifecycle(new BudgetNotificationLifecycleEvent(
                BudgetNotificationOutcome.DEDUPLICATED,
                BudgetThreshold.HALF
        ));

        assertThat(meterRegistry.find("tokenpilot.notification.events")
                .tag("outcome", "success").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("tokenpilot.notification.events")
                .tag("outcome", "failure").counter().count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("tokenpilot.notification.events")
                .tag("outcome", "deduplicated").counter().count()).isEqualTo(1.0);
    }
}
