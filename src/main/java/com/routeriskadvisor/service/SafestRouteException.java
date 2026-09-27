package com.routeriskadvisor.service;

/**
 * Signals a business-level rejection or abort of the Safest Route Finder flow (Requirement 4).
 *
 * <p>The safest-route comparison is <em>all-or-nothing</em>: whenever the flow cannot produce a
 * complete recommendation it throws this exception instead of returning a partial
 * {@link com.routeriskadvisor.domain.model.SafestRouteResult}. This keeps the outcome atomic
 * (design "Error Handling", Property 15) — a caller either receives a full per-category
 * recommendation or a single typed failure, never a mix.
 *
 * <p>The {@link Reason} discriminator lets the future REST controller (task 16.1) map each outcome
 * to the shared {@code { code, message, field? }} error envelope and the correct HTTP status
 * without re-deriving the cause:
 * <ul>
 *   <li>{@link Reason#LOCATION_COUNT_OUT_OF_RANGE} &rarr; {@code LOCATION_COUNT_OUT_OF_RANGE}, HTTP 400 (Req 4.6)</li>
 *   <li>{@link Reason#OUT_OF_SERVICE_AREA} &rarr; {@code OUT_OF_SERVICE_AREA}, HTTP 400 (Req 6.3)</li>
 *   <li>{@link Reason#NO_ROUTES_RETRIEVED} &rarr; {@code NO_ROUTES_RETRIEVED}, HTTP 503 (Req 4.8)</li>
 *   <li>{@link Reason#ROUTE_RISK_UNEVALUATED} &rarr; {@code ROUTE_RISK_UNEVALUATED}, HTTP 503 (Req 4.9)</li>
 * </ul>
 */
public class SafestRouteException extends RuntimeException {

    /**
     * The class of failure that aborted (or rejected) the safest-route comparison.
     */
    public enum Reason {
        /** Fewer than 2 or more than 25 locations were submitted (Req 4.6). */
        LOCATION_COUNT_OUT_OF_RANGE,
        /** At least one submitted location resolves outside the Miami-Dade Service_Area (Req 6.3). */
        OUT_OF_SERVICE_AREA,
        /** The Routing_Provider returned no candidate routes, timed out, or failed (Req 4.8). */
        NO_ROUTES_RETRIEVED,
        /** The Route_Classifier failed or timed out for a candidate route (Req 4.9). */
        ROUTE_RISK_UNEVALUATED
    }

    private final Reason reason;

    /** The offending input field, when one applies; otherwise {@code null}. */
    private final String field;

    public SafestRouteException(Reason reason, String message) {
        this(reason, message, null, null);
    }

    public SafestRouteException(Reason reason, String message, String field) {
        this(reason, message, field, null);
    }

    public SafestRouteException(Reason reason, String message, String field, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.field = field;
    }

    /**
     * @return the discriminator identifying why the comparison was rejected or aborted
     */
    public Reason reason() {
        return reason;
    }

    /**
     * @return the offending input field, or {@code null} when the failure is not tied to a field
     */
    public String field() {
        return field;
    }
}
