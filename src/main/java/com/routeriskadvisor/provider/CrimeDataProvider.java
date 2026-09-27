package com.routeriskadvisor.provider;

import com.routeriskadvisor.domain.model.RiskObservation;
import com.routeriskadvisor.domain.model.RouteSegment;

import java.util.Optional;

/**
 * Supplies theft (crime) risk data for a route segment.
 */
public interface CrimeDataProvider {

    /**
     * Theft data for a segment; empty means "no data for this segment" (Req 2.7).
     */
    Optional<RiskObservation> theftData(RouteSegment segment) throws ProviderException;
}
