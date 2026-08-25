package io.tokenpilot.core.domain;

public enum PricingResolution {
    RESOLVED,
    MISSING_PLAN,
    MISSING_RATE,
    CURRENCY_MISMATCH;

    public boolean isResolved() {
        return this == RESOLVED;
    }

    public boolean isMissing() {
        return this == MISSING_PLAN || this == MISSING_RATE;
    }
}
