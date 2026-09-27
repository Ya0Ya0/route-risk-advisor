package com.routeriskadvisor.domain.model;

/**
 * Risk categories evaluated for a route. This version scores ACCIDENT, THEFT, and FIRE;
 * OTHER is reserved for future expansion.
 */
public enum RiskCategory {
    ACCIDENT,
    THEFT,
    FIRE,
    OTHER
}
