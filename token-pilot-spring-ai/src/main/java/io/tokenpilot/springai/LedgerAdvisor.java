package io.tokenpilot.springai;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.core.Ordered;

/**
 * ChatClient 호출 시 토큰 사용량을 가로채서 기록하는 어드바이저 인터페이스.
 * Spring AI의 {@link BaseAdvisor}를 상속하여 AI 호출 전후 처리 로직을 표준 방식으로 정의합니다.
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
