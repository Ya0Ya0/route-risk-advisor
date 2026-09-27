package com.routeriskadvisor.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.domain.model.RiskAssessment;
import com.routeriskadvisor.domain.model.RiskCategory;
import com.routeriskadvisor.domain.model.RiskLevel;
import com.routeriskadvisor.domain.model.RiskObservation;
import com.routeriskadvisor.domain.model.RouteSegment;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for {@link RiskScorer} score-to-level banding.
 *
 * <p>Implements design correctness Property 6: for any integer Risk_Score in 0..100, the
 * assigned {@link RiskLevel} is LOW for 0–33, MEDIUM for 34–66, and HIGH for 67–100.
 *
 * <p>The banding logic is exercised through the real public {@link RiskScorer#score} API. A
 * single present observation with {@code distanceMeters > 0} and
 * {@code normalizedIntensity = score / 100.0} yields a distance-weighted mean intensity of
 * exactly {@code score / 100.0}, which the scorer maps to {@code round(intensity * 100) = score}.
 * The produced score therefore equals the generated target exactly, letting us assert the band.
 *
 * <p><strong>Validates: Requirements 2.5</strong>
 */
class ScoreToLevelMappingProperties {

    private final RiskScorer scorer = new RiskScorer();

    // Feature: route-risk-advisor, Property 6: Score maps to the correct risk level
    @Property(tries = 100)
    void scoreMapsToCorrectRiskLevel(@ForAll("scores") int targetScore) {
        RiskObservation observation = observationForScore(targetScore);
        RiskAssessment assessment = scorer.score(RiskCategory.ACCIDENT, List.of(observation));

        assertThat(assessment.score())
            .as("driven score should equal the generated target: %s", targetScore)
            .isEqualTo(targetScore);

        assertThat(assessment.level())
            .as("score %s must band to the expected risk level", targetScore)
            .isEqualTo(expectedLevel(targetScore));
    }

    /** Generates integer Risk_Scores across the full inclusive range 0..100. */
    @Provide
    Arbitrary<Integer> scores() {
        return Arbitraries.integers().between(0, 100);
    }

    /**
     * Builds a single present observation that drives the scorer to produce exactly
     * {@code targetScore}. Uses a positive segment distance so the observation carries weight.
     */
    private static RiskObservation observationForScore(int targetScore) {
        RouteSegment segment = new RouteSegment(
            "seg-" + targetScore,
            new GeoCoordinate(25.7617, -80.1918),
            new GeoCoordinate(25.7700, -80.2000),
            1000.0);
        return new RiskObservation(segment, targetScore / 100.0, 1);
    }

    /** Reference banding independent of the implementation: 0–33 LOW, 34–66 MEDIUM, 67–100 HIGH. */
    private static RiskLevel expectedLevel(int score) {
        if (score <= 33) {
            return RiskLevel.LOW;
        }
        if (score <= 66) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.HIGH;
    }
}
