package com.routeriskadvisor.service;

import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.domain.model.InsuranceRecommendationResult;
import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteClassification;

/**
 * The successful outcome of the route-risk classification flow: the resolved coordinates, the
 * route that was classified, its per-category risk classification, and the insurance
 * recommendation derived from it. The future REST controller (task 16.1) serializes this into
 * the classify endpoint response; business rejections are signalled as {@link RouteRiskException}
 * subclasses instead of being represented here.
 *
 * @param originCoordinate      resolved origin coordinate
 * @param destinationCoordinate resolved destination coordinate
 * @param route                 the classified route
 * @param classification        the per-category risk classification
 * @param recommendation        the insurance recommendation for the classification
 */
public record RouteClassificationResult(
    GeoCoordinate originCoordinate,
    GeoCoordinate destinationCoordinate,
    Route route,
    RouteClassification classification,
    InsuranceRecommendationResult recommendation
) {}
