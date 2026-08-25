package io.tokenpilot.springai.internal;

import java.util.Objects;

final class RequestFramingPolicy {

    String frame(AdaptedRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        StringBuilder framed = new StringBuilder();
        for (AdaptedMessage message : request.messages()) {
            framed.append(message.role())
                    .append(':')
                    .append(message.text().length())
                    .append(':')
                    .append(message.text())
                    .append('\n');
        }
        return framed.toString();
    }
}
