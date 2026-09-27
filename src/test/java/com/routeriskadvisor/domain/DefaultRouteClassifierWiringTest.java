package com.routeriskadvisor.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit (example) tests for {@link DefaultRouteClassifier} provider wiring and score-to-level
 * banding boundaries.
 *
 * <p>Covers Requirements 2.1, 2.2, 2.3 (each category is drawn from its own provider) and 2.5
 * (score-to-level banding: 0-33 LOW, 34-66 MEDIUM, 67-100 HIGH) using recording test-double
 * providers rather than mocking frameworks. Each double records which segments it was asked
 * about, so the test can assert that ACCIDENT comes from the crash provider, THEFT from the
 * crime provider, and FIRE from the fire provider — and never the other way around.
 */
class DefaultRouteClassifierWiringTest {

    /**
     * A recording data provider double. It records every segment it is queried for and returns a
     * fixed {@code normalizedIntensity} for each so the resulting score is deterministic.
     */
    private static final class RecordingProvider
        implements CrashDataProvider, CrimeDataProvider, FireDataProvider {

        private final RiskCategory category;
        private final double intensity;
        private final List<RouteSegment> queriedSegments = new ArrayList<>();

        RecordingProvider(RiskCategory category, double intensity) {
            this.category = category;
            this.intensity = intensity;
        }

        private Optional<RiskObservation> observe(RouteSegment segment) {
            queriedSegments.add(segment);
            return Optional.of(new RiskObservation(segment, intensity, 1));
        }

        @Override
        public Optional<RiskObservation> crashData(RouteSegment segment) throws ProviderException {
            return observe(segment);
        }

        @Override
        public Optional<RiskObservation> theftData(RouteSegment segment) throws ProviderException {
            return observe(segment);
        }

        @Override
        public Optional<RiskObservation> fireData(RouteSegment segment) throws ProviderException {
            return observe(segment);
        }

        RiskCategory category() {
            return category;
        }

        List<RouteSegment> queriedSegments() {
            return queriedSegments;
        }
    }

    /** Builds a single-segment route with the given distance so an exact score can be driven. */
    private static Route singleSegmentRoute(double distanceMeters) {
        RouteSegment segment = new RouteSegment(
            "seg-1",
            new GeoCoordinate(25.7617, -80.1918),
            new GeoCoordinate(25.7907, -80.1300),
            distanceMeters
        );
        return new Route("route-1", 0, List.of(segment), distanceMeters);
    }

    private static RiskAssessment assessmentFor(RouteClassification classification, RiskCategory category) {
        return classification.assessments().stream()
            .filter(a -> a.category() == category)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no assessment for category " + category));
    }

    @Nested
    @DisplayName("each category is drawn from its own provider (Req 2.1, 2.2, 2.3)")
    class ProviderWiring {

        @Test
        @DisplayName("Accident from crash, Theft from crime, Fire from fire — each queried exactly once")
        void categoriesDrawnFromCorrectProviders() {
            // Distinct intensities per provider so a mis-wiring (e.g. Accident reading the fire
            // provider) would surface as the wrong score for that category.
            RecordingProvider crash = new RecordingProvider(RiskCategory.ACCIDENT, 0.10); // -> 10 LOW
            RecordingProvider crime = new RecordingProvider(RiskCategory.THEFT, 0.50);    // -> 50 MEDIUM
            RecordingProvider fire = new RecordingProvider(RiskCategory.FIRE, 0.90);      // -> 90 HIGH

            DefaultRouteClassifier classifier =
                new DefaultRouteClassifier(crash, crime, fire, new RiskScorer());

            Route route = singleSegmentRoute(1000.0);
            RouteSegment onlySegment = route.segments().get(0);

            RouteClassification classification = classifier.classify(route);

            // Each provider was consulted for exactly the route's segment.
            assertEquals(List.of(onlySegment), crash.queriedSegments(),
                "crash provider should be queried for the Accident category");
            assertEquals(List.of(onlySegment), crime.queriedSegments(),
                "crime provider should be queried for the Theft category");
            assertEquals(List.of(onlySegment), fire.queriedSegments(),
                "fire provider should be queried for the Fire category");

            // The score for each category reflects the intensity of ITS provider, proving the
            // Accident<-crash, Theft<-crime, Fire<-fire wiring.
            assertEquals(10, assessmentFor(classification, RiskCategory.ACCIDENT).score(),
                "Accident score must come from the crash provider's intensity");
            assertEquals(50, assessmentFor(classification, RiskCategory.THEFT).score(),
                "Theft score must come from the crime provider's intensity");
            assertEquals(90, assessmentFor(classification, RiskCategory.FIRE).score(),
                "Fire score must come from the fire provider's intensity");
        }

        @Test
        @DisplayName("classification always carries exactly one assessment per Accident/Theft/Fire")
        void oneAssessmentPerCategory() {
            RecordingProvider crash = new RecordingProvider(RiskCategory.ACCIDENT, 0.2);
            RecordingProvider crime = new RecordingProvider(RiskCategory.THEFT, 0.2);
            RecordingProvider fire = new RecordingProvider(RiskCategory.FIRE, 0.2);

            DefaultRouteClassifier classifier =
                new DefaultRouteClassifier(crash, crime, fire, new RiskScorer());

            RouteClassification classification = classifier.classify(singleSegmentRoute(500.0));

            assertEquals(3, classification.assessments().size());
            assertNotNull(assessmentFor(classification, RiskCategory.ACCIDENT));
            assertNotNull(assessmentFor(classification, RiskCategory.THEFT));
            assertNotNull(assessmentFor(classification, RiskCategory.FIRE));
        }
    }

    @Nested
    @DisplayName("score-to-level banding boundaries through the classifier (Req 2.5)")
    class BandingBoundaries {

        /**
         * Drives an exact integer score through the classifier by using a single segment whose
         * provider reports {@code normalizedIntensity = score / 100.0}; the scorer computes
         * {@code round(intensity * 100) == score}.
         */
        private RiskLevel levelForScore(int score) {
            double intensity = score / 100.0;
            RecordingProvider crash = new RecordingProvider(RiskCategory.ACCIDENT, intensity);
            RecordingProvider crime = new RecordingProvider(RiskCategory.THEFT, intensity);
            RecordingProvider fire = new RecordingProvider(RiskCategory.FIRE, intensity);

            DefaultRouteClassifier classifier =
                new DefaultRouteClassifier(crash, crime, fire, new RiskScorer());

            RouteClassification classification = classifier.classify(singleSegmentRoute(1000.0));
            RiskAssessment accident = assessmentFor(classification, RiskCategory.ACCIDENT);
            assertEquals(score, accident.score(), "expected the driven score to round-trip exactly");
            return accident.level();
        }

        @Test
        @DisplayName("score 33 bands to LOW (upper edge of Low)")
        void score33IsLow() {
            assertEquals(RiskLevel.LOW, levelForScore(33));
        }

        @Test
        @DisplayName("score 34 bands to MEDIUM (lower edge of Medium)")
        void score34IsMedium() {
            assertEquals(RiskLevel.MEDIUM, levelForScore(34));
        }

        @Test
        @DisplayName("score 66 bands to MEDIUM (upper edge of Medium)")
        void score66IsMedium() {
            assertEquals(RiskLevel.MEDIUM, levelForScore(66));
        }

        @Test
        @DisplayName("score 67 bands to HIGH (lower edge of High)")
        void score67IsHigh() {
            assertEquals(RiskLevel.HIGH, levelForScore(67));
        }
    }
}
