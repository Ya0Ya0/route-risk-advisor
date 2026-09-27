package com.routeriskadvisor.domain.model;

import java.util.List;

/**
 * A single insurance recommendation together with the risk justifications that produced it (Req 3.6).
 */
public record InsuranceRecommendation(
    InsuranceType insuranceType,
    List<RiskJustification> justifiedBy   // category name + risk level (Req 3.6)
) {}
