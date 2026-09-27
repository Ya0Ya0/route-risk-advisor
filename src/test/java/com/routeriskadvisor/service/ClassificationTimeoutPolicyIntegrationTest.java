package com.routeriskadvisor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.routeriskadvisor.config.TimeoutProperties;
import com.routeriskadvisor.domain.DefaultInsuranceAdvisor;
import com.routeriskadvisor.domain.DefaultRouteClassifier;
import com.routeriskadvisor.domain.InsuranceAdvisor;
import com.routeriskadvisor.domain.RiskScorer;
import com.routeriskadvisor.domain.RouteClassifier;
import com.routeriskadvisor.domain.ServiceAreaValidator;
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
import com.routeriskadvisor.provider.GeocodingProvider;
import com.routeriskadvisor.provider.ProviderException;
import com.routeriskadvisor.provider.RoutingProvider;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Integration tests for the classification timeout policy (Requirements 1.8, 2.9).
 *
 * <p>The requirements specify a 10s budget for geocoding/routing and a 5s budget for risk-data
 * classification. Rather than sleeping for the real budgets, these tests construct the service
 * with {@link TimeoutProperties} configured to <em>small</em> budgets (tens of milliseconds) and
 * feed it provider test-doubles that either block just past the budget or throw a
 * {@link ProviderException}. That drives the timeout/abort path deterministically and quickly
 * while exercising exactly the same {@link TimeoutExecutor} enforcement the production budgets use.
 *
 * <p>Two behaviours are verified:
 * <ul>
 *   <li><b>Abort (Req 1.8):</b> a geocoding or routing provider that exceeds its budget (or fails)
 *       causes {@link RouteRiskService#classify} to abort with a
 *       {@link ServiceUnavailableException} ("temporarily unavailable"). There are no coordinates
 *       or route to work with, so the whole request fails.</li>
 *   <li><b>Degrade (Req 2.9):</b> a risk-data provider (crash/crime/fire) that fails degrades only
 *       <em>its</em> category to Unknown while the other categories are still scored. This is
 *       verified at the classifier level, mirroring the graceful-degradation contract the service
 *       relies on within the 5s classification budget.</li>
 * </ul>
 */
class ClassificationTimeoutPolicyIntegrationTest {

    /** Small budgets so the timeout path fires in milliseconds, not seconds. */
    private static final long TINY_BUDGET_MS = 40;
    /** How long a "slow" provider blocks — comfortably past {@link #TINY_BUDGET_MS}. */
    private static final long OVER_BUDGET_SLEEP_MS = 400;

    // Two clearly in-area Miami-Dade coordinates (Downtown Miami, Coral Gables).
    private static final GeoCoordinate ORIGIN = new GeoCoordinate(25.7617, -80.1918);
    private static final GeoCoordinate DESTINATION = new GeoCoordinate(25.7215, -80.2684);

    private final ServiceAreaValidator serviceArea = new ServiceAreaValidator();
    private final RiskScorer riskScorer = new RiskScorer();
    private final InsuranceAdvisor insuranceAdvisor = new DefaultInsuranceAdvisor();

    // ---------------------------------------------------------------------------------------
    // Req 1.8 — geocoding/routing timeout (or failure) aborts as "temporarily unavailable".
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("geocoding that exceeds its budget aborts classify() with ServiceUnavailableException (Req 1.8)")
    void slowGeocodingAbortsAsTemporarilyUnavailable() {
        GeocodingProvider slowGeocoder = location -> {
            sleepUninterruptibly(OVER_BUDGET_SLEEP_MS);
            return Optional.of(ORIGIN);
        };
        RoutingProvider routing = healthyRoutingProvider();

        RouteRiskService service = newService(slowGeocoder, routing, healthyClassifier());

        assertThatThrownBy(() -> service.classify("Downtown Miami", "Coral Gables"))
            .isInstanceOf(ServiceUnavailableException.class)
            .hasMessageContaining("temporarily unavailable");
    }

    @Test
    @DisplayName("geocoding that fails aborts classify() with ServiceUnavailableException (Req 1.8)")
    void failingGeocodingAbortsAsTemporarilyUnavailable() {
        GeocodingProvider failingGeocoder = location -> {
            throw new ProviderException("geocoder down", ProviderException.Kind.FAILURE);
        };
        RouteRiskService service = newService(failingGeocoder, healthyRoutingProvider(), healthyClassifier());

        assertThatThrownBy(() -> service.classify("Downtown Miami", "Coral Gables"))
            .isInstanceOf(ServiceUnavailableException.class)
            .hasMessageContaining("temporarily unavailable");
    }

    @Test
    @DisplayName("routing that exceeds its budget aborts classify() with ServiceUnavailableException (Req 1.8)")
    void slowRoutingAbortsAsTemporarilyUnavailable() {
        RoutingProvider slowRouting = new RoutingProvider() {
            @Override
            public Optional<Route> route(GeoCoordinate origin, GeoCoordinate destination) {
                sleepUninterruptibly(OVER_BUDGET_SLEEP_MS);
                return Optional.of(sampleRoute());
            }

            @Override
            public List<Route> candidateRoutes(List<GeoCoordinate> orderedPoints) {
                return List.of(sampleRoute());
            }
        };

        RouteRiskService service = newService(healthyGeocoder(), slowRouting, healthyClassifier());

        assertThatThrownBy(() -> service.classify("Downtown Miami", "Coral Gables"))
            .isInstanceOf(ServiceUnavailableException.class)
            .hasMessageContaining("temporarily unavailable");
    }

    @Test
    @DisplayName("routing that fails aborts classify() with ServiceUnavailableException (Req 1.8)")
    void failingRoutingAbortsAsTemporarilyUnavailable() {
        RoutingProvider failingRouting = new RoutingProvider() {
            @Override
            public Optional<Route> route(GeoCoordinate origin, GeoCoordinate destination)
                    throws ProviderException {
                throw new ProviderException("router down", ProviderException.Kind.FAILURE);
            }

            @Override
            public List<Route> candidateRoutes(List<GeoCoordinate> orderedPoints) {
                return List.of();
            }
        };

        RouteRiskService service = newService(healthyGeocoder(), failingRouting, healthyClassifier());

        assertThatThrownBy(() -> service.classify("Downtown Miami", "Coral Gables"))
            .isInstanceOf(ServiceUnavailableException.class)
            .hasMessageContaining("temporarily unavailable");
    }

    // ---------------------------------------------------------------------------------------
    // Req 2.9 — a failing risk-data provider degrades only its category to Unknown.
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("a failing crash provider yields Unknown for ACCIDENT only; THEFT and FIRE stay scored (Req 2.9)")
    void failingCrashProviderDegradesOnlyAccidentCategory() {
        // Crash provider fails; crime and fire always return a present, scorable observation.
        CrashDataProvider failingCrash = segment -> {
            throw new ProviderException("crash feed down", ProviderException.Kind.FAILURE);
        };
        CrimeDataProvider healthyCrime = segment -> Optional.of(observation(segment, 0.20));
        FireDataProvider healthyFire = segment -> Optional.of(observation(segment, 0.80));

        RouteClassifier classifier =
            new DefaultRouteClassifier(failingCrash, healthyCrime, healthyFire, riskScorer);

        RouteClassification classification = classifier.classify(sampleRoute());

        assertThat(levelOf(classification, RiskCategory.ACCIDENT)).isEqualTo(RiskLevel.UNKNOWN);
        assertThat(scoreOf(classification, RiskCategory.ACCIDENT)).isNull();

        // The other two categories were still computed (not Unknown) and carry a numeric score.
        assertThat(levelOf(classification, RiskCategory.THEFT)).isNotEqualTo(RiskLevel.UNKNOWN);
        assertThat(scoreOf(classification, RiskCategory.THEFT)).isNotNull();
        assertThat(levelOf(classification, RiskCategory.FIRE)).isNotEqualTo(RiskLevel.UNKNOWN);
        assertThat(scoreOf(classification, RiskCategory.FIRE)).isNotNull();
    }

    @Test
    @DisplayName("a timing-out theft provider degrades only THEFT; ACCIDENT and FIRE stay scored (Req 2.9)")
    void timingOutTheftProviderDegradesOnlyTheftCategory() {
        CrashDataProvider healthyCrash = segment -> Optional.of(observation(segment, 0.10));
        // A TIMEOUT-kind ProviderException degrades identically to a FAILURE (Req 2.9).
        CrimeDataProvider timingOutCrime = segment -> {
            throw new ProviderException("crime feed timed out", ProviderException.Kind.TIMEOUT);
        };
        FireDataProvider healthyFire = segment -> Optional.of(observation(segment, 0.90));

        RouteClassifier classifier =
            new DefaultRouteClassifier(healthyCrash, timingOutCrime, healthyFire, riskScorer);

        RouteClassification classification = classifier.classify(sampleRoute());

        assertThat(levelOf(classification, RiskCategory.THEFT)).isEqualTo(RiskLevel.UNKNOWN);
        assertThat(scoreOf(classification, RiskCategory.THEFT)).isNull();

        assertThat(levelOf(classification, RiskCategory.ACCIDENT)).isNotEqualTo(RiskLevel.UNKNOWN);
        assertThat(scoreOf(classification, RiskCategory.ACCIDENT)).isNotNull();
        assertThat(levelOf(classification, RiskCategory.FIRE)).isNotEqualTo(RiskLevel.UNKNOWN);
        assertThat(scoreOf(classification, RiskCategory.FIRE)).isNotNull();
    }

    @Test
    @DisplayName("a failing risk-data provider inside the 5s budget still lets classify() succeed with degraded category (Req 2.9)")
    void failingRiskProviderDoesNotAbortServiceClassify() throws Exception {
        // At the service level, a degraded category must NOT abort the request — only
        // geocoding/routing failures do (Req 1.8). Wire a classifier whose crash provider fails.
        CrashDataProvider failingCrash = segment -> {
            throw new ProviderException("crash feed down", ProviderException.Kind.FAILURE);
        };
        CrimeDataProvider healthyCrime = segment -> Optional.of(observation(segment, 0.20));
        FireDataProvider healthyFire = segment -> Optional.of(observation(segment, 0.30));
        RouteClassifier degradingClassifier =
            new DefaultRouteClassifier(failingCrash, healthyCrime, healthyFire, riskScorer);

        RouteRiskService service = newService(healthyGeocoder(), healthyRoutingProvider(), degradingClassifier);

        RouteClassificationResult result = service.classify("Downtown Miami", "Coral Gables");

        RouteClassification classification = result.classification();
        assertThat(levelOf(classification, RiskCategory.ACCIDENT)).isEqualTo(RiskLevel.UNKNOWN);
        assertThat(levelOf(classification, RiskCategory.THEFT)).isNotEqualTo(RiskLevel.UNKNOWN);
        assertThat(levelOf(classification, RiskCategory.FIRE)).isNotEqualTo(RiskLevel.UNKNOWN);
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** Builds the service with tiny timeout budgets so the timeout path fires quickly. */
    private RouteRiskService newService(
            GeocodingProvider geocoder, RoutingProvider routing, RouteClassifier classifier) {
        TimeoutProperties timeouts = new TimeoutProperties();
        timeouts.setGeocodingMs(TINY_BUDGET_MS);
        timeouts.setRoutingMs(TINY_BUDGET_MS);
        timeouts.setServiceAreaMs(TINY_BUDGET_MS);
        timeouts.setClassifyMs(TINY_BUDGET_MS);
        timeouts.setRecommendMs(TINY_BUDGET_MS);
        return new RouteRiskService(
            geocoder, routing, serviceArea, classifier, insuranceAdvisor, new TimeoutExecutor(), timeouts);
    }

    /** Geocoder that returns a distinct in-area coordinate for each of the two calls. */
    private static GeocodingProvider healthyGeocoder() {
        AtomicInteger calls = new AtomicInteger();
        return location -> Optional.of(calls.getAndIncrement() == 0 ? ORIGIN : DESTINATION);
    }

    private static RoutingProvider healthyRoutingProvider() {
        return new RoutingProvider() {
            @Override
            public Optional<Route> route(GeoCoordinate origin, GeoCoordinate destination) {
                return Optional.of(sampleRoute());
            }

            @Override
            public List<Route> candidateRoutes(List<GeoCoordinate> orderedPoints) {
                return List.of(sampleRoute());
            }
        };
    }

    /** Classifier that returns a fixed, all-scored classification (used when we test the abort path). */
    private RouteClassifier healthyClassifier() {
        CrashDataProvider crash = segment -> Optional.of(observation(segment, 0.10));
        CrimeDataProvider crime = segment -> Optional.of(observation(segment, 0.50));
        FireDataProvider fire = segment -> Optional.of(observation(segment, 0.90));
        return new DefaultRouteClassifier(crash, crime, fire, riskScorer);
    }

    private static Route sampleRoute() {
        RouteSegment segment = new RouteSegment("seg-1", ORIGIN, DESTINATION, 8500.0);
        return new Route("route-1", 0, List.of(segment), 8500.0);
    }

    private static RiskObservation observation(RouteSegment segment, double intensity) {
        return new RiskObservation(segment, intensity, 5);
    }

    private static RiskLevel levelOf(RouteClassification classification, RiskCategory category) {
        return assessment(classification, category).level();
    }

    private static Integer scoreOf(RouteClassification classification, RiskCategory category) {
        return assessment(classification, category).score();
    }

    private static RiskAssessment assessment(RouteClassification classification, RiskCategory category) {
        return classification.assessments().stream()
            .filter(a -> a.category() == category)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no assessment for " + category));
    }

    private static void sleepUninterruptibly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
