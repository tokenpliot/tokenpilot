package io.tokenpilot.core.internal;

import io.tokenpilot.core.CostCalculator;
import io.tokenpilot.core.LedgerListener;
import io.tokenpilot.core.LedgerManager;
import io.tokenpilot.core.PricingEvaluator;
import io.tokenpilot.core.PricingProvider;
import io.tokenpilot.core.PricingRegistry;
import io.tokenpilot.core.PreflightCostEstimator;

import java.util.List;

/**
 * Token Pilot 코어 컴포넌트 생성을 위한 팩토리 클래스입니다.
 */
public final class LedgerComponents {

    private LedgerComponents() {
    }

    public static CostCalculator defaultCostCalculator() {
        return new DefaultCostCalculator();
    }

    public static PricingEvaluator defaultPricingEvaluator() {
        return new DefaultPricingEvaluator();
    }

    /**
     * 기본 보수적 preflight cost estimator를 생성합니다.
     *
     * @param pricingRegistry immutable pricing snapshot 조회에 사용할 registry
     * @return 기본 보수적 preflight cost estimator
     */
    public static PreflightCostEstimator defaultPreflightCostEstimator(PricingRegistry pricingRegistry) {
        return new DefaultPreflightCostEstimator(pricingRegistry);
    }

    public static PricingRegistry inMemoryPricingRegistry(List<PricingProvider> providers) {
        return new InMemoryPricingRegistry(providers);
    }

    public static LedgerManager defaultLedgerManager(
            PricingRegistry pricingRegistry,
            CostCalculator costCalculator,
            List<LedgerListener> listeners
    ) {
        return new DefaultLedgerManager(pricingRegistry, costCalculator, listeners);
    }
}
