package com.routeriskadvisor.domain.model;

/**
 * The category and risk level that justify an insurance recommendation (Req 3.6).
 */
public record RiskJustification(RiskCategory category, RiskLevel level) {}
