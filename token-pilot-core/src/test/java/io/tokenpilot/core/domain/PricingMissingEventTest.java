package io.tokenpilot.core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingMissingEventTest {

    @Test
    @DisplayName("missing pricing policy와 bounded resolution을 보존한다")
    void preservesPolicyAndResolution() {
        PricingMissingEvent event = new PricingMissingEvent(
                MissingPricingPolicy.FAIL_CLOSED,
                PricingResolution.MISSING_RATE
        );

        assertThat(event.policy()).isEqualTo(MissingPricingPolicy.FAIL_CLOSED);
        assertThat(event.resolution()).isEqualTo(PricingResolution.MISSING_RATE);
    }

    @Test
    @DisplayName("resolved와 currency mismatch는 missing pricing event가 될 수 없다")
    void rejectsNonMissingPricing() {
        for (PricingResolution resolution : new PricingResolution[] {
                PricingResolution.RESOLVED,
                PricingResolution.CURRENCY_MISMATCH
        }) {
            assertThatThrownBy(() -> new PricingMissingEvent(
                    MissingPricingPolicy.FAIL_OPEN,
                    resolution
            ))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("resolution must identify missing pricing");
        }
    }
}
