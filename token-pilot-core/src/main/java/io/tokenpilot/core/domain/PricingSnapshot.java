package io.tokenpilot.core.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Currency;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Request-scoped pricing snapshot resolved before provider invocation.
 */
public record PricingSnapshot(
        String modelId,
        String pricingPolicyId,
        String catalogVersion,
        Instant checkedAt,
        Map<TokenType, BigDecimal> rates,
        Currency currency
) {
    public static final String DEFAULT_CATALOG_VERSION = "default";

    public PricingSnapshot {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("modelId must not be blank");
        }
        if (pricingPolicyId == null || pricingPolicyId.isBlank()) {
            throw new IllegalArgumentException("pricingPolicyId must not be blank");
        }
        if (catalogVersion == null || catalogVersion.isBlank()) {
            throw new IllegalArgumentException("catalogVersion must not be blank");
        }

        checkedAt = Objects.requireNonNull(checkedAt, "checkedAt must not be null");
        currency = Objects.requireNonNull(currency, "currency must not be null");
        Objects.requireNonNull(rates, "rates must not be null");
        Map<TokenType, BigDecimal> copiedRates = new EnumMap<>(TokenType.class);
        copiedRates.putAll(rates);
        rates = Collections.unmodifiableMap(copiedRates);
        rates.values().forEach(rate -> {
            if (rate.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("rate must not be negative");
            }
        });
    }

    public static PricingSnapshot from(PricingPlan plan, String catalogVersion, Instant checkedAt) {
        return new PricingSnapshot(
                plan.modelId(),
                plan.pricingPolicyId(),
                catalogVersion,
                checkedAt,
                plan.rates(),
                plan.currency()
        );
    }

    /**
     * Returns whether the billing terms are equal, excluding the pricing lookup time.
     * {@link #checkedAt()} is observational metadata that may differ on retries of
     * the same request, so it is excluded from the idempotency fingerprint.
     */
    public boolean hasSameBillingTerms(PricingSnapshot other) {
        return haveSameBillingTerms(this, other);
    }

    /**
     * Compares the billing terms of two snapshots. Compatible snapshots without a
     * rate map, such as test doubles, are considered equal when both maps are absent.
     */
    public static boolean haveSameBillingTerms(
            PricingSnapshot first,
            PricingSnapshot second
    ) {
        Objects.requireNonNull(first, "first must not be null");
        Objects.requireNonNull(second, "second must not be null");
        if (!Objects.equals(first.modelId(), second.modelId())
                || !Objects.equals(first.pricingPolicyId(), second.pricingPolicyId())
                || !Objects.equals(first.catalogVersion(), second.catalogVersion())
                || !Objects.equals(first.currency(), second.currency())) {
            return false;
        }
        Map<TokenType, BigDecimal> firstRates = first.rates();
        Map<TokenType, BigDecimal> secondRates = second.rates();
        if (firstRates == null || secondRates == null) {
            return firstRates == secondRates;
        }
        if (!firstRates.keySet().equals(secondRates.keySet())) {
            return false;
        }
        return firstRates.entrySet().stream().allMatch(entry ->
                entry.getValue().compareTo(secondRates.get(entry.getKey())) == 0
        );
    }

    /**
     * Bounded hash code consistent with {@link #hasSameBillingTerms(PricingSnapshot)}.
     */
    public int billingTermsHashCode() {
        int result = Objects.hash(modelId, pricingPolicyId, catalogVersion, currency);
        if (rates == null) {
            return result;
        }
        for (TokenType tokenType : TokenType.values()) {
            BigDecimal rate = rates.get(tokenType);
            if (rate != null) {
                result = 31 * result + Objects.hash(
                        tokenType,
                        rate.stripTrailingZeros()
                );
            }
        }
        return result;
    }
}
