package io.tokenpilot.sample;

import io.micrometer.core.instrument.MeterRegistry;
import io.tokenpilot.budget.ActualUsageCommand;
import io.tokenpilot.budget.AtomicBudgetStateStore;
import io.tokenpilot.budget.BudgetDecision;
import io.tokenpilot.budget.BudgetEvaluator;
import io.tokenpilot.budget.BudgetReservationRequest;
import io.tokenpilot.budget.BudgetReservationResult;
import io.tokenpilot.budget.BudgetSnapshot;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.budget.ReservationAccounting;
import io.tokenpilot.budget.ReservationAccountingEvent;
import io.tokenpilot.budget.ReservationAccountingListener;
import io.tokenpilot.budget.ReservationAccountingReason;
import io.tokenpilot.budget.ReservationId;
import io.tokenpilot.budget.ReservationReconciliationRequiredEvent;
import io.tokenpilot.budget.ReservationStatus;
import io.tokenpilot.budget.exception.BudgetExceededException;
import io.tokenpilot.budget.internal.LedgerBudgetComponents;
import io.tokenpilot.core.CostCalculator;
import io.tokenpilot.core.domain.TokenUsage;
import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.PricingPlan;
import io.tokenpilot.core.exception.MissingPricingException;
import io.tokenpilot.notification.BudgetNotificationEvent;
import io.tokenpilot.notification.BudgetNotificationHandler;
import io.tokenpilot.notification.BudgetNotificationSource;
import io.tokenpilot.springai.UsageExtractor;
import io.tokenpilot.springai.internal.LedgerSpringAiComponents;
import io.tokenpilot.core.internal.LedgerComponents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.util.MimeTypeUtils;

import java.time.Clock;
import java.util.Currency;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@SpringBootTest(properties = {
        "token-pilot.enabled=true",
        "token-pilot.pricing.plans[0].model-id=gpt-4o-2024-08-06",
        "token-pilot.pricing.plans[0].currency=USD",
        "token-pilot.pricing.plans[0].rates.PROMPT=0.00015",
        "token-pilot.pricing.plans[0].rates.COMPLETION=0.00060",
        "token-pilot.budget.enabled=true",
        "token-pilot.budget.monthly-limit=10.00",
        "token-pilot.budget.currency=USD",
        "token-pilot.budget.target-tag-key=tenant_id",
        "token-pilot.notification.enabled=true"
})
class SampleApplicationChatClientE2ETest {

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private ProviderProbe providerProbe;

    @Autowired
    private BudgetStateStore stateStoreProbe;

    @Autowired
    private BudgetEvaluator budgetEvaluator;

    @Autowired
    private UsageExtractorProbe usageExtractorProbe;

    @Autowired
    private AccountingListenerProbe accountingListenerProbe;

    @Autowired
    private AccountingCostProbe accountingCostProbe;

    @Autowired
    private ReservationLifecycleProbe reservationLifecycleProbe;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private NotificationProbe notificationProbe;

    @BeforeEach
    void resetProbes() {
        providerProbe.reset();
        usageExtractorProbe.reset();
        accountingListenerProbe.reset();
        accountingCostProbe.reset();
        reservationLifecycleProbe.reset();
        notificationProbe.reset();
        reset(stateStoreProbe);
    }

    @Test
    @DisplayName("정상 호출은 예약 후 IN_FLIGHT를 거쳐 actual 비용을 한 번 commit한다")
    void providerAndAccountingLifecycleRunOnce() {
        ChatClientResponse response = chatClientBuilder.clone()
                .build()
                .prompt()
                .user("Record this fake budget-aware Spring AI call.")
                .options(ChatOptions.builder()
                        .model("gpt-4o-2024-08-06")
                        .maxTokens(100))
                .advisors(advisors -> advisors
                        .param("tenant_id", "budget-chat-tenant")
                        .param("tokenpilot.request.id", "request-1")
                        .param("tokenpilot.attempt.id", "attempt-1"))
                .call()
                .chatClientResponse();

        BudgetDecision decision = budgetEvaluator.evaluate(
                Map.of("tenant_id", "budget-chat-tenant")
        );
        BudgetSnapshot snapshot = stateStoreProbe.snapshot(
                decision.key(),
                decision.limit()
        );
        ReservationAccounting accountingProbe = (ReservationAccounting) stateStoreProbe;

        assertThat(response.chatResponse().getResult().getOutput().getText())
                .isEqualTo("fake chat response");
        assertThat(providerProbe.invocationCount()).isEqualTo(1);
        verify(stateStoreProbe).checkAndReserve(any(BudgetReservationRequest.class));
        verify(accountingProbe).markInFlight(any(ReservationId.class));
        verify(accountingProbe).commit(any(ActualUsageCommand.class));
        verify(accountingProbe, never()).release(
                any(ReservationId.class),
                any(ReservationAccountingReason.class)
        );
        verify(accountingProbe, never()).markReconciliationRequired(
                any(ReservationId.class),
                any(ReservationAccountingReason.class)
        );
        assertThat(snapshot.committedCost().value()).isEqualByComparingTo("0.00135");
        assertThat(snapshot.activeReservedCost().value()).isZero();
        assertThat(snapshot.pendingReconciliationLiability().value()).isZero();
    }

    @Test
    @DisplayName("request와 기본 model이 없으면 provider 호출과 예약 전에 차단한다")
    void missingRequestAndDefaultModelStopsBeforeProviderAndReservation() {
        assertThatThrownBy(() -> chatClientBuilder.clone()
                .build()
                .prompt()
                .user("This request has no model.")
                .options(ChatOptions.builder().maxTokens(100))
                .advisors(advisors -> advisors
                        .param("tenant_id", "missing-model-tenant")
                        .param("tokenpilot.request.id", "request-missing-model")
                        .param("tokenpilot.attempt.id", "attempt-missing-model"))
                .call()
                .chatClientResponse())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("MODEL_UNRESOLVED");

        assertThat(providerProbe.invocationCount()).isZero();
        verifyNoInteractions(stateStoreProbe);
    }

    @Test
    @DisplayName("request와 기본 reserved output이 없으면 provider 호출과 예약 전에 차단한다")
    void missingRequestAndDefaultOutputStopsBeforeProviderAndReservation() {
        assertThatThrownBy(() -> chatClientBuilder.clone()
                .build()
                .prompt()
                .user("This request has no reserved output.")
                .options(ChatOptions.builder().model("gpt-4o-2024-08-06"))
                .advisors(advisors -> advisors
                        .param("tenant_id", "missing-output-tenant")
                        .param("tokenpilot.request.id", "request-missing-output")
                        .param("tokenpilot.attempt.id", "attempt-missing-output"))
                .call()
                .chatClientResponse())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OUTPUT_RESERVATION_UNRESOLVED");

        assertThat(providerProbe.invocationCount()).isZero();
        verifyNoInteractions(stateStoreProbe);
    }

    @Test
    @DisplayName("context 한도를 넘는 요청은 provider 호출과 예약 전에 차단한다")
    void contextAdmissionStopsBeforeProviderAndReservation() {
        assertThatThrownBy(() -> chatClientBuilder.clone()
                .build()
                .prompt()
                .user("x".repeat(150_000))
                .options(ChatOptions.builder()
                        .model("gpt-4o-2024-08-06")
                        .maxTokens(100))
                .advisors(advisors -> advisors
                        .param("tenant_id", "context-blocked-tenant")
                        .param("tokenpilot.request.id", "request-context-blocked")
                        .param("tokenpilot.attempt.id", "attempt-context-blocked"))
                .call()
                .chatClientResponse())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONTEXT_EXCEEDED");

        assertThat(providerProbe.invocationCount()).isZero();
        verifyNoInteractions(stateStoreProbe);
    }

    @Test
    @DisplayName("예산 BLOCK 요청은 원자적 BLOCKED 전이를 발행하고 provider나 예약을 만들지 않는다")
    void budgetBlockPublishesAtomicTransitionWithoutProviderOrReservation() {
        Map<String, String> tags = Map.of("tenant_id", "budget-blocked-tenant");
        BudgetDecision initial = budgetEvaluator.evaluate(tags);
        stateStoreProbe.addCost(initial.key(), initial.limit(), initial.limit());
        BudgetSnapshot before = stateStoreProbe.snapshot(initial.key(), initial.limit());
        var blockedCounterBefore = meterRegistry
                .find("tokenpilot.budget.reservations")
                .tag("state", "blocked")
                .counter();
        double blockedMeterBefore = blockedCounterBefore == null
                ? 0.0
                : blockedCounterBefore.count();
        clearInvocations(stateStoreProbe);

        assertThatThrownBy(() -> chatClientBuilder.clone()
                .build()
                .prompt()
                .user("This request is over budget.")
                .options(ChatOptions.builder()
                        .model("gpt-4o-2024-08-06")
                        .maxTokens(100))
                .advisors(advisors -> advisors
                        .param("tenant_id", "budget-blocked-tenant")
                        .param("tokenpilot.request.id", "request-budget-blocked")
                        .param("tokenpilot.attempt.id", "attempt-budget-blocked"))
                .call()
                .chatClientResponse())
                .isInstanceOf(BudgetExceededException.class);

        BudgetSnapshot after = stateStoreProbe.snapshot(initial.key(), initial.limit());
        assertThat(providerProbe.invocationCount()).isZero();
        verify(stateStoreProbe).checkAndReserve(argThat(reservation ->
                reservation.key().equals(initial.key())
                        && reservation.limit().equals(initial.limit())
        ));
        assertThat(reservationLifecycleProbe.evaluatedStatuses())
                .containsExactly(ReservationStatus.BLOCKED);
        assertThat(reservationLifecycleProbe.blockedStatuses())
                .containsExactly(ReservationStatus.BLOCKED);
        assertThat(meterRegistry.get("tokenpilot.budget.reservations")
                .tag("state", "blocked")
                .counter()
                .count()).isEqualTo(blockedMeterBefore + 1.0);
        assertThat(notificationProbe.events())
                .extracting(BudgetNotificationEvent::source)
                .containsExactly(BudgetNotificationSource.RESERVATION_BLOCK);
        assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("pricing이 없는 요청은 provider 호출과 예약 전에 차단한다")
    void missingPricingStopsBeforeProviderAndReservation() {
        assertThatThrownBy(() -> chatClientBuilder.clone()
                .build()
                .prompt()
                .user("This model has no configured pricing.")
                .options(ChatOptions.builder()
                        .model("gpt-4o-mini-2024-07-18")
                        .maxTokens(100))
                .advisors(advisors -> advisors
                        .param("tenant_id", "missing-pricing-tenant")
                        .param("tokenpilot.request.id", "request-missing-pricing")
                        .param("tokenpilot.attempt.id", "attempt-missing-pricing"))
                .call()
                .chatClientResponse())
                .isInstanceOf(MissingPricingException.class)
                .hasMessageContaining("MISSING_PLAN");

        assertThat(providerProbe.invocationCount()).isZero();
        verifyNoInteractions(stateStoreProbe);
    }

    @Test
    @DisplayName("예약 후 dispatch 이전 오류는 예약을 한 번 release한다")
    void errorBeforeDispatchReleasesReservationOnce() {
        ReservationAccounting accountingProbe = (ReservationAccounting) stateStoreProbe;
        doThrow(new IllegalStateException("dispatch preparation failed"))
                .when(accountingProbe)
                .markInFlight(any(ReservationId.class));

        assertThatThrownBy(() -> call("pre-dispatch-tenant", "pre-dispatch"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("dispatch preparation failed");

        BudgetSnapshot snapshot = snapshot("pre-dispatch-tenant");
        assertThat(providerProbe.invocationCount()).isZero();
        verify(accountingProbe).releaseBeforeDispatch(any(ReservationId.class));
        assertThat(snapshot.committedCost().value()).isZero();
        assertThat(snapshot.activeReservedCost().value()).isZero();
        assertThat(snapshot.pendingReconciliationLiability().value()).isZero();
    }

    @Test
    @DisplayName("IN_FLIGHT 이후 downstream 오류는 estimate를 pending liability로 보존한다")
    void downstreamErrorPreservesPendingLiability() {
        providerProbe.failWith(new IllegalStateException("provider failed"));

        assertThatThrownBy(() -> call("downstream-error-tenant", "downstream-error"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider failed");

        ReservationAccounting accountingProbe = (ReservationAccounting) stateStoreProbe;
        BudgetSnapshot snapshot = snapshot("downstream-error-tenant");
        assertThat(providerProbe.invocationCount()).isEqualTo(1);
        verify(accountingProbe).markReconciliationRequired(
                any(ReservationId.class),
                any(ReservationAccountingReason.class)
        );
        verify(accountingProbe, never()).commit(any(ActualUsageCommand.class));
        verify(accountingProbe, never()).release(
                any(ReservationId.class),
                any(ReservationAccountingReason.class)
        );
        assertThat(snapshot.committedCost().value()).isZero();
        assertThat(snapshot.activeReservedCost().value()).isZero();
        assertThat(snapshot.pendingReconciliationLiability().value()).isPositive();
    }

    @Test
    @DisplayName("성공 응답의 usage가 없으면 응답을 보존하고 pending liability로 남긴다")
    void unavailableUsagePreservesResponseAndPendingLiability() {
        providerProbe.withoutUsage();

        ChatClientResponse response = call("usage-unavailable-tenant", "usage-unavailable");

        ReservationAccounting accountingProbe = (ReservationAccounting) stateStoreProbe;
        BudgetSnapshot snapshot = snapshot("usage-unavailable-tenant");
        assertThat(response.chatResponse().getResult().getOutput().getText())
                .isEqualTo("fake chat response");
        assertThat(providerProbe.invocationCount()).isEqualTo(1);
        verify(accountingProbe).markReconciliationRequired(
                any(ReservationId.class),
                any(ReservationAccountingReason.class)
        );
        verify(accountingProbe, never()).commit(any(ActualUsageCommand.class));
        assertThat(snapshot.committedCost().value()).isZero();
        assertThat(snapshot.activeReservedCost().value()).isZero();
        assertThat(snapshot.pendingReconciliationLiability().value()).isPositive();
    }

    @Test
    @DisplayName("usage 추출 오류는 provider 응답을 뒤집지 않고 pending liability로 남긴다")
    void extractionErrorPreservesResponseAndPendingLiability() {
        usageExtractorProbe.failWith(new IllegalStateException("extraction failed"));

        ChatClientResponse response = call("extraction-error-tenant", "extraction-error");

        ReservationAccounting accountingProbe = (ReservationAccounting) stateStoreProbe;
        BudgetSnapshot snapshot = snapshot("extraction-error-tenant");
        assertThat(response.chatResponse().getResult().getOutput().getText())
                .isEqualTo("fake chat response");
        verify(accountingProbe).markReconciliationRequired(
                any(ReservationId.class),
                any(ReservationAccountingReason.class)
        );
        verify(accountingProbe, never()).commit(any(ActualUsageCommand.class));
        assertThat(snapshot.pendingReconciliationLiability().value()).isPositive();
    }

    @Test
    @DisplayName("actual 비용 통화가 reservation과 다르면 응답을 보존하고 pending liability로 남긴다")
    void actualCurrencyMismatchPreservesResponseAndPendingLiability() {
        ReservationAccounting accountingProbe = (ReservationAccounting) stateStoreProbe;
        accountingCostProbe.returnCurrency(Currency.getInstance("EUR"));

        ChatClientResponse response = call("commit-error-tenant", "commit-error");

        BudgetSnapshot snapshot = snapshot("commit-error-tenant");
        assertThat(response.chatResponse().getResult().getOutput().getText())
                .isEqualTo("fake chat response");
        verify(accountingProbe).markReconciliationRequired(
                any(ReservationId.class),
                any(ReservationAccountingReason.class)
        );
        assertThat(snapshot.committedCost().value()).isZero();
        assertThat(snapshot.activeReservedCost().value()).isZero();
        assertThat(snapshot.pendingReconciliationLiability().value()).isPositive();
    }

    @Test
    @DisplayName("response model이 다르면 request pricing으로 commit하지 않고 pending으로 남긴다")
    void responseModelMismatchRequiresPricingReconciliation() {
        providerProbe.respondAs("provider-routed-model-v2");

        ChatClientResponse response = call(
                "model-correlation-tenant",
                "model-correlation"
        );

        ReservationAccounting accountingProbe = (ReservationAccounting) stateStoreProbe;
        verify(stateStoreProbe).checkAndReserve(argThat(
                reservation -> reservation.modelId().equals("gpt-4o-2024-08-06")
        ));
        verify(accountingProbe).markReconciliationRequired(
                argThat((ActualUsageCommand command) -> command.requestId()
                        .equals("request-model-correlation")),
                org.mockito.ArgumentMatchers.eq(
                        ReservationAccountingReason.PRICING_RECONCILIATION_REQUIRED
                )
        );
        verify(accountingProbe, never()).commit(any(ActualUsageCommand.class));
        assertThat(response.chatResponse().getResult().getOutput().getText())
                .isEqualTo("fake chat response");
        assertThat(snapshot("model-correlation-tenant")
                .pendingReconciliationLiability().value()).isPositive();
    }

    @Test
    @DisplayName("cache read·create와 reasoning usage를 정규화해 actual commit에 전달한다")
    void normalizedCacheAndReasoningUsageReachesAccountingCommit() {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(50);
        when(usage.getCompletionTokens()).thenReturn(60);
        when(usage.getNativeUsage()).thenReturn(Map.of(
                "input_tokens", 50,
                "cache_read_input_tokens", 100,
                "cache_creation_input_tokens", 25,
                "candidatesTokenCount", 60,
                "thoughtsTokenCount", 20
        ));
        providerProbe.withUsage(usage);

        call("normalized-usage-tenant", "normalized-usage");

        ReservationAccounting accountingProbe = (ReservationAccounting) stateStoreProbe;
        verify(accountingProbe).commit(argThat(command ->
                command.usage().inputTokens() == 175
                        && command.usage().outputTokens() == 80
                        && command.usage().details().cacheReadInputTokens() == 100
                        && command.usage().details().cacheCreationInputTokens() == 25
                        && command.usage().details().reasoningOutputTokens() == 20
        ));
    }

    @Test
    @DisplayName("tool schema 요청은 provider 호출과 예약 전에 지원하지 않는 scope로 차단한다")
    void unsupportedToolSchemaStopsBeforeProviderAndReservation() {
        ToolCallback toolCallback = mock(ToolCallback.class);

        assertThatThrownBy(() -> chatClientBuilder.clone()
                .build()
                .prompt()
                .user("Do not dispatch this tool request.")
                .options(ToolCallingChatOptions.builder()
                        .model("gpt-4o-2024-08-06")
                        .maxTokens(100)
                        .toolCallbacks(toolCallback))
                .advisors(advisors -> advisors
                        .param("tenant_id", "unsupported-scope-tenant")
                        .param("tokenpilot.request.id", "request-unsupported-scope")
                        .param("tokenpilot.attempt.id", "attempt-unsupported-scope"))
                .call()
                .chatClientResponse())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("UNSUPPORTED_REQUEST_SCOPE: TOOL_SCHEMA");

        assertThat(providerProbe.invocationCount()).isZero();
        verifyNoInteractions(stateStoreProbe);
    }

    @Test
    @DisplayName("media 요청은 provider 호출과 예약 전에 지원하지 않는 scope로 차단한다")
    void unsupportedMediaStopsBeforeProviderAndReservation() {
        Media media = Media.builder()
                .mimeType(MimeTypeUtils.IMAGE_PNG)
                .data(new byte[]{1})
                .build();

        assertThatThrownBy(() -> chatClientBuilder.clone()
                .build()
                .prompt()
                .user(user -> user
                        .text("Do not dispatch this media request.")
                        .media(media))
                .options(ChatOptions.builder()
                        .model("gpt-4o-2024-08-06")
                        .maxTokens(100))
                .advisors(advisors -> advisors
                        .param("tenant_id", "unsupported-media-tenant")
                        .param("tokenpilot.request.id", "request-unsupported-media")
                        .param("tokenpilot.attempt.id", "attempt-unsupported-media"))
                .call()
                .chatClientResponse())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("UNSUPPORTED_REQUEST_SCOPE: MEDIA");

        assertThat(providerProbe.invocationCount()).isZero();
        verifyNoInteractions(stateStoreProbe);
    }

    @Test
    @DisplayName("structured output 요청은 provider 호출과 예약 전에 지원하지 않는 scope로 차단한다")
    void unsupportedStructuredOutputStopsBeforeProviderAndReservation() {
        assertThatThrownBy(() -> chatClientBuilder.clone()
                .build()
                .prompt()
                .user("Do not dispatch this structured output request.")
                .options(ChatOptions.builder()
                        .model("gpt-4o-2024-08-06")
                        .maxTokens(100))
                .advisors(advisors -> advisors
                        .param("tenant_id", "unsupported-structured-output-tenant")
                        .param("tokenpilot.request.id", "request-unsupported-structured-output")
                        .param("tokenpilot.attempt.id", "attempt-unsupported-structured-output"))
                .call()
                .entity(StructuredResponse.class, parameters ->
                        parameters.useProviderStructuredOutput()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("UNSUPPORTED_REQUEST_SCOPE: STRUCTURED_OUTPUT");

        assertThat(providerProbe.invocationCount()).isZero();
        verifyNoInteractions(stateStoreProbe);
    }

    @Test
    @DisplayName("뒤쪽 user Advisor가 추가한 framing도 preflight에 반영해 provider 호출 전에 차단한다")
    void downstreamUserAdvisorFramingIsIncludedInPreflight() {
        assertThatThrownBy(() -> chatClientBuilder.clone()
                .build()
                .prompt()
                .user("This text alone fits the context window.")
                .options(ChatOptions.builder()
                        .model("gpt-4o-2024-08-06")
                        .maxTokens(100))
                .advisors(advisors -> advisors
                        .advisors(new ContextExpandingAdvisor())
                        .param("tenant_id", "advisor-order-tenant")
                        .param("tokenpilot.request.id", "request-advisor-order")
                        .param("tokenpilot.attempt.id", "attempt-advisor-order"))
                .call()
                .chatClientResponse())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONTEXT_EXCEEDED");

        assertThat(providerProbe.invocationCount()).isZero();
        verifyNoInteractions(stateStoreProbe);
    }

    @Test
    @DisplayName("budget enforcement가 활성화된 streaming은 provider 호출과 예약 전에 차단한다")
    void streamingEnforcementStopsBeforeProviderAndReservation() {
        assertThatThrownBy(() -> chatClientBuilder.clone()
                .build()
                .prompt()
                .user("Do not dispatch this streaming request.")
                .options(ChatOptions.builder()
                        .model("gpt-4o-2024-08-06")
                        .maxTokens(100))
                .advisors(advisors -> advisors
                        .param("tenant_id", "streaming-tenant")
                        .param("tokenpilot.request.id", "request-streaming")
                        .param("tokenpilot.attempt.id", "attempt-streaming"))
                .stream()
                .chatClientResponse()
                .blockLast())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("STREAMING_UNSUPPORTED_FOR_ENFORCEMENT");

        assertThat(providerProbe.invocationCount()).isZero();
        verifyNoInteractions(stateStoreProbe);
    }

    @Test
    @DisplayName("listener 실패와 중복 commit 명령은 완료된 정산과 event를 반복하지 않는다")
    void listenerFailureAndDuplicateCommandDoNotReverseOrRepeatCommit() {
        ChatClientResponse response = call("listener-failure-tenant", "listener-failure");
        ReservationAccounting accountingProbe = (ReservationAccounting) stateStoreProbe;
        var commandCaptor = forClass(ActualUsageCommand.class);
        verify(accountingProbe).commit(commandCaptor.capture());
        BudgetSnapshot committed = snapshot("listener-failure-tenant");

        accountingProbe.commit(commandCaptor.getValue());
        BudgetSnapshot reused = snapshot("listener-failure-tenant");

        assertThat(response.chatResponse().getResult().getOutput().getText())
                .isEqualTo("fake chat response");
        assertThat(accountingListenerProbe.deliveryCount()).isEqualTo(1);
        assertThat(reused).isEqualTo(committed);
    }

    private ChatClientResponse call(String tenantId, String correlationId) {
        return chatClientBuilder.clone()
                .build()
                .prompt()
                .user("Run a fake provider lifecycle scenario.")
                .options(ChatOptions.builder()
                        .model("gpt-4o-2024-08-06")
                        .maxTokens(100))
                .advisors(advisors -> advisors
                        .param("tenant_id", tenantId)
                        .param("tokenpilot.request.id", "request-" + correlationId)
                        .param("tokenpilot.attempt.id", "attempt-" + correlationId))
                .call()
                .chatClientResponse();
    }

    private BudgetSnapshot snapshot(String tenantId) {
        BudgetDecision decision = budgetEvaluator.evaluate(Map.of("tenant_id", tenantId));
        return stateStoreProbe.snapshot(decision.key(), decision.limit());
    }

    private record StructuredResponse(String value) {
    }

    private static final class ContextExpandingAdvisor implements CallAdvisor {

        @Override
        public ChatClientResponse adviseCall(
                ChatClientRequest request,
                CallAdvisorChain chain
        ) {
            List<Message> messages = new ArrayList<>(
                    request.prompt().getInstructions()
            );
            messages.add(new UserMessage("x".repeat(150_000)));
            ChatClientRequest expandedRequest = request.mutate()
                    .prompt(new Prompt(messages, request.prompt().getOptions()))
                    .build();
            return chain.nextCall(expandedRequest);
        }

        @Override
        public String getName() {
            return "Context Expanding User Advisor";
        }

        @Override
        public int getOrder() {
            return 1;
        }
    }

    static final class UsageExtractorProbe implements UsageExtractor {
        private final UsageExtractor delegate = LedgerSpringAiComponents.defaultUsageExtractor();
        private RuntimeException failure;

        @Override
        public TokenUsage extract(ChatClientResponse response) {
            if (failure != null) {
                throw failure;
            }
            return delegate.extract(response);
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        void reset() {
            failure = null;
        }
    }

    static final class AccountingListenerProbe {
        private final AtomicInteger deliveryCount = new AtomicInteger();

        void onCommitted(ReservationAccountingEvent event) {
            deliveryCount.incrementAndGet();
            throw new IllegalStateException("listener failed");
        }

        int deliveryCount() {
            return deliveryCount.get();
        }

        void reset() {
            deliveryCount.set(0);
        }
    }

    static final class AccountingCostProbe {
        private final CostCalculator delegate = LedgerComponents.defaultCostCalculator();
        private Currency returnedCurrency;

        Cost calculate(TokenUsage usage, PricingPlan plan) {
            Cost calculated = delegate.calculate(usage, plan);
            if (returnedCurrency == null) {
                return calculated;
            }
            return Cost.of(calculated.value(), returnedCurrency);
        }

        void returnCurrency(Currency currency) {
            returnedCurrency = currency;
        }

        void reset() {
            returnedCurrency = null;
        }
    }

    static final class ReservationLifecycleProbe
            implements ReservationAccountingListener {
        private final List<ReservationStatus> evaluatedStatuses = new ArrayList<>();
        private final List<ReservationStatus> blockedStatuses = new ArrayList<>();

        @Override
        public void onCommitted(ReservationAccountingEvent event) {
        }

        @Override
        public void onReservationEvaluated(
                BudgetReservationRequest request,
                BudgetReservationResult result
        ) {
            evaluatedStatuses.add(result.status());
        }

        @Override
        public void onReservationBlocked(
                BudgetReservationRequest request,
                BudgetReservationResult result
        ) {
            blockedStatuses.add(result.status());
        }

        List<ReservationStatus> evaluatedStatuses() {
            return List.copyOf(evaluatedStatuses);
        }

        List<ReservationStatus> blockedStatuses() {
            return List.copyOf(blockedStatuses);
        }

        void reset() {
            evaluatedStatuses.clear();
            blockedStatuses.clear();
        }
    }

    static final class LateBoundAccountingListeners
            implements ReservationAccountingListener {
        private final ObjectProvider<ReservationAccountingListener> listeners;

        LateBoundAccountingListeners(
                ObjectProvider<ReservationAccountingListener> listeners
        ) {
            this.listeners = listeners;
        }

        @Override
        public void onCommitted(ReservationAccountingEvent event) {
            listeners.orderedStream().forEach(listener -> listener.onCommitted(event));
        }

        @Override
        public void onAccountingApplied(
                ReservationAccountingEvent event,
                BudgetSnapshot snapshot
        ) {
            listeners.orderedStream()
                    .forEach(listener -> listener.onAccountingApplied(event, snapshot));
        }

        @Override
        public void onReconciliationRequired(
                ReservationReconciliationRequiredEvent event
        ) {
            listeners.orderedStream()
                    .forEach(listener -> listener.onReconciliationRequired(event));
        }

        @Override
        public void onReservationEvaluated(
                BudgetReservationRequest request,
                BudgetReservationResult result
        ) {
            listeners.orderedStream()
                    .forEach(listener -> listener.onReservationEvaluated(request, result));
        }

        @Override
        public void onReservationBlocked(
                BudgetReservationRequest request,
                BudgetReservationResult result
        ) {
            listeners.orderedStream()
                    .forEach(listener -> listener.onReservationBlocked(request, result));
        }
    }

    static final class NotificationProbe implements BudgetNotificationHandler {
        private final List<BudgetNotificationEvent> events = new ArrayList<>();

        @Override
        public void handle(BudgetNotificationEvent event) {
            events.add(event);
        }

        List<BudgetNotificationEvent> events() {
            return List.copyOf(events);
        }

        void reset() {
            events.clear();
        }
    }

    static final class ProviderProbe implements ChatModel {
        private final AtomicInteger invocationCount = new AtomicInteger();
        private RuntimeException failure;
        private boolean usageAvailable = true;
        private String responseModelId = "gpt-4o-2024-08-06";
        private Usage usage = new DefaultUsage(1_000, 2_000);

        @Override
        public ChatResponse call(Prompt prompt) {
            invocationCount.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder()
                    .model(responseModelId);
            if (usageAvailable) {
                metadata.usage(usage);
            }
            return new ChatResponse(
                    List.of(new Generation(new AssistantMessage("fake chat response"))),
                    metadata.build()
            );
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        int invocationCount() {
            return invocationCount.get();
        }

        void failWith(RuntimeException failure) {
            this.failure = failure;
        }

        void withoutUsage() {
            usageAvailable = false;
        }

        void respondAs(String responseModelId) {
            this.responseModelId = responseModelId;
        }

        void withUsage(Usage usage) {
            this.usage = usage;
        }

        void reset() {
            invocationCount.set(0);
            failure = null;
            usageAvailable = true;
            responseModelId = "gpt-4o-2024-08-06";
            usage = new DefaultUsage(1_000, 2_000);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeChatClientConfiguration {

        @Bean
        AtomicBudgetStateStore accountingProbe(
                AccountingListenerProbe listenerProbe,
                AccountingCostProbe costProbe,
                ObjectProvider<ReservationAccountingListener> accountingListeners
        ) {
            AtomicBudgetStateStore delegate = (AtomicBudgetStateStore)
                    LedgerBudgetComponents.inMemoryBudgetStateStore(
                    Clock.systemUTC(),
                    () -> new ReservationId(UUID.randomUUID().toString()),
                    costProbe::calculate,
                    List.of(
                            listenerProbe::onCommitted,
                            new LateBoundAccountingListeners(accountingListeners)
                    )
            );
            return mock(
                    AtomicBudgetStateStore.class,
                    withSettings()
                            .defaultAnswer(delegatesTo(delegate))
            );
        }

        @Bean
        UsageExtractorProbe usageExtractor() {
            return new UsageExtractorProbe();
        }

        @Bean
        AccountingListenerProbe accountingListenerProbe() {
            return new AccountingListenerProbe();
        }

        @Bean
        AccountingCostProbe accountingCostProbe() {
            return new AccountingCostProbe();
        }

        @Bean
        ReservationLifecycleProbe reservationLifecycleProbe() {
            return new ReservationLifecycleProbe();
        }

        @Bean
        NotificationProbe notificationProbe() {
            return new NotificationProbe();
        }

        @Bean
        ProviderProbe fakeChatModel() {
            return new ProviderProbe();
        }

        @Bean
        ChatClient.Builder chatClientBuilder(
                ChatModel chatModel,
                ObjectProvider<ChatClientBuilderCustomizer> customizers
        ) {
            ChatClient.Builder builder = ChatClient.builder(chatModel);
            customizers.orderedStream()
                    .forEach(customizer -> customizer.customize(builder));
            return builder;
        }
    }
}
