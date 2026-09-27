package com.routeriskadvisor.domain.model;

import java.util.List;

/**
 * A candidate route composed of ordered segments.
 */
public record Route(
    String id,
    int providerIndex,              // order returned by Routing_Provider (tie-break key, Req 4.7)
    List<RouteSegment> segments,
    double totalDistanceMeters
) {}
