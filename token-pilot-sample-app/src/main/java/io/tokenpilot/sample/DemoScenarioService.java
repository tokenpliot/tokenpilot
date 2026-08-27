package io.tokenpilot.sample;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.tokenpilot.autoconfigure.TokenPilotProperties;
import io.tokenpilot.budget.AtomicBudgetStateStore;
import io.tokenpilot.budget.BudgetDecision;
import io.tokenpilot.budget.BudgetEvaluator;
import io.tokenpilot.budget.BudgetReservationRequest;
import io.tokenpilot.budget.BudgetReservationResult;
import io.tokenpilot.budget.BudgetSnapshot;
import io.tokenpilot.budget.IdempotencyKey;
import io.tokenpilot.budget.ReservationState;
import io.tokenpilot.budget.ReservationTransition;
import io.tokenpilot.core.ModelRegistry;
import io.tokenpilot.core.TokenBudget;
import io.tokenpilot.core.TokenEstimator;
import io.tokenpilot.core.domain.AdmissionStatus;
import io.tokenpilot.core.domain.BudgetResult;
import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.TokenCountAccuracy;
import io.tokenpilot.core.domain.TokenCountResult;
import io.tokenpilot.core.domain.TokenCountScope;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Repeatable framework-independent control/accounting scenarios.
 * All provider calls are routed only to {@link DemoChatModel}, and no external
 * network is used.
 */
@Service
@Profile("demo")
public class DemoScenarioService {

    private static final String MODEL_ID = "gpt-4o-2024-08-06";
    private static final String CATALOG_VERSION = "openai-2026-08-14";
    private static final Currency USD = Currency.getInstance("USD");
    private static final int COMPETITOR_COUNT = 7;
    private static final long REQUEST_OUTPUT_TOKENS = 16;
    private static final String CONCURRENCY_PROMPT =
            "Hold one conservative reservation while other requests compete. "
                    + "x".repeat(5_000);

    private final ChatClient.Builder chatClientBuilder;
    private final DemoChatModel demoChatModel;
    private final AtomicBudgetStateStore budgetStateStore;
    private final BudgetEvaluator budgetEvaluator;
    private final ModelRegistry modelRegistry;
    private final TokenEstimator tokenEstimator;
    private final TokenBudget tokenBudget;
    private final TokenPilotProperties properties;
    private final MeterRegistry meterRegistry;
    private final AtomicInteger runSequence = new AtomicInteger();

    @Autowired
    public DemoScenarioService(
            ChatClient.Builder chatClientBuilder,
            DemoChatModel demoChatModel,
            AtomicBudgetStateStore budgetStateStore,
            BudgetEvaluator budgetEvaluator,
            ModelRegistry modelRegistry,
            TokenEstimator tokenEstimator,
            TokenBudget tokenBudget,
            TokenPilotProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.chatClientBuilder = chatClientBuilder;
        this.demoChatModel = demoChatModel;
        this.budgetStateStore = budgetStateStore;
        this.budgetEvaluator = budgetEvaluator;
        this.modelRegistry = modelRegistry;
        this.tokenEstimator = tokenEstimator;
        this.tokenBudget = tokenBudget;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public synchronized DemoRunReport runAll() {
        String runId = nextRunId();
        List<DemoScenarioResult> results = List.of(
                contextFit(runId),
                contextBlock(runId),
                budgetConcurrency(runId),
                idempotency(runId),
                release(runId),
                reconciliation(runId, ReconciliationMode.SUCCESS),
                reconciliation(runId, ReconciliationMode.PROVIDER_FAILURE),
                reconciliation(runId, ReconciliationMode.USAGE_UNKNOWN)
        );
        return new DemoRunReport(
                runId,
                "demo",
                "in-memory DemoChatModel",
                "외부 provider 호출과 API key 없이 재현한 보수적 admission·예약·회계 흐름입니다.",
                results,
                metricSnapshot()
        );
    }

    public synchronized DemoScenarioResult contextFit() {
        return contextFit(nextRunId());
    }

    public synchronized DemoScenarioResult contextBlock() {
        return contextBlock(nextRunId());
    }

    public synchronized DemoScenarioResult budgetConcurrency() {
        return budgetConcurrency(nextRunId());
    }

    public synchronized DemoScenarioResult idempotency() {
        return idempotency(nextRunId());
    }

    public synchronized DemoScenarioResult release() {
        return release(nextRunId());
    }

    public synchronized DemoScenarioResult reconciliationSuccess() {
        return reconciliation(nextRunId(), ReconciliationMode.SUCCESS);
    }

    public synchronized DemoScenarioResult reconciliationFailure() {
        return reconciliation(nextRunId(), ReconciliationMode.PROVIDER_FAILURE);
    }

    public synchronized DemoScenarioResult reconciliationUnknown() {
        return reconciliation(nextRunId(), ReconciliationMode.USAGE_UNKNOWN);
    }

    private DemoScenarioResult contextFit(String runId) {
        String text = "A short request stays inside the versioned model context window.";
        BudgetResult result = checkContext(text, 64);
        Map<String, Object> evidence = contextEvidence(result);
        evidence.put("runId", runId);
        evidence.put("providerInvocations", 0);
        evidence.put("requestText", text);
        boolean passed = result.status() == AdmissionStatus.FITS;
        return new DemoScenarioResult(
                "context-fit",
                passed ? "PASS" : "FAIL",
                passed
                        ? "보수적 REQUEST 상한으로 provider 호출 전 FITS를 확인했습니다."
                        : "짧은 요청이 예상과 다르게 context admission에 실패했습니다.",
                evidence
        );
    }

    private DemoScenarioResult contextBlock(String runId) {
        String text = "context overflow ".repeat(10_000);
        BudgetResult result = checkContext(text, 64);
        Map<String, Object> evidence = contextEvidence(result);
        evidence.put("runId", runId);
        evidence.put("providerInvocations", 0);
        evidence.put("inputCharacters", text.length());
        evidence.put("requestTextIncluded", false);
        boolean passed = result.status() == AdmissionStatus.EXCEEDS;
        return new DemoScenarioResult(
                "context-block",
                passed ? "PASS" : "FAIL",
                passed
                        ? "보수적 safe upper bound가 context를 넘겨 provider 전에 차단했습니다."
                        : "큰 입력이 예상과 다르게 context admission을 통과했습니다.",
                evidence
        );
    }

    private DemoScenarioResult budgetConcurrency(String runId) {
        demoChatModel.reset();
        String tenant = "demo-concurrency-" + runId;
        List<CallOutcome> competitorOutcomes = new ArrayList<>();
        CallOutcome primaryOutcome = CallOutcome.failed("NOT_STARTED", "primary call did not finish");

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                demoChatModel.blockNextProviderCall();
                Future<CallOutcome> primary = executor.submit(
                        () -> invokeChat(
                                tenant,
                                "request-" + runId + "-primary",
                                "attempt-" + runId + "-primary",
                                "idempotency-" + runId + "-primary",
                                CONCURRENCY_PROMPT,
                                REQUEST_OUTPUT_TOKENS
                        )
                );
                boolean providerEntered = demoChatModel.awaitProviderEntry(5, TimeUnit.SECONDS);

                if (!providerEntered) {
                    throw new IllegalStateException("demo provider did not enter in time");
                }

                List<Future<CallOutcome>> competitors = new ArrayList<>();
                for (int index = 0; index < COMPETITOR_COUNT; index++) {
                    String suffix = runId + "-competitor-" + index;
                    competitors.add(executor.submit(
                            () -> invokeChat(
                                    tenant,
                                    "request-" + suffix,
                                    "attempt-" + suffix,
                                    "idempotency-" + suffix,
                                    CONCURRENCY_PROMPT,
                                    REQUEST_OUTPUT_TOKENS
                            )
                    ));
                }

                // Competing requests must finish at the reservation stage while the first reservation is IN_FLIGHT.
                for (Future<CallOutcome> competitor : competitors) {
                    competitorOutcomes.add(await(competitor));
                }
                demoChatModel.releaseProvider();
                primaryOutcome = await(primary);
            }
            finally {
                demoChatModel.releaseProvider();
            }
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failedScenario(
                    "budget-concurrency",
                    "동시 예약 검증 중 thread가 중단되었습니다.",
                    Map.of("error", "INTERRUPTED")
            );
        }
        catch (RuntimeException failure) {
            return failedScenario(
                    "budget-concurrency",
                    "동시 예약 시나리오가 완료되지 않았습니다.",
                    Map.of("error", sanitize(failure.getMessage()))
            );
        }

        long blocked = competitorOutcomes.stream()
                .filter(outcome -> outcome.state().equals("BLOCKED"))
                .count();
        boolean passed = primaryOutcome.state().equals("COMMITTED")
                && blocked == COMPETITOR_COUNT
                && demoChatModel.invocationCount() == 1;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("primary", primaryOutcome.state());
        evidence.put("competitors", competitorOutcomes.stream()
                .map(CallOutcome::state)
                .toList());
        evidence.put("admittedRequests", 1);
        evidence.put("blockedRequests", blocked);
        evidence.put("providerInvocations", demoChatModel.invocationCount());
        evidence.put("expectedProviderInvocations", 1);
        evidence.put("budget", budgetSnapshot(tenant));
        return new DemoScenarioResult(
                "budget-concurrency",
                passed ? "PASS" : "FAIL",
                passed
                        ? "원자적 예약으로 1건만 provider에 도달하고 나머지는 BLOCKED 되었습니다."
                        : "동시 예약 결과가 원자적 admission 기대와 다릅니다.",
                evidence
        );
    }

    private DemoScenarioResult idempotency(String runId) {
        demoChatModel.reset();
        String tenant = "demo-idempotency-" + runId;
        String requestId = "request-" + runId;
        String attemptId = "attempt-" + runId;
        String idempotencyKey = "idempotency-" + runId;
        CallOutcome firstOutcome = CallOutcome.failed("NOT_STARTED", "first call did not finish");
        CallOutcome duplicateOutcome = CallOutcome.failed("NOT_STARTED", "duplicate call did not finish");

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                demoChatModel.blockNextProviderCall();
                Future<CallOutcome> first = executor.submit(
                        () -> invokeChat(
                                tenant,
                                requestId,
                                attemptId,
                                idempotencyKey,
                                "The same idempotency key must not dispatch twice.",
                                REQUEST_OUTPUT_TOKENS
                        )
                );
                if (!demoChatModel.awaitProviderEntry(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("demo provider did not enter in time");
                }
                Future<CallOutcome> duplicate = executor.submit(
                        () -> invokeChat(
                                tenant,
                                requestId,
                                attemptId,
                                idempotencyKey,
                                "The same idempotency key must not dispatch twice.",
                                REQUEST_OUTPUT_TOKENS
                        )
                );
                duplicateOutcome = await(duplicate);
                demoChatModel.releaseProvider();
                firstOutcome = await(first);
            }
            finally {
                demoChatModel.releaseProvider();
            }
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failedScenario(
                    "idempotency",
                    "멱등성 검증 중 thread가 중단되었습니다.",
                    Map.of("error", "INTERRUPTED")
            );
        }
        catch (RuntimeException failure) {
            return failedScenario(
                    "idempotency",
                    "멱등성 시나리오가 완료되지 않았습니다.",
                    Map.of("error", sanitize(failure.getMessage()))
            );
        }

        boolean passed = firstOutcome.state().equals("COMMITTED")
                && duplicateOutcome.state().equals("DUPLICATE_NOT_DISPATCHED")
                && demoChatModel.invocationCount() == 1;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("first", firstOutcome.state());
        evidence.put("duplicate", duplicateOutcome.state());
        evidence.put("sameRequestId", true);
        evidence.put("sameIdempotencyKey", true);
        evidence.put("providerInvocations", demoChatModel.invocationCount());
        evidence.put("budget", budgetSnapshot(tenant));
        return new DemoScenarioResult(
                "idempotency",
                passed ? "PASS" : "FAIL",
                passed
                        ? "같은 멱등성 키의 재시도는 예약을 재사용하지만 provider를 중복 호출하지 않았습니다."
                        : "멱등성 키 재사용 결과가 기대와 다릅니다.",
                evidence
        );
    }

    private DemoScenarioResult release(String runId) {
        String tenant = "demo-release-" + runId;
        Cost amount = Cost.of(new BigDecimal("0.0001"), USD);
        BudgetDecision decision = budgetEvaluator.evaluate(
                Map.of("tenant_id", tenant),
                amount
        );
        String requestId = "request-release-" + runId;
        BudgetReservationRequest request = new BudgetReservationRequest(
                decision.key(),
                decision.limit(),
                amount,
                requestId,
                new IdempotencyKey("idempotency-release-" + runId),
                MODEL_ID,
                "default",
                CATALOG_VERSION,
                Optional.empty(),
                Optional.empty()
        );
        BudgetReservationResult reservation = budgetStateStore.checkAndReserve(request);
        ReservationTransition transition = budgetStateStore.releaseBeforeDispatch(
                reservation.reservationId()
        );
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("reservationStatus", reservation.status().name());
        evidence.put("transitionStatus", transition.status().name());
        evidence.put("resultingState", transition.resultingState().name());
        evidence.put("providerInvocations", 0);
        evidence.put("budget", budgetSnapshot(tenant));
        boolean passed = reservation.status().name().equals("CREATED")
                && transition.resultingState() == ReservationState.RELEASED
                && transition.status().isApplied();
        return new DemoScenarioResult(
                "release",
                passed ? "PASS" : "FAIL",
                passed
                        ? "provider dispatch 전 예약 실패를 안전하게 release 했습니다."
                        : "dispatch 전 release 결과가 기대와 다릅니다.",
                evidence
        );
    }

    private DemoScenarioResult reconciliation(
            String runId,
            ReconciliationMode mode
    ) {
        demoChatModel.reset();
        String tenant = "demo-reconciliation-" + mode.name().toLowerCase() + "-" + runId;
        if (mode == ReconciliationMode.PROVIDER_FAILURE) {
            demoChatModel.failNextCall();
        }
        else if (mode == ReconciliationMode.USAGE_UNKNOWN) {
            demoChatModel.omitUsageNextCall();
        }

        CallOutcome outcome = invokeChat(
                tenant,
                "request-reconciliation-" + runId,
                "attempt-reconciliation-" + runId,
                "idempotency-reconciliation-" + mode.name().toLowerCase() + "-" + runId,
                "Reconcile this deterministic demo request.",
                REQUEST_OUTPUT_TOKENS
        );
        BudgetSnapshot snapshot = readBudgetSnapshot(tenant);
        Map<String, Object> budget = budgetEvidence(snapshot);
        String accountingState = mode == ReconciliationMode.SUCCESS
                ? "COMMITTED"
                : "RECONCILIATION_REQUIRED";
        boolean pending = snapshot.pendingReconciliationLiability().value().signum() > 0;
        boolean committed = snapshot.committedCost().value().signum() > 0;
        boolean passed = switch (mode) {
            case SUCCESS -> outcome.state().equals("COMMITTED") && committed && !pending;
            case PROVIDER_FAILURE -> outcome.state().equals("PROVIDER_FAILED") && pending && !committed;
            case USAGE_UNKNOWN -> outcome.state().equals("COMMITTED") && pending && !committed;
        };
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("providerOutcome", outcome.state());
        evidence.put("providerDetail", outcome.detail());
        evidence.put("providerResponse", outcome.response());
        evidence.put("accountingState", accountingState);
        evidence.put("providerInvocations", demoChatModel.invocationCount());
        evidence.put("budget", budget);
        return new DemoScenarioResult(
                "reconciliation-" + mode.pathName,
                passed ? "PASS" : "FAIL",
                passed
                        ? mode.summary
                        : "provider 결과와 accounting 상태가 기대와 다릅니다.",
                evidence
        );
    }

    private CallOutcome invokeChat(
            String tenant,
            String requestId,
            String attemptId,
            String idempotencyKey,
            String text,
            long maxTokens
    ) {
        try {
            ChatClientResponse response = chatClientBuilder.clone()
                    .build()
                    .prompt()
                    .user(text)
                    .options(ChatOptions.builder()
                            .model(MODEL_ID)
                            .maxTokens((int) maxTokens))
                    .advisors(advisors -> advisors
                            .param("tenant_id", tenant)
                            .param("tokenpilot.request.id", requestId)
                            .param("tokenpilot.attempt.id", attemptId)
                            .param("tokenpilot.idempotency.key", new IdempotencyKey(idempotencyKey)))
                    .call()
                    .chatClientResponse();
            String responseText = response.chatResponse()
                    .getResult()
                    .getOutput()
                    .getText();
            return new CallOutcome("COMMITTED", "provider response returned", responseText);
        }
        catch (RuntimeException failure) {
            String message = sanitize(failure.getMessage());
            String state = message.contains("budget reservation rejected")
                    ? "BLOCKED"
                    : message.contains("reservation dispatch was not claimed")
                    ? "DUPLICATE_NOT_DISPATCHED"
                    : message.contains("demo provider failure")
                    ? "PROVIDER_FAILED"
                    : "FAILED";
            return CallOutcome.failed(state, message);
        }
    }

    private CallOutcome await(Future<CallOutcome> future)
            throws InterruptedException {
        try {
            return future.get(10, TimeUnit.SECONDS);
        }
        catch (ExecutionException executionFailure) {
            Throwable cause = executionFailure.getCause();
            return CallOutcome.failed(
                    "TASK_FAILED",
                    sanitize(cause == null ? executionFailure.getMessage() : cause.getMessage())
            );
        }
        catch (TimeoutException timeout) {
            return CallOutcome.failed("TIMEOUT", "demo scenario future timed out");
        }
    }

    private BudgetResult checkContext(String text, long reservedOutputTokens) {
        String framed = "USER:" + text.length() + ":" + text + "\n";
        TokenCountResult textResult = tokenEstimator.estimate(framed);
        TokenCountResult requestResult;
        if (textResult.isUnavailable()) {
            requestResult = TokenCountResult.unavailable(
                    textResult.unavailableReason().orElseThrow(),
                    TokenCountScope.REQUEST,
                    textResult.estimatorDescriptor(),
                    textResult.tokenizationBasis()
            );
        }
        else {
            requestResult = TokenCountResult.counted(
                    textResult.tokens().orElseThrow(),
                    Math.addExact(
                            textResult.safeUpperBoundTokens().orElseThrow(),
                            properties.getSpringAi().getFramingHeadroomTokens()
                    ),
                    TokenCountAccuracy.HEURISTIC,
                    TokenCountScope.REQUEST,
                    textResult.estimatorDescriptor(),
                    textResult.tokenizationBasis()
            );
        }
        return tokenBudget.check(MODEL_ID, requestResult, reservedOutputTokens);
    }

    private Map<String, Object> contextEvidence(BudgetResult result) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("status", result.status().name());
        evidence.put("reason", result.reason().name());
        evidence.put("model", result.canonicalModelId().orElse(MODEL_ID));
        evidence.put("inputEstimatedTokens", result.inputEstimatedTokens().isPresent()
                ? result.inputEstimatedTokens().getAsLong()
                : "unavailable");
        evidence.put("inputSafeUpperBoundTokens", result.inputSafeUpperBoundTokens().isPresent()
                ? result.inputSafeUpperBoundTokens().getAsLong()
                : "unavailable");
        evidence.put("reservedOutputTokens", result.reservedOutputTokens());
        evidence.put("maxContextTokens", result.maxContextTokens().isPresent()
                ? result.maxContextTokens().getAsLong()
                : "unavailable");
        evidence.put("remainingTokens", result.remainingTokens().isPresent()
                ? result.remainingTokens().getAsLong()
                : "not-provided-for-rejected-admission");
        evidence.put("estimator", result.estimatorDescriptor().estimatorId()
                + "@" + result.estimatorDescriptor().estimatorVersion());
        evidence.put("tokenizationBasis", result.tokenizationBasis().id());
        evidence.put("catalogVersion", modelRegistry.find(MODEL_ID)
                .map(model -> model.catalogVersion())
                .orElse("unknown"));
        evidence.put("scope", "REQUEST");
        return evidence;
    }

    private Map<String, Object> budgetSnapshot(String tenant) {
        return budgetEvidence(readBudgetSnapshot(tenant));
    }

    private BudgetSnapshot readBudgetSnapshot(String tenant) {
        BudgetDecision decision = budgetEvaluator.evaluate(Map.of("tenant_id", tenant));
        return budgetStateStore.snapshot(decision.key(), decision.limit());
    }

    private Map<String, Object> budgetEvidence(BudgetSnapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("committedCost", CostBoundaryFormatter.format(snapshot.committedCost()));
        result.put("activeReservedCost", CostBoundaryFormatter.format(snapshot.activeReservedCost()));
        result.put("pendingReconciliationLiability",
                CostBoundaryFormatter.format(snapshot.pendingReconciliationLiability()));
        result.put("effectiveUsage", CostBoundaryFormatter.format(snapshot.effectiveUsage()));
        result.put("remaining", CostBoundaryFormatter.format(snapshot.remaining()));
        result.put("limit", CostBoundaryFormatter.format(snapshot.limit()));
        result.put("activeReservationCount", snapshot.activeReservationIds().size());
        return result;
    }

    private Map<String, Double> metricSnapshot() {
        return Map.of(
                "preflightFits", counter(
                        "tokenpilot.preflight.requests", "decision", "fits", "reason", "within_context"
                ),
                "preflightExceeds", counter(
                        "tokenpilot.preflight.requests", "decision", "exceeds", "reason", "context_exceeded"
                ),
                "reservationsCreated", counter(
                        "tokenpilot.budget.reservations", "state", "created"
                ),
                "reservationsReused", counter(
                        "tokenpilot.budget.reservations", "state", "reused"
                ),
                "reservationsBlocked", counter(
                        "tokenpilot.budget.reservations", "state", "blocked"
                ),
                "committedCostUsd", counter(
                        "tokenpilot.cost.total", "currency", "USD"
                ),
                "reconciliationCommitted", counter(
                        "tokenpilot.reconciliation.outcomes", "outcome", "committed", "reason", "actual_usage_reported"
                ),
                "reconciliationRequired", counter(
                        "tokenpilot.reconciliation.outcomes", "outcome", "reconciliation_required", "reason", "actual_usage_unavailable"
                )
        );
    }

    private double counter(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private DemoScenarioResult failedScenario(
            String scenario,
            String summary,
            Map<String, Object> evidence
    ) {
        return new DemoScenarioResult(scenario, "FAIL", summary, evidence);
    }

    private String nextRunId() {
        return "run-" + runSequence.incrementAndGet();
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unspecified";
        }
        String normalized = value.replaceAll("\\s+", " ");
        return normalized.length() <= 160
                ? normalized
                : normalized.substring(0, 157) + "...";
    }

    private record CallOutcome(String state, String detail, String response) {

        static CallOutcome failed(String state, String detail) {
            return new CallOutcome(state, detail, "not-returned");
        }
    }

    private enum ReconciliationMode {
        SUCCESS("success", "provider usage를 actual로 commit했습니다."),
        PROVIDER_FAILURE("failure", "provider 실패 후 estimate liability를 reconciliation 대기로 보존했습니다."),
        USAGE_UNKNOWN("unknown", "provider 응답은 반환됐지만 usage 불명으로 reconciliation 대기에 보존했습니다.");

        private final String pathName;
        private final String summary;

        ReconciliationMode(String pathName, String summary) {
            this.pathName = pathName;
            this.summary = summary;
        }
    }
}
