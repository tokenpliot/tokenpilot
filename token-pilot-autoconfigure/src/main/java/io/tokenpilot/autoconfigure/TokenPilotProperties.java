package io.tokenpilot.autoconfigure;

import io.tokenpilot.core.domain.PricingPlan;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Token Pilot의 설정을 담당하는 프로퍼티 클래스.
 */
@ConfigurationProperties(prefix = "token-pilot")
public class TokenPilotProperties {

    private boolean enabled = true;

    @NestedConfigurationProperty
    private PricingProperties pricing = new PricingProperties();

    @NestedConfigurationProperty
    private MetricsProperties metrics = new MetricsProperties();

    @NestedConfigurationProperty
    private BudgetProperties budget = new BudgetProperties();

    @NestedConfigurationProperty
    private NotificationProperties notification = new NotificationProperties();

    @NestedConfigurationProperty
    private SpringAiProperties springAi = new SpringAiProperties();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public PricingProperties getPricing() {
        return pricing;
    }

    public void setPricing(PricingProperties pricing) {
        this.pricing = pricing;
    }

    public MetricsProperties getMetrics() {
        return metrics;
    }

    public void setMetrics(MetricsProperties metrics) {
        this.metrics = metrics;
    }

    public BudgetProperties getBudget() {
        return budget;
    }

    public void setBudget(BudgetProperties budget) {
        this.budget = budget;
    }

    public NotificationProperties getNotification() {
        return notification;
    }

    public void setNotification(NotificationProperties notification) {
        this.notification = notification;
    }

    public SpringAiProperties getSpringAi() {
        return springAi;
    }

    public void setSpringAi(SpringAiProperties springAi) {
        this.springAi = springAi;
    }

    public List<PricingPlan> toPricingPlans() {
        if (pricing == null || pricing.getPlans() == null) {
            return List.of();
        }

        return pricing.getPlans()
                      .stream()
                      .map(PricingPlanProperties::toPricingPlan)
                      .toList();
    }

    public static class PricingProperties {
        private List<PricingPlanProperties> plans = new ArrayList<>();

        public List<PricingPlanProperties> getPlans() {
            return plans;
        }

        public void setPlans(List<PricingPlanProperties> plans) {
            this.plans = plans;
        }
    }

    public static class MetricsProperties {
        private boolean enabled = true;
        private Set<String> tagWhitelist = new HashSet<>();
        private boolean legacyAiTokenMetricsEnabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Set<String> getTagWhitelist() {
            return tagWhitelist;
        }

        public void setTagWhitelist(Set<String> tagWhitelist) {
            this.tagWhitelist = tagWhitelist;
        }

        public boolean isLegacyAiTokenMetricsEnabled() {
            return legacyAiTokenMetricsEnabled;
        }

        public void setLegacyAiTokenMetricsEnabled(
                boolean legacyAiTokenMetricsEnabled
        ) {
            this.legacyAiTokenMetricsEnabled = legacyAiTokenMetricsEnabled;
        }
    }

    public static class BudgetProperties {
        private boolean enabled = false;
        private java.math.BigDecimal monthlyLimit = new java.math.BigDecimal("10.00");
        private String policyId = "default-monthly";
        private String targetType = "tenant";
        private String targetTagKey = "tenant_id";
        private String fallbackTargetId;
        private String currency = "USD";
        private String zoneId = "UTC";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public java.math.BigDecimal getMonthlyLimit() {
            return monthlyLimit;
        }

        public void setMonthlyLimit(java.math.BigDecimal monthlyLimit) {
            this.monthlyLimit = monthlyLimit;
        }

        public String getPolicyId() {
            return policyId;
        }

        public void setPolicyId(String policyId) {
            this.policyId = policyId;
        }

        public String getTargetType() {
            return targetType;
        }

        public void setTargetType(String targetType) {
            this.targetType = targetType;
        }

        public String getTargetTagKey() {
            return targetTagKey;
        }

        public void setTargetTagKey(String targetTagKey) {
            this.targetTagKey = targetTagKey;
        }

        /**
         * 대상 tag가 없을 때 사용할 명시적 fallback입니다. 미설정 시 평가는 fail-closed 됩니다.
         */
        public String getFallbackTargetId() {
            return fallbackTargetId;
        }

        public void setFallbackTargetId(String fallbackTargetId) {
            this.fallbackTargetId = fallbackTargetId;
        }

        public String getCurrency() {
            return currency;
        }

        public void setCurrency(String currency) {
            this.currency = currency;
        }

        /**
         * 월별 budget window 경계를 계산하는 IANA ZoneId입니다. 기본값은 UTC입니다.
         */
        public String getZoneId() {
            return zoneId;
        }

        public void setZoneId(String zoneId) {
            this.zoneId = zoneId;
        }
    }

    public static class NotificationProperties {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class SpringAiProperties {
        private String defaultModelId;
        private Long defaultReservedOutputTokens;
        private long framingHeadroomTokens;

        public String getDefaultModelId() {
            return defaultModelId;
        }

        public void setDefaultModelId(String defaultModelId) {
            this.defaultModelId = defaultModelId;
        }

        public Long getDefaultReservedOutputTokens() {
            return defaultReservedOutputTokens;
        }

        public void setDefaultReservedOutputTokens(Long defaultReservedOutputTokens) {
            this.defaultReservedOutputTokens = defaultReservedOutputTokens;
        }

        public long getFramingHeadroomTokens() {
            return framingHeadroomTokens;
        }

        public void setFramingHeadroomTokens(long framingHeadroomTokens) {
            this.framingHeadroomTokens = framingHeadroomTokens;
        }
    }
}
