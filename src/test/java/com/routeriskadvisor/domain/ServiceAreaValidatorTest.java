package com.routeriskadvisor.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.routeriskadvisor.domain.model.GeoCoordinate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Example/unit tests for {@link ServiceAreaValidator} containment (Requirements 6.1–6.4).
 */
class ServiceAreaValidatorTest {

    private final ServiceAreaValidator validator = new ServiceAreaValidator();

    @Nested
    @DisplayName("coordinates clearly inside Miami-Dade County are accepted")
    class InsidePoints {

        @Test
        @DisplayName("Downtown Miami is inside the Service_Area")
        void downtownMiami() {
            assertTrue(validator.contains(new GeoCoordinate(25.7617, -80.1918)));
        }

        @Test
        @DisplayName("Miami Beach is inside the Service_Area")
        void miamiBeach() {
            assertTrue(validator.contains(new GeoCoordinate(25.7907, -80.1300)));
        }

        @Test
        @DisplayName("Coral Gables is inside the Service_Area")
        void coralGables() {
            assertTrue(validator.contains(new GeoCoordinate(25.7215, -80.2684)));
        }

        @Test
        @DisplayName("Hialeah is inside the Service_Area")
        void hialeah() {
            assertTrue(validator.contains(new GeoCoordinate(25.8576, -80.2781)));
        }

        @Test
        @DisplayName("Kendall is inside the Service_Area")
        void kendall() {
            assertTrue(validator.contains(new GeoCoordinate(25.6793, -80.3173)));
        }

        @Test
        @DisplayName("Homestead (near southern extent) is inside the Service_Area")
        void homestead() {
            assertTrue(validator.contains(new GeoCoordinate(25.4687, -80.4776)));
        }
    }

    @Nested
    @DisplayName("coordinates clearly outside Miami-Dade County are rejected")
    class OutsidePoints {

        @Test
        @DisplayName("Orlando is outside the Service_Area")
        void orlando() {
            assertFalse(validator.contains(new GeoCoordinate(28.5383, -81.3792)));
        }

        @Test
        @DisplayName("New York City is outside the Service_Area")
        void newYork() {
            assertFalse(validator.contains(new GeoCoordinate(40.7128, -74.0060)));
        }

        @Test
        @DisplayName("Fort Lauderdale (north of the county line) is outside the Service_Area")
        void fortLauderdale() {
            assertFalse(validator.contains(new GeoCoordinate(26.1224, -80.1373)));
        }

        @Test
        @DisplayName("Naples (west, across the Everglades) is outside the Service_Area")
        void naples() {
            assertFalse(validator.contains(new GeoCoordinate(26.1420, -81.7948)));
        }

        @Test
        @DisplayName("Key Largo (south of the county line) is outside the Service_Area")
        void keyLargo() {
            assertFalse(validator.contains(new GeoCoordinate(25.0865, -80.4473)));
        }

        @Test
        @DisplayName("a point far out in the Atlantic is outside the Service_Area")
        void atlanticOcean() {
            assertFalse(validator.contains(new GeoCoordinate(25.7617, -79.5000)));
        }
    }

    @Nested
    @DisplayName("boundary and degenerate cases")
    class BoundaryAndEdgeCases {

        @Test
        @DisplayName("a point just north of the northern boundary is rejected")
        void justNorthOfBoundary() {
            assertFalse(validator.contains(new GeoCoordinate(26.05, -80.30)));
        }

        @Test
        @DisplayName("a point just inside the northern boundary is accepted")
        void justInsideNorthernBoundary() {
            assertTrue(validator.contains(new GeoCoordinate(25.95, -80.30)));
        }

        @Test
        @DisplayName("a point just west of the western boundary is rejected")
        void justWestOfBoundary() {
            assertFalse(validator.contains(new GeoCoordinate(25.60, -80.95)));
        }

        @Test
        @DisplayName("a null coordinate is rejected rather than throwing")
        void nullCoordinate() {
            assertFalse(validator.contains(null));
        }
    }
}
