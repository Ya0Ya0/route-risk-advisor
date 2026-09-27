package com.routeriskadvisor.domain;

import com.routeriskadvisor.domain.model.InsuranceRecommendationResult;
import com.routeriskadvisor.domain.model.RouteClassification;

/**
 * Maps a route's classified risk categories to recommended car insurance types (Req 3).
 *
 * <p>Implementations translate each Medium/High risk category into its corresponding
 * insurance coverage, recommend a single baseline liability coverage when every category is
 * Low, and report that no recommendation could be produced when nothing maps.
 */
public interface InsuranceAdvisor {

    /**
     * Produces insurance recommendations for a classified route (Req 3.1-3.7).
     *
     * @param classification the classified route with one assessment per evaluated category;
     *                       must not be {@code null}
     * @return the recommendations, a status flag, and the received assessments retained unchanged
     */
    InsuranceRecommendationResult recommend(RouteClassification classification);
}
