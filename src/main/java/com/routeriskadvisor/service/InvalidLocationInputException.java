package com.routeriskadvisor.service;

/**
 * Raised when an origin or destination string is empty, blank, or longer than 250 characters
 * (Req 1.2, 6.5). The rejection is pure — it happens before any provider call and mutates no
 * state. {@link #field()} names which input was invalid ({@code "origin"} or {@code "destination"}).
 */
public class InvalidLocationInputException extends RouteRiskException {

    public static final String CODE = "INVALID_INPUT";

    public InvalidLocationInputException(String field, String message) {
        super(CODE, message, field);
    }
}
