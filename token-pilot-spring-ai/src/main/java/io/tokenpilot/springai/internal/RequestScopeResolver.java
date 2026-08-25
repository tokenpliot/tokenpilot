package io.tokenpilot.springai.internal;

import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.content.MediaContent;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Objects;

/** Spring AI 요청이 Token Pilot 0.1.0에서 지원하는 범위인지 판정합니다. */
final class RequestScopeResolver {

    RequestScopeResult resolve(ChatClientRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        for (Message message : request.prompt().getInstructions()) {
            if (message.getMessageType() == MessageType.TOOL) {
                return RequestScopeResult.unsupported(
                        UnsupportedRequestScopeReason.TOOL_MESSAGE
                );
            }
            if (hasToolCalls(message)) {
                return RequestScopeResult.unsupported(
                        UnsupportedRequestScopeReason.TOOL_CALL
                );
            }
            if (hasMedia(message)) {
                return RequestScopeResult.unsupported(
                        UnsupportedRequestScopeReason.MEDIA
                );
            }
            if (message.getText() == null) {
                return RequestScopeResult.unsupported(
                        UnsupportedRequestScopeReason.MESSAGE_TEXT_UNAVAILABLE
                );
            }
        }
        if (hasToolSchema(request)) {
            return RequestScopeResult.unsupported(
                    UnsupportedRequestScopeReason.TOOL_SCHEMA
            );
        }
        if (usesStructuredOutput(request)) {
            return RequestScopeResult.unsupported(
                    UnsupportedRequestScopeReason.STRUCTURED_OUTPUT
            );
        }
        return RequestScopeResult.textOnly();
    }

    private boolean hasMedia(Message message) {
        return message instanceof MediaContent mediaContent
                && !mediaContent.getMedia().isEmpty();
    }

    private boolean hasToolCalls(Message message) {
        return message instanceof AssistantMessage assistantMessage
                && assistantMessage.hasToolCalls();
    }

    private boolean usesStructuredOutput(ChatClientRequest request) {
        return hasTextAttribute(request, ChatClientAttributes.OUTPUT_FORMAT)
                || hasTextAttribute(request, ChatClientAttributes.STRUCTURED_OUTPUT_SCHEMA)
                || usesNativeStructuredOutput(request)
                || hasStructuredOutputOption(request);
    }

    private boolean hasToolSchema(ChatClientRequest request) {
        ChatOptions options = request.prompt().getOptions();
        return options instanceof ToolCallingChatOptions toolOptions
                && !CollectionUtils.isEmpty(toolOptions.getToolCallbacks());
    }

    private boolean hasStructuredOutputOption(ChatClientRequest request) {
        ChatOptions options = request.prompt().getOptions();
        return options instanceof StructuredOutputChatOptions structuredOptions
                && StringUtils.hasText(structuredOptions.getOutputSchema());
    }

    private boolean usesNativeStructuredOutput(ChatClientRequest request) {
        Object value = request.context().get(
                ChatClientAttributes.STRUCTURED_OUTPUT_NATIVE.getKey()
        );
        return Boolean.TRUE.equals(value);
    }

    private boolean hasTextAttribute(
            ChatClientRequest request,
            ChatClientAttributes attribute
    ) {
        Object value = request.context().get(attribute.getKey());
        return value instanceof String text && StringUtils.hasText(text);
    }
}
