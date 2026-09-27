package com.routeriskadvisor.provider;

import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.domain.model.Route;

import java.util.List;
import java.util.Optional;

/**
 * Produces routes between geographic points.
 */
public interface RoutingProvider {

    /**
     * One route between two points (used by classification).
     */
    Optional<Route> route(GeoCoordinate origin, GeoCoordinate destination) throws ProviderException;

    /**
     * Candidate routes connecting an ordered list of points (used by the safest-route finder).
     * The returned list preserves provider order; the index is the tie-break "earliest returned" key.
     */
    List<Route> candidateRoutes(List<GeoCoordinate> orderedPoints) throws ProviderException;
}
