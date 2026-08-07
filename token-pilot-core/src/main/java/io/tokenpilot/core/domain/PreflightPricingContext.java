package io.tokenpilot.core.domain;

import java.util.Currency;
import java.util.Objects;

/**
 * canonical model resolution 이후 preflight 비용 계산에 전달하는 immutable pricing projection입니다.
 * alias 해석은 ModelRegistry 경계가 소유하며 이 값에는 canonical 식별자만 전달해야 합니다.
 *
 * @param canonicalModelId canonical model 식별자
 * @param pricingPolicyId immutable pricing policy 식별자
 * @param catalogVersion model/pricing catalog 버전
 * @param tokenizationBasis model이 허용하는 tokenizer compatibility 기준
 * @param currency model이 요구하는 비용 통화
 * @param upperBoundCapability pricing policy의 유한 비용 상한 제공 가능 여부
 */
public record PreflightPricingContext(
        String canonicalModelId,
        String pricingPolicyId,
        String catalogVersion,
        TokenizationBasis tokenizationBasis,
        Currency currency,
        UpperBoundCapability upperBoundCapability
) {

    /**
     * 모든 식별자와 compatibility metadata를 검증합니다.
     */
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

    /**
     * pricing policy가 호출 전에 유한한 보수적 상한을 제공할 수 있는지 나타냅니다.
     */
    public enum UpperBoundCapability {
        /** 모든 적용 경로에 유한한 최대 단가가 있습니다. */
        FINITE,
        /** 하나 이상의 적용 경로에 유한한 최대 단가가 없습니다. */
        UNBOUNDED
    }
}
