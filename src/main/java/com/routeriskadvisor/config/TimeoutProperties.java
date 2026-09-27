package com.routeriskadvisor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code route-risk-advisor.timeouts.*} configuration tree so the service layer can
 * enforce the per-step time budgets the requirements specify. Defaults mirror
 * {@code application.yml} so the service behaves correctly even if the tree is absent.
 *
 * <p>Kebab-case YAML keys ({@code geocoding-ms}, {@code service-area-ms}, ...) bind to the
 * camel-case accessors below via Spring's relaxed binding.
 */
@ConfigurationProperties(prefix = "route-risk-advisor.timeouts")
public class TimeoutProperties {

    /** Budget for a single geocoding call (Req 1.8). */
    private long geocodingMs = 10_000;
    /** Budget for a single routing call (Req 1.8). */
    private long routingMs = 10_000;
    /** Budget for a single risk-data provider call (Req 2.9). */
    private long providerMs = 5_000;
    /** Budget for classifying a single route (Req 2.6, 2.10). */
    private long classifyMs = 5_000;
    /** Budget for producing insurance recommendations (Req 3.1). */
    private long recommendMs = 2_000;
    /** Budget for a Service_Area containment check (Req 6.2). */
    private long serviceAreaMs = 2_000;
    /** Budget for classifying a candidate route in the safest-route flow (Req 4.2, 4.9). */
    private long safestClassifyMs = 10_000;

    public long getGeocodingMs() {
        return geocodingMs;
    }

    public void setGeocodingMs(long geocodingMs) {
        this.geocodingMs = geocodingMs;
    }

    public long getRoutingMs() {
        return routingMs;
    }

    public void setRoutingMs(long routingMs) {
        this.routingMs = routingMs;
    }

    public long getProviderMs() {
        return providerMs;
    }

    public void setProviderMs(long providerMs) {
        this.providerMs = providerMs;
    }

    public long getClassifyMs() {
        return classifyMs;
    }

    public void setClassifyMs(long classifyMs) {
        this.classifyMs = classifyMs;
    }

    public long getRecommendMs() {
        return recommendMs;
    }

    public void setRecommendMs(long recommendMs) {
        this.recommendMs = recommendMs;
    }

    public long getServiceAreaMs() {
        return serviceAreaMs;
    }

    public void setServiceAreaMs(long serviceAreaMs) {
        this.serviceAreaMs = serviceAreaMs;
    }

    public long getSafestClassifyMs() {
        return safestClassifyMs;
    }

    public void setSafestClassifyMs(long safestClassifyMs) {
        this.safestClassifyMs = safestClassifyMs;
    }
}
