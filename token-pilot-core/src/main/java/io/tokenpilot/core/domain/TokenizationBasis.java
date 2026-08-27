package io.tokenpilot.core.domain;

/**
 * Basis used to determine compatibility between a token count result and a
 * model encoding.
 *
 * @param id stable value identifying the tokenization basis
 */
public record TokenizationBasis(String id) {

    /**
     * Validates that the tokenization basis identifier is not null or blank.
     *
     * @throws IllegalArgumentException when id is null or blank
     */
    public TokenizationBasis {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
    }
}
