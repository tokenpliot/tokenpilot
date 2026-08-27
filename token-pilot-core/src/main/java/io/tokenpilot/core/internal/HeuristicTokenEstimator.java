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
 * Estimates text-only token counts from UTF-8 byte length.
 *
 * <p>{@code BYTE_LEVEL_BPE_UTF8} is only a compatibility basis representing a
 * byte-level tokenizer safety assumption; this implementation does not perform
 * exact BPE merges. The result cannot prove context admission for a complete request.</p>
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
     * Counts UTF-8 byte length without normalizing the original text.
     *
     * @param text original text to count
     * @return text-only heuristic token count result
     * @throws NullPointerException     when text is null
     * @throws IllegalArgumentException when text contains malformed UTF-16
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
