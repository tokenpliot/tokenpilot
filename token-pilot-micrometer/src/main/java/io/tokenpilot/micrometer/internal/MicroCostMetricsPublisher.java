package io.tokenpilot.micrometer.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.tokenpilot.core.LedgerListener;
import io.tokenpilot.core.domain.CostRecordedEvent;
import io.tokenpilot.core.domain.TokenType;
import io.tokenpilot.micrometer.MetricsOptions;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Micrometer 기반의 비용 메트릭 리스너.
 * {@link LedgerListener}를 구현하여 비용 기록 이벤트를 가로채고,
 * 이를 Prometheus 등 모니터링 시스템으로 전송합니다.
 */
public class MicroCostMetricsPublisher implements LedgerListener {

    private final MeterRegistry meterRegistry;
    private final Set<String> allowedTagKeys;
    private final boolean enabled;

    public MicroCostMetricsPublisher(MeterRegistry meterRegistry) {
        this(meterRegistry, MetricsOptions.legacyDefaults());
    }

    public MicroCostMetricsPublisher(MeterRegistry meterRegistry, Set<String> allowedTagKeys) {
        this(meterRegistry, MetricsOptions.withAllowedTagKeys(allowedTagKeys));
    }

    public MicroCostMetricsPublisher(MeterRegistry meterRegistry, MetricsOptions options) {
        this.meterRegistry = Objects.requireNonNull(
                meterRegistry,
                "meterRegistry must not be null"
        );
        MetricsOptions resolvedOptions = (options != null)
                ? options
                : MetricsOptions.legacyDefaults();
        this.allowedTagKeys = resolvedOptions.allowedTagKeys();
        this.enabled = resolvedOptions.legacyAiTokenMetricsEnabled();
    }

    @Override
    public void onRecord(CostRecordedEvent event) {
        if (!enabled) {
            return;
        }
        Objects.requireNonNull(event, "event must not be null");
        Tags commonTags = Tags.of("model", event.modelId())
                .and(allowedTags(event.tags()));

        final Tags finalTags = commonTags;

        for (TokenType tokenType : TokenType.values()) {
            long count = event.usage().getCount(tokenType);
            if (count > 0) {
                String typeName = tokenType.name().toLowerCase(Locale.ROOT);
                DistributionSummary.builder("ai.token.usage.distribution")
                        .description("Distribution of AI token usage per recorded model call")
                        .baseUnit("tokens")
                        .tags(finalTags.and("token_type", typeName))
                        .register(meterRegistry)
                        .record(count);
                Counter.builder("ai.token.usage.total")
                        .description("Total number of AI tokens recorded")
                        .baseUnit("tokens")
                        .tags(finalTags.and("token_type", typeName))
                        .register(meterRegistry)
                        .increment(count);
            }
        }
        Counter.builder("ai.token.cost.total")
                .description("Total estimated AI token cost")
                .baseUnit("currency")
                .tags(finalTags.and("currency", event.cost().currency().getCurrencyCode()))
                .register(meterRegistry)
                .increment(event.cost().value().doubleValue());
    }

    private Tags allowedTags(Map<String, String> tags) {
        if (tags == null || tags.isEmpty()) {
            return Tags.empty();
        }
        return Tags.of(tags.entrySet().stream()
                .filter(entry -> allowedTagKeys.contains(entry.getKey()))
                .map(entry -> Tag.of(entry.getKey(), entry.getValue()))
                .toList());
    }
}
