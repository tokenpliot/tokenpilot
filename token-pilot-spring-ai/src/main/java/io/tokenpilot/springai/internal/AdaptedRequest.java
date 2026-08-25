package io.tokenpilot.springai.internal;

import java.util.List;
import java.util.Objects;

final class AdaptedRequest {

    private final List<AdaptedMessage> messages;

    AdaptedRequest(List<AdaptedMessage> messages) {
        this.messages = List.copyOf(
                Objects.requireNonNull(messages, "messages must not be null")
        );
    }

    List<AdaptedMessage> messages() {
        return messages;
    }
}
