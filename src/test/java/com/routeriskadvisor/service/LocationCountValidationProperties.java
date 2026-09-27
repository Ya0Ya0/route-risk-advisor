package com.routeriskadvisor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.domain.model.RiskObservation;
import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteSegment;
import com.routeriskadvisor.provider.CrashDataProvider;
import com.routeriskadvisor.provider.CrimeDataProvider;
import com.routeriskadvisor.provider.FireDataProvider;
import com.routeriskadvisor.provider.ProviderException;
import com.routeriskadvisor.provider.RoutingProvider;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based test for {@link SafestRouteService} location-count validation.
 *
 * <p>Implements design correctness Property 13: for any list of submitted locations the service
 * requests candidate Routes from the Routing_Provider <em>if and only if</em> the count is between
 * 2 and 25 inclusive; otherwise it rejects the request with the "between 2 and 25 locations"
 * message ({@link SafestRouteException.Reason#LOCATION_COUNT_OUT_OF_RANGE}) and does not request
 * candidate Routes.
 *
 * <p>The test drives the real service with a recording {@link RoutingProvider} double that tracks
 * whether {@code candidateRoutes(...)} was invoked and returns a single valid in-area route when
 * called, plus crash/crime/fire doubles that always return a present observation so classification
 * succeeds for the in-range cases. All submitted coordinates are drawn from a conservative interior
 * rectangle of Miami-Dade County so the Service_Area check passes for every in-range size.
 *
 * <p><strong>Validates: Requirements 4.1, 4.6</strong>
 */
class LocationCountValidationProperties {

    private static final int MIN_LOCATIONS = 2;
    private static final int MAX_LOCATIONS = 25;

    // Feature: route-risk-advisor, Property 13: Location count validation
    @Property(tries = 200)
    void requestsCandidateRoutesIffCountInRange(
            @ForAll("locationLists") List<GeoCoordinate> locations) {
        RecordingRoutingProvider routing = new RecordingRoutingProvider();
        SafestRouteService service = new SafestRouteService(
            routing,
            presentCrash(),
            presentCrime(),
            presentFire(),
            10_000L,
            10_000L);

        int count = locations.size();
        boolean inRange = count >= MIN_LOCATIONS && count <= MAX_LOCATIONS;

        if (inRange) {
            // In range: the service must request candidate routes and complete without a
            // count rejection (Req 4.1).
            service.findSafestRoute(locations);
            assertThat(routing.candidateRoutesCalls())
                .as("in-range count %d must trigger a candidate-routes request", count)
                .isGreaterThanOrEqualTo(1);
        } else {
            // Out of range: the service must reject with the count reason/message and must not
            // request any candidate routes (Req 4.6).
            assertThatThrownBy(() -> service.findSafestRoute(locations))
                .as("out-of-range count %d must be rejected", count)
                .isInstanceOfSatisfying(SafestRouteException.class, ex ->
                    assertThat(ex.reason())
                        .isEqualTo(SafestRouteException.Reason.LOCATION_COUNT_OUT_OF_RANGE));
            assertThat(routing.candidateRoutesCalls())
                .as("out-of-range count %d must not trigger a candidate-routes request", count)
                .isZero();
        }
    }

    /**
     * Lists of in-area Miami-Dade coordinates whose size is drawn uniformly across {@code 0..40},
     * spanning both the accepted 2..25 range and the rejected boundaries on either side.
     */
    @Provide
    Arbitrary<List<GeoCoordinate>> locationLists() {
        return Arbitraries.integers().between(0, 40).flatMap(size ->
            inAreaCoordinate().list().ofSize(size));
    }

    /**
     * Coordinates strictly inside a conservative interior rectangle of Miami-Dade County
     * (latitude 25.30–25.85, longitude -80.75 to -80.30) so the Service_Area check always passes.
     */
    @Provide
    Arbitrary<GeoCoordinate> inAreaCoordinate() {
        Arbitrary<Double> latitude = Arbitraries.doubles().between(25.30, 25.85);
        Arbitrary<Double> longitude = Arbitraries.doubles().between(-80.75, -80.30);
        return Combinators.combine(latitude, longitude).as(GeoCoordinate::new);
    }

    /**
     * A {@link RoutingProvider} that records how many times {@link #candidateRoutes} was invoked
     * and returns a single valid in-area route so the downstream flow can complete.
     */
    private static final class RecordingRoutingProvider implements RoutingProvider {
        private final AtomicInteger candidateRoutesCalls = new AtomicInteger();

        int candidateRoutesCalls() {
            return candidateRoutesCalls.get();
        }

        @Override
        public Optional<Route> route(GeoCoordinate origin, GeoCoordinate destination) {
            return Optional.empty();
        }

        @Override
        public List<Route> candidateRoutes(List<GeoCoordinate> orderedPoints) {
            candidateRoutesCalls.incrementAndGet();
            RouteSegment segment = new RouteSegment(
                "seg-0",
                new GeoCoordinate(25.7617, -80.1918),
                new GeoCoordinate(25.7215, -80.2684),
                8500.0);
            Route route = new Route("route-0", 0, List.of(segment), 8500.0);
            return List.of(route);
        }
    }

    private static CrashDataProvider presentCrash() {
        return segment -> Optional.of(observation(segment));
    }

    private static CrimeDataProvider presentCrime() {
        return segment -> Optional.of(observation(segment));
    }

    private static FireDataProvider presentFire() {
        return segment -> Optional.of(observation(segment));
    }

    private static RiskObservation observation(RouteSegment segment) throws ProviderException {
        return new RiskObservation(segment, 0.5, 3);
    }
}
