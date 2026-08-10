package io.tokenpilot.core;

import io.tokenpilot.core.domain.PreflightCostResult;
import io.tokenpilot.core.domain.PreflightPricingContext;
import io.tokenpilot.core.domain.TokenCountResult;

/**
 * 호출 전에 계산한 REQUEST 범위의 토큰 상한을 금액 상한으로 변환하는 Core 계약입니다.
 *
 * <p>이 계약은 비용을 계산할 뿐 context window 적합성이나 provider 호출 허가를
 * 판정하지 않습니다. 호출자는 요청 토큰 결과가 모델의 context admission을 통과했고,
 * {@link PreflightPricingContext}가 계산에 사용할 하나의 불변 pricing snapshot을
 * 보관하는지 먼저 보장해야 합니다. 계산기는 다른 snapshot을 registry에서 다시
 * 조회하지 않습니다.</p>
 *
 * <p>계산 가능한 경우에도 예약 근거로 사용할 값은
 * {@link PreflightCostResult.Bounded#safeUpperBoundCost()}뿐입니다.
 * {@code estimatedCost}는 관찰과 표시를 위한 값입니다.</p>
 */
public interface PreflightCostEstimator {

    /**
     * atomic reservation에 사용할 호출 전 비용 상한을 계산합니다.
     * 입력이 REQUEST 범위가 아니거나 tokenizer 기준이 맞지 않거나 가격 snapshot이
     * 없거나 문맥에 snapshot이 없으면 숫자 비용 대신 제한된 unavailable 결과를 반환해야 합니다.
     *
     * @param pricingContext canonical model, pricing policy, catalog version과
     *                       검증된 pricing 조건을 담은 계산 문맥
     * @param requestInput 실제 전송 요청 전체를 계산한 REQUEST 범위의 token 결과
     * @param reservedOutputTokens 호출 전에 확보할 최대 출력 token 수
     * @return 계산 가능한 비용 상한 또는 그 사유를 담은 unavailable 결과
     * @throws NullPointerException 필수 인자가 {@code null}인 경우
     * @throws IllegalArgumentException reservedOutputTokens가 음수인 경우
     */
    PreflightCostResult estimate(
            PreflightPricingContext pricingContext,
            TokenCountResult requestInput,
            long reservedOutputTokens
    );
}
