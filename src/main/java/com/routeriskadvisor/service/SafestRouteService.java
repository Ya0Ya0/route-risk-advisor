package com.routeriskadvisor.service;

import com.routeriskadvisor.domain.DefaultRouteClassifier;
import com.routeriskadvisor.domain.DefaultRouteFinder;
import com.routeriskadvisor.domain.RiskScorer;
import com.routeriskadvisor.domain.RouteClassifier;
import com.routeriskadvisor.domain.RouteFinder;
import com.routeriskadvisor.domain.ServiceAreaValidator;
import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteClassification;
import com.routeriskadvisor.domain.model.SafestRouteResult;
import com.routeriskadvisor.provider.CrashDataProvider;
import com.routeriskadvisor.provider.CrimeDataProvider;
import com.routeriskadvisor.provider.FireDataProvider;
import com.routeriskadvisor.provider.RoutingProvider;
import com.routeriskadvisor.service.SafestRouteTimeoutSupport.BoundedWaitException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the Safest Route Finder flow (Requirement 4): given a set of locations it validates
 * the request, requests candidate routes, classifies each candidate, and returns the lowest-risk
 * route per risk category. The flow is <em>atomic</em> — it either returns a complete
 * {@link SafestRouteResult} or throws a {@link SafestRouteException} with no partial result
 * (design "Safest Route Finder Flow", "Error Handling", Property 15).
 *
 * <p>Sequence (design sequence diagram):
 * <ol>
 *   <li>reject fewer than 2 or more than 25 locations with the count message and request no routes
 *       (Req 4.6);</li>
 *   <li>resolve every location and confirm it lies within the Miami-Dade Service_Area, rejecting
 *       the request otherwise (Req 6.3);</li>
 *   <li>request candidate routes under a 10s budget, aborting with a no-routes-retrieved error when
 *       the provider returns nothing, times out, or fails (Req 4.1, 4.8);</li>
 *   <li>classify each candidate under a 10s budget, aborting with a route-risk-unevaluated error on
 *       any classifier failure or timeout (Req 4.2, 4.9);</li>
 *   <li>return the safest route per category via the {@link RouteFinder} (Req 4.3-4.5, 4.7).</li>
 * </ol>
 *
 * <p>Wiring: the service depends on the provider <em>interfaces</em> (Spring beans selected by
 * configuration, Req 5) and builds its pure domain collaborators — {@link RiskScorer},
 * {@link DefaultRouteClassifier}, {@link ServiceAreaValidator}, {@link DefaultRouteFinder} — from
 * them. Because {@link DefaultRouteFinder} classifies internally with no time budget, this service
 * enforces the per-candidate 10s budget itself and then feeds the finder pre-computed
 * classifications through a caching wrapper, so each candidate is classified exactly once
 * (Req 4.2) and every classification is bounded.
 *
 * <p>This class intentionally does not depend on any REST/web type; mapping the success result and
 * the typed {@link SafestRouteException} outcomes onto an HTTP response is the controller's job
 * (task 16.1).
 */
@Service
public class SafestRouteService {

    /** Inclusive lower bound on the number of submitted locations (Req 4.6). */
    public static final int MIN_LOCATIONS = 2;
    /** Inclusive upper bound on the number of submitted locations (Req 4.6). */
    public static final int MAX_LOCATIONS = 25;

    public static final String COUNT_MESSAGE =
        "Between " + MIN_LOCATIONS + " and " + MAX_LOCATIONS + " locations are required.";
    static final String OUT_OF_AREA_MESSAGE =
        "Only the Miami-Dade County service area is supported.";
    static final String NO_ROUTES_MESSAGE =
        "No routes could be retrieved.";
    static final String RISK_UNEVALUATED_MESSAGE =
        "Route risk could not be evaluated.";

    private final RoutingProvider routingProvider;
    private final ServiceAreaValidator serviceAreaValidator;
    private final RouteClassifier routeClassifier;
    private final RouteFinder routeFinder;
    private final SafestRouteTimeoutSupport timeoutSupport;

    private final long routingBudgetMillis;
    private final long classifyBudgetMillis;

    public SafestRouteService(
        RoutingProvider routingProvider,
        CrashDataProvider crashDataProvider,
        CrimeDataProvider crimeDataProvider,
        FireDataProvider fireDataProvider,
        @Value("${route-risk-advisor.timeouts.routing-ms:10000}") long routingBudgetMillis,
        @Value("${route-risk-advisor.timeouts.safest-classify-ms:10000}") long classifyBudgetMillis
    ) {
        if (routingProvider == null) {
            throw new IllegalArgumentException("routingProvider must not be null");
        }
        if (crashDataProvider == null || crimeDataProvider == null || fireDataProvider == null) {
            throw new IllegalArgumentException("data providers must not be null");
        }
        this.routingProvider = routingProvider;
        this.serviceAreaValidator = new ServiceAreaValidator();
        this.routeClassifier = new DefaultRouteClassifier(
            crashDataProvider, crimeDataProvider, fireDataProvider, new RiskScorer());
        // The finder classifies via whatever RouteClassifier it is given. We drive the actual,
        // time-bounded classification ourselves (below) and hand the finder a caching wrapper so
        // it reuses those results rather than re-invoking the classifier off the deadline.
        this.routeFinder = new DefaultRouteFinder(new CachingClassifier());
        this.timeoutSupport = new SafestRouteTimeoutSupport();
        this.routingBudgetMillis = routingBudgetMillis;
        this.classifyBudgetMillis = classifyBudgetMillis;
    }

    /**
     * Holds the pre-computed classifications for the candidates of the current request so the
     * caching wrapper can serve them to the finder. Keyed by route identity; scoped to the calling
     * thread because a single request runs on one thread.
     */
    private final ThreadLocal<Map<Route, RouteClassification>> classificationCache =
        ThreadLocal.withInitial(() -> new IdentityHashMap<>());

    /**
     * Finds the safest route per category among the routes connecting the submitted locations.
     *
     * @param locations the ordered locations to connect; must not be {@code null}
     * @return the safest route per Accident/Theft/Fire category (Req 4.4)
     * @throws SafestRouteException if the request is rejected (count/out-of-area) or the comparison
     *                              is aborted (no routes / risk unevaluated) — never a partial result
     */
    public SafestRouteResult findSafestRoute(List<GeoCoordinate> locations) {
        validateCount(locations);
        validateAllInServiceArea(locations);

        List<Route> candidates = requestCandidateRoutes(locations);
        Map<Route, RouteClassification> classifications = classifyAll(candidates);

        // Feed the finder the pre-computed, time-bounded classifications via the cache.
        classificationCache.get().clear();
        classificationCache.get().putAll(classifications);
        try {
            return routeFinder.findSafest(candidates);
        } finally {
            classificationCache.get().clear();
        }
    }

    /**
     * Rejects submissions outside the 2..25 range without requesting any routes (Req 4.6).
     */
    private void validateCount(List<GeoCoordinate> locations) {
        if (locations == null) {
            throw new SafestRouteException(
                SafestRouteException.Reason.LOCATION_COUNT_OUT_OF_RANGE, COUNT_MESSAGE, "locations");
        }
        int count = locations.size();
        if (count < MIN_LOCATIONS || count > MAX_LOCATIONS) {
            throw new SafestRouteException(
                SafestRouteException.Reason.LOCATION_COUNT_OUT_OF_RANGE, COUNT_MESSAGE, "locations");
        }
    }

    /**
     * Confirms every submitted location lies within the Miami-Dade Service_Area; rejects the whole
     * request if any location falls outside (Req 6.3). Runs before any routing is requested.
     */
    private void validateAllInServiceArea(List<GeoCoordinate> locations) {
        for (GeoCoordinate location : locations) {
            if (location == null || !serviceAreaValidator.contains(location)) {
                throw new SafestRouteException(
                    SafestRouteException.Reason.OUT_OF_SERVICE_AREA, OUT_OF_AREA_MESSAGE, "locations");
            }
        }
    }

    /**
     * Requests candidate routes under the 10s budget. Aborts with no-routes-retrieved when the
     * provider returns an empty list, times out, or fails (Req 4.1, 4.8).
     */
    private List<Route> requestCandidateRoutes(List<GeoCoordinate> locations) {
        List<Route> candidates;
        try {
            candidates = timeoutSupport.runWithin(
                () -> routingProvider.candidateRoutes(locations),
                routingBudgetMillis,
                "candidate routing");
        } catch (BoundedWaitException e) {
            // Both timeout and failure abort with the same no-routes-retrieved outcome (Req 4.8).
            throw new SafestRouteException(
                SafestRouteException.Reason.NO_ROUTES_RETRIEVED, NO_ROUTES_MESSAGE, null, e);
        }
        if (candidates == null || candidates.isEmpty()) {
            throw new SafestRouteException(
                SafestRouteException.Reason.NO_ROUTES_RETRIEVED, NO_ROUTES_MESSAGE);
        }
        return candidates;
    }

    /**
     * Classifies every candidate route, each under its own 10s budget (Req 4.2). Any timeout or
     * failure aborts the whole comparison with route-risk-unevaluated and yields no partial result
     * (Req 4.9, Property 15).
     */
    private Map<Route, RouteClassification> classifyAll(List<Route> candidates) {
        Map<Route, RouteClassification> classifications = new IdentityHashMap<>();
        for (Route candidate : candidates) {
            RouteClassification classification;
            try {
                classification = timeoutSupport.runWithin(
                    () -> routeClassifier.classify(candidate),
                    classifyBudgetMillis,
                    "route classification");
            } catch (BoundedWaitException e) {
                throw new SafestRouteException(
                    SafestRouteException.Reason.ROUTE_RISK_UNEVALUATED,
                    RISK_UNEVALUATED_MESSAGE, null, e);
            }
            classifications.put(candidate, classification);
        }
        return classifications;
    }

    /**
     * A {@link RouteClassifier} that returns the pre-computed classification for a candidate route
     * from the current request's cache. This lets {@link DefaultRouteFinder} reuse the time-bounded
     * classifications this service already produced instead of classifying again off the deadline.
     * If a route is ever missing from the cache it means the finder saw a route the service did not
     * classify — a programming error rather than a provider outcome — so it fails fast.
     */
    private final class CachingClassifier implements RouteClassifier {
        @Override
        public RouteClassification classify(Route route) {
            RouteClassification cached = classificationCache.get().get(route);
            if (cached == null) {
                throw new IllegalStateException(
                    "No pre-computed classification for route " + (route == null ? "null" : route.id()));
            }
            return cached;
        }
    }
}
