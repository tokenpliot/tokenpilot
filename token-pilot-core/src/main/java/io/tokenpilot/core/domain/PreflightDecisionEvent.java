package io.tokenpilot.core.domain;

import java.util.Objects;

/**
 * Framework-independent event carrying a context admission decision before provider invocation.
 *
 * @param result immutable result containing bounded status/reason and decision metadata
 */
public record PreflightDecisionEvent(BudgetResult result) {

    public PreflightDecisionEvent {
        Objects.requireNonNull(result, "result must not be null");
    }
}
