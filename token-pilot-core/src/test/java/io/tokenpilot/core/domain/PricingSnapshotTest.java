package io.tokenpilot.core.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PricingSnapshotTest {

    @Test
    @DisplayName("요청 단위 pricing snapshot은 pricing 식별자와 적용 rate 정보를 보존해야 한다")
    void preservePricingSnapshotValues() {
        Instant checkedAt = Instant.parse("2026-07-30T00:00:00Z");
        Map<TokenType, BigDecimal> rates = new EnumMap<>(TokenType.class);
        rates.put(TokenType.PROMPT, new BigDecimal("0.01"));
        rates.put(TokenType.COMPLETION, new BigDecimal("0.03"));

        PricingSnapshot snapshot = new PricingSnapshot(
                "gpt-4o",
                "standard",
                "catalog-v1",
                checkedAt,
                rates,
                Currency.getInstance("USD")
        );

        assertThat(snapshot.modelId()).isEqualTo("gpt-4o");
        assertThat(snapshot.pricingPolicyId()).isEqualTo("standard");
        assertThat(snapshot.catalogVersion()).isEqualTo("catalog-v1");
        assertThat(snapshot.checkedAt()).isEqualTo(checkedAt);
        assertThat(snapshot.currency()).isEqualTo(Currency.getInstance("USD"));
        assertThat(snapshot.rates()).containsEntry(TokenType.PROMPT, new BigDecimal("0.01"));
        assertThat(snapshot.rates()).containsEntry(TokenType.COMPLETION, new BigDecimal("0.03"));
    }

    @Test
    @DisplayName("pricing snapshot rates는 생성 후 변경할 수 없어야 한다")
    void ratesMustBeImmutable() {
        Map<TokenType, BigDecimal> rates = new EnumMap<>(TokenType.class);
        rates.put(TokenType.PROMPT, new BigDecimal("0.01"));

        PricingSnapshot snapshot = new PricingSnapshot(
                "gpt-4o",
                "standard",
                "catalog-v1",
                Instant.parse("2026-07-30T00:00:00Z"),
                rates,
                Currency.getInstance("USD")
        );

        rates.put(TokenType.PROMPT, new BigDecimal("9.99"));

        assertThat(snapshot.rates()).containsEntry(TokenType.PROMPT, new BigDecimal("0.01"));
        assertThatThrownBy(() -> snapshot.rates().put(TokenType.COMPLETION, new BigDecimal("0.03")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("pricing snapshot은 빈 rates를 보존할 수 있어야 한다")
    void preserveEmptyRates() {
        PricingSnapshot snapshot = new PricingSnapshot(
                "gpt-4o",
                "standard",
                "catalog-v1",
                Instant.parse("2026-07-30T00:00:00Z"),
                Map.of(),
                Currency.getInstance("USD")
        );

        assertThat(snapshot.rates()).isEmpty();
    }

    @Test
    @DisplayName("멱등성 비교는 조회 시각과 decimal scale을 가격 책임에서 제외해야 한다")
    void comparesBillingTermsWithoutObservationMetadata() {
        PricingSnapshot first = new PricingSnapshot(
                "gpt-4o",
                "standard",
                "catalog-v1",
                Instant.parse("2026-07-30T00:00:00Z"),
                Map.of(
                        TokenType.PROMPT, new BigDecimal("0.010"),
                        TokenType.COMPLETION, new BigDecimal("0.030")
                ),
                Currency.getInstance("USD")
        );
        PricingSnapshot second = new PricingSnapshot(
                "gpt-4o",
                "standard",
                "catalog-v1",
                Instant.parse("2026-07-30T00:01:00Z"),
                Map.of(
                        TokenType.PROMPT, new BigDecimal("0.01"),
                        TokenType.COMPLETION, new BigDecimal("0.03")
                ),
                Currency.getInstance("USD")
        );

        assertThat(first.hasSameBillingTerms(second)).isTrue();
        assertThat(first.billingTermsHashCode())
                .isEqualTo(second.billingTermsHashCode());
    }
}
