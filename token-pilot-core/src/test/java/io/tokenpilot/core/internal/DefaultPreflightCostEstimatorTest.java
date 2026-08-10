package io.tokenpilot.core.internal;

import io.tokenpilot.core.PreflightCostEstimator;
import io.tokenpilot.core.PricingProvider;
import io.tokenpilot.core.PricingRegistry;
import io.tokenpilot.core.domain.PreflightCostResult;
import io.tokenpilot.core.domain.PreflightCostUnavailableReason;
import io.tokenpilot.core.domain.PreflightPricingContext;
import io.tokenpilot.core.domain.PricingPlan;
import io.tokenpilot.core.domain.PricingSnapshot;
import io.tokenpilot.core.domain.TokenCountAccuracy;
import io.tokenpilot.core.domain.TokenCountResult;
import io.tokenpilot.core.domain.TokenCountScope;
import io.tokenpilot.core.domain.TokenCountUnavailableReason;
import io.tokenpilot.core.domain.TokenEstimatorDescriptor;
import io.tokenpilot.core.domain.TokenType;
import io.tokenpilot.core.domain.TokenizationBasis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.Arrays;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;

import static io.tokenpilot.core.domain.PreflightPricingContext.UpperBoundCapability.FINITE;
import static io.tokenpilot.core.domain.PreflightPricingContext.UpperBoundCapability.UNBOUNDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultPreflightCostEstimatorTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final TokenizationBasis BASIS = new TokenizationBasis("o200k_base");
    private static final TokenEstimatorDescriptor ESTIMATOR =
            new TokenEstimatorDescriptor("request-estimator", "1");

    @Test
    @DisplayName("estimated cost와 보수적 safe upper bound를 분리해 계산한다")
    void calculateEstimatedAndSafeUpperBoundCost() {
        PreflightCostEstimator estimator = estimator(plan(Map.of(
                TokenType.PROMPT, decimal("0.010"),
                TokenType.CACHE_READ_PROMPT, decimal("0.002"),
                TokenType.CACHE_CREATION_PROMPT, decimal("0.030"),
                TokenType.COMPLETION, decimal("0.020"),
                TokenType.REASONING, decimal("0.050")
        )));

        PreflightCostResult result = estimator.estimate(
                context(FINITE, USD, PricingSnapshot.DEFAULT_CATALOG_VERSION),
                requestCount(1_000, 1_200),
                500
        );

        assertThat(result).isInstanceOf(PreflightCostResult.Bounded.class);
        PreflightCostResult.Bounded bounded = (PreflightCostResult.Bounded) result;
        assertThat(bounded.estimatedCost().value()).isEqualByComparingTo("0.020");
        assertThat(bounded.safeUpperBoundCost().value()).isEqualByComparingTo("0.061");
        assertThat(bounded.safeUpperBoundCost().compareTo(bounded.estimatedCost())).isGreaterThanOrEqualTo(0);
        assertThat(bounded.inputEstimatedTokens()).isEqualTo(1_000);
        assertThat(bounded.inputSafeUpperBoundTokens()).isEqualTo(1_200);
        assertThat(bounded.reservedOutputTokens()).isEqualTo(500);
        assertThat(bounded.currency()).isEqualTo(USD);
        assertThat(bounded.canonicalModelId()).isEqualTo("model-v1");
        assertThat(bounded.pricingPolicyId()).isEqualTo("standard");
        assertThat(bounded.catalogVersion()).isEqualTo(PricingSnapshot.DEFAULT_CATALOG_VERSION);
        assertThat(bounded.pricingSnapshot().rates()).containsEntry(TokenType.PROMPT, decimal("0.010"));
        assertThat(bounded.estimatorDescriptor()).isEqualTo(ESTIMATOR);
        assertThat(bounded.tokenizationBasis()).isEqualTo(BASIS);
    }

    @Test
    @DisplayName("cache와 reasoning 경로는 최대 단가만 선택해 token을 이중 과금하지 않는다")
    void chooseConservativeRatesWithoutDoubleCountingTokens() {
        PreflightCostEstimator estimator = estimator(plan(Map.of(
                TokenType.PROMPT, decimal("1"),
                TokenType.CACHE_READ_PROMPT, decimal("2"),
                TokenType.CACHE_CREATION_PROMPT, decimal("3"),
                TokenType.COMPLETION, decimal("4"),
                TokenType.REASONING, decimal("5")
        )));

        PreflightCostResult.Bounded result = bounded(estimator.estimate(
                context(FINITE, USD, PricingSnapshot.DEFAULT_CATALOG_VERSION),
                requestCount(1_000, 1_000),
                1_000
        ));

        assertThat(result.safeUpperBoundCost().value()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("input safe bound와 reserved output이 증가하면 비용 상한은 감소하지 않는다")
    void keepSafeUpperBoundMonotonic() {
        PreflightCostEstimator estimator = estimator(plan(Map.of(
                TokenType.PROMPT, decimal("0.01"),
                TokenType.COMPLETION, decimal("0.03")
        )));
        PreflightPricingContext context = context(FINITE, USD, PricingSnapshot.DEFAULT_CATALOG_VERSION);

        PreflightCostResult.Bounded base = bounded(estimator.estimate(context, requestCount(100, 120), 50));
        PreflightCostResult.Bounded moreInput = bounded(estimator.estimate(context, requestCount(100, 121), 50));
        PreflightCostResult.Bounded moreOutput = bounded(estimator.estimate(context, requestCount(100, 120), 51));

        assertThat(moreInput.safeUpperBoundCost().compareTo(base.safeUpperBoundCost())).isGreaterThanOrEqualTo(0);
        assertThat(moreOutput.safeUpperBoundCost().compareTo(base.safeUpperBoundCost())).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("TEXT_ONLY, unavailable count와 tokenizer mismatch는 숫자 비용을 만들지 않는다")
    void rejectInvalidTokenEvidence() {
        PreflightCostEstimator estimator = estimator(standardPlan());
        PreflightPricingContext context = context(FINITE, USD, PricingSnapshot.DEFAULT_CATALOG_VERSION);

        assertUnavailable(
                estimator.estimate(context, counted(100, 120, TokenCountScope.TEXT_ONLY, BASIS), 10),
                PreflightCostUnavailableReason.INCOMPLETE_SCOPE
        );
        assertUnavailable(
                estimator.estimate(context, unavailableCount(), 10),
                PreflightCostUnavailableReason.COUNT_UNAVAILABLE
        );
        assertUnavailable(
                estimator.estimate(
                        context,
                        counted(100, 120, TokenCountScope.REQUEST, new TokenizationBasis("cl100k_base")),
                        10
                ),
                PreflightCostUnavailableReason.INCOMPATIBLE_TOKENIZER
        );
    }

    @Test
    @DisplayName("가격 미등록, 필수 단가 누락과 unbounded pricing은 typed unavailable이다")
    void distinguishUnavailablePricingFromZeroCost() {
        PreflightPricingContext finiteContext = context(
                FINITE,
                USD,
                PricingSnapshot.DEFAULT_CATALOG_VERSION
        );

        assertUnavailable(
                estimator().estimate(finiteContext, requestCount(100, 120), 10),
                PreflightCostUnavailableReason.PRICING_NOT_FOUND
        );
        assertUnavailable(
                estimator(plan(Map.of(TokenType.PROMPT, decimal("0.01"))))
                        .estimate(finiteContext, requestCount(100, 120), 10),
                PreflightCostUnavailableReason.INCOMPLETE_PRICING
        );
        assertUnavailable(
                estimator(standardPlan()).estimate(
                        context(UNBOUNDED, USD, PricingSnapshot.DEFAULT_CATALOG_VERSION),
                        requestCount(100, 120),
                        10
                ),
                PreflightCostUnavailableReason.UNBOUNDED_PRICING
        );
    }

    @Test
    @DisplayName("모든 필수 단가가 명시적인 0인 정책만 0원 bound를 만든다")
    void allowExplicitFreePricingPolicy() {
        PreflightCostEstimator estimator = estimator(plan(Map.of(
                TokenType.PROMPT, BigDecimal.ZERO,
                TokenType.COMPLETION, BigDecimal.ZERO
        )));

        PreflightCostResult.Bounded result = bounded(estimator.estimate(
                context(FINITE, USD, PricingSnapshot.DEFAULT_CATALOG_VERSION),
                requestCount(1_000, 2_000),
                1_000
        ));

        assertThat(result.estimatedCost().value()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.safeUpperBoundCost().value()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("currency와 snapshot identity mismatch를 합산하거나 변환하지 않는다")
    void rejectCurrencyAndSnapshotMismatch() {
        PreflightCostEstimator estimator = estimator(standardPlan());

        assertUnavailable(
                estimator.estimate(
                        context(FINITE, Currency.getInstance("EUR"), PricingSnapshot.DEFAULT_CATALOG_VERSION),
                        requestCount(100, 120),
                        10
                ),
                PreflightCostUnavailableReason.CURRENCY_MISMATCH
        );
        assertUnavailable(
                estimator.estimate(
                        context(FINITE, USD, "catalog-v2"),
                        requestCount(100, 120),
                        10
                ),
                PreflightCostUnavailableReason.PRICING_SNAPSHOT_MISMATCH
        );
    }

    @Test
    @DisplayName("registry 가격이 바뀌어도 문맥에 담긴 snapshot으로 계산한다")
    void usesCapturedPricingSnapshotAfterRegistryUpdate() {
        PricingPlan initialPlan = plan(Map.of(
                TokenType.PROMPT, decimal("10"),
                TokenType.COMPLETION, decimal("10")
        ));
        PricingPlan updatedPlan = plan(Map.of(
                TokenType.PROMPT, decimal("1"),
                TokenType.COMPLETION, decimal("1")
        ));
        PricingProvider provider = () -> Arrays.asList(initialPlan);
        PricingRegistry registry = LedgerComponents.inMemoryPricingRegistry(java.util.List.of(provider));
        PricingSnapshot captured = registry.resolveSnapshot("model-v1", "standard").orElseThrow();
        registry.registerPlan(updatedPlan);

        PreflightPricingContext context = withSnapshot(
                context(FINITE, USD, PricingSnapshot.DEFAULT_CATALOG_VERSION),
                captured
        );
        PreflightCostResult.Bounded result = bounded(
                LedgerComponents.defaultPreflightCostEstimator().estimate(
                        context,
                        requestCount(1_000, 1_000),
                        0
                )
        );

        assertThat(result.safeUpperBoundCost().value()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("계산할 수 없는 BigDecimal 정밀도는 정형 unavailable 결과로 반환한다")
    void returnsUnavailableForUnsupportedDecimalScale() {
        BigDecimal extremeRate = new BigDecimal(BigInteger.ONE, Integer.MAX_VALUE);
        PricingSnapshot snapshot = new PricingSnapshot(
                "model-v1",
                "standard",
                PricingSnapshot.DEFAULT_CATALOG_VERSION,
                Instant.EPOCH,
                Map.of(
                        TokenType.PROMPT, extremeRate,
                        TokenType.COMPLETION, BigDecimal.ZERO
                ),
                USD
        );

        PreflightCostResult result = LedgerComponents.defaultPreflightCostEstimator().estimate(
                withSnapshot(context(FINITE, USD, PricingSnapshot.DEFAULT_CATALOG_VERSION), snapshot),
                requestCount(1, 1),
                0
        );

        assertUnavailable(result, PreflightCostUnavailableReason.ARITHMETIC_FAILURE);
    }

    @Test
    @DisplayName("Long.MAX_VALUE token에서도 overflow 없이 BigDecimal 정밀도를 보존한다")
    void preservePrecisionForVeryLargeTokenCounts() {
        BigDecimal promptRate = decimal("0.12345678901234567890123456789");
        BigDecimal completionRate = decimal("0.98765432109876543210987654321");
        PreflightCostEstimator estimator = estimator(plan(Map.of(
                TokenType.PROMPT, promptRate,
                TokenType.COMPLETION, completionRate
        )));

        PreflightCostResult.Bounded result = bounded(estimator.estimate(
                context(FINITE, USD, PricingSnapshot.DEFAULT_CATALOG_VERSION),
                requestCount(Long.MAX_VALUE, Long.MAX_VALUE),
                Long.MAX_VALUE
        ));
        BigDecimal expected = promptRate.multiply(BigDecimal.valueOf(Long.MAX_VALUE)).movePointLeft(3)
                .add(completionRate.multiply(BigDecimal.valueOf(Long.MAX_VALUE)).movePointLeft(3));

        assertThat(result.estimatedCost().value()).isEqualByComparingTo(expected);
        assertThat(result.safeUpperBoundCost().value()).isEqualByComparingTo(expected);
    }

    @Test
    @DisplayName("reserved output token은 음수일 수 없다")
    void rejectNegativeReservedOutputTokens() {
        PreflightCostEstimator estimator = estimator(standardPlan());

        assertThatThrownBy(() -> estimator.estimate(
                context(FINITE, USD, PricingSnapshot.DEFAULT_CATALOG_VERSION),
                requestCount(100, 120),
                -1
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reservedOutputTokens");
    }

    private static PreflightCostEstimator estimator(PricingPlan... plans) {
        PricingSnapshot snapshot = plans.length == 0
                ? null
                : PricingSnapshot.from(plans[0], PricingSnapshot.DEFAULT_CATALOG_VERSION, Instant.EPOCH);
        PreflightCostEstimator delegate = LedgerComponents.defaultPreflightCostEstimator();
        return (context, requestInput, reservedOutputTokens) -> delegate.estimate(
                withSnapshot(context, snapshot),
                requestInput,
                reservedOutputTokens
        );
    }

    private static PreflightPricingContext withSnapshot(
            PreflightPricingContext context,
            PricingSnapshot snapshot
    ) {
        return new PreflightPricingContext(
                context.canonicalModelId(),
                context.pricingPolicyId(),
                context.catalogVersion(),
                context.tokenizationBasis(),
                context.currency(),
                context.upperBoundCapability(),
                Optional.ofNullable(snapshot)
        );
    }

    private static PricingPlan standardPlan() {
        return plan(Map.of(
                TokenType.PROMPT, decimal("0.01"),
                TokenType.COMPLETION, decimal("0.03")
        ));
    }

    private static PricingPlan plan(Map<TokenType, BigDecimal> rates) {
        return new PricingPlan("model-v1", "standard", rates, USD);
    }

    private static PreflightPricingContext context(
            PreflightPricingContext.UpperBoundCapability capability,
            Currency currency,
            String catalogVersion
    ) {
        return new PreflightPricingContext(
                "model-v1",
                "standard",
                catalogVersion,
                BASIS,
                currency,
                capability
        );
    }

    private static TokenCountResult requestCount(long estimated, long safeUpperBound) {
        return counted(estimated, safeUpperBound, TokenCountScope.REQUEST, BASIS);
    }

    private static TokenCountResult counted(
            long estimated,
            long safeUpperBound,
            TokenCountScope scope,
            TokenizationBasis basis
    ) {
        return TokenCountResult.counted(
                estimated,
                safeUpperBound,
                estimated == safeUpperBound ? TokenCountAccuracy.EXACT : TokenCountAccuracy.HEURISTIC,
                scope,
                ESTIMATOR,
                basis
        );
    }

    private static TokenCountResult unavailableCount() {
        return TokenCountResult.unavailable(
                TokenCountUnavailableReason.ESTIMATOR_UNAVAILABLE,
                TokenCountScope.REQUEST,
                ESTIMATOR,
                BASIS
        );
    }

    private static PreflightCostResult.Bounded bounded(PreflightCostResult result) {
        assertThat(result).isInstanceOf(PreflightCostResult.Bounded.class);
        return (PreflightCostResult.Bounded) result;
    }

    private static void assertUnavailable(
            PreflightCostResult result,
            PreflightCostUnavailableReason reason
    ) {
        assertThat(result).isInstanceOf(PreflightCostResult.Unavailable.class);
        assertThat(((PreflightCostResult.Unavailable) result).reason()).isEqualTo(reason);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
