package com.routeriskadvisor.provider.placeholder;

import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.domain.model.RouteSegment;

/**
 * Shared helpers for the deterministic placeholder risk-data providers
 * ({@code PlaceholderCrashDataProvider}, {@code PlaceholderCrimeDataProvider},
 * {@code PlaceholderFireDataProvider}).
 *
 * <p>The placeholders derive stable, reproducible values purely from a segment's geography, so a
 * given segment always yields the same intensity, sample count, and present/empty decision (design:
 * "Placeholder Data Design"). No randomness or external state is involved, which keeps scores
 * deterministic per segment and tests predictable.
 */
final class PlaceholderProviderSupport {

    private PlaceholderProviderSupport() {
    }

    /**
     * Produces a stable, well-distributed 64-bit hash of a segment's geography combined with a
     * per-provider salt. Mixing in the salt makes crash, crime, and fire diverge for the same
     * segment while each remains individually deterministic.
     *
     * @param segment the segment to derive a value from
     * @param salt    a per-provider constant so the three providers produce independent streams
     * @return a deterministic hash of the segment geometry and salt
     */
    static long geographyHash(RouteSegment segment, long salt) {
        long h = salt;
        h = mix(h, Double.doubleToLongBits(round(segment.start().latitude())));
        h = mix(h, Double.doubleToLongBits(round(segment.start().longitude())));
        h = mix(h, Double.doubleToLongBits(round(segment.end().latitude())));
        h = mix(h, Double.doubleToLongBits(round(segment.end().longitude())));
        return h;
    }

    /**
     * Maps a geography hash to a normalized incident intensity in {@code [0.0, 1.0]}.
     */
    static double normalizedIntensity(long hash) {
        // Use the top 53 bits for a uniform value in [0, 1); matches RiskObservation's 0.0..1.0 range.
        long bits = hash >>> 11;
        return bits / (double) (1L << 53);
    }

    /**
     * Maps a geography hash to a plausible incident sample count.
     *
     * @param hash the segment geography hash
     * @param max  the (inclusive) maximum sample count for this provider
     * @return a deterministic count in {@code [0, max]}
     */
    static int sampleCount(long hash, int max) {
        return (int) (Math.floorMod(hash, (long) (max + 1)));
    }

    /**
     * Decides whether a segment deliberately has "no data" (empty) to exercise the Unknown path
     * (Req 2.7). Roughly one in {@code emptyModulus} segments returns empty, chosen deterministically
     * from the segment geography and the provider salt.
     *
     * @param segment      the segment being evaluated
     * @param salt         the provider salt (distinct from the intensity salt so the empty decision
     *                     is independent of the intensity value)
     * @param emptyModulus how frequently empties occur; larger means rarer
     * @return {@code true} if this segment should report no data
     */
    static boolean isEmpty(RouteSegment segment, long salt, int emptyModulus) {
        long hash = geographyHash(segment, salt);
        return Math.floorMod(hash, (long) emptyModulus) == 0L;
    }

    /**
     * Rounds a coordinate component to a fixed grid so that geographically-equal segments hash
     * identically despite floating-point noise, keeping the placeholder output stable.
     */
    private static double round(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    private static long mix(long h, long value) {
        h ^= value;
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        return h;
    }

    /**
     * Guards against a segment whose coordinates fall outside the Service_Area. The placeholder
     * providers only ever receive in-area segments in practice; this is a defensive check so a
     * caller cannot trick a placeholder into referencing out-of-area geography (Req 6.6).
     */
    static boolean inArea(GeoCoordinate coordinate) {
        // Coarse Miami-Dade bounding extents; matches the ServiceAreaValidator polygon's span.
        double lat = coordinate.latitude();
        double lon = coordinate.longitude();
        return lat >= 25.13 && lat <= 25.98 && lon >= -80.87 && lon <= -80.12;
    }
}
