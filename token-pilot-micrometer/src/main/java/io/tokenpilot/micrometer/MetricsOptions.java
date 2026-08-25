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
     * 기존 직접 생성 코드의 source/binary 호환성을 유지하는 생성자입니다.
     *
     * <p>이 생성자를 직접 사용하는 것은 legacy {@code ai.token.*} meter에 대한 명시적
     * opt-in으로 취급됩니다. Starter 자동 설정 기본값은 {@link #defaults()}를 사용하여
     * legacy meter를 비활성화합니다.</p>
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
