package io.tokenpilot.core.domain;

import java.util.Objects;

/**
 * 명시적인 missing-pricing 판정을 전달하는 framework-independent 이벤트입니다.
 *
 * @param policy missing pricing 처리 정책
 * @param resolution missing plan/rate 판정
 */
public record PricingMissingEvent(
        MissingPricingPolicy policy,
        PricingResolution resolution
) {

    public PricingMissingEvent {
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(resolution, "resolution must not be null");
        if (!resolution.isMissing()) {
            throw new IllegalArgumentException("resolution must identify missing pricing");
        }
    }
}
