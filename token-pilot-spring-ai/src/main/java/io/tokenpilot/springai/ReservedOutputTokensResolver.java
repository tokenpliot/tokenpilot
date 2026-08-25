package io.tokenpilot.springai;

import org.springframework.ai.chat.client.ChatClientRequest;

import java.util.OptionalLong;

/** Provider 전용 request option에서 reserved output token 수를 해석합니다. */
@FunctionalInterface
public interface ReservedOutputTokensResolver {

    /**
     * 지원하는 provider request이면 양수 token 수를, 지원하지 않으면 빈 값을 반환합니다.
     */
    OptionalLong resolve(ChatClientRequest request);
}
