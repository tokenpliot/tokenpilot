package io.tokenpilot.springai.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRequestAdapterTest {

    private final DefaultRequestAdapter adapter = new DefaultRequestAdapter();

    @Test
    @DisplayName("지원 message의 role과 순서를 보존한다")
    void preservesSupportedMessageRolesAndOrder() {
        ChatClientRequest request = new ChatClientRequest(
                new Prompt(List.of(
                        new SystemMessage("system instructions"),
                        new UserMessage("user question"),
                        new AssistantMessage("previous answer")
                )),
                Map.of()
        );

        AdaptedRequest adaptedRequest = adapter.adapt(request);

        assertThat(adaptedRequest.messages()).containsExactly(
                new AdaptedMessage(
                        RequestMessageRole.SYSTEM,
                        "system instructions"
                ),
                new AdaptedMessage(
                        RequestMessageRole.USER,
                        "user question"
                ),
                new AdaptedMessage(
                        RequestMessageRole.ASSISTANT,
                        "previous answer"
                )
        );
    }

}
