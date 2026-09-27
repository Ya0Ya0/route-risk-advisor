package com.routeriskadvisor.provider.ors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteSegment;
import com.routeriskadvisor.provider.ProviderException;
import com.routeriskadvisor.provider.RoutingProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Real {@link RoutingProvider} backed by the OpenRouteService (ORS) Directions API.
 *
 * <p>Issues {@code POST /v2/directions/driving-car/geojson} with a body of
 * {@code {"coordinates":[[lon,lat],...]}} and parses the returned GeoJSON. Each returned feature is
 * mapped to a {@link Route}:
 * <ul>
 *   <li>{@code geometry.coordinates} is the ordered list of {@code [lon,lat]} points along the
 *       route; consecutive points are paired into {@link RouteSegment}s so the per-segment risk
 *       providers have meaningful legs to sample. Each segment's distance is the haversine between
 *       its two points.</li>
 *   <li>{@code properties.summary.distance} is the total route distance in meters.</li>
 *   <li>{@code providerIndex} is the feature's position in the response (0, 1, 2, ...), preserving
 *       ORS order as the "earliest returned" tie-break key.</li>
 * </ul>
 *
 * <p>{@link #candidateRoutes(List)} additionally requests alternatives
 * ({@code alternative_routes: {target_count, share_factor, weight_factor}}); ORS returns multiple
 * features when alternatives exist and a single feature otherwise.
 *
 * <p>Failure policy: a no-route outcome (a 404-style routable-point failure, or an empty feature
 * list) resolves to {@link Optional#empty()} / an empty list; a read timeout throws
 * {@link ProviderException.Kind#TIMEOUT}; any other transport/parse or non-2xx error throws
 * {@link ProviderException.Kind#FAILURE}.
 *
 * <p>Bean registration is centralized in {@code ProviderConfiguration} via
 * {@code @ConditionalOnProperty} on {@code route-risk-advisor.providers.routing = openrouteservice}.
 */
public class OpenRouteServiceRoutingProvider implements RoutingProvider {

    /** Mean Earth radius in meters, used for the haversine segment distance. */
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    /** Number of alternative routes requested in the safest-route flow. */
    private static final int ALTERNATIVE_TARGET_COUNT = 3;
    private static final double ALTERNATIVE_SHARE_FACTOR = 0.6;
    private static final double ALTERNATIVE_WEIGHT_FACTOR = 1.6;

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param baseUrl        ORS base URL (e.g. {@code https://api.openrouteservice.org})
     * @param apiKey         ORS API key, sent in the {@code Authorization} header
     * @param connectTimeout connect timeout for the underlying HTTP client
     * @param readTimeout    read timeout for the underlying HTTP client (the routing budget)
     */
    public OpenRouteServiceRoutingProvider(
        String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(connectTimeout)
            .withReadTimeout(readTimeout);
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactories.get(settings);
        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .defaultHeader(HttpHeaders.AUTHORIZATION, apiKey == null ? "" : apiKey)
            .defaultHeader(HttpHeaders.ACCEPT, "application/geo+json, application/json")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }

    @Override
    public Optional<Route> route(GeoCoordinate origin, GeoCoordinate destination)
        throws ProviderException {
        if (origin == null || destination == null) {
            return Optional.empty();
        }
        List<GeoCoordinate> points = List.of(origin, destination);
        List<Route> routes = requestRoutes(points, false);
        return routes.isEmpty() ? Optional.empty() : Optional.of(routes.get(0));
    }

    @Override
    public List<Route> candidateRoutes(List<GeoCoordinate> orderedPoints) throws ProviderException {
        if (orderedPoints == null || orderedPoints.size() < 2) {
            return List.of();
        }
        return requestRoutes(orderedPoints, true);
    }

    /**
     * Posts a directions request for the ordered points and maps each returned feature to a
     * {@link Route}. When {@code withAlternatives} is true, alternative routes are requested (valid
     * only for two-point requests per the ORS API; for multi-point requests ORS returns a single
     * optimal route).
     */
    private List<Route> requestRoutes(List<GeoCoordinate> orderedPoints, boolean withAlternatives)
        throws ProviderException {
        String requestBody = buildRequestBody(orderedPoints, withAlternatives && orderedPoints.size() == 2);

        JsonNode body;
        try {
            body = restClient.post()
                .uri("/v2/directions/driving-car/geojson")
                .body(requestBody)
                .retrieve()
                .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            // ORS returns a 404 (with an error code) when no route can be found between the points.
            if (e.getStatusCode().value() == 404) {
                return List.of();
            }
            throw new ProviderException(
                "OpenRouteService routing returned status " + e.getStatusCode() + ".",
                ProviderException.Kind.FAILURE, e);
        } catch (RestClientException e) {
            throw classifyTransportError(e);
        }

        if (body == null) {
            return List.of();
        }

        JsonNode features = body.path("features");
        if (!features.isArray() || features.isEmpty()) {
            return List.of();
        }

        List<Route> routes = new ArrayList<>(features.size());
        for (int i = 0; i < features.size(); i++) {
            Route route = mapFeatureToRoute(features.get(i), i);
            if (route != null) {
                routes.add(route);
            }
        }
        return routes;
    }

    /** Builds the JSON request body for the ORS directions endpoint. */
    private String buildRequestBody(List<GeoCoordinate> orderedPoints, boolean withAlternatives)
        throws ProviderException {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode coordinates = root.putArray("coordinates");
        for (GeoCoordinate point : orderedPoints) {
            ArrayNode pair = coordinates.addArray();
            pair.add(point.longitude());
            pair.add(point.latitude());
        }
        if (withAlternatives) {
            ObjectNode alternatives = root.putObject("alternative_routes");
            alternatives.put("target_count", ALTERNATIVE_TARGET_COUNT);
            alternatives.put("share_factor", ALTERNATIVE_SHARE_FACTOR);
            alternatives.put("weight_factor", ALTERNATIVE_WEIGHT_FACTOR);
        }
        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new ProviderException(
                "Failed to serialize OpenRouteService routing request.",
                ProviderException.Kind.FAILURE, e);
        }
    }

    /**
     * Maps one GeoJSON feature to a {@link Route}: pairs consecutive geometry points into segments
     * and reads the total distance from {@code properties.summary.distance}. Returns {@code null}
     * when the feature lacks a usable geometry.
     */
    private Route mapFeatureToRoute(JsonNode feature, int providerIndex) {
        JsonNode coordinates = feature.path("geometry").path("coordinates");
        if (!coordinates.isArray() || coordinates.size() < 2) {
            return null;
        }

        List<GeoCoordinate> points = new ArrayList<>(coordinates.size());
        for (JsonNode pair : coordinates) {
            if (pair.isArray() && pair.size() >= 2) {
                double longitude = pair.get(0).asDouble();
                double latitude = pair.get(1).asDouble();
                points.add(new GeoCoordinate(latitude, longitude));
            }
        }
        if (points.size() < 2) {
            return null;
        }

        List<RouteSegment> segments = new ArrayList<>(points.size() - 1);
        for (int i = 0; i < points.size() - 1; i++) {
            GeoCoordinate start = points.get(i);
            GeoCoordinate end = points.get(i + 1);
            double distance = haversineMeters(start, end);
            segments.add(new RouteSegment(
                "r" + providerIndex + "-s" + i, start, end, distance));
        }

        double totalDistanceMeters = feature.path("properties").path("summary").path("distance")
            .asDouble(sumSegmentDistances(segments));

        return new Route("route-" + providerIndex, providerIndex, segments, totalDistanceMeters);
    }

    private double sumSegmentDistances(List<RouteSegment> segments) {
        double total = 0.0;
        for (RouteSegment segment : segments) {
            total += segment.distanceMeters();
        }
        return total;
    }

    private ProviderException classifyTransportError(RestClientException e) {
        if (OpenRouteServiceGeocodingProvider.isTimeout(e)) {
            return new ProviderException(
                "OpenRouteService routing timed out.", ProviderException.Kind.TIMEOUT, e);
        }
        return new ProviderException(
            "OpenRouteService routing call failed.", ProviderException.Kind.FAILURE, e);
    }

    /** Great-circle distance in meters between two coordinates via the haversine formula. */
    private double haversineMeters(GeoCoordinate a, GeoCoordinate b) {
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double dLat = Math.toRadians(b.latitude() - a.latitude());
        double dLon = Math.toRadians(b.longitude() - a.longitude());

        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
        return EARTH_RADIUS_METERS * c;
    }
}
