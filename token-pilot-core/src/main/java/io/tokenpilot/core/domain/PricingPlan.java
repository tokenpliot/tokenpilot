package io.tokenpilot.core.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Currency;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Pricing policy information for a specific model.
 * Maintains the price per 1,000 (1K) tokens for each {@link TokenType}.
 *
 * @param modelId  model identifier, such as gpt-4o or claude-3-5-sonnet
 * @param rates    per-1K-token rate by token type
 * @param currency currency, defaulting to USD
 */
public record PricingPlan(
        String modelId,
        String pricingPolicyId,
        Map<TokenType, BigDecimal> rates,
        Currency currency
) {
    public static final String DEFAULT_PRICING_POLICY_ID = "default";

    public PricingPlan {
        if (pricingPolicyId == null || pricingPolicyId.isBlank()) {
            throw new IllegalArgumentException("pricingPolicyId must not be blank");
        }

        Objects.requireNonNull(rates, "rates must not be null");
        Map<TokenType, BigDecimal> copiedRates = new EnumMap<>(TokenType.class);
        copiedRates.putAll(rates);
        rates = Collections.unmodifiableMap(copiedRates);
        if (currency == null) {
            currency = Currency.getInstance("USD");
        }

        rates.values().forEach(v -> {
            if (v.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("Price cannot be negative");
            }
        });
    }

    public PricingPlan(String modelId, Map<TokenType, BigDecimal> rates, Currency currency) {
        this(modelId, DEFAULT_PRICING_POLICY_ID, rates, currency);
    }

    /**
     * Creates a {@link PricingPlan} with basic input/output rates and a currency.
     */
    public PricingPlan(String modelId, BigDecimal promptPricePerK, BigDecimal completionPricePerK, Currency currency) {
        this(modelId, DEFAULT_PRICING_POLICY_ID, createRates(promptPricePerK, completionPricePerK), currency);
    }

    /**
     * Creates a {@link PricingPlan} with basic input/output rates, a pricing policy ID, and a currency.
     */
    public PricingPlan(String modelId, String pricingPolicyId, BigDecimal promptPricePerK, BigDecimal completionPricePerK, Currency currency) {
        this(modelId, pricingPolicyId, createRates(promptPricePerK, completionPricePerK), currency);
    }

    /**
     * Creates a {@link PricingPlan} with basic input/output rates and the default USD currency.
     */
    public PricingPlan(String modelId, BigDecimal promptPricePerK, BigDecimal completionPricePerK) {
        this(modelId, promptPricePerK, completionPricePerK, Currency.getInstance("USD"));
    }

    private static Map<TokenType, BigDecimal> createRates(BigDecimal prompt, BigDecimal completion) {
        Map<TokenType, BigDecimal> rates = new EnumMap<>(TokenType.class);
        rates.put(TokenType.PROMPT, prompt);
        rates.put(TokenType.COMPLETION, completion);
        return rates;
    }

    /**
     * Returns the basic input rate for compatibility.
     */
    public BigDecimal promptPricePerK() {
        return getRate(TokenType.PROMPT);
    }

    /**
     * Returns the basic output rate for compatibility.
     */
    public BigDecimal completionPricePerK() {
        return getRate(TokenType.COMPLETION);
    }

    /**
     * Returns the rate for a token type, falling back through the rate hierarchy
     * when a direct rate is absent.
     * REASONING -> COMPLETION
     * CACHE_READ_PROMPT, CACHE_CREATION_PROMPT -> PROMPT
     */
    public BigDecimal getRate(TokenType type) {
        if (rates.containsKey(type)) {
            return rates.get(type);
        }

        return PricingRateFallback.fallbackFor(type)
                .map(fallbackType -> rates.getOrDefault(fallbackType, BigDecimal.ZERO))
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Returns the pricing resolution for a token type.
     * An explicitly registered zero rate is represented as
     * {@link PricingResolution#RESOLVED}; a missing rate is represented as
     * {@link PricingResolution#MISSING_RATE}.
     */
    public PricingResolution resolveRate(TokenType type) {
        if (rates.containsKey(type)) {
            return PricingResolution.RESOLVED;
        }

        return PricingRateFallback.fallbackFor(type)
                .filter(rates::containsKey)
                .map(fallbackType -> PricingResolution.RESOLVED)
                .orElse(PricingResolution.MISSING_RATE);
    }
}
