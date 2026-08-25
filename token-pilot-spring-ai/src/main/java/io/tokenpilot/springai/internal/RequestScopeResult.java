package io.tokenpilot.springai.internal;

import io.tokenpilot.core.domain.TokenCountScope;

import java.util.Objects;
import java.util.Optional;

/** 지원하는 request scope 또는 제한된 미지원 사유를 나타냅니다. */
final class RequestScopeResult {

    private final Optional<TokenCountScope> scope;
    private final Optional<UnsupportedRequestScopeReason> unsupportedReason;

    private RequestScopeResult(
            Optional<TokenCountScope> scope,
            Optional<UnsupportedRequestScopeReason> unsupportedReason
    ) {
        this.scope = scope;
        this.unsupportedReason = unsupportedReason;
    }

    static RequestScopeResult textOnly() {
        return new RequestScopeResult(
                Optional.of(TokenCountScope.TEXT_ONLY),
                Optional.empty()
        );
    }

    static RequestScopeResult unsupported(UnsupportedRequestScopeReason reason) {
        return new RequestScopeResult(
                Optional.empty(),
                Optional.of(Objects.requireNonNull(reason, "reason must not be null"))
        );
    }

    Optional<TokenCountScope> scope() {
        return scope;
    }

    Optional<UnsupportedRequestScopeReason> unsupportedReason() {
        return unsupportedReason;
    }
}
