package io.tokenpilot.notification;

import io.tokenpilot.budget.BudgetReservationRequest;
import io.tokenpilot.budget.BudgetReservationResult;
import io.tokenpilot.budget.BudgetSnapshot;
import io.tokenpilot.budget.ReservationAccountingEvent;
import io.tokenpilot.budget.ReservationReconciliationRequiredEvent;

/** Store contract for atomically claiming thresholds from atomic budget/accounting results. */
public interface AtomicNotificationStateStore extends NotificationStateStore {

  NotificationClaim recordAppliedTransition(
      ReservationAccountingEvent event,
      BudgetSnapshot snapshot,
      BudgetNotificationSource source
  );

  NotificationClaim recordReconciliationRequired(
      ReservationReconciliationRequiredEvent event
  );

  NotificationClaim recordBlockedReservation(
      BudgetReservationRequest request,
      BudgetReservationResult result
  );
}
