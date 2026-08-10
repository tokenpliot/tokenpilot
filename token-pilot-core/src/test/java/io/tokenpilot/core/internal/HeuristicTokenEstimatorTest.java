package io.tokenpilot.core.internal;

import io.tokenpilot.core.TokenEstimator;
import io.tokenpilot.core.domain.TokenCountAccuracy;
import io.tokenpilot.core.domain.TokenCountResult;
import io.tokenpilot.core.domain.TokenCountScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeuristicTokenEstimatorTest {

    private final TokenEstimator estimator = LedgerComponents.utf8ByteHeuristicTokenEstimator();

    @Test
    @DisplayName("빈 문자열은 estimate와 upper bound가 0인 결과로 계산한다")
    void estimatesEmptyTextWithZeroEstimateAndUpperBound() {
        TokenCountResult result = estimator.estimate("");

        assertThat(result).isNotNull();
        assertThat(result.isCounted()).isTrue();
        assertThat(result.isUnavailable()).isFalse();
        assertThat(result.tokens()).hasValue(0L);
        assertThat(result.safeUpperBoundTokens()).hasValue(0L);
        assertThat(result.isExact()).isFalse();
        assertThat(result.accuracy()).contains(TokenCountAccuracy.HEURISTIC);
        assertThat(result.scope()).isEqualTo(TokenCountScope.TEXT_ONLY);
    }

    @Test
    @DisplayName("4로 나누어떨어지는 ASCII byte 길이로 estimate를 계산한다")
    void estimatesAsciiTextWhenUtf8ByteLengthIsDivisibleByFour() {
        TokenCountResult result = estimator.estimate("four");

        assertThat(result.tokens()).hasValue(1L);
        assertThat(result.safeUpperBoundTokens()).hasValue(4L);
    }

    @Test
    @DisplayName("4보다 짧은 ASCII byte 길이의 estimate를 올림한다")
    void roundsUpAsciiTextShorterThanFourBytes() {
        TokenCountResult result = estimator.estimate("abc");

        assertThat(result.tokens()).hasValue(1L);
        assertThat(result.safeUpperBoundTokens()).hasValue(3L);
    }

    @Test
    @DisplayName("나머지가 있는 ASCII byte 길이의 estimate를 올림한다")
    void roundsUpAsciiTextWhenUtf8ByteLengthHasRemainder() {
        TokenCountResult result = estimator.estimate("hello");

        assertThat(result.tokens()).hasValue(2L);
        assertThat(result.safeUpperBoundTokens()).hasValue(5L);
    }

    @Test
    @DisplayName("한글 문자열을 UTF-8 byte 길이로 계산한다")
    void estimatesKoreanTextFromUtf8ByteLength() {
        TokenCountResult result = estimator.estimate("한글");

        assertThat(result.tokens()).hasValue(2L);
        assertThat(result.safeUpperBoundTokens()).hasValue(6L);
        assertHeuristicTextOnlyMetadata(result);
    }

    @Test
    @DisplayName("ASCII와 한글이 섞인 문자열을 UTF-8 byte 길이로 계산한다")
    void estimatesMixedAsciiAndKoreanTextFromUtf8ByteLength() {
        TokenCountResult result = estimator.estimate("A한");

        assertThat(result.tokens()).hasValue(1L);
        assertThat(result.safeUpperBoundTokens()).hasValue(4L);
        assertHeuristicTextOnlyMetadata(result);
    }

    @Test
    @DisplayName("모든 결과에 고정된 estimator와 tokenization 기준을 포함한다")
    void includesFixedEstimatorAndTokenizationMetadata() {
        TokenCountResult result = estimator.estimate("");

        assertThat(result.estimatorDescriptor().estimatorId())
                .isEqualTo("tokenpilot-utf8-byte-heuristic");
        assertThat(result.estimatorDescriptor().estimatorVersion()).isEqualTo("1");
        assertThat(result.tokenizationBasis().id()).isEqualTo("BYTE_LEVEL_BPE_UTF8");
    }

    @Test
    @DisplayName("null 입력을 거부한다")
    void rejectsNullText() {
        assertThatThrownBy(() -> estimator.estimate(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("짝이 없는 high surrogate를 거부한다")
    void rejectsUnpairedHighSurrogate() {
        assertThatThrownBy(() -> estimator.estimate("\uD800"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("짝이 없는 low surrogate를 거부한다")
    void rejectsUnpairedLowSurrogate() {
        assertThatThrownBy(() -> estimator.estimate("\uDC00"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("올바른 surrogate pair를 UTF-8 byte 길이로 계산한다")
    void estimatesValidSurrogatePairFromUtf8ByteLength() {
        TokenCountResult result = estimator.estimate("\uD83D\uDE00");

        assertThat(result.tokens()).hasValue(1L);
        assertThat(result.safeUpperBoundTokens()).hasValue(4L);
        assertHeuristicTextOnlyMetadata(result);
    }

    @Test
    @DisplayName("precomposed 문자열을 원문의 UTF-8 byte 길이로 계산한다")
    void estimatesPrecomposedTextWithoutNormalization() {
        TokenCountResult result = estimator.estimate("\u00E9");

        assertThat(result.tokens()).hasValue(1L);
        assertThat(result.safeUpperBoundTokens()).hasValue(2L);
    }

    @Test
    @DisplayName("combining 문자열을 원문의 UTF-8 byte 길이로 계산한다")
    void estimatesCombiningTextWithoutNormalization() {
        TokenCountResult result = estimator.estimate("e\u0301");

        assertThat(result.tokens()).hasValue(1L);
        assertThat(result.safeUpperBoundTokens()).hasValue(3L);
    }

    @Test
    @DisplayName("canonical equivalent 문자열을 같은 byte 길이로 정규화하지 않는다")
    void preservesDifferentByteLengthsForCanonicalEquivalentText() {
        String precomposed = "\u00E9";
        String combining = "e\u0301";
        assertThat(Normalizer.normalize(combining, Normalizer.Form.NFC))
                .isEqualTo(precomposed);

        TokenCountResult precomposedResult = estimator.estimate(precomposed);
        TokenCountResult combiningResult = estimator.estimate(combining);

        assertThat(precomposedResult.safeUpperBoundTokens()).hasValue(2L);
        assertThat(combiningResult.safeUpperBoundTokens()).hasValue(3L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "hello", "한글", "A한", "\uD83D\uDE00", "\u00E9", "e\u0301"})
    @DisplayName("모든 정상 입력 결과는 HEURISTIC/TEXT_ONLY 계약을 유지한다")
    void preservesHeuristicTextOnlyContractForValidText(String text) {
        TokenCountResult result = estimator.estimate(text);

        assertHeuristicTextOnlyMetadata(result);
        assertThat(result.isExact()).isFalse();
        assertThat(result.tokens().orElseThrow())
                .isLessThanOrEqualTo(result.safeUpperBoundTokens().orElseThrow());
    }

    private static void assertHeuristicTextOnlyMetadata(TokenCountResult result) {
        assertThat(result.accuracy()).contains(TokenCountAccuracy.HEURISTIC);
        assertThat(result.scope()).isEqualTo(TokenCountScope.TEXT_ONLY);
        assertThat(result.estimatorDescriptor().estimatorId())
                .isEqualTo("tokenpilot-utf8-byte-heuristic");
        assertThat(result.estimatorDescriptor().estimatorVersion()).isEqualTo("1");
        assertThat(result.tokenizationBasis().id()).isEqualTo("BYTE_LEVEL_BPE_UTF8");
    }
}
