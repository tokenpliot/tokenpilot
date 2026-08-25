package io.tokenpilot.springai.internal;

import java.util.Objects;

record AdaptedMessage(RequestMessageRole role, String text) {

    AdaptedMessage {
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(text, "text must not be null");
    }
}
