package io.tokenpilot.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.tokenpilot.budget.BudgetEvaluator;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.budget.internal.LedgerBudgetComponents;
import io.tokenpilot.core.CostCalculator;
import io.tokenpilot.core.LedgerListener;
import io.tokenpilot.core.LedgerManager;
import io.tokenpilot.core.PricingProvider;
import io.tokenpilot.core.PricingRegistry;
import io.tokenpilot.core.internal.LedgerComponents;
import io.tokenpilot.micrometer.internal.LedgerMicrometerComponents;
import io.tokenpilot.springai.LedgerAdvisor;
import io.tokenpilot.springai.UsageExtractor;
import io.tokenpilot.springai.internal.LedgerSpringAiComponents;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import io.tokenpilot.notification.BudgetNotificationHandler;
import io.tokenpilot.notification.BudgetNotificationService;
import io.tokenpilot.notification.InMemoryNotificationStateStore;
import io.tokenpilot.notification.NotificationStateStore;

import java.time.Clock;

/**
 * Token Pilot 라이브러리의 자동 설정을 담당하는 클래스.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration"
})
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

    /**
     * ChatClientResponse에서 토큰 사용량을 추출하는 UsageExtractor를 등록합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass({ChatClient.class, LedgerSpringAiComponents.class})
    public UsageExtractor usageExtractor() {
        return LedgerSpringAiComponents.defaultUsageExtractor();
    }

    /**
     * Spring AI 호출 전후로 비용을 측정하고 기록하는 LedgerAdvisor를 등록합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass({ChatClient.class, LedgerSpringAiComponents.class})
    public LedgerAdvisor ledgerAdvisor(
            LedgerManager ledgerManager,
            UsageExtractor usageExtractor,
            ObjectProvider<BudgetEvaluator> budgetEvaluator,
            ObjectProvider<BudgetStateStore> budgetStateStore,
            CostCalculator costCalculator,
            PricingRegistry pricingRegistry
    ) {
        BudgetEvaluator evaluator = budgetEvaluator.getIfAvailable();
        BudgetStateStore stateStore = budgetStateStore.getIfAvailable();

        if (evaluator != null && stateStore != null) {
            return LedgerSpringAiComponents.defaultLedgerAdvisor(
                    ledgerManager,
                    usageExtractor,
                    evaluator,
                    stateStore,
                    costCalculator,
                    pricingRegistry
            );
        }

        return LedgerSpringAiComponents.defaultLedgerAdvisor(ledgerManager, usageExtractor);
    }

    /**
     * LedgerAdvisor가 빈으로 등록되어 있을 경우, ChatClient.Builder를 위한 커스터마이저를 생성합니다.
     */
    @Bean
    @ConditionalOnBean(LedgerAdvisor.class)
    @ConditionalOnClass(ChatClient.class)
    public LedgerChatClientBuilderCustomizer ledgerChatClientBuilderCustomizer(LedgerAdvisor ledgerAdvisor) {
        return new LedgerChatClientBuilderCustomizer(ledgerAdvisor);
    }

    /**
     * Micrometer 메트릭 발행을 위한 리스너를 등록합니다.
     */
    @Bean
    @ConditionalOnClass({MeterRegistry.class, LedgerMicrometerComponents.class})
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "token-pilot.metrics", name = "enabled", havingValue = "true", matchIfMissing = true)
    public LedgerListener microCostMetricsPublisher(MeterRegistry meterRegistry, TokenPilotProperties properties) {
        return LedgerMicrometerComponents.microCostMetricsPublisher(meterRegistry, properties.getMetrics()
                                                                                             .getTagWhitelist());
    }

    /**
     * 예산 관리 모듈이 있을 경우 기본 예산 저장소를 등록합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(LedgerBudgetComponents.class)
    @ConditionalOnProperty(prefix = "token-pilot.budget", name = "enabled", havingValue = "true")
    public BudgetStateStore budgetStateStore() {
        return LedgerBudgetComponents.inMemoryBudgetStateStore();
    }

    /**
     * 예산 관리 모듈이 있을 경우 예산 평가기를 등록합니다.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(LedgerBudgetComponents.class)
    @ConditionalOnProperty(prefix = "token-pilot.budget", name = "enabled", havingValue = "true")
    public BudgetEvaluator budgetEvaluator(
        BudgetStateStore budgetStateStore,
        TokenPilotProperties properties,
        ObjectProvider<Clock> clock
    ) {
        return LedgerBudgetComponents.defaultBudgetEvaluator(
            budgetStateStore,
            properties.toBudgetPolicy(),
            clock.getIfAvailable(Clock::systemUTC)
        );
    }

    /**
     * 알림 상태 저장소를 등록합니다.
     * - 중복 알림 방지를 위해 window 단위로 상태를 저장
     * - token-pilot.notification.enabled=true 일 때만 등록
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "token-pilot.notification", name = "enabled", havingValue = "true")
    public NotificationStateStore notificationStateStore() {
        return new InMemoryNotificationStateStore();
    }

    /**
     * 예산 알림 서비스를 등록합니다.
     * - BudgetNotificationHandler 빈이 있을 때만 등록
     * - 없으면 no-op으로 동작 (알림 서비스 자체가 등록되지 않음)
     * - token-pilot.notification.enabled=true 일 때만 등록
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(BudgetNotificationHandler.class)
    @ConditionalOnProperty(prefix = "token-pilot.notification", name = "enabled", havingValue = "true")
    public BudgetNotificationService budgetNotificationService(
        BudgetNotificationHandler handler,
        NotificationStateStore notificationStateStore
    ) {
        return new BudgetNotificationService(handler, notificationStateStore);
    }
}
