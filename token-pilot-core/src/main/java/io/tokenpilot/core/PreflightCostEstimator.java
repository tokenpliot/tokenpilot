package io.tokenpilot.core;

import io.tokenpilot.core.domain.PreflightCostResult;
import io.tokenpilot.core.domain.PreflightPricingContext;
import io.tokenpilot.core.domain.TokenCountResult;

/**
 * 호출 전 REQUEST token 상한을 보수적인 금액 상한으로 변환합니다.
 * 구현은 숫자 비용을 반환하기 전에 token scope, tokenizer compatibility와
 * immutable pricing snapshot을 검증해야 합니다.
 */
public interface PreflightCostEstimator {

    /**
     * atomic reservation이 사용할 호출 전 비용 상한을 계산합니다.
     * {@link PreflightCostResult.Bounded#safeUpperBoundCost()}만 reservation 근거로 사용하며,
     * {@code estimatedCost}는 관찰과 표시 목적으로만 사용합니다.
     *
     * @param pricingContext canonical model과 pricing snapshot 조회 기준
     * @param requestInput REQUEST 전체를 계산한 token 결과
     * @param reservedOutputTokens 호출 전에 확보할 최대 출력 token 수
     * @return 계산 가능한 비용 상한 또는 제한된 unavailable 사유
     */
    PreflightCostResult estimate(
            PreflightPricingContext pricingContext,
            TokenCountResult requestInput,
            long reservedOutputTokens
    );
}
