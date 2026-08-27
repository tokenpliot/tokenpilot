package io.tokenpilot.core;

import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.PricingPlan;
import io.tokenpilot.core.domain.PricingSnapshot;
import io.tokenpilot.core.domain.TokenUsage;

import java.util.Map;

/**
 * Unified manager interface for recording and managing AI call spending.
 */
public interface LedgerManager {
    /**
     * Records a call for a model and calculates the final cost.
     * @param modelId model identifier
     * @param usage   token usage
     * @param tags    additional metadata, such as tenant_id or user_id
     * @return calculated cost
     */
    Cost record(String modelId, TokenUsage usage, Map<String, String> tags);

    /**
     * Records a call and calculates the final cost using an already resolved pricing policy.
     * @param plan    pricing policy resolved before provider invocation
     * @param usage   token usage
     * @param tags    additional metadata, such as tenant_id or user_id
     * @return calculated cost
     */
    Cost record(PricingPlan plan, TokenUsage usage, Map<String, String> tags);

    /**
     * Records a call and calculates the final cost using a request-scoped pricing snapshot.
     * @param snapshot pricing snapshot retained before provider invocation
     * @param usage    token usage
     * @param tags     additional metadata, such as tenant_id or user_id
     * @return calculated cost
     */
    Cost record(PricingSnapshot snapshot, TokenUsage usage, Map<String, String> tags);
}
