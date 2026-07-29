package io.tokenpilot.budget;

import io.tokenpilot.core.domain.Cost;

import java.util.Objects;

/**
 * 부수 효과 없는 예산 평가 결과입니다.
 *
 * @param key 평가 시점에 확정된 예산 bucket 식별자
 * @param evaluationType 후보 비용 포함 여부
 * @param state ALLOW / WARN / BLOCK / CURRENCY_MISMATCH 상태
 * @param threshold 예상 사용량이 도달한 예산 임계치
 * @param reason 상태 설명
 * @param committedUsage 현재 확정된 누적 사용량
 * @param projectedUsage 후보 비용을 포함한 예상 사용량. 상태 조회이거나 통화가 일치하지 않으면
 *                       {@code committedUsage}와 같습니다.
 * @param limit 통화를 포함한 총 예산
 *
 * <p><strong>Migration note:</strong> {@link EvaluationType#STATUS} 결과는 현재 상태 조회용이며
 * provider 호출 허가의 근거가 아닙니다. 기존의 모호한 {@code currentUsage} 대신 확정
 * 사용량은 {@code committedUsage}, 후보 포함 사용량은 {@code projectedUsage}를 사용합니다.
 */
public record BudgetDecision(
    BudgetKey key,
    EvaluationType evaluationType,
    BudgetState state,
    BudgetThreshold threshold,
    String reason,
    Cost committedUsage,
    Cost projectedUsage,
    Cost limit
) {

  public BudgetDecision {
    Objects.requireNonNull(key, "key must not be null");
    Objects.requireNonNull(evaluationType, "evaluationType must not be null");
    Objects.requireNonNull(state, "state must not be null");
    Objects.requireNonNull(threshold, "threshold must not be null");
    Objects.requireNonNull(reason, "reason must not be null");
    Objects.requireNonNull(committedUsage, "committedUsage must not be null");
    Objects.requireNonNull(projectedUsage, "projectedUsage must not be null");
    Objects.requireNonNull(limit, "limit must not be null");

    if (!committedUsage.currency().equals(projectedUsage.currency())
        || !committedUsage.currency().equals(limit.currency())) {
      throw new IllegalArgumentException(
          "committedUsage, projectedUsage and limit must use the same currency"
      );
    }
    if (evaluationType == EvaluationType.STATUS
        && committedUsage.compareTo(projectedUsage) != 0) {
      throw new IllegalArgumentException(
          "STATUS decisions must not include candidate cost"
      );
    }
  }

  /**
   * 후보 비용을 포함해 provider admission을 판단한 결과인지 반환합니다.
   */
  public boolean isAdmissionDecision() {
    return evaluationType == EvaluationType.ADMISSION;
  }

  public enum EvaluationType {
    /**
     * 후보 비용 없는 조회 전용 결과입니다. Provider 호출 허가로 사용할 수 없습니다.
     */
    STATUS,

    /**
     * 후보 비용을 포함한 admission 판단 결과입니다.
     */
    ADMISSION
  }
}
