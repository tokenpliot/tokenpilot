package io.tokenpilot.springai.internal;

import io.tokenpilot.budget.BudgetDecision;
import io.tokenpilot.budget.BudgetDecision.EvaluationType;
import io.tokenpilot.budget.BudgetEvaluator;
import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetState;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.budget.BudgetThreshold;
import io.tokenpilot.budget.BudgetWindow;
import io.tokenpilot.budget.exception.BudgetExceededException;
import io.tokenpilot.core.*;
import io.tokenpilot.core.domain.*;
import io.tokenpilot.core.exception.MissingPricingException;
import io.tokenpilot.core.internal.LedgerComponents;
import io.tokenpilot.springai.LedgerAdvisor;
import io.tokenpilot.springai.UsageExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DefaultLedgerAdvisorTest {

    @Test
    @DisplayName("AI 응답 후 사용량, 모델ID, 태그가 올바르게 LedgerManager에 기록되어야 한다")
    void recordAfterAIResponse() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        UsageExtractor extractor = mock(UsageExtractor.class);
        TokenUsage mockUsage = TokenUsage.from(100, 200);
        when(extractor.extract(any())).thenReturn(mockUsage);

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(ledgerManager, extractor);

        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model("gpt-4o")
                .build();
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(new org.springframework.ai.chat.messages.AssistantMessage("test"))), metadata);
        Map<String, Object> context = Map.of("user_id", "user-123", "tenant_id", "tenant-abc");
        ChatClientResponse response = new ChatClientResponse(chatResponse, context);

        advisor.after(response, mock(AdvisorChain.class));

        verify(ledgerManager, times(1)).record(
                eq("gpt-4o"),
                eq(mockUsage),
                argThat(tags -> tags.get("user_id").equals("user-123") && 
                               tags.get("tenant_id").equals("tenant-abc"))
        );
    }

    @Test
    @DisplayName("AI 응답 후 비용이 계산되어 BudgetStateStore에 누적되어야 한다")
    void recordBudgetAfterAIResponse() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        UsageExtractor extractor = mock(UsageExtractor.class);
        BudgetEvaluator budgetEvaluator = mock(BudgetEvaluator.class);
        BudgetStateStore budgetStateStore = mock(BudgetStateStore.class);
        CostCalculator costCalculator = mock(CostCalculator.class);
        PricingRegistry pricingRegistry = mock(PricingRegistry.class);

        TokenUsage mockUsage = TokenUsage.from(100, 200);
        PricingPlan mockPlan = new PricingPlan("gpt-4o", new BigDecimal("0.01"), new BigDecimal("0.03"), Currency.getInstance("USD"));
        PricingSnapshot snapshot = PricingSnapshot.from(
                mockPlan,
                PricingSnapshot.DEFAULT_CATALOG_VERSION,
                Instant.parse("2026-07-30T00:00:00Z")
        );
        Cost mockCost = new Cost(new BigDecimal("0.5"), Currency.getInstance("USD"));
        BudgetDecision budgetDecision = decision();

        when(extractor.extract(any())).thenReturn(mockUsage);
        when(pricingRegistry.resolveSnapshot("gpt-4o", PricingPlan.DEFAULT_PRICING_POLICY_ID))
                .thenReturn(Optional.of(snapshot));
        when(ledgerManager.record(same(snapshot), same(mockUsage), anyMap())).thenReturn(mockCost);
        when(budgetEvaluator.evaluate(anyMap())).thenReturn(budgetDecision);

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(ledgerManager, extractor, 
                budgetEvaluator, budgetStateStore, costCalculator, pricingRegistry);

        ChatClientRequest request = new ChatClientRequest(
                new Prompt("test"),
                Map.of(
                        DefaultLedgerAdvisor.MODEL_ID_CONTEXT, "gpt-4o",
                        "tenant_id", "tenant-abc"
                )
        );
        ChatClientRequest resolvedRequest = advisor.before(request, mock(AdvisorChain.class));

        ChatResponseMetadata metadata = ChatResponseMetadata.builder().model("gpt-4o").build();
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(new org.springframework.ai.chat.messages.AssistantMessage("test"))), metadata);
        ChatClientResponse response = new ChatClientResponse(chatResponse, resolvedRequest.context());

        advisor.after(response, mock(AdvisorChain.class));

        verify(budgetStateStore, times(1)).addCost(
                same(budgetDecision.key()),
                same(budgetDecision.limit()),
                same(mockCost)
        );
        verify(pricingRegistry, times(1)).resolveSnapshot("gpt-4o", PricingPlan.DEFAULT_PRICING_POLICY_ID);
        verifyNoMoreInteractions(pricingRegistry);
    }

    @Test
    @DisplayName("AI 호출 전 pricing snapshot을 만들어 요청 context에 보존해야 한다")
    void createPricingSnapshotBeforeProviderCall() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        PricingRegistry pricingRegistry = mock(PricingRegistry.class);
        PricingPlan plan = new PricingPlan(
                "gpt-4o",
                "standard",
                new BigDecimal("0.01"),
                new BigDecimal("0.03"),
                Currency.getInstance("USD")
        );
        PricingSnapshot snapshot = PricingSnapshot.from(
                plan,
                PricingSnapshot.DEFAULT_CATALOG_VERSION,
                Instant.parse("2026-07-30T00:00:00Z")
        );

        when(pricingRegistry.resolveSnapshot("gpt-4o", "standard")).thenReturn(Optional.of(snapshot));

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(
                ledgerManager,
                mock(UsageExtractor.class),
                null,
                null,
                mock(CostCalculator.class),
                pricingRegistry
        );
        ChatClientRequest request = new ChatClientRequest(
                new Prompt("test"),
                Map.of(
                        DefaultLedgerAdvisor.MODEL_ID_CONTEXT, "gpt-4o",
                        DefaultLedgerAdvisor.PRICING_POLICY_ID_CONTEXT, "standard"
                )
        );

        ChatClientRequest resolvedRequest = advisor.before(request, mock(AdvisorChain.class));

        assertThat(resolvedRequest.context().get(DefaultLedgerAdvisor.PRICING_RESOLUTION_CONTEXT))
                .isEqualTo(PricingResolution.RESOLVED);
        assertThat(resolvedRequest.context().get(DefaultLedgerAdvisor.PRICING_SNAPSHOT_CONTEXT))
                .isInstanceOfSatisfying(PricingSnapshot.class, resolvedSnapshot -> {
                    assertThat(resolvedSnapshot.modelId()).isEqualTo("gpt-4o");
                    assertThat(resolvedSnapshot.pricingPolicyId()).isEqualTo("standard");
                    assertThat(resolvedSnapshot.catalogVersion()).isEqualTo(PricingSnapshot.DEFAULT_CATALOG_VERSION);
                    assertThat(resolvedSnapshot.checkedAt()).isEqualTo(snapshot.checkedAt());
                    assertThat(resolvedSnapshot.currency()).isEqualTo(Currency.getInstance("USD"));
                    assertThat(resolvedSnapshot.rates()).containsAllEntriesOf(plan.rates());
                });

        verify(pricingRegistry, times(1)).resolveSnapshot("gpt-4o", "standard");
        verifyNoMoreInteractions(pricingRegistry);
        verifyNoInteractions(ledgerManager);
    }

    @Test
    @DisplayName("pricing snapshot rate 검증은 Core PricingEvaluator에 위임해야 한다")
    void delegateSnapshotRateValidationToPricingEvaluator() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        PricingRegistry pricingRegistry = mock(PricingRegistry.class);
        PricingEvaluator pricingEvaluator = mock(PricingEvaluator.class);
        PricingSnapshot snapshot = PricingSnapshot.from(
                new PricingPlan(
                        "gpt-4o",
                        new BigDecimal("0.01"),
                        new BigDecimal("0.03"),
                        Currency.getInstance("USD")
                ),
                PricingSnapshot.DEFAULT_CATALOG_VERSION,
                Instant.parse("2026-07-30T00:00:00Z")
        );

        when(pricingRegistry.resolveSnapshot("gpt-4o", PricingPlan.DEFAULT_PRICING_POLICY_ID))
                .thenReturn(Optional.of(snapshot));
        when(pricingEvaluator.validateSnapshotRates(any())).thenReturn(PricingResolution.MISSING_RATE);

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(
                ledgerManager,
                mock(UsageExtractor.class),
                null,
                null,
                mock(CostCalculator.class),
                pricingRegistry,
                pricingEvaluator,
                MissingPricingPolicy.FAIL_OPEN
        );
        ChatClientRequest request = new ChatClientRequest(
                new Prompt("test"),
                Map.of(DefaultLedgerAdvisor.MODEL_ID_CONTEXT, "gpt-4o")
        );

        ChatClientRequest resolvedRequest = advisor.before(request, mock(AdvisorChain.class));

        assertThat(resolvedRequest.context().get(DefaultLedgerAdvisor.PRICING_RESOLUTION_CONTEXT))
                .isEqualTo(PricingResolution.MISSING_RATE);
        assertThat(resolvedRequest.context()).doesNotContainKey(DefaultLedgerAdvisor.PRICING_SNAPSHOT_CONTEXT);
        verify(pricingEvaluator).validateSnapshotRates(
                argThat(candidate -> candidate.isPresent() && candidate.get() == snapshot)
        );
        verifyNoInteractions(ledgerManager);
    }

    @Test
    @DisplayName("Prompt options의 model과 기본 pricing policy로 snapshot을 resolve해야 한다")
    void resolvePricingSnapshotFromPromptOptions() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        PricingRegistry pricingRegistry = mock(PricingRegistry.class);
        PricingPlan plan = new PricingPlan(
                "gpt-4o",
                new BigDecimal("0.01"),
                new BigDecimal("0.03"),
                Currency.getInstance("USD")
        );
        PricingSnapshot snapshot = PricingSnapshot.from(
                plan,
                PricingSnapshot.DEFAULT_CATALOG_VERSION,
                Instant.parse("2026-07-30T00:00:00Z")
        );

        when(pricingRegistry.resolveSnapshot("gpt-4o", PricingPlan.DEFAULT_PRICING_POLICY_ID))
                .thenReturn(Optional.of(snapshot));

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(
                ledgerManager,
                mock(UsageExtractor.class),
                null,
                null,
                mock(CostCalculator.class),
                pricingRegistry
        );
        ChatClientRequest request = new ChatClientRequest(
                new Prompt(
                        "test",
                        ChatOptions.builder().model("gpt-4o").build()
                ),
                Map.of()
        );

        ChatClientRequest resolvedRequest = advisor.before(request, mock(AdvisorChain.class));

        assertThat(resolvedRequest.context().get(DefaultLedgerAdvisor.PRICING_RESOLUTION_CONTEXT))
                .isEqualTo(PricingResolution.RESOLVED);
        assertThat(resolvedRequest.context().get(DefaultLedgerAdvisor.PRICING_SNAPSHOT_CONTEXT))
                .isSameAs(snapshot);
        verify(pricingRegistry, times(1))
                .resolveSnapshot("gpt-4o", PricingPlan.DEFAULT_PRICING_POLICY_ID);
        verifyNoMoreInteractions(pricingRegistry);
        verifyNoInteractions(ledgerManager);
    }

    @Test
    @DisplayName("AI 호출 전 completion rate가 없는 부분 snapshot은 MISSING_RATE여야 한다")
    void resolvePartialPricingSnapshotAsMissingRateBeforeProviderCall() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        PricingRegistry pricingRegistry = mock(PricingRegistry.class);
        PricingPlan plan = new PricingPlan(
                "prompt-only-model",
                Map.of(TokenType.PROMPT, new BigDecimal("0.01")),
                Currency.getInstance("USD")
        );
        PricingSnapshot snapshot = PricingSnapshot.from(
                plan,
                PricingSnapshot.DEFAULT_CATALOG_VERSION,
                Instant.parse("2026-07-30T00:00:00Z")
        );

        when(pricingRegistry.resolveSnapshot("prompt-only-model", PricingPlan.DEFAULT_PRICING_POLICY_ID))
                .thenReturn(Optional.of(snapshot));

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(
                ledgerManager,
                mock(UsageExtractor.class),
                null,
                null,
                mock(CostCalculator.class),
                pricingRegistry
        );
        ChatClientRequest request = new ChatClientRequest(
                new Prompt("test"),
                Map.of(DefaultLedgerAdvisor.MODEL_ID_CONTEXT, "prompt-only-model")
        );

        ChatClientRequest resolvedRequest = advisor.before(request, mock(AdvisorChain.class));

        assertThat(resolvedRequest.context().get(DefaultLedgerAdvisor.PRICING_RESOLUTION_CONTEXT))
                .isEqualTo(PricingResolution.MISSING_RATE);
        verify(pricingRegistry, times(1))
                .resolveSnapshot("prompt-only-model", PricingPlan.DEFAULT_PRICING_POLICY_ID);
        verifyNoMoreInteractions(pricingRegistry);
        verifyNoInteractions(ledgerManager);
    }

    @Test
    @DisplayName("AI 응답 후 actual reconciliation은 registry를 다시 조회하지 않고 snapshot으로 기록해야 한다")
    void reconcileActualWithPricingSnapshot() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        UsageExtractor extractor = mock(UsageExtractor.class);
        PricingRegistry pricingRegistry = mock(PricingRegistry.class);
        TokenUsage usage = TokenUsage.from(100, 200);
        PricingPlan plan = new PricingPlan(
                "gpt-4o",
                "standard",
                new BigDecimal("0.01"),
                new BigDecimal("0.03"),
                Currency.getInstance("USD")
        );
        PricingSnapshot snapshot = PricingSnapshot.from(
                plan,
                PricingSnapshot.DEFAULT_CATALOG_VERSION,
                Instant.parse("2026-07-30T00:00:00Z")
        );

        when(extractor.extract(any())).thenReturn(usage);
        when(pricingRegistry.resolveSnapshot("gpt-4o", "standard")).thenReturn(Optional.of(snapshot));

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(
                ledgerManager,
                extractor,
                null,
                null,
                mock(CostCalculator.class),
                pricingRegistry
        );
        ChatClientRequest request = new ChatClientRequest(
                new Prompt("test"),
                Map.of(
                        DefaultLedgerAdvisor.MODEL_ID_CONTEXT, "gpt-4o",
                        DefaultLedgerAdvisor.PRICING_POLICY_ID_CONTEXT, "standard"
                )
        );
        ChatClientRequest resolvedRequest = advisor.before(request, mock(AdvisorChain.class));

        ChatClientResponse reconciledResponse = advisor.after(
                response("gpt-4o", resolvedRequest.context()),
                mock(AdvisorChain.class)
        );

        assertThat(reconciledResponse.context().get(DefaultLedgerAdvisor.PRICING_RECONCILIATION_RESULT_CONTEXT))
                .isEqualTo(PricingReconciliationResult.RECONCILED);

        verify(pricingRegistry, times(1)).resolveSnapshot("gpt-4o", "standard");
        verifyNoMoreInteractions(pricingRegistry);
        verify(ledgerManager, times(1)).record(same(snapshot), same(usage), anyMap());
        verify(ledgerManager, never()).record(eq("gpt-4o"), same(usage), anyMap());
    }

    @Test
    @DisplayName("actual usage에 필요한 rate가 없으면 UNPRICED로 남기고 비용을 기록하지 않아야 한다")
    void leaveActualUsageUnpricedWhenRequiredRateIsMissing() {
        UsageExtractor extractor = mock(UsageExtractor.class);
        LedgerListener listener = mock(LedgerListener.class);
        CostCalculator costCalculator = LedgerComponents.defaultCostCalculator();
        LedgerManager ledgerManager = LedgerComponents.defaultLedgerManager(
                mock(PricingRegistry.class),
                costCalculator,
                List.of(listener)
        );
        TokenUsage usage = TokenUsage.from(1_000, 1_000);
        PricingPlan plan = new PricingPlan(
                "prompt-only-model",
                Map.of(TokenType.PROMPT, new BigDecimal("0.01")),
                Currency.getInstance("USD")
        );
        PricingSnapshot snapshot = PricingSnapshot.from(
                plan,
                PricingSnapshot.DEFAULT_CATALOG_VERSION,
                Instant.parse("2026-07-30T00:00:00Z")
        );

        when(extractor.extract(any())).thenReturn(usage);

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(ledgerManager, extractor);
        ChatClientResponse response = response(
                "prompt-only-model",
                Map.of(
                        DefaultLedgerAdvisor.PRICING_SNAPSHOT_CONTEXT, snapshot,
                        DefaultLedgerAdvisor.PRICING_RESOLUTION_CONTEXT, PricingResolution.RESOLVED
                )
        );

        ChatClientResponse unpricedResponse = advisor.after(response, mock(AdvisorChain.class));

        assertThat(unpricedResponse.context().get(DefaultLedgerAdvisor.PRICING_RESOLUTION_CONTEXT))
                .isEqualTo(PricingResolution.MISSING_RATE);
        assertThat(unpricedResponse.context().get(DefaultLedgerAdvisor.PRICING_RECONCILIATION_RESULT_CONTEXT))
                .isEqualTo(PricingReconciliationResult.UNPRICED);
        verifyNoInteractions(listener);
    }

    @Test
    @DisplayName("FAIL_CLOSED에서 actual usage의 rate가 없으면 reconciliation을 실패시켜야 한다")
    void failActualReconciliationWhenRequiredRateIsMissingAndPolicyIsFailClosed() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        UsageExtractor extractor = mock(UsageExtractor.class);
        BudgetStateStore budgetStateStore = mock(BudgetStateStore.class);
        TokenUsage usage = TokenUsage.from(1_000, 1_000);
        PricingPlan plan = new PricingPlan(
                "prompt-only-model",
                Map.of(TokenType.PROMPT, new BigDecimal("0.01")),
                Currency.getInstance("USD")
        );
        PricingSnapshot snapshot = PricingSnapshot.from(
                plan,
                PricingSnapshot.DEFAULT_CATALOG_VERSION,
                Instant.parse("2026-07-30T00:00:00Z")
        );

        when(extractor.extract(any())).thenReturn(usage);
        when(ledgerManager.record(same(snapshot), same(usage), anyMap()))
                .thenThrow(new MissingPricingException(PricingResolution.MISSING_RATE));

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(
                ledgerManager,
                extractor,
                null,
                budgetStateStore,
                mock(CostCalculator.class),
                null,
                MissingPricingPolicy.FAIL_CLOSED
        );
        ChatClientResponse response = response(
                "prompt-only-model",
                Map.of(
                        DefaultLedgerAdvisor.PRICING_SNAPSHOT_CONTEXT, snapshot,
                        DefaultLedgerAdvisor.PRICING_RESOLUTION_CONTEXT, PricingResolution.RESOLVED
                )
        );

        assertThatThrownBy(() -> advisor.after(response, mock(AdvisorChain.class)))
                .isInstanceOf(MissingPricingException.class)
                .extracting(exception -> ((MissingPricingException) exception).getResolution())
                .isEqualTo(PricingResolution.MISSING_RATE);

        verifyNoInteractions(budgetStateStore);
    }

    @Test
    @DisplayName("explicit zero는 FAIL_CLOSED에서도 RESOLVED로 처리하고 0원 cost로 reconcile해야 한다")
    void explicitZeroResolvesAndReconcilesWithZeroCost() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        UsageExtractor extractor = mock(UsageExtractor.class);
        PricingRegistry pricingRegistry = mock(PricingRegistry.class);
        TokenUsage usage = TokenUsage.from(100, 200);
        PricingPlan plan = new PricingPlan(
                "free-model",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Currency.getInstance("USD")
        );
        PricingSnapshot snapshot = PricingSnapshot.from(
                plan,
                PricingSnapshot.DEFAULT_CATALOG_VERSION,
                Instant.parse("2026-07-30T00:00:00Z")
        );
        Cost zeroCost = Cost.zero(Currency.getInstance("USD"));

        when(extractor.extract(any())).thenReturn(usage);
        when(pricingRegistry.resolveSnapshot("free-model", PricingPlan.DEFAULT_PRICING_POLICY_ID))
                .thenReturn(Optional.of(snapshot));
        when(ledgerManager.record(same(snapshot), same(usage), anyMap())).thenReturn(zeroCost);

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(
                ledgerManager,
                extractor,
                null,
                null,
                mock(CostCalculator.class),
                pricingRegistry,
                MissingPricingPolicy.FAIL_CLOSED
        );
        ChatClientRequest request = new ChatClientRequest(
                new Prompt("test"),
                Map.of(DefaultLedgerAdvisor.MODEL_ID_CONTEXT, "free-model")
        );

        ChatClientRequest resolvedRequest = advisor.before(request, mock(AdvisorChain.class));

        assertThat(resolvedRequest.context().get(DefaultLedgerAdvisor.PRICING_RESOLUTION_CONTEXT))
                .isEqualTo(PricingResolution.RESOLVED);
        assertThat(resolvedRequest.context().get(DefaultLedgerAdvisor.PRICING_SNAPSHOT_CONTEXT))
                .isSameAs(snapshot);

        ChatClientResponse reconciledResponse = advisor.after(
                response("free-model", resolvedRequest.context()),
                mock(AdvisorChain.class)
        );

        assertThat(reconciledResponse.context().get(DefaultLedgerAdvisor.PRICING_RECONCILIATION_RESULT_CONTEXT))
                .isEqualTo(PricingReconciliationResult.RECONCILED);
        assertThat(reconciledResponse.context().get(DefaultLedgerAdvisor.PRICING_RECONCILIATION_RESULT_CONTEXT))
                .isNotEqualTo(PricingReconciliationResult.UNPRICED);
        verify(ledgerManager, times(1)).record(same(snapshot), same(usage), anyMap());
    }

    @Test
    @DisplayName("response model이 snapshot model과 다르면 기존 snapshot을 자동 적용하지 않아야 한다")
    void requireReconciliationWhenResponseModelDiffersFromSnapshotModel() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        UsageExtractor extractor = mock(UsageExtractor.class);
        PricingRegistry pricingRegistry = mock(PricingRegistry.class);
        TokenUsage usage = TokenUsage.from(100, 200);
        PricingPlan plan = new PricingPlan(
                "gpt-4o-mini",
                "standard",
                new BigDecimal("0.01"),
                new BigDecimal("0.03"),
                Currency.getInstance("USD")
        );
        PricingSnapshot snapshot = PricingSnapshot.from(
                plan,
                PricingSnapshot.DEFAULT_CATALOG_VERSION,
                Instant.parse("2026-07-30T00:00:00Z")
        );

        when(extractor.extract(any())).thenReturn(usage);
        when(pricingRegistry.resolveSnapshot("gpt-4o-mini", "standard")).thenReturn(Optional.of(snapshot));

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(
                ledgerManager,
                extractor,
                null,
                null,
                mock(CostCalculator.class),
                pricingRegistry
        );
        ChatClientRequest request = new ChatClientRequest(
                new Prompt("test"),
                Map.of(
                        DefaultLedgerAdvisor.MODEL_ID_CONTEXT, "gpt-4o-mini",
                        DefaultLedgerAdvisor.PRICING_POLICY_ID_CONTEXT, "standard"
                )
        );
        ChatClientRequest resolvedRequest = advisor.before(request, mock(AdvisorChain.class));

        ChatClientResponse result = advisor.after(
                response("gpt-4o", resolvedRequest.context()),
                mock(AdvisorChain.class)
        );

        assertThat(result.context().get(DefaultLedgerAdvisor.PRICING_RECONCILIATION_RESULT_CONTEXT))
                .isEqualTo(PricingReconciliationResult.RECONCILIATION_REQUIRED);
        verify(pricingRegistry, times(1)).resolveSnapshot("gpt-4o-mini", "standard");
        verifyNoMoreInteractions(pricingRegistry);
        verifyNoInteractions(ledgerManager);
    }

    @Test
    @DisplayName("actual model reconciliation 판단은 Core PricingEvaluator에 위임해야 한다")
    void delegateReconciliationDecisionToPricingEvaluator() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        UsageExtractor extractor = mock(UsageExtractor.class);
        PricingEvaluator pricingEvaluator = mock(PricingEvaluator.class);
        PricingSnapshot snapshot = PricingSnapshot.from(
                new PricingPlan(
                        "gpt-4o-mini",
                        new BigDecimal("0.01"),
                        new BigDecimal("0.03"),
                        Currency.getInstance("USD")
                ),
                PricingSnapshot.DEFAULT_CATALOG_VERSION,
                Instant.parse("2026-07-30T00:00:00Z")
        );

        when(extractor.extract(any())).thenReturn(TokenUsage.from(100, 200));
        when(pricingEvaluator.determineReconciliation(Optional.of(snapshot), "gpt-4o"))
                .thenReturn(PricingReconciliationResult.RECONCILIATION_REQUIRED);

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(
                ledgerManager,
                extractor,
                null,
                null,
                mock(CostCalculator.class),
                null,
                pricingEvaluator,
                MissingPricingPolicy.FAIL_OPEN
        );

        ChatClientResponse result = advisor.after(
                response(
                        "gpt-4o",
                        Map.of(
                                DefaultLedgerAdvisor.PRICING_SNAPSHOT_CONTEXT, snapshot,
                                DefaultLedgerAdvisor.PRICING_RESOLUTION_CONTEXT, PricingResolution.RESOLVED
                        )
                ),
                mock(AdvisorChain.class)
        );

        assertThat(result.context().get(DefaultLedgerAdvisor.PRICING_RECONCILIATION_RESULT_CONTEXT))
                .isEqualTo(PricingReconciliationResult.RECONCILIATION_REQUIRED);
        verify(pricingEvaluator).determineReconciliation(Optional.of(snapshot), "gpt-4o");
        verifyNoInteractions(ledgerManager);
    }

    @Test
    @DisplayName("FAIL_OPEN은 missing pricing이어도 provider 호출을 허용하고 UNPRICED로 남겨야 한다")
    void failOpenAllowsProviderCallAndMarksMissingPricingAsUnpriced() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        UsageExtractor extractor = mock(UsageExtractor.class);
        PricingRegistry pricingRegistry = mock(PricingRegistry.class);
        TokenUsage usage = TokenUsage.from(100, 200);

        when(extractor.extract(any())).thenReturn(usage);
        when(pricingRegistry.resolveSnapshot("missing-model", PricingPlan.DEFAULT_PRICING_POLICY_ID))
                .thenReturn(Optional.empty());

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(
                ledgerManager,
                extractor,
                null,
                null,
                mock(CostCalculator.class),
                pricingRegistry
        );
        ChatClientRequest request = new ChatClientRequest(
                new Prompt("test"),
                Map.of(DefaultLedgerAdvisor.MODEL_ID_CONTEXT, "missing-model")
        );

        ChatClientRequest resolvedRequest = advisor.before(request, mock(AdvisorChain.class));

        assertThat(resolvedRequest.context().get(DefaultLedgerAdvisor.PRICING_RESOLUTION_CONTEXT))
                .isEqualTo(PricingResolution.MISSING_PLAN);
        assertThat(resolvedRequest.context()).doesNotContainKey(DefaultLedgerAdvisor.PRICING_SNAPSHOT_CONTEXT);

        ChatClientResponse unpricedResponse = advisor.after(
                response("missing-model", resolvedRequest.context()),
                mock(AdvisorChain.class)
        );

        assertThat(unpricedResponse.context().get(DefaultLedgerAdvisor.PRICING_RECONCILIATION_RESULT_CONTEXT))
                .isEqualTo(PricingReconciliationResult.UNPRICED);

        verify(pricingRegistry, times(1)).resolveSnapshot("missing-model", PricingPlan.DEFAULT_PRICING_POLICY_ID);
        verifyNoMoreInteractions(pricingRegistry);
        verifyNoInteractions(ledgerManager);
    }

    @Test
    @DisplayName("pre-call missing pricing은 provider 전 한 번만 발행하고 after에서 중복하지 않는다")
    void publishesPreCallPricingMissExactlyOnce() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        UsageExtractor extractor = mock(UsageExtractor.class);
        PricingRegistry pricingRegistry = mock(PricingRegistry.class);
        List<PricingMissingEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        when(extractor.extract(any())).thenReturn(TokenUsage.from(100, 200));
        when(pricingRegistry.resolveSnapshot("missing-model", PricingPlan.DEFAULT_PRICING_POLICY_ID))
                .thenReturn(Optional.empty());

        LedgerAdvisor advisor = LedgerSpringAiComponents.defaultLedgerAdvisor(
                ledgerManager,
                extractor,
                null,
                null,
                mock(CostCalculator.class),
                pricingRegistry,
                LedgerComponents.defaultPricingEvaluator(),
                MissingPricingPolicy.FAIL_OPEN,
                List.of(events::add)
        );
        ChatClientRequest request = new ChatClientRequest(
                new Prompt("test"),
                Map.of(DefaultLedgerAdvisor.MODEL_ID_CONTEXT, "missing-model")
        );

        ChatClientRequest resolved = advisor.before(request, mock(AdvisorChain.class));
        advisor.after(
                response("missing-model", resolved.context()),
                mock(AdvisorChain.class)
        );

        assertThat(events).containsExactly(new PricingMissingEvent(
                MissingPricingPolicy.FAIL_OPEN,
                PricingResolution.MISSING_PLAN
        ));
    }

    @Test
    @DisplayName("actual cost 계산의 MissingPricingException도 한 번 발행한다")
    void publishesActualPricingMissExactlyOnce() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        UsageExtractor extractor = mock(UsageExtractor.class);
        List<PricingMissingEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        TokenUsage usage = TokenUsage.from(100, 200);
        PricingSnapshot snapshot = PricingSnapshot.from(
                new PricingPlan(
                        "gpt-4o",
                        new BigDecimal("0.01"),
                        new BigDecimal("0.03"),
                        Currency.getInstance("USD")
                ),
                PricingSnapshot.DEFAULT_CATALOG_VERSION,
                Instant.parse("2026-07-30T00:00:00Z")
        );

        when(extractor.extract(any())).thenReturn(usage);
        when(ledgerManager.record(same(snapshot), same(usage), anyMap()))
                .thenThrow(new MissingPricingException(PricingResolution.MISSING_RATE));

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(
                ledgerManager,
                extractor,
                null,
                null,
                mock(CostCalculator.class),
                null,
                LedgerComponents.defaultPricingEvaluator(),
                MissingPricingPolicy.FAIL_OPEN,
                List.of(events::add)
        );

        ChatClientResponse result = advisor.after(
                response(
                        "gpt-4o",
                        Map.of(
                                DefaultLedgerAdvisor.PRICING_SNAPSHOT_CONTEXT, snapshot,
                                DefaultLedgerAdvisor.PRICING_RESOLUTION_CONTEXT, PricingResolution.RESOLVED
                        )
                ),
                mock(AdvisorChain.class)
        );

        assertThat(result.context().get(DefaultLedgerAdvisor.PRICING_RECONCILIATION_RESULT_CONTEXT))
                .isEqualTo(PricingReconciliationResult.UNPRICED);
        assertThat(events).containsExactly(new PricingMissingEvent(
                MissingPricingPolicy.FAIL_OPEN,
                PricingResolution.MISSING_RATE
        ));
    }

    @Test
    @DisplayName("legacy actual pricing miss는 한 번 발행하고 기존 예외를 그대로 전파한다")
    void publishesLegacyActualPricingMissWithoutChangingFailureSemantics() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        UsageExtractor extractor = mock(UsageExtractor.class);
        List<PricingMissingEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        TokenUsage usage = TokenUsage.from(100, 200);
        MissingPricingException failure = new MissingPricingException(PricingResolution.MISSING_PLAN);

        when(extractor.extract(any())).thenReturn(usage);
        when(ledgerManager.record(eq("missing-model"), same(usage), anyMap()))
                .thenThrow(failure);

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(
                ledgerManager,
                extractor,
                null,
                null,
                null,
                null,
                LedgerComponents.defaultPricingEvaluator(),
                MissingPricingPolicy.FAIL_OPEN,
                List.of(events::add)
        );

        assertThatThrownBy(() -> advisor.after(
                response("missing-model", Map.of()),
                mock(AdvisorChain.class)
        )).isSameAs(failure);
        assertThat(events).containsExactly(new PricingMissingEvent(
                MissingPricingPolicy.FAIL_OPEN,
                PricingResolution.MISSING_PLAN
        ));
    }

    @Test
    @DisplayName("pricing miss listener 실패는 fail-closed 판정과 다음 listener를 바꾸지 않는다")
    void isolatesPricingMissListenerFailures() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        PricingRegistry pricingRegistry = mock(PricingRegistry.class);
        AtomicInteger failedDeliveries = new AtomicInteger();
        List<PricingMissingEvent> received = new java.util.concurrent.CopyOnWriteArrayList<>();

        when(pricingRegistry.resolveSnapshot("missing-model", PricingPlan.DEFAULT_PRICING_POLICY_ID))
                .thenReturn(Optional.empty());

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(
                ledgerManager,
                mock(UsageExtractor.class),
                null,
                null,
                mock(CostCalculator.class),
                pricingRegistry,
                LedgerComponents.defaultPricingEvaluator(),
                MissingPricingPolicy.FAIL_CLOSED,
                List.of(
                        event -> {
                            failedDeliveries.incrementAndGet();
                            throw new IllegalStateException("listener failed");
                        },
                        received::add
                )
        );
        ChatClientRequest request = new ChatClientRequest(
                new Prompt("test"),
                Map.of(DefaultLedgerAdvisor.MODEL_ID_CONTEXT, "missing-model")
        );

        assertThatThrownBy(() -> advisor.before(request, mock(AdvisorChain.class)))
                .isInstanceOf(MissingPricingException.class)
                .extracting(exception -> ((MissingPricingException) exception).getResolution())
                .isEqualTo(PricingResolution.MISSING_PLAN);
        assertThat(failedDeliveries).hasValue(1);
        assertThat(received).containsExactly(new PricingMissingEvent(
                MissingPricingPolicy.FAIL_CLOSED,
                PricingResolution.MISSING_PLAN
        ));
        verifyNoInteractions(ledgerManager);
    }

    @Test
    @DisplayName("FAIL_OPEN은 model id가 없어도 MISSING_PLAN을 보존하고 UNPRICED로 남겨야 한다")
    void failOpenPreservesMissingPlanWhenModelIdIsMissing() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        UsageExtractor extractor = mock(UsageExtractor.class);
        PricingRegistry pricingRegistry = mock(PricingRegistry.class);
        TokenUsage usage = TokenUsage.from(100, 200);

        when(extractor.extract(any())).thenReturn(usage);

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(
                ledgerManager,
                extractor,
                null,
                null,
                mock(CostCalculator.class),
                pricingRegistry
        );
        ChatClientRequest request = new ChatClientRequest(
                new Prompt("test"),
                Map.of()
        );

        ChatClientRequest resolvedRequest = advisor.before(request, mock(AdvisorChain.class));

        assertThat(resolvedRequest.context().get(DefaultLedgerAdvisor.PRICING_RESOLUTION_CONTEXT))
                .isEqualTo(PricingResolution.MISSING_PLAN);
        assertThat(resolvedRequest.context()).doesNotContainKey(DefaultLedgerAdvisor.PRICING_SNAPSHOT_CONTEXT);

        ChatClientResponse unpricedResponse = advisor.after(
                response("unknown-model", resolvedRequest.context()),
                mock(AdvisorChain.class)
        );

        assertThat(unpricedResponse.context().get(DefaultLedgerAdvisor.PRICING_RECONCILIATION_RESULT_CONTEXT))
                .isEqualTo(PricingReconciliationResult.UNPRICED);
        verifyNoInteractions(pricingRegistry);
        verifyNoInteractions(ledgerManager);
    }

    @Test
    @DisplayName("missing pricing은 CostBound 실패로 전파할 PricingResolution을 보존해야 한다")
    void preserveMissingPricingResolutionForCostBoundFailure() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        UsageExtractor extractor = mock(UsageExtractor.class);
        PricingRegistry pricingRegistry = mock(PricingRegistry.class);
        TokenUsage usage = TokenUsage.from(100, 200);

        when(extractor.extract(any())).thenReturn(usage);
        when(pricingRegistry.resolveSnapshot("missing-model", PricingPlan.DEFAULT_PRICING_POLICY_ID))
                .thenReturn(Optional.empty());

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(
                ledgerManager,
                extractor,
                null,
                null,
                mock(CostCalculator.class),
                pricingRegistry
        );
        ChatClientRequest request = new ChatClientRequest(
                new Prompt("test"),
                Map.of(DefaultLedgerAdvisor.MODEL_ID_CONTEXT, "missing-model")
        );
        ChatClientRequest resolvedRequest = advisor.before(request, mock(AdvisorChain.class));

        ChatClientResponse unpricedResponse = advisor.after(
                response("missing-model", resolvedRequest.context()),
                mock(AdvisorChain.class)
        );

        assertThat(unpricedResponse.context().get(DefaultLedgerAdvisor.PRICING_RESOLUTION_CONTEXT))
                .isEqualTo(PricingResolution.MISSING_PLAN);
        assertThat(unpricedResponse.context().get(DefaultLedgerAdvisor.PRICING_RECONCILIATION_RESULT_CONTEXT))
                .isEqualTo(PricingReconciliationResult.UNPRICED);
        verifyNoInteractions(ledgerManager);
    }

    @Test
    @DisplayName("FAIL_CLOSED는 provider 호출 전에 missing pricing을 차단하고 invocation count를 0으로 유지해야 한다")
    void failClosedBlocksMissingPricingBeforeProviderCall() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        UsageExtractor extractor = mock(UsageExtractor.class);
        PricingRegistry pricingRegistry = mock(PricingRegistry.class);
        AtomicInteger providerInvocationCount = new AtomicInteger();

        when(pricingRegistry.resolveSnapshot("missing-model", PricingPlan.DEFAULT_PRICING_POLICY_ID))
                .thenReturn(Optional.empty());

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(
                ledgerManager,
                extractor,
                null,
                null,
                mock(CostCalculator.class),
                pricingRegistry,
                MissingPricingPolicy.FAIL_CLOSED
        );
        ChatClientRequest request = new ChatClientRequest(
                new Prompt("test"),
                Map.of(DefaultLedgerAdvisor.MODEL_ID_CONTEXT, "missing-model")
        );

        assertThatThrownBy(() -> {
            advisor.before(request, mock(AdvisorChain.class));
            providerInvocationCount.incrementAndGet();
        })
                .isInstanceOf(MissingPricingException.class)
                .hasMessage("MISSING_PLAN")
                .extracting(exception -> ((MissingPricingException) exception).getResolution())
                .isEqualTo(PricingResolution.MISSING_PLAN);

        assertThat(providerInvocationCount).hasValue(0);
        verify(pricingRegistry, times(1)).resolveSnapshot("missing-model", PricingPlan.DEFAULT_PRICING_POLICY_ID);
        verifyNoMoreInteractions(pricingRegistry);
        verifyNoInteractions(ledgerManager);
    }

    @Test
    @DisplayName("FAIL_CLOSED는 model id가 없으면 provider 호출 전에 차단하고 invocation count를 0으로 유지해야 한다")
    void failClosedBlocksMissingModelIdBeforeProviderCall() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        UsageExtractor extractor = mock(UsageExtractor.class);
        PricingRegistry pricingRegistry = mock(PricingRegistry.class);
        AtomicInteger providerInvocationCount = new AtomicInteger();

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(
                ledgerManager,
                extractor,
                null,
                null,
                mock(CostCalculator.class),
                pricingRegistry,
                MissingPricingPolicy.FAIL_CLOSED
        );
        ChatClientRequest request = new ChatClientRequest(
                new Prompt("test"),
                Map.of()
        );

        assertThatThrownBy(() -> {
            advisor.before(request, mock(AdvisorChain.class));
            providerInvocationCount.incrementAndGet();
        })
                .isInstanceOf(MissingPricingException.class)
                .hasMessage("MISSING_PLAN")
                .extracting(exception -> ((MissingPricingException) exception).getResolution())
                .isEqualTo(PricingResolution.MISSING_PLAN);

        assertThat(providerInvocationCount).hasValue(0);
        verifyNoInteractions(pricingRegistry);
        verifyNoInteractions(ledgerManager);
    }

    @Test
    @DisplayName("FAIL_CLOSED는 provider 호출 전에 MISSING_RATE를 차단하고 invocation count를 0으로 유지해야 한다")
    void failClosedBlocksMissingRateBeforeProviderCall() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        UsageExtractor extractor = mock(UsageExtractor.class);
        PricingRegistry pricingRegistry = mock(PricingRegistry.class);
        AtomicInteger providerInvocationCount = new AtomicInteger();
        PricingPlan plan = new PricingPlan(
                "prompt-only-model",
                Map.of(TokenType.PROMPT, new BigDecimal("0.01")),
                Currency.getInstance("USD")
        );
        PricingSnapshot snapshot = PricingSnapshot.from(
                plan,
                PricingSnapshot.DEFAULT_CATALOG_VERSION,
                Instant.parse("2026-07-30T00:00:00Z")
        );

        when(pricingRegistry.resolveSnapshot("prompt-only-model", PricingPlan.DEFAULT_PRICING_POLICY_ID))
                .thenReturn(Optional.of(snapshot));

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(
                ledgerManager,
                extractor,
                null,
                null,
                mock(CostCalculator.class),
                pricingRegistry,
                MissingPricingPolicy.FAIL_CLOSED
        );
        ChatClientRequest request = new ChatClientRequest(
                new Prompt("test"),
                Map.of(DefaultLedgerAdvisor.MODEL_ID_CONTEXT, "prompt-only-model")
        );

        assertThatThrownBy(() -> {
            advisor.before(request, mock(AdvisorChain.class));
            providerInvocationCount.incrementAndGet();
        })
                .isInstanceOf(MissingPricingException.class)
                .hasMessage("MISSING_RATE")
                .extracting(exception -> ((MissingPricingException) exception).getResolution())
                .isEqualTo(PricingResolution.MISSING_RATE);

        assertThat(providerInvocationCount).hasValue(0);
        verify(pricingRegistry, times(1)).resolveSnapshot("prompt-only-model", PricingPlan.DEFAULT_PRICING_POLICY_ID);
        verifyNoMoreInteractions(pricingRegistry);
        verifyNoInteractions(ledgerManager);
    }

    @Test
    @DisplayName("AI 호출 전 BudgetEvaluator를 통해 예산을 체크해야 한다")
    void checkBudgetBeforeAIRequest() {
        BudgetEvaluator budgetEvaluator = mock(BudgetEvaluator.class);
        BudgetDecision decision = decision(BudgetState.ALLOW);
        when(budgetEvaluator.evaluate(anyMap())).thenReturn(decision);
        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(mock(LedgerManager.class), mock(UsageExtractor.class),
                budgetEvaluator, null, null, null);

        ChatClientRequest request = new ChatClientRequest(
                new Prompt("test"),
                Map.of("tenant_id", "tenant-abc")
        );

        ChatClientRequest resolvedRequest = advisor.before(request, mock(AdvisorChain.class));

        verify(budgetEvaluator, times(1)).evaluate(
                argThat(tags -> tags.get("tenant_id").equals("tenant-abc"))
        );
        assertThat(resolvedRequest.context().get(DefaultLedgerAdvisor.BUDGET_DECISION_CONTEXT))
                .isSameAs(decision);
    }

    @Test
    @DisplayName("BLOCK이면 provider 호출 전 예외로 중단하고 ledger와 budget을 변경하지 않아야 한다")
    void blockBeforeProviderInvocation() {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        UsageExtractor extractor = mock(UsageExtractor.class);
        BudgetEvaluator budgetEvaluator = mock(BudgetEvaluator.class);
        BudgetStateStore budgetStateStore = mock(BudgetStateStore.class);
        CostCalculator costCalculator = mock(CostCalculator.class);
        PricingRegistry pricingRegistry = mock(PricingRegistry.class);
        CallAdvisorChain providerChain = mock(CallAdvisorChain.class);
        BudgetDecision blockDecision = decision(BudgetState.BLOCK);
        when(budgetEvaluator.evaluate(anyMap())).thenReturn(blockDecision);

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(
                ledgerManager,
                extractor,
                budgetEvaluator,
                budgetStateStore,
                costCalculator,
                pricingRegistry
        );
        ChatClientRequest request = new ChatClientRequest(
                new Prompt("test"),
                Map.of("tenant_id", "tenant-abc")
        );

        assertThatThrownBy(() -> advisor.adviseCall(request, providerChain))
                .isInstanceOf(BudgetExceededException.class)
                .satisfies(throwable -> {
                    BudgetDecision preserved =
                            ((BudgetExceededException) throwable).getDecision();
                    assertThat(preserved).isSameAs(blockDecision);
                    assertThat(preserved.reason()).isEqualTo("blocked");
                    assertThat(preserved.projectedUsage()).isEqualTo(usd("100.00"));
                    assertThat(preserved.limit()).isEqualTo(usd("100.00"));
                    assertThat(preserved.limit().currency()).isEqualTo(USD);
                });

        verify(budgetEvaluator).evaluate(
                argThat(tags -> tags.get("tenant_id").equals("tenant-abc"))
        );
        verifyNoInteractions(
                providerChain,
                ledgerManager,
                extractor,
                budgetStateStore,
                costCalculator,
                pricingRegistry
        );
    }

    @ParameterizedTest
    @EnumSource(value = BudgetState.class, names = {"ALLOW", "WARN"})
    @DisplayName("ALLOW와 WARN만 provider chain으로 진행해야 한다")
    void allowAndWarnProceedToProvider(BudgetState state) {
        LedgerManager ledgerManager = mock(LedgerManager.class);
        UsageExtractor extractor = mock(UsageExtractor.class);
        BudgetEvaluator budgetEvaluator = mock(BudgetEvaluator.class);
        CallAdvisorChain providerChain = mock(CallAdvisorChain.class);
        BudgetDecision decision = decision(state);
        TokenUsage usage = TokenUsage.from(0, 0);
        when(budgetEvaluator.evaluate(anyMap())).thenReturn(decision);
        when(extractor.extract(any())).thenReturn(usage);
        when(providerChain.nextCall(any())).thenAnswer(invocation -> {
            ChatClientRequest resolvedRequest = invocation.getArgument(0);
            return new ChatClientResponse(null, resolvedRequest.context());
        });
        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(
                ledgerManager,
                extractor,
                budgetEvaluator,
                null,
                null,
                null
        );
        ChatClientRequest request = new ChatClientRequest(
                new Prompt("test"),
                Map.of("tenant_id", "tenant-abc")
        );

        ChatClientResponse response = advisor.adviseCall(request, providerChain);

        verify(providerChain).nextCall(any(ChatClientRequest.class));
        verify(ledgerManager).record("unknown-model", usage, Map.of("tenant_id", "tenant-abc"));
        assertThat(response.context().get(DefaultLedgerAdvisor.BUDGET_DECISION_CONTEXT))
                .isSameAs(decision);
    }

    @Test
    @DisplayName("CURRENCY_MISMATCH는 fail-closed하고 provider chain으로 진행하지 않아야 한다")
    void currencyMismatchDoesNotProceedToProvider() {
        BudgetEvaluator budgetEvaluator = mock(BudgetEvaluator.class);
        CallAdvisorChain providerChain = mock(CallAdvisorChain.class);
        when(budgetEvaluator.evaluate(anyMap()))
                .thenReturn(decision(BudgetState.CURRENCY_MISMATCH));
        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(
                mock(LedgerManager.class),
                mock(UsageExtractor.class),
                budgetEvaluator,
                null,
                null,
                null
        );
        ChatClientRequest request = new ChatClientRequest(
                new Prompt("test"),
                Map.of("tenant_id", "tenant-abc")
        );

        assertThatThrownBy(() -> advisor.adviseCall(request, providerChain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("currency mismatch");
        verifyNoInteractions(providerChain);
    }

    @Test
    @DisplayName("Advisor 이름과 순서가 기본값으로 설정되어야 한다")
    void checkAdvisorMetadata() {
        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(mock(LedgerManager.class), mock(UsageExtractor.class));

        assertThat(advisor.getName()).isEqualTo("LedgerAdvisor");
        assertThat(advisor.getOrder()).isEqualTo(0);
    }

    private static final Currency USD = Currency.getInstance("USD");

    private static BudgetDecision decision() {
        return decision(BudgetState.ALLOW);
    }

    private static BudgetDecision decision(BudgetState state) {
        Cost usage = switch (state) {
            case ALLOW -> usd("0");
            case WARN -> usd("80.00");
            case BLOCK, CURRENCY_MISMATCH -> usd("100.00");
        };
        BudgetThreshold threshold = switch (state) {
            case ALLOW, CURRENCY_MISMATCH -> BudgetThreshold.NONE;
            case WARN -> BudgetThreshold.WARNING;
            case BLOCK -> BudgetThreshold.EXCEEDED;
        };
        String reason = switch (state) {
            case ALLOW -> "allowed";
            case WARN -> "warning";
            case BLOCK -> "blocked";
            case CURRENCY_MISMATCH -> "currency mismatch";
        };
        return new BudgetDecision(
                new BudgetKey(
                        "policy-a",
                        "tenant",
                        "tenant-abc",
                        BudgetWindow.parse("2026-07")
                ),
                EvaluationType.STATUS,
                state,
                threshold,
                reason,
                usage,
                usage,
                usd("100.00")
        );
    }

    private static ChatClientResponse response(String modelId, Map<String, Object> context) {
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().model(modelId).build();
        ChatResponse chatResponse = new ChatResponse(
                List.of(new Generation(new org.springframework.ai.chat.messages.AssistantMessage("test"))),
                metadata
        );
        return new ChatClientResponse(chatResponse, context);
    }

    private static Cost usd(String amount) {
        return Cost.of(new BigDecimal(amount), USD);
    }
}
