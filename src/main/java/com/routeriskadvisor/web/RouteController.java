package com.routeriskadvisor.web;

import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.domain.model.SafestRouteResult;
import com.routeriskadvisor.provider.GeocodingProvider;
import com.routeriskadvisor.provider.ProviderException;
import com.routeriskadvisor.service.InvalidLocationInputException;
import com.routeriskadvisor.service.LocationNotResolvedException;
import com.routeriskadvisor.service.RouteClassificationResult;
import com.routeriskadvisor.service.RouteRiskException;
import com.routeriskadvisor.service.RouteRiskService;
import com.routeriskadvisor.service.SafestRouteException;
import com.routeriskadvisor.service.SafestRouteService;
import com.routeriskadvisor.service.ServiceUnavailableException;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * REST entry point for the Route Risk Advisor (design "REST Controllers").
 *
 * <ul>
 *   <li>{@code POST /api/routes/classify} — body {@code { origin, destination }}; returns a
 *       {@link RouteClassificationResult} (classification + insurance recommendations) or a
 *       structured error.</li>
 *   <li>{@code POST /api/routes/safest} — body {@code { locations: [...] }}; returns a
 *       {@link SafestRouteResult} (safest route per category) or a structured error.</li>
 * </ul>
 *
 * <p>The controller keeps orchestration in the application services: {@link RouteRiskService}
 * runs the classification flow end-to-end, and {@link SafestRouteService} runs the comparison
 * flow over resolved coordinates. Because {@link SafestRouteService#findSafestRoute} takes
 * coordinates while the endpoint accepts free-text locations, this controller geocodes each
 * location first — reusing the same free-text-to-coordinate rules as the classify flow
 * (1..250 length validation → {@link InvalidLocationInputException}; unresolved →
 * {@link LocationNotResolvedException}; provider timeout/failure →
 * {@link ServiceUnavailableException}). The 2..25 count rule (Req 4.6) is enforced <em>before</em>
 * any geocoding so an out-of-range submission never triggers a provider call.
 *
 * <p>Business outcomes are thrown as typed exceptions ({@link RouteRiskException} and
 * {@link SafestRouteException}) and mapped to the shared {@code { code, message, field? }} envelope
 * with the correct HTTP status by {@link ApiExceptionHandler}; the controller itself contains no
 * error-to-status mapping.
 */
@RestController
@RequestMapping(path = "/api/routes", produces = MediaType.APPLICATION_JSON_VALUE)
public class RouteController {

    private final RouteRiskService routeRiskService;
    private final SafestRouteService safestRouteService;
    private final GeocodingProvider geocodingProvider;

    public RouteController(
        RouteRiskService routeRiskService,
        SafestRouteService safestRouteService,
        GeocodingProvider geocodingProvider
    ) {
        this.routeRiskService = routeRiskService;
        this.safestRouteService = safestRouteService;
        this.geocodingProvider = geocodingProvider;
    }

    /**
     * Classifies the route between a free-text origin and destination and returns the risk
     * classification plus insurance recommendations (Req 1.2, 1.4, 1.5, 1.6, 1.7, 1.8).
     *
     * @param request the {@code { origin, destination }} body
     * @return the classification result on success
     * @throws RouteRiskException a typed business outcome mapped to the error envelope
     */
    @PostMapping(path = "/classify", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RouteClassificationResult classify(@RequestBody ClassifyRequest request)
        throws RouteRiskException {
        String origin = request == null ? null : request.origin();
        String destination = request == null ? null : request.destination();
        return routeRiskService.classify(origin, destination);
    }

    /**
     * Compares candidate routes among the submitted locations and returns the safest route per
     * category (Req 4.6, 4.8, 4.9). Locations are geocoded first using the classify flow's rules;
     * the count is validated before geocoding so an out-of-range request requests no routes.
     *
     * @param request the {@code { locations: [...] }} body
     * @return the safest-route result on success
     * @throws RouteRiskException if a location is invalid, unresolved, or the geocoder is unavailable
     */
    @PostMapping(path = "/safest", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SafestRouteResult safest(@RequestBody SafestRouteRequest request)
        throws RouteRiskException {
        List<String> locations = request == null ? null : request.locations();

        // Enforce the 2..25 count rule before any geocoding so an out-of-range submission never
        // triggers a provider call (Req 4.6). SafestRouteService re-checks this on the resolved
        // coordinates, but doing it here keeps the "SHALL NOT request candidate Routes" guarantee
        // even before resolution.
        validateLocationCount(locations);

        List<GeoCoordinate> coordinates = geocodeAll(locations);
        return safestRouteService.findSafestRoute(coordinates);
    }

    /** Rejects a location list outside the 2..25 range without geocoding or routing (Req 4.6). */
    private static void validateLocationCount(List<String> locations) {
        int count = locations == null ? 0 : locations.size();
        if (count < SafestRouteService.MIN_LOCATIONS || count > SafestRouteService.MAX_LOCATIONS) {
            throw new SafestRouteException(
                SafestRouteException.Reason.LOCATION_COUNT_OUT_OF_RANGE,
                SafestRouteService.COUNT_MESSAGE,
                "locations");
        }
    }

    /**
     * Geocodes every free-text location into a coordinate, mirroring the classify flow: blank or
     * over-length inputs are rejected as invalid input (Req 1.2), unresolved locations are reported
     * by index (Req 1.4), and a geocoder timeout/failure aborts as temporarily unavailable
     * (Req 1.8). Service_Area containment is left to {@link SafestRouteService} so a single
     * authority enforces it (Req 6.3).
     */
    private List<GeoCoordinate> geocodeAll(List<String> locations) throws RouteRiskException {
        List<GeoCoordinate> coordinates = new ArrayList<>(locations.size());
        for (int i = 0; i < locations.size(); i++) {
            final String location = locations.get(i);
            final int position = i + 1;
            final String field = "locations[" + i + "]";
            if (location == null || location.trim().isEmpty()
                || location.trim().length() > MAX_LOCATION_LENGTH) {
                throw new InvalidLocationInputException(
                    field, "Location " + position + " must be between 1 and 250 characters.");
            }
            Optional<GeoCoordinate> resolved;
            try {
                resolved = geocodingProvider.geocode(location);
            } catch (ProviderException e) {
                throw new ServiceUnavailableException(
                    "The location service is temporarily unavailable. Please try again later.");
            }
            coordinates.add(resolved.orElseThrow(() -> new LocationNotResolvedException(
                field, "Location " + position + " could not be resolved to a place: \""
                    + location + "\".")));
        }
        return coordinates;
    }

    /** Maximum accepted location string length, mirroring the classify flow's 250-char rule (Req 1.2). */
    private static final int MAX_LOCATION_LENGTH = 250;
}
