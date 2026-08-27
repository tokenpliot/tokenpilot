package io.tokenpilot.springai;

import io.tokenpilot.core.domain.TokenUsage;
import org.springframework.ai.chat.client.ChatClientResponse;

/**
 * Interface for extracting token usage information from an AI response
 * ({@link ChatClientResponse}).
 */
public interface UsageExtractor {
    TokenUsage extract(ChatClientResponse response);
}
