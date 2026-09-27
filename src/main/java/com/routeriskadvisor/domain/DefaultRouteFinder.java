package com.routeriskadvisor.domain;

import com.routeriskadvisor.domain.model.CategoryRecommendation;
import com.routeriskadvisor.domain.model.RiskAssessment;
import com.routeriskadvisor.domain.model.RiskCategory;
import com.routeriskadvisor.domain.model.RiskLevel;
import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteClassification;
import com.routeriskadvisor.domain.model.SafestRouteResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link RouteFinder} that selects, per risk category, the candidate route with the
 * lowest risk under a deterministic tie-break order.
 *
 * <p>Each candidate route is classified once via the {@link RouteClassifier}. For every evaluated
 * category (Accident, Theft, Fire) the finder considers only the candidates whose category is
 * scored (not {@code Unknown} — a route that is {@code Unknown} for a category cannot be proven
 * lowest for it, Req 4.3). Among the scored candidates it selects the minimum under the
 * lexicographic order:
 * <ol>
 *   <li>lowest {@code Risk_Score} (Req 4.3);</li>
 *   <li>tie-break 1: shorter {@code totalDistanceMeters} (Req 4.7);</li>
 *   <li>tie-break 2: smaller {@code providerIndex} — the route returned earliest (Req 4.7).</li>
 * </ol>
 *
 * <p>If only one candidate route is supplied it is recommended for every category for which it is
 * scored (Req 4.5) — a direct consequence of it being the sole element of the selection set. If
 * every candidate is {@code Unknown} for a category, that category is reported with a {@code null}
 * recommended route and an {@code UNKNOWN} level (Req 4.4).
 *
 * <p>This class depends only on the {@link RouteClassifier} abstraction, never on a concrete
 * provider implementation (Req 5.6).
 */
public class DefaultRouteFinder implements RouteFinder {

    /** Categories the finder evaluates and reports a recommendation for. */
    private static final List<RiskCategory> EVALUATED_CATEGORIES =
        List.of(RiskCategory.ACCIDENT, RiskCategory.THEFT, RiskCategory.FIRE);

    private final RouteClassifier routeClassifier;

    public DefaultRouteFinder(RouteClassifier routeClassifier) {
        if (routeClassifier == null) {
            throw new IllegalArgumentException("routeClassifier must not be null");
        }
        this.routeClassifier = routeClassifier;
    }

    @Override
    public SafestRouteResult findSafest(List<Route> candidateRoutes) {
        if (candidateRoutes == null) {
            throw new IllegalArgumentException("candidateRoutes must not be null");
        }
        if (candidateRoutes.isEmpty()) {
            throw new IllegalArgumentException("candidateRoutes must not be empty");
        }

        // Classify each candidate exactly once (Req 4.2) and index its assessments by category.
        List<ScoredRoute> scoredRoutes = new ArrayList<>(candidateRoutes.size());
        for (Route route : candidateRoutes) {
            if (route == null) {
                throw new IllegalArgumentException("candidateRoutes must not contain null");
            }
            RouteClassification classification = routeClassifier.classify(route);
            scoredRoutes.add(new ScoredRoute(route, assessmentsByCategory(classification)));
        }

        List<CategoryRecommendation> perCategory = new ArrayList<>(EVALUATED_CATEGORIES.size());
        for (RiskCategory category : EVALUATED_CATEGORIES) {
            perCategory.add(select(category, scoredRoutes));
        }
        return new SafestRouteResult(perCategory);
    }

    /**
     * Selects the recommended route for a single category as the minimum under the tie-break
     * order among the candidates that are scored (not Unknown) for that category.
     */
    private CategoryRecommendation select(RiskCategory category, List<ScoredRoute> scoredRoutes) {
        Candidate best = null;
        for (ScoredRoute scored : scoredRoutes) {
            RiskAssessment assessment = scored.assessmentFor(category);
            // Skip Unknown categories: an unscored route cannot be proven lowest (Req 4.3).
            if (assessment == null || assessment.level() == RiskLevel.UNKNOWN) {
                continue;
            }
            Candidate current = new Candidate(scored.route(), assessment);
            if (best == null || TIE_BREAK.compare(current, best) < 0) {
                best = current;
            }
        }

        if (best == null) {
            // Every candidate was Unknown for this category (Req 4.4).
            return new CategoryRecommendation(category, null, null, RiskLevel.UNKNOWN);
        }
        return new CategoryRecommendation(
            category, best.route(), best.assessment().score(), best.assessment().level());
    }

    private static Map<RiskCategory, RiskAssessment> assessmentsByCategory(
            RouteClassification classification) {
        Map<RiskCategory, RiskAssessment> byCategory = new EnumMap<>(RiskCategory.class);
        for (RiskAssessment assessment : classification.assessments()) {
            byCategory.put(assessment.category(), assessment);
        }
        return byCategory;
    }

    /**
     * A scored candidate for a single category: the route and its (non-Unknown) assessment for
     * the category under consideration. The assessment's {@code score} is guaranteed non-null
     * because {@code select} only builds a {@code Candidate} from a scored assessment.
     */
    private record Candidate(Route route, RiskAssessment assessment) {}

    /**
     * Lexicographic tie-break order applied per category (Req 4.3, 4.7):
     * lowest score, then shorter total distance, then smallest provider index.
     */
    private static final Comparator<Candidate> TIE_BREAK =
        Comparator.<Candidate>comparingInt(c -> c.assessment().score())
            .thenComparingDouble(c -> c.route().totalDistanceMeters())
            .thenComparingInt(c -> c.route().providerIndex());

    /**
     * A candidate route together with its per-category assessments, indexed for O(1) lookup.
     */
    private record ScoredRoute(Route route, Map<RiskCategory, RiskAssessment> assessments) {
        RiskAssessment assessmentFor(RiskCategory category) {
            return assessments.get(category);
        }
    }
}
