package io.tokenpilot.core.internal;

import io.tokenpilot.core.*;
import io.tokenpilot.core.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DefaultLedgerManagerTest {

    private PricingRegistry registry;
    private CostCalculator calculator;
    private LedgerListener listener;
    private DefaultLedgerManager manager;

    @BeforeEach
    void setUp() {
        registry = new InMemoryPricingRegistry();
        calculator = new DefaultCostCalculator();
        listener = Mockito.mock(LedgerListener.class);
        manager = new DefaultLedgerManager(registry, calculator, List.of(listener));
    }

    @Test
    @DisplayName("모델 정책이 존재할 때 호출 기록 및 비용 계산이 정상적으로 수행되어야 한다")
    void shouldRecordAndCalculateCost() {
        registry.registerPlan(new PricingPlan("gpt-4o", new BigDecimal("5.0"), new BigDecimal("15.0")));
        TokenUsage usage = TokenUsage.from(1000, 1000);

        Cost cost = manager.record("gpt-4o", usage, Map.of());

        assertThat(cost.value()).isEqualByComparingTo("20.000000");
        verify(listener).onRecord(argThat(event -> 
            event.modelId().equals("gpt-4o") &&
            event.usage().equals(usage) &&
            event.cost().equals(cost)
        ));
    }

    @Test
    @DisplayName("모델 정책이 없을 경우 0원의 비용을 반환하고 리스너에게 알려야 한다")
    void shouldReturnZeroCostWhenPlanIsMissing() {
        TokenUsage usage = TokenUsage.from(100, 100);

        Cost result = manager.record("unknown-model", usage, Map.of());

        assertThat(result.value()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.currency()).isEqualTo(Currency.getInstance("USD"));
        verify(listener).onRecord(any(CostRecordedEvent.class));
    }

    @Test
    @DisplayName("이미 resolve된 plan으로 기록하면 registry를 다시 조회하지 않아야 한다")
    void shouldRecordWithResolvedPlanWithoutRegistryLookup() {
        PricingRegistry pricingRegistry = Mockito.mock(PricingRegistry.class);
        CostCalculator costCalculator = Mockito.mock(CostCalculator.class);
        LedgerListener listener = Mockito.mock(LedgerListener.class);
        DefaultLedgerManager manager = new DefaultLedgerManager(pricingRegistry, costCalculator, List.of(listener));
        PricingPlan plan = new PricingPlan("gpt-4o", new BigDecimal("5.0"), new BigDecimal("15.0"));
        TokenUsage usage = TokenUsage.from(1000, 1000);
        Cost expectedCost = new Cost(new BigDecimal("20.000000"), Currency.getInstance("USD"));

        when(costCalculator.calculate(usage, plan)).thenReturn(expectedCost);

        Cost cost = manager.record(plan, usage, Map.of());

        assertThat(cost).isEqualTo(expectedCost);
        verifyNoInteractions(pricingRegistry);
        verify(listener).onRecord(argThat(event ->
                event.modelId().equals("gpt-4o") &&
                event.usage().equals(usage) &&
                event.cost().equals(expectedCost)
        ));
    }

    @Test
    @DisplayName("pricing snapshot으로 기록하면 registry 변경 후에도 snapshot rate를 사용해야 한다")
    void shouldRecordWithSnapshotRatesAfterRegistryChanges() {
        PricingPlan originalPlan = new PricingPlan("gpt-4o", new BigDecimal("5.0"), new BigDecimal("15.0"));
        PricingSnapshot snapshot = PricingSnapshot.from(
                originalPlan,
                PricingSnapshot.DEFAULT_CATALOG_VERSION,
                Instant.parse("2026-07-30T00:00:00Z")
        );
        registry.registerPlan(new PricingPlan("gpt-4o", new BigDecimal("50.0"), new BigDecimal("150.0")));
        TokenUsage usage = TokenUsage.from(1000, 1000);

        Cost cost = manager.record(snapshot, usage, Map.of());

        assertThat(cost.value()).isEqualByComparingTo("20.000000");
        verify(listener).onRecord(argThat(event ->
                event.modelId().equals("gpt-4o") &&
                event.usage().equals(usage) &&
                event.cost().equals(cost)
        ));
    }

    @Test
    @DisplayName("명시적 0 rate snapshot은 정상 0원 cost로 기록되어야 한다")
    void shouldRecordZeroCostWithExplicitZeroRateSnapshot() {
        PricingPlan freePlan = new PricingPlan(
                "free-model",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                Currency.getInstance("USD")
        );
        PricingSnapshot snapshot = PricingSnapshot.from(
                freePlan,
                PricingSnapshot.DEFAULT_CATALOG_VERSION,
                Instant.parse("2026-07-30T00:00:00Z")
        );
        TokenUsage usage = TokenUsage.from(1000, 1000);

        Cost cost = manager.record(snapshot, usage, Map.of());

        assertThat(cost.value()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(cost.currency()).isEqualTo(Currency.getInstance("USD"));
        verify(listener).onRecord(argThat(event ->
                event.modelId().equals("free-model") &&
                event.usage().equals(usage) &&
                event.cost().equals(cost)
        ));
    }

    @Test
    @DisplayName("한 리스너의 RuntimeException은 비용 결과와 다음 리스너 호출에 영향을 주지 않아야 한다")
    void shouldIsolateListenerRuntimeExceptionAndContinuePublishing() {
        PricingPlan plan = new PricingPlan(
                "gpt-4o",
                new BigDecimal("5.0"),
                new BigDecimal("15.0")
        );
        registry.registerPlan(plan);
        LedgerListener failingListener = mock(LedgerListener.class);
        LedgerListener succeedingListener = mock(LedgerListener.class);
        DefaultLedgerManager isolatedManager = new DefaultLedgerManager(
                registry,
                calculator,
                List.of(failingListener, succeedingListener)
        );
        TokenUsage usage = TokenUsage.from(1_000, 1_000);
        doThrow(new IllegalStateException("listener failed"))
                .when(failingListener)
                .onRecord(any(CostRecordedEvent.class));

        Cost cost = isolatedManager.record("gpt-4o", usage, Map.of());

        assertThat(cost).isEqualTo(Cost.of(
                new BigDecimal("20.000000"),
                Currency.getInstance("USD")
        ));
        var inOrder = inOrder(failingListener, succeedingListener);
        inOrder.verify(failingListener).onRecord(any(CostRecordedEvent.class));
        inOrder.verify(succeedingListener).onRecord(argThat(event ->
                event.modelId().equals("gpt-4o")
                        && event.usage().equals(usage)
                        && event.cost().equals(cost)
        ));
    }
}
