package io.tokenpilot.sample;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.tokenpilot.autoconfigure.TokenPilotProperties;
import io.tokenpilot.budget.BudgetDecision;
import io.tokenpilot.budget.BudgetEvaluator;
import io.tokenpilot.budget.BudgetSnapshot;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.core.domain.TokenUsage;
import io.tokenpilot.core.domain.UsageSource;
import io.tokenpilot.springai.UsageExtractor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Verification API that performs real provider calls only in the {@code openai-smoke} profile. */
@RestController
@Profile("openai-smoke")
public final class OpenAiSmokeController {

    private static final String PROFILE = "openai-smoke";
    private static final String TENANT_ID = "openai-smoke";
    private static final String DEFAULT_PROMPT =
            "Reply with exactly one short sentence confirming that the provider is reachable.";

    private final ChatClient.Builder chatClientBuilder;
    private final UsageExtractor usageExtractor;
    private final BudgetEvaluator budgetEvaluator;
    private final BudgetStateStore budgetStateStore;
    private final MeterRegistry meterRegistry;
    private final TokenPilotProperties properties;

    public OpenAiSmokeController(
            ChatClient.Builder chatClientBuilder,
            UsageExtractor usageExtractor,
            BudgetEvaluator budgetEvaluator,
            BudgetStateStore budgetStateStore,
            MeterRegistry meterRegistry,
            TokenPilotProperties properties
    ) {
        this.chatClientBuilder = chatClientBuilder;
        this.usageExtractor = usageExtractor;
        this.budgetEvaluator = budgetEvaluator;
        this.budgetStateStore = budgetStateStore;
        this.meterRegistry = meterRegistry;
        this.properties = properties;
    }

    @GetMapping("/test/token-pilot/openai-smoke")
    public OpenAiSmokeReport smoke(
            @RequestParam(defaultValue = DEFAULT_PROMPT) String prompt
    ) {
        String normalizedPrompt = requirePrompt(prompt);
        String modelId = requireModelId();
        int reservedOutputTokens = reservedOutputTokens();
        String requestId = "openai-smoke-" + UUID.randomUUID();

        Map<String, String> tags = Map.of("tenant_id", TENANT_ID);
        BudgetDecision beforeDecision = budgetEvaluator.evaluate(tags);
        BudgetSnapshot beforeBudget = budgetStateStore.snapshot(
                beforeDecision.key(),
                beforeDecision.limit()
        );
        double beforeCommittedCost = counter(
                "tokenpilot.cost.total",
                "currency", "USD"
        );
        double beforeCommitted = committedReconciliationCounter();
        double beforeReconciliationRequired = reconciliationRequiredCounter();
        double beforeReservationsCreated = counter(
                "tokenpilot.budget.reservations",
                "state", "created"
        );

        ChatClientResponse response = chatClientBuilder.clone()
                .build()
                .prompt()
                .user(normalizedPrompt)
                .options(ChatOptions.builder()
                        .model(modelId)
                        .maxTokens(reservedOutputTokens)
                        .temperature(0.0))
                .advisors(advisors -> advisors
                        .param("tenant_id", TENANT_ID)
                        .param("tokenpilot.request.id", requestId)
                        .param("tokenpilot.attempt.id", requestId + "-attempt"))
                .call()
                .chatClientResponse();

        TokenUsage usage = usageExtractor.extract(response);
        String responseModel = responseModel(response);
        String responseText = responseText(response);

        BudgetDecision afterDecision = budgetEvaluator.evaluate(tags);
        BudgetSnapshot afterBudget = budgetStateStore.snapshot(
                afterDecision.key(),
                afterDecision.limit()
        );
        double committedCostDelta = counter(
                "tokenpilot.cost.total",
                "currency", "USD"
        ) - beforeCommittedCost;
        double committedDelta = committedReconciliationCounter() - beforeCommitted;
        double reconciliationRequiredDelta = reconciliationRequiredCounter()
                - beforeReconciliationRequired;
        String accountingState = accountingState(
                committedDelta,
                reconciliationRequiredDelta
        );
        boolean passed = hasText(responseText)
                && usage.source() != UsageSource.UNAVAILABLE
                && "COMMITTED".equals(accountingState);

        return new OpenAiSmokeReport(
                passed ? "PASS" : "FAIL",
                PROFILE,
                "OpenAI Chat Completions",
                requestId,
                modelId,
                responseModel,
                responseText,
                usage.source().name(),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.totalTokens(),
                accountingState,
                Map.of(
                        "committedCostUsd", committedCostDelta,
                        "reconciliationCommitted", committedDelta,
                        "reconciliationRequired", reconciliationRequiredDelta,
                        "reservationsCreated",
                        counter("tokenpilot.budget.reservations", "state", "created")
                                - beforeReservationsCreated
                ),
                budget(afterBudget),
                note(passed, responseModel, usage)
        );
    }

    private String requirePrompt(String prompt) {
        if (!hasText(prompt)) {
            throw new IllegalArgumentException("prompt must not be blank");
        }
        return prompt.trim();
    }

    private String requireModelId() {
        String modelId = properties.getSpringAi().getDefaultModelId();
        if (!hasText(modelId)) {
            throw new IllegalStateException(
                    "token-pilot.spring-ai.default-model-id must be configured"
            );
        }
        return modelId.trim();
    }

    private int reservedOutputTokens() {
        Long configured = properties.getSpringAi().getDefaultReservedOutputTokens();
        long value = configured == null ? 64L : configured;
        if (value <= 0 || value > Integer.MAX_VALUE) {
            throw new IllegalStateException(
                    "token-pilot.spring-ai.default-reserved-output-tokens must be between 1 and "
                            + Integer.MAX_VALUE
            );
        }
        return (int) value;
    }

    private String responseModel(ChatClientResponse response) {
        ChatResponse chatResponse = response == null ? null : response.chatResponse();
        return chatResponse == null || chatResponse.getMetadata() == null
                ? null
                : chatResponse.getMetadata().getModel();
    }

    private String responseText(ChatClientResponse response) {
        ChatResponse chatResponse = response == null ? null : response.chatResponse();
        if (chatResponse == null) {
            return null;
        }
        Generation result = chatResponse.getResult();
        if (result == null || result.getOutput() == null) {
            return null;
        }
        return result.getOutput().getText();
    }

    private Map<String, String> budget(BudgetSnapshot snapshot) {
        return Map.of(
                "committedCost", CostBoundaryFormatter.format(snapshot.committedCost()),
                "activeReservedCost", CostBoundaryFormatter.format(snapshot.activeReservedCost()),
                "pendingReconciliationLiability",
                CostBoundaryFormatter.format(snapshot.pendingReconciliationLiability()),
                "effectiveUsage", CostBoundaryFormatter.format(snapshot.effectiveUsage()),
                "remaining", CostBoundaryFormatter.format(snapshot.remaining()),
                "limit", CostBoundaryFormatter.format(snapshot.limit())
        );
    }

    private double committedReconciliationCounter() {
        return counter(
                "tokenpilot.reconciliation.outcomes",
                "outcome", "committed",
                "reason", "actual_usage_reported"
        );
    }

    private double reconciliationRequiredCounter() {
        return counter(
                "tokenpilot.reconciliation.outcomes",
                "outcome", "reconciliation_required",
                "reason", "actual_usage_unavailable"
        ) + counter(
                "tokenpilot.reconciliation.outcomes",
                "outcome", "reconciliation_required",
                "reason", "pricing_reconciliation_required"
        );
    }

    private double counter(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private String accountingState(
            double committedDelta,
            double reconciliationRequiredDelta
    ) {
        if (committedDelta > 0.0) {
            return "COMMITTED";
        }
        if (reconciliationRequiredDelta > 0.0) {
            return "RECONCILIATION_REQUIRED";
        }
        return "UNKNOWN";
    }

    private String note(
            boolean passed,
            String responseModel,
            TokenUsage usage
    ) {
        if (passed) {
            return "실제 OpenAI 응답의 usage를 정규화하고 예약 비용을 COMMITTED로 정산했습니다.";
        }
        if (usage.source() == UsageSource.UNAVAILABLE) {
            return "provider 응답은 받았지만 usage를 확인하지 못해 정산 대기로 남았습니다.";
        }
        if (!hasText(responseModel)) {
            return "provider 응답의 model metadata가 없어 정산을 확정하지 못했습니다.";
        }
        return "provider 응답은 받았지만 예약 snapshot과 actual 정산 결과를 확인해야 합니다.";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
