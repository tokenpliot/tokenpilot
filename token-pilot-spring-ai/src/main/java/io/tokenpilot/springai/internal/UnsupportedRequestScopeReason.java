package io.tokenpilot.springai.internal;

/** Bounded reasons for Spring AI request scopes unsupported by Token Pilot 0.1.0. */
enum UnsupportedRequestScopeReason {
    TOOL_MESSAGE,
    MEDIA,
    TOOL_CALL,
    TOOL_SCHEMA,
    MESSAGE_TEXT_UNAVAILABLE,
    STRUCTURED_OUTPUT
}
