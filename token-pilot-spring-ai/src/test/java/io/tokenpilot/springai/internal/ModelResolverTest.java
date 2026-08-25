package io.tokenpilot.springai.internal;

import io.tokenpilot.core.CoreComponents;
import io.tokenpilot.core.ModelRegistry;
import io.tokenpilot.core.domain.ModelDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelResolverTest {

    private final ModelRegistry modelRegistry = CoreComponents.defaultModelRegistry();

    @Test
    @DisplayName("request model을 configured default보다 우선한다")
    void prioritizesRequestModelOverConfiguredDefault() {
        ModelResolver resolver = new ModelResolver(modelRegistry, "gpt-4o-mini");

        assertThat(resolver.resolve(requestWithModel("gpt-4o")))
                .get()
                .extracting(ModelDefinition::canonicalModelId)
                .isEqualTo("gpt-4o-2024-08-06");
    }

    @Test
    @DisplayName("request model이 없으면 configured default를 사용한다")
    void usesConfiguredDefaultWhenRequestModelIsAbsent() {
        ModelResolver resolver = new ModelResolver(modelRegistry, "gpt-4o-mini");

        assertThat(resolver.resolve(requestWithoutModel()))
                .get()
                .extracting(ModelDefinition::canonicalModelId)
                .isEqualTo("gpt-4o-mini-2024-07-18");
    }

    @Test
    @DisplayName("request model과 configured default가 모두 없으면 해석하지 못한다")
    void doesNotResolveWhenRequestAndDefaultModelsAreAbsent() {
        ModelResolver resolver = new ModelResolver(modelRegistry);

        assertThat(resolver.resolve(requestWithoutModel())).isEmpty();
    }

    @Test
    @DisplayName("blank 또는 unknown request model은 configured default로 대체하지 않는다")
    void doesNotFallbackForInvalidRequestModel() {
        ModelResolver resolver = new ModelResolver(modelRegistry, "gpt-4o-mini");

        assertThat(resolver.resolve(requestWithModel(" "))).isEmpty();
        assertThat(resolver.resolve(requestWithModel("unknown-model"))).isEmpty();
    }

    private ChatClientRequest requestWithModel(String modelId) {
        return new ChatClientRequest(
                new Prompt(
                        "user question",
                        ChatOptions.builder().model(modelId).build()
                ),
                Map.of()
        );
    }

    private ChatClientRequest requestWithoutModel() {
        return new ChatClientRequest(new Prompt("user question"), Map.of());
    }
}
