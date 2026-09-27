package com.routeriskadvisor.provider.placeholder;

import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteSegment;
import com.routeriskadvisor.provider.ProviderException;
import com.routeriskadvisor.provider.RoutingProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic placeholder {@link RoutingProvider}. It synthesizes routes between geographic
 * points without any network call (Requirement 5.3), producing plausible Miami-Dade distances
 * and stable {@code providerIndex} values.
 *
 * <p>A route is built by connecting the given ordered points into a chain of
 * {@link RouteSegment}s. To make each synthesized route look like a real road path (rather than
 * one straight line per leg), each leg is subdivided into a few intermediate points that gently
 * offset from the straight line; because those intermediate points are convex combinations of
 * two in-area endpoints (plus a small offset that stays well inside the county), every emitted
 * coordinate remains inside the Service_Area (Requirement 6.6).
 *
 * <p>{@link #candidateRoutes(List)} returns several alternative routes for the same ordered
 * points, each with a distinct, stable {@code providerIndex} (0, 1, 2, ...) preserving provider
 * order so it can serve as the "earliest returned" tie-break key (design Property 14 /
 * Requirement 4.7). Candidates differ in how much they detour, giving them different total
 * distances.
 *
 * <p>Bean registration is centralized in {@code ProviderConfiguration} via
 * {@code @ConditionalOnProperty} on {@code route-risk-advisor.providers.routing} (Req 5.4, 5.5),
 * so this class carries no Spring stereotype of its own.
 */
public class PlaceholderRoutingProvider implements RoutingProvider {

    /** Mean Earth radius in meters, used for the haversine distance. */
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    /** Number of alternative candidate routes synthesized for the safest-route flow. */
    private static final int CANDIDATE_COUNT = 3;

    /** Number of intermediate points inserted along each leg to shape a plausible path. */
    private static final int POINTS_PER_LEG = 3;

    /**
     * Small latitude/longitude offsets (in degrees) applied to intermediate points so different
     * candidates take different shapes. These are a fraction of the county's extent, so offset
     * points stay comfortably in-area. Index 0 produces a near-straight route.
     */
    private static final double[] CANDIDATE_OFFSETS = {0.0, 0.010, -0.010};

    @Override
    public Optional<Route> route(GeoCoordinate origin, GeoCoordinate destination) throws ProviderException {
        if (origin == null || destination == null) {
            return Optional.empty();
        }
        List<GeoCoordinate> points = List.of(origin, destination);
        return Optional.of(buildRoute(points, 0, CANDIDATE_OFFSETS[0]));
    }

    @Override
    public List<Route> candidateRoutes(List<GeoCoordinate> orderedPoints) throws ProviderException {
        if (orderedPoints == null || orderedPoints.size() < 2) {
            return List.of();
        }
        List<Route> routes = new ArrayList<>(CANDIDATE_COUNT);
        for (int i = 0; i < CANDIDATE_COUNT; i++) {
            routes.add(buildRoute(orderedPoints, i, CANDIDATE_OFFSETS[i % CANDIDATE_OFFSETS.length]));
        }
        return routes;
    }

    /**
     * Builds a single route with a stable {@code providerIndex} by chaining the ordered points
     * into segments, subdividing each leg into intermediate points shaped by {@code offset}.
     */
    private Route buildRoute(List<GeoCoordinate> orderedPoints, int providerIndex, double offset) {
        List<RouteSegment> segments = new ArrayList<>();
        double totalDistance = 0.0;
        int segmentSeq = 0;

        for (int leg = 0; leg < orderedPoints.size() - 1; leg++) {
            GeoCoordinate legStart = orderedPoints.get(leg);
            GeoCoordinate legEnd = orderedPoints.get(leg + 1);

            List<GeoCoordinate> waypoints = shapeLeg(legStart, legEnd, offset);
            for (int w = 0; w < waypoints.size() - 1; w++) {
                GeoCoordinate start = waypoints.get(w);
                GeoCoordinate end = waypoints.get(w + 1);
                double distance = haversineMeters(start, end);
                totalDistance += distance;
                segments.add(new RouteSegment(
                    "r" + providerIndex + "-s" + segmentSeq,
                    start,
                    end,
                    distance
                ));
                segmentSeq++;
            }
        }

        return new Route("route-" + providerIndex, providerIndex, segments, totalDistance);
    }

    /**
     * Produces the ordered waypoints for a single leg: the start, a handful of intermediate
     * points that interpolate between start and end with a small perpendicular-ish offset, then
     * the end. The offset is applied most strongly at the midpoint and tapers to zero at the
     * endpoints, so the start and end coordinates are preserved exactly.
     */
    private List<GeoCoordinate> shapeLeg(GeoCoordinate start, GeoCoordinate end, double offset) {
        List<GeoCoordinate> waypoints = new ArrayList<>(POINTS_PER_LEG + 2);
        waypoints.add(start);
        for (int k = 1; k <= POINTS_PER_LEG; k++) {
            double t = (double) k / (POINTS_PER_LEG + 1);
            double lat = start.latitude() + (end.latitude() - start.latitude()) * t;
            double lon = start.longitude() + (end.longitude() - start.longitude()) * t;
            // Taper the offset so it is 0 at t=0 and t=1, peaking near the middle of the leg.
            double taper = Math.sin(Math.PI * t);
            waypoints.add(new GeoCoordinate(lat + offset * taper, lon + offset * taper));
        }
        waypoints.add(end);
        return waypoints;
    }

    /**
     * Great-circle distance in meters between two coordinates via the haversine formula.
     */
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
