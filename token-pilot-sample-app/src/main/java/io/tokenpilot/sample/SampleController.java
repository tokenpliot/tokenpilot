package io.tokenpilot.sample;

import io.tokenpilot.budget.BudgetDecision;
import io.tokenpilot.budget.BudgetEvaluator;
import io.tokenpilot.budget.BudgetState;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.core.LedgerManager;
import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.TokenUsage;

import java.math.BigDecimal;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SampleController {

    private final ApplicationContext applicationContext;
    private final LedgerManager ledgerManager;
    private final ObjectProvider<BudgetEvaluator> budgetEvaluator;
    private final ObjectProvider<BudgetStateStore> budgetStateStore;

    public SampleController(ApplicationContext applicationContext,
                            LedgerManager ledgerManager,
                            ObjectProvider<BudgetEvaluator> budgetEvaluator,
                            ObjectProvider<BudgetStateStore> budgetStateStore) {
        this.applicationContext = applicationContext;
        this.ledgerManager = ledgerManager;
        this.budgetEvaluator = budgetEvaluator;
        this.budgetStateStore = budgetStateStore;
    }

    @GetMapping("/test/ai")
    public String testAiLogic() {
        return "AI 장부 분석 테스트 성공! (이 접속이 그라파나에 기록됩니다.)";
    }

    @GetMapping("/test/token-pilot/smoke")
    public Map<String, String> smoke() {
        return Map.of(
                "status", "ok",
                "starter", "token-pilot-starter"
        );
    }

    @GetMapping("/test/token-pilot/beans")
    public Map<String, Boolean> tokenLedgerBeans() {
        return Map.of(
                "ledgerManager", applicationContext.containsBean("ledgerManager"),
                "ledgerAdvisor", applicationContext.containsBean("ledgerAdvisor"),
                "pricingRegistry", applicationContext.containsBean("pricingRegistry"),
                "microCostMetricsPublisher", applicationContext.containsBean("microCostMetricsPublisher"),
                "budgetEvaluator", applicationContext.containsBean("budgetEvaluator"),
                "budgetStateStore", applicationContext.containsBean("budgetStateStore")
        );
    }

    @GetMapping("/test/token-pilot/record")
    public Map<String, String> recordTokenPilotEvent() {
        Cost cost = ledgerManager.record(
                "gpt-4o-mini",
                TokenUsage.from(1_000, 2_000),
                Map.of("tenant_id", "sample-tenant", "user_id", "sample-user")
        );

        return Map.of(
                "modelId", "gpt-4o-mini",
                "cost", CostBoundaryFormatter.format(cost),
                "currency", cost.currency().getCurrencyCode()
        );
    }

    @GetMapping("/test/token-pilot/budget")
    public Map<String, String> budget() {
        BudgetEvaluator evaluator = budgetEvaluator.getIfAvailable();
        BudgetStateStore stateStore = budgetStateStore.getIfAvailable();

        if (evaluator == null || stateStore == null) {
            return Map.of("enabled", "false");
        }

        Map<String, String> tags = Map.of("tenant_id", "budget-sample-tenant");
        BudgetDecision currentDecision = evaluator.evaluate(tags);
        Cost projectedCost = Cost.of(
                new BigDecimal("0.001"),
                currentDecision.limit().currency()
        );
        BudgetDecision initialDecision = evaluator.evaluate(tags, projectedCost);
        stateStore.addCost(
                initialDecision.key(),
                initialDecision.limit(),
                Cost.of(new BigDecimal("0.0045"), initialDecision.limit().currency())
        );

        BudgetDecision blockedDecision = evaluator.evaluate(tags, projectedCost);
        if (blockedDecision.state() == BudgetState.BLOCK
                || blockedDecision.state() == BudgetState.CURRENCY_MISMATCH) {
            return Map.of(
                    "enabled", "true",
                    "initialState", initialDecision.state().name(),
                    "blockedState", blockedDecision.state().name(),
                    "projectedUsage", CostBoundaryFormatter.format(blockedDecision.projectedUsage()),
                    "limit", CostBoundaryFormatter.format(blockedDecision.limit())
            );
        }
        return Map.of(
                "enabled", "true",
                "initialState", initialDecision.state().name(),
                "blockedState", "NONE"
        );
    }

}
