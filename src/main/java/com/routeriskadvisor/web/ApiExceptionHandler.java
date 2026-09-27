package com.routeriskadvisor.web;

import com.routeriskadvisor.service.InvalidLocationInputException;
import com.routeriskadvisor.service.LocationNotResolvedException;
import com.routeriskadvisor.service.NoRouteFoundException;
import com.routeriskadvisor.service.OutOfServiceAreaException;
import com.routeriskadvisor.service.RouteRiskException;
import com.routeriskadvisor.service.SafestRouteException;
import com.routeriskadvisor.service.SameLocationException;
import com.routeriskadvisor.service.ServiceUnavailableException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Central mapping from the application services' typed business outcomes to the shared
 * {@link ApiError} envelope and the correct HTTP status (design "Error Handling", "Error envelope").
 *
 * <p>Status map:
 * <table>
 *   <caption>Exception → code → HTTP status</caption>
 *   <tr><th>Outcome</th><th>code</th><th>status</th></tr>
 *   <tr><td>{@link InvalidLocationInputException}</td><td>{@code INVALID_INPUT}</td><td>400</td></tr>
 *   <tr><td>{@link OutOfServiceAreaException}</td><td>{@code OUT_OF_SERVICE_AREA}</td><td>400</td></tr>
 *   <tr><td>{@link SameLocationException}</td><td>{@code SAME_LOCATION}</td><td>400</td></tr>
 *   <tr><td>{@link LocationNotResolvedException}</td><td>{@code LOCATION_NOT_RESOLVED}</td><td>404</td></tr>
 *   <tr><td>{@link NoRouteFoundException}</td><td>{@code NO_ROUTE}</td><td>404</td></tr>
 *   <tr><td>{@link ServiceUnavailableException}</td><td>{@code SERVICE_UNAVAILABLE}</td><td>503</td></tr>
 *   <tr><td>{@link SafestRouteException} {@code LOCATION_COUNT_OUT_OF_RANGE}</td><td>same</td><td>400</td></tr>
 *   <tr><td>{@link SafestRouteException} {@code OUT_OF_SERVICE_AREA}</td><td>same</td><td>400</td></tr>
 *   <tr><td>{@link SafestRouteException} {@code NO_ROUTES_RETRIEVED}</td><td>same</td><td>503</td></tr>
 *   <tr><td>{@link SafestRouteException} {@code ROUTE_RISK_UNEVALUATED}</td><td>same</td><td>503</td></tr>
 * </table>
 *
 * <p>The design maps validation/business rejections to 400, no-route/unresolved-location to 404,
 * and provider timeouts/unavailability to 503. Note the safest flow's {@code NO_ROUTES_RETRIEVED}
 * covers "no routes OR provider timeout/failure" (Req 4.8) and is therefore mapped to 503 (the
 * {@link SafestRouteException.Reason} javadoc records the intended status per reason).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * Maps a classification-flow business outcome to the error envelope. The concrete subclass
     * determines the status; the {@code code} and {@code field} come straight off the exception so
     * a new subclass only needs a status decision here.
     */
    @ExceptionHandler(RouteRiskException.class)
    public ResponseEntity<ApiError> handleRouteRisk(RouteRiskException ex) {
        HttpStatus status = statusFor(ex);
        ApiError body = new ApiError(ex.code(), ex.getMessage(), ex.field());
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Maps a safest-route-flow business outcome to the error envelope. The {@link SafestRouteException.Reason}
     * supplies both the stable {@code code} and the HTTP status.
     */
    @ExceptionHandler(SafestRouteException.class)
    public ResponseEntity<ApiError> handleSafestRoute(SafestRouteException ex) {
        HttpStatus status = statusFor(ex.reason());
        ApiError body = new ApiError(ex.reason().name(), ex.getMessage(), ex.field());
        return ResponseEntity.status(status).body(body);
    }

    /** 400 for validation/business rejections, 404 for missing route/location, 503 for provider unavailability. */
    private static HttpStatus statusFor(RouteRiskException ex) {
        if (ex instanceof InvalidLocationInputException
            || ex instanceof OutOfServiceAreaException
            || ex instanceof SameLocationException) {
            return HttpStatus.BAD_REQUEST;
        }
        if (ex instanceof LocationNotResolvedException
            || ex instanceof NoRouteFoundException) {
            return HttpStatus.NOT_FOUND;
        }
        if (ex instanceof ServiceUnavailableException) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        // Any future business outcome without an explicit mapping is treated as a client-side
        // rejection rather than a 500, keeping the envelope contract intact.
        return HttpStatus.BAD_REQUEST;
    }

    /** Maps each safest-route reason to its HTTP status (see class javadoc / Reason javadoc). */
    private static HttpStatus statusFor(SafestRouteException.Reason reason) {
        return switch (reason) {
            case LOCATION_COUNT_OUT_OF_RANGE, OUT_OF_SERVICE_AREA -> HttpStatus.BAD_REQUEST;
            case NO_ROUTES_RETRIEVED, ROUTE_RISK_UNEVALUATED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
