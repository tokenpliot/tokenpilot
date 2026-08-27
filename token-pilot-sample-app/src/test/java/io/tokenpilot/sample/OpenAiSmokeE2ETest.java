package io.tokenpilot.sample;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Real OpenAI smoke test that can incur cost only when explicitly enabled. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("openai-smoke")
@EnabledIfEnvironmentVariables({
        @EnabledIfEnvironmentVariable(
                named = "RUN_OPENAI_SMOKE",
                matches = "(?i)true",
                disabledReason = "RUN_OPENAI_SMOKE=true일 때만 실제 provider를 호출합니다."
        ),
        @EnabledIfEnvironmentVariable(
                named = "OPENAI_API_KEY",
                matches = ".+",
                disabledReason = "OPENAI_API_KEY가 없으면 실제 provider smoke를 건너뜁니다."
        )
})
class OpenAiSmokeE2ETest {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void openAiResponseIsReconciledByTokenPilot() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(
                        "http://localhost:" + port
                                + "/test/token-pilot/openai-smoke?prompt="
                                + "Reply%20with%20one%20short%20sentence."
                ))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"status\":\"PASS\"")
                .contains("\"provider\":\"OpenAI Chat Completions\"")
                .contains("\"accountingState\":\"COMMITTED\"")
                .doesNotContain("\"usageSource\":\"UNAVAILABLE\"");
    }
}
