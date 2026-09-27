package com.routeriskadvisor.domain;

import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.SafestRouteResult;

import java.util.List;

/**
 * Compares candidate {@link Route}s and selects the lowest-risk route per {@code Risk_Category},
 * applying the defined tie-breaking rules (Req 4).
 *
 * <p>For each category the finder scores every candidate route (via the {@link RouteClassifier})
 * and selects the minimum under the lexicographic order
 * {@code (Risk_Score, total distance, provider index)}, considering only candidates whose
 * category is scored (not {@code Unknown}). If every candidate is {@code Unknown} for a category,
 * that category's recommendation is reported as {@code Unknown} with no recommended route
 * (Req 4.3, 4.4, 4.5, 4.7).
 */
public interface RouteFinder {

    /**
     * Selects the safest candidate route per risk category.
     *
     * @param candidateRoutes the candidate routes to compare; must not be {@code null} or empty
     * @return one {@link com.routeriskadvisor.domain.model.CategoryRecommendation} per evaluated
     *         category (Accident, Theft, Fire)
     */
    SafestRouteResult findSafest(List<Route> candidateRoutes);
}
