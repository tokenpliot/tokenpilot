package io.tokenpilot.sample;

import io.tokenpilot.core.TokenBudget;
import io.tokenpilot.core.TokenEstimator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "token-pilot.enabled=true",
                "token-pilot.pricing.plans[0].model-id=gpt-4o-mini",
                "token-pilot.pricing.plans[0].currency=USD",
                "token-pilot.pricing.plans[0].rates.PROMPT=0.00015",
                "token-pilot.pricing.plans[0].rates.COMPLETION=0.00060",
                "token-pilot.metrics.enabled=true",
                "management.endpoints.web.exposure.include=prometheus,health"
        }
)
class SampleApplicationE2ETest {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Autowired
    private TokenEstimator tokenEstimator;

    @Autowired
    private TokenBudget tokenBudget;

    @Test
    void starterEndpointsAndPrometheusMetricsWorkEndToEnd() throws Exception {
        HttpResponse<String> smoke = get("/test/token-pilot/smoke");
        assertThat(smoke.statusCode()).isEqualTo(200);
        assertThat(smoke.body())
                .contains("\"status\":\"ok\"")
                .contains("\"starter\":\"token-pilot-starter\"");

        HttpResponse<String> beans = get("/test/token-pilot/beans");
        assertThat(beans.statusCode()).isEqualTo(200);
        assertThat(beans.body())
                .contains("\"ledgerManager\":true")
                .contains("\"ledgerAdvisor\":true")
                .contains("\"pricingRegistry\":true")
                .contains("\"tokenPilotCoreMetricsPublisher\":true")
                .contains("\"tokenPilotBudgetMetricsPublisher\":true")
                .contains("\"tokenPilotNotificationMetricsPublisher\":true")
                .contains("\"microCostMetricsPublisher\":false");

        HttpResponse<String> record = get("/test/token-pilot/record");
        assertThat(record.statusCode()).isEqualTo(200);
        assertThat(record.body())
                .contains("\"modelId\":\"gpt-4o-mini\"")
                .contains("\"cost\":\"0.001350\"")
                .contains("\"currency\":\"USD\"");

        tokenBudget.check(
                "gpt-4o-mini",
                tokenEstimator.estimate("sample preflight"),
                256
        );

        HttpResponse<String> prometheus = get("/actuator/prometheus");
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body())
                .contains("tokenpilot_preflight_requests_total")
                .contains("decision=\"indeterminate\"")
                .contains("reason=\"incomplete_scope\"")
                .doesNotContain("ai_token_usage_total")
                .doesNotContain("ai_token_usage_distribution")
                .doesNotContain("ai_token_cost_total")
                .doesNotContain("tenant_id=\"sample-tenant\"")
                .doesNotContain("user_id=\"sample-user\"");
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
