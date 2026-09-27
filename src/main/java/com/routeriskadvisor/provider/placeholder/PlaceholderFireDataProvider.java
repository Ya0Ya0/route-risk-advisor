package com.routeriskadvisor.provider.placeholder;

import com.routeriskadvisor.domain.model.RiskObservation;
import com.routeriskadvisor.domain.model.RouteSegment;
import com.routeriskadvisor.provider.FireDataProvider;
import com.routeriskadvisor.provider.ProviderException;

import java.util.Optional;

/**
 * Deterministic placeholder implementation of {@link FireDataProvider}.
 *
 * <p>Returns fire {@link RiskObservation}s keyed off the segment's geography: a given segment
 * always yields the same {@code normalizedIntensity} (0.0..1.0) and {@code sampleCount}, so scores
 * are reproducible (design: "Placeholder Data Design"; Req 5.2, 5.3). Some segments deliberately
 * return {@link Optional#empty()} to exercise the {@code Unknown} path (Req 2.7). No network call is
 * made. Every observation references only the in-area segment it was given (Req 6.6).
 */
public class PlaceholderFireDataProvider implements FireDataProvider {

    /** Salt distinguishing fire intensity from crash/crime for the same segment. */
    private static final long INTENSITY_SALT = 0x46_49_52_45_00L; // "FIRE"
    /** Independent salt for the empty decision so it is not correlated with intensity. */
    private static final long EMPTY_SALT = 0x46_49_52_4D_54L;
    /** Roughly one in eleven segments has no fire data. */
    private static final int EMPTY_MODULUS = 11;
    /** Plausible upper bound on the number of fire incidents represented for a segment. */
    private static final int MAX_SAMPLE_COUNT = 15;

    @Override
    public Optional<RiskObservation> fireData(RouteSegment segment) throws ProviderException {
        if (segment == null) {
            throw new ProviderException("segment must not be null", ProviderException.Kind.FAILURE);
        }
        if (PlaceholderProviderSupport.isEmpty(segment, EMPTY_SALT, EMPTY_MODULUS)) {
            return Optional.empty();
        }
        long hash = PlaceholderProviderSupport.geographyHash(segment, INTENSITY_SALT);
        double intensity = PlaceholderProviderSupport.normalizedIntensity(hash);
        int sampleCount = PlaceholderProviderSupport.sampleCount(hash, MAX_SAMPLE_COUNT);
        return Optional.of(new RiskObservation(segment, intensity, sampleCount));
    }
}
