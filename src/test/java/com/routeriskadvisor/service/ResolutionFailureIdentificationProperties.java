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
import java.util.Set;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for {@link RouteRiskService} resolution-failure reporting.
 *
 * <p>Implements design correctness Property 2: when one or both locations cannot be resolved to
 * coordinates by the Geocoding_Provider, the flow aborts with a {@link LocationNotResolvedException}
 * whose {@link RouteRiskException#field()} names the location that could not be resolved. The
 * service evaluates the origin before the destination, so when both are unresolvable the origin —
 * the first location encountered in evaluation order — is the one reported and destination
 * geocoding is never reached.
 *
 * <p><strong>Validates: Requirements 1.4</strong>
 */
class ResolutionFailureIdentificationProperties {

    /** A coordinate inside the Miami-Dade Service_Area so a resolved location passes containment. */
    private static final GeoCoordinate IN_AREA = new GeoCoordinate(25.7617, -80.1918);

    // Feature: route-risk-advisor, Property 2: Resolution failure identifies the failing location
    @Property(tries = 200)
    void unresolvedLocationIsIdentifiedByName(@ForAll("resolutionScenarios") Scenario scenario) {
        RecordingRoutingProvider routing = new RecordingRoutingProvider();
        RouteRiskService service = new RouteRiskService(
            new ScriptedGeocodingProvider(scenario),
            routing,
            new ServiceAreaValidator(),
            classificationNeverReached(),
            recommendationNeverReached(),
            new TimeoutExecutor(),
            new TimeoutProperties());

        // The origin is geocoded before the destination, so the first unresolved location in
        // evaluation order is the one that must be named.
        String expectedField = !scenario.originResolvable
            ? RouteRiskService.FIELD_ORIGIN
            : RouteRiskService.FIELD_DESTINATION;

        assertThatThrownBy(() -> service.classify(scenario.origin, scenario.destination))
            .as("an unresolvable location must abort with LocationNotResolvedException naming %s "
                + "for scenario %s", expectedField, scenario)
            .isInstanceOf(LocationNotResolvedException.class)
            .extracting(e -> ((LocationNotResolvedException) e).field())
            .isEqualTo(expectedField);

        // When the origin cannot be resolved the destination is never geocoded, so a route is
        // never requested regardless of which location failed.
        assertThat(routing.routeCalls)
            .as("routing must not be requested when a location cannot be resolved: %s", scenario)
            .isZero();
    }

    /**
     * Generates origin/destination pairs where at least one location is unresolvable. Each input
     * string is non-blank and at most 250 characters so input validation always passes and the
     * flow reaches geocoding.
     */
    @Provide
    Arbitrary<Scenario> resolutionScenarios() {
        Arbitrary<String> validInput = Arbitraries.strings()
            .withCharRange('a', 'z')
            .withChars(' ', '0', '9')
            .ofMinLength(1)
            .ofMaxLength(250)
            .filter(s -> !s.trim().isEmpty());

        // (originResolvable, destinationResolvable) with at least one false — the interesting
        // space for a resolution failure.
        Arbitrary<boolean[]> flags = Combinators
            .combine(Arbitraries.of(true, false), Arbitraries.of(true, false))
            .as((origin, destination) -> new boolean[] {origin, destination})
            .filter(f -> !(f[0] && f[1]));

        return Combinators.combine(validInput, validInput, flags)
            // Distinct strings so the scripted geocoder, which keys on the input text, can return a
            // different outcome per location.
            .as((origin, destination, f) -> new Scenario(origin, destination, f[0], f[1]))
            .filter(s -> !s.origin.equals(s.destination));
    }

    private static RouteClassifier classificationNeverReached() {
        return route -> {
            throw new AssertionError("classification must not be reached on a resolution failure");
        };
    }

    private static InsuranceAdvisor recommendationNeverReached() {
        return classification -> {
            throw new AssertionError("recommendation must not be reached on a resolution failure");
        };
    }

    /** One generated case: the two input strings and whether each resolves to coordinates. */
    record Scenario(String origin, String destination, boolean originResolvable, boolean destinationResolvable) {
        @Override
        public String toString() {
            return "Scenario[originResolvable=" + originResolvable
                + ", destinationResolvable=" + destinationResolvable + "]";
        }
    }

    /**
     * A test-double Geocoding_Provider that returns an in-area coordinate for locations the
     * scenario marks resolvable and {@link Optional#empty()} for those it marks unresolvable.
     */
    private static final class ScriptedGeocodingProvider implements GeocodingProvider {
        private final Scenario scenario;

        ScriptedGeocodingProvider(Scenario scenario) {
            this.scenario = scenario;
        }

        @Override
        public Optional<GeoCoordinate> geocode(String locationDescription) {
            boolean resolvable = locationDescription.equals(scenario.origin)
                ? scenario.originResolvable
                : scenario.destinationResolvable;
            return resolvable ? Optional.of(IN_AREA) : Optional.empty();
        }
    }

    /** A Routing_Provider that records whether it was ever asked for a route. */
    private static final class RecordingRoutingProvider implements RoutingProvider {
        private int routeCalls;

        @Override
        public Optional<Route> route(GeoCoordinate origin, GeoCoordinate destination) {
            routeCalls++;
            return Optional.empty();
        }

        @Override
        public List<Route> candidateRoutes(List<GeoCoordinate> orderedPoints) {
            return List.of();
        }
    }
}
