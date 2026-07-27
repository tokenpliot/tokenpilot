package io.tokenpilot.autoconfigure;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.tokenpilot.budget.BudgetDecision;
import io.tokenpilot.budget.BudgetEvaluator;
import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetState;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.budget.BudgetThreshold;
import io.tokenpilot.budget.BudgetWindow;
import io.tokenpilot.core.CostCalculator;
import io.tokenpilot.core.LedgerManager;
import io.tokenpilot.core.PricingProvider;
import io.tokenpilot.core.PricingRegistry;
import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.PricingPlan;
import io.tokenpilot.core.domain.TokenUsage;
import io.tokenpilot.notification.BudgetNotificationHandler;
import io.tokenpilot.notification.BudgetNotificationService;
import io.tokenpilot.notification.NotificationStateStore;
import io.tokenpilot.springai.LedgerAdvisor;
import io.tokenpilot.springai.UsageExtractor;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static io.tokenpilot.core.domain.TokenType.COMPLETION;
import static io.tokenpilot.core.domain.TokenType.PROMPT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TokenPilotAutoConfigurationTest {

    private static final String PREFIX = "token-pilot.pricing.plans[0]";
    private static final String PROP_MODEL_ID = PREFIX + ".model-id";
    private static final String PROP_PROMPT = PREFIX + ".rates.PROMPT";
    private static final String PROP_COMPLETION = PREFIX + ".rates.COMPLETION";
    private static final String PROP_CURRENCY = PREFIX + ".currency";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TokenPilotAutoConfiguration.class));

    @Test
    @DisplayName("기본 설정에서 Core 및 Spring AI 빈은 등록되고, Budget 빈은 등록되지 않아야 한다")
    void shouldRegisterDefaultBeans() {
        this.contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PricingProvider.class);
            assertThat(context).hasSingleBean(PricingRegistry.class);
            assertThat(context).hasSingleBean(CostCalculator.class);
            assertThat(context).hasSingleBean(LedgerManager.class);

            assertThat(context).hasSingleBean(UsageExtractor.class);
            assertThat(context).hasSingleBean(LedgerAdvisor.class);
            assertThat(context).hasSingleBean(LedgerChatClientBuilderCustomizer.class);

            assertThat(context).doesNotHaveBean(BudgetStateStore.class);
            assertThat(context).doesNotHaveBean(BudgetEvaluator.class);
            assertThat(context).doesNotHaveBean(NotificationStateStore.class);
            assertThat(context).doesNotHaveBean(BudgetNotificationService.class);
        });
    }

    @Test
    @DisplayName("설정 값이 없을 경우 빈 목록을 가진 PricingProvider가 생성되어야 한다")
    void shouldRegisterDefaultPricingProviderWhenNoProperties() {
        this.contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PricingProvider.class);
            PricingProvider provider = context.getBean(PricingProvider.class);
            assertThat(provider.getAllPlans()).isEmpty();
        });
    }

    @ParameterizedTest(name = "[{index}] {argumentSetName}")
    @MethodSource("providePricingConfigs")
    @DisplayName("설정 프로퍼티가 PricingProvider 빈과 환경에 정확하게 바인딩되어야 한다")
    void shouldBindPropertiesToEnvironmentAndBean(
        String modelId,
        String promptRate,
        String completionRate,
        String currency
    ) {
        this.contextRunner
            .withPropertyValues(buildProperties(
                modelId,
                promptRate,
                completionRate,
                currency
            ))
            .run(context -> {
                assertThat(context).hasSingleBean(PricingProvider.class);

                var env = context.getEnvironment();
                var plans = context.getBean(PricingProvider.class)
                    .getAllPlans();

                SoftAssertions.assertSoftly(softly -> {
                    softly.assertThat(env.getProperty(PROP_MODEL_ID))
                        .isEqualTo(modelId);
                    softly.assertThat(env.getProperty(PROP_PROMPT))
                        .isEqualTo(promptRate);
                    softly.assertThat(env.getProperty(PROP_COMPLETION))
                        .isEqualTo(completionRate);
                    softly.assertThat(env.getProperty(PROP_CURRENCY))
                        .isEqualTo(currency);

                    softly.assertThat(plans)
                        .hasSize(1);

                    PricingPlan plan = plans.iterator()
                        .next();

                    softly.assertThat(plan.modelId())
                        .isEqualTo(modelId);
                    softly.assertThat(plan.currency()
                            .getCurrencyCode())
                        .isEqualTo(currency);
                    softly.assertThat(plan.getRate(PROMPT))
                        .isEqualByComparingTo(promptRate);
                    softly.assertThat(plan.getRate(COMPLETION))
                        .isEqualByComparingTo(completionRate);
                });
            });
    }

    @Test
    @DisplayName("설정된 가격 정책이 PricingRegistry와 LedgerManager 비용 계산에 연결되어야 한다")
    void shouldRegisterConfiguredPricingPlansInRegistry() {
        this.contextRunner
            .withPropertyValues(buildProperties(
                "gpt-4o",
                "0.005",
                "0.015",
                "USD"
            ))
            .run(context -> {
                PricingRegistry pricingRegistry = context.getBean(PricingRegistry.class);
                LedgerManager ledgerManager = context.getBean(LedgerManager.class);

                var plan = pricingRegistry.getPlan("gpt-4o");
                Cost cost = ledgerManager.record(
                    "gpt-4o",
                    TokenUsage.from(1_000, 2_000),
                    Map.of()
                );

                SoftAssertions.assertSoftly(softly -> {
                    softly.assertThat(plan)
                        .isPresent();
                    softly.assertThat(plan.orElseThrow()
                            .getRate(PROMPT))
                        .isEqualByComparingTo("0.005");
                    softly.assertThat(plan.orElseThrow()
                            .getRate(COMPLETION))
                        .isEqualByComparingTo("0.015");
                    softly.assertThat(cost.value())
                        .isEqualByComparingTo("0.035000");
                    softly.assertThat(cost.currency()
                            .getCurrencyCode())
                        .isEqualTo("USD");
                });
            });
    }

    @Test
    @DisplayName("token-pilot.budget.enabled=true 일 때 Budget 관련 빈이 등록되어야 한다")
    void shouldRegisterBudgetBeansWhenEnabled() {
        this.contextRunner
            .withPropertyValues("token-pilot.budget.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(BudgetStateStore.class);
                assertThat(context).hasSingleBean(BudgetEvaluator.class);
            });
    }

    @Test
    @DisplayName("Budget 빈은 사용자 Clock 기준의 월별 window를 공유해야 한다")
    void shouldUseUserClockForMonthlyBudgetWindow() {
        this.contextRunner
            .withUserConfiguration(FixedClockConfiguration.class)
            .withPropertyValues(
                "token-pilot.budget.enabled=true",
                "token-pilot.budget.monthly-limit=100.00",
                "token-pilot.budget.zone-id=Asia/Seoul"
            )
            .run(context -> {
                BudgetStateStore store = context.getBean(BudgetStateStore.class);
                BudgetEvaluator evaluator = context.getBean(BudgetEvaluator.class);
                Map<String, String> tags = Map.of("tenant_id", "tenant-clock");
                BudgetDecision initial = evaluator.evaluate(tags);
                BudgetKey july = new BudgetKey(
                    initial.key().policyId(),
                    initial.key().targetType(),
                    initial.key().targetId(),
                    BudgetWindow.parse("2026-07")
                );

                store.addCost(
                    july,
                    initial.limit(),
                    Cost.of(new BigDecimal("100.00"), initial.limit().currency())
                );
                store.addCost(
                    initial.key(),
                    initial.limit(),
                    Cost.of(new BigDecimal("50.00"), initial.limit().currency())
                );

                BudgetDecision decision = evaluator.evaluate(tags);

                assertThat(decision.key().window()).isEqualTo(BudgetWindow.parse("2026-08"));
                assertThat(decision.threshold()).isEqualTo(BudgetThreshold.HALF);
                assertThat(decision.currentUsage().value()).isEqualByComparingTo("50.00");
            });
    }

    @Test
    @DisplayName("Budget가 활성화되면 LedgerAdvisor가 BudgetEvaluator를 사용해야 한다")
    void shouldWireBudgetEvaluatorIntoLedgerAdvisorWhenBudgetEnabled() {
        this.contextRunner
            .withUserConfiguration(RecordingBudgetEvaluatorConfiguration.class)
            .withPropertyValues("token-pilot.budget.enabled=true")
            .run(context -> {
                LedgerAdvisor advisor = context.getBean(LedgerAdvisor.class);
                RecordingBudgetEvaluator evaluator = context.getBean(RecordingBudgetEvaluator.class);

                ChatClientRequest request = new ChatClientRequest(
                    new Prompt("test"),
                    Map.of("tenant_id", "tenant-abc")
                );

                advisor.before(request, mock(AdvisorChain.class));

                SoftAssertions.assertSoftly(softly -> {
                    softly.assertThat(evaluator.evaluateCalls())
                        .isEqualTo(1);
                    softly.assertThat(evaluator.lastTags())
                        .containsEntry("tenant_id", "tenant-abc");
                });
            });
    }

    @Test
    @DisplayName("token-pilot.budget.enabled=false 일 때 Budget 관련 빈이 등록되지 않아야 한다")
    void shouldNotRegisterBudgetBeansWhenDisabled() {
        this.contextRunner
            .withPropertyValues("token-pilot.budget.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(BudgetStateStore.class);
                assertThat(context).doesNotHaveBean(BudgetEvaluator.class);
            });
    }

    @Test
    @DisplayName("MeterRegistry가 존재할 때 Micrometer 관련 빈이 등록되어야 한다")
    void shouldRegisterMicrometerBeanWhenMeterRegistryExists() {
        this.contextRunner
            .withUserConfiguration(MeterRegistryConfiguration.class)
            .run(context -> {
                assertThat(context).hasBean("microCostMetricsPublisher");
            });
    }

    @Test
    @DisplayName("token-pilot.metrics.enabled=false 일 때 Micrometer 관련 빈이 등록되지 않아야 한다")
    void shouldNotRegisterMicrometerBeanWhenMetricsDisabled() {
        this.contextRunner
            .withUserConfiguration(MeterRegistryConfiguration.class)
            .withPropertyValues("token-pilot.metrics.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean("microCostMetricsPublisher");
            });
    }

    @Test
    @DisplayName("사용자 정의 빈이 있으면 자동 설정 빈이 덮어쓰지 않아야 한다")
    void shouldNotOverrideUserDefinedBeans() {
        this.contextRunner
            .withUserConfiguration(UserCustomConfiguration.class)
            .run(context -> {
                assertThat(context).hasSingleBean(PricingRegistry.class);
                assertThat(context.getBean(PricingRegistry.class))
                    .isInstanceOf(UserCustomPricingRegistry.class);
            });
    }

    @Test
    @DisplayName("token-pilot.notification.enabled=false 일 때 Notification 관련 빈이 등록되지 않아야 한다")
    void shouldNotRegisterNotificationBeansWhenDisabled() {
        this.contextRunner
            .withPropertyValues("token-pilot.notification.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(NotificationStateStore.class);
                assertThat(context).doesNotHaveBean(BudgetNotificationService.class);
            });
    }

    @Test
    @DisplayName("notification이 활성화되어도 BudgetNotificationHandler 빈이 없으면 서비스가 등록되지 않아야 한다")
    void shouldNotRegisterNotificationServiceWhenHandlerMissing() {
        this.contextRunner
            .withPropertyValues("token-pilot.notification.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(NotificationStateStore.class);
                assertThat(context).doesNotHaveBean(BudgetNotificationService.class);
            });
    }

    @Test
    @DisplayName("notification이 활성화되고 BudgetNotificationHandler 빈이 있을 때 BudgetNotificationService가 등록되어야 한다")
    void shouldRegisterNotificationServiceWhenEnabledAndHandlerExists() {
        this.contextRunner
            .withUserConfiguration(FakeBudgetNotificationHandlerConfiguration.class)
            .withPropertyValues("token-pilot.notification.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(NotificationStateStore.class);
                assertThat(context).hasSingleBean(BudgetNotificationService.class);
                assertThat(context.getBean(TokenPilotProperties.class).getNotification().isEnabled())
                    .isTrue();
            });
    }

    private static Stream<Arguments> providePricingConfigs() {
        return Stream.of(
            argumentSet(
                "OpenAI GPT-4o 표준 설정",
                "gpt-4o",
                "0.005",
                "0.015",
                "USD"),
            argumentSet(
                "Anthropic Claude-3 EUR 설정",
                "claude-3",
                "0.01",
                "0.03",
                "EUR")
        );
    }

    private String[] buildProperties(String modelId, String promptRate, String completionRate, String currency) {
        return new String[]{
            PROP_MODEL_ID + "=" + modelId,
            PROP_PROMPT + "=" + promptRate,
            PROP_COMPLETION + "=" + completionRate,
            PROP_CURRENCY + "=" + currency
        };
    }

    @Configuration(proxyBeanMethods = false)
    static class UserCustomConfiguration {
        @Bean
        public PricingRegistry pricingRegistry() {
            return new UserCustomPricingRegistry();
        }
    }

    static class UserCustomPricingRegistry implements PricingRegistry {
        @Override public void registerPlan(PricingPlan plan) {}
        @Override public Optional<PricingPlan> getPlan(String modelId) { return Optional.empty(); }
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingBudgetEvaluatorConfiguration {
        @Bean
        public RecordingBudgetEvaluator budgetEvaluator() {
            return new RecordingBudgetEvaluator();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FixedClockConfiguration {
        @Bean
        public Clock clock() {
            return Clock.fixed(Instant.parse("2026-07-31T15:30:00Z"), ZoneOffset.UTC);
        }
    }

    static class RecordingBudgetEvaluator implements BudgetEvaluator {
        private int evaluateCalls;
        private Map<String, String> lastTags = Map.of();

        @Override
        public BudgetDecision evaluate(Map<String, String> tags) {
            this.evaluateCalls++;
            this.lastTags = tags;
            return new BudgetDecision(
                new BudgetKey(
                    "policy-a",
                    "tenant",
                    tags.get("tenant_id"),
                    BudgetWindow.parse("2026-07")
                ),
                BudgetState.ALLOW,
                BudgetThreshold.NONE,
                "allowed",
                Cost.zero(Currency.getInstance("USD")),
                Cost.of(BigDecimal.TEN, Currency.getInstance("USD"))
            );
        }

        @Override
        public BudgetDecision evaluate(Map<String, String> tags, Cost cost) {
            return evaluate(tags);
        }

        int evaluateCalls() {
            return evaluateCalls;
        }

        Map<String, String> lastTags() {
            return lastTags;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MeterRegistryConfiguration {
        @Bean
        public MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    // 테스트용 no-op handler - 실제 알림 전송 없이 빈 등록 여부만 검증
    @Configuration(proxyBeanMethods = false)
    static class FakeBudgetNotificationHandlerConfiguration {
        @Bean
        public BudgetNotificationHandler budgetNotificationHandler() {
            return event -> {};
        }
    }
}
