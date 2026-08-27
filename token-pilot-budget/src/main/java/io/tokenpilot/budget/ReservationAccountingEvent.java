package io.tokenpilot.budget;

import java.util.Objects;

/** Accounting event for a newly applied reservation settlement. */
public record ReservationAccountingEvent(
        ReservationReconciliation reconciliation
) {

    public ReservationAccountingEvent {
        Objects.requireNonNull(
                reconciliation,
                "reconciliation must not be null"
        );
    }
}
