package io.tokenpilot.micrometer;

import java.util.Set;

/**
 * Micrometer metric publishing options.
 *
 * @param allowedTagKeys metric tag keys allowed beyond the built-in low-cardinality tags
 * @param legacyAiTokenMetricsEnabled whether the legacy {@code ai.token.*} meters are enabled
 */
public record MetricsOptions(
        Set<String> allowedTagKeys,
        boolean legacyAiTokenMetricsEnabled
) {

    public static final Set<String> DEFAULT_ALLOWED_TAG_KEYS = Set.of();
    private static final Set<String> LEGACY_DEFAULT_ALLOWED_TAG_KEYS =
            Set.of("tenant_id");

    public MetricsOptions {
        allowedTagKeys = normalize(allowedTagKeys);
    }

    /**
     * Constructor preserving source and binary compatibility with existing direct construction code.
     *
     * <p>Using this constructor directly is treated as an explicit opt-in to
     * legacy {@code ai.token.*} meters. Starter auto-configuration uses
     * {@link #defaults()} by default to disable legacy meters.</p>
     */
    public MetricsOptions(Set<String> allowedTagKeys) {
        this(
                allowedTagKeys == null
                        ? LEGACY_DEFAULT_ALLOWED_TAG_KEYS
                        : allowedTagKeys,
                true
        );
    }

    public static MetricsOptions defaults() {
        return new MetricsOptions(DEFAULT_ALLOWED_TAG_KEYS, false);
    }

    public static MetricsOptions withAllowedTagKeys(Set<String> allowedTagKeys) {
        return new MetricsOptions(allowedTagKeys);
    }

    public static MetricsOptions legacyDefaults() {
        return new MetricsOptions(LEGACY_DEFAULT_ALLOWED_TAG_KEYS, true);
    }

    private static Set<String> normalize(Set<String> allowedTagKeys) {
        if (allowedTagKeys == null) {
            return DEFAULT_ALLOWED_TAG_KEYS;
        }
        return Set.copyOf(allowedTagKeys);
    }
}
