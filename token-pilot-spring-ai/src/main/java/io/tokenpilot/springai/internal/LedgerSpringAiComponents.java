package io.tokenpilot.springai.internal;

import io.tokenpilot.budget.BudgetEvaluator;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.core.CostCalculator;
import io.tokenpilot.core.LedgerManager;
import io.tokenpilot.core.PricingEvaluator;
import io.tokenpilot.core.PricingMissingListener;
import io.tokenpilot.core.PricingRegistry;
import io.tokenpilot.core.domain.MissingPricingPolicy;
import io.tokenpilot.core.internal.LedgerComponents;
import io.tokenpilot.springai.LedgerAdvisor;
import io.tokenpilot.springai.UsageExtractor;

import java.util.List;

/**
 * Spring AI 어댑터 컴포넌트 생성을 위한 팩토리 클래스입니다.
 */
public final class LedgerSpringAiComponents {

    private LedgerSpringAiComponents() {
    }

    public static UsageExtractor defaultUsageExtractor() {
        return new DefaultUsageExtractor();
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
