package com.routeriskadvisor.service;

/**
 * Raised when the Geocoding_Provider or Routing_Provider times out (10s) or returns a failure
 * during classification (Req 1.8). Because there are no coordinates or route to classify, the
 * request is aborted with a "temporarily unavailable" message. The future controller maps this
 * to HTTP 503.
 */
public class ServiceUnavailableException extends RouteRiskException {

    public static final String CODE = "SERVICE_UNAVAILABLE";

    public ServiceUnavailableException(String message) {
        super(CODE, message, null);
    }
}
