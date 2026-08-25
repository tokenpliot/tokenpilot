package io.tokenpilot.micrometer.internal;

import java.util.Locale;

final class TokenPilotMeterMetadata {

    static final String COST_TOTAL = "tokenpilot.cost.total";
    static final String COST_TOTAL_DESCRIPTION =
            "Total actual LLM cost committed by Token Pilot";
    static final String COST_BASE_UNIT = "currency";

    static final String PREFLIGHT_REQUESTS = "tokenpilot.preflight.requests";
    static final String PREFLIGHT_REQUESTS_DESCRIPTION =
            "Total preflight admission decisions made by Token Pilot";
    static final String REQUESTS_BASE_UNIT = "requests";

    static final String BUDGET_RESERVATIONS = "tokenpilot.budget.reservations";
    static final String BUDGET_RESERVATIONS_DESCRIPTION =
            "Total budget reservation results produced by Token Pilot";
    static final String RESERVATIONS_BASE_UNIT = "reservations";

    static final String RECONCILIATION_ERROR_TOKENS =
            "tokenpilot.reconciliation.error.tokens";
    static final String RECONCILIATION_ERROR_TOKENS_DESCRIPTION =
            "Absolute difference between estimated and actual total tokens per reconciliation";
    static final String TOKENS_BASE_UNIT = "tokens";

    static final String RECONCILIATION_OUTCOMES =
            "tokenpilot.reconciliation.outcomes";
    static final String RECONCILIATION_OUTCOMES_DESCRIPTION =
            "Total reservation reconciliation outcomes applied by Token Pilot";
    static final String RECONCILIATIONS_BASE_UNIT = "reconciliations";

    static final String PRICING_MISSING = "tokenpilot.pricing.missing";
    static final String PRICING_MISSING_DESCRIPTION =
            "Total pricing misses observed by Token Pilot";

    static final String LISTENER_FAILURES = "tokenpilot.listener.failures";
    static final String LISTENER_FAILURES_DESCRIPTION =
            "Total listener failures isolated by Token Pilot";
    static final String FAILURES_BASE_UNIT = "failures";

    static final String NOTIFICATION_EVENTS = "tokenpilot.notification.events";
    static final String NOTIFICATION_EVENTS_DESCRIPTION =
            "Total budget notification lifecycle events observed by Token Pilot";
    static final String EVENTS_BASE_UNIT = "events";

    private TokenPilotMeterMetadata() {
    }

    static String tagValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
