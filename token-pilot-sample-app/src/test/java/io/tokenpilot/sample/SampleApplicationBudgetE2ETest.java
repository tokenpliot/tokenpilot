package io.tokenpilot.sample;

import io.tokenpilot.budget.BudgetDecision;
import io.tokenpilot.budget.BudgetDecision.EvaluationType;
import io.tokenpilot.budget.BudgetEvaluator;
import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetState;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.budget.BudgetThreshold;
import io.tokenpilot.budget.BudgetWindow;
import io.tokenpilot.core.LedgerManager;
import io.tokenpilot.core.domain.Cost;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Currency;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    @SuppressWarnings("unchecked")
    void currencyMismatchIsReportedAsBlockedState() {
        BudgetEvaluator evaluator = mock(BudgetEvaluator.class);
        BudgetStateStore stateStore = mock(BudgetStateStore.class);
        ObjectProvider<BudgetEvaluator> evaluatorProvider = mock(ObjectProvider.class);
        ObjectProvider<BudgetStateStore> stateStoreProvider = mock(ObjectProvider.class);
        when(evaluatorProvider.getIfAvailable()).thenReturn(evaluator);
        when(stateStoreProvider.getIfAvailable()).thenReturn(stateStore);
        when(evaluator.evaluate(anyMap())).thenReturn(decision(
                EvaluationType.STATUS,
                BudgetState.ALLOW,
                Cost.zero(Currency.getInstance("USD"))
        ));
        when(evaluator.evaluate(anyMap(), any(Cost.class)))
                .thenReturn(
                        decision(
                                EvaluationType.ADMISSION,
                                BudgetState.ALLOW,
                                Cost.of(new BigDecimal("0.001"), Currency.getInstance("USD"))
                        ),
                        decision(
                                EvaluationType.ADMISSION,
                                BudgetState.CURRENCY_MISMATCH,
                                Cost.of(new BigDecimal("0.0045"), Currency.getInstance("USD"))
                        )
                );
        SampleController controller = new SampleController(
                mock(ApplicationContext.class),
                mock(LedgerManager.class),
                evaluatorProvider,
                stateStoreProvider
        );

        Map<String, String> response = controller.budget();

        assertThat(response)
                .containsEntry("blockedState", "CURRENCY_MISMATCH")
                .containsEntry("projectedUsage", "0.004500")
                .containsEntry("limit", "0.005000");
    }

    private static BudgetDecision decision(
            EvaluationType evaluationType,
            BudgetState state,
            Cost projectedUsage
    ) {
        Cost committedUsage = evaluationType == EvaluationType.STATUS
                || state == BudgetState.CURRENCY_MISMATCH
                ? projectedUsage
                : Cost.zero(projectedUsage.currency());
        return new BudgetDecision(
                new BudgetKey(
                        "policy-a",
                        "tenant",
                        "budget-sample-tenant",
                        BudgetWindow.parse("2026-07")
                ),
                evaluationType,
                state,
                BudgetThreshold.NONE,
                state.name(),
                committedUsage,
                projectedUsage,
                Cost.of(new BigDecimal("0.005"), projectedUsage.currency())
        );
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
