package com.routeriskadvisor.provider.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.routeriskadvisor.domain.ServiceAreaValidator;
import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteSegment;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Example/unit tests for {@link PlaceholderRoutingProvider} (Requirements 5.2, 5.3, 6.6).
 */
class PlaceholderRoutingProviderTest {

    private final PlaceholderRoutingProvider provider = new PlaceholderRoutingProvider();
    private final ServiceAreaValidator serviceArea = new ServiceAreaValidator();

    private static final GeoCoordinate DOWNTOWN_MIAMI = new GeoCoordinate(25.7617, -80.1918);
    private static final GeoCoordinate CORAL_GABLES = new GeoCoordinate(25.7215, -80.2684);
    private static final GeoCoordinate KENDALL = new GeoCoordinate(25.6793, -80.3173);

    @Test
    @DisplayName("route() synthesizes a single route with plausible positive distance")
    void singleRouteHasPositiveDistance() throws Exception {
        Optional<Route> result = provider.route(DOWNTOWN_MIAMI, CORAL_GABLES);
        assertTrue(result.isPresent());
        Route route = result.get();
        assertFalse(route.segments().isEmpty());
        assertTrue(route.totalDistanceMeters() > 0);
        assertEquals(0, route.providerIndex());
    }

    @Test
    @DisplayName("route() total distance equals the sum of its segment distances")
    void totalDistanceMatchesSegments() throws Exception {
        Route route = provider.route(DOWNTOWN_MIAMI, KENDALL).orElseThrow();
        double summed = route.segments().stream().mapToDouble(RouteSegment::distanceMeters).sum();
        assertEquals(summed, route.totalDistanceMeters(), 1e-6);
    }

    @Test
    @DisplayName("route() returns empty when an endpoint is null")
    void nullEndpointReturnsEmpty() throws Exception {
        assertTrue(provider.route(null, CORAL_GABLES).isEmpty());
        assertTrue(provider.route(DOWNTOWN_MIAMI, null).isEmpty());
    }

    @Test
    @DisplayName("candidateRoutes() returns multiple candidates with stable, distinct provider indices")
    void candidatesHaveStableIndices() throws Exception {
        List<Route> routes = provider.candidateRoutes(List.of(DOWNTOWN_MIAMI, CORAL_GABLES, KENDALL));
        assertTrue(routes.size() >= 2);
        for (int i = 0; i < routes.size(); i++) {
            assertEquals(i, routes.get(i).providerIndex());
        }
    }

    @Test
    @DisplayName("candidateRoutes() returns empty for fewer than 2 points or null")
    void tooFewPointsReturnsEmpty() throws Exception {
        assertTrue(provider.candidateRoutes(null).isEmpty());
        assertTrue(provider.candidateRoutes(List.of()).isEmpty());
        assertTrue(provider.candidateRoutes(List.of(DOWNTOWN_MIAMI)).isEmpty());
    }

    @Test
    @DisplayName("every coordinate emitted by route() and candidateRoutes() is inside the Service_Area")
    void allEmittedCoordinatesAreInArea() throws Exception {
        List<GeoCoordinate> points = List.of(DOWNTOWN_MIAMI, CORAL_GABLES, KENDALL);
        Route single = provider.route(DOWNTOWN_MIAMI, KENDALL).orElseThrow();
        assertAllInArea(single);
        for (Route route : provider.candidateRoutes(points)) {
            assertAllInArea(route);
        }
    }

    private void assertAllInArea(Route route) {
        for (RouteSegment segment : route.segments()) {
            assertTrue(serviceArea.contains(segment.start()),
                "segment start must be in Service_Area: " + segment.start());
            assertTrue(serviceArea.contains(segment.end()),
                "segment end must be in Service_Area: " + segment.end());
        }
    }
}
