package io.tokenpilot.core;

import io.tokenpilot.core.domain.PreflightDecisionEvent;

/** Framework-independent contract for best-effort observation of context admission decisions. */
@FunctionalInterface
public interface PreflightDecisionListener {

    void onDecision(PreflightDecisionEvent event);
}
