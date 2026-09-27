package com.routeriskadvisor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code route-risk-advisor.providers.osm.*} configuration tree used by the keyless
 * OpenStreetMap providers: the Nominatim geocoder and the OSRM router.
 *
 * <p>Neither service requires an API key. Nominatim's usage policy does, however, require a
 * descriptive {@code User-Agent} header on every request (a default/blank agent is rejected with
 * HTTP 403), so {@link #userAgent} is supplied here and defaulted to a project-identifying value.
 *
 * <p>Both base URLs default to the public OSM endpoints and are overridable so tests or a
 * self-hosted Nominatim/OSRM instance can point elsewhere.
 */
@ConfigurationProperties(prefix = "route-risk-advisor.providers.osm")
public class OsmProperties {

    /** Base URL of the Nominatim geocoding API. */
    private String nominatimBaseUrl = "https://nominatim.openstreetmap.org";

    /** Base URL of the OSRM routing API. */
    private String osrmBaseUrl = "https://router.project-osrm.org";

    /** Descriptive {@code User-Agent} sent to Nominatim (required by its usage policy). */
    private String userAgent = "route-risk-advisor/1.0";

    public String getNominatimBaseUrl() {
        return nominatimBaseUrl;
    }

    public void setNominatimBaseUrl(String nominatimBaseUrl) {
        this.nominatimBaseUrl = nominatimBaseUrl;
    }

    public String getOsrmBaseUrl() {
        return osrmBaseUrl;
    }

    public void setOsrmBaseUrl(String osrmBaseUrl) {
        this.osrmBaseUrl = osrmBaseUrl;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }
}
