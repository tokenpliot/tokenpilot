package io.tokenpilot.springai.internal;

import io.tokenpilot.core.PreflightCostEstimator;
import io.tokenpilot.core.PricingEvaluator;
import io.tokenpilot.core.PricingRegistry;
import io.tokenpilot.core.TokenBudget;
import io.tokenpilot.core.TokenEstimator;
import io.tokenpilot.core.domain.ModelDefinition;
import io.tokenpilot.core.domain.PreflightCostResult;
import io.tokenpilot.core.domain.PreflightPricingContext;
import io.tokenpilot.core.domain.PricingResolution;
import io.tokenpilot.core.domain.TokenCountAccuracy;
import io.tokenpilot.core.domain.TokenCountResult;
import io.tokenpilot.core.domain.TokenCountScope;
import io.tokenpilot.core.exception.MissingPricingException;
import io.tokenpilot.core.internal.LedgerComponents;
import org.springframework.ai.chat.client.ChatClientRequest;

import java.util.Objects;

/** Connects Spring AI request resolution results to the existing Core preflight contract. */
final class RequestPreflight {

    private final DefaultRequestAdapter requestAdapter = new DefaultRequestAdapter();
    private final RequestScopeResolver scopeResolver = new RequestScopeResolver();
    private final RequestFramingPolicy framingPolicy = new RequestFramingPolicy();
    private final ModelResolver modelResolver;
    private final ReservedOutputResolver outputResolver;
    private final TokenEstimator tokenEstimator;
    private final TokenBudget tokenBudget;
    private final PricingRegistry pricingRegistry;
    private final PricingEvaluator pricingEvaluator;
    private final PreflightCostEstimator costEstimator;
    private final long framingHeadroomTokens;

    RequestPreflight(
            ModelResolver modelResolver,
            ReservedOutputResolver outputResolver,
            TokenEstimator tokenEstimator,
            TokenBudget tokenBudget,
            PricingRegistry pricingRegistry,
            PreflightCostEstimator costEstimator,
            long framingHeadroomTokens
    ) {
        this(
                modelResolver,
                outputResolver,
                tokenEstimator,
                tokenBudget,
                pricingRegistry,
                LedgerComponents.defaultPricingEvaluator(),
                costEstimator,
                framingHeadroomTokens
        );
    }

    RequestPreflight(
            ModelResolver modelResolver,
            ReservedOutputResolver outputResolver,
            TokenEstimator tokenEstimator,
            TokenBudget tokenBudget,
            PricingRegistry pricingRegistry,
            PricingEvaluator pricingEvaluator,
            PreflightCostEstimator costEstimator,
            long framingHeadroomTokens
    ) {
        this.modelResolver = Objects.requireNonNull(modelResolver);
        this.outputResolver = Objects.requireNonNull(outputResolver);
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator);
        this.tokenBudget = Objects.requireNonNull(tokenBudget);
        this.pricingRegistry = Objects.requireNonNull(pricingRegistry);
        this.pricingEvaluator = Objects.requireNonNull(pricingEvaluator);
        this.costEstimator = Objects.requireNonNull(costEstimator);
        if (framingHeadroomTokens < 0) {
            throw new IllegalArgumentException("framingHeadroomTokens must be non-negative");
        }
        this.framingHeadroomTokens = framingHeadroomTokens;
    }

    PreflightCostResult.Bounded resolve(ChatClientRequest request) {
        requireTextOnly(request);
        ModelDefinition model = modelResolver.resolve(request)
                .orElseThrow(() -> new IllegalStateException("MODEL_UNRESOLVED"));
        long reservedOutputTokens = outputResolver.resolve(request)
                .orElseThrow(() -> new IllegalStateException(
                        "OUTPUT_RESERVATION_UNRESOLVED"
                ));
        TokenCountResult requestTokens = countRequest(request);
        tokenBudget.requireFits(
                model.canonicalModelId(),
                requestTokens,
                reservedOutputTokens
        );

        var pricingSnapshot = pricingRegistry.resolveSnapshot(model);
        PricingResolution pricingResolution = pricingEvaluator.validateSnapshotRates(
                pricingSnapshot
        );
        if (!pricingResolution.isResolved()) {
            throw new MissingPricingException(pricingResolution);
        }
        PreflightPricingContext pricing = new PreflightPricingContext(
                model.canonicalModelId(),
                model.pricingPolicyId(),
                model.catalogVersion(),
                model.acceptedCompatibilityBasis(),
                model.pricingCurrency(),
                PreflightPricingContext.UpperBoundCapability.FINITE,
                pricingSnapshot
        );
        PreflightCostResult result = costEstimator.estimate(
                pricing,
                requestTokens,
                reservedOutputTokens
        );
        if (result instanceof PreflightCostResult.Bounded bounded) {
            return bounded;
        }
        PreflightCostResult.Unavailable unavailable =
                (PreflightCostResult.Unavailable) result;
        throw new IllegalStateException(
                "preflight cost bound unavailable: " + unavailable.reason()
        );
    }

    private void requireTextOnly(ChatClientRequest request) {
        RequestScopeResult result = scopeResolver.resolve(request);
        if (result.scope().isPresent()) {
            return;
        }
        throw new IllegalStateException(
                "UNSUPPORTED_REQUEST_SCOPE: "
                        + result.unsupportedReason().orElseThrow()
        );
    }

    private TokenCountResult countRequest(ChatClientRequest request) {
        TokenCountResult text = tokenEstimator.estimate(
                framingPolicy.frame(requestAdapter.adapt(request))
        );
        if (text.isUnavailable()) {
            return TokenCountResult.unavailable(
                    text.unavailableReason().orElseThrow(),
                    TokenCountScope.REQUEST,
                    text.estimatorDescriptor(),
                    text.tokenizationBasis()
            );
        }
        return TokenCountResult.counted(
                text.tokens().orElseThrow(),
                Math.addExact(
                        text.safeUpperBoundTokens().orElseThrow(),
                        framingHeadroomTokens
                ),
                TokenCountAccuracy.HEURISTIC,
                TokenCountScope.REQUEST,
                text.estimatorDescriptor(),
                text.tokenizationBasis()
        );
    }

}
