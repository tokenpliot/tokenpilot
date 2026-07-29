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
import org.springframework.ai.chat.prompt.Prompt;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        Cost mockCost = new Cost(new BigDecimal("0.5"), Currency.getInstance("USD"));
        BudgetDecision budgetDecision = decision();

        when(extractor.extract(any())).thenReturn(mockUsage);
        when(pricingRegistry.getPlan("gpt-4o")).thenReturn(Optional.of(mockPlan));
        when(costCalculator.calculate(mockUsage, mockPlan)).thenReturn(mockCost);
        when(budgetEvaluator.evaluate(anyMap())).thenReturn(budgetDecision);

        DefaultLedgerAdvisor advisor = new DefaultLedgerAdvisor(ledgerManager, extractor, 
                budgetEvaluator, budgetStateStore, costCalculator, pricingRegistry);

        ChatClientRequest request = new ChatClientRequest(
                new Prompt("test"),
                Map.of("tenant_id", "tenant-abc")
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

    private static Cost usd(String amount) {
        return Cost.of(new BigDecimal(amount), USD);
    }
}
