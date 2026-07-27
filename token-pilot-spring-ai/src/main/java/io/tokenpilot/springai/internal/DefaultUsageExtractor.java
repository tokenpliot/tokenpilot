package io.tokenpilot.springai.internal;

import io.tokenpilot.core.domain.TokenUsage;
import io.tokenpilot.core.domain.TokenUsageDetails;
import io.tokenpilot.core.domain.UsageSource;
import io.tokenpilot.springai.UsageExtractor;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.util.JacksonUtils;
import tools.jackson.core.type.TypeReference;

import java.util.HashMap;
import java.util.Map;

/**
 * 기본 {@link UsageExtractor} 구현체.
 * Spring AI의 {@link Usage} 정보를 {@link TokenUsage}로 변환하며,
 * provider native usage와 메타데이터에서 cache/reasoning breakdown을 식별합니다.
 * Provider가 비포괄 총량을 반환하면 Token Pilot의 포괄 총량 계약에 맞게 정규화합니다.
 */
public class DefaultUsageExtractor implements UsageExtractor {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Override
    public TokenUsage extract(ChatClientResponse response) {
        if (response == null) {
            return TokenUsage.unavailable(Map.of());
        }

        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null || chatResponse.getMetadata() == null) {
            return TokenUsage.unavailable(Map.of());
        }

        ChatResponseMetadata metadata = chatResponse.getMetadata();
        Usage usage = metadata.getUsage();
        if (usage == null || usage instanceof EmptyUsage) {
            return TokenUsage.unavailable(copyMetadata(metadata, null));
        }

        long prompt = (usage.getPromptTokens() != null) ? usage.getPromptTokens() : 0L;
        long completion = (usage.getCompletionTokens() != null) ? usage.getCompletionTokens() : 0L;
        Object nativeUsage = usage.getNativeUsage();
        Map<String, Object> nativeUsageFields = usageFields(nativeUsage);

        Long cacheRead = extractCacheReadTokens(metadata, nativeUsageFields);
        Long cacheCreation = extractCacheCreationTokens(metadata, nativeUsageFields);
        Long reasoning = extractReasoningTokens(metadata, nativeUsageFields);
        UsageSource source = UsageSource.PROVIDER_REPORTED;

        Long nativeInput = firstNonNull(
                numberAt(nativeUsageFields, "input_tokens"),
                numberAt(nativeUsageFields, "inputTokens")
        );
        if (cacheCreation != null && nativeInput != null && prompt == nativeInput) {
            prompt = Math.addExact(prompt, countOrZero(cacheRead));
            prompt = Math.addExact(prompt, cacheCreation);
            source = UsageSource.PROVIDER_DERIVED;
        }

        Long nativeCandidates = firstNonNull(
                numberAt(nativeUsageFields, "candidatesTokenCount"),
                numberAt(nativeUsageFields, "candidates_token_count")
        );
        if (reasoning != null && nativeCandidates != null && completion == nativeCandidates) {
            completion = Math.addExact(completion, reasoning);
            source = UsageSource.PROVIDER_DERIVED;
        }

        return new TokenUsage(
                prompt,
                completion,
                new TokenUsageDetails(cacheRead, cacheCreation, reasoning),
                source,
                copyMetadata(metadata, nativeUsage)
        );
    }

    private Long extractCacheReadTokens(ChatResponseMetadata metadata, Object nativeUsage) {
        return firstNonNull(
                numberAt(metadata.get("prompt_tokens_details"), "cached_tokens"),
                numberAt(metadata.get("input_tokens_details"), "cached_tokens"),
                numberAt(metadata, "cache_read_input_tokens"),
                numberAt(metadata, "cachedContentTokenCount"),
                numberAt(nativeUsage, "prompt_tokens_details", "cached_tokens"),
                numberAt(nativeUsage, "input_tokens_details", "cached_tokens"),
                numberAt(nativeUsage, "cache_read_input_tokens"),
                numberAt(nativeUsage, "cacheReadInputTokens"),
                numberAt(nativeUsage, "cachedContentTokenCount"),
                numberAt(nativeUsage, "cached_content_token_count")
        );
    }

    private Long extractCacheCreationTokens(ChatResponseMetadata metadata, Object nativeUsage) {
        return firstNonNull(
                numberAt(metadata, "cache_creation_input_tokens"),
                numberAt(metadata, "cacheCreationInputTokens"),
                numberAt(nativeUsage, "cache_creation_input_tokens"),
                numberAt(nativeUsage, "cacheCreationInputTokens")
        );
    }

    private Long extractReasoningTokens(ChatResponseMetadata metadata, Object nativeUsage) {
        return firstNonNull(
                numberAt(metadata.get("completion_tokens_details"), "reasoning_tokens"),
                numberAt(metadata.get("output_tokens_details"), "reasoning_tokens"),
                numberAt(metadata, "reasoning_tokens"),
                numberAt(metadata, "thoughtsTokenCount"),
                numberAt(nativeUsage, "completion_tokens_details", "reasoning_tokens"),
                numberAt(nativeUsage, "output_tokens_details", "reasoning_tokens"),
                numberAt(nativeUsage, "reasoning_tokens"),
                numberAt(nativeUsage, "reasoningTokens"),
                numberAt(nativeUsage, "thoughtsTokenCount"),
                numberAt(nativeUsage, "thoughts_token_count")
        );
    }

    private Long numberAt(Object source, String... path) {
        Object current = source;
        for (String key : path) {
            if (current instanceof ChatResponseMetadata responseMetadata) {
                current = responseMetadata.get(key);
            }
            else if (current instanceof Map<?, ?> map) {
                current = map.get(key);
            }
            else {
                return null;
            }
        }
        return current instanceof Number number ? number.longValue() : null;
    }

    private Long firstNonNull(Long... values) {
        for (Long value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private long countOrZero(Long count) {
        return count == null ? 0L : count;
    }

    private Map<String, Object> usageFields(Object nativeUsage) {
        if (nativeUsage == null) {
            return Map.of();
        }
        try {
            return JacksonUtils.getDefaultJsonMapper().convertValue(nativeUsage, MAP_TYPE);
        }
        catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> copyMetadata(ChatResponseMetadata metadata, Object nativeUsage) {
        Map<String, Object> metadataMap = new HashMap<>();
        metadata.keySet().forEach(key -> metadataMap.put(key, metadata.get(key)));
        if (nativeUsage != null) {
            metadataMap.put("nativeUsage", nativeUsage);
        }
        return metadataMap;
    }
}
