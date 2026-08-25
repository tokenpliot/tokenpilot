package io.tokenpilot.core.internal;

import io.tokenpilot.core.ModelRegistry;
import io.tokenpilot.core.PreflightDecisionListener;
import io.tokenpilot.core.TokenBudget;
import io.tokenpilot.core.domain.AdmissionReason;
import io.tokenpilot.core.domain.AdmissionStatus;
import io.tokenpilot.core.domain.BudgetResult;
import io.tokenpilot.core.domain.ModelDefinition;
import io.tokenpilot.core.domain.PreflightDecisionEvent;
import io.tokenpilot.core.domain.TokenCountResult;
import io.tokenpilot.core.domain.TokenCountScope;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * versioned model registry를 사용해 overflow-safe context admission을 수행합니다.
 */
final class DefaultTokenBudget implements TokenBudget {

    private final ModelRegistry modelRegistry;
    private final List<PreflightDecisionListener> listeners;

    DefaultTokenBudget(ModelRegistry modelRegistry) {
        this(modelRegistry, List.of());
    }

    DefaultTokenBudget(
            ModelRegistry modelRegistry,
            List<PreflightDecisionListener> listeners
    ) {
        this.modelRegistry = Objects.requireNonNull(modelRegistry, "modelRegistry must not be null");
        this.listeners = List.copyOf(
                Objects.requireNonNull(listeners, "listeners must not be null")
        );
    }

    @Override
    public BudgetResult check(
            String modelId,
            TokenCountResult input,
            long reservedOutputTokens
    ) {
        Objects.requireNonNull(modelId, "modelId must not be null");
        Objects.requireNonNull(input, "input must not be null");
        if (reservedOutputTokens < 0) {
            throw new IllegalArgumentException("reservedOutputTokens must be non-negative");
        }

        Optional<ModelDefinition> model = modelRegistry.find(modelId);
        if (model.isEmpty()) {
            return buildResult(
                    AdmissionStatus.INDETERMINATE,
                    AdmissionReason.UNKNOWN_MODEL,
                    Optional.empty(),
                    input,
                    reservedOutputTokens,
                    OptionalLong.empty()
            );
        }

        ModelDefinition definition = model.orElseThrow();
        OptionalLong maxContextTokens = OptionalLong.of(definition.maxContextTokens());
        if (!input.isCounted()) {
            return buildResult(
                    AdmissionStatus.INDETERMINATE,
                    AdmissionReason.COUNT_UNAVAILABLE,
                    Optional.of(definition.canonicalModelId()),
                    input,
                    reservedOutputTokens,
                    maxContextTokens
            );
        }
        if (!definition.acceptedCompatibilityBasis().equals(input.tokenizationBasis())) {
            return buildResult(
                    AdmissionStatus.INDETERMINATE,
                    AdmissionReason.INCOMPATIBLE_TOKENIZER,
                    Optional.of(definition.canonicalModelId()),
                    input,
                    reservedOutputTokens,
                    maxContextTokens
            );
        }

        long safeInput = input.safeUpperBoundTokens().orElseThrow();
        long maxContextTokensValue = definition.maxContextTokens();
        if (safeInput > maxContextTokensValue) {
            return buildResult(
                    AdmissionStatus.EXCEEDS,
                    AdmissionReason.CONTEXT_EXCEEDED,
                    Optional.of(definition.canonicalModelId()),
                    input,
                    reservedOutputTokens,
                    maxContextTokens
            );
        }

        long remainingAfterInput = maxContextTokensValue - safeInput;
        if (reservedOutputTokens > remainingAfterInput) {
            return buildResult(
                    AdmissionStatus.EXCEEDS,
                    AdmissionReason.CONTEXT_EXCEEDED,
                    Optional.of(definition.canonicalModelId()),
                    input,
                    reservedOutputTokens,
                    maxContextTokens
            );
        }

        if (input.scope() != TokenCountScope.REQUEST) {
            return buildResult(
                    AdmissionStatus.INDETERMINATE,
                    AdmissionReason.INCOMPLETE_SCOPE,
                    Optional.of(definition.canonicalModelId()),
                    input,
                    reservedOutputTokens,
                    maxContextTokens
            );
        }

        return buildResult(
                AdmissionStatus.FITS,
                AdmissionReason.WITHIN_CONTEXT,
                Optional.of(definition.canonicalModelId()),
                input,
                reservedOutputTokens,
                maxContextTokens,
                OptionalLong.of(remainingAfterInput - reservedOutputTokens)
        );
    }

    private BudgetResult buildResult(
            AdmissionStatus status,
            AdmissionReason reason,
            Optional<String> canonicalModelId,
            TokenCountResult input,
            long reservedOutputTokens,
            OptionalLong maxContextTokens
    ) {
        return buildResult(
                status,
                reason,
                canonicalModelId,
                input,
                reservedOutputTokens,
                maxContextTokens,
                OptionalLong.empty()
        );
    }

    private BudgetResult buildResult(
            AdmissionStatus status,
            AdmissionReason reason,
            Optional<String> canonicalModelId,
            TokenCountResult input,
            long reservedOutputTokens,
            OptionalLong maxContextTokens,
            OptionalLong remainingTokens
    ) {
        BudgetResult result = new BudgetResult(
                status,
                reason,
                canonicalModelId,
                input.tokens(),
                input.safeUpperBoundTokens(),
                reservedOutputTokens,
                maxContextTokens,
                remainingTokens,
                input.estimatorDescriptor(),
                input.tokenizationBasis()
        );
        publishBestEffort(result);
        return result;
    }

    private void publishBestEffort(BudgetResult result) {
        if (listeners.isEmpty()) {
            return;
        }
        PreflightDecisionEvent event = new PreflightDecisionEvent(result);
        for (PreflightDecisionListener listener : listeners) {
            try {
                listener.onDecision(event);
            } catch (RuntimeException ignored) {
                // Optional observers do not change a completed admission decision.
            }
        }
    }
}
