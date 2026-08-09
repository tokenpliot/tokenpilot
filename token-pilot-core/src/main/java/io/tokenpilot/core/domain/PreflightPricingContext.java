package io.tokenpilot.core.domain;

import java.util.Currency;
import java.util.Objects;

/**
 * canonical model 해석 이후 preflight 비용 계산에 전달하는 불변 pricing projection입니다.
 *
 * <p>alias는 ModelRegistry에서 먼저 canonical model로 해석해야 하며, 이 문맥을
 * 임의의 문자열 조합으로 만들면 안 됩니다. {@code catalogVersion},
 * {@code pricingPolicyId}, 통화와 tokenizer 기준은 같은 모델 정의와 pricing
 * snapshot에서 함께 파생되어야 합니다.</p>
 *
 * <p>현재 타입은 snapshot 자체가 아니라 식별자 projection을 담습니다. 따라서
 * 계산·예약·정산 과정에서 mutable registry를 다시 조회해 다른 가격을 섞지 않도록
 * 호출 계층이 동일한 불변 snapshot을 보존해야 합니다. {@link UpperBoundCapability}
 * 역시 호출자가 임의로 선언하는 값이 아니라 검증된 pricing policy의 결과여야 합니다.</p>
 *
 * @param canonicalModelId canonical model 식별자
 * @param pricingPolicyId 불변 pricing policy 식별자
 * @param catalogVersion model과 pricing catalog의 버전
 * @param tokenizationBasis 모델이 허용하는 tokenizer 호환성 기준
 * @param currency 모델이 요구하는 비용 통화
 * @param upperBoundCapability pricing policy가 유한한 비용 상한을 제공할 수 있는지 나타내는 결과
 */
public record PreflightPricingContext(
        String canonicalModelId,
        String pricingPolicyId,
        String catalogVersion,
        TokenizationBasis tokenizationBasis,
        Currency currency,
        UpperBoundCapability upperBoundCapability
) {

    /** 모든 식별자와 호환성 metadata가 비어 있지 않은지 검증합니다. */
    public PreflightPricingContext {
        canonicalModelId = requireText(canonicalModelId, "canonicalModelId");
        pricingPolicyId = requireText(pricingPolicyId, "pricingPolicyId");
        catalogVersion = requireText(catalogVersion, "catalogVersion");
        tokenizationBasis = Objects.requireNonNull(tokenizationBasis, "tokenizationBasis must not be null");
        currency = Objects.requireNonNull(currency, "currency must not be null");
        upperBoundCapability = Objects.requireNonNull(
                upperBoundCapability,
                "upperBoundCapability must not be null"
        );
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /** pricing policy가 호출 전에 유한한 보수적 상한을 제공할 수 있는지 나타냅니다. */
    public enum UpperBoundCapability {
        /** 모든 적용 경로에 유한한 최대 단가가 있습니다. */
        FINITE,
        /** 하나 이상의 적용 경로에 유한한 최대 단가가 없습니다. */
        UNBOUNDED
    }
}
