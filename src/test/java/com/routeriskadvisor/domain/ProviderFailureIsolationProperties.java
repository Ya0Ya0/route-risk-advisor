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
import com.routeriskadvisor.provider.ProviderException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for {@link DefaultRouteClassifier} graceful degradation.
 *
 * <p>Implements design correctness Property 8: a provider that fails or times out (throws a
 * {@link ProviderException}) degrades only its own category to Unknown while every non-failing
 * category still produces a scored assessment. The classifier never propagates a provider
 * failure to the caller.
 *
 * <p>The failing subset is drawn over the crash/crime/fire providers, mapped to their
 * categories {@code {ACCIDENT, THEFT, FIRE}}. Providers in the failing subset throw a
 * {@link ProviderException} (a timeout or a failure response); providers not in the subset
 * return a valid, present observation for every segment so their category is guaranteed to be
 * scored rather than Unknown-through-missing-data.
 *
 * <p><strong>Validates: Requirements 2.9</strong>
 */
class ProviderFailureIsolationProperties {

    private final RiskScorer riskScorer = new RiskScorer();

    // Feature: route-risk-advisor, Property 8: Provider failure is isolated to its category
    @Property(tries = 200)
    void failingProvidersDegradeOnlyTheirOwnCategory(
            @ForAll("failingSubsets") Set<RiskCategory> failing,
            @ForAll("routes") Route route,
            @ForAll("failureKinds") ProviderException.Kind kind) {

        CrashDataProvider crash = segment ->
                observe(RiskCategory.ACCIDENT, failing, kind, segment);
        CrimeDataProvider crime = segment ->
                observe(RiskCategory.THEFT, failing, kind, segment);
        FireDataProvider fire = segment ->
                observe(RiskCategory.FIRE, failing, kind, segment);

        DefaultRouteClassifier classifier =
                new DefaultRouteClassifier(crash, crime, fire, riskScorer);

        // A failure in any category must never propagate to the caller (Req 2.9).
        RouteClassification classification = classifier.classify(route);

        // Exactly one assessment per ACCIDENT, THEFT, FIRE is always returned.
        assertThat(classification.assessments())
                .as("classification always carries one assessment per scored category")
                .hasSize(3)
                .extracting(RiskAssessment::category)
                .containsExactly(RiskCategory.ACCIDENT, RiskCategory.THEFT, RiskCategory.FIRE);

        for (RiskAssessment assessment : classification.assessments()) {
            if (failing.contains(assessment.category())) {
                // A failing/timing-out provider degrades its category to Unknown with no score.
                assertThat(assessment.level())
                        .as("failing category %s must be Unknown", assessment.category())
                        .isEqualTo(RiskLevel.UNKNOWN);
                assertThat(assessment.score())
                        .as("Unknown category %s must have no score", assessment.category())
                        .isNull();
            } else {
                // A non-failing category still produces a bounded, banded score (isolation).
                assertThat(assessment.level())
                        .as("non-failing category %s must be scored, not Unknown", assessment.category())
                        .isNotEqualTo(RiskLevel.UNKNOWN);
                assertThat(assessment.score())
                        .as("scored category %s must carry a bounded score", assessment.category())
                        .isNotNull()
                        .isBetween(0, 100);
            }
        }
    }

    /**
     * Returns a present observation for a segment, unless this category's provider is in the
     * failing subset, in which case it throws a {@link ProviderException} of the drawn kind
     * (timeout or failure response). Returning a present observation for every segment of a
     * non-failing category guarantees that category is scored rather than Unknown for lack of
     * data, so the assertion isolates the effect of failure alone.
     */
    private static Optional<RiskObservation> observe(
            RiskCategory category,
            Set<RiskCategory> failing,
            ProviderException.Kind kind,
            RouteSegment segment) throws ProviderException {
        if (failing.contains(category)) {
            throw new ProviderException(category + " provider unavailable", kind);
        }
        // Deterministic non-zero intensity so the scored level is well-defined.
        return Optional.of(new RiskObservation(segment, 0.5, 3));
    }

    /**
     * Every non-empty and empty subset of the crash/crime/fire categories. The empty subset means
     * no provider fails (all categories scored); the full subset means all fail (all Unknown).
     */
    @Provide
    Arbitrary<Set<RiskCategory>> failingSubsets() {
        return Arbitraries.subsetOf(RiskCategory.ACCIDENT, RiskCategory.THEFT, RiskCategory.FIRE)
                .map(subset -> subset.isEmpty()
                        ? EnumSet.noneOf(RiskCategory.class)
                        : EnumSet.copyOf(subset));
    }

    @Provide
    Arbitrary<ProviderException.Kind> failureKinds() {
        return Arbitraries.of(ProviderException.Kind.TIMEOUT, ProviderException.Kind.FAILURE);
    }

    /**
     * Routes with at least one segment (so a non-failing category has data to score) and up to a
     * handful of segments, each with a positive distance and in-area coordinates.
     */
    @Provide
    Arbitrary<Route> routes() {
        Arbitrary<Integer> segmentCount = Arbitraries.integers().between(1, 6);
        return segmentCount.flatMap(count -> {
            Arbitrary<List<RouteSegment>> segmentList = segment().list().ofSize(count);
            return segmentList.map(segments -> {
                double total = segments.stream().mapToDouble(RouteSegment::distanceMeters).sum();
                return new Route("route", 0, new ArrayList<>(segments), total);
            });
        });
    }

    private Arbitrary<RouteSegment> segment() {
        Arbitrary<Double> lat = Arbitraries.doubles().between(25.30, 25.85);
        Arbitrary<Double> lon = Arbitraries.doubles().between(-80.75, -80.30);
        Arbitrary<GeoCoordinate> start =
                Combinators.combine(lat, lon).as(GeoCoordinate::new);
        Arbitrary<GeoCoordinate> end =
                Combinators.combine(lat, lon).as(GeoCoordinate::new);
        Arbitrary<Double> distance = Arbitraries.doubles().between(1.0, 5000.0);
        return Combinators.combine(start, end, distance)
                .as((s, e, d) -> new RouteSegment("segment", s, e, d));
    }
}
