package com.routeriskadvisor.domain.model;

/**
 * A single leg of a route between two geographic points.
 */
public record RouteSegment(
    String id,
    GeoCoordinate start,
    GeoCoordinate end,
    double distanceMeters
) {}
