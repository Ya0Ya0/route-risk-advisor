package com.routeriskadvisor.domain.model;

/**
 * The set of insurance products the advisor can recommend.
 */
public enum InsuranceType {
    LIABILITY_BASELINE,           // Req 3.5
    COLLISION_COVERAGE,           // Accident, Req 3.2
    COMPREHENSIVE_THEFT_COVERAGE, // Theft,    Req 3.3
    COMPREHENSIVE_FIRE_COVERAGE   // Fire,     Req 3.4
}
