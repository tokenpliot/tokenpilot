package io.tokenpilot.springai.internal;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class DefaultRequestAdapter {

    AdaptedRequest adapt(ChatClientRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        List<AdaptedMessage> messages = new ArrayList<>();
        for (Message message : request.prompt().getInstructions()) {
            messages.add(adaptMessage(message));
        }

        return new AdaptedRequest(messages);
    }

    private AdaptedMessage adaptMessage(Message message) {
        return new AdaptedMessage(
                adaptRole(message.getMessageType()),
                message.getText()
        );
    }

    private RequestMessageRole adaptRole(MessageType messageType) {
        return switch (messageType) {
            case SYSTEM -> RequestMessageRole.SYSTEM;
            case USER -> RequestMessageRole.USER;
            case ASSISTANT -> RequestMessageRole.ASSISTANT;
            case TOOL -> throw new IllegalArgumentException("TOOL message is unsupported");
        };
    }
}
