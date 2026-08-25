package io.tokenpilot.sample;

import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.budget.ReservationAccounting;
import io.tokenpilot.core.LedgerManager;
import io.tokenpilot.core.domain.PricingSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "token-pilot.enabled=true",
        "token-pilot.budget.enabled=false",
        "token-pilot.pricing.plans[0].model-id=gpt-4o-2024-08-06",
        "token-pilot.pricing.plans[0].currency=USD",
        "token-pilot.pricing.plans[0].rates.PROMPT=0.00015",
        "token-pilot.pricing.plans[0].rates.COMPLETION=0.00060"
})
@Import(SampleApplicationLedgerOnlyStreamingE2ETest.StreamingConfiguration.class)
class SampleApplicationLedgerOnlyStreamingE2ETest {

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private StreamingProviderProbe providerProbe;

    @Autowired
    private LedgerManager ledgerManagerProbe;

    @Autowired
    private ApplicationContext applicationContext;

    @BeforeEach
    void resetProbes() {
        providerProbe.reset();
        clearInvocations(ledgerManagerProbe);
    }

    @Test
    @DisplayName("budget 비활성 ledger-only streaming은 기존 provider와 terminal usage 기록 경로를 유지한다")
    void ledgerOnlyStreamingKeepsExistingProviderAndLedgerPath() {
        List<ChatClientResponse> responses = chatClientBuilder.clone()
                .build()
                .prompt()
                .user("Run the ledger-only streaming path.")
                .options(ChatOptions.builder()
                        .model("gpt-4o-2024-08-06"))
                .stream()
                .chatClientResponse()
                .collectList()
                .block();

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().chatResponse().getResult().getOutput().getText())
                .isEqualTo("ledger-only stream response");
        assertThat(providerProbe.invocationCount()).isEqualTo(1);
        assertThat(applicationContext.getBeansOfType(BudgetStateStore.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(ReservationAccounting.class)).isEmpty();
        verify(ledgerManagerProbe).record(
                any(PricingSnapshot.class),
                argThat(usage -> usage.inputTokens() == 10 && usage.outputTokens() == 5),
                anyMap()
        );
    }

    static final class StreamingProviderProbe implements ChatModel {
        private final AtomicInteger invocationCount = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("non-streaming call is not expected");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            invocationCount.incrementAndGet();
            Generation generation = new Generation(
                    new AssistantMessage("ledger-only stream response"),
                    ChatGenerationMetadata.builder()
                            .finishReason("STOP")
                            .build()
            );
            ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                    .model("gpt-4o-2024-08-06")
                    .usage(new DefaultUsage(10, 5))
                    .build();
            return Flux.just(new ChatResponse(List.of(generation), metadata));
        }

        int invocationCount() {
            return invocationCount.get();
        }

        void reset() {
            invocationCount.set(0);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StreamingConfiguration {

        @Bean
        LedgerManager ledgerManagerProbe() {
            return mock(LedgerManager.class);
        }

        @Bean
        StreamingProviderProbe streamingProviderProbe() {
            return new StreamingProviderProbe();
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
    }
}
