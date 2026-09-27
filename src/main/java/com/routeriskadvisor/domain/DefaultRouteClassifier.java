package com.routeriskadvisor.domain;

import com.routeriskadvisor.domain.model.RiskAssessment;
import com.routeriskadvisor.domain.model.RiskCategory;
import com.routeriskadvisor.domain.model.RiskLevel;
import com.routeriskadvisor.domain.model.RiskObservation;
import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteClassification;
import com.routeriskadvisor.domain.model.RouteSegment;
import com.routeriskadvisor.provider.CrashDataProvider;
import com.routeriskadvisor.provider.CrimeDataProvider;
import com.routeriskadvisor.provider.FireDataProvider;
import com.routeriskadvisor.provider.ProviderException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Default {@link RouteClassifier} that orchestrates the crash, crime, and fire data providers
 * over a route's segments and aggregates the results with a {@link RiskScorer}.
 *
 * <p>For each route segment it draws the Accident observation from the {@link CrashDataProvider},
 * the Theft observation from the {@link CrimeDataProvider}, and the Fire observation from the
 * {@link FireDataProvider} (Req 2.1-2.3). The observations that are present for a category are
 * handed to the {@link RiskScorer}, which produces the aggregated {@link RiskAssessment} and
 * applies the Unknown banding rules (Req 2.4-2.8).
 *
 * <p>Graceful degradation (Req 2.9): if a provider throws a {@link ProviderException} — whether a
 * timeout or a failure response — for any segment of a category, that entire category is degraded
 * to Unknown and the remaining categories are still computed. This method never propagates a
 * provider failure to the caller.
 *
 * <p>The returned {@link RouteClassification} always carries exactly one assessment for each of
 * ACCIDENT, THEFT, and FIRE, in that order (Req 2.6).
 */
public class DefaultRouteClassifier implements RouteClassifier {

    private final CrashDataProvider crashDataProvider;
    private final CrimeDataProvider crimeDataProvider;
    private final FireDataProvider fireDataProvider;
    private final RiskScorer riskScorer;

    public DefaultRouteClassifier(
        CrashDataProvider crashDataProvider,
        CrimeDataProvider crimeDataProvider,
        FireDataProvider fireDataProvider,
        RiskScorer riskScorer
    ) {
        if (crashDataProvider == null) {
            throw new IllegalArgumentException("crashDataProvider must not be null");
        }
        if (crimeDataProvider == null) {
            throw new IllegalArgumentException("crimeDataProvider must not be null");
        }
        if (fireDataProvider == null) {
            throw new IllegalArgumentException("fireDataProvider must not be null");
        }
        if (riskScorer == null) {
            throw new IllegalArgumentException("riskScorer must not be null");
        }
        this.crashDataProvider = crashDataProvider;
        this.crimeDataProvider = crimeDataProvider;
        this.fireDataProvider = fireDataProvider;
        this.riskScorer = riskScorer;
    }

    @Override
    public RouteClassification classify(Route route) {
        if (route == null) {
            throw new IllegalArgumentException("route must not be null");
        }

        List<RouteSegment> segments = route.segments() == null ? List.of() : route.segments();

        RiskAssessment accident = assess(RiskCategory.ACCIDENT, segments, crashDataProvider::crashData);
        RiskAssessment theft = assess(RiskCategory.THEFT, segments, crimeDataProvider::theftData);
        RiskAssessment fire = assess(RiskCategory.FIRE, segments, fireDataProvider::fireData);

        // Always one assessment per Accident/Theft/Fire, in a stable order (Req 2.6).
        return new RouteClassification(route, List.of(accident, theft, fire));
    }

    /**
     * Collects the observations that are present for a single category across all segments and
     * scores them. A segment whose provider returns empty contributes no observation (Req 2.7);
     * when a provider fails or times out for any segment the whole category degrades to Unknown
     * (Req 2.9).
     *
     * @param category the category being assessed
     * @param segments the route's segments
     * @param query    the per-segment provider call for this category
     * @return the aggregated assessment for the category, Unknown when degraded or when no
     *         segment had data
     */
    private RiskAssessment assess(RiskCategory category, List<RouteSegment> segments, SegmentQuery query) {
        List<RiskObservation> observations = new ArrayList<>();
        for (RouteSegment segment : segments) {
            Optional<RiskObservation> observation;
            try {
                observation = query.observe(segment);
            } catch (ProviderException e) {
                // Provider timeout or failure degrades this category to Unknown while other
                // categories continue (Req 2.9). Never propagate the failure.
                return unknown(category);
            }
            observation.ifPresent(observations::add);
        }
        // RiskScorer returns Unknown when the observation list is empty (all segments lacked
        // data, Req 2.8) and otherwise a bounded, banded score (Req 2.4, 2.5).
        return riskScorer.score(category, observations);
    }

    private static RiskAssessment unknown(RiskCategory category) {
        return new RiskAssessment(category, null, RiskLevel.UNKNOWN);
    }

    /**
     * A per-segment provider call for one category. Wraps the checked {@link ProviderException}
     * so the three provider method references share one orchestration path.
     */
    @FunctionalInterface
    private interface SegmentQuery {
        Optional<RiskObservation> observe(RouteSegment segment) throws ProviderException;
    }
}
