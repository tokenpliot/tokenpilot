package io.tokenpilot.core.internal;

import io.tokenpilot.core.PreflightCostEstimator;
import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.PreflightCostResult;
import io.tokenpilot.core.domain.PreflightCostUnavailableReason;
import io.tokenpilot.core.domain.PreflightPricingContext;
import io.tokenpilot.core.domain.PricingSnapshot;
import io.tokenpilot.core.domain.TokenCountResult;
import io.tokenpilot.core.domain.TokenCountScope;
import io.tokenpilot.core.domain.TokenType;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * 불변 pricing snapshot의 배타적인 입력/출력 가격 경로에서 각각 최대 단가를 선택해
 * 같은 token을 중복 합산하지 않고 보수적인 비용 상한을 계산하는 기본 구현입니다.
 *
 * <p>이 구현은 비용만 계산하며 context admission이나 provider 호출을 허가하지 않습니다.
 * 입력 문맥이 보관한 동일한 snapshot만 사용하며, 계산 중 registry를 다시 조회하지
 * 않습니다. 호출 계층은 그 snapshot을 예약·정산에도 그대로 전달해야 합니다.</p>
 */
class DefaultPreflightCostEstimator implements PreflightCostEstimator {

    private static final int TOKENS_PER_K_SHIFT = 3;

    @Override
    public PreflightCostResult estimate(
            PreflightPricingContext pricingContext,
            TokenCountResult requestInput,
            long reservedOutputTokens
    ) {
        Objects.requireNonNull(pricingContext, "pricingContext must not be null");
        Objects.requireNonNull(requestInput, "requestInput must not be null");
        if (reservedOutputTokens < 0) {
            throw new IllegalArgumentException("reservedOutputTokens must be non-negative");
        }

        if (!requestInput.isCounted()) {
            return unavailable(
                    PreflightCostUnavailableReason.COUNT_UNAVAILABLE,
                    pricingContext,
                    requestInput,
                    reservedOutputTokens
            );
        }
        if (requestInput.scope() != TokenCountScope.REQUEST) {
            return unavailable(
                    PreflightCostUnavailableReason.INCOMPLETE_SCOPE,
                    pricingContext,
                    requestInput,
                    reservedOutputTokens
            );
        }
        if (!pricingContext.tokenizationBasis().equals(requestInput.tokenizationBasis())) {
            return unavailable(
                    PreflightCostUnavailableReason.INCOMPATIBLE_TOKENIZER,
                    pricingContext,
                    requestInput,
                    reservedOutputTokens
            );
        }
        if (pricingContext.upperBoundCapability() == PreflightPricingContext.UpperBoundCapability.UNBOUNDED) {
            return unavailable(
                    PreflightCostUnavailableReason.UNBOUNDED_PRICING,
                    pricingContext,
                    requestInput,
                    reservedOutputTokens
            );
        }

        if (pricingContext.pricingSnapshot().isEmpty()) {
            return unavailable(
                    PreflightCostUnavailableReason.PRICING_NOT_FOUND,
                    pricingContext,
                    requestInput,
                    reservedOutputTokens
            );
        }

        PricingSnapshot snapshot = pricingContext.pricingSnapshot().orElseThrow();
        if (!snapshot.modelId().equals(pricingContext.canonicalModelId())
                || !snapshot.pricingPolicyId().equals(pricingContext.pricingPolicyId())
                || !snapshot.catalogVersion().equals(pricingContext.catalogVersion())) {
            return unavailable(
                    PreflightCostUnavailableReason.PRICING_SNAPSHOT_MISMATCH,
                    pricingContext,
                    requestInput,
                    reservedOutputTokens
            );
        }
        if (!snapshot.currency().equals(pricingContext.currency())) {
            return unavailable(
                    PreflightCostUnavailableReason.CURRENCY_MISMATCH,
                    pricingContext,
                    requestInput,
                    reservedOutputTokens
            );
        }

        Map<TokenType, BigDecimal> rates = snapshot.rates();
        BigDecimal promptRate = rates.get(TokenType.PROMPT);
        BigDecimal completionRate = rates.get(TokenType.COMPLETION);
        if (promptRate == null || completionRate == null) {
            return unavailable(
                    PreflightCostUnavailableReason.INCOMPLETE_PRICING,
                    pricingContext,
                    requestInput,
                    reservedOutputTokens
            );
        }

        long inputEstimatedTokens = requestInput.tokens().orElseThrow();
        long inputSafeUpperBoundTokens = requestInput.safeUpperBoundTokens().orElseThrow();

        BigDecimal conservativeInputRate = maxRate(
                promptRate,
                rates.getOrDefault(TokenType.CACHE_READ_PROMPT, promptRate),
                rates.getOrDefault(TokenType.CACHE_CREATION_PROMPT, promptRate)
        );
        BigDecimal conservativeOutputRate = maxRate(
                completionRate,
                rates.getOrDefault(TokenType.REASONING, completionRate)
        );

        Cost estimatedCost;
        Cost safeUpperBoundCost;
        try {
            estimatedCost = new Cost(
                    costFor(inputEstimatedTokens, promptRate)
                            .add(costFor(reservedOutputTokens, completionRate)),
                    snapshot.currency()
            );
            safeUpperBoundCost = new Cost(
                    costFor(inputSafeUpperBoundTokens, conservativeInputRate)
                            .add(costFor(reservedOutputTokens, conservativeOutputRate)),
                    snapshot.currency()
            );
        } catch (ArithmeticException exception) {
            return unavailable(
                    PreflightCostUnavailableReason.ARITHMETIC_FAILURE,
                    pricingContext,
                    requestInput,
                    reservedOutputTokens
            );
        }

        return new PreflightCostResult.Bounded(
                estimatedCost,
                safeUpperBoundCost,
                inputEstimatedTokens,
                inputSafeUpperBoundTokens,
                reservedOutputTokens,
                snapshot.modelId(),
                snapshot.pricingPolicyId(),
                snapshot.catalogVersion(),
                snapshot,
                requestInput.estimatorDescriptor(),
                requestInput.tokenizationBasis()
        );
    }

    private PreflightCostResult.Unavailable unavailable(
            PreflightCostUnavailableReason reason,
            PreflightPricingContext pricingContext,
            TokenCountResult requestInput,
            long reservedOutputTokens
    ) {
        return new PreflightCostResult.Unavailable(
                reason,
                pricingContext.canonicalModelId(),
                pricingContext.pricingPolicyId(),
                pricingContext.catalogVersion(),
                pricingContext.currency(),
                reservedOutputTokens,
                requestInput.estimatorDescriptor(),
                requestInput.tokenizationBasis()
        );
    }

    private BigDecimal maxRate(BigDecimal first, BigDecimal... remaining) {
        BigDecimal maximum = first;
        for (BigDecimal candidate : remaining) {
            maximum = maximum.max(candidate);
        }
        return maximum;
    }

    private BigDecimal costFor(long tokens, BigDecimal ratePerK) {
        return ratePerK.multiply(BigDecimal.valueOf(tokens)).movePointLeft(TOKENS_PER_K_SHIFT);
    }
}
