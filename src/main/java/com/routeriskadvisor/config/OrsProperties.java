package com.routeriskadvisor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code route-risk-advisor.providers.ors.*} configuration tree used by the real
 * OpenRouteService (ORS) geocoding and routing providers.
 *
 * <p>The API key is supplied out-of-band (never committed): {@code application.yml} defaults the
 * key to {@code ${ORS_API_KEY:}}, so operators export the {@code ORS_API_KEY} environment variable
 * (or pass {@code -Droute-risk-advisor.providers.ors.api-key=...}) to enable live calls. When the
 * key is blank the ORS providers still construct — they simply fail their first call with a
 * {@link com.routeriskadvisor.provider.ProviderException} FAILURE, keeping startup healthy while the
 * misconfiguration surfaces on use rather than at wiring time.
 *
 * <p>{@code base-url} defaults to the public ORS endpoint and is overridable so tests or a
 * self-hosted ORS instance can point elsewhere.
 */
@ConfigurationProperties(prefix = "route-risk-advisor.providers.ors")
public class OrsProperties {

    /** OpenRouteService API key; sent in the {@code Authorization} header on every request. */
    private String apiKey = "";

    /** Base URL of the ORS API. */
    private String baseUrl = "https://api.openrouteservice.org";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
