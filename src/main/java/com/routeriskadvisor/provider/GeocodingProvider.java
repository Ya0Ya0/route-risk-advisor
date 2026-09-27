package com.routeriskadvisor.provider;

import com.routeriskadvisor.domain.model.GeoCoordinate;

import java.util.Optional;

/**
 * Resolves a free-text location description into a geographic coordinate.
 */
public interface GeocodingProvider {

    /**
     * Returns coordinates for a free-text location, or empty if it cannot be resolved.
     */
    Optional<GeoCoordinate> geocode(String locationDescription) throws ProviderException;
}
