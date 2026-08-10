package io.tokenpilot.core.internal;

import io.tokenpilot.core.CostCalculator;
import io.tokenpilot.core.LedgerListener;
import io.tokenpilot.core.LedgerManager;
import io.tokenpilot.core.PricingEvaluator;
import io.tokenpilot.core.PricingProvider;
import io.tokenpilot.core.PricingRegistry;
import io.tokenpilot.core.PreflightCostEstimator;
import io.tokenpilot.core.TokenEstimator;

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
     * 기본 preflight 비용 상한 계산기를 생성합니다.
     *
     * <p>계산기는 REQUEST 범위의 token 결과와 하나의 불변 pricing snapshot을
     * 보관한 문맥을 요구합니다. 이 factory는 context admission이나 atomic reservation을
     * 대신 수행하지 않습니다.</p>
     *
     * @return 기본 preflight 비용 상한 계산기
     */
    public static PreflightCostEstimator defaultPreflightCostEstimator() {
        return new DefaultPreflightCostEstimator();
    }

    /**
     * UTF-8 byte 길이 기반의 text-only 휴리스틱 token estimator를 생성합니다.
     *
     * @return UTF-8 byte 휴리스틱 estimator
     */
    public static TokenEstimator utf8ByteHeuristicTokenEstimator() {
        return new HeuristicTokenEstimator();
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
