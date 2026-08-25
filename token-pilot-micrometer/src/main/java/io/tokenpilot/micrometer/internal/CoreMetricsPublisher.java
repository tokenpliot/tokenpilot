package io.tokenpilot.micrometer.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.tokenpilot.core.PreflightDecisionListener;
import io.tokenpilot.core.PricingMissingListener;
import io.tokenpilot.core.domain.PreflightDecisionEvent;
import io.tokenpilot.core.domain.PricingMissingEvent;

import java.util.Objects;

/** Token Pilot core의 preflight와 pricing-miss 이벤트를 Micrometer meter로 투영합니다. */
public final class CoreMetricsPublisher
        implements PreflightDecisionListener, PricingMissingListener {

    private final MeterRegistry meterRegistry;

    public CoreMetricsPublisher(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(
                meterRegistry,
                "meterRegistry must not be null"
        );
    }

    @Override
    public void onDecision(PreflightDecisionEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        var result = event.result();
        Counter.builder(TokenPilotMeterMetadata.PREFLIGHT_REQUESTS)
                .description(TokenPilotMeterMetadata.PREFLIGHT_REQUESTS_DESCRIPTION)
                .baseUnit(TokenPilotMeterMetadata.REQUESTS_BASE_UNIT)
                .tag("decision", TokenPilotMeterMetadata.tagValue(result.status()))
                .tag("reason", TokenPilotMeterMetadata.tagValue(result.reason()))
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void onPricingMissing(PricingMissingEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        Counter.builder(TokenPilotMeterMetadata.PRICING_MISSING)
                .description(TokenPilotMeterMetadata.PRICING_MISSING_DESCRIPTION)
                .baseUnit(TokenPilotMeterMetadata.EVENTS_BASE_UNIT)
                .tag("policy", TokenPilotMeterMetadata.tagValue(event.policy()))
                .register(meterRegistry)
                .increment();
    }
}
