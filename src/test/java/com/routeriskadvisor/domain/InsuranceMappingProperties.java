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
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for {@link DefaultInsuranceAdvisor} insurance mapping.
 *
 * <p>Implements design correctness Property 9: for any RouteClassification, the set of recommended
 * Insurance_Types equals exactly the set obtained by mapping each category at Medium or High level
 * through the fixed table (Accident&nbsp;&rarr;&nbsp;COLLISION_COVERAGE,
 * Theft&nbsp;&rarr;&nbsp;COMPREHENSIVE_THEFT_COVERAGE,
 * Fire&nbsp;&rarr;&nbsp;COMPREHENSIVE_FIRE_COVERAGE).
 *
 * <p><strong>Validates: Requirements 3.1, 3.2, 3.3, 3.4</strong>
 */
class InsuranceMappingProperties {

    private final DefaultInsuranceAdvisor advisor = new DefaultInsuranceAdvisor();

    private static final Route ROUTE = new Route("r1", 0, List.of(), 1000.0);

    /** The fixed category-to-coverage table under test (the expected oracle). */
    private static final Map<RiskCategory, InsuranceType> COVERAGE_BY_CATEGORY =
        buildCoverageTable();

    private static Map<RiskCategory, InsuranceType> buildCoverageTable() {
        Map<RiskCategory, InsuranceType> table = new EnumMap<>(RiskCategory.class);
        table.put(RiskCategory.ACCIDENT, InsuranceType.COLLISION_COVERAGE);
        table.put(RiskCategory.THEFT, InsuranceType.COMPREHENSIVE_THEFT_COVERAGE);
        table.put(RiskCategory.FIRE, InsuranceType.COMPREHENSIVE_FIRE_COVERAGE);
        return table;
    }

    // Feature: route-risk-advisor, Property 9: Insurance mapping for Medium and High categories
    @Property(tries = 200)
    void recommendedInsuranceTypesEqualMappedMediumHighCategories(
            @ForAll("mixedMediumHighClassifications") RouteClassification classification) {
        InsuranceRecommendationResult result = advisor.recommend(classification);

        // Expected set: map each Medium/High category through the fixed table.
        Set<InsuranceType> expected = new LinkedHashSet<>();
        for (RiskAssessment assessment : classification.assessments()) {
            if (assessment.level() == RiskLevel.MEDIUM || assessment.level() == RiskLevel.HIGH) {
                expected.add(COVERAGE_BY_CATEGORY.get(assessment.category()));
            }
        }

        Set<InsuranceType> actual = new LinkedHashSet<>();
        for (InsuranceRecommendation recommendation : result.recommendations()) {
            actual.add(recommendation.insuranceType());
        }

        assertThat(actual)
            .as("recommended Insurance_Types must equal exactly the fixed-table mapping of "
                + "Medium/High categories for %s", classification.assessments())
            .isEqualTo(expected);
    }

    /**
     * Generates {@link RouteClassification}s over ACCIDENT/THEFT/FIRE where each category is
     * independently assigned MEDIUM, HIGH, LOW, or UNKNOWN, constrained so that at least one
     * category is MEDIUM or HIGH.
     *
     * <p>The at-least-one-Medium/High constraint keeps the classification on the mapping path
     * (Req 3.1-3.4) rather than the all-Low baseline path (Req 3.5) or the none-produced path
     * (Req 3.7), which are covered by their own properties. The mix of LOW/UNKNOWN alongside the
     * Medium/High categories exercises that non-mappable levels do not contribute coverages.
     */
    @Provide
    Arbitrary<RouteClassification> mixedMediumHighClassifications() {
        Arbitrary<RiskAssessment> accident = assessments(RiskCategory.ACCIDENT);
        Arbitrary<RiskAssessment> theft = assessments(RiskCategory.THEFT);
        Arbitrary<RiskAssessment> fire = assessments(RiskCategory.FIRE);

        return Combinators.combine(accident, theft, fire)
            .as((a, t, f) -> {
                List<RiskAssessment> list = new ArrayList<>();
                list.add(a);
                list.add(t);
                list.add(f);
                return new RouteClassification(ROUTE, list);
            })
            .filter(this::hasAtLeastOneMediumOrHigh);
    }

    /**
     * Generates a valid {@link RiskAssessment} for a category over all four levels. UNKNOWN carries
     * no score; scored levels carry a score drawn randomly from that level's banding range
     * (Low 0-33, Medium 34-66, High 67-100). Randomizing scores across the bands widens the input
     * space well past 100 distinct cases so the property is exercised over many generated inputs
     * rather than exhausted after a handful.
     */
    private Arbitrary<RiskAssessment> assessments(RiskCategory category) {
        Arbitrary<RiskAssessment> unknown =
            Arbitraries.just(new RiskAssessment(category, null, RiskLevel.UNKNOWN));
        Arbitrary<RiskAssessment> low = Arbitraries.integers().between(0, 33)
            .map(score -> new RiskAssessment(category, score, RiskLevel.LOW));
        Arbitrary<RiskAssessment> medium = Arbitraries.integers().between(34, 66)
            .map(score -> new RiskAssessment(category, score, RiskLevel.MEDIUM));
        Arbitrary<RiskAssessment> high = Arbitraries.integers().between(67, 100)
            .map(score -> new RiskAssessment(category, score, RiskLevel.HIGH));
        return Arbitraries.oneOf(unknown, low, medium, high);
    }

    private boolean hasAtLeastOneMediumOrHigh(RouteClassification classification) {
        return classification.assessments().stream()
            .anyMatch(a -> a.level() == RiskLevel.MEDIUM || a.level() == RiskLevel.HIGH);
    }
}
