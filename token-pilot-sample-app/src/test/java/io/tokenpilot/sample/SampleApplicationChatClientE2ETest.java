package io.tokenpilot.sample;

import io.tokenpilot.budget.BudgetDecision;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.PricingPlan;
import io.tokenpilot.core.domain.PricingReconciliationResult;
import io.tokenpilot.core.domain.PricingResolution;
import io.tokenpilot.core.domain.PricingSnapshot;
import io.tokenpilot.core.exception.MissingPricingException;
import io.tokenpilot.springai.LedgerAdvisor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "token-pilot.enabled=true",
                "token-pilot.pricing.plans[0].model-id=fake-chat-model",
                "token-pilot.pricing.plans[0].currency=USD",
                "token-pilot.pricing.plans[0].rates.PROMPT=0.00015",
                "token-pilot.pricing.plans[0].rates.COMPLETION=0.00060",
                "token-pilot.metrics.enabled=true",
                "token-pilot.metrics.legacy-ai-token-metrics-enabled=true",
                "token-pilot.metrics.tag-whitelist[0]=tenant_id",
                "token-pilot.budget.enabled=true",
                "token-pilot.budget.monthly-limit=10.00",
                "token-pilot.budget.currency=USD",
                "token-pilot.budget.target-tag-key=tenant_id",
                "management.endpoints.web.exposure.include=prometheus,health"
        }
)
class SampleApplicationChatClientE2ETest {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private BudgetStateStore budgetStateStore;

    @Autowired
    private LedgerAdvisor ledgerAdvisor;

    @Test
    void chatClientAdvisorRecordsTokenPilotMetricsEndToEnd() throws Exception {
        HttpResponse<String> beans = get("/test/token-pilot/beans");
        assertThat(beans.statusCode()).isEqualTo(200);
        assertThat(beans.body())
                .contains("\"ledgerAdvisor\":true")
                .contains("\"tokenPilotCoreMetricsPublisher\":true")
                .contains("\"tokenPilotBudgetMetricsPublisher\":true")
                .contains("\"tokenPilotNotificationMetricsPublisher\":true")
                .contains("\"microCostMetricsPublisher\":true");

        HttpResponse<String> chat = get("/test/token-pilot/chat");
        assertThat(chat.statusCode()).isEqualTo(200);
        assertThat(chat.body())
                .contains("\"available\":\"true\"")
                .contains("\"content\":\"fake chat response\"");

        HttpResponse<String> prometheus = get("/actuator/prometheus");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body())
                .contains("ai_token_usage_total")
                .contains("ai_token_cost_total")
                .contains("model=\"fake-chat-model\"")
                .contains("tenant_id=\"chat-sample-tenant\"")
                .doesNotContain("user_id=\"chat-sample-user\"");
    }

    @Test
    void budgetAdvisorResolvesModelAndPolicyFromRegularChatClientCall() {
        ChatClientResponse response = chatClientBuilder.clone()
                .build()
                .prompt()
                .user("Record this fake budget-aware Spring AI call.")
                .advisors(advisors -> advisors.param("tenant_id", "budget-chat-tenant"))
                .call()
                .chatClientResponse();

        PricingSnapshot snapshot = contextValue(response, PricingSnapshot.class);
        PricingResolution resolution = contextValue(response, PricingResolution.class);
        PricingReconciliationResult reconciliationResult = contextValue(
                response,
                PricingReconciliationResult.class
        );
        BudgetDecision budgetDecision = contextValue(response, BudgetDecision.class);
        Cost accumulatedCost = budgetStateStore.getAccumulatedCost(
                budgetDecision.key(),
                budgetDecision.limit()
        );

        assertThat(snapshot.modelId()).isEqualTo("fake-chat-model");
        assertThat(snapshot.pricingPolicyId()).isEqualTo(PricingPlan.DEFAULT_PRICING_POLICY_ID);
        assertThat(snapshot.currency()).isEqualTo(Currency.getInstance("USD"));
        assertThat(resolution).isEqualTo(PricingResolution.RESOLVED);
        assertThat(reconciliationResult).isEqualTo(PricingReconciliationResult.RECONCILED);
        assertThat(accumulatedCost.value()).isEqualByComparingTo("0.00135");
        assertThat(accumulatedCost.currency()).isEqualTo(Currency.getInstance("USD"));
    }

    @Test
    void missingPricingPublishesTokenPilotMetricBeforeProviderInvocation() throws Exception {
        ChatClientRequest request = new ChatClientRequest(
                new Prompt(
                        "missing pricing metric",
                        ChatOptions.builder().model("missing-model").build()
                ),
                Map.of("tenant_id", "metric-tenant")
        );

        assertThatThrownBy(() -> ledgerAdvisor.before(
                request,
                mock(AdvisorChain.class)
        )).isInstanceOf(MissingPricingException.class);

        HttpResponse<String> prometheus = get("/actuator/prometheus");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body())
                .contains("tokenpilot_pricing_missing_events_total")
                .contains("policy=\"fail_closed\"")
                .doesNotContain("missing-model");
    }

    private <T> T contextValue(ChatClientResponse response, Class<T> type) {
        return response.context().values().stream()
                .filter(type::isInstance)
                .map(type::cast)
                .findFirst()
                .orElseThrow();
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeChatClientConfiguration {

        @Bean
        ChatModel fakeChatModel() {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    return new ChatResponse(
                            List.of(new Generation(new AssistantMessage("fake chat response"))),
                            ChatResponseMetadata.builder()
                                    .model("fake-chat-model")
                                    .usage(new DefaultUsage(1_000, 2_000))
                                    .build()
                    );
                }

                @Override
                public ChatOptions getOptions() {
                    return ChatOptions.builder()
                            .model("fake-chat-model")
                            .build();
                }
            };
        }

        @Bean
        ChatClient.Builder chatClientBuilder(
                ChatModel chatModel,
                ObjectProvider<ChatClientBuilderCustomizer> customizers
        ) {
            ChatClient.Builder builder = ChatClient.builder(chatModel);
            customizers.orderedStream()
                    .forEach(customizer -> customizer.customize(builder));
            return builder;
        }

        @RestController
        static class FakeChatController {
            private final ChatClient.Builder chatClientBuilder;

            FakeChatController(ChatClient.Builder chatClientBuilder) {
                this.chatClientBuilder = chatClientBuilder;
            }

            @GetMapping("/test/token-pilot/chat")
            Map<String, String> chat() {
                String content = chatClientBuilder.clone()
                        .build()
                        .prompt()
                        .user("Record this fake Spring AI call.")
                        .advisors(advisors -> advisors
                                .param("tenant_id", "chat-sample-tenant")
                                .param("user_id", "chat-sample-user"))
                        .call()
                        .content();

                return Map.of(
                        "available", "true",
                        "content", content
                );
            }
        }
    }
}
