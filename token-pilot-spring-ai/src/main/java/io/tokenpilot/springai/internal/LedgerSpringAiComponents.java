package io.tokenpilot.springai.internal;

import io.tokenpilot.budget.BudgetEvaluator;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.budget.IdempotencyKey;
import io.tokenpilot.budget.ReservationAccounting;
import io.tokenpilot.core.CostCalculator;
import io.tokenpilot.core.LedgerManager;
import io.tokenpilot.core.ModelRegistry;
import io.tokenpilot.core.PreflightCostEstimator;
import io.tokenpilot.core.PricingEvaluator;
import io.tokenpilot.core.PricingMissingListener;
import io.tokenpilot.core.PricingRegistry;
import io.tokenpilot.core.TokenBudget;
import io.tokenpilot.core.TokenEstimator;
import io.tokenpilot.core.domain.MissingPricingPolicy;
import io.tokenpilot.core.internal.LedgerComponents;
import io.tokenpilot.springai.LedgerAdvisor;
import io.tokenpilot.springai.ReservedOutputTokensResolver;
import io.tokenpilot.springai.UsageExtractor;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

import java.util.List;
import java.util.Objects;

/**
 * Factory for creating Spring AI adapter components.
 */
public final class LedgerSpringAiComponents {

    private LedgerSpringAiComponents() {
    }

    public static UsageExtractor defaultUsageExtractor() {
        return new DefaultUsageExtractor();
    }

    public static LedgerAdvisor accountingLedgerAdvisor(
            UsageExtractor usageExtractor,
            BudgetEvaluator budgetEvaluator,
            BudgetStateStore budgetStateStore,
            ReservationAccounting reservationAccounting,
            PricingRegistry pricingRegistry,
            ModelRegistry modelRegistry,
            TokenEstimator tokenEstimator,
            TokenBudget tokenBudget,
            PreflightCostEstimator costEstimator,
            @Nullable String defaultModelId,
            @Nullable Long defaultReservedOutputTokens,
            long framingHeadroomTokens
    ) {
        return accountingLedgerAdvisor(
                usageExtractor,
                budgetEvaluator,
                budgetStateStore,
                reservationAccounting,
                pricingRegistry,
                modelRegistry,
                tokenEstimator,
                tokenBudget,
                costEstimator,
                defaultModelId,
                defaultReservedOutputTokens,
                null,
                framingHeadroomTokens
        );
    }

    public static LedgerAdvisor accountingLedgerAdvisor(
            UsageExtractor usageExtractor,
            BudgetEvaluator budgetEvaluator,
            BudgetStateStore budgetStateStore,
            ReservationAccounting reservationAccounting,
            PricingRegistry pricingRegistry,
            ModelRegistry modelRegistry,
            TokenEstimator tokenEstimator,
            TokenBudget tokenBudget,
            PreflightCostEstimator costEstimator,
            @Nullable String defaultModelId,
            @Nullable Long defaultReservedOutputTokens,
            @Nullable ReservedOutputTokensResolver providerOutputResolver,
            long framingHeadroomTokens
    ) {
        return accountingLedgerAdvisor(
                usageExtractor,
                budgetEvaluator,
                budgetStateStore,
                reservationAccounting,
                pricingRegistry,
                modelRegistry,
                tokenEstimator,
                tokenBudget,
                costEstimator,
                LedgerComponents.defaultPricingEvaluator(),
                List.of(),
                defaultModelId,
                defaultReservedOutputTokens,
                providerOutputResolver,
                framingHeadroomTokens
        );
    }

    public static LedgerAdvisor accountingLedgerAdvisor(
            UsageExtractor usageExtractor,
            BudgetEvaluator budgetEvaluator,
            BudgetStateStore budgetStateStore,
            ReservationAccounting reservationAccounting,
            PricingRegistry pricingRegistry,
            ModelRegistry modelRegistry,
            TokenEstimator tokenEstimator,
            TokenBudget tokenBudget,
            PreflightCostEstimator costEstimator,
            PricingEvaluator pricingEvaluator,
            List<PricingMissingListener> pricingMissingListeners,
            @Nullable String defaultModelId,
            @Nullable Long defaultReservedOutputTokens,
            @Nullable ReservedOutputTokensResolver providerOutputResolver,
            long framingHeadroomTokens
    ) {
        if (Objects.requireNonNull(
                budgetStateStore,
                "budgetStateStore must not be null"
        ) != Objects.requireNonNull(
                reservationAccounting,
                "reservationAccounting must not be null"
        )) {
            throw new IllegalArgumentException(
                    "budgetStateStore and reservationAccounting must be the same instance"
            );
        }
        ReservedOutputResolver outputResolver = new ReservedOutputResolver(
                providerOutputResolver,
                defaultReservedOutputTokens
        );
        RequestContextAccessor contextAccessor = new RequestContextAccessor();
        RequestPreflight preflight = new RequestPreflight(
                new ModelResolver(modelRegistry, defaultModelId),
                outputResolver,
                tokenEstimator,
                tokenBudget,
                pricingRegistry,
                pricingEvaluator,
                costEstimator,
                framingHeadroomTokens
        );
        return new DefaultLedgerAdvisor(
                usageExtractor,
                budgetEvaluator,
                budgetStateStore,
                reservationAccounting,
                preflight,
                pricingEvaluator,
                contextAccessor,
                new IdempotencyKeyResolver(
                        contextAccessor,
                        () -> new IdempotencyKey(UUID.randomUUID().toString())
                ),
                pricingMissingListeners
        );
    }

    public static LedgerAdvisor defaultLedgerAdvisor(
            LedgerManager ledgerManager,
            UsageExtractor usageExtractor
    ) {
        return new DefaultLedgerAdvisor(ledgerManager, usageExtractor);
    }

    public static LedgerAdvisor defaultLedgerAdvisor(
            LedgerManager ledgerManager,
            UsageExtractor usageExtractor,
            CostCalculator costCalculator,
            PricingRegistry pricingRegistry
    ) {
        return new DefaultLedgerAdvisor(
                ledgerManager,
                usageExtractor,
                null,
                null,
                costCalculator,
                pricingRegistry,
                MissingPricingPolicy.FAIL_OPEN
        );
    }

    public static LedgerAdvisor defaultLedgerAdvisor(
            LedgerManager ledgerManager,
            UsageExtractor usageExtractor,
            CostCalculator costCalculator,
            PricingRegistry pricingRegistry,
            PricingEvaluator pricingEvaluator
    ) {
        return defaultLedgerAdvisor(
                ledgerManager,
                usageExtractor,
                null,
                null,
                costCalculator,
                pricingRegistry,
                pricingEvaluator,
                MissingPricingPolicy.FAIL_OPEN
        );
    }

    public static LedgerAdvisor defaultLedgerAdvisor(
            LedgerManager ledgerManager,
            UsageExtractor usageExtractor,
            BudgetEvaluator budgetEvaluator,
            BudgetStateStore budgetStateStore,
            CostCalculator costCalculator,
            PricingRegistry pricingRegistry
    ) {
        return defaultLedgerAdvisor(
                ledgerManager,
                usageExtractor,
                budgetEvaluator,
                budgetStateStore,
                costCalculator,
                pricingRegistry,
                MissingPricingPolicy.FAIL_OPEN
        );
    }

    public static LedgerAdvisor defaultLedgerAdvisor(
            LedgerManager ledgerManager,
            UsageExtractor usageExtractor,
            BudgetEvaluator budgetEvaluator,
            BudgetStateStore budgetStateStore,
            CostCalculator costCalculator,
            PricingRegistry pricingRegistry,
            MissingPricingPolicy missingPricingPolicy
    ) {
        return defaultLedgerAdvisor(
                ledgerManager,
                usageExtractor,
                budgetEvaluator,
                budgetStateStore,
                costCalculator,
                pricingRegistry,
                LedgerComponents.defaultPricingEvaluator(),
                missingPricingPolicy
        );
    }

    public static LedgerAdvisor defaultLedgerAdvisor(
            LedgerManager ledgerManager,
            UsageExtractor usageExtractor,
            BudgetEvaluator budgetEvaluator,
            BudgetStateStore budgetStateStore,
            CostCalculator costCalculator,
            PricingRegistry pricingRegistry,
            PricingEvaluator pricingEvaluator,
            MissingPricingPolicy missingPricingPolicy
    ) {
        return defaultLedgerAdvisor(
                ledgerManager,
                usageExtractor,
                budgetEvaluator,
                budgetStateStore,
                costCalculator,
                pricingRegistry,
                pricingEvaluator,
                missingPricingPolicy,
                List.of()
        );
    }

    public static LedgerAdvisor defaultLedgerAdvisor(
            LedgerManager ledgerManager,
            UsageExtractor usageExtractor,
            BudgetEvaluator budgetEvaluator,
            BudgetStateStore budgetStateStore,
            CostCalculator costCalculator,
            PricingRegistry pricingRegistry,
            PricingEvaluator pricingEvaluator,
            MissingPricingPolicy missingPricingPolicy,
            List<PricingMissingListener> pricingMissingListeners
    ) {
        return new DefaultLedgerAdvisor(
                ledgerManager,
                usageExtractor,
                budgetEvaluator,
                budgetStateStore,
                costCalculator,
                pricingRegistry,
                pricingEvaluator,
                missingPricingPolicy,
                pricingMissingListeners
        );
    }
}
