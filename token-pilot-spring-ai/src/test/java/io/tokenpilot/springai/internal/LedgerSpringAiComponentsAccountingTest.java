package io.tokenpilot.springai.internal;

import io.tokenpilot.budget.BudgetEvaluator;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.budget.ReservationAccounting;
import io.tokenpilot.core.ModelRegistry;
import io.tokenpilot.core.PreflightCostEstimator;
import io.tokenpilot.core.PricingRegistry;
import io.tokenpilot.core.TokenBudget;
import io.tokenpilot.core.TokenEstimator;
import io.tokenpilot.springai.UsageExtractor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class LedgerSpringAiComponentsAccountingTest {

    @Test
    void 서로_다른_예약_store와_accounting_writer를_조합하지_않는다() {
        assertThatThrownBy(() -> LedgerSpringAiComponents.accountingLedgerAdvisor(
                mock(UsageExtractor.class),
                mock(BudgetEvaluator.class),
                mock(BudgetStateStore.class),
                mock(ReservationAccounting.class),
                mock(PricingRegistry.class),
                mock(ModelRegistry.class),
                mock(TokenEstimator.class),
                mock(TokenBudget.class),
                mock(PreflightCostEstimator.class),
                null,
                null,
                0L
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "budgetStateStore and reservationAccounting must be the same instance"
                );
    }
}
