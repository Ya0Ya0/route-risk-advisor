package com.routeriskadvisor.provider;

import com.routeriskadvisor.domain.model.RiskObservation;
import com.routeriskadvisor.domain.model.RouteSegment;

import java.util.Optional;

/**
 * Supplies fire risk data for a route segment.
 */
public interface FireDataProvider {

    /**
     * Fire data for a segment; empty means "no data for this segment" (Req 2.7).
     */
    Optional<RiskObservation> fireData(RouteSegment segment) throws ProviderException;
}
