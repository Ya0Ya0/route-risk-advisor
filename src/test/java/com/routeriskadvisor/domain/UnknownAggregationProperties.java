package com.routeriskadvisor.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.domain.model.RiskAssessment;
import com.routeriskadvisor.domain.model.RiskCategory;
import com.routeriskadvisor.domain.model.RiskLevel;
import com.routeriskadvisor.domain.model.RiskObservation;
import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteClassification;
import com.routeriskadvisor.domain.model.RouteSegment;
import com.routeriskadvisor.provider.CrashDataProvider;
import com.routeriskadvisor.provider.CrimeDataProvider;
import com.routeriskadvisor.provider.FireDataProvider;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for {@link DefaultRouteClassifier} Unknown aggregation and category
 * completeness.
 *
 * <p>Implements design correctness Property 7: for any resolved {@link Route}, the classification
 * returns an assessment for each of the Accident, Theft, and Fire categories, and a category is
 * reported as {@link RiskLevel#UNKNOWN} with no score if and only if every {@link RouteSegment}
 * lacked data for that category; otherwise the category carries a bounded numeric score in
 * {@code [0, 100]} computed only from the segments that returned data.
 *
 * <p>The three crash/crime/fire providers are driven by per-segment, per-category "has data"
 * flags: a segment flagged as having data returns a {@link RiskObservation}, and a segment
 * flagged as lacking data returns {@link Optional#empty()}. No provider fails or times out in
 * this property (that path belongs to Property 8), so a category is Unknown exactly when all of
 * its segments were flagged as lacking data.
 *
 * <p><strong>Validates: Requirements 2.6, 2.7, 2.8</strong>
 */
class UnknownAggregationProperties {

    private final RiskScorer scorer = new RiskScorer();

    // Feature: route-risk-advisor, Property 7: Unknown aggregation and category completeness
    @Property(tries = 200)
    void unknownIffEverySegmentLacksDataOtherwiseBoundedScore(@ForAll("routeWithDataFlags") RouteWithFlags input) {
        Route route = input.route();

        FlaggedProvider crash = new FlaggedProvider(input.hasData(RiskCategory.ACCIDENT));
        FlaggedProvider crime = new FlaggedProvider(input.hasData(RiskCategory.THEFT));
        FlaggedProvider fire = new FlaggedProvider(input.hasData(RiskCategory.FIRE));

        DefaultRouteClassifier classifier = new DefaultRouteClassifier(
            crash::observe, crime::observe, fire::observe, scorer);

        RouteClassification classification = classifier.classify(route);

        // An assessment for each of ACCIDENT, THEFT, FIRE (Req 2.6).
        List<RiskAssessment> assessments = classification.assessments();
        assertThat(assessments)
            .as("classification must contain exactly one assessment per Accident/Theft/Fire")
            .hasSize(3)
            .extracting(RiskAssessment::category)
            .containsExactly(RiskCategory.ACCIDENT, RiskCategory.THEFT, RiskCategory.FIRE);

        for (RiskAssessment assessment : assessments) {
            RiskCategory category = assessment.category();
            boolean anySegmentHasData = input.hasData(category).stream().anyMatch(Boolean::booleanValue);

            if (!anySegmentHasData) {
                // Every segment lacked data for this category -> Unknown with no score
                // (Req 2.7, 2.8). Also covers the empty-segment route (nothing has data).
                assertThat(assessment.level())
                    .as("category %s must be Unknown when every segment lacked data", category)
                    .isEqualTo(RiskLevel.UNKNOWN);
                assertThat(assessment.score())
                    .as("Unknown category %s must carry no score", category)
                    .isNull();
            } else {
                // At least one segment returned data -> bounded numeric score, not Unknown
                // (Req 2.7, 2.8).
                assertThat(assessment.level())
                    .as("category %s must be scored when at least one segment had data", category)
                    .isNotEqualTo(RiskLevel.UNKNOWN);
                assertThat(assessment.score())
                    .as("scored category %s must carry a non-null score", category)
                    .isNotNull();
                assertThat(assessment.score())
                    .as("Risk_Score for %s must be an integer in [0, 100]", category)
                    .isBetween(0, 100);

                // The score is computed only from segments that returned data: it must equal the
                // scorer applied to exactly the present observations for this category.
                RiskAssessment expected = scorer.score(category, presentObservations(route, input.hasData(category)));
                assertThat(assessment.score())
                    .as("score for %s must be computed only from segments that returned data", category)
                    .isEqualTo(expected.score());
            }
        }
    }

    /**
     * Rebuilds the observation list the scorer should have seen for a category: one observation
     * per segment flagged as having data, using the same deterministic intensity the test-double
     * provider emits, and skipping segments flagged as lacking data.
     */
    private static List<RiskObservation> presentObservations(Route route, List<Boolean> hasData) {
        List<RouteSegment> segments = route.segments();
        List<RiskObservation> present = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            if (hasData.get(i)) {
                present.add(FlaggedProvider.observationFor(segments.get(i)));
            }
        }
        return present;
    }

    /**
     * A route paired with, for each category, a per-segment list of "has data" flags. The flag
     * list for each category has exactly one entry per route segment.
     */
    record RouteWithFlags(Route route, Map<RiskCategory, List<Boolean>> flags) {
        List<Boolean> hasData(RiskCategory category) {
            return flags.get(category);
        }
    }

    /**
     * A test-double data provider: for a segment at index {@code i} it returns a deterministic
     * {@link RiskObservation} when {@code hasData.get(i)} is true, and {@link Optional#empty()}
     * otherwise. It never throws, so Unknown arises only from missing data (not provider
     * failure).
     */
    private static final class FlaggedProvider {
        private final List<Boolean> hasData;
        private int index = 0;

        FlaggedProvider(List<Boolean> hasData) {
            this.hasData = hasData;
        }

        Optional<RiskObservation> observe(RouteSegment segment) {
            boolean present = index < hasData.size() && hasData.get(index);
            index++;
            return present ? Optional.of(observationFor(segment)) : Optional.empty();
        }

        /** Deterministic observation for a segment so expected/actual scores line up. */
        static RiskObservation observationFor(RouteSegment segment) {
            // A stable intensity derived from the segment distance, kept within [0.0, 1.0].
            double intensity = Math.abs(Math.sin(segment.distanceMeters()));
            return new RiskObservation(segment, intensity, 1);
        }
    }

    @Provide
    Arbitrary<RouteWithFlags> routeWithDataFlags() {
        Arbitrary<List<RouteSegment>> segmentLists = segment().list().ofMinSize(0).ofMaxSize(8);
        return segmentLists.flatMap(segments -> {
            Route route = new Route(
                "route",
                0,
                segments,
                segments.stream().mapToDouble(RouteSegment::distanceMeters).sum());
            return flagsFor(segments.size()).map(flags -> new RouteWithFlags(route, flags));
        });
    }

    /**
     * Generates independent per-segment "has data" flag lists for each of the three categories,
     * each list sized to the segment count. A zero-segment route yields empty flag lists, which
     * drives every category to Unknown.
     */
    private static Arbitrary<Map<RiskCategory, List<Boolean>>> flagsFor(int segmentCount) {
        Arbitrary<List<Boolean>> flagList = Arbitraries.of(true, false).list().ofSize(segmentCount);
        return Combinators.combine(flagList, flagList, flagList).as((accident, theft, fire) -> {
            Map<RiskCategory, List<Boolean>> flags = new EnumMap<>(RiskCategory.class);
            flags.put(RiskCategory.ACCIDENT, accident);
            flags.put(RiskCategory.THEFT, theft);
            flags.put(RiskCategory.FIRE, fire);
            return flags;
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
