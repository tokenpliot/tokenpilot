package io.tokenpilot.micrometer.internal;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.tokenpilot.core.*;
import io.tokenpilot.core.domain.*;
import io.tokenpilot.micrometer.MetricsOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MicroCostMetricsPublisherTest {

    private MeterRegistry meterRegistry;
    private MicroCostMetricsPublisher publisher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        publisher = new MicroCostMetricsPublisher(meterRegistry);
    }

    @Test
    @DisplayName("비용 기록 이벤트 발생 시 토큰 사용량과 비용 메트릭이 올바르게 발행되어야 한다")
    void shouldPublishMetricsWhenEventRecorded() {
        // Given
        TokenUsage usage = TokenUsage.from(100, 200);
        Cost cost = new Cost(new BigDecimal("0.5"), Currency.getInstance("USD"));
        Map<String, String> tags = Map.of("tenant_id", "tenant-1");
        CostRecordedEvent event = new CostRecordedEvent("gpt-4o", usage, cost, tags);

        // When
        publisher.onRecord(event);

        // Then: verify the token usage counter
        assertThat(meterRegistry.find("ai.token.usage.total")
                .tag("model", "gpt-4o")
                .tag("token_type", "prompt")
                .counter().count()).isEqualTo(100.0);

        assertThat(meterRegistry.find("ai.token.usage.total")
                .tag("model", "gpt-4o")
                .tag("token_type", "completion")
                .counter().count()).isEqualTo(200.0);

        // Then: verify the token usage distribution summary
        assertThat(meterRegistry.find("ai.token.usage.distribution")
                .tag("model", "gpt-4o")
                .tag("token_type", "prompt")
                .summary().max()).isEqualTo(100.0);

        // Then: verify the cost counter
        var costCounter = meterRegistry.find("ai.token.cost.total")
                .tag("model", "gpt-4o")
                .tag("currency", "USD")
                .counter();

        assertThat(costCounter.count()).isEqualTo(0.5);
        assertThat(costCounter.getId().getDescription()).isEqualTo("Total estimated AI token cost");
        assertThat(costCounter.getId().getBaseUnit()).isEqualTo("currency");

        var promptSummary = meterRegistry.find("ai.token.usage.distribution")
                .tag("model", "gpt-4o")
                .tag("token_type", "prompt")
                .summary();

        assertThat(promptSummary.getId().getDescription())
                .isEqualTo("Distribution of AI token usage per recorded model call");
        assertThat(promptSummary.getId().getBaseUnit()).isEqualTo("tokens");
        assertThat(costCounter.getId().getTag("tenant_id"))
                .isEqualTo("tenant-1");
    }

    @Test
    @DisplayName("허용된 태그만 메트릭에 포함되어야 한다")
    void shouldPublishOnlyAllowedTags() {
        // Given
        publisher = new MicroCostMetricsPublisher(meterRegistry, Set.of("tenant_id"));
        TokenUsage usage = TokenUsage.from(100, 200);
        Cost cost = new Cost(new BigDecimal("0.5"), Currency.getInstance("USD"));
        Map<String, String> tags = Map.of(
                "tenant_id", "tenant-1",
                "user_id", "user-123",
                "request_id", "req-999"
        );
        CostRecordedEvent event = new CostRecordedEvent("gpt-4o", usage, cost, tags);

        // When
        publisher.onRecord(event);

        // Then
        assertThat(meterRegistry.find("ai.token.cost.total")
                .tag("model", "gpt-4o")
                .tag("tenant_id", "tenant-1")
                .tag("currency", "USD")
                .counter()).isNotNull();

        assertThat(meterRegistry.find("ai.token.cost.total")
                .tag("user_id", "user-123")
                .counter()).isNull();

        assertThat(meterRegistry.find("ai.token.cost.total")
                .tag("request_id", "req-999")
                .counter()).isNull();
    }

    @Test
    @DisplayName("MetricsOptions로 여러 허용 태그를 지정할 수 있어야 한다")
    void shouldPublishMultipleAllowedTagsFromOptions() {
        publisher = new MicroCostMetricsPublisher(
                meterRegistry,
                MetricsOptions.withAllowedTagKeys(Set.of("tenant_id", "team"))
        );
        TokenUsage usage = TokenUsage.from(100, 200);
        Cost cost = new Cost(new BigDecimal("0.5"), Currency.getInstance("USD"));
        Map<String, String> tags = Map.of(
                "tenant_id", "tenant-1",
                "team", "platform",
                "request_id", "req-999"
        );
        CostRecordedEvent event = new CostRecordedEvent("gpt-4o", usage, cost, tags);

        publisher.onRecord(event);

        assertThat(meterRegistry.find("ai.token.cost.total")
                .tag("model", "gpt-4o")
                .tag("tenant_id", "tenant-1")
                .tag("team", "platform")
                .tag("currency", "USD")
                .counter()).isNotNull();

        assertThat(meterRegistry.find("ai.token.cost.total")
                .tag("request_id", "req-999")
                .counter()).isNull();
    }

    @Test
    @DisplayName("이벤트 태그가 null이어도 기본 메트릭은 발행되어야 한다")
    void shouldPublishMetricsWhenTagsAreNull() {
        TokenUsage usage = TokenUsage.from(100, 200);
        Cost cost = new Cost(new BigDecimal("0.5"), Currency.getInstance("USD"));
        CostRecordedEvent event = new CostRecordedEvent("gpt-4o", usage, cost, null);

        publisher.onRecord(event);

        assertThat(meterRegistry.find("ai.token.cost.total")
                .tag("model", "gpt-4o")
                .tag("currency", "USD")
                .counter()).isNotNull();
    }

    @Test
    @DisplayName("이벤트 태그가 비어 있어도 기본 메트릭은 발행되어야 한다")
    void shouldPublishMetricsWhenTagsAreEmpty() {
        TokenUsage usage = TokenUsage.from(100, 200);
        Cost cost = new Cost(new BigDecimal("0.5"), Currency.getInstance("USD"));
        CostRecordedEvent event = new CostRecordedEvent("gpt-4o", usage, cost, Map.of());

        publisher.onRecord(event);

        assertThat(meterRegistry.find("ai.token.cost.total")
                .tag("model", "gpt-4o")
                .tag("currency", "USD")
                .counter()).isNotNull();
    }

    @Test
    @DisplayName("기본 MetricsOptions는 사용자 태그와 legacy meter를 비활성화해야 한다")
    void shouldUseSafeDefaults() {
        MetricsOptions options = MetricsOptions.defaults();

        assertThat(options.allowedTagKeys()).isEmpty();
        assertThat(options.legacyAiTokenMetricsEnabled()).isFalse();
    }

    @Test
    @DisplayName("legacy 비활성 options를 사용하면 ai.token meter를 발행하지 않아야 한다")
    void shouldNotPublishLegacyMetricsWhenDisabled() {
        publisher = new MicroCostMetricsPublisher(
                meterRegistry,
                MetricsOptions.defaults()
        );

        publisher.onRecord(new CostRecordedEvent(
                "gpt-4o",
                TokenUsage.from(100, 200),
                new Cost(new BigDecimal("0.5"), Currency.getInstance("USD")),
                Map.of("tenant_id", "tenant-1")
        ));

        assertThat(meterRegistry.find("ai.token.usage.total").meter()).isNull();
        assertThat(meterRegistry.find("ai.token.usage.distribution").meter()).isNull();
        assertThat(meterRegistry.find("ai.token.cost.total").meter()).isNull();
    }

    @Test
    @DisplayName("1-arg MetricsOptions 생성자는 기존 직접 생성의 legacy opt-in을 유지해야 한다")
    void shouldPreserveOneArgumentOptionsCompatibility() {
        MetricsOptions options = new MetricsOptions(Set.of("tenant_id"));

        assertThat(options.allowedTagKeys()).containsExactly("tenant_id");
        assertThat(options.legacyAiTokenMetricsEnabled()).isTrue();
    }

    @Test
    @DisplayName("기존 direct 기본 생성자는 tenant_id 허용 동작을 유지해야 한다")
    void shouldPreserveDirectPublisherDefaultTagCompatibility() {
        publisher.onRecord(new CostRecordedEvent(
                "gpt-4o",
                TokenUsage.from(1, 1),
                Cost.of(new BigDecimal("0.1"), Currency.getInstance("USD")),
                Map.of("tenant_id", "tenant-legacy", "user_id", "user-sensitive")
        ));

        var counter = meterRegistry.get("ai.token.cost.total")
                .tag("tenant_id", "tenant-legacy")
                .counter();
        assertThat(counter.getId().getTag("user_id")).isNull();
    }
}
