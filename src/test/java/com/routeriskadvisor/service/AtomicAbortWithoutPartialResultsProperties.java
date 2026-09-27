package com.routeriskadvisor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.routeriskadvisor.domain.model.GeoCoordinate;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based test for {@link SafestRouteService} atomic-abort behaviour.
 *
 * <p>Implements design correctness Property 15: for any safest-route comparison in which the
 * Routing_Provider returns no candidates (or fails) or the Route_Classifier fails for any
 * candidate, the flow aborts with a {@link SafestRouteException} and produces no
 * {@link SafestRouteResult} — never a partial per-category recommendation. The outcome is
 * all-or-nothing (design "Error Handling", Req 4.8, 4.9).
 *
 * <p>The test drives the real {@link SafestRouteService} with an in-area location list of a valid
 * size (2..25) and one injected failure mode:
 * <ul>
 *   <li><b>EMPTY_ROUTES</b> — the Routing_Provider returns an empty candidate list, which must
 *       abort with {@link SafestRouteException.Reason#NO_ROUTES_RETRIEVED} (Req 4.8);</li>
 *   <li><b>ROUTING_FAILS</b> — the Routing_Provider throws a {@link ProviderException}, which must
 *       abort with {@link SafestRouteException.Reason#NO_ROUTES_RETRIEVED} (Req 4.8);</li>
 *   <li><b>CLASSIFIER_FAILS</b> — a risk-data provider raises an unchecked failure while a
 *       candidate segment is being classified, so classification of that candidate fails and the
 *       comparison must abort with {@link SafestRouteException.Reason#ROUTE_RISK_UNEVALUATED}
 *       (Req 4.9).</li>
 * </ul>
 *
 * <p>Small timeout budgets are used so the bounded-wait paths stay fast. In every case the test
 * asserts that {@code findSafestRoute} throws the typed exception with the expected reason and that
 * no {@link SafestRouteResult} escapes the call — i.e. no partial recommendation is produced.
 *
 * <p><strong>Validates: Requirements 4.8, 4.9</strong>
 */
class AtomicAbortWithoutPartialResultsProperties {

    private static final long ROUTING_BUDGET_MILLIS = 500L;
    private static final long CLASSIFY_BUDGET_MILLIS = 500L;

    /** The three ways a comparison can be forced to abort under this property. */
    enum FailureMode {
        /** Routing_Provider returns an empty candidate list (Req 4.8). */
        EMPTY_ROUTES,
        /** Routing_Provider throws a ProviderException (Req 4.8). */
        ROUTING_FAILS,
        /** The classifier fails for a candidate route (Req 4.9). */
        CLASSIFIER_FAILS
    }

    // Feature: route-risk-advisor, Property 15: Comparison aborts atomically without partial results
    @Property(tries = 200)
    void abortsAtomicallyWithNoPartialResult(
            @ForAll("locationLists") List<GeoCoordinate> locations,
            @ForAll FailureMode failureMode) {
        SafestRouteException.Reason expectedReason =
            failureMode == FailureMode.CLASSIFIER_FAILS
                ? SafestRouteException.Reason.ROUTE_RISK_UNEVALUATED
                : SafestRouteException.Reason.NO_ROUTES_RETRIEVED;

        SafestRouteService service = new SafestRouteService(
            routingFor(failureMode),
            crashFor(failureMode),
            crimeFor(failureMode),
            fireFor(failureMode),
            ROUTING_BUDGET_MILLIS,
            CLASSIFY_BUDGET_MILLIS);

        // Capture any (erroneously) returned result so we can prove none escaped the call.
        AtomicReference<SafestRouteResult> escaped = new AtomicReference<>();

        assertThatThrownBy(() -> escaped.set(service.findSafestRoute(locations)))
            .as("failure mode %s over %d locations must abort the comparison",
                failureMode, locations.size())
            .isInstanceOfSatisfying(SafestRouteException.class, ex ->
                assertThat(ex.reason())
                    .as("abort reason for %s", failureMode)
                    .isEqualTo(expectedReason));

        // Atomic: no SafestRouteResult (and therefore no per-category recommendation) is produced.
        assertThat(escaped.get())
            .as("no partial SafestRouteResult may escape an aborted comparison (%s)", failureMode)
            .isNull();
    }

    /**
     * In-area Miami-Dade location lists of a valid size (2..25) so the count and Service_Area
     * checks always pass and the flow reaches the routing/classification stage where the injected
     * failure takes effect.
     */
    @Provide
    Arbitrary<List<GeoCoordinate>> locationLists() {
        return Arbitraries.integers().between(
                SafestRouteService.MIN_LOCATIONS, SafestRouteService.MAX_LOCATIONS)
            .flatMap(size -> inAreaCoordinate().list().ofSize(size));
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
     * A routing provider matched to the failure mode: empty list, a thrown failure, or a couple of
     * valid in-area candidate routes (so the classifier stage is reached for CLASSIFIER_FAILS).
     */
    private static RoutingProvider routingFor(FailureMode mode) {
        return new RoutingProvider() {
            @Override
            public Optional<Route> route(GeoCoordinate origin, GeoCoordinate destination) {
                return Optional.empty();
            }

            @Override
            public List<Route> candidateRoutes(List<GeoCoordinate> orderedPoints)
                    throws ProviderException {
                switch (mode) {
                    case EMPTY_ROUTES:
                        return List.of();
                    case ROUTING_FAILS:
                        throw new ProviderException(
                            "routing failed", ProviderException.Kind.FAILURE);
                    case CLASSIFIER_FAILS:
                    default:
                        return sampleCandidates();
                }
            }
        };
    }

    /** Two valid in-area candidate routes, each with a single segment. */
    private static List<Route> sampleCandidates() {
        List<Route> routes = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            RouteSegment segment = new RouteSegment(
                "seg-" + i,
                new GeoCoordinate(25.7617, -80.1918),
                new GeoCoordinate(25.7215, -80.2684),
                8500.0);
            routes.add(new Route("route-" + i, i, List.of(segment), 8500.0));
        }
        return routes;
    }

    // For CLASSIFIER_FAILS the crash provider raises an unchecked failure while a segment is being
    // classified. That escapes DefaultRouteClassifier (which only degrades on the checked
    // ProviderException), so classification of the candidate fails and the service aborts with
    // ROUTE_RISK_UNEVALUATED (Req 4.9). For the routing-side modes the classifier is never reached,
    // so present observations keep the doubles well-formed.

    private static CrashDataProvider crashFor(FailureMode mode) {
        if (mode == FailureMode.CLASSIFIER_FAILS) {
            return segment -> {
                throw new RuntimeException("crash data lookup blew up for " + segment.id());
            };
        }
        return segment -> Optional.of(new com.routeriskadvisor.domain.model.RiskObservation(segment, 0.5, 3));
    }

    private static CrimeDataProvider crimeFor(FailureMode mode) {
        return segment -> Optional.of(new com.routeriskadvisor.domain.model.RiskObservation(segment, 0.5, 3));
    }

    private static FireDataProvider fireFor(FailureMode mode) {
        return segment -> Optional.of(new com.routeriskadvisor.domain.model.RiskObservation(segment, 0.5, 3));
    }
}
