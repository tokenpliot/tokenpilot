package io.tokenpilot.micrometer.internal;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.tokenpilot.core.domain.AdmissionReason;
import io.tokenpilot.core.domain.AdmissionStatus;
import io.tokenpilot.core.domain.BudgetResult;
import io.tokenpilot.core.domain.MissingPricingPolicy;
import io.tokenpilot.core.domain.PreflightDecisionEvent;
import io.tokenpilot.core.domain.PricingMissingEvent;
import io.tokenpilot.core.domain.PricingResolution;
import io.tokenpilot.core.domain.TokenEstimatorDescriptor;
import io.tokenpilot.core.domain.TokenizationBasis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

class CoreMetricsPublisherTest {

    private SimpleMeterRegistry meterRegistry;
    private CoreMetricsPublisher publisher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        publisher = new CoreMetricsPublisher(meterRegistry);
    }

    @Test
    void publishesBoundedPreflightDecision() {
        publisher.onDecision(new PreflightDecisionEvent(indeterminateResult()));

        var counter = meterRegistry.find("tokenpilot.preflight.requests")
                .tag("decision", "indeterminate")
                .tag("reason", "unknown_model")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
        assertThat(counter.getId().getType()).isEqualTo(Meter.Type.COUNTER);
        assertThat(counter.getId().getBaseUnit()).isEqualTo("requests");
        assertThat(counter.getId().getDescription())
                .isEqualTo("Total preflight admission decisions made by Token Pilot");
        assertThat(counter.getId().getTags()).extracting(tag -> tag.getKey())
                .containsExactly("decision", "reason");
    }

    @Test
    void publishesPricingMissWithoutResolutionOrRequestTags() {
        publisher.onPricingMissing(new PricingMissingEvent(
                MissingPricingPolicy.FAIL_CLOSED,
                PricingResolution.MISSING_PLAN
        ));

        var counter = meterRegistry.find("tokenpilot.pricing.missing")
                .tag("policy", "fail_closed")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
        assertThat(counter.getId().getType()).isEqualTo(Meter.Type.COUNTER);
        assertThat(counter.getId().getBaseUnit()).isEqualTo("events");
        assertThat(counter.getId().getDescription())
                .isEqualTo("Total pricing misses observed by Token Pilot");
        assertThat(counter.getId().getTags()).extracting(tag -> tag.getKey())
                .containsExactly("policy");
    }

    private static BudgetResult indeterminateResult() {
        return new BudgetResult(
                AdmissionStatus.INDETERMINATE,
                AdmissionReason.UNKNOWN_MODEL,
                Optional.empty(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                0,
                OptionalLong.empty(),
                OptionalLong.empty(),
                new TokenEstimatorDescriptor("test-estimator", "1"),
                new TokenizationBasis("TEST")
        );
    }
}
