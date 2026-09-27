package com.routeriskadvisor.service;

/**
 * Raised when the resolved origin and destination coordinates are identical (Req 1.7). The
 * Routing_Provider is never called in this case.
 */
public class SameLocationException extends RouteRiskException {

    public static final String CODE = "SAME_LOCATION";

    public SameLocationException(String message) {
        super(CODE, message, null);
    }
}
