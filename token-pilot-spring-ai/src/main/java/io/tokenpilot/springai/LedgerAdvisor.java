package io.tokenpilot.springai;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;

/**
 * Advisor interface that intercepts and records token usage during ChatClient calls.
 * Extends Spring AI's {@link BaseAdvisor} to define standard processing before
 * and after an AI call.
 */
public interface LedgerAdvisor extends BaseAdvisor {

    @Override
    default ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        return chatClientRequest;
    }

    @Override
    ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain);

    @Override
    default String getName() {
        return "LedgerAdvisor";
    }

    @Override
    default int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }
}
