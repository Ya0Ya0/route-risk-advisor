package com.routeriskadvisor.provider.osm;

import com.fasterxml.jackson.databind.JsonNode;
import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteSegment;
import com.routeriskadvisor.provider.ProviderException;
import com.routeriskadvisor.provider.RoutingProvider;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Real {@link RoutingProvider} backed by the keyless OpenStreetMap OSRM public routing service.
 *
 * <p>Issues {@code GET /route/v1/driving/{lon,lat;lon,lat;...}?overview=full&geometries=geojson}
 * (with {@code alternatives=true} for the candidate flow) and parses the JSON response. OSRM
 * coordinate order is {@code lon,lat} both in the request path and in the returned geometry.
 * Each returned route is mapped to a {@link Route}:
 * <ul>
 *   <li>{@code geometry.coordinates} is the ordered list of {@code [lon,lat]} points; consecutive
 *       points are paired into {@link RouteSegment}s so the per-segment risk providers have
 *       meaningful legs to sample. Each segment's distance is the haversine between its two
 *       points.</li>
 *   <li>{@code distance} is the total route distance in meters (fallback: summed segment
 *       distances).</li>
 *   <li>{@code providerIndex} is the route's position in the response (0, 1, 2, ...), preserving
 *       OSRM order as the "earliest returned" tie-break key.</li>
 * </ul>
 *
 * <p>Failure policy: a no-route outcome (top-level {@code code} of {@code NoRoute},
 * {@code NoSegment}, or {@code InvalidQuery}, or an empty {@code routes} array) resolves to
 * {@link Optional#empty()} / an empty list; a read/connect timeout throws
 * {@link ProviderException.Kind#TIMEOUT}; any other transport/parse or non-2xx error throws
 * {@link ProviderException.Kind#FAILURE}.
 *
 * <p>Bean registration is centralized in {@code ProviderConfiguration} via
 * {@code @ConditionalOnProperty} on {@code route-risk-advisor.providers.routing = osrm}.
 */
public class OsrmRoutingProvider implements RoutingProvider {

    /** Mean Earth radius in meters, used for the haversine segment distance. */
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private final RestClient restClient;

    /**
     * @param baseUrl        OSRM base URL (e.g. {@code https://router.project-osrm.org})
     * @param connectTimeout connect timeout for the underlying HTTP client
     * @param readTimeout    read timeout for the underlying HTTP client (the routing budget)
     */
    public OsrmRoutingProvider(String baseUrl, Duration connectTimeout, Duration readTimeout) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(connectTimeout)
            .withReadTimeout(readTimeout);
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactories.get(settings);
        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .defaultHeader(HttpHeaders.ACCEPT, "application/json")
            .build();
    }

    @Override
    public Optional<Route> route(GeoCoordinate origin, GeoCoordinate destination)
        throws ProviderException {
        if (origin == null || destination == null) {
            return Optional.empty();
        }
        List<Route> routes = requestRoutes(List.of(origin, destination), false);
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
     * Issues the OSRM directions request for the ordered points and maps each returned route to a
     * {@link Route}. When {@code withAlternatives} is true, alternative routes are requested.
     */
    private List<Route> requestRoutes(List<GeoCoordinate> orderedPoints, boolean withAlternatives)
        throws ProviderException {
        String coordinatePath = buildCoordinatePath(orderedPoints);

        JsonNode body;
        try {
            body = restClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/route/v1/driving/")
                    .path(coordinatePath)
                    .queryParam("overview", "full")
                    .queryParam("geometries", "geojson")
                    .queryParam("alternatives", withAlternatives)
                    .build())
                .retrieve()
                .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            // A 400 carrying a no-route code (InvalidQuery / NoSegment / NoRoute) is a normal
            // "cannot route" outcome, not a failure.
            if (e.getStatusCode().value() == 400 && isNoRouteBody(e.getResponseBodyAsString())) {
                return List.of();
            }
            throw new ProviderException(
                "OSRM routing returned status " + e.getStatusCode() + ".",
                ProviderException.Kind.FAILURE, e);
        } catch (RestClientException e) {
            throw classifyTransportError(e);
        }

        if (body == null) {
            return List.of();
        }

        String code = body.path("code").asText("");
        if (!"Ok".equals(code)) {
            // NoRoute / NoSegment / InvalidQuery etc. -> no-route outcome.
            return List.of();
        }

        JsonNode routes = body.path("routes");
        if (!routes.isArray() || routes.isEmpty()) {
            return List.of();
        }

        List<Route> mapped = new ArrayList<>(routes.size());
        for (int i = 0; i < routes.size(); i++) {
            Route route = mapRoute(routes.get(i), i);
            if (route != null) {
                mapped.add(route);
            }
        }
        return mapped;
    }

    /** Builds the OSRM coordinate path {@code lon,lat;lon,lat;...} in order (OSRM order is lon,lat). */
    private String buildCoordinatePath(List<GeoCoordinate> orderedPoints) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < orderedPoints.size(); i++) {
            GeoCoordinate point = orderedPoints.get(i);
            if (i > 0) {
                sb.append(';');
            }
            sb.append(String.format(Locale.ROOT, "%s,%s", point.longitude(), point.latitude()));
        }
        return sb.toString();
    }

    /** Best-effort check for a no-route error code inside a 400 response body. */
    private boolean isNoRouteBody(String responseBody) {
        if (responseBody == null) {
            return false;
        }
        return responseBody.contains("NoRoute")
            || responseBody.contains("NoSegment")
            || responseBody.contains("InvalidQuery");
    }

    /**
     * Maps one OSRM route object to a {@link Route}: pairs consecutive geometry points into segments
     * and reads the total distance from the route's {@code distance} field. Returns {@code null}
     * when the route lacks a usable geometry.
     */
    private Route mapRoute(JsonNode routeNode, int providerIndex) {
        JsonNode coordinates = routeNode.path("geometry").path("coordinates");
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

        double totalDistanceMeters = routeNode.path("distance")
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
        if (isTimeout(e)) {
            return new ProviderException(
                "OSRM routing timed out.", ProviderException.Kind.TIMEOUT, e);
        }
        return new ProviderException(
            "OSRM routing call failed.", ProviderException.Kind.FAILURE, e);
    }

    /** Walks the cause chain looking for a {@link SocketTimeoutException}. */
    private static boolean isTimeout(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
