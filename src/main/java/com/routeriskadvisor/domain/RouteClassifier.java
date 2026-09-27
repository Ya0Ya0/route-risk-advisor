package com.routeriskadvisor.domain;

import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteClassification;

/**
 * Classifies a resolved {@link Route} across the Accident, Theft, and Fire risk categories.
 *
 * <p>For each category the classifier orchestrates the corresponding data provider over every
 * route segment, aggregates the per-segment observations into a single {@link RouteClassification}
 * via the {@link RiskScorer}, and applies the Unknown rules from Requirement 2:
 * <ul>
 *   <li>a segment whose provider returns no data contributes nothing to that category (Req 2.7);</li>
 *   <li>a category whose segments all lacked data is reported as Unknown with no score (Req 2.8);</li>
 *   <li>a provider that fails or times out degrades only its own category to Unknown while the
 *       remaining categories are still computed (Req 2.9).</li>
 * </ul>
 *
 * <p>The returned classification always contains exactly one assessment for each of the
 * Accident, Theft, and Fire categories (Req 2.1-2.3, 2.6). This method never throws for a
 * provider failure — such failures are absorbed and surfaced as an Unknown category.
 */
public interface RouteClassifier {

    /**
     * Classifies the given route. Never throws for provider failure; a failing or empty provider
     * degrades its category to Unknown (Req 2).
     *
     * @param route the resolved route to classify; must not be {@code null}
     * @return the classification with one assessment per Accident, Theft, and Fire category
     */
    RouteClassification classify(Route route);
}
