package io.tokenpilot.core.internal;

import io.tokenpilot.core.TokenEstimator;
import io.tokenpilot.core.domain.TokenCountAccuracy;
import io.tokenpilot.core.domain.TokenCountResult;
import io.tokenpilot.core.domain.TokenCountScope;
import io.tokenpilot.core.domain.TokenEstimatorDescriptor;
import io.tokenpilot.core.domain.TokenizationBasis;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * UTF-8 byte 길이를 기반으로 문자열의 text-only token 수를 추정합니다.
 *
 * <p>{@code BYTE_LEVEL_BPE_UTF8}는 byte-level tokenizer의 안전성 가정을
 * 나타내는 compatibility basis일 뿐, 이 구현이 exact BPE merge를 수행한다는
 * 뜻은 아닙니다. 결과는 전체 요청의 context admission 근거로 사용할 수 없습니다.</p>
 */
final class HeuristicTokenEstimator implements TokenEstimator {

    private static final int BYTES_PER_ESTIMATED_TOKEN = 4;
    private static final String ESTIMATOR_ID = "tokenpilot-utf8-byte-heuristic";
    private static final String ESTIMATOR_VERSION = "1";
    private static final String TOKENIZATION_BASIS_ID = "BYTE_LEVEL_BPE_UTF8";

    private static final TokenEstimatorDescriptor ESTIMATOR_DESCRIPTOR =
            new TokenEstimatorDescriptor(
                    ESTIMATOR_ID,
                    ESTIMATOR_VERSION
            );

    private static final TokenizationBasis TOKENIZATION_BASIS =
            new TokenizationBasis(TOKENIZATION_BASIS_ID);

    /**
     * 원문을 정규화하지 않고 UTF-8 byte 길이로 계산합니다.
     *
     * @param text 계산할 원문
     * @return text-only 휴리스틱 token 계산 결과
     * @throws NullPointerException     text가 null인 경우
     * @throws IllegalArgumentException text에 malformed UTF-16이 포함된 경우
     */
    @Override
    public TokenCountResult estimate(String text) {
        Objects.requireNonNull(text, "text must not be null");

        long utf8Bytes = utf8ByteLength(text);
        long estimatedTokens = Math.ceilDiv(utf8Bytes, BYTES_PER_ESTIMATED_TOKEN);

        return TokenCountResult.counted(
                estimatedTokens,
                utf8Bytes,
                TokenCountAccuracy.HEURISTIC,
                TokenCountScope.TEXT_ONLY,
                ESTIMATOR_DESCRIPTOR,
                TOKENIZATION_BASIS
        );
    }

    private static long utf8ByteLength(String text) {
        CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder();
        encoder.onMalformedInput(CodingErrorAction.REPORT);
        return encodedByteLength(encoder, text);
    }

    private static long encodedByteLength(CharsetEncoder encoder, String text) {
        try {
            CharBuffer input = CharBuffer.wrap(text);
            ByteBuffer encoded = encoder.encode(input);
            return encoded.remaining();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("text contains invalid UTF-16", exception);
        }
    }
}
