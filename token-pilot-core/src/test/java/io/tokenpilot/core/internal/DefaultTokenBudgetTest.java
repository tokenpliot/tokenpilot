package io.tokenpilot.core.internal;

import io.tokenpilot.core.CoreComponents;
import io.tokenpilot.core.ModelRegistry;
import io.tokenpilot.core.TokenBudget;
import io.tokenpilot.core.domain.AdmissionReason;
import io.tokenpilot.core.domain.AdmissionStatus;
import io.tokenpilot.core.domain.BudgetResult;
import io.tokenpilot.core.domain.ModelDefinition;
import io.tokenpilot.core.domain.PreflightDecisionEvent;
import io.tokenpilot.core.domain.TokenCountAccuracy;
import io.tokenpilot.core.domain.TokenCountResult;
import io.tokenpilot.core.domain.TokenCountScope;
import io.tokenpilot.core.domain.TokenEstimatorDescriptor;
import io.tokenpilot.core.domain.TokenizationBasis;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultTokenBudgetTest {

    private static final TokenizationBasis BASIS = new TokenizationBasis("o200k_base");
    private static final TokenEstimatorDescriptor ESTIMATOR =
            new TokenEstimatorDescriptor("test-estimator", "1");
    private static final ModelDefinition MODEL = model("model-v1", 10);

    private final ModelRegistry registry = new InMemoryModelRegistry(List.of(MODEL));
    private final TokenBudget budget = new DefaultTokenBudget(registry);

    @Test
    @DisplayName("REQUEST 입력은 정확히 context 경계까지 FITS다")
    void fitsAtExactBoundary() {
        BudgetResult result = budget.check("model-v1", request(6, 6), 4);

        assertThat(result.status()).isEqualTo(AdmissionStatus.FITS);
        assertThat(result.reason()).isEqualTo(AdmissionReason.WITHIN_CONTEXT);
        assertThat(result.fits()).isTrue();
        assertThat(result.canonicalModelId()).contains("model-v1");
        assertThat(result.maxContextTokens()).hasValue(10);
        assertThat(result.remainingTokens()).hasValue(0);
    }

    @Test
    @DisplayName("reserved output이 context를 넘으면 overflow 없이 EXCEEDS다")
    void exceedsWhenReservedOutputDoesNotFit() {
        BudgetResult result = budget.check("model-v1", request(6, 6), 5);

        assertThat(result.status()).isEqualTo(AdmissionStatus.EXCEEDS);
        assertThat(result.reason()).isEqualTo(AdmissionReason.CONTEXT_EXCEEDED);
        assertThat(result.fits()).isFalse();
        assertThat(result.remainingTokens()).isEmpty();
    }

    @Test
    @DisplayName("입력 safe upper bound가 context를 넘으면 EXCEEDS다")
    void exceedsWhenInputUpperBoundIsTooLarge() {
        BudgetResult result = budget.check("model-v1", request(11, 11), 0);

        assertThat(result.status()).isEqualTo(AdmissionStatus.EXCEEDS);
        assertThat(result.reason()).isEqualTo(AdmissionReason.CONTEXT_EXCEEDED);
        assertThat(result.remainingTokens()).isEmpty();
    }

    @Test
    @DisplayName("TEXT_ONLY 입력이 window 안이면 INDETERMINATE다")
    void textOnlyWithinWindowIsIndeterminate() {
        BudgetResult result = budget.check("model-v1", textOnly(6, 6), 4);

        assertThat(result.status()).isEqualTo(AdmissionStatus.INDETERMINATE);
        assertThat(result.reason()).isEqualTo(AdmissionReason.INCOMPLETE_SCOPE);
        assertThat(result.fits()).isFalse();
        assertThat(result.remainingTokens()).isEmpty();
    }

    @Test
    @DisplayName("TEXT_ONLY 입력이 window를 넘으면 EXCEEDS다")
    void textOnlyOverWindowIsExceeds() {
        BudgetResult result = budget.check("model-v1", textOnly(11, 11), 0);

        assertThat(result.status()).isEqualTo(AdmissionStatus.EXCEEDS);
        assertThat(result.reason()).isEqualTo(AdmissionReason.CONTEXT_EXCEEDED);
    }

    @Test
    @DisplayName("unknown model은 fail-closed INDETERMINATE다")
    void unknownModelIsIndeterminate() {
        BudgetResult result = budget.check("unknown", request(1, 1), 0);

        assertThat(result.status()).isEqualTo(AdmissionStatus.INDETERMINATE);
        assertThat(result.reason()).isEqualTo(AdmissionReason.UNKNOWN_MODEL);
        assertThat(result.canonicalModelId()).isEmpty();
        assertThat(result.maxContextTokens()).isEmpty();
        assertThat(result.remainingTokens()).isEmpty();
    }

    @Test
    @DisplayName("unavailable count는 fail-closed INDETERMINATE다")
    void unavailableCountIsIndeterminate() {
        TokenCountResult unavailable = TokenCountResult.unavailable(
                io.tokenpilot.core.domain.TokenCountUnavailableReason.ESTIMATOR_UNAVAILABLE,
                TokenCountScope.REQUEST,
                ESTIMATOR,
                BASIS
        );

        BudgetResult result = budget.check("model-v1", unavailable, 0);

        assertThat(result.status()).isEqualTo(AdmissionStatus.INDETERMINATE);
        assertThat(result.reason()).isEqualTo(AdmissionReason.COUNT_UNAVAILABLE);
        assertThat(result.inputEstimatedTokens()).isEmpty();
        assertThat(result.inputSafeUpperBoundTokens()).isEmpty();
        assertThat(result.maxContextTokens()).hasValue(10);
    }

    @Test
    @DisplayName("tokenizer mismatch는 context 수치와 무관하게 INDETERMINATE다")
    void incompatibleTokenizerIsIndeterminate() {
        TokenCountResult input = TokenCountResult.counted(
                1,
                1,
                TokenCountAccuracy.EXACT,
                TokenCountScope.REQUEST,
                ESTIMATOR,
                new TokenizationBasis("cl100k_base")
        );

        BudgetResult result = budget.check("model-v1", input, 0);

        assertThat(result.status()).isEqualTo(AdmissionStatus.INDETERMINATE);
        assertThat(result.reason()).isEqualTo(AdmissionReason.INCOMPATIBLE_TOKENIZER);
    }

    @Test
    @DisplayName("Long.MAX_VALUE 인접 값에서도 합산 overflow 없이 판정한다")
    void handlesLongMaxValueWithoutOverflow() {
        TokenBudget maxBudget = new DefaultTokenBudget(
                new InMemoryModelRegistry(List.of(model("max-model", Long.MAX_VALUE)))
        );

        BudgetResult fits = maxBudget.check("max-model", request(Long.MAX_VALUE, Long.MAX_VALUE), 0);
        BudgetResult exceeds = maxBudget.check("max-model", request(Long.MAX_VALUE, Long.MAX_VALUE), 1);

        assertThat(fits.status()).isEqualTo(AdmissionStatus.FITS);
        assertThat(fits.remainingTokens()).hasValue(0);
        assertThat(exceeds.status()).isEqualTo(AdmissionStatus.EXCEEDS);
    }

    @Test
    @DisplayName("음수 reserved output을 거부하고 FITS 외 상태를 requireFits에서 차단한다")
    void rejectsNegativeReservedOutputAndRequiresFits() {
        assertThatThrownBy(() -> budget.check("model-v1", request(1, 1), -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> budget.requireFits("model-v1", textOnly(1, 1), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INCOMPLETE_SCOPE");
    }

    @Test
    @DisplayName("모든 admission 결과를 immutable event로 한 번씩 발행한다")
    void publishesEveryAdmissionResultOnce() {
        List<PreflightDecisionEvent> events = new CopyOnWriteArrayList<>();
        TokenBudget observed = CoreComponents.tokenBudget(
                registry,
                List.of(events::add)
        );

        BudgetResult fits = observed.check("model-v1", request(6, 6), 4);
        BudgetResult exceeds = observed.check("model-v1", request(6, 6), 5);
        BudgetResult indeterminate = observed.check("unknown", request(1, 1), 0);

        assertThat(events).containsExactly(
                new PreflightDecisionEvent(fits),
                new PreflightDecisionEvent(exceeds),
                new PreflightDecisionEvent(indeterminate)
        );
    }

    @Test
    @DisplayName("한 preflight listener 실패가 결과나 다음 listener를 바꾸지 않는다")
    void isolatesPreflightListenerFailures() {
        AtomicInteger failedDeliveries = new AtomicInteger();
        List<PreflightDecisionEvent> received = new CopyOnWriteArrayList<>();
        TokenBudget observed = LedgerComponents.tokenBudget(
                registry,
                List.of(
                        event -> {
                            failedDeliveries.incrementAndGet();
                            throw new IllegalStateException("listener failed");
                        },
                        received::add
                )
        );

        BudgetResult result = observed.check("model-v1", request(6, 6), 4);

        assertThat(result.status()).isEqualTo(AdmissionStatus.FITS);
        assertThat(failedDeliveries).hasValue(1);
        assertThat(received).containsExactly(new PreflightDecisionEvent(result));
    }

    private static TokenCountResult request(long tokens, long safeUpperBound) {
        return counted(tokens, safeUpperBound, TokenCountScope.REQUEST);
    }

    private static TokenCountResult textOnly(long tokens, long safeUpperBound) {
        return counted(tokens, safeUpperBound, TokenCountScope.TEXT_ONLY);
    }

    private static TokenCountResult counted(
            long tokens,
            long safeUpperBound,
            TokenCountScope scope
    ) {
        return TokenCountResult.counted(
                tokens,
                safeUpperBound,
                tokens == safeUpperBound ? TokenCountAccuracy.EXACT : TokenCountAccuracy.HEURISTIC,
                scope,
                ESTIMATOR,
                BASIS
        );
    }

    private static ModelDefinition model(String canonicalId, long maxContextTokens) {
        return new ModelDefinition(
                canonicalId,
                Set.of(canonicalId + "-alias"),
                "o200k_base",
                BASIS,
                maxContextTokens,
                "default",
                "catalog-v1",
                URI.create("https://example.com/" + canonicalId),
                Instant.parse("2026-08-14T00:00:00Z")
        );
    }
}
