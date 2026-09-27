package com.routeriskadvisor.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.routeriskadvisor.domain.model.InsuranceRecommendation;
import com.routeriskadvisor.domain.model.InsuranceRecommendationResult;
import com.routeriskadvisor.domain.model.InsuranceType;
import com.routeriskadvisor.domain.model.RiskAssessment;
import com.routeriskadvisor.domain.model.RiskCategory;
import com.routeriskadvisor.domain.model.RiskLevel;
import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteClassification;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for {@link DefaultInsuranceAdvisor} all-Low baseline recommendation.
 *
 * <p>Implements design correctness Property 10: for any {@link RouteClassification} in which every
 * category is assessed at the Low risk level, the recommendation set consists of exactly one
 * {@link InsuranceType#LIABILITY_BASELINE} and no other coverage (Req 3.5).
 *
 * <p><strong>Validates: Requirements 3.5</strong>
 */
class AllLowBaselineProperties {

    private final DefaultInsuranceAdvisor advisor = new DefaultInsuranceAdvisor();

    private static final Route ROUTE = new Route("r1", 0, List.of(), 1000.0);

    // Feature: route-risk-advisor, Property 10: All-Low routes recommend a single baseline liability
    @Property(tries = 200)
    void allLowClassificationsRecommendExactlyOneBaselineLiability(
            @ForAll("allLowClassifications") RouteClassification classification) {
        InsuranceRecommendationResult result = advisor.recommend(classification);

        List<InsuranceRecommendation> recommendations = result.recommendations();

        assertThat(recommendations)
            .as("an all-Low classification must recommend exactly one coverage for %s",
                classification.assessments())
            .hasSize(1);

        assertThat(recommendations.get(0).insuranceType())
            .as("the single recommendation for an all-Low classification must be the baseline "
                + "liability for %s", classification.assessments())
            .isEqualTo(InsuranceType.LIABILITY_BASELINE);
    }

    /**
     * Generates {@link RouteClassification}s over ACCIDENT/THEFT/FIRE where every category is
     * assessed at the LOW level. Each category's score is drawn randomly from the Low banding range
     * (0-33), widening the input space well beyond exhaustive enumeration so jqwik exercises the
     * property over many randomized cases rather than a handful of fixed values.
     */
    @Provide
    Arbitrary<RouteClassification> allLowClassifications() {
        Arbitrary<RiskAssessment> accident = lowAssessment(RiskCategory.ACCIDENT);
        Arbitrary<RiskAssessment> theft = lowAssessment(RiskCategory.THEFT);
        Arbitrary<RiskAssessment> fire = lowAssessment(RiskCategory.FIRE);

        return Combinators.combine(accident, theft, fire)
            .as((a, t, f) -> {
                List<RiskAssessment> list = new ArrayList<>();
                list.add(a);
                list.add(t);
                list.add(f);
                return new RouteClassification(ROUTE, list);
            });
    }

    /**
     * Generates a valid LOW {@link RiskAssessment} for a category with a randomized score in the
     * Low banding range [0, 33].
     */
    private Arbitrary<RiskAssessment> lowAssessment(RiskCategory category) {
        return Arbitraries.integers().between(0, 33)
            .map(score -> new RiskAssessment(category, score, RiskLevel.LOW));
    }
}
