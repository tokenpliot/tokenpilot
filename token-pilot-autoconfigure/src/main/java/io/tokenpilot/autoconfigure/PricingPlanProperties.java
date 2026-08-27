package io.tokenpilot.autoconfigure;

import io.tokenpilot.core.domain.PricingPlan;
import io.tokenpilot.core.domain.TokenType;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;
import java.util.Objects;

/**
 * Pricing configuration for an individual AI model.
 *
 * @param modelId  AI model identifier, such as gpt-4o or Claude-3-5-sonnet. This value must match the ChatResponse model_id.
 * @param rates    per-1,000 (1K) token price by token type, such as PROMPT or COMPLETION
 * @param currency currency used for cost settlement, defaulting to USD
 */
public record PricingPlanProperties(
        String modelId,
        Map<TokenType, BigDecimal> rates,
        String currency
) {

    public PricingPlanProperties {
        rates = Objects.requireNonNullElse(rates, Map.of());
        currency = Objects.requireNonNullElse(currency, "USD");
    }

    public PricingPlan toPricingPlan() {
        return new PricingPlan(
                modelId,
                rates,
                Currency.getInstance(currency)
        );
    }
}
