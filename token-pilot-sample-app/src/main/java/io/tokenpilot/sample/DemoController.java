package io.tokenpilot.sample;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Verification HTTP API exposed only in the {@code demo} profile. */
@RestController
@Profile("demo")
public class DemoController {

    private final DemoScenarioService scenarioService;

    public DemoController(DemoScenarioService scenarioService) {
        this.scenarioService = scenarioService;
    }

    @GetMapping("/test/token-pilot/demo")
    public Map<String, Object> index() {
        return Map.of(
                "status", "ok",
                "profile", "demo",
                "provider", "in-memory DemoChatModel",
                "networkCall", false,
                "apiKeyRequired", false,
                "run", "/test/token-pilot/demo/run",
                "scenarios", List.of(
                        "/test/token-pilot/demo/context-fit",
                        "/test/token-pilot/demo/context-block",
                        "/test/token-pilot/demo/budget-concurrency",
                        "/test/token-pilot/demo/idempotency",
                        "/test/token-pilot/demo/release",
                        "/test/token-pilot/demo/reconciliation-success",
                        "/test/token-pilot/demo/reconciliation-failure",
                        "/test/token-pilot/demo/reconciliation-unknown"
                )
        );
    }

    @GetMapping("/test/token-pilot/demo/run")
    public DemoRunReport runAll() {
        return scenarioService.runAll();
    }

    @GetMapping("/test/token-pilot/demo/context-fit")
    public DemoScenarioResult contextFit() {
        return scenarioService.contextFit();
    }

    @GetMapping("/test/token-pilot/demo/context-block")
    public DemoScenarioResult contextBlock() {
        return scenarioService.contextBlock();
    }

    @GetMapping("/test/token-pilot/demo/budget-concurrency")
    public DemoScenarioResult budgetConcurrency() {
        return scenarioService.budgetConcurrency();
    }

    @GetMapping("/test/token-pilot/demo/idempotency")
    public DemoScenarioResult idempotency() {
        return scenarioService.idempotency();
    }

    @GetMapping("/test/token-pilot/demo/release")
    public DemoScenarioResult release() {
        return scenarioService.release();
    }

    @GetMapping("/test/token-pilot/demo/reconciliation-success")
    public DemoScenarioResult reconciliationSuccess() {
        return scenarioService.reconciliationSuccess();
    }

    @GetMapping("/test/token-pilot/demo/reconciliation-failure")
    public DemoScenarioResult reconciliationFailure() {
        return scenarioService.reconciliationFailure();
    }

    @GetMapping("/test/token-pilot/demo/reconciliation-unknown")
    public DemoScenarioResult reconciliationUnknown() {
        return scenarioService.reconciliationUnknown();
    }
}
