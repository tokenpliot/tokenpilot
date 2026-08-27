package io.tokenpilot.core.domain;

import java.util.Map;

/**
 * Event data emitted when token usage and cost are recorded.
 * Defined as an immutable record to keep event delivery stable.
 */
public record CostRecordedEvent(
    String modelId,
    TokenUsage usage,
    Cost cost,
    Map<String, String> tags
) {}
