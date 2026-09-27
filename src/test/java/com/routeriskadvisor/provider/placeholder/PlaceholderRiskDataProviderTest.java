package com.routeriskadvisor.provider.placeholder;

import static org.assertj.core.api.Assertions.assertThat;

import com.routeriskadvisor.domain.ServiceAreaValidator;
import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.domain.model.RiskObservation;
import com.routeriskadvisor.domain.model.RouteSegment;
import com.routeriskadvisor.provider.ProviderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Example/unit tests for the deterministic placeholder crash, crime, and fire providers
 * (Requirements 5.2, 5.3, 6.6).
 */
class PlaceholderRiskDataProviderTest {

    private final PlaceholderCrashDataProvider crash = new PlaceholderCrashDataProvider();
    private final PlaceholderCrimeDataProvider crime = new PlaceholderCrimeDataProvider();
    private final PlaceholderFireDataProvider fire = new PlaceholderFireDataProvider();
    private final ServiceAreaValidator serviceArea = new ServiceAreaValidator();

    /** A stable, clearly in-area Miami-Dade segment (Downtown Miami to Coral Gables). */
    private static RouteSegment inAreaSegment(String id) {
        return new RouteSegment(
            id,
            new GeoCoordinate(25.7617, -80.1918),
            new GeoCoordinate(25.7215, -80.2684),
            8500.0);
    }

    /**
     * Builds a distinct in-area segment for index {@code i}. Because the placeholders key off
     * geography (not the segment id), varying the coordinates is what produces distinct
     * observations and exercises the present/empty split. Coordinates stay within a conservative
     * interior rectangle of Miami-Dade County (latitude 25.30–25.85, longitude -80.75 to -80.30).
     */
    private static RouteSegment distinctInAreaSegment(int i) {
        double startLat = 25.30 + ((i * 7) % 55) * 0.01;
        double startLon = -80.75 + ((i * 3) % 45) * 0.01;
        double endLat = 25.30 + ((i * 11) % 55) * 0.01;
        double endLon = -80.75 + ((i * 5) % 45) * 0.01;
        return new RouteSegment(
            "seg-" + i,
            new GeoCoordinate(startLat, startLon),
            new GeoCoordinate(endLat, endLon),
            1000.0 + i);
    }

    @Test
    @DisplayName("each provider returns the same observation for the same segment (deterministic)")
    void deterministicPerSegment() throws ProviderException {
        RouteSegment segment = inAreaSegment("seg-det");

        assertThat(crash.crashData(segment)).isEqualTo(crash.crashData(segment));
        assertThat(crime.theftData(segment)).isEqualTo(crime.theftData(segment));
        assertThat(fire.fireData(segment)).isEqualTo(fire.fireData(segment));
    }

    @Test
    @DisplayName("present observations have intensity in [0,1], non-negative count, and echo the segment")
    void presentObservationsAreWellFormed() throws ProviderException {
        for (int i = 0; i < 50; i++) {
            RouteSegment segment = distinctInAreaSegment(i);
            assertObservationValid(crash.crashData(segment), segment);
            assertObservationValid(crime.theftData(segment), segment);
            assertObservationValid(fire.fireData(segment), segment);
        }
    }

    @Test
    @DisplayName("across many segments each provider returns at least one empty (Unknown path exercised)")
    void someSegmentsReturnEmpty() throws ProviderException {
        List<RouteSegment> segments = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            segments.add(distinctInAreaSegment(i));
        }

        assertThat(countEmpty(segments, crash)).isPositive();
        assertThat(countEmptyCrime(segments)).isPositive();
        assertThat(countEmptyFire(segments)).isPositive();
    }

    @Test
    @DisplayName("every referenced coordinate stays within the Service_Area (Req 6.6)")
    void referencedCoordinatesAreInArea() throws ProviderException {
        for (int i = 0; i < 100; i++) {
            RouteSegment segment = distinctInAreaSegment(i);
            assertReferencedInArea(crash.crashData(segment));
            assertReferencedInArea(crime.theftData(segment));
            assertReferencedInArea(fire.fireData(segment));
        }
    }

    @Test
    @DisplayName("crash, crime, and fire diverge for the same segment (independent streams)")
    void providersAreIndependent() throws ProviderException {
        // Pick a segment for which all three return present observations.
        for (int i = 0; i < 200; i++) {
            RouteSegment segment = distinctInAreaSegment(i);
            Optional<RiskObservation> c = crash.crashData(segment);
            Optional<RiskObservation> t = crime.theftData(segment);
            Optional<RiskObservation> f = fire.fireData(segment);
            if (c.isPresent() && t.isPresent() && f.isPresent()) {
                assertThat(c.get().normalizedIntensity())
                    .isNotEqualTo(t.get().normalizedIntensity());
                assertThat(c.get().normalizedIntensity())
                    .isNotEqualTo(f.get().normalizedIntensity());
                return;
            }
        }
        throw new AssertionError("expected at least one segment with all three present");
    }

    private void assertObservationValid(Optional<RiskObservation> observation, RouteSegment segment) {
        observation.ifPresent(obs -> {
            assertThat(obs.segment()).isEqualTo(segment);
            assertThat(obs.normalizedIntensity()).isBetween(0.0, 1.0);
            assertThat(obs.sampleCount()).isGreaterThanOrEqualTo(0);
        });
    }

    private void assertReferencedInArea(Optional<RiskObservation> observation) {
        observation.ifPresent(obs -> {
            assertThat(serviceArea.contains(obs.segment().start())).isTrue();
            assertThat(serviceArea.contains(obs.segment().end())).isTrue();
        });
    }

    private long countEmpty(List<RouteSegment> segments, PlaceholderCrashDataProvider provider)
            throws ProviderException {
        long count = 0;
        for (RouteSegment segment : segments) {
            if (provider.crashData(segment).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private long countEmptyCrime(List<RouteSegment> segments) throws ProviderException {
        long count = 0;
        for (RouteSegment segment : segments) {
            if (crime.theftData(segment).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private long countEmptyFire(List<RouteSegment> segments) throws ProviderException {
        long count = 0;
        for (RouteSegment segment : segments) {
            if (fire.fireData(segment).isEmpty()) {
                count++;
            }
        }
        return count;
    }
}
