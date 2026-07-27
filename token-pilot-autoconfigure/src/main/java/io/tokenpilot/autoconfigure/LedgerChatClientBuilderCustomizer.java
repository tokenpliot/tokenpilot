package io.tokenpilot.autoconfigure;

import io.tokenpilot.springai.LedgerAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;

/**
 * ChatClient.Builder에 LedgerAdvisor를 자동으로 주입하는 커스터마이저.
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
