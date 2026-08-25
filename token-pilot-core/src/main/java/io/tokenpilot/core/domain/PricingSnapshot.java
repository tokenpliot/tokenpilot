package io.tokenpilot.core.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Currency;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * provider 호출 전에 확정된 요청 단위 pricing snapshot.
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
     * 가격 조회 시각을 제외하고 실제 비용 책임을 결정하는 terms가 같은지 반환합니다.
     * {@link #checkedAt()}은 같은 요청의 재시도에서 달라질 수 있는 관측 metadata이므로
     * 멱등성 fingerprint에는 포함하지 않습니다.
     */
    public boolean hasSameBillingTerms(PricingSnapshot other) {
        return haveSameBillingTerms(this, other);
    }

    /**
     * 두 snapshot의 비용 책임 terms를 비교합니다. 테스트 double처럼 rate map이 없는
     * 호환 snapshot은 양쪽 모두 없을 때 같은 것으로 취급합니다.
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
     * {@link #hasSameBillingTerms(PricingSnapshot)}와 일치하는 bounded hash code입니다.
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
