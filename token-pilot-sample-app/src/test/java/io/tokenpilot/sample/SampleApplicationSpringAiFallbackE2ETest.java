package io.tokenpilot.sample;

import io.tokenpilot.budget.BudgetStateStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "token-pilot.enabled=true",
        "token-pilot.pricing.plans[0].model-id=gpt-4o-2024-08-06",
        "token-pilot.pricing.plans[0].currency=USD",
        "token-pilot.pricing.plans[0].rates.PROMPT=0.00015",
        "token-pilot.pricing.plans[0].rates.COMPLETION=0.00060",
        "token-pilot.budget.enabled=true",
        "token-pilot.budget.monthly-limit=10.00",
        "token-pilot.budget.currency=USD",
        "token-pilot.budget.target-tag-key=tenant_id",
        "token-pilot.spring-ai.default-model-id=gpt-4o",
        "token-pilot.spring-ai.default-reserved-output-tokens=64",
        "token-pilot.spring-ai.framing-headroom-tokens=8"
})
@Import(SampleApplicationChatClientE2ETest.FakeChatClientConfiguration.class)
class SampleApplicationSpringAiFallbackE2ETest {

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private SampleApplicationChatClientE2ETest.ProviderProbe providerProbe;

    @Autowired
    private BudgetStateStore stateStoreProbe;

    @BeforeEach
    void resetProbes() {
        providerProbe.reset();
        clearInvocations(stateStoreProbe);
    }

    @Test
    @DisplayName("request model과 maxTokens가 없으면 설정 fallback으로 versioned safe bound 예약을 만든다")
    void configuredFallbacksCreateVersionedSafeBoundReservation() {
        String message = "Use configured model and output fallbacks.";
        long expectedInputSafeUpperBound = (
                "USER:" + message.length() + ":" + message + '\n'
        ).getBytes(StandardCharsets.UTF_8).length + 8L;

        chatClientBuilder.clone()
                .build()
                .prompt()
                .user(message)
                .advisors(advisors -> advisors
                        .param("tenant_id", "fallback-tenant")
                        .param("tokenpilot.request.id", "request-fallback")
                        .param("tokenpilot.attempt.id", "attempt-fallback"))
                .call()
                .chatClientResponse();

        verify(stateStoreProbe).checkAndReserve(argThat(
                reservation -> reservation.modelId().equals("gpt-4o-2024-08-06")
                        && reservation.tokenEstimate().orElseThrow()
                        .reservedOutputTokens() == 64
                        && reservation.tokenEstimate().orElseThrow()
                        .inputSafeUpperBoundTokens() == expectedInputSafeUpperBound
                        && reservation.safeUpperBoundCost().value().signum() > 0
        ));
        assertThat(providerProbe.invocationCount()).isEqualTo(1);
    }
}
