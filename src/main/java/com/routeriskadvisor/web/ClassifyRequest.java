package com.routeriskadvisor.web;

/**
 * Request body for {@code POST /api/routes/classify} (design "REST Controllers"): a free-text
 * origin and destination the {@link com.routeriskadvisor.service.RouteRiskService} resolves,
 * classifies, and turns into insurance recommendations.
 *
 * <p>Validation of length/blankness is delegated to the service so a single authority enforces the
 * 1..250 rule (Req 1.2); the controller passes the raw strings through unchanged, allowing the
 * service to name the offending {@code field} on rejection.
 *
 * @param origin      the free-text origin location
 * @param destination the free-text destination location
 */
public record ClassifyRequest(String origin, String destination) {}
