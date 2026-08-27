package io.tokenpilot.autoconfigure;

import io.tokenpilot.springai.LedgerAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;

/**
 * Customizer that automatically injects LedgerAdvisor into ChatClient.Builder.
 */
public class LedgerChatClientBuilderCustomizer implements ChatClientBuilderCustomizer {

    private final LedgerAdvisor ledgerAdvisor;

    public LedgerChatClientBuilderCustomizer(LedgerAdvisor ledgerAdvisor) {
        this.ledgerAdvisor = ledgerAdvisor;
    }

    @Override
    public void customize(ChatClient.Builder builder) {
        builder.defaultAdvisors(ledgerAdvisor);
    }
}
