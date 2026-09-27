package com.routeriskadvisor.provider;

import com.routeriskadvisor.domain.model.RiskObservation;
import com.routeriskadvisor.domain.model.RouteSegment;

import java.util.Optional;

/**
 * Supplies accident (crash) risk data for a route segment.
 */
public interface CrashDataProvider {

    /**
     * Accident data for a segment; empty means "no data for this segment" (Req 2.7).
     */
    Optional<RiskObservation> crashData(RouteSegment segment) throws ProviderException;
}
