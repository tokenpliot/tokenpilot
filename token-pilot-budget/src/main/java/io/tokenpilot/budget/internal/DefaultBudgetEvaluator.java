package io.tokenpilot.budget.internal;

import io.tokenpilot.budget.BudgetDecision;
import io.tokenpilot.budget.BudgetDecision.EvaluationType;
import io.tokenpilot.budget.BudgetEvaluator;
import io.tokenpilot.budget.BudgetKey;
import io.tokenpilot.budget.BudgetPolicy;
import io.tokenpilot.budget.BudgetState;
import io.tokenpilot.budget.BudgetStateStore;
import io.tokenpilot.budget.BudgetThreshold;
import io.tokenpilot.budget.BudgetWindow;
import io.tokenpilot.core.domain.Cost;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * 현재 구현이 지원하는 snapshot은 확정 비용과 후보 비용입니다.
 * {@code projectedUsage = committedUsage + candidateCost}이며 active reservation과
 * reconciliation liability는 #36, #37에서 추가됩니다.
 */
public class DefaultBudgetEvaluator implements BudgetEvaluator {

  private final BudgetStateStore store;
  private final BudgetPolicy policy;
  private final Clock clock;

  public DefaultBudgetEvaluator(BudgetStateStore store, BudgetPolicy policy, Clock clock) {
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.policy = Objects.requireNonNull(policy, "policy must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  @Override
  public BudgetDecision evaluate(Map<String, String> tags, Cost candidateCost) {
    Objects.requireNonNull(candidateCost, "candidateCost must not be null");
    BudgetKey key = resolveKey(tags);
    Cost committedUsage = store.getAccumulatedCost(key, policy.monthlyLimit());
    if (!policy.monthlyLimit().currency().equals(candidateCost.currency())) {
      return decision(
          key,
          EvaluationType.ADMISSION,
          BudgetState.CURRENCY_MISMATCH,
          BudgetThreshold.NONE,
          "예산 통화와 비용 통화가 일치하지 않습니다",
          committedUsage,
          committedUsage
      );
    }

    Cost projectedUsage = committedUsage.add(candidateCost);
    return decide(
        key,
        EvaluationType.ADMISSION,
        committedUsage,
        projectedUsage
    );
  }

  @Override
  public BudgetDecision evaluate(Map<String, String> tags) {
    BudgetKey key = resolveKey(tags);
    Cost committedUsage = store.getAccumulatedCost(key, policy.monthlyLimit());
    return decide(
        key,
        EvaluationType.STATUS,
        committedUsage,
        committedUsage
    );
  }

  private BudgetKey resolveKey(Map<String, String> tags) {
    Objects.requireNonNull(tags, "tags must not be null");
    String targetId = tags.get(policy.targetTagKey());
    if (targetId == null || targetId.isBlank()) {
      targetId = policy.fallbackTargetId();
    }
    if (targetId == null) {
      throw new IllegalArgumentException(
          "Missing required budget target tag: " + policy.targetTagKey()
      );
    }
    return new BudgetKey(
        policy.id(),
        policy.targetType(),
        targetId,
        BudgetWindow.resolve(clock, policy.zoneId())
    );
  }

  private BudgetDecision decide(
      BudgetKey key,
      EvaluationType evaluationType,
      Cost committedUsage,
      Cost projectedUsage
  ) {
    Cost halfThreshold = threshold("0.5");
    Cost warningThreshold = threshold("0.8");

    if (projectedUsage.compareTo(policy.monthlyLimit()) >= 0) {
      return decision(
          key,
          evaluationType,
          BudgetState.BLOCK,
          BudgetThreshold.EXCEEDED,
          "월 예산을 초과했습니다",
          committedUsage,
          projectedUsage
      );
    }
    if (projectedUsage.compareTo(warningThreshold) >= 0) {
      return decision(
          key,
          evaluationType,
          BudgetState.WARN,
          BudgetThreshold.WARNING,
          "월 예산의 80% 이상 사용",
          committedUsage,
          projectedUsage
      );
    }
    if (projectedUsage.compareTo(halfThreshold) >= 0) {
      return decision(
          key,
          evaluationType,
          BudgetState.ALLOW,
          BudgetThreshold.HALF,
          "월 예산의 50% 이상 사용",
          committedUsage,
          projectedUsage
      );
    }
    return decision(
        key,
        evaluationType,
        BudgetState.ALLOW,
        BudgetThreshold.NONE,
        "정상 범위 사용",
        committedUsage,
        projectedUsage
    );
  }

  private BudgetDecision decision(
      BudgetKey key,
      EvaluationType evaluationType,
      BudgetState state,
      BudgetThreshold threshold,
      String reason,
      Cost committedUsage,
      Cost projectedUsage
  ) {
    return new BudgetDecision(
        key,
        evaluationType,
        state,
        threshold,
        reason,
        committedUsage,
        projectedUsage,
        policy.monthlyLimit()
    );
  }

  private Cost threshold(String ratio) {
    return Cost.of(
        policy.monthlyLimit().value().multiply(new BigDecimal(ratio)),
        policy.monthlyLimit().currency()
    );
  }
}
