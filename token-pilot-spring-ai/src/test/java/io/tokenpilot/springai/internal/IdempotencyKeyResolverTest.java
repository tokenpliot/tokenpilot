package io.tokenpilot.springai.internal;

import io.tokenpilot.budget.IdempotencyKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyResolverTest {

    private final RequestContextAccessor contextAccessor = new RequestContextAccessor();

    @Test
    @DisplayName("caller가 제공한 idempotency key를 그대로 사용한다")
    void usesCallerProvidedIdempotencyKey() {
        AtomicInteger generationCount = new AtomicInteger();
        IdempotencyKey callerKey = new IdempotencyKey("caller-key");
        IdempotencyKeyResolver resolver = resolver(generationCount);
        ChatClientRequest request = requestWithContext(Map.of(
                RequestContextAccessor.IDEMPOTENCY_CONTEXT_KEY,
                callerKey
        ));

        IdempotencyKey resolved = resolver.resolve(request);

        assertThat(resolved).isEqualTo(callerKey);
        assertThat(generationCount).hasValue(0);
    }

    @Test
    @DisplayName("idempotency key가 없으면 현재 호출을 위한 key를 한 번 생성한다")
    void generatesIdempotencyKeyForCurrentInvocation() {
        AtomicInteger generationCount = new AtomicInteger();
        IdempotencyKeyResolver resolver = resolver(generationCount);
        ChatClientRequest request = requestWithContext(Map.of());

        IdempotencyKey resolved = resolver.resolve(request);

        assertThat(resolved).isEqualTo(new IdempotencyKey("generated-key"));
        assertThat(generationCount).hasValue(1);
    }

    @Test
    @DisplayName("생성한 idempotency key는 같은 request context에서 재사용한다")
    void reusesGeneratedIdempotencyKeyInSameRequestContext() {
        AtomicInteger generationCount = new AtomicInteger();
        IdempotencyKeyResolver resolver = resolver(generationCount);
        ChatClientRequest request = requestWithContext(Map.of());

        IdempotencyKey generatedKey = resolver.resolve(request);
        ChatClientRequest requestWithKey = contextAccessor.withIdempotencyKey(
                request,
                generatedKey
        );
        IdempotencyKey reusedKey = resolver.resolve(requestWithKey);

        assertThat(reusedKey).isEqualTo(generatedKey);
        assertThat(generationCount).hasValue(1);
    }

    @Test
    @DisplayName("잘못된 타입의 기존 idempotency key를 새 key로 대체하지 않는다")
    void rejectsInvalidCallerIdempotencyKey() {
        AtomicInteger generationCount = new AtomicInteger();
        IdempotencyKeyResolver resolver = resolver(generationCount);
        ChatClientRequest request = requestWithContext(Map.of(
                RequestContextAccessor.IDEMPOTENCY_CONTEXT_KEY,
                "caller-key"
        ));

        assertThatThrownBy(() -> resolver.resolve(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(RequestContextAccessor.IDEMPOTENCY_CONTEXT_KEY);
        assertThat(generationCount).hasValue(0);
    }

    private IdempotencyKeyResolver resolver(AtomicInteger generationCount) {
        return new IdempotencyKeyResolver(
                contextAccessor,
                () -> {
                    generationCount.incrementAndGet();
                    return new IdempotencyKey("generated-key");
                }
        );
    }

    private ChatClientRequest requestWithContext(Map<String, Object> context) {
        return new ChatClientRequest(
                new Prompt("user question"),
                new HashMap<>(context)
        );
    }
}
