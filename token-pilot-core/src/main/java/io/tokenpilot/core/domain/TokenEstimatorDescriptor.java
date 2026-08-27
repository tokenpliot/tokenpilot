package io.tokenpilot.core.domain;

/**
 * Identifies the estimator implementation and version used for token counting.
 *
 * @param estimatorId stable estimator implementation identifier
 * @param estimatorVersion version distinguishing formula or asset changes
 */
public record TokenEstimatorDescriptor(
        String estimatorId,
        String estimatorVersion
) {

    /**
     * Validates that the estimator identifier and version are not null or blank.
     *
     * @throws IllegalArgumentException when estimatorId or estimatorVersion is null or blank
     */
    public TokenEstimatorDescriptor {
        estimatorId = requireText(estimatorId, "estimatorId");
        estimatorVersion = requireText(estimatorVersion, "estimatorVersion");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
