package io.tokenpilot.budget;

/**
 * 같은 요청의 중복 예약을 식별하는 불변 키입니다.
 */
public record IdempotencyKey(String value) {

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
