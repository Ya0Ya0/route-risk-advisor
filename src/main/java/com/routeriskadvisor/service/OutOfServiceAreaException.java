package com.routeriskadvisor.service;

/**
 * Raised when a resolved location falls outside the Miami-Dade County Service_Area (Req 1.5, 6.3).
 * No route is requested. {@link #field()} names which location was out of area.
 */
public class OutOfServiceAreaException extends RouteRiskException {

    public static final String CODE = "OUT_OF_SERVICE_AREA";

    public OutOfServiceAreaException(String field, String message) {
        super(CODE, message, field);
    }
}
