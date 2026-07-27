package io.tokenpilot.autoconfigure;

import io.tokenpilot.springai.LedgerAdvisor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ChatClientBuilderCustomizerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TokenPilotAutoConfiguration.class));

    @Test
    @DisplayName("Advisor가 존재할 때 ChatClementCustomizer를 등록해야 합니다.")
    void shouldRegisterChatClientBuilderCustomizerWhenAdvisorExists() {
        this.contextRunner
                .withUserConfiguration(TestAdvisorConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ChatClientBuilderCustomizer.class);
                    
                    ChatClientBuilderCustomizer customizer = context.getBean(ChatClientBuilderCustomizer.class);
                    assertThat(customizer).isInstanceOf(LedgerChatClientBuilderCustomizer.class);
                });
    }

    @Configuration
    static class TestAdvisorConfiguration {
        @Bean
        public LedgerAdvisor ledgerAdvisor() {
            return (response, chain) -> response;
        }
    }
}
