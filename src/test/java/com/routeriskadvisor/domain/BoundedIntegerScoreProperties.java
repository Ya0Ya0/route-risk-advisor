package com.routeriskadvisor.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.domain.model.RiskAssessment;
import com.routeriskadvisor.domain.model.RiskCategory;
import com.routeriskadvisor.domain.model.RiskLevel;
import com.routeriskadvisor.domain.model.RiskObservation;
import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteSegment;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for {@link RiskScorer} score bounding.
 *
 * <p>Implements design correctness Property 5: for any Route and any set of provider
 * observations (each with a {@code normalizedIntensity} in {@code [0.0, 1.0]}), every produced
 * Risk_Score is an integer in the inclusive range {@code [0, 100]}.
 *
 * <p><strong>Validates: Requirements 2.4</strong>
 */
class BoundedIntegerScoreProperties {

    private final RiskScorer scorer = new RiskScorer();

    // Feature: route-risk-advisor, Property 5: Risk scores are bounded integers
    @Property(tries = 200)
    void scoreIsAnIntegerWithinZeroToHundred(
            @ForAll RiskCategory category,
            @ForAll("observationsOverRoute") List<RiskObservation> observations) {
        RiskAssessment assessment = scorer.score(category, observations);

        if (assessment.level() == RiskLevel.UNKNOWN) {
            // No present observations: Unknown with no score (Req 2.7, 2.8). Nothing to bound.
            assertThat(assessment.score())
                .as("Unknown assessments must carry no score: %s", assessment)
                .isNull();
        } else {
            Integer score = assessment.score();
            assertThat(score)
                .as("scored assessment must carry a non-null score: %s", assessment)
                .isNotNull();
            assertThat(score)
                .as("Risk_Score must be an integer in [0, 100]: %s", assessment)
                .isBetween(0, 100);
        }
    }

    /**
     * Generates the observation list for a freshly generated {@link Route}: each of the route's
     * segments is paired with an independently drawn {@code normalizedIntensity} in
     * {@code [0.0, 1.0]}. The list may be empty (route with no segments), which exercises the
     * Unknown branch of the scorer.
     */
    @Provide
    Arbitrary<List<RiskObservation>> observationsOverRoute() {
        Arbitrary<List<RouteSegment>> segmentLists = segment().list().ofMinSize(0).ofMaxSize(8);
        return segmentLists.flatMap(segments -> {
            Route route = new Route(
                "route",
                0,
                segments,
                segments.stream().mapToDouble(RouteSegment::distanceMeters).sum());
            return observationsFor(route);
        });
    }

    /**
     * Builds an arbitrary list of {@link RiskObservation}, one per segment of {@code route},
     * each with an independent bounded intensity in {@code [0.0, 1.0]}.
     */
    private static Arbitrary<List<RiskObservation>> observationsFor(Route route) {
        List<RouteSegment> segments = route.segments();
        if (segments.isEmpty()) {
            return Arbitraries.just(List.of());
        }
        Arbitrary<List<Double>> intensities =
            Arbitraries.doubles().between(0.0, 1.0).list()
                .ofSize(segments.size());
        return intensities.map(values -> {
            List<RiskObservation> observations = new ArrayList<>(segments.size());
            for (int i = 0; i < segments.size(); i++) {
                observations.add(new RiskObservation(segments.get(i), values.get(i), 1));
            }
            return observations;
        });
    }

    private Arbitrary<RouteSegment> segment() {
        Arbitrary<Double> distance = Arbitraries.doubles().between(0.0, 50_000.0);
        Arbitrary<GeoCoordinate> point =
            Combinators.combine(
                    Arbitraries.doubles().between(25.30, 25.85),
                    Arbitraries.doubles().between(-80.75, -80.30))
                .as(GeoCoordinate::new);
        return Combinators.combine(distance, point, point)
            .as((d, start, end) -> new RouteSegment("segment", start, end, d));
    }
}
