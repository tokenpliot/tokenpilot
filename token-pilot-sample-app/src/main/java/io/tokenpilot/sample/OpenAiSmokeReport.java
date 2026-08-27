package io.tokenpilot.sample;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Smoke result showing the real OpenAI call and the corresponding Token Pilot settlement. */
public record OpenAiSmokeReport(
        String status,
        String profile,
        String provider,
        String requestId,
        String configuredModel,
        String responseModel,
        String response,
        String usageSource,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        String accountingState,
        Map<String, Double> metrics,
        Map<String, String> budget,
        String note
) {

    public OpenAiSmokeReport {
        metrics = Collections.unmodifiableMap(new LinkedHashMap<>(metrics));
        budget = Collections.unmodifiableMap(new LinkedHashMap<>(budget));
    }
}
