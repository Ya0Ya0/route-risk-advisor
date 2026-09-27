package com.routeriskadvisor.domain.model;

/**
 * Risk level banding for a scored category. UNKNOWN is a first-class level used when no data
 * was available (or the provider failed/timed out) for a category (Req 2.7-2.9).
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    UNKNOWN
}
