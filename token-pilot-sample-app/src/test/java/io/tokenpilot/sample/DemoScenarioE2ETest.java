package io.tokenpilot.sample;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** API key 없이 실행되는 제출용 demo profile의 HTTP 계약을 검증합니다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
class DemoScenarioE2ETest {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void demoIndexDeclaresDeterministicNoKeyProvider() throws Exception {
        HttpResponse<String> response = get("/test/token-pilot/demo");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"status\":\"ok\"")
                .contains("\"profile\":\"demo\"")
                .contains("\"provider\":\"in-memory DemoChatModel\"")
                .contains("\"networkCall\":false")
                .contains("\"apiKeyRequired\":false")
                .contains("/test/token-pilot/demo/run");
    }

    @Test
    void demoRunCoversAdmissionReservationIdempotencyAndReconciliation() throws Exception {
        HttpResponse<String> response = get("/test/token-pilot/demo/run");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"scenario\":\"context-fit\"")
                .contains("\"scenario\":\"context-block\"")
                .contains("\"scenario\":\"budget-concurrency\"")
                .contains("\"scenario\":\"idempotency\"")
                .contains("\"scenario\":\"release\"")
                .contains("\"scenario\":\"reconciliation-success\"")
                .contains("\"scenario\":\"reconciliation-failure\"")
                .contains("\"scenario\":\"reconciliation-unknown\"")
                .contains("\"blockedRequests\":7")
                .contains("\"expectedProviderInvocations\":1")
                .contains("\"sameIdempotencyKey\":true")
                .contains("\"accountingState\":\"RECONCILIATION_REQUIRED\"")
                .contains("\"reconciliationRequired\"");
        assertThat(count(response.body(), "\"status\":\"PASS\""))
                .isEqualTo(8);
    }

    @Test
    void demoPublishesCurrentTokenPilotMetricsOnly() throws Exception {
        get("/test/token-pilot/demo/run");

        HttpResponse<String> response = get("/actuator/prometheus");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("tokenpilot_preflight_requests_total")
                .contains("tokenpilot_budget_reservations_total")
                .contains("tokenpilot_reconciliation_outcomes_reconciliations_total")
                .contains("tokenpilot_cost_total_currency_total")
                .doesNotContain("ai_token_usage_total")
                .doesNotContain("ai_token_cost_total");
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static int count(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
