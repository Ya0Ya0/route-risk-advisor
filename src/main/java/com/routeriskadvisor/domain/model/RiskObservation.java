package com.routeriskadvisor.domain.model;

/**
 * The common per-segment record returned by the crash, crime, and fire data providers.
 * The scorer aggregates {@code normalizedIntensity} across segments into a 0-100 Risk_Score.
 *
 * @param segment             the route segment this observation applies to
 * @param normalizedIntensity provider-normalized incident density in the range 0.0..1.0
 * @param sampleCount         number of underlying incidents represented
 */
public record RiskObservation(
    RouteSegment segment,
    double normalizedIntensity,
    int sampleCount
) {}
