package io.tokenpilot.core;

import io.tokenpilot.core.domain.CostRecordedEvent;

/**
 * Listener interface for cost-recording events.
 * It serves as the Observer in the observer pattern and runs on plain Java.
 */
@FunctionalInterface
public interface LedgerListener {
    /**
     * Called when a cost-recording event occurs.
     * @param event emitted cost-recording event
     */
    void onRecord(CostRecordedEvent event);
}
