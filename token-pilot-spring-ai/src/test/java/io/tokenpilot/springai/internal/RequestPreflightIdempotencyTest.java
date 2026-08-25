package io.tokenpilot.springai.internal;

import io.tokenpilot.budget.AtomicBudgetStateStore;
import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetReservationRequest;
import io.tokenpilot.budget.BudgetWindow;
import io.tokenpilot.budget.IdempotencyKey;
import io.tokenpilot.budget.ReservationStatus;
import io.tokenpilot.budget.ReservationTokenEstimate;
import io.tokenpilot.budget.internal.LedgerBudgetComponents;
import io.tokenpilot.core.ModelRegistry;
import io.tokenpilot.core.PricingRegistry;
import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.ModelDefinition;
import io.tokenpilot.core.domain.PreflightCostResult;
import io.tokenpilot.core.domain.PricingSnapshot;
import io.tokenpilot.core.domain.TokenType;
import io.tokenpilot.core.internal.LedgerComponents;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestPreflightIdempotencyTest {

    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void 실제_preflight의_서로_다른_pricing_조회_시각은_동일_예약을_REUSED한다() {
        ModelRegistry modelRegistry = LedgerComponents.defaultModelRegistry();
        PricingRegistry pricingRegistry = mock(PricingRegistry.class);
        PricingSnapshot firstSnapshot = snapshot(
                Instant.parse("2026-08-25T00:00:00Z")
        );
        PricingSnapshot secondSnapshot = snapshot(
                Instant.parse("2026-08-25T00:00:01Z")
        );
        AtomicInteger resolution = new AtomicInteger();
        when(pricingRegistry.resolveSnapshot(any(ModelDefinition.class)))
                .thenAnswer(ignored -> Optional.of(
                        resolution.getAndIncrement() == 0
                                ? firstSnapshot
                                : secondSnapshot
                ));
        RequestPreflight preflight = new RequestPreflight(
                new ModelResolver(modelRegistry),
                new ReservedOutputResolver(),
                LedgerComponents.utf8ByteHeuristicTokenEstimator(),
                LedgerComponents.tokenBudget(modelRegistry),
                pricingRegistry,
                LedgerComponents.defaultPricingEvaluator(),
                LedgerComponents.defaultPreflightCostEstimator(),
                0
        );
        ChatClientRequest request = new ChatClientRequest(
                new Prompt(
                        "same request",
                        ChatOptions.builder()
                                .model("gpt-4o-2024-08-06")
                                .maxTokens(100)
                                .build()
                ),
                Map.of()
        );

        PreflightCostResult.Bounded firstBound = preflight.resolve(request);
        PreflightCostResult.Bounded secondBound = preflight.resolve(request);
        AtomicBudgetStateStore store = LedgerBudgetComponents
                .inMemoryAtomicBudgetStateStore();
        var created = store.checkAndReserve(reservation(firstBound));
        var reused = store.checkAndReserve(reservation(secondBound));

        assertThat(firstBound.pricingSnapshot().checkedAt())
                .isNotEqualTo(secondBound.pricingSnapshot().checkedAt());
        assertThat(created.status()).isEqualTo(ReservationStatus.CREATED);
        assertThat(reused.status()).isEqualTo(ReservationStatus.REUSED);
        assertThat(reused.reservation().id()).isEqualTo(created.reservation().id());
        assertThat(reused.reservation().pricingSnapshot())
                .contains(firstSnapshot);
    }

    private BudgetReservationRequest reservation(
            PreflightCostResult.Bounded bound
    ) {
        return new BudgetReservationRequest(
                new BudgetKey(
                        "monthly",
                        "tenant",
                        "tenant-1",
                        BudgetWindow.parse("2026-08")
                ),
                Cost.of(new BigDecimal("10.00"), USD),
                bound.safeUpperBoundCost(),
                "request-1",
                new IdempotencyKey("idempotency-1"),
                bound.pricingSnapshot(),
                new ReservationTokenEstimate(
                        bound.inputEstimatedTokens(),
                        bound.inputSafeUpperBoundTokens(),
                        bound.reservedOutputTokens()
                )
        );
    }

    private PricingSnapshot snapshot(Instant checkedAt) {
        return new PricingSnapshot(
                "gpt-4o-2024-08-06",
                "default",
                "openai-2026-08-14",
                checkedAt,
                Map.of(
                        TokenType.PROMPT, new BigDecimal("0.00015"),
                        TokenType.COMPLETION, new BigDecimal("0.00060")
                ),
                USD
        );
    }
}
