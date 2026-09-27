package com.routeriskadvisor.config;

import com.routeriskadvisor.provider.CrashDataProvider;
import com.routeriskadvisor.provider.CrimeDataProvider;
import com.routeriskadvisor.provider.FireDataProvider;
import com.routeriskadvisor.provider.GeocodingProvider;
import com.routeriskadvisor.provider.RoutingProvider;
import com.routeriskadvisor.provider.ors.OpenRouteServiceGeocodingProvider;
import com.routeriskadvisor.provider.ors.OpenRouteServiceRoutingProvider;
import com.routeriskadvisor.provider.osm.NominatimGeocodingProvider;
import com.routeriskadvisor.provider.osm.OsrmRoutingProvider;
import com.routeriskadvisor.provider.placeholder.PlaceholderCrashDataProvider;
import com.routeriskadvisor.provider.placeholder.PlaceholderCrimeDataProvider;
import com.routeriskadvisor.provider.placeholder.PlaceholderFireDataProvider;
import com.routeriskadvisor.provider.placeholder.PlaceholderGeocodingProvider;
import com.routeriskadvisor.provider.placeholder.PlaceholderRoutingProvider;

import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Central Spring configuration that registers exactly one bean per provider interface based on the
 * {@code route-risk-advisor.providers.*} configuration keys (Req 5.4, 5.5).
 *
 * <p>Each interface ({@link GeocodingProvider}, {@link RoutingProvider}, {@link CrashDataProvider},
 * {@link CrimeDataProvider}, {@link FireDataProvider}) is bound via {@link ConditionalOnProperty}
 * keyed on {@code route-risk-advisor.providers.{geocoding,routing,crash,crime,fire}}. This version
 * ships only the deterministic placeholder implementation for each interface, and it is the default:
 * {@code matchIfMissing = true} means the placeholder is selected both when the key is set to
 * {@code placeholder} and when the key is absent entirely (design: "Configuration Strategy
 * (Provider Selection)").
 *
 * <p>Because the domain services depend only on the interfaces — never on the concrete placeholder
 * classes — swapping in a real provider requires no source change to {@code Route_Classifier},
 * {@code Route_Finder}, or {@code Insurance_Advisor} (Req 5.6). Future real implementations would be
 * added here (or as their own {@code @ConditionalOnProperty} beans) keyed on their own
 * {@code havingValue}, leaving the placeholder as the {@code matchIfMissing} default.
 */
@Configuration
@EnableConfigurationProperties({OrsProperties.class, OsmProperties.class})
public class ProviderConfiguration {

    /**
     * Registers the placeholder {@link GeocodingProvider} only when
     * {@code route-risk-advisor.providers.geocoding} is explicitly {@code placeholder}.
     *
     * <p>Unlike the crash/crime/fire placeholders, this bean does <em>not</em> use
     * {@code matchIfMissing = true}: the default geocoding implementation is now OpenRouteService
     * (see {@link #openRouteServiceGeocodingProvider}). Requiring the explicit {@code placeholder}
     * value keeps exactly one geocoding bean active whether the key selects placeholder or ORS.
     */
    @Bean
    @ConditionalOnProperty(
        name = "route-risk-advisor.providers.geocoding",
        havingValue = "placeholder",
        matchIfMissing = false)
    public GeocodingProvider placeholderGeocodingProvider() {
        return new PlaceholderGeocodingProvider();
    }

    /**
     * Registers the real OpenRouteService {@link GeocodingProvider} when
     * {@code route-risk-advisor.providers.geocoding} is {@code openrouteservice}. The API key and
     * base URL come from {@link OrsProperties}; the read timeout is the configured geocoding budget.
     */
    @Bean
    @ConditionalOnProperty(
        name = "route-risk-advisor.providers.geocoding",
        havingValue = "openrouteservice")
    public GeocodingProvider openRouteServiceGeocodingProvider(
        OrsProperties orsProperties, TimeoutProperties timeoutProperties) {
        return new OpenRouteServiceGeocodingProvider(
            orsProperties.getBaseUrl(),
            orsProperties.getApiKey(),
            Duration.ofMillis(timeoutProperties.getGeocodingMs()),
            Duration.ofMillis(timeoutProperties.getGeocodingMs()));
    }

    /**
     * Registers the real keyless {@link GeocodingProvider} backed by OpenStreetMap Nominatim when
     * {@code route-risk-advisor.providers.geocoding} is {@code nominatim} (the shipped default). The
     * base URL and {@code User-Agent} come from {@link OsmProperties}; the read timeout is the
     * configured geocoding budget.
     */
    @Bean
    @ConditionalOnProperty(
        name = "route-risk-advisor.providers.geocoding",
        havingValue = "nominatim")
    public GeocodingProvider nominatimGeocodingProvider(
        OsmProperties osmProperties, TimeoutProperties timeoutProperties) {
        return new NominatimGeocodingProvider(
            osmProperties.getNominatimBaseUrl(),
            osmProperties.getUserAgent(),
            Duration.ofMillis(timeoutProperties.getGeocodingMs()),
            Duration.ofMillis(timeoutProperties.getGeocodingMs()));
    }

    /**
     * Registers the placeholder {@link RoutingProvider} only when
     * {@code route-risk-advisor.providers.routing} is explicitly {@code placeholder}. The default
     * routing implementation is now OpenRouteService (see {@link #openRouteServiceRoutingProvider}).
     */
    @Bean
    @ConditionalOnProperty(
        name = "route-risk-advisor.providers.routing",
        havingValue = "placeholder",
        matchIfMissing = false)
    public RoutingProvider placeholderRoutingProvider() {
        return new PlaceholderRoutingProvider();
    }

    /**
     * Registers the real OpenRouteService {@link RoutingProvider} when
     * {@code route-risk-advisor.providers.routing} is {@code openrouteservice}. The read timeout is
     * the configured routing budget.
     */
    @Bean
    @ConditionalOnProperty(
        name = "route-risk-advisor.providers.routing",
        havingValue = "openrouteservice")
    public RoutingProvider openRouteServiceRoutingProvider(
        OrsProperties orsProperties, TimeoutProperties timeoutProperties) {
        return new OpenRouteServiceRoutingProvider(
            orsProperties.getBaseUrl(),
            orsProperties.getApiKey(),
            Duration.ofMillis(timeoutProperties.getRoutingMs()),
            Duration.ofMillis(timeoutProperties.getRoutingMs()));
    }

    /**
     * Registers the real keyless {@link RoutingProvider} backed by OpenStreetMap OSRM when
     * {@code route-risk-advisor.providers.routing} is {@code osrm} (the shipped default). The base
     * URL comes from {@link OsmProperties}; the read timeout is the configured routing budget.
     */
    @Bean
    @ConditionalOnProperty(
        name = "route-risk-advisor.providers.routing",
        havingValue = "osrm")
    public RoutingProvider osrmRoutingProvider(
        OsmProperties osmProperties, TimeoutProperties timeoutProperties) {
        return new OsrmRoutingProvider(
            osmProperties.getOsrmBaseUrl(),
            Duration.ofMillis(timeoutProperties.getRoutingMs()),
            Duration.ofMillis(timeoutProperties.getRoutingMs()));
    }

    /**
     * Registers the placeholder {@link CrashDataProvider} when
     * {@code route-risk-advisor.providers.crash} is {@code placeholder} or unset.
     */
    @Bean
    @ConditionalOnProperty(
        name = "route-risk-advisor.providers.crash",
        havingValue = "placeholder",
        matchIfMissing = true)
    public CrashDataProvider placeholderCrashDataProvider() {
        return new PlaceholderCrashDataProvider();
    }

    /**
     * Registers the placeholder {@link CrimeDataProvider} when
     * {@code route-risk-advisor.providers.crime} is {@code placeholder} or unset.
     */
    @Bean
    @ConditionalOnProperty(
        name = "route-risk-advisor.providers.crime",
        havingValue = "placeholder",
        matchIfMissing = true)
    public CrimeDataProvider placeholderCrimeDataProvider() {
        return new PlaceholderCrimeDataProvider();
    }

    /**
     * Registers the placeholder {@link FireDataProvider} when
     * {@code route-risk-advisor.providers.fire} is {@code placeholder} or unset.
     */
    @Bean
    @ConditionalOnProperty(
        name = "route-risk-advisor.providers.fire",
        havingValue = "placeholder",
        matchIfMissing = true)
    public FireDataProvider placeholderFireDataProvider() {
        return new PlaceholderFireDataProvider();
    }
}
