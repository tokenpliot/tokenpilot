package io.tokenpilot.springai.internal;

final class StreamingRequestPolicy {

    void requireSupported(boolean enforcementEnabled) {
        if (enforcementEnabled) {
            throw new IllegalStateException(
                    "STREAMING_UNSUPPORTED_FOR_ENFORCEMENT"
            );
        }
    }
}
