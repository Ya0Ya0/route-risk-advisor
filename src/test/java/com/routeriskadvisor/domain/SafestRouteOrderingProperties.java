package com.routeriskadvisor.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.routeriskadvisor.domain.model.CategoryRecommendation;
import com.routeriskadvisor.domain.model.RiskAssessment;
import com.routeriskadvisor.domain.model.RiskCategory;
import com.routeriskadvisor.domain.model.RiskLevel;
import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteClassification;
import com.routeriskadvisor.domain.model.SafestRouteResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for {@link DefaultRouteFinder} safest-route selection and tie-breaking.
 *
 * <p>Implements design correctness Property 14: for any set of candidate routes with computed
 * risk scores, the route recommended for a category is the minimum under the lexicographic
 * ordering by {@code (Risk_Score, total distance, provider index)}. As a direct consequence,
 * when only one candidate exists it is recommended for every (scored) category.
 *
 * <p>To control scores, distances, and provider indices precisely — and to deliberately force
 * collisions that exercise every tie-break level — the finder is driven by a stub
 * {@link RouteClassifier} that returns generated per-route assessments. This isolates the
 * selection logic from the real scoring pipeline.
 *
 * <p><strong>Validates: Requirements 4.3, 4.4, 4.5, 4.7</strong>
 */
class SafestRouteOrderingProperties {

    /** Categories the finder evaluates and reports a recommendation for. */
    private static final List<RiskCategory> EVALUATED =
        List.of(RiskCategory.ACCIDENT, RiskCategory.THEFT, RiskCategory.FIRE);

    /**
     * A classifier that returns pre-registered assessments per route id, so the property fully
     * controls each candidate's score, distance, and provider index.
     */
    private static final class StubClassifier implements RouteClassifier {
        private final Map<String, List<RiskAssessment>> byRouteId = new HashMap<>();

        void register(String routeId, List<RiskAssessment> assessments) {
            byRouteId.put(routeId, assessments);
        }

        @Override
        public RouteClassification classify(Route route) {
            List<RiskAssessment> assessments = byRouteId.get(route.id());
            if (assessments == null) {
                throw new IllegalStateException("no assessments registered for " + route.id());
            }
            return new RouteClassification(route, assessments);
        }
    }

    /**
     * A generated candidate: a route (with distinct id, controllable provider index and total
     * distance) together with its per-category scores.
     */
    private record GeneratedCandidate(
        int providerIndex,
        double totalDistanceMeters,
        int accidentScore,
        int theftScore,
        int fireScore) {

        Route toRoute() {
            // Route id is derived from the provider index, which is unique within a candidate set.
            return new Route("r" + providerIndex, providerIndex, List.of(), totalDistanceMeters);
        }

        int scoreFor(RiskCategory category) {
            return switch (category) {
                case ACCIDENT -> accidentScore;
                case THEFT -> theftScore;
                case FIRE -> fireScore;
                default -> throw new IllegalArgumentException("unexpected category " + category);
            };
        }

        List<RiskAssessment> assessments() {
            return List.of(
                assessment(RiskCategory.ACCIDENT, accidentScore),
                assessment(RiskCategory.THEFT, theftScore),
                assessment(RiskCategory.FIRE, fireScore));
        }

        private static RiskAssessment assessment(RiskCategory category, int score) {
            RiskLevel level;
            if (score <= 33) {
                level = RiskLevel.LOW;
            } else if (score <= 66) {
                level = RiskLevel.MEDIUM;
            } else {
                level = RiskLevel.HIGH;
            }
            return new RiskAssessment(category, score, level);
        }
    }

    // Feature: route-risk-advisor, Property 14: Safest route is the minimum under the tie-break order
    @Property(tries = 200)
    void recommendedRouteIsLexicographicMinimumPerCategory(
            @ForAll("candidateSets") List<GeneratedCandidate> candidates) {
        // Provider indices are unique per set, so ids are unique — build the finder input.
        StubClassifier classifier = new StubClassifier();
        List<Route> routes = new ArrayList<>(candidates.size());
        Map<Integer, GeneratedCandidate> byIndex = new HashMap<>();
        for (GeneratedCandidate candidate : candidates) {
            Route route = candidate.toRoute();
            classifier.register(route.id(), candidate.assessments());
            routes.add(route);
            byIndex.put(candidate.providerIndex(), candidate);
        }
        // Shuffle-independence: pass candidates in arbitrary order; selection must not depend on
        // list position, only on the tie-break keys.

        SafestRouteResult result = new DefaultRouteFinder(classifier).findSafest(routes);

        for (RiskCategory category : EVALUATED) {
            GeneratedCandidate expected = candidates.stream()
                .min(lexicographicOrder(category))
                .orElseThrow();

            CategoryRecommendation actual = recFor(result, category);
            assertThat(actual.recommendedRoute()).as("recommended route for %s", category).isNotNull();
            assertThat(actual.recommendedRoute().providerIndex())
                .as("recommended provider index for %s (candidates=%s)", category, candidates)
                .isEqualTo(expected.providerIndex());
            assertThat(actual.score())
                .as("recommended score for %s", category)
                .isEqualTo(expected.scoreFor(category));
        }
    }

    // Feature: route-risk-advisor, Property 14: Safest route is the minimum under the tie-break order
    @Property(tries = 200)
    void singleCandidateRecommendedForEveryCategory(@ForAll("oneCandidate") GeneratedCandidate only) {
        StubClassifier classifier = new StubClassifier();
        Route route = only.toRoute();
        classifier.register(route.id(), only.assessments());

        SafestRouteResult result = new DefaultRouteFinder(classifier).findSafest(List.of(route));

        for (RiskCategory category : EVALUATED) {
            CategoryRecommendation rec = recFor(result, category);
            assertThat(rec.recommendedRoute())
                .as("single candidate must be recommended for %s", category)
                .isSameAs(route);
            assertThat(rec.score())
                .as("single-candidate score for %s", category)
                .isEqualTo(only.scoreFor(category));
        }
    }

    /** The lexicographic tie-break order under test: score, then total distance, then index. */
    private static Comparator<GeneratedCandidate> lexicographicOrder(RiskCategory category) {
        return Comparator.<GeneratedCandidate>comparingInt(c -> c.scoreFor(category))
            .thenComparingDouble(GeneratedCandidate::totalDistanceMeters)
            .thenComparingInt(GeneratedCandidate::providerIndex);
    }

    private static CategoryRecommendation recFor(SafestRouteResult result, RiskCategory category) {
        return result.perCategory().stream()
            .filter(r -> r.category() == category)
            .findFirst()
            .orElseThrow();
    }

    /**
     * Generates non-empty candidate sets with unique provider indices and deliberately colliding
     * scores and distances so every level of the tie-break order is exercised.
     *
     * <p>Scores are drawn from a small pool (0, 10, 20) and distances from a small pool (1000,
     * 2000) so that ties on score — and ties on both score and distance — occur frequently across
     * the 200 generated cases, forcing the finder to fall through to the distance and then the
     * provider-index tie-breaks. Provider indices are assigned uniquely per candidate.
     */
    @Provide
    Arbitrary<List<GeneratedCandidate>> candidateSets() {
        Arbitrary<Integer> collidingScore = Arbitraries.of(0, 10, 20);
        Arbitrary<Double> collidingDistance = Arbitraries.of(1000.0, 2000.0);

        return Arbitraries.integers().between(1, 6).flatMap(size -> {
            List<Arbitrary<GeneratedCandidate>> perCandidate = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                int providerIndex = i;
                Arbitrary<GeneratedCandidate> candidate = Combinators.combine(
                        collidingDistance,
                        collidingScore,
                        collidingScore,
                        collidingScore)
                    .as((distance, accident, theft, fire) ->
                        new GeneratedCandidate(providerIndex, distance, accident, theft, fire));
                perCandidate.add(candidate);
            }
            return combineAll(perCandidate);
        });
    }

    /** Generates a single candidate with an arbitrary bounded score/distance for each category. */
    @Provide
    Arbitrary<GeneratedCandidate> oneCandidate() {
        Arbitrary<Double> distance = Arbitraries.doubles().between(0.0, 100_000.0);
        Arbitrary<Integer> score = Arbitraries.integers().between(0, 100);
        return Combinators.combine(distance, score, score, score)
            .as((d, accident, theft, fire) -> new GeneratedCandidate(0, d, accident, theft, fire));
    }

    /** Collapses a list of candidate arbitraries into an arbitrary list of candidates. */
    private static Arbitrary<List<GeneratedCandidate>> combineAll(
            List<Arbitrary<GeneratedCandidate>> arbitraries) {
        Arbitrary<List<GeneratedCandidate>> combined = Arbitraries.just(new ArrayList<>());
        for (Arbitrary<GeneratedCandidate> arbitrary : arbitraries) {
            combined = Combinators.combine(combined, arbitrary).as((list, candidate) -> {
                List<GeneratedCandidate> next = new ArrayList<>(list);
                next.add(candidate);
                return next;
            });
        }
        return combined;
    }
}
