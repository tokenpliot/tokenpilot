package io.tokenpilot.notification;

import io.tokenpilot.budget.BudgetReservationRequest;
import io.tokenpilot.budget.BudgetReservationResult;
import io.tokenpilot.budget.BudgetSnapshot;
import io.tokenpilot.budget.ReservationAccountingEvent;
import io.tokenpilot.budget.ReservationReconciliationRequiredEvent;

/** atomic budget/accounting 결과의 threshold를 원자적으로 claim하는 저장소 계약입니다. */
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
