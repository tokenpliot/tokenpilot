package io.tokenpilot.springai;

import org.springframework.ai.chat.client.ChatClientRequest;

import java.util.OptionalLong;

/** Resolves reserved output token counts from provider-specific request options. */
@FunctionalInterface
public interface ReservedOutputTokensResolver {

    /**
     * Returns a positive token count for a supported provider request, or empty
     * when the request is not supported.
     */
    OptionalLong resolve(ChatClientRequest request);
}
