package io.tokenpilot.sample;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "token-pilot.enabled=true",
                "token-pilot.pricing.plans[0].model-id=fake-chat-model",
                "token-pilot.pricing.plans[0].currency=USD",
                "token-pilot.pricing.plans[0].rates.PROMPT=0.00015",
                "token-pilot.pricing.plans[0].rates.COMPLETION=0.00060",
                "token-pilot.metrics.enabled=true",
                "token-pilot.metrics.tag-whitelist[0]=tenant_id",
                "management.endpoints.web.exposure.include=prometheus,health"
        }
)
class SampleApplicationChatClientE2ETest {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void chatClientAdvisorRecordsTokenPilotMetricsEndToEnd() throws Exception {
        HttpResponse<String> beans = get("/test/token-pilot/beans");
        assertThat(beans.statusCode()).isEqualTo(200);
        assertThat(beans.body())
                .contains("\"ledgerAdvisor\":true")
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
            return prompt -> new ChatResponse(
                    List.of(new Generation(new AssistantMessage("fake chat response"))),
                    ChatResponseMetadata.builder()
                            .model("fake-chat-model")
                            .usage(new DefaultUsage(1_000, 2_000))
                            .build()
            );
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
