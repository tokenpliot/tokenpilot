package io.tokenpilot.sample;

import org.junit.jupiter.api.Test;
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
                "token-pilot.budget.enabled=true",
                "token-pilot.budget.monthly-limit=0.005",
                "management.endpoints.web.exposure.include=prometheus,health"
        }
)
class SampleApplicationBudgetE2ETest {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void budgetBeansAndBudgetBlockWorkEndToEnd() throws Exception {
        HttpResponse<String> beans = get("/test/token-pilot/beans");
        assertThat(beans.statusCode()).isEqualTo(200);
        assertThat(beans.body())
                .contains("\"budgetEvaluator\":true")
                .contains("\"budgetStateStore\":true");

        HttpResponse<String> budget = get("/test/token-pilot/budget");
        assertThat(budget.statusCode()).isEqualTo(200);
        assertThat(budget.body())
                .contains("\"enabled\":\"true\"")
                .contains("\"initialState\":\"ALLOW\"")
                .contains("\"blockedState\":\"BLOCK\"")
                .contains("\"projectedUsage\":\"0.005500\"")
                .contains("\"limit\":\"0.005000\"");
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
