package com.routeriskadvisor.provider.placeholder;

import static org.assertj.core.api.Assertions.assertThat;

import com.routeriskadvisor.domain.ServiceAreaValidator;
import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteSegment;
import com.routeriskadvisor.provider.ProviderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

/**
 * Property-based tests implementing design correctness Property 16: for any input to a
 * Placeholder_Provider, every returned record conforms to the field structure of its
 * Data_Provider interface (required fields present and within valid ranges) and every
 * geographic coordinate it references lies within the Service_Area.
 *
 * <p><strong>Validates: Requirements 5.3, 6.6</strong>
 *
 * <p>Each property feeds varied, generated inputs to the placeholder providers and asserts both
 * halves of the invariant: (1) structural validity of every returned record (non-null fields,
 * {@code normalizedIntensity} in {@code [0,1]}, non-negative counts/distances, non-negative
 * provider index), and (2) every {@link GeoCoordinate} the record references is inside the
 * Miami-Dade County Service_Area per {@link ServiceAreaValidator}.
 */
class PlaceholderOutputValidityProperties {

    private final PlaceholderCrashDataProvider crash = new PlaceholderCrashDataProvider();
    private final PlaceholderCrimeDataProvider crime = new PlaceholderCrimeDataProvider();
    private final PlaceholderFireDataProvider fire = new PlaceholderFireDataProvider();
    private final PlaceholderGeocodingProvider geocoding = new PlaceholderGeocodingProvider();
    private final PlaceholderRoutingProvider routing = new PlaceholderRoutingProvider();
    private final ServiceAreaValidator serviceArea = new ServiceAreaValidator();

    /** Curated Miami-Dade place names known to the placeholder geocoder. */
    private static final List<String> KNOWN_PLACES = List.of(
        "Downtown Miami", "Miami", "Miami Beach", "Coral Gables", "Hialeah",
        "Kendall", "Homestead", "Doral", "North Miami", "Miami International Airport");

    // Feature: route-risk-advisor, Property 16: Placeholder output is valid and in-area
    @Property(tries = 200)
    void crashObservationsAreValidAndInArea(@ForAll("inAreaSegment") RouteSegment segment)
            throws ProviderException {
        assertObservationValidAndInArea(crash.crashData(segment), segment);
    }

    // Feature: route-risk-advisor, Property 16: Placeholder output is valid and in-area
    @Property(tries = 200)
    void crimeObservationsAreValidAndInArea(@ForAll("inAreaSegment") RouteSegment segment)
            throws ProviderException {
        assertObservationValidAndInArea(crime.theftData(segment), segment);
    }

    // Feature: route-risk-advisor, Property 16: Placeholder output is valid and in-area
    @Property(tries = 200)
    void fireObservationsAreValidAndInArea(@ForAll("inAreaSegment") RouteSegment segment)
            throws ProviderException {
        assertObservationValidAndInArea(fire.fireData(segment), segment);
    }

    // Feature: route-risk-advisor, Property 16: Placeholder output is valid and in-area
    @Property(tries = 200)
    void geocodedCuratedPlacesResolveInsideServiceArea(@ForAll("knownPlace") String place)
            throws ProviderException {
        Optional<GeoCoordinate> resolved = geocoding.geocode(place);
        assertThat(resolved)
            .as("curated Miami-Dade place must resolve to a coordinate: %s", place)
            .isPresent();
        assertThat(serviceArea.contains(resolved.get()))
            .as("geocoded coordinate for %s must be inside the Service_Area: %s", place, resolved.get())
            .isTrue();
    }

    // Feature: route-risk-advisor, Property 16: Placeholder output is valid and in-area
    @Property(tries = 200)
    void geocodedArbitraryInputNeverEscapesServiceArea(@ForAll("arbitraryQuery") String query)
            throws ProviderException {
        // The geocoder may resolve nothing for arbitrary input, but any coordinate it does
        // return must lie inside the Service_Area (Req 6.6).
        geocoding.geocode(query).ifPresent(coordinate ->
            assertThat(serviceArea.contains(coordinate))
                .as("any resolved coordinate must be inside the Service_Area: %s", coordinate)
                .isTrue());
    }

    // Feature: route-risk-advisor, Property 16: Placeholder output is valid and in-area
    @Property(tries = 200)
    void singleRouteIsValidAndInArea(
            @ForAll("inAreaCoordinate") GeoCoordinate origin,
            @ForAll("inAreaCoordinate") GeoCoordinate destination)
            throws ProviderException {
        routing.route(origin, destination).ifPresent(this::assertRouteValidAndInArea);
    }

    // Feature: route-risk-advisor, Property 16: Placeholder output is valid and in-area
    @Property(tries = 200)
    void candidateRoutesAreValidAndInArea(
            @ForAll("inAreaPoints") @Size(min = 2, max = 6) List<GeoCoordinate> points)
            throws ProviderException {
        List<Route> routes = routing.candidateRoutes(points);
        for (Route route : routes) {
            assertRouteValidAndInArea(route);
        }
    }

    private void assertObservationValidAndInArea(
            Optional<com.routeriskadvisor.domain.model.RiskObservation> observation,
            RouteSegment segment) {
        observation.ifPresent(obs -> {
            assertThat(obs.segment())
                .as("observation must echo a non-null segment")
                .isNotNull()
                .isEqualTo(segment);
            assertThat(obs.normalizedIntensity())
                .as("normalizedIntensity must be in [0,1]: %s", obs.normalizedIntensity())
                .isBetween(0.0, 1.0);
            assertThat(obs.sampleCount())
                .as("sampleCount must be non-negative: %s", obs.sampleCount())
                .isGreaterThanOrEqualTo(0);
            assertCoordinateInArea(obs.segment().start());
            assertCoordinateInArea(obs.segment().end());
        });
    }

    private void assertRouteValidAndInArea(Route route) {
        assertThat(route.id())
            .as("route id must be non-null and non-blank")
            .isNotNull()
            .isNotBlank();
        assertThat(route.providerIndex())
            .as("providerIndex must be non-negative: %s", route.providerIndex())
            .isGreaterThanOrEqualTo(0);
        assertThat(route.totalDistanceMeters())
            .as("total distance must be non-negative and finite: %s", route.totalDistanceMeters())
            .isGreaterThanOrEqualTo(0.0);
        assertThat(Double.isFinite(route.totalDistanceMeters()))
            .as("total distance must be finite")
            .isTrue();
        assertThat(route.segments())
            .as("route must have at least one segment")
            .isNotNull()
            .isNotEmpty();

        for (RouteSegment segment : route.segments()) {
            assertThat(segment.id())
                .as("segment id must be non-null and non-blank")
                .isNotNull()
                .isNotBlank();
            assertThat(segment.distanceMeters())
                .as("segment distance must be non-negative: %s", segment.distanceMeters())
                .isGreaterThanOrEqualTo(0.0);
            assertThat(segment.start()).as("segment start must be non-null").isNotNull();
            assertThat(segment.end()).as("segment end must be non-null").isNotNull();
            assertCoordinateInArea(segment.start());
            assertCoordinateInArea(segment.end());
        }
    }

    private void assertCoordinateInArea(GeoCoordinate coordinate) {
        assertThat(serviceArea.contains(coordinate))
            .as("referenced coordinate must be inside the Service_Area: %s", coordinate)
            .isTrue();
    }

    /**
     * Generates coordinates strictly inside the Miami-Dade County polygon. The generator samples
     * from a conservative interior rectangle (latitude 25.35–25.80, longitude -80.70 to -80.35)
     * that stays clear of every polygon edge, so every draw is guaranteed in-area. This margin
     * also leaves room for the routing provider's small intermediate-point offsets to remain
     * in-area. {@code GeoCoordinate} is {@code (latitude, longitude)}.
     */
    @Provide
    Arbitrary<GeoCoordinate> inAreaCoordinate() {
        Arbitrary<Double> latitude = Arbitraries.doubles().between(25.35, 25.80);
        Arbitrary<Double> longitude = Arbitraries.doubles().between(-80.70, -80.35);
        return Combinators.combine(latitude, longitude).as(GeoCoordinate::new);
    }

    /**
     * Generates in-area route segments with a non-null id and a non-negative distance. Both
     * endpoints are drawn from the in-area interior rectangle.
     */
    @Provide
    Arbitrary<RouteSegment> inAreaSegment() {
        Arbitrary<String> ids = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12);
        Arbitrary<Double> distances = Arbitraries.doubles().between(0.0, 50_000.0);
        return Combinators.combine(ids, inAreaCoordinate(), inAreaCoordinate(), distances)
            .as(RouteSegment::new);
    }

    /** Ordered lists of in-area points for the candidate-routes flow. */
    @Provide
    Arbitrary<List<GeoCoordinate>> inAreaPoints() {
        return inAreaCoordinate().list().ofMinSize(2).ofMaxSize(6);
    }

    /** Curated place names (with varied casing/whitespace) that the geocoder resolves. */
    @Provide
    Arbitrary<String> knownPlace() {
        return Arbitraries.of(KNOWN_PLACES).map(name -> {
            // Exercise the case-insensitive, whitespace-tolerant lookup.
            switch (Math.floorMod(name.hashCode(), 4)) {
                case 0: return name.toUpperCase(java.util.Locale.ROOT);
                case 1: return name.toLowerCase(java.util.Locale.ROOT);
                case 2: return "  " + name + "  ";
                default: return name;
            }
        });
    }

    /**
     * Arbitrary free-text queries: a mix of the curated names and random strings, so both the
     * resolvable and unresolvable paths are exercised.
     */
    @Provide
    Arbitrary<String> arbitraryQuery() {
        Arbitrary<String> random = Arbitraries.strings().ofMaxLength(30);
        Arbitrary<String> curated = Arbitraries.of(KNOWN_PLACES);
        return Arbitraries.oneOf(random, curated);
    }
}
