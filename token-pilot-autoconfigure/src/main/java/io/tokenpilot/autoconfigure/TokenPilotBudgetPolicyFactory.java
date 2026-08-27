package io.tokenpilot.autoconfigure;

import io.tokenpilot.budget.BudgetPolicy;
import io.tokenpilot.core.domain.Cost;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Objects;

/**
 * Public entry point for converting Token Pilot settings into an optional budget policy.
 *
 * <p>Budget types are kept separate from {@link TokenPilotProperties} so
 * core-only auto-configuration can load when the budget module is absent.</p>
 */
public final class TokenPilotBudgetPolicyFactory {

    private TokenPilotBudgetPolicyFactory() {
    }

    public static BudgetPolicy from(TokenPilotProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        return from(properties.getBudget());
    }

    public static BudgetPolicy from(TokenPilotProperties.BudgetProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        return new BudgetPolicy(
                properties.getPolicyId(),
                properties.getTargetType(),
                properties.getTargetTagKey(),
                properties.getFallbackTargetId(),
                Cost.of(
                        properties.getMonthlyLimit(),
                        Currency.getInstance(properties.getCurrency())
                ),
                ZoneId.of(properties.getZoneId())
        );
    }
}
