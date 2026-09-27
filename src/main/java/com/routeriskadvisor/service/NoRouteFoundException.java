package com.routeriskadvisor.service;

/**
 * Raised when the Routing_Provider returns no route between the resolved coordinates (Req 1.6).
 * The future controller maps this to HTTP 404.
 */
public class NoRouteFoundException extends RouteRiskException {

    public static final String CODE = "NO_ROUTE";

    public NoRouteFoundException(String message) {
        super(CODE, message, null);
    }
}
