package com.routeriskadvisor.domain;

import com.routeriskadvisor.domain.model.CategoryRecommendation;
import com.routeriskadvisor.domain.model.RiskAssessment;
import com.routeriskadvisor.domain.model.RiskCategory;
import com.routeriskadvisor.domain.model.RiskLevel;
import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteClassification;
import com.routeriskadvisor.domain.model.SafestRouteResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DefaultRouteFinder} per-category minimum selection and tie-breaking
 * (Req 4.3, 4.4, 4.5, 4.7).
 */
class DefaultRouteFinderTest {

    /**
     * A test classifier that returns pre-registered assessments per route id. Records how many
     * times it was invoked so we can assert "once per candidate" (Req 4.2).
     */
    private static final class StubClassifier implements RouteClassifier {
        private final Map<String, List<RiskAssessment>> byRouteId = new HashMap<>();
        int classifyCalls = 0;

        void register(String routeId, List<RiskAssessment> assessments) {
            byRouteId.put(routeId, assessments);
        }

        @Override
        public RouteClassification classify(Route route) {
            classifyCalls++;
            List<RiskAssessment> assessments = byRouteId.get(route.id());
            if (assessments == null) {
                throw new IllegalStateException("no assessments registered for " + route.id());
            }
            return new RouteClassification(route, assessments);
        }
    }

    private static Route route(String id, int providerIndex, double totalDistanceMeters) {
        return new Route(id, providerIndex, List.of(), totalDistanceMeters);
    }

    private static RiskAssessment scored(RiskCategory category, int score) {
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

    private static RiskAssessment unknown(RiskCategory category) {
        return new RiskAssessment(category, null, RiskLevel.UNKNOWN);
    }

    private static List<RiskAssessment> all(int accident, int theft, int fire) {
        return List.of(
            scored(RiskCategory.ACCIDENT, accident),
            scored(RiskCategory.THEFT, theft),
            scored(RiskCategory.FIRE, fire));
    }

    private static CategoryRecommendation recFor(SafestRouteResult result, RiskCategory category) {
        return result.perCategory().stream()
            .filter(r -> r.category() == category)
            .findFirst()
            .orElseThrow();
    }

    @Test
    void selectsLowestScorePerCategory() {
        StubClassifier classifier = new StubClassifier();
        Route a = route("a", 0, 1000);
        Route b = route("b", 1, 1000);
        Route c = route("c", 2, 1000);
        classifier.register("a", all(70, 10, 40));
        classifier.register("b", all(20, 80, 40));
        classifier.register("c", all(50, 30, 5));

        SafestRouteResult result = new DefaultRouteFinder(classifier).findSafest(List.of(a, b, c));

        assertSame(b, recFor(result, RiskCategory.ACCIDENT).recommendedRoute());
        assertEquals(20, recFor(result, RiskCategory.ACCIDENT).score());
        assertSame(a, recFor(result, RiskCategory.THEFT).recommendedRoute());
        assertEquals(10, recFor(result, RiskCategory.THEFT).score());
        assertSame(c, recFor(result, RiskCategory.FIRE).recommendedRoute());
        assertEquals(5, recFor(result, RiskCategory.FIRE).score());
    }

    @Test
    void reportsExactlyOneRecommendationPerEvaluatedCategory() {
        StubClassifier classifier = new StubClassifier();
        Route a = route("a", 0, 1000);
        classifier.register("a", all(10, 20, 30));

        SafestRouteResult result = new DefaultRouteFinder(classifier).findSafest(List.of(a));

        assertEquals(3, result.perCategory().size());
        assertTrue(result.perCategory().stream()
            .map(CategoryRecommendation::category).toList()
            .containsAll(List.of(RiskCategory.ACCIDENT, RiskCategory.THEFT, RiskCategory.FIRE)));
    }

    @Test
    void tieOnScoreBreaksByShorterDistance() {
        StubClassifier classifier = new StubClassifier();
        Route longer = route("longer", 0, 5000);
        Route shorter = route("shorter", 1, 3000);
        classifier.register("longer", all(40, 40, 40));
        classifier.register("shorter", all(40, 40, 40));

        SafestRouteResult result =
            new DefaultRouteFinder(classifier).findSafest(List.of(longer, shorter));

        // Same score → shorter distance wins for every category (Req 4.7).
        for (RiskCategory category : List.of(
                RiskCategory.ACCIDENT, RiskCategory.THEFT, RiskCategory.FIRE)) {
            assertSame(shorter, recFor(result, category).recommendedRoute());
        }
    }

    @Test
    void tieOnScoreAndDistanceBreaksBySmallestProviderIndex() {
        StubClassifier classifier = new StubClassifier();
        Route later = route("later", 5, 2000);
        Route earlier = route("earlier", 2, 2000);
        classifier.register("later", all(50, 50, 50));
        classifier.register("earlier", all(50, 50, 50));

        // Pass "later" first to prove ordering is by provider index, not list position (Req 4.7).
        SafestRouteResult result =
            new DefaultRouteFinder(classifier).findSafest(List.of(later, earlier));

        for (RiskCategory category : List.of(
                RiskCategory.ACCIDENT, RiskCategory.THEFT, RiskCategory.FIRE)) {
            assertSame(earlier, recFor(result, category).recommendedRoute());
        }
    }

    @Test
    void skipsUnknownCandidatesWhenSelecting() {
        StubClassifier classifier = new StubClassifier();
        Route a = route("a", 0, 1000);
        Route b = route("b", 1, 1000);
        // a is Unknown for ACCIDENT but has the lower value elsewhere; it must be skipped for
        // ACCIDENT even though its (would-be) position is first.
        classifier.register("a", List.of(
            unknown(RiskCategory.ACCIDENT),
            scored(RiskCategory.THEFT, 10),
            scored(RiskCategory.FIRE, 10)));
        classifier.register("b", all(90, 90, 90));

        SafestRouteResult result = new DefaultRouteFinder(classifier).findSafest(List.of(a, b));

        // Only b is scored for ACCIDENT.
        assertSame(b, recFor(result, RiskCategory.ACCIDENT).recommendedRoute());
        assertEquals(90, recFor(result, RiskCategory.ACCIDENT).score());
        // a wins THEFT and FIRE.
        assertSame(a, recFor(result, RiskCategory.THEFT).recommendedRoute());
        assertSame(a, recFor(result, RiskCategory.FIRE).recommendedRoute());
    }

    @Test
    void allUnknownForCategoryReportsUnknownRecommendation() {
        StubClassifier classifier = new StubClassifier();
        Route a = route("a", 0, 1000);
        Route b = route("b", 1, 1000);
        classifier.register("a", List.of(
            unknown(RiskCategory.ACCIDENT),
            scored(RiskCategory.THEFT, 10),
            scored(RiskCategory.FIRE, 10)));
        classifier.register("b", List.of(
            unknown(RiskCategory.ACCIDENT),
            scored(RiskCategory.THEFT, 20),
            scored(RiskCategory.FIRE, 20)));

        SafestRouteResult result = new DefaultRouteFinder(classifier).findSafest(List.of(a, b));

        CategoryRecommendation accident = recFor(result, RiskCategory.ACCIDENT);
        assertNull(accident.recommendedRoute());
        assertNull(accident.score());
        assertEquals(RiskLevel.UNKNOWN, accident.level());
    }

    @Test
    void singleCandidateRecommendedForEveryScoredCategory() {
        StubClassifier classifier = new StubClassifier();
        Route only = route("only", 0, 4200);
        classifier.register("only", all(15, 45, 75));

        SafestRouteResult result = new DefaultRouteFinder(classifier).findSafest(List.of(only));

        for (RiskCategory category : List.of(
                RiskCategory.ACCIDENT, RiskCategory.THEFT, RiskCategory.FIRE)) {
            assertSame(only, recFor(result, category).recommendedRoute());
        }
        assertEquals(15, recFor(result, RiskCategory.ACCIDENT).score());
        assertEquals(45, recFor(result, RiskCategory.THEFT).score());
        assertEquals(75, recFor(result, RiskCategory.FIRE).score());
    }

    @Test
    void classifiesEachCandidateExactlyOnce() {
        StubClassifier classifier = new StubClassifier();
        List<Route> routes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String id = "r" + i;
            routes.add(route(id, i, 1000 + i));
            classifier.register(id, all(10 + i, 20 + i, 30 + i));
        }

        new DefaultRouteFinder(classifier).findSafest(routes);

        assertEquals(4, classifier.classifyCalls);
    }

    @Test
    void rejectsNullOrEmptyInput() {
        DefaultRouteFinder finder = new DefaultRouteFinder(new StubClassifier());
        assertThrows(IllegalArgumentException.class, () -> finder.findSafest(null));
        assertThrows(IllegalArgumentException.class, () -> finder.findSafest(List.of()));
    }

    @Test
    void rejectsNullClassifier() {
        assertThrows(IllegalArgumentException.class, () -> new DefaultRouteFinder(null));
    }
}
