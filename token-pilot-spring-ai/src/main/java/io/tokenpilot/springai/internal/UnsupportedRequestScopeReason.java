package io.tokenpilot.springai.internal;

/** Token Pilot 0.1.0이 지원하지 않는 Spring AI request scope의 제한된 사유입니다. */
enum UnsupportedRequestScopeReason {
    TOOL_MESSAGE,
    MEDIA,
    TOOL_CALL,
    TOOL_SCHEMA,
    MESSAGE_TEXT_UNAVAILABLE,
    STRUCTURED_OUTPUT
}
