package com.routeriskadvisor.service;

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

import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Orchestrates the Route Risk Classification flow (Requirement 1, feeding Requirements 2, 3, 6).
 *
 * <p>The flow follows the design's "Route Risk Classification Flow" sequence with the per-step
 * time budgets enforced at this service layer via {@link TimeoutExecutor} (design:
 * "Timeout enforcement"):
 * <ol>
 *   <li>Validate that each location string, after trimming, is non-empty and at most 250
 *       characters. Rejection is pure — no provider is called and no state is mutated — and names
 *       the invalid field, letting the caller retain prior values (Req 1.2, 6.5).</li>
 *   <li>Geocode both locations, each within a 10s budget; an unresolved location is reported by
 *       name (Req 1.4) and a geocoding timeout/failure aborts as temporarily unavailable
 *       (Req 1.8).</li>
 *   <li>Enforce Service_Area containment within a 2s budget; an out-of-area location is rejected
 *       with the Miami-Dade-only message and no route is requested (Req 1.5, 6.2, 6.3).</li>
 *   <li>Short-circuit identical origin/destination coordinates without requesting a route
 *       (Req 1.7).</li>
 *   <li>Request the route within a 10s budget; absence yields the no-route message (Req 1.6) and a
 *       timeout/failure aborts as temporarily unavailable (Req 1.8).</li>
 *   <li>Classify the route within a 5s budget (Req 2.6, 2.10) then produce insurance
 *       recommendations within a 2s budget (Req 3.1).</li>
 * </ol>
 *
 * <p>Business outcomes are surfaced as {@link RouteRiskException} subclasses the future REST
 * controller (task 16.1) maps to the {@code { code, message, field? }} error envelope; the
 * success path returns a {@link RouteClassificationResult}. This service does not expose an HTTP
 * endpoint.
 */
@Service
public class RouteRiskService {

    /** Requirement 1.1/1.2: locations must contain 1..250 non-whitespace characters. */
    private static final int MIN_LENGTH = 1;
    private static final int MAX_LENGTH = 250;

    static final String FIELD_ORIGIN = "origin";
    static final String FIELD_DESTINATION = "destination";

    private final GeocodingProvider geocodingProvider;
    private final RoutingProvider routingProvider;
    private final ServiceAreaValidator serviceAreaValidator;
    private final RouteClassifier routeClassifier;
    private final InsuranceAdvisor insuranceAdvisor;
    private final TimeoutExecutor timeoutExecutor;
    private final TimeoutProperties timeouts;

    public RouteRiskService(
        GeocodingProvider geocodingProvider,
        RoutingProvider routingProvider,
        ServiceAreaValidator serviceAreaValidator,
        RouteClassifier routeClassifier,
        InsuranceAdvisor insuranceAdvisor,
        TimeoutExecutor timeoutExecutor,
        TimeoutProperties timeouts
    ) {
        this.geocodingProvider = geocodingProvider;
        this.routingProvider = routingProvider;
        this.serviceAreaValidator = serviceAreaValidator;
        this.routeClassifier = routeClassifier;
        this.insuranceAdvisor = insuranceAdvisor;
        this.timeoutExecutor = timeoutExecutor;
        this.timeouts = timeouts;
    }

    /**
     * Runs the full classification flow for an origin/destination pair.
     *
     * @param origin      the free-text origin location
     * @param destination the free-text destination location
     * @return the classification and insurance recommendation for the resolved route
     * @throws InvalidLocationInputException if either input is blank or longer than 250 chars
     * @throws LocationNotResolvedException   if either location cannot be geocoded
     * @throws OutOfServiceAreaException      if either resolved location is outside Miami-Dade
     * @throws SameLocationException          if the resolved coordinates are identical
     * @throws NoRouteFoundException          if no route exists between the coordinates
     * @throws ServiceUnavailableException    if geocoding/routing/classification times out or fails
     */
    public RouteClassificationResult classify(String origin, String destination)
        throws RouteRiskException {

        // Step 1 — pure input validation (Req 1.2, 6.5). No provider is called and no state is
        // mutated before both inputs are known valid.
        validateInput(origin, FIELD_ORIGIN);
        validateInput(destination, FIELD_DESTINATION);

        // Step 2 — geocode both locations within the 10s budget each (Req 1.1, 1.4, 1.8).
        GeoCoordinate originCoord = geocode(origin, FIELD_ORIGIN);
        GeoCoordinate destinationCoord = geocode(destination, FIELD_DESTINATION);

        // Step 3 — Service_Area containment within the 2s budget (Req 1.5, 6.2, 6.3).
        enforceServiceArea(originCoord, FIELD_ORIGIN);
        enforceServiceArea(destinationCoord, FIELD_DESTINATION);

        // Step 4 — identical coordinates short-circuit routing entirely (Req 1.7).
        if (originCoord.equals(destinationCoord)) {
            throw new SameLocationException(
                "The origin and destination are the same location.");
        }

        // Step 5 — request the route within the 10s budget (Req 1.3, 1.6, 1.8).
        Route route = requestRoute(originCoord, destinationCoord);

        // Step 6 — classify (5s) then recommend insurance (2s).
        RouteClassification classification = classifyWithinBudget(route);
        InsuranceRecommendationResult recommendation = recommendWithinBudget(classification);

        return new RouteClassificationResult(
            originCoord, destinationCoord, route, classification, recommendation);
    }

    /** Validates one location string in place; pure, throws before any side effect (Req 1.2, 6.5). */
    private static void validateInput(String value, String field) throws InvalidLocationInputException {
        if (value == null || value.trim().isEmpty()) {
            throw new InvalidLocationInputException(
                field, "The " + field + " location must not be empty.");
        }
        // "non-whitespace characters" — length is measured on the trimmed value (Req 1.1).
        int length = value.trim().length();
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new InvalidLocationInputException(
                field, "The " + field + " location must be between 1 and 250 characters.");
        }
    }

    /** Geocodes one location within the geocoding budget, mapping outcomes to the flow's errors. */
    private GeoCoordinate geocode(String location, String field) throws RouteRiskException {
        Optional<GeoCoordinate> resolved;
        try {
            resolved = timeoutExecutor.callWithin(
                () -> geocodingProvider.geocode(location), timeouts.getGeocodingMs(), "geocoding");
        } catch (ProviderException e) {
            // Timeout or failure from the Geocoding_Provider aborts the request (Req 1.8).
            throw new ServiceUnavailableException(
                "The location service is temporarily unavailable. Please try again later.");
        }
        return resolved.orElseThrow(() -> new LocationNotResolvedException(
            field, "The " + field + " location could not be resolved to a place: \"" + location + "\"."));
    }

    /** Rejects a coordinate outside the Service_Area within the 2s budget (Req 1.5, 6.2, 6.3). */
    private void enforceServiceArea(GeoCoordinate coordinate, String field) throws RouteRiskException {
        boolean within;
        try {
            within = timeoutExecutor.callWithin(
                () -> serviceAreaValidator.contains(coordinate),
                timeouts.getServiceAreaMs(), "service-area");
        } catch (ProviderException e) {
            // A containment check that cannot complete in budget is treated as unavailable rather
            // than silently accepting an unverified location.
            throw new ServiceUnavailableException(
                "The service area check is temporarily unavailable. Please try again later.");
        }
        if (!within) {
            throw new OutOfServiceAreaException(
                field, "The " + field + " location is outside the supported Miami-Dade County service area.");
        }
    }

    /** Requests the route within the routing budget (Req 1.3, 1.6, 1.8). */
    private Route requestRoute(GeoCoordinate origin, GeoCoordinate destination) throws RouteRiskException {
        Optional<Route> route;
        try {
            route = timeoutExecutor.callWithin(
                () -> routingProvider.route(origin, destination), timeouts.getRoutingMs(), "routing");
        } catch (ProviderException e) {
            // Timeout or failure from the Routing_Provider aborts the request (Req 1.8).
            throw new ServiceUnavailableException(
                "The route service is temporarily unavailable. Please try again later.");
        }
        return route.orElseThrow(() -> new NoRouteFoundException(
            "No route was found between the origin and destination."));
    }

    /**
     * Classifies the route within the 5s budget. Classification itself never throws for a
     * provider failure — it degrades individual categories to Unknown (Req 2.9) — so the only
     * failure here is exceeding the whole-classification budget, in which case the partial result
     * is discarded and reported as a timeout (Req 2.10, mapped to temporarily unavailable).
     */
    private RouteClassification classifyWithinBudget(Route route) throws RouteRiskException {
        try {
            return timeoutExecutor.callWithin(
                () -> routeClassifier.classify(route), timeouts.getClassifyMs(), "classification");
        } catch (ProviderException e) {
            throw new ServiceUnavailableException(
                "Route risk classification did not complete in time. Please try again later.");
        }
    }

    /** Produces insurance recommendations within the 2s budget (Req 3.1). */
    private InsuranceRecommendationResult recommendWithinBudget(RouteClassification classification)
        throws RouteRiskException {
        try {
            return timeoutExecutor.callWithin(
                () -> insuranceAdvisor.recommend(classification), timeouts.getRecommendMs(), "recommendation");
        } catch (ProviderException e) {
            throw new ServiceUnavailableException(
                "Insurance recommendation did not complete in time. Please try again later.");
        }
    }
}
