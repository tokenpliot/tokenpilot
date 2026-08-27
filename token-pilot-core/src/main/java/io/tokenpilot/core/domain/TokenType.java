package io.tokenpilot.core.domain;

/**
 * Detailed token types produced by an AI model call.
 */
public enum TokenType {
    /** Regular input (prompt). */
    PROMPT,
    /** Regular output (completion). */
    COMPLETION,
    /** Reasoning tokens, usually part of the output category. */
    REASONING,
    /** Input read from the cache (cache-read prompt). */
    CACHE_READ_PROMPT,
    /** Input newly stored in the cache (cache-creation prompt). */
    CACHE_CREATION_PROMPT;

    /**
     * Returns whether this token type belongs to the input (prompt) category.
     *
     * @return {@code true} when this is an input category
     */
    public boolean isPrompt() {
        return this == PROMPT || this == CACHE_READ_PROMPT || this == CACHE_CREATION_PROMPT;
    }

    /**
     * Returns whether this token type belongs to the output (completion) category.
     *
     * @return {@code true} when this is an output category
     */
    public boolean isCompletion() {
        return this == COMPLETION || this == REASONING;
    }
}
