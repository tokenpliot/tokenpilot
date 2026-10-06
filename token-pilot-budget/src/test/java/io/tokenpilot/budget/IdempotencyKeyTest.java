package io.tokenpilot.budget;                                                    // 대상 클래스와 같은 패키지에 둔다

import org.junit.jupiter.api.Test;                                               // JUnit 5의 테스트 어노테이션

import static org.assertj.core.api.Assertions.assertThat;                        // 일반 값 검증용 AssertJ 메서드
import static org.assertj.core.api.Assertions.assertThatThrownBy;                // 예외 발생 검증용 AssertJ 메서드

class IdempotencyKeyTest {                                                       // IdempotencyKey를 검증하는 테스트 클래스

  @Test                                                                        // 아래 메서드가 테스트임을 표시
  void valueIsPreserved() {                                                    // 생성할 때 넣은 값이 그대로 보존되는지 확인
    IdempotencyKey key = new IdempotencyKey("request-123");                  // 정상 값으로 키 생성

    assertThat(key.value()).isEqualTo("request-123");                        // value()가 넣은 값과 같은지 검증
  }                                                                            // valueIsPreserved 끝

  @Test                                                                        // 테스트 표시
  void toStringReturnsValue() {                                                // toString이 값만 반환하는지 확인
    IdempotencyKey key = new IdempotencyKey("request-123");                  // 정상 값으로 키 생성

    assertThat(key.toString()).isEqualTo("request-123");                     // record 기본 형식이 아니라 값 그대로여야 함
  }                                                                            // toStringReturnsValue 끝

  @Test                                                                        // 테스트 표시
  void valueIsNotTrimmed() {                                                   // 앞뒤 공백이 있어도 값을 가공하지 않는지 확인
    IdempotencyKey key = new IdempotencyKey("  request-123  ");              // 앞뒤에 공백이 있는 값으로 생성

    assertThat(key.value()).isEqualTo("  request-123  ");                    // 공백이 제거되지 않고 그대로 남아야 함
  }                                                                            // valueIsNotTrimmed 끝

  @Test                                                                        // 테스트 표시
  void nullValueIsRejected() {                                                 // null 값이 거부되는지 확인
    assertThatThrownBy(() -> new IdempotencyKey(null))                       // null로 생성을 시도
        .isInstanceOf(IllegalArgumentException.class)                    // IllegalArgumentException이 발생해야 함
        .hasMessage("value must not be blank");                          // 예외 메시지도 정확히 일치해야 함
  }                                                                            // nullValueIsRejected 끝

  @Test                                                                        // 테스트 표시
  void emptyValueIsRejected() {                                                // 빈 문자열이 거부되는지 확인
    assertThatThrownBy(() -> new IdempotencyKey(""))                         // 빈 문자열로 생성을 시도
        .isInstanceOf(IllegalArgumentException.class)                    // IllegalArgumentException이 발생해야 함
        .hasMessage("value must not be blank");                          // 예외 메시지 검증
  }                                                                            // emptyValueIsRejected 끝

  @Test                                                                        // 테스트 표시
  void blankValueIsRejected() {                                                // 공백만 있는 문자열이 거부되는지 확인
    assertThatThrownBy(() -> new IdempotencyKey("   "))                      // 스페이스만 있는 문자열로 생성을 시도
        .isInstanceOf(IllegalArgumentException.class)                    // IllegalArgumentException이 발생해야 함
        .hasMessage("value must not be blank");                          // 예외 메시지 검증
  }                                                                            // blankValueIsRejected 끝

  @Test                                                                        // 테스트 표시
  void whitespaceOnlyValueWithTabAndNewlineIsRejected() {                      // 탭과 줄바꿈만 있는 값도 거부되는지 확인
    assertThatThrownBy(() -> new IdempotencyKey("\t\n"))                     // 탭과 줄바꿈 문자로만 이뤄진 값으로 생성을 시도
        .isInstanceOf(IllegalArgumentException.class)                    // IllegalArgumentException이 발생해야 함
        .hasMessage("value must not be blank");                          // 예외 메시지 검증
  }                                                                            // whitespaceOnlyValueWithTabAndNewlineIsRejected 끝

  @Test                                                                        // 테스트 표시
  void keysWithSameValueAreEqual() {                                           // 값이 같은 키끼리 동등한지 확인
    IdempotencyKey first = new IdempotencyKey("request-123");                // 첫 번째 키 생성
    IdempotencyKey second = new IdempotencyKey("request-123");               // 같은 값으로 두 번째 키 생성

    assertThat(first).isEqualTo(second);                                     // record이므로 값이 같으면 equals가 true
    assertThat(first.hashCode()).isEqualTo(second.hashCode());               // equals가 같으면 hashCode도 같아야 함
  }                                                                            // keysWithSameValueAreEqual 끝

  @Test                                                                        // 테스트 표시
  void keysWithDifferentValuesAreNotEqual() {                                  // 값이 다른 키끼리는 다른지 확인
    IdempotencyKey first = new IdempotencyKey("request-123");                // 첫 번째 키 생성
    IdempotencyKey second = new IdempotencyKey("request-456");               // 다른 값으로 두 번째 키 생성

    assertThat(first).isNotEqualTo(second);                                  // 값이 다르면 equals가 false
  }                                                                            // keysWithDifferentValuesAreNotEqual 끝
}                                                                                // IdempotencyKeyTest 끝