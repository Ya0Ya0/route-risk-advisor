package com.routeriskadvisor.service;

/**
 * Base type for business outcomes of the route-risk classification flow that the future REST
 * controller (task 16.1) maps to the shared error envelope {@code { code, message, field? }}.
 *
 * <p>Each subclass carries a stable machine-readable {@link #code()} and an optional
 * {@link #field()} naming the offending input, mirroring the design's "Error Handling" section.
 * These are checked exceptions so callers cannot accidentally ignore a business outcome; the
 * controller layer translates them into the appropriate HTTP status (400 validation/business,
 * 404 no-route, 503 provider unavailable).
 */
public abstract class RouteRiskException extends Exception {

    private final String code;
    private final String field;

    protected RouteRiskException(String code, String message, String field) {
        super(message);
        this.code = code;
        this.field = field;
    }

    /** @return the stable machine-readable error code (e.g. {@code INVALID_INPUT}). */
    public String code() {
        return code;
    }

    /** @return the offending input field name, or {@code null} when not field-specific. */
    public String field() {
        return field;
    }
}
