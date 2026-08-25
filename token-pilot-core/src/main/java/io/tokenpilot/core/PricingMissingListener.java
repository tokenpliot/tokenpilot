package io.tokenpilot.core;

import io.tokenpilot.core.domain.PricingMissingEvent;

/** missing-pricing 판정을 best-effort로 관찰하는 framework-independent 계약입니다. */
@FunctionalInterface
public interface PricingMissingListener {

    void onPricingMissing(PricingMissingEvent event);
}
