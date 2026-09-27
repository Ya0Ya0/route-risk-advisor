package com.routeriskadvisor.service;

/**
 * Raised when the Geocoding_Provider cannot resolve a location to coordinates (Req 1.4).
 * {@link #field()} names the unresolved location ({@code "origin"} or {@code "destination"}).
 */
public class LocationNotResolvedException extends RouteRiskException {

    public static final String CODE = "LOCATION_NOT_RESOLVED";

    public LocationNotResolvedException(String field, String message) {
        super(CODE, message, field);
    }
}
