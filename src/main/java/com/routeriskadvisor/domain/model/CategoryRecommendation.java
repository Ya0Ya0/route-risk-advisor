package com.routeriskadvisor.domain.model;

/**
 * The recommended (safest) route for a single risk category (Req 4.4, 4.5).
 */
public record CategoryRecommendation(
    RiskCategory category,
    Route recommendedRoute,   // null only when all candidates Unknown for this category
    Integer score,            // score of recommendedRoute for this category
    RiskLevel level
) {}
