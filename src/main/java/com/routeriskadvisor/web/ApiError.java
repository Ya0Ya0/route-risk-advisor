package com.routeriskadvisor.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The shared API error envelope (design "Error envelope"): every API error serializes to the same
 * JSON shape {@code { code, message, field? }} where:
 * <ul>
 *   <li>{@code code} is a stable machine-readable identifier (e.g. {@code INVALID_INPUT},
 *       {@code OUT_OF_SERVICE_AREA}, {@code NO_ROUTE}, {@code SERVICE_UNAVAILABLE},
 *       {@code LOCATION_COUNT_OUT_OF_RANGE}, {@code ROUTE_RISK_UNEVALUATED});</li>
 *   <li>{@code message} is a human-readable explanation;</li>
 *   <li>{@code field} names the offending input when applicable and is omitted from the payload
 *       when {@code null} (via {@link JsonInclude}), so callers see {@code field} only when it is
 *       meaningful.</li>
 * </ul>
 *
 * @param code    stable machine-readable error identifier
 * @param message human-readable message
 * @param field   offending input field, or {@code null} when not field-specific (omitted from JSON)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, String field) {}
