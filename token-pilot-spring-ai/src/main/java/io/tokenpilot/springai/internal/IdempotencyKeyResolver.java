package io.tokenpilot.springai.internal;

import io.tokenpilot.budget.IdempotencyKey;
import org.springframework.ai.chat.client.ChatClientRequest;

import java.util.Objects;
import java.util.function.Supplier;

/** 현재 Advisor 호출에서 사용할 idempotency key를 결정합니다. */
final class IdempotencyKeyResolver {

    private final RequestContextAccessor contextAccessor;
    private final Supplier<IdempotencyKey> keyGenerator;

    IdempotencyKeyResolver(
            RequestContextAccessor contextAccessor,
            Supplier<IdempotencyKey> keyGenerator
    ) {
        this.contextAccessor = Objects.requireNonNull(
                contextAccessor,
                "contextAccessor must not be null"
        );
        this.keyGenerator = Objects.requireNonNull(
                keyGenerator,
                "keyGenerator must not be null"
        );
    }

    IdempotencyKey resolve(ChatClientRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        IdempotencyKey existingKey = contextAccessor.idempotencyKey(request);
        if (existingKey != null) {
            return existingKey;
        }

        return Objects.requireNonNull(
                keyGenerator.get(),
                "generated idempotencyKey must not be null"
        );
    }
}
