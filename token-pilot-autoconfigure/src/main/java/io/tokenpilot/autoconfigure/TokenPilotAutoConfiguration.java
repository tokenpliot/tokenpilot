package io.tokenpilot.autoconfigure;

import io.tokenpilot.core.CostCalculator;
import io.tokenpilot.core.LedgerListener;
import io.tokenpilot.core.LedgerManager;
import io.tokenpilot.core.ModelRegistry;
import io.tokenpilot.core.PreflightDecisionListener;
import io.tokenpilot.core.PricingEvaluator;
import io.tokenpilot.core.PricingProvider;
import io.tokenpilot.core.PricingRegistry;
import io.tokenpilot.core.TokenBudget;
import io.tokenpilot.core.TokenEstimator;
import io.tokenpilot.core.CoreComponents;
import io.tokenpilot.core.internal.LedgerComponents;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Token Pilot 라이브러리의 자동 설정을 담당하는 클래스.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "token-pilot", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TokenPilotProperties.class)
public class TokenPilotAutoConfiguration {

    /**
     * 외부 설정으로부터 가격 정책을 읽어오는 PricingProvider를 등록합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    public PricingProvider pricingProvider(TokenPilotProperties properties) {
        var plans = properties.toPricingPlans();
        return () -> plans;
    }

    /**
     * 메모리 기반의 가격 정책 저장소(PricingRegistry)를 등록합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    public PricingRegistry pricingRegistry(ObjectProvider<PricingProvider> pricingProviders) {
        return LedgerComponents.inMemoryPricingRegistry(pricingProviders.orderedStream().toList());
    }

    /**
     * 토큰 사용량을 바탕으로 비용을 계산하는 CostCalculator를 등록합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    public CostCalculator costCalculator() {
        return LedgerComponents.defaultCostCalculator();
    }

    /**
     * Pricing snapshot rate와 actual model 정합성을 평가하는 정책을 등록합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    public PricingEvaluator pricingEvaluator() {
        return LedgerComponents.defaultPricingEvaluator();
    }

    /**
     * 버전이 고정된 기본 model metadata catalog를 등록합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelRegistry modelRegistry() {
        return CoreComponents.defaultModelRegistry();
    }

    /**
     * framework-independent UTF-8 휴리스틱 estimator를 등록합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    public TokenEstimator tokenEstimator() {
        return CoreComponents.utf8ByteHeuristicTokenEstimator();
    }

    /**
     * context admission 결과를 등록된 observer와 함께 발행합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    public TokenBudget tokenBudget(
            ModelRegistry modelRegistry,
            ObjectProvider<PreflightDecisionListener> decisionListeners
    ) {
        return CoreComponents.tokenBudget(
                modelRegistry,
                decisionListeners.orderedStream().toList()
        );
    }

    /**
     * 비용 기록 및 리스너 관리를 담당하는 LedgerManager를 등록합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    public LedgerManager ledgerManager(PricingRegistry pricingRegistry,
                                       CostCalculator costCalculator,
                                       ObjectProvider<LedgerListener> ledgerListeners) {
        return LedgerComponents.defaultLedgerManager(
                pricingRegistry,
                costCalculator,
                ledgerListeners.orderedStream()
                               .toList()
        );
    }

}
