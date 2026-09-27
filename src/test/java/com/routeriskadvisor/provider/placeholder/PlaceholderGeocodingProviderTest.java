package com.routeriskadvisor.provider.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.routeriskadvisor.domain.ServiceAreaValidator;
import com.routeriskadvisor.domain.model.GeoCoordinate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Example/unit tests for {@link PlaceholderGeocodingProvider} (Requirements 5.2, 5.3, 6.6).
 */
class PlaceholderGeocodingProviderTest {

    private final PlaceholderGeocodingProvider provider = new PlaceholderGeocodingProvider();
    private final ServiceAreaValidator serviceArea = new ServiceAreaValidator();

    @Test
    @DisplayName("resolves a curated place name to in-county coordinates")
    void resolvesKnownPlace() throws Exception {
        Optional<GeoCoordinate> result = provider.geocode("Downtown Miami");
        assertTrue(result.isPresent());
        assertTrue(serviceArea.contains(result.get()));
    }

    @Test
    @DisplayName("lookup is case-insensitive and trims surrounding whitespace")
    void lookupIsNormalized() throws Exception {
        Optional<GeoCoordinate> mixedCase = provider.geocode("  CORAL Gables  ");
        Optional<GeoCoordinate> canonical = provider.geocode("coral gables");
        assertTrue(mixedCase.isPresent());
        assertEquals(canonical, mixedCase);
    }

    @Test
    @DisplayName("returns empty for an unknown place")
    void unknownPlaceReturnsEmpty() throws Exception {
        assertTrue(provider.geocode("Seattle Space Needle").isEmpty());
    }

    @Test
    @DisplayName("returns empty for blank, whitespace-only, and null inputs")
    void blankInputsReturnEmpty() throws Exception {
        assertTrue(provider.geocode("").isEmpty());
        assertTrue(provider.geocode("   ").isEmpty());
        assertTrue(provider.geocode(null).isEmpty());
    }

    @Test
    @DisplayName("every curated place resolves to a coordinate inside the Service_Area")
    void allCuratedPlacesAreInArea() throws Exception {
        String[] places = {
            "downtown miami", "miami", "miami beach", "coral gables", "hialeah",
            "kendall", "homestead", "doral", "north miami", "miami international airport"
        };
        for (String place : places) {
            Optional<GeoCoordinate> result = provider.geocode(place);
            assertTrue(result.isPresent(), place + " should resolve");
            assertTrue(serviceArea.contains(result.get()), place + " must be in the Service_Area");
        }
    }
}
