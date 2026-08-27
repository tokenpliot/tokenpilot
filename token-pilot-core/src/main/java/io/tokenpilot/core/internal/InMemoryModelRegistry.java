package io.tokenpilot.core.internal;

import io.tokenpilot.core.ModelRegistry;
import io.tokenpilot.core.domain.ModelDefinition;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * In-memory registry that manages canonical IDs and exact aliases in one lookup index.
 */
final class InMemoryModelRegistry implements ModelRegistry {

    private final Map<String, ModelDefinition> definitionsByLookupKey;

    InMemoryModelRegistry(Collection<ModelDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions must not be null");
        Map<String, ModelDefinition> index = new HashMap<>();
        definitions.forEach(definition -> add(index, definition));
        definitionsByLookupKey = Map.copyOf(index);
    }

    @Override
    public Optional<ModelDefinition> find(String modelIdOrAlias) {
        Objects.requireNonNull(modelIdOrAlias, "modelIdOrAlias must not be null");
        if (modelIdOrAlias.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(definitionsByLookupKey.get(modelIdOrAlias));
    }

    private static void add(Map<String, ModelDefinition> index, ModelDefinition definition) {
        Objects.requireNonNull(definition, "definition must not be null");
        add(index, definition.canonicalModelId(), definition);
        definition.aliases().forEach(alias -> add(index, alias, definition));
    }

    private static void add(
            Map<String, ModelDefinition> index,
            String id,
            ModelDefinition definition
    ) {
        if (index.containsKey(id)) {
            throw new IllegalArgumentException("model id or alias is already registered: " + id);
        }
        index.put(id, definition);
    }
}
