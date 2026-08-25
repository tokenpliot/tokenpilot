package io.tokenpilot.core.domain;

import java.util.Objects;

/**
 * provider 호출 전 context admission 판정을 전달하는 framework-independent 이벤트입니다.
 *
 * @param result bounded status/reason과 판정 metadata를 포함한 immutable 결과
 */
public record PreflightDecisionEvent(BudgetResult result) {

    public PreflightDecisionEvent {
        Objects.requireNonNull(result, "result must not be null");
    }
}
