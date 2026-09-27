package com.routeriskadvisor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.domain.model.RiskObservation;
import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteSegment;
import com.routeriskadvisor.domain.model.SafestRouteResult;
import com.routeriskadvisor.provider.CrashDataProvider;
import com.routeriskadvisor.provider.CrimeDataProvider;
import com.routeriskadvisor.provider.FireDataProvider;
import com.routeriskadvisor.provider.ProviderException;
import com.routeriskadvisor.provider.RoutingProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit/integration tests for the classify-per-candidate contract and the abort outcomes of the
 * Safest Route Finder flow.
 *
 * <p>These tests exercise {@link SafestRouteService} through its real domain collaborators
 * (it builds {@code DefaultRouteClassifier}, {@code DefaultRouteFinder}, {@code ServiceAreaValidator}
 * internally); only the provider <em>interfaces</em> are substituted with counting/failing
 * test-doubles. This lets us observe how the service drives classification without reaching into
 * its private wiring.
 *
 * <ul>
 *   <li>Req 4.2 — every candidate route is classified exactly once: because the classifier draws
 *       one crash/crime/fire observation per segment, "classified once" is observable as each
 *       segment of each candidate being queried exactly once per category. The caching wrapper the
 *       service feeds the finder must not re-query providers.</li>
 *   <li>Req 4.8 — no candidate routes (empty/timeout) aborts with {@code NO_ROUTES_RETRIEVED} and
 *       the {@code No routes could be retrieved.} message, with no result.</li>
 *   <li>Req 4.9 — a classifier that fails or times out for a candidate aborts with
 *       {@code ROUTE_RISK_UNEVALUATED} and the {@code Route risk could not be evaluated.} message,
 *       with no partial result.</li>
 * </ul>
 *
 * <p>Placed in {@code com.routeriskadvisor.service} to read the package-private
 * {@code NO_ROUTES_MESSAGE} / {@code RISK_UNEVALUATED_MESSAGE} constants directly.
 */
class SafestRouteServiceClassificationTest {

    /** A generous 10s budget so time-bounding never trips in the non-timeout tests. */
    private static final long BUDGET_MS = 10_000L;

    // Two clearly in-area Miami-Dade points (Downtown Miami / Coral Gables), so the service-area
    // gate passes and the flow reaches routing + classification.
    private static final GeoCoordinate DOWNTOWN = new GeoCoordinate(25.7617, -80.1918);
    private static final GeoCoordinate GABLES = new GeoCoordinate(25.7215, -80.2684);

    // --- Test data builders -------------------------------------------------------------------

    private static RouteSegment segment(String id) {
        return new RouteSegment(id, DOWNTOWN, GABLES, 8500.0);
    }

    /** A candidate route with {@code segmentCount} uniquely-identified segments. */
    private static Route route(String id, int providerIndex, int segmentCount) {
        List<RouteSegment> segments = new ArrayList<>(segmentCount);
        for (int i = 0; i < segmentCount; i++) {
            segments.add(segment(id + "-seg-" + i));
        }
        return new Route(id, providerIndex, segments, 8500.0 * segmentCount);
    }

    private static List<GeoCoordinate> twoLocations() {
        return List.of(DOWNTOWN, GABLES);
    }

    // --- Test 1: classification happens exactly once per candidate route (Req 4.2) ------------

    @Test
    @DisplayName("each candidate route's segments are queried exactly once per category (classify-once, Req 4.2)")
    void classifiesEachCandidateExactlyOnce() {
        int candidateCount = 4;
        int segmentsPerRoute = 3;

        List<Route> candidates = new ArrayList<>();
        for (int i = 0; i < candidateCount; i++) {
            candidates.add(route("r" + i, i, segmentsPerRoute));
        }

        CountingCrashProvider crash = new CountingCrashProvider();
        CountingCrimeProvider crime = new CountingCrimeProvider();
        CountingFireProvider fire = new CountingFireProvider();

        SafestRouteService service = new SafestRouteService(
            listRoutingProvider(candidates), crash, crime, fire, BUDGET_MS, BUDGET_MS);

        SafestRouteResult result = service.findSafestRoute(twoLocations());

        // A complete, non-partial result with one recommendation per evaluated category.
        assertThat(result).isNotNull();
        assertThat(result.perCategory()).hasSize(3);

        int totalSegments = candidateCount * segmentsPerRoute;

        // Each category provider is queried once per segment: candidateCount * segmentsPerRoute.
        // If a candidate were classified twice (e.g. the finder re-invoking a real classifier
        // instead of reusing the cache) these totals would double.
        assertThat(crash.totalQueries()).isEqualTo(totalSegments);
        assertThat(crime.totalQueries()).isEqualTo(totalSegments);
        assertThat(fire.totalQueries()).isEqualTo(totalSegments);

        // And no individual segment is queried more than once by any provider — the strongest
        // form of "classified exactly once per candidate".
        assertThat(crash.maxPerSegment()).isEqualTo(1);
        assertThat(crime.maxPerSegment()).isEqualTo(1);
        assertThat(fire.maxPerSegment()).isEqualTo(1);
    }

    // --- Test 2: no candidate routes aborts with NO_ROUTES_RETRIEVED (Req 4.8) -----------------

    @Test
    @DisplayName("empty candidate list aborts with NO_ROUTES_RETRIEVED and the no-routes message (Req 4.8)")
    void abortsWhenNoRoutesRetrieved() {
        SafestRouteService service = new SafestRouteService(
            listRoutingProvider(List.of()),
            new CountingCrashProvider(),
            new CountingCrimeProvider(),
            new CountingFireProvider(),
            BUDGET_MS, BUDGET_MS);

        SafestRouteException thrown = catchThrowableOfType(
            () -> service.findSafestRoute(twoLocations()), SafestRouteException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.reason()).isEqualTo(SafestRouteException.Reason.NO_ROUTES_RETRIEVED);
        assertThat(thrown.getMessage()).isEqualTo(SafestRouteService.NO_ROUTES_MESSAGE);
    }

    @Test
    @DisplayName("routing timeout aborts with NO_ROUTES_RETRIEVED and the no-routes message (Req 4.8)")
    void abortsWhenRoutingTimesOut() {
        RoutingProvider slowRouting = new RoutingProvider() {
            @Override
            public Optional<Route> route(GeoCoordinate origin, GeoCoordinate destination) {
                return Optional.empty();
            }

            @Override
            public List<Route> candidateRoutes(List<GeoCoordinate> orderedPoints) {
                sleepBeyondBudget();
                return List.of(SafestRouteServiceClassificationTest.route("late", 0, 1));
            }
        };

        // A tiny routing budget so the slow provider overruns it deterministically.
        SafestRouteService service = new SafestRouteService(
            slowRouting,
            new CountingCrashProvider(),
            new CountingCrimeProvider(),
            new CountingFireProvider(),
            50L, BUDGET_MS);

        SafestRouteException thrown = catchThrowableOfType(
            () -> service.findSafestRoute(twoLocations()), SafestRouteException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.reason()).isEqualTo(SafestRouteException.Reason.NO_ROUTES_RETRIEVED);
        assertThat(thrown.getMessage()).isEqualTo(SafestRouteService.NO_ROUTES_MESSAGE);
    }

    // --- Test 3: classifier failure/timeout aborts with ROUTE_RISK_UNEVALUATED (Req 4.9) -------

    @Test
    @DisplayName("classifier failure for a candidate aborts with ROUTE_RISK_UNEVALUATED and the risk-unevaluated message (Req 4.9)")
    void abortsWhenClassifierFails() {
        // DefaultRouteClassifier swallows a checked ProviderException (degrading a category to
        // Unknown, Req 2.9). A real classifier failure that the finder must abort on surfaces as
        // an unchecked exception escaping classify(); model that with a provider that throws one.
        CrashDataProvider explodingCrash = segmentReturn -> {
            throw new IllegalStateException("crash datastore unavailable");
        };

        SafestRouteService service = new SafestRouteService(
            listRoutingProvider(List.of(route("r0", 0, 2))),
            explodingCrash,
            new CountingCrimeProvider(),
            new CountingFireProvider(),
            BUDGET_MS, BUDGET_MS);

        SafestRouteException thrown = catchThrowableOfType(
            () -> service.findSafestRoute(twoLocations()), SafestRouteException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.reason()).isEqualTo(SafestRouteException.Reason.ROUTE_RISK_UNEVALUATED);
        assertThat(thrown.getMessage()).isEqualTo(SafestRouteService.RISK_UNEVALUATED_MESSAGE);
    }

    @Test
    @DisplayName("classifier timeout for a candidate aborts with ROUTE_RISK_UNEVALUATED and the risk-unevaluated message (Req 4.9)")
    void abortsWhenClassifierTimesOut() {
        // A crash provider that hangs past the classify budget makes classify() overrun its
        // deadline, which the service treats as a route-risk-unevaluated abort.
        CrashDataProvider slowCrash = segment -> {
            sleepBeyondBudget();
            return Optional.empty();
        };

        SafestRouteService service = new SafestRouteService(
            listRoutingProvider(List.of(route("r0", 0, 1))),
            slowCrash,
            new CountingCrimeProvider(),
            new CountingFireProvider(),
            BUDGET_MS, 50L);

        SafestRouteException thrown = catchThrowableOfType(
            () -> service.findSafestRoute(twoLocations()), SafestRouteException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.reason()).isEqualTo(SafestRouteException.Reason.ROUTE_RISK_UNEVALUATED);
        assertThat(thrown.getMessage()).isEqualTo(SafestRouteService.RISK_UNEVALUATED_MESSAGE);
    }

    @Test
    @DisplayName("a checked ProviderException degrades gracefully and does NOT abort the comparison (Req 2.9)")
    void providerExceptionDegradesRatherThanAborting() {
        // Complements Req 4.9: only a classifier that genuinely fails to produce a classification
        // aborts. A single provider raising a checked ProviderException degrades that category to
        // Unknown while the flow still returns a complete result.
        CrashDataProvider failingCrash = segment -> {
            throw new ProviderException("crash feed down", ProviderException.Kind.FAILURE);
        };

        SafestRouteService service = new SafestRouteService(
            listRoutingProvider(List.of(route("r0", 0, 2))),
            failingCrash,
            new CountingCrimeProvider(),
            new CountingFireProvider(),
            BUDGET_MS, BUDGET_MS);

        SafestRouteResult result = service.findSafestRoute(twoLocations());

        assertThat(result).isNotNull();
        assertThat(result.perCategory()).hasSize(3);
    }

    @Test
    @DisplayName("out-of-service-area location is rejected before any routing is requested (Req 6.3)")
    void rejectsOutOfAreaBeforeRouting() {
        AtomicInteger routingCalls = new AtomicInteger();
        RoutingProvider trackingRouting = new RoutingProvider() {
            @Override
            public Optional<Route> route(GeoCoordinate origin, GeoCoordinate destination) {
                return Optional.empty();
            }

            @Override
            public List<Route> candidateRoutes(List<GeoCoordinate> orderedPoints) {
                routingCalls.incrementAndGet();
                return List.of(SafestRouteServiceClassificationTest.route("r0", 0, 1));
            }
        };

        SafestRouteService service = new SafestRouteService(
            trackingRouting,
            new CountingCrashProvider(),
            new CountingCrimeProvider(),
            new CountingFireProvider(),
            BUDGET_MS, BUDGET_MS);

        // Second location is far outside Miami-Dade (roughly London), so validation rejects it.
        List<GeoCoordinate> locations = List.of(DOWNTOWN, new GeoCoordinate(51.5074, -0.1278));

        assertThatThrownBy(() -> service.findSafestRoute(locations))
            .isInstanceOf(SafestRouteException.class)
            .satisfies(e -> assertThat(((SafestRouteException) e).reason())
                .isEqualTo(SafestRouteException.Reason.OUT_OF_SERVICE_AREA));
        assertThat(routingCalls.get()).isZero();
    }

    // --- Test doubles -------------------------------------------------------------------------

    private static void sleepBeyondBudget() {
        try {
            Thread.sleep(2_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** A routing provider that always returns the supplied candidate list. */
    private static RoutingProvider listRoutingProvider(List<Route> candidates) {
        return new RoutingProvider() {
            @Override
            public Optional<Route> route(GeoCoordinate origin, GeoCoordinate destination) {
                return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));
            }

            @Override
            public List<Route> candidateRoutes(List<GeoCoordinate> orderedPoints) {
                return candidates;
            }
        };
    }

    /**
     * Records how many times each distinct segment id was queried. {@code totalQueries} sums all
     * calls; {@code maxPerSegment} is the busiest single segment. Thread-safe because the service
     * runs classification on its own worker thread.
     */
    private abstract static class CountingProvider {
        private final Map<String, LongAdder> perSegment = new ConcurrentHashMap<>();

        protected Optional<RiskObservation> record(RouteSegment segment) {
            perSegment.computeIfAbsent(segment.id(), k -> new LongAdder()).increment();
            // Present, well-formed observation so the scorer produces a real (non-Unknown) score.
            return Optional.of(new RiskObservation(segment, 0.5, 3));
        }

        long totalQueries() {
            return perSegment.values().stream().mapToLong(LongAdder::sum).sum();
        }

        long maxPerSegment() {
            return perSegment.values().stream().mapToLong(LongAdder::sum).max().orElse(0L);
        }
    }

    private static final class CountingCrashProvider extends CountingProvider
            implements CrashDataProvider {
        @Override
        public Optional<RiskObservation> crashData(RouteSegment segment) {
            return record(segment);
        }
    }

    private static final class CountingCrimeProvider extends CountingProvider
            implements CrimeDataProvider {
        @Override
        public Optional<RiskObservation> theftData(RouteSegment segment) {
            return record(segment);
        }
    }

    private static final class CountingFireProvider extends CountingProvider
            implements FireDataProvider {
        @Override
        public Optional<RiskObservation> fireData(RouteSegment segment) {
            return record(segment);
        }
    }
}
