package io.tokenpilot.springai.internal;

import io.tokenpilot.core.domain.TokenCountScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.MimeTypeUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RequestScopeResolverTest {

    private final RequestScopeResolver resolver = new RequestScopeResolver();

    @Test
    @DisplayName("text message만 있는 요청은 TEXT_ONLY 범위다")
    void resolvesTextOnlyRequestScope() {
        ChatClientRequest request = new ChatClientRequest(
                new Prompt(List.of(new UserMessage("user question"))),
                Map.of()
        );

        RequestScopeResult result = resolver.resolve(request);

        assertThat(result.scope()).contains(TokenCountScope.TEXT_ONLY);
        assertThat(result.unsupportedReason()).isEmpty();
    }

    @Test
    @DisplayName("tool message는 지원하지 않는 request scope다")
    void rejectsToolMessageAsUnsupportedScope() {
        ToolResponseMessage toolMessage = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1",
                        "weather",
                        "sunny"
                )))
                .build();
        ChatClientRequest request = new ChatClientRequest(
                new Prompt(List.of(toolMessage)),
                Map.of()
        );

        RequestScopeResult result = resolver.resolve(request);

        assertThat(result.scope()).isEmpty();
        assertThat(result.unsupportedReason()).contains(
                UnsupportedRequestScopeReason.TOOL_MESSAGE
        );
    }

    @Test
    @DisplayName("media가 포함된 user message는 지원하지 않는 request scope다")
    void rejectsUserMessageWithMediaAsUnsupportedScope() {
        Media media = Media.builder()
                .mimeType(MimeTypeUtils.IMAGE_PNG)
                .data(new byte[]{1})
                .build();
        UserMessage userMessage = UserMessage.builder()
                .text("describe this image")
                .media(media)
                .build();
        ChatClientRequest request = new ChatClientRequest(
                new Prompt(List.of(userMessage)),
                Map.of()
        );

        RequestScopeResult result = resolver.resolve(request);

        assertThat(result.scope()).isEmpty();
        assertThat(result.unsupportedReason()).contains(
                UnsupportedRequestScopeReason.MEDIA
        );
    }

    @Test
    @DisplayName("tool call이 포함된 assistant message는 지원하지 않는 request scope다")
    void rejectsAssistantMessageWithToolCallAsUnsupportedScope() {
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1",
                        "function",
                        "weather",
                        "{}"
                )))
                .build();
        ChatClientRequest request = new ChatClientRequest(
                new Prompt(List.of(assistantMessage)),
                Map.of()
        );

        RequestScopeResult result = resolver.resolve(request);

        assertThat(result.scope()).isEmpty();
        assertThat(result.unsupportedReason()).contains(
                UnsupportedRequestScopeReason.TOOL_CALL
        );
    }

    @Test
    @DisplayName("tool schema가 포함된 request option은 지원하지 않는 request scope다")
    void rejectsToolSchemaAsUnsupportedScope() {
        ToolCallback toolCallback = mock(ToolCallback.class);
        ChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(toolCallback)
                .build();
        ChatClientRequest request = new ChatClientRequest(
                new Prompt(List.of(new UserMessage("user question")), options),
                Map.of()
        );

        RequestScopeResult result = resolver.resolve(request);

        assertThat(result.scope()).isEmpty();
        assertThat(result.unsupportedReason()).contains(
                UnsupportedRequestScopeReason.TOOL_SCHEMA
        );
    }

    @Test
    @DisplayName("output format이 추가될 요청은 지원하지 않는 request scope다")
    void rejectsOutputFormatAugmentationAsUnsupportedScope() {
        ChatClientRequest request = new ChatClientRequest(
                new Prompt(List.of(new UserMessage("user question"))),
                Map.of(ChatClientAttributes.OUTPUT_FORMAT.getKey(), "Return JSON")
        );

        RequestScopeResult result = resolver.resolve(request);

        assertThat(result.scope()).isEmpty();
        assertThat(result.unsupportedReason()).contains(
                UnsupportedRequestScopeReason.STRUCTURED_OUTPUT
        );
    }

    @Test
    @DisplayName("structured output schema가 추가될 요청은 지원하지 않는 request scope다")
    void rejectsStructuredOutputSchemaAsUnsupportedScope() {
        ChatClientRequest request = new ChatClientRequest(
                new Prompt(List.of(new UserMessage("user question"))),
                Map.of(ChatClientAttributes.STRUCTURED_OUTPUT_SCHEMA.getKey(), "{type: object}")
        );

        RequestScopeResult result = resolver.resolve(request);

        assertThat(result.scope()).isEmpty();
        assertThat(result.unsupportedReason()).contains(
                UnsupportedRequestScopeReason.STRUCTURED_OUTPUT
        );
    }

    @Test
    @DisplayName("structured output option은 지원하지 않는 request scope다")
    void rejectsStructuredOutputOptionAsUnsupportedScope() {
        ChatOptions options = StructuredOutputChatOptions.builder()
                .outputSchema("{type: object}")
                .build();
        ChatClientRequest request = new ChatClientRequest(
                new Prompt(List.of(new UserMessage("user question")), options),
                Map.of()
        );

        RequestScopeResult result = resolver.resolve(request);

        assertThat(result.scope()).isEmpty();
        assertThat(result.unsupportedReason()).contains(
                UnsupportedRequestScopeReason.STRUCTURED_OUTPUT
        );
    }

    @Test
    @DisplayName("provider native structured output은 지원하지 않는 request scope다")
    void rejectsNativeStructuredOutputAsUnsupportedScope() {
        ChatClientRequest request = new ChatClientRequest(
                new Prompt(List.of(new UserMessage("user question"))),
                Map.of(ChatClientAttributes.STRUCTURED_OUTPUT_NATIVE.getKey(), true)
        );

        RequestScopeResult result = resolver.resolve(request);

        assertThat(result.scope()).isEmpty();
        assertThat(result.unsupportedReason()).contains(
                UnsupportedRequestScopeReason.STRUCTURED_OUTPUT
        );
    }

    @Test
    @DisplayName("text가 없는 assistant message는 예외 대신 제한된 미지원 결과를 반환한다")
    void rejectsAssistantMessageWithoutTextAsUnsupportedScope() {
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content(null)
                .build();
        ChatClientRequest request = new ChatClientRequest(
                new Prompt(List.of(assistantMessage)),
                Map.of()
        );

        RequestScopeResult result = resolver.resolve(request);

        assertThat(result.scope()).isEmpty();
        assertThat(result.unsupportedReason()).contains(
                UnsupportedRequestScopeReason.MESSAGE_TEXT_UNAVAILABLE
        );
    }
}
