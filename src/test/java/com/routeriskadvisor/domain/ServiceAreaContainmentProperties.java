package com.routeriskadvisor.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.routeriskadvisor.domain.model.GeoCoordinate;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for {@link ServiceAreaValidator} containment.
 *
 * <p>Implements design correctness Property 4: containment of a resolved coordinate within the
 * Miami-Dade County Service_Area polygon governs whether a request is accepted. A coordinate
 * inside the polygon is accepted (processing proceeds); a coordinate outside is rejected.
 *
 * <p><strong>Validates: Requirements 1.5, 6.2, 6.3, 6.4</strong>
 */
class ServiceAreaContainmentProperties {

    private final ServiceAreaValidator validator = new ServiceAreaValidator();

    // Feature: route-risk-advisor, Property 4: Service_Area containment governs acceptance
    @Property(tries = 200)
    void coordinatesInsideMiamiDadeAreAccepted(@ForAll("insideMiamiDade") GeoCoordinate coordinate) {
        assertThat(validator.contains(coordinate))
            .as("coordinate inside the Miami-Dade Service_Area polygon must be accepted: %s", coordinate)
            .isTrue();
    }

    // Feature: route-risk-advisor, Property 4: Service_Area containment governs acceptance
    @Property(tries = 200)
    void coordinatesOutsideMiamiDadeAreRejected(@ForAll("outsideMiamiDade") GeoCoordinate coordinate) {
        assertThat(validator.contains(coordinate))
            .as("coordinate outside the Miami-Dade Service_Area polygon must be rejected: %s", coordinate)
            .isFalse();
    }

    /**
     * Generates coordinates that lie strictly inside the Miami-Dade County polygon.
     *
     * <p>The polygon spans roughly latitude 25.13–25.98 and longitude -80.87 to -80.12, but its
     * edges trim the northeast coast and follow the southern/western county line. To guarantee the
     * generated point is inside for every draw, this generator samples from a conservative interior
     * rectangle (latitude 25.30–25.85, longitude -80.75 to -80.30) that stays clear of every
     * polygon edge. {@code GeoCoordinate} is {@code (latitude, longitude)}.
     */
    @Provide
    Arbitrary<GeoCoordinate> insideMiamiDade() {
        Arbitrary<Double> latitude = Arbitraries.doubles().between(25.30, 25.85);
        Arbitrary<Double> longitude = Arbitraries.doubles().between(-80.75, -80.30);
        return Combinators.combine(latitude, longitude).as(GeoCoordinate::new);
    }

    /**
     * Generates coordinates that lie outside the Miami-Dade County polygon.
     *
     * <p>Points are drawn from one of four clearly-external bands: north of the county line, south
     * of it, west across the Everglades, and east out into the Atlantic. Each band is well beyond
     * the polygon's bounding extent so every draw is guaranteed to be outside.
     */
    @Provide
    Arbitrary<GeoCoordinate> outsideMiamiDade() {
        // North of the northern boundary (lat > 25.98).
        Arbitrary<GeoCoordinate> north = coordinatesIn(26.05, 30.00, -85.00, -78.00);
        // South of the southern boundary (lat < 25.13).
        Arbitrary<GeoCoordinate> south = coordinatesIn(22.00, 25.05, -85.00, -78.00);
        // West of the western boundary (lon < -80.87).
        Arbitrary<GeoCoordinate> west = coordinatesIn(24.50, 26.50, -83.00, -80.95);
        // East of the eastern boundary, out into the Atlantic (lon > -80.10).
        Arbitrary<GeoCoordinate> east = coordinatesIn(24.50, 26.50, -80.05, -77.00);
        return Arbitraries.oneOf(north, south, west, east);
    }

    private Arbitrary<GeoCoordinate> coordinatesIn(
            double minLat,
            double maxLat,
            double minLon,
            double maxLon) {
        Arbitrary<Double> latitude = Arbitraries.doubles().between(minLat, maxLat);
        Arbitrary<Double> longitude = Arbitraries.doubles().between(minLon, maxLon);
        return Combinators.combine(latitude, longitude).as(GeoCoordinate::new);
    }
}
