package io.tokenpilot.notification;

import io.tokenpilot.budget.BudgetThreshold;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BudgetNotificationLifecycleEventTest {

  @Test
  void bounded_outcome과_threshold를_보존한다() {
    BudgetNotificationLifecycleEvent event = new BudgetNotificationLifecycleEvent(
        BudgetNotificationOutcome.SUCCESS,
        BudgetThreshold.WARNING
    );

    assertThat(event.outcome()).isEqualTo(BudgetNotificationOutcome.SUCCESS);
    assertThat(event.threshold()).isEqualTo(BudgetThreshold.WARNING);
  }

  @Test
  void NONE_threshold를_거부한다() {
    assertThatThrownBy(() -> new BudgetNotificationLifecycleEvent(
        BudgetNotificationOutcome.DEDUPLICATED,
        BudgetThreshold.NONE
    ))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("threshold must identify a notification boundary");
  }
}
