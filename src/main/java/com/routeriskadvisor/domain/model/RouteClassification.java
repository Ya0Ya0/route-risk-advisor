package com.routeriskadvisor.domain.model;

import java.util.List;

/**
 * The result of classifying a resolved route: the route together with one risk assessment per
 * evaluated category (ACCIDENT, THEFT, FIRE).
 *
 * @param route       the classified route
 * @param assessments one assessment per ACCIDENT, THEFT, and FIRE category
 */
public record RouteClassification(
    Route route,
    List<RiskAssessment> assessments
) {}
