package io.tokenpilot.core;

import io.tokenpilot.core.domain.PricingMissingEvent;

/** Framework-independent contract for best-effort observation of missing-pricing decisions. */
@FunctionalInterface
public interface PricingMissingListener {

    void onPricingMissing(PricingMissingEvent event);
}
