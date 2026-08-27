package io.tokenpilot.core.internal;

import io.tokenpilot.core.CostCalculator;
import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.PricingPlan;
import io.tokenpilot.core.domain.PricingResolution;
import io.tokenpilot.core.domain.TokenType;
import io.tokenpilot.core.domain.TokenUsage;
import io.tokenpilot.core.exception.MissingPricingException;

import java.math.BigDecimal;

/**
 * Default cost calculator implementation.
 * Separates cache/reasoning details from inclusive totals into disjoint segments
 * and applies the {@link TokenType} rates without double counting.
 * Calculates per-1K-token prices with an exact decimal shift and does not round internally.
 */
class DefaultCostCalculator implements CostCalculator {
    @Override
    public Cost calculate(TokenUsage usage, PricingPlan plan) {
        long cacheReadInput = countOrZero(usage.details().cacheReadInputTokens());
        long cacheCreationInput = countOrZero(usage.details().cacheCreationInputTokens());
        long reasoningOutput = countOrZero(usage.details().reasoningOutputTokens());

        long regularInput = usage.inputTokens() - cacheReadInput - cacheCreationInput;
        long regularOutput = usage.outputTokens() - reasoningOutput;

        BigDecimal totalCostValue = costFor(regularInput, plan, TokenType.PROMPT)
                .add(costFor(cacheReadInput, plan, TokenType.CACHE_READ_PROMPT))
                .add(costFor(cacheCreationInput, plan, TokenType.CACHE_CREATION_PROMPT))
                .add(costFor(regularOutput, plan, TokenType.COMPLETION))
                .add(costFor(reasoningOutput, plan, TokenType.REASONING));

        return new Cost(totalCostValue, plan.currency());
    }

    private BigDecimal costFor(long count, PricingPlan plan, TokenType tokenType) {
        if (count == 0) {
            return BigDecimal.ZERO;
        }

        PricingResolution resolution = plan.resolveRate(tokenType);
        if (!resolution.isResolved()) {
            throw new MissingPricingException(resolution);
        }

        return plan.getRate(tokenType)
                .multiply(BigDecimal.valueOf(count))
                .movePointLeft(3);
    }

    private long countOrZero(Long count) {
        return count == null ? 0L : count;
    }
}
