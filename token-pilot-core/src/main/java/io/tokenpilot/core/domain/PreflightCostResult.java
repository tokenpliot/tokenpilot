package io.tokenpilot.core.domain;

import java.util.Currency;
import java.util.Objects;

/**
 * 호출 전 비용 계산 결과입니다. 숫자 상한이 있는 결과와 계산 불가 결과를
 * sealed hierarchy로 분리해 0원과 pricing 부재를 혼동하지 않습니다.
 */
public sealed interface PreflightCostResult
        permits PreflightCostResult.Bounded, PreflightCostResult.Unavailable {

    /**
     * immutable pricing snapshot으로 계산한 유한 비용 상한입니다.
     * 내부 계산은 반올림하지 않습니다.
     *
     * @param estimatedCost 관찰과 표시를 위한 예상 비용
     * @param safeUpperBoundCost reservation에 사용할 보수적 비용 상한
     * @param inputEstimatedTokens 정보 제공용 input token 계산값
     * @param inputSafeUpperBoundTokens 보수적인 input token 안전 상한
     * @param reservedOutputTokens 예약한 최대 output token 수
     * @param canonicalModelId canonical model 식별자
     * @param pricingPolicyId immutable pricing policy 식별자
     * @param catalogVersion 계산에 사용한 catalog 버전
     * @param estimatorDescriptor token estimator 식별 정보
     * @param tokenizationBasis 검증된 tokenizer compatibility 기준
     */
    record Bounded(
            Cost estimatedCost,
            Cost safeUpperBoundCost,
            long inputEstimatedTokens,
            long inputSafeUpperBoundTokens,
            long reservedOutputTokens,
            String canonicalModelId,
            String pricingPolicyId,
            String catalogVersion,
            TokenEstimatorDescriptor estimatorDescriptor,
            TokenizationBasis tokenizationBasis
    ) implements PreflightCostResult {

        /**
         * 비용, token 관계와 재현성 metadata를 검증합니다.
         */
        public Bounded {
            estimatedCost = Objects.requireNonNull(estimatedCost, "estimatedCost must not be null");
            safeUpperBoundCost = Objects.requireNonNull(
                    safeUpperBoundCost,
                    "safeUpperBoundCost must not be null"
            );
            validateTokens(inputEstimatedTokens, inputSafeUpperBoundTokens, reservedOutputTokens);
            canonicalModelId = requireText(canonicalModelId, "canonicalModelId");
            pricingPolicyId = requireText(pricingPolicyId, "pricingPolicyId");
            catalogVersion = requireText(catalogVersion, "catalogVersion");
            estimatorDescriptor = Objects.requireNonNull(
                    estimatorDescriptor,
                    "estimatorDescriptor must not be null"
            );
            tokenizationBasis = Objects.requireNonNull(tokenizationBasis, "tokenizationBasis must not be null");

            if (!estimatedCost.currency().equals(safeUpperBoundCost.currency())) {
                throw new IllegalArgumentException("estimated and safe upper bound currencies must match");
            }
            if (safeUpperBoundCost.compareTo(estimatedCost) < 0) {
                throw new IllegalArgumentException("safeUpperBoundCost must be greater than or equal to estimatedCost");
            }
        }

        /**
         * 계산된 비용의 통화를 반환합니다.
         *
         * @return estimated cost와 safe upper bound cost가 공유하는 통화
         */
        public Currency currency() {
            return safeUpperBoundCost.currency();
        }
    }

    /**
     * fail-closed 경계에서 숫자 비용으로 취급할 수 없는 결과입니다.
     *
     * @param reason 숫자 비용을 생성하지 못한 제한된 사유
     * @param canonicalModelId canonical model 식별자
     * @param pricingPolicyId 참조한 pricing policy 식별자
     * @param catalogVersion 참조한 catalog 버전
     * @param currency model이 요구한 비용 통화
     * @param reservedOutputTokens 요청한 최대 output token 수
     * @param estimatorDescriptor token estimator 식별 정보
     * @param tokenizationBasis token 결과의 tokenizer compatibility 기준
     */
    record Unavailable(
            PreflightCostUnavailableReason reason,
            String canonicalModelId,
            String pricingPolicyId,
            String catalogVersion,
            Currency currency,
            long reservedOutputTokens,
            TokenEstimatorDescriptor estimatorDescriptor,
            TokenizationBasis tokenizationBasis
    ) implements PreflightCostResult {

        /**
         * Unavailable 사유와 진단 metadata를 검증합니다.
         */
        public Unavailable {
            reason = Objects.requireNonNull(reason, "reason must not be null");
            canonicalModelId = requireText(canonicalModelId, "canonicalModelId");
            pricingPolicyId = requireText(pricingPolicyId, "pricingPolicyId");
            catalogVersion = requireText(catalogVersion, "catalogVersion");
            currency = Objects.requireNonNull(currency, "currency must not be null");
            if (reservedOutputTokens < 0) {
                throw new IllegalArgumentException("reservedOutputTokens must be non-negative");
            }
            estimatorDescriptor = Objects.requireNonNull(
                    estimatorDescriptor,
                    "estimatorDescriptor must not be null"
            );
            tokenizationBasis = Objects.requireNonNull(tokenizationBasis, "tokenizationBasis must not be null");
        }
    }

    private static void validateTokens(long estimated, long safeUpperBound, long reservedOutput) {
        if (estimated < 0 || safeUpperBound < 0 || reservedOutput < 0) {
            throw new IllegalArgumentException("token values must be non-negative");
        }
        if (safeUpperBound < estimated) {
            throw new IllegalArgumentException(
                    "inputSafeUpperBoundTokens must be greater than or equal to inputEstimatedTokens"
            );
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
