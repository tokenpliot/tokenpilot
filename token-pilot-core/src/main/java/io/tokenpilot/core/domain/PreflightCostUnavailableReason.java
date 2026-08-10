package io.tokenpilot.core.domain;

/**
 * 호출 전 보수적 비용 상한을 숫자로 확정할 수 없는 제한된 사유입니다.
 */
public enum PreflightCostUnavailableReason {
    /** token 계산 결과를 사용할 수 없는 상태입니다. */
    COUNT_UNAVAILABLE,
    /** 전체 REQUEST 범위를 포함하지 않은 token 결과입니다. */
    INCOMPLETE_SCOPE,
    /** estimator와 model의 tokenizer 기준이 호환되지 않습니다. */
    INCOMPATIBLE_TOKENIZER,
    /** 참조한 pricing policy snapshot을 찾지 못했습니다. */
    PRICING_NOT_FOUND,
    /** 비용 계산에 필요한 기본 입력/출력 단가가 누락됐습니다. */
    INCOMPLETE_PRICING,
    /** pricing policy가 호출 전에 유한한 상한을 제공할 수 없습니다. */
    UNBOUNDED_PRICING,
    /** model과 pricing snapshot의 통화가 다릅니다. */
    CURRENCY_MISMATCH,
    /** canonical model, policy 또는 catalog 식별자가 snapshot과 다릅니다. */
    PRICING_SNAPSHOT_MISMATCH,
    /** 가격 정밀도가 계산 범위를 벗어나 정형 비용 결과를 만들 수 없습니다. */
    ARITHMETIC_FAILURE
}
