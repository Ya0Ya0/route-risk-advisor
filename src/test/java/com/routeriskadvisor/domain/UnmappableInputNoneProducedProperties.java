package com.routeriskadvisor.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.routeriskadvisor.domain.model.InsuranceRecommendationResult;
import com.routeriskadvisor.domain.model.RecommendationStatus;
import com.routeriskadvisor.domain.model.RiskAssessment;
import com.routeriskadvisor.domain.model.RiskCategory;
import com.routeriskadvisor.domain.model.RiskLevel;
import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteClassification;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based test for {@link DefaultInsuranceAdvisor} implementing design correctness
 * Property 12: unmappable input (a {@link RouteClassification} that contains no Medium/High
 * category and is not an all-Low classification — i.e. empty or entirely Unknown) yields an empty
 * recommendation set with status {@link RecommendationStatus#NONE_PRODUCED}, and the received
 * assessments are returned unchanged.
 *
 * <p><strong>Validates: Requirements 3.7</strong>
 */
class UnmappableInputNoneProducedProperties {

    private final DefaultInsuranceAdvisor advisor = new DefaultInsuranceAdvisor();

    private static final Route ROUTE = new Route("r1", 0, List.of(), 1000.0);

    // Feature: route-risk-advisor, Property 12: Unmappable input yields none-produced with input retained
    @Property(tries = 200)
    void unmappableInputYieldsNoneProducedWithInputRetained(
            @ForAll("unmappableClassifications") RouteClassification classification) {
        // Snapshot the input assessments before invoking so we can assert they are unchanged.
        List<RiskAssessment> inputAssessments = List.copyOf(classification.assessments());

        InsuranceRecommendationResult result = advisor.recommend(classification);

        assertThat(result.status())
            .as("unmappable input must yield NONE_PRODUCED: %s", inputAssessments)
            .isEqualTo(RecommendationStatus.NONE_PRODUCED);

        assertThat(result.recommendations())
            .as("unmappable input must yield an empty recommendation set: %s", inputAssessments)
            .isEmpty();

        assertThat(result.receivedAssessments())
            .as("received assessments must be retained unchanged: %s", inputAssessments)
            .isEqualTo(inputAssessments);
    }

    /**
     * Generates {@link RouteClassification}s that cannot be mapped to any Insurance_Type: an empty
     * assessment list, or a list composed entirely of {@code UNKNOWN} assessments. Neither case
     * contains a Medium/High category nor is an all-Low classification, so both must produce the
     * none-produced outcome (Req 3.7).
     */
    @Provide
    Arbitrary<RouteClassification> unmappableClassifications() {
        return Arbitraries.oneOf(emptyAssessments(), allUnknownAssessments())
            .map(assessments -> new RouteClassification(ROUTE, assessments));
    }

    /** The empty assessment set. */
    private Arbitrary<List<RiskAssessment>> emptyAssessments() {
        return Arbitraries.just(List.of());
    }

    /**
     * A non-empty list of distinct {@code UNKNOWN} assessments drawn from the scored categories
     * (ACCIDENT, THEFT, FIRE, OTHER). Categories are sampled without replacement so the resulting
     * list never repeats a category, mirroring how the classifier reports one assessment per
     * category.
     */
    private Arbitrary<List<RiskAssessment>> allUnknownAssessments() {
        Arbitrary<List<RiskCategory>> categories = Arbitraries.of(RiskCategory.values())
            .list()
            .uniqueElements()
            .ofMinSize(1)
            .ofMaxSize(RiskCategory.values().length);
        return categories.map(cats -> cats.stream()
            .map(category -> new RiskAssessment(category, null, RiskLevel.UNKNOWN))
            .toList());
    }
}
