package com.routeriskadvisor.domain.model;

/**
 * A per-category risk result: either a scored value with a Low/Medium/High level, or Unknown
 * with no score.
 *
 * <p>Invariant (Req 2.8): {@code score} is {@code null} exactly when {@code level == UNKNOWN};
 * for LOW/MEDIUM/HIGH the {@code score} is non-null and within the inclusive range [0, 100].
 *
 * @param category the risk category this assessment describes
 * @param score    0..100, or {@code null} when {@code level == UNKNOWN}
 * @param level    the banded risk level
 */
public record RiskAssessment(
    RiskCategory category,
    Integer score,
    RiskLevel level
) {
    public RiskAssessment {
        if (category == null) {
            throw new IllegalArgumentException("category must not be null");
        }
        if (level == null) {
            throw new IllegalArgumentException("level must not be null");
        }
        if (level == RiskLevel.UNKNOWN) {
            if (score != null) {
                throw new IllegalArgumentException(
                    "score must be null when level == UNKNOWN, but was " + score);
            }
        } else {
            if (score == null) {
                throw new IllegalArgumentException(
                    "score must be non-null when level == " + level);
            }
            if (score < 0 || score > 100) {
                throw new IllegalArgumentException(
                    "score must be within [0, 100] when level == " + level + ", but was " + score);
            }
        }
    }
}
