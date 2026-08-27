package io.tokenpilot.springai.internal;

import io.tokenpilot.core.ModelRegistry;
import io.tokenpilot.core.domain.ModelDefinition;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.Objects;
import java.util.Optional;

/** Selects the request model before the configured default and resolves it in the Core registry. */
final class ModelResolver {

    private final ModelRegistry modelRegistry;
    private final Optional<String> defaultModelId;

    ModelResolver(ModelRegistry modelRegistry) {
        this(modelRegistry, null);
    }

    ModelResolver(ModelRegistry modelRegistry, @Nullable String defaultModelId) {
        this.modelRegistry = Objects.requireNonNull(
                modelRegistry,
                "modelRegistry must not be null"
        );
        this.defaultModelId = Optional.ofNullable(defaultModelId)
                .filter(modelId -> !modelId.isBlank());
    }

    Optional<ModelDefinition> resolve(ChatClientRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        String requestModelId = requestModelId(request);
        if (requestModelId != null) {
            return resolveModel(requestModelId);
        }
        return defaultModelId.flatMap(this::resolveModel);
    }

    private Optional<ModelDefinition> resolveModel(String modelId) {
        if (modelId.isBlank()) {
            return Optional.empty();
        }
        return modelRegistry.find(modelId);
    }

    private @Nullable String requestModelId(ChatClientRequest request) {
        ChatOptions options = request.prompt().getOptions();
        if (options == null) {
            return null;
        }
        return options.getModel();
    }
}
