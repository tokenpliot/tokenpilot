package io.tokenpilot.budget.internal;

/** Path on which the reserved amount was released. */
enum ReleaseType {
    BEFORE_DISPATCH,
    CONFIRMED_UNBILLED
}
