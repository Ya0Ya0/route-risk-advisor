package com.routeriskadvisor.domain;

import com.routeriskadvisor.domain.model.RiskAssessment;
import com.routeriskadvisor.domain.model.RiskCategory;
import com.routeriskadvisor.domain.model.RiskLevel;
import com.routeriskadvisor.domain.model.RiskObservation;
import com.routeriskadvisor.domain.model.RouteSegment;

import java.util.List;

/**
 * Converts per-segment provider observations into a route-level {@link RiskAssessment} for a
 * single {@link RiskCategory}.
 *
 * <p>The scoring approach follows the design's "Risk Scoring Approach" section:
 * <ol>
 *   <li>Each present {@link RiskObservation} contributes its {@code normalizedIntensity}
 *       (in {@code [0.0, 1.0]}) weighted by its segment's {@code distanceMeters}, so longer
 *       segments influence the aggregate proportionally (a distance-weighted mean).</li>
 *   <li>The aggregate intensity is mapped to an integer score
 *       {@code round(intensity * 100)}, clamped to {@code [0, 100]} (Req 2.4).</li>
 *   <li>The score is banded into a {@link RiskLevel}: 0-33 LOW, 34-66 MEDIUM, 67-100 HIGH
 *       (Req 2.5).</li>
 *   <li>If <em>every</em> segment lacked data for the category (i.e. the observation list is
 *       empty), the category is reported as {@link RiskLevel#UNKNOWN} with no score
 *       (Req 2.7, 2.8).</li>
 * </ol>
 *
 * <p>Observations passed to {@link #score} are the ones that were <em>present</em> for the
 * category: segments whose provider returned empty are simply omitted from the list. When no
 * observations are present, the result is Unknown.
 */
public class RiskScorer {

    private static final int MIN_SCORE = 0;
    private static final int MAX_SCORE = 100;

    /** Inclusive upper bound of the Low band. */
    private static final int LOW_MAX = 33;
    /** Inclusive upper bound of the Medium band. */
    private static final int MEDIUM_MAX = 66;

    /**
     * Scores a single category from the observations that were present for it.
     *
     * @param category     the category being scored; must not be {@code null}
     * @param observations the observations for segments that returned data for this category;
     *                     an empty list means no segment had data. Must not be {@code null}.
     * @return a scored {@link RiskAssessment} when at least one observation is present, or an
     *         Unknown assessment (null score) when the list is empty
     */
    public RiskAssessment score(RiskCategory category, List<RiskObservation> observations) {
        if (category == null) {
            throw new IllegalArgumentException("category must not be null");
        }
        if (observations == null) {
            throw new IllegalArgumentException("observations must not be null");
        }

        if (observations.isEmpty()) {
            // Every segment lacked data for this category (Req 2.7, 2.8).
            return new RiskAssessment(category, null, RiskLevel.UNKNOWN);
        }

        double intensity = distanceWeightedMeanIntensity(observations);
        int score = toScore(intensity);
        RiskLevel level = levelFor(score);
        return new RiskAssessment(category, score, level);
    }

    /**
     * Aggregates present observations into a single route intensity in {@code [0.0, 1.0]}
     * using a distance-weighted mean of {@code normalizedIntensity}.
     *
     * <p>Weight is each segment's {@code distanceMeters}. If the total weight is zero (e.g.
     * all present segments report zero distance), the method falls back to an unweighted mean
     * so a valid, bounded intensity is still produced rather than dividing by zero.
     */
    private static double distanceWeightedMeanIntensity(List<RiskObservation> observations) {
        double weightedSum = 0.0;
        double totalWeight = 0.0;
        for (RiskObservation observation : observations) {
            double weight = weightOf(observation);
            weightedSum += observation.normalizedIntensity() * weight;
            totalWeight += weight;
        }

        double intensity;
        if (totalWeight > 0.0) {
            intensity = weightedSum / totalWeight;
        } else {
            // Degenerate case: no positive distance weight. Fall back to an unweighted mean.
            double sum = 0.0;
            for (RiskObservation observation : observations) {
                sum += observation.normalizedIntensity();
            }
            intensity = sum / observations.size();
        }

        // Guard against tiny floating-point excursions outside [0.0, 1.0].
        return clampIntensity(intensity);
    }

    /**
     * Returns the non-negative distance weight for an observation. A missing or negative
     * distance contributes zero weight rather than distorting the mean.
     */
    private static double weightOf(RiskObservation observation) {
        RouteSegment segment = observation.segment();
        if (segment == null) {
            return 0.0;
        }
        double distance = segment.distanceMeters();
        return distance > 0.0 ? distance : 0.0;
    }

    private static double clampIntensity(double intensity) {
        if (intensity < 0.0) {
            return 0.0;
        }
        if (intensity > 1.0) {
            return 1.0;
        }
        return intensity;
    }

    /**
     * Maps an intensity in {@code [0.0, 1.0]} to an integer score, clamped to {@code [0, 100]}
     * (Req 2.4).
     */
    private static int toScore(double intensity) {
        long rounded = Math.round(intensity * 100.0);
        if (rounded < MIN_SCORE) {
            return MIN_SCORE;
        }
        if (rounded > MAX_SCORE) {
            return MAX_SCORE;
        }
        return (int) rounded;
    }

    /**
     * Bands a score into a {@link RiskLevel}: 0-33 LOW, 34-66 MEDIUM, 67-100 HIGH (Req 2.5).
     */
    private static RiskLevel levelFor(int score) {
        if (score <= LOW_MAX) {
            return RiskLevel.LOW;
        }
        if (score <= MEDIUM_MAX) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.HIGH;
    }
}
