package io.tokenpilot.springai.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFramingPolicyTest {

    private final RequestFramingPolicy policy = new RequestFramingPolicy();

    @Test
    @DisplayName("message role과 순서 및 text 경계를 명시적인 framing으로 보존한다")
    void preservesRoleOrderAndTextBoundaries() {
        AdaptedRequest request = new AdaptedRequest(List.of(
                new AdaptedMessage(RequestMessageRole.SYSTEM, "rules"),
                new AdaptedMessage(RequestMessageRole.USER, "a\nb")
        ));

        String framed = policy.frame(request);

        assertThat(framed).isEqualTo("SYSTEM:5:rules\nUSER:3:a\nb\n");
    }
}
