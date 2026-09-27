package com.routeriskadvisor.provider.placeholder;

import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.provider.GeocodingProvider;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic placeholder {@link GeocodingProvider}. It maps a curated set of Miami-Dade
 * place names to real in-county coordinates and returns empty for anything it cannot resolve.
 *
 * <p>Every coordinate this provider emits lies inside the Service_Area polygon
 * ({@code ServiceAreaValidator}), satisfying Requirement 6.6. It performs no network calls
 * (Requirement 5.3) and the curated table is the placeholder sample data described in the
 * design's "Placeholder Data Design" section (Requirement 5.2).
 *
 * <p>Lookups are case-insensitive and tolerant of surrounding whitespace so common variations
 * of a place name resolve to the same coordinate. Inputs that are blank or not present in the
 * curated table resolve to {@link Optional#empty()} to exercise the "cannot resolve" path
 * (Requirement 1.4).
 *
 * <p>Bean registration is centralized in {@code ProviderConfiguration} via
 * {@code @ConditionalOnProperty} on {@code route-risk-advisor.providers.geocoding} (Req 5.4, 5.5),
 * so this class carries no Spring stereotype of its own.
 */
public class PlaceholderGeocodingProvider implements GeocodingProvider {

    /**
     * Curated Miami-Dade place names mapped to in-county coordinates. All coordinates are known
     * to fall inside the Service_Area polygon. Keys are normalized (lower-case, trimmed) so the
     * lookup is case- and whitespace-insensitive.
     */
    private static final Map<String, GeoCoordinate> KNOWN_PLACES = Map.ofEntries(
        Map.entry("downtown miami", new GeoCoordinate(25.7617, -80.1918)),
        Map.entry("miami", new GeoCoordinate(25.7617, -80.1918)),
        Map.entry("miami beach", new GeoCoordinate(25.7907, -80.1300)),
        Map.entry("coral gables", new GeoCoordinate(25.7215, -80.2684)),
        Map.entry("hialeah", new GeoCoordinate(25.8576, -80.2781)),
        Map.entry("kendall", new GeoCoordinate(25.6793, -80.3173)),
        Map.entry("homestead", new GeoCoordinate(25.4687, -80.4776)),
        Map.entry("doral", new GeoCoordinate(25.8195, -80.3553)),
        Map.entry("north miami", new GeoCoordinate(25.8901, -80.1867)),
        Map.entry("miami international airport", new GeoCoordinate(25.7959, -80.2870))
    );

    @Override
    public Optional<GeoCoordinate> geocode(String locationDescription) {
        if (locationDescription == null) {
            return Optional.empty();
        }
        String normalized = locationDescription.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(KNOWN_PLACES.get(normalized));
    }
}
