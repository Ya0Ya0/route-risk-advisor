package com.routeriskadvisor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.routeriskadvisor.config.TimeoutProperties;
import com.routeriskadvisor.domain.InsuranceAdvisor;
import com.routeriskadvisor.domain.RouteClassifier;
import com.routeriskadvisor.domain.ServiceAreaValidator;
import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.domain.model.InsuranceRecommendationResult;
import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteClassification;
import com.routeriskadvisor.provider.GeocodingProvider;
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
 * Property-based test for {@link RouteRiskService} identical-coordinate short-circuit behaviour.
 *
 * <p>Implements design correctness Property 3: when the resolved origin and destination
 * coordinates are identical, the service returns the same-location outcome and never requests a
 * Route from the Routing_Provider. Here the geocoder is a test double that maps both the origin
 * and the destination input strings to the SAME in-area {@link GeoCoordinate}, guaranteeing the
 * resolved coordinates are identical; the routing provider is a test double that records whether
 * {@code route(...)} was ever invoked.
 *
 * <p><strong>Validates: Requirements 1.7</strong>
 */
class IdenticalCoordinateShortCircuitProperties {

    // Feature: route-risk-advisor, Property 3: Identical coordinates short-circuit routing
    @Property(tries = 200)
    void identicalResolvedCoordinatesShortCircuitRoutingProvider(
            @ForAll("insideMiamiDade") GeoCoordinate resolved,
            @ForAll("validLocationString") String origin,
            @ForAll("validLocationString") String destination) {

        // Geocoder maps BOTH inputs to the same in-area coordinate, forcing identical resolved
        // origin/destination (Req 1.7 precondition).
        GeocodingProvider geocoder = location -> Optional.of(resolved);

        // Routing provider records every call so we can assert it is never touched.
        RecordingRoutingProvider routing = new RecordingRoutingProvider();

        RouteRiskService service = new RouteRiskService(
            geocoder,
            routing,
            new ServiceAreaValidator(),
            unreachableClassifier(),
            unreachableAdvisor(),
            new TimeoutExecutor(),
            new TimeoutProperties());

        // The service must signal the same-location outcome...
        assertThatThrownBy(() -> service.classify(origin, destination))
            .as("identical resolved coordinates must yield the same-location outcome for %s / %s",
                origin, destination)
            .isInstanceOf(SameLocationException.class);

        // ...and it must never have asked the Routing_Provider for a route (single-route or
        // candidate-routes entry points).
        assertThat(routing.totalCallCount())
            .as("Routing_Provider must not be called when origin and destination resolve identically")
            .isZero();
    }

    /**
     * Generates coordinates that lie strictly inside the Miami-Dade County Service_Area polygon so
     * containment passes and the flow reaches the identical-coordinate short-circuit. Samples a
     * conservative interior rectangle that stays clear of every polygon edge.
     * {@code GeoCoordinate} is {@code (latitude, longitude)}.
     */
    @Provide
    Arbitrary<GeoCoordinate> insideMiamiDade() {
        Arbitrary<Double> latitude = Arbitraries.doubles().between(25.30, 25.85);
        Arbitrary<Double> longitude = Arbitraries.doubles().between(-80.75, -80.30);
        return Combinators.combine(latitude, longitude).as(GeoCoordinate::new);
    }

    /**
     * Generates non-blank location strings of 1..250 (trimmed) characters so input validation
     * passes. The actual text is irrelevant because the geocoder double ignores it.
     */
    @Provide
    Arbitrary<String> validLocationString() {
        return Arbitraries.strings()
            .withCharRange('a', 'z')
            .ofMinLength(1)
            .ofMaxLength(250);
    }

    /**
     * A {@link RoutingProvider} that records whether either {@link #route} or
     * {@link #candidateRoutes} was ever invoked.
     */
    private static final class RecordingRoutingProvider implements RoutingProvider {

        private final AtomicInteger routeCalls = new AtomicInteger();
        private final AtomicInteger candidateRoutesCalls = new AtomicInteger();

        @Override
        public Optional<Route> route(GeoCoordinate origin, GeoCoordinate destination)
                throws ProviderException {
            routeCalls.incrementAndGet();
            return Optional.empty();
        }

        @Override
        public List<Route> candidateRoutes(List<GeoCoordinate> orderedPoints)
                throws ProviderException {
            candidateRoutesCalls.incrementAndGet();
            return List.of();
        }

        int totalCallCount() {
            return routeCalls.get() + candidateRoutesCalls.get();
        }
    }

    /** A classifier that must never be reached when routing short-circuits. */
    private static RouteClassifier unreachableClassifier() {
        return route -> {
            throw new AssertionError(
                "RouteClassifier must not be reached when origin and destination are identical");
        };
    }

    /** An advisor that must never be reached when routing short-circuits. */
    private static InsuranceAdvisor unreachableAdvisor() {
        return classification -> {
            throw new AssertionError(
                "InsuranceAdvisor must not be reached when origin and destination are identical");
        };
    }
}
