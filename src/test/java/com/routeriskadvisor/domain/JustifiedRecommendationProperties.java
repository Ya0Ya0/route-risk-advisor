package com.routeriskadvisor.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.routeriskadvisor.domain.model.InsuranceRecommendation;
import com.routeriskadvisor.domain.model.InsuranceRecommendationResult;
import com.routeriskadvisor.domain.model.InsuranceType;
import com.routeriskadvisor.domain.model.RiskAssessment;
import com.routeriskadvisor.domain.model.RiskCategory;
import com.routeriskadvisor.domain.model.RiskJustification;
import com.routeriskadvisor.domain.model.RiskLevel;
import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteClassification;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based tests for {@link DefaultInsuranceAdvisor} recommendation justification.
 *
 * <p>Implements design correctness Property 11: for any produced recommendation set, each
 * recommended Insurance_Type carries at least one justifying Risk_Category, and every justifying
 * category has a Medium or High level and maps to that Insurance_Type under the fixed mapping
 * table. The baseline liability recommendation is the documented exception: it is justified by the
 * Low categories of an all-Low classification (per the design's Insurance Mapping Rules), so its
 * justifications are all Low rather than Medium/High.
 *
 * <p><strong>Validates: Requirements 3.6</strong>
 */
class JustifiedRecommendationProperties {

    private final DefaultInsuranceAdvisor advisor = new DefaultInsuranceAdvisor();

    private static final Route ROUTE = new Route("r1", 0, List.of(), 1000.0);

    /** The fixed category-to-coverage table used as the justification oracle. */
    private static final Map<RiskCategory, InsuranceType> COVERAGE_BY_CATEGORY =
        buildCoverageTable();

    private static Map<RiskCategory, InsuranceType> buildCoverageTable() {
        Map<RiskCategory, InsuranceType> table = new EnumMap<>(RiskCategory.class);
        table.put(RiskCategory.ACCIDENT, InsuranceType.COLLISION_COVERAGE);
        table.put(RiskCategory.THEFT, InsuranceType.COMPREHENSIVE_THEFT_COVERAGE);
        table.put(RiskCategory.FIRE, InsuranceType.COMPREHENSIVE_FIRE_COVERAGE);
        return table;
    }

    // Feature: route-risk-advisor, Property 11: Every recommendation is justified
    @Property(tries = 200)
    void everyRecommendationIsJustifiedByItsMappingCategories(
            @ForAll("variedClassifications") RouteClassification classification) {
        InsuranceRecommendationResult result = advisor.recommend(classification);

        for (InsuranceRecommendation recommendation : result.recommendations()) {
            List<RiskJustification> justifications = recommendation.justifiedBy();

            // Every produced recommendation carries at least one justifying category (Req 3.6).
            assertThat(justifications)
                .as("recommendation %s must carry at least one justification for %s",
                    recommendation.insuranceType(), classification.assessments())
                .isNotNull()
                .isNotEmpty();

            if (recommendation.insuranceType() == InsuranceType.LIABILITY_BASELINE) {
                // Documented exception: the baseline is justified by the Low categories of an
                // all-Low classification, so every justification is Low (per the design).
                assertThat(justifications)
                    .as("baseline liability must be justified only by Low categories for %s",
                        classification.assessments())
                    .allSatisfy(j -> assertThat(j.level()).isEqualTo(RiskLevel.LOW));
            } else {
                // Coverage recommendations: every justifying category is Medium/High and maps to
                // exactly this coverage under the fixed table (Req 3.6).
                assertThat(justifications)
                    .as("every justification for coverage %s must be Medium/High and map to it "
                        + "for %s", recommendation.insuranceType(), classification.assessments())
                    .allSatisfy(j -> {
                        assertThat(j.level())
                            .as("justifying level must be Medium or High")
                            .isIn(RiskLevel.MEDIUM, RiskLevel.HIGH);
                        assertThat(COVERAGE_BY_CATEGORY.get(j.category()))
                            .as("justifying category %s must map to coverage %s",
                                j.category(), recommendation.insuranceType())
                            .isEqualTo(recommendation.insuranceType());
                    });
            }
        }
    }

    /**
     * Generates {@link RouteClassification}s over ACCIDENT/THEFT/FIRE where each category is
     * independently assigned MEDIUM, HIGH, LOW, or UNKNOWN. The full mix exercises every
     * recommendation-producing path so the justification invariant is checked broadly:
     * <ul>
     *   <li>at least one Medium/High &rarr; coverage recommendations justified by Medium/High
     *       categories;</li>
     *   <li>all Low &rarr; the baseline exception justified by Low categories;</li>
     *   <li>empty/all-Unknown/mixed Low+Unknown &rarr; no recommendations, so the loop is
     *       vacuously satisfied.</li>
     * </ul>
     * Randomizing scores within each level's banding range widens the input space well beyond a
     * handful of fixed cases.
     */
    @Provide
    Arbitrary<RouteClassification> variedClassifications() {
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
            });
    }

    /**
     * Generates a valid {@link RiskAssessment} for a category over all four levels. UNKNOWN carries
     * no score; scored levels draw a random score from the level's banding range (Low 0-33,
     * Medium 34-66, High 67-100).
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
}
