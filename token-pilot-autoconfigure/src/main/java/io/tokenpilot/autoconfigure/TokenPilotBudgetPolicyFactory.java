package io.tokenpilot.autoconfigure;

import io.tokenpilot.budget.BudgetPolicy;
import io.tokenpilot.core.domain.Cost;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Objects;

/**
 * Token Pilot 설정을 optional budget 정책으로 변환하는 공개 진입점입니다.
 *
 * <p>budget 타입을 {@link TokenPilotProperties}에서 분리해 budget 모듈이 없는
 * core-only 자동 설정도 class loading에 실패하지 않도록 합니다.</p>
 */
public final class TokenPilotBudgetPolicyFactory {

    private TokenPilotBudgetPolicyFactory() {
    }

    public static BudgetPolicy from(TokenPilotProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        return from(properties.getBudget());
    }

    public static BudgetPolicy from(TokenPilotProperties.BudgetProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        return new BudgetPolicy(
                properties.getPolicyId(),
                properties.getTargetType(),
                properties.getTargetTagKey(),
                properties.getFallbackTargetId(),
                Cost.of(
                        properties.getMonthlyLimit(),
                        Currency.getInstance(properties.getCurrency())
                ),
                ZoneId.of(properties.getZoneId())
        );
    }
}
