package com.routeriskadvisor.domain.model;

import java.util.List;

/**
 * The outcome of insurance recommendation: the recommendations, a status flag, and the
 * received risk assessments retained unchanged (Req 3.7).
 */
public record InsuranceRecommendationResult(
    List<InsuranceRecommendation> recommendations,
    RecommendationStatus status,
    List<RiskAssessment> receivedAssessments  // retained unchanged, Req 3.7
) {}
