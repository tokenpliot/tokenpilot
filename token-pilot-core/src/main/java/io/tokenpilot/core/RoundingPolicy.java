package io.tokenpilot.core;

import io.tokenpilot.core.domain.Cost;

import java.math.RoundingMode;
import java.util.Objects;

/**
 * Policy for rounding costs that preserve internal precision at an external
 * display or billing boundary.
 */
public final class RoundingPolicy {

    public static final RoundingPolicy COST_BOUNDARY_ROUNDING =
            new RoundingPolicy(6, RoundingMode.HALF_UP);

    private final int scale;
    private final RoundingMode roundingMode;

    private RoundingPolicy(int scale, RoundingMode roundingMode) {
        this.scale = scale;
        this.roundingMode = roundingMode;
    }

    public Cost apply(Cost cost) {
        Objects.requireNonNull(cost, "cost must not be null");
        return Cost.of(
                cost.value().setScale(scale, roundingMode),
                cost.currency()
        );
    }
}
