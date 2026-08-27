package io.tokenpilot.core.domain;

import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable model catalog definition used for both context admission and
 * pricing policy lookup.
 *
 * @param canonicalModelId versioned canonical model ID
 * @param aliases exact aliases resolved to the canonical ID
 * @param encodingName model encoding asset name
 * @param acceptedCompatibilityBasis compatibility basis accepted by heuristic/exact estimators
 * @param maxContextTokens model context window size
 * @param pricingPolicyId canonical pricing policy identifier
 * @param catalogVersion model catalog version
 * @param sourceUri authoritative source for context/encoding metadata
 * @param sourceAsOf time at which the source was checked
 */
public record ModelDefinition(
        String canonicalModelId,
        Set<String> aliases,
        String encodingName,
        TokenizationBasis acceptedCompatibilityBasis,
        long maxContextTokens,
        String pricingPolicyId,
        Currency pricingCurrency,
        String catalogVersion,
        URI sourceUri,
        Instant sourceAsOf
) {

    public ModelDefinition {
        canonicalModelId = requireText(canonicalModelId, "canonicalModelId");
        aliases = copyAliases(aliases);
        if (aliases.contains(canonicalModelId)) {
            throw new IllegalArgumentException("aliases must not contain canonicalModelId");
        }
        encodingName = requireText(encodingName, "encodingName");
        acceptedCompatibilityBasis = Objects.requireNonNull(
                acceptedCompatibilityBasis,
                "acceptedCompatibilityBasis must not be null"
        );
        if (maxContextTokens <= 0) {
            throw new IllegalArgumentException("maxContextTokens must be greater than zero");
        }
        pricingPolicyId = requireText(pricingPolicyId, "pricingPolicyId");
        pricingCurrency = Objects.requireNonNull(pricingCurrency, "pricingCurrency must not be null");
        catalogVersion = requireText(catalogVersion, "catalogVersion");
        sourceUri = Objects.requireNonNull(sourceUri, "sourceUri must not be null");
        if (!sourceUri.isAbsolute()) {
            throw new IllegalArgumentException("sourceUri must be absolute");
        }
        sourceAsOf = Objects.requireNonNull(sourceAsOf, "sourceAsOf must not be null");
    }

    /**
     * Uses USD as the default currency for compatibility with existing model
     * definition construction code.
     */
    public ModelDefinition(
            String canonicalModelId,
            Set<String> aliases,
            String encodingName,
            TokenizationBasis acceptedCompatibilityBasis,
            long maxContextTokens,
            String pricingPolicyId,
            String catalogVersion,
            URI sourceUri,
            Instant sourceAsOf
    ) {
        this(
                canonicalModelId,
                aliases,
                encodingName,
                acceptedCompatibilityBasis,
                maxContextTokens,
                pricingPolicyId,
                Currency.getInstance("USD"),
                catalogVersion,
                sourceUri,
                sourceAsOf
        );
    }

    private static Set<String> copyAliases(Set<String> aliases) {
        Objects.requireNonNull(aliases, "aliases must not be null");
        Set<String> copied = new LinkedHashSet<>();
        for (String alias : aliases) {
            copied.add(requireText(alias, "alias"));
        }
        return Collections.unmodifiableSet(copied);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
