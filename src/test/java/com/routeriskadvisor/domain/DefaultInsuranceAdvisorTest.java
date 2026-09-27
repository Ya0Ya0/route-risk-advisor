package com.routeriskadvisor.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.routeriskadvisor.domain.model.InsuranceRecommendation;
import com.routeriskadvisor.domain.model.InsuranceRecommendationResult;
import com.routeriskadvisor.domain.model.InsuranceType;
import com.routeriskadvisor.domain.model.RecommendationStatus;
import com.routeriskadvisor.domain.model.RiskAssessment;
import com.routeriskadvisor.domain.model.RiskCategory;
import com.routeriskadvisor.domain.model.RiskJustification;
import com.routeriskadvisor.domain.model.RiskLevel;
import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteClassification;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultInsuranceAdvisor} covering the fixed mapping table (Req 3.2-3.4),
 * the all-Low baseline rule (Req 3.5), justification attachment (Req 3.6), and the none-produced
 * / retained-assessments rule (Req 3.7).
 */
class DefaultInsuranceAdvisorTest {

    private final DefaultInsuranceAdvisor advisor = new DefaultInsuranceAdvisor();

    private static final Route ROUTE =
        new Route("r1", 0, List.of(), 1000.0);

    private static RouteClassification classification(RiskAssessment... assessments) {
        return new RouteClassification(ROUTE, List.of(assessments));
    }

    private static RiskAssessment scored(RiskCategory category, RiskLevel level, int score) {
        return new RiskAssessment(category, score, level);
    }

    private static RiskAssessment unknown(RiskCategory category) {
        return new RiskAssessment(category, null, RiskLevel.UNKNOWN);
    }

    private static Map<InsuranceType, InsuranceRecommendation> byType(
        InsuranceRecommendationResult result) {
        return result.recommendations().stream()
            .collect(Collectors.toMap(InsuranceRecommendation::insuranceType, Function.identity()));
    }

    @Test
    void mapsAccidentMediumToCollisionCoverage() {
        InsuranceRecommendationResult result = advisor.recommend(
            classification(scored(RiskCategory.ACCIDENT, RiskLevel.MEDIUM, 50)));

        assertEquals(RecommendationStatus.PRODUCED, result.status());
        assertEquals(1, result.recommendations().size());
        InsuranceRecommendation rec = result.recommendations().get(0);
        assertEquals(InsuranceType.COLLISION_COVERAGE, rec.insuranceType());
        assertEquals(
            List.of(new RiskJustification(RiskCategory.ACCIDENT, RiskLevel.MEDIUM)),
            rec.justifiedBy());
    }

    @Test
    void mapsTheftHighToComprehensiveTheftCoverage() {
        InsuranceRecommendationResult result = advisor.recommend(
            classification(scored(RiskCategory.THEFT, RiskLevel.HIGH, 90)));

        InsuranceRecommendation rec = result.recommendations().get(0);
        assertEquals(InsuranceType.COMPREHENSIVE_THEFT_COVERAGE, rec.insuranceType());
        assertEquals(
            List.of(new RiskJustification(RiskCategory.THEFT, RiskLevel.HIGH)),
            rec.justifiedBy());
    }

    @Test
    void mapsFireMediumToComprehensiveFireCoverage() {
        InsuranceRecommendationResult result = advisor.recommend(
            classification(scored(RiskCategory.FIRE, RiskLevel.MEDIUM, 40)));

        assertEquals(
            InsuranceType.COMPREHENSIVE_FIRE_COVERAGE,
            result.recommendations().get(0).insuranceType());
    }

    @Test
    void mapsMultipleMediumHighCategoriesEachToItsCoverage() {
        InsuranceRecommendationResult result = advisor.recommend(classification(
            scored(RiskCategory.ACCIDENT, RiskLevel.HIGH, 80),
            scored(RiskCategory.THEFT, RiskLevel.MEDIUM, 50),
            scored(RiskCategory.FIRE, RiskLevel.LOW, 10)));

        assertEquals(RecommendationStatus.PRODUCED, result.status());
        Map<InsuranceType, InsuranceRecommendation> byType = byType(result);
        // Two mapped coverages, and no baseline because not all categories are Low.
        assertEquals(2, result.recommendations().size());
        assertTrue(byType.containsKey(InsuranceType.COLLISION_COVERAGE));
        assertTrue(byType.containsKey(InsuranceType.COMPREHENSIVE_THEFT_COVERAGE));
    }

    @Test
    void allLowRecommendsExactlyOneBaselineLiability() {
        InsuranceRecommendationResult result = advisor.recommend(classification(
            scored(RiskCategory.ACCIDENT, RiskLevel.LOW, 10),
            scored(RiskCategory.THEFT, RiskLevel.LOW, 5),
            scored(RiskCategory.FIRE, RiskLevel.LOW, 0)));

        assertEquals(RecommendationStatus.PRODUCED, result.status());
        assertEquals(1, result.recommendations().size());
        InsuranceRecommendation rec = result.recommendations().get(0);
        assertEquals(InsuranceType.LIABILITY_BASELINE, rec.insuranceType());
        // Baseline is justified by the Low categories (Req 3.6).
        assertEquals(3, rec.justifiedBy().size());
        assertTrue(rec.justifiedBy().stream().allMatch(j -> j.level() == RiskLevel.LOW));
    }

    @Test
    void allUnknownYieldsNoneProducedAndRetainsAssessments() {
        RiskAssessment a = unknown(RiskCategory.ACCIDENT);
        RiskAssessment t = unknown(RiskCategory.THEFT);
        RiskAssessment f = unknown(RiskCategory.FIRE);
        RouteClassification input = classification(a, t, f);

        InsuranceRecommendationResult result = advisor.recommend(input);

        assertEquals(RecommendationStatus.NONE_PRODUCED, result.status());
        assertTrue(result.recommendations().isEmpty());
        assertEquals(List.of(a, t, f), result.receivedAssessments());
    }

    @Test
    void emptyAssessmentsYieldNoneProducedAndRetainsEmpty() {
        InsuranceRecommendationResult result = advisor.recommend(classification());

        assertEquals(RecommendationStatus.NONE_PRODUCED, result.status());
        assertTrue(result.recommendations().isEmpty());
        assertTrue(result.receivedAssessments().isEmpty());
    }

    @Test
    void mixedLowAndUnknownIsNotAllLowSoNoneProduced() {
        // No Medium/High and not every category Low (one Unknown) => nothing maps (Req 3.7).
        RouteClassification input = classification(
            scored(RiskCategory.ACCIDENT, RiskLevel.LOW, 10),
            unknown(RiskCategory.THEFT));

        InsuranceRecommendationResult result = advisor.recommend(input);

        assertEquals(RecommendationStatus.NONE_PRODUCED, result.status());
        assertTrue(result.recommendations().isEmpty());
        assertEquals(2, result.receivedAssessments().size());
    }

    @Test
    void unknownCategoriesDoNotSuppressMediumHighMapping() {
        InsuranceRecommendationResult result = advisor.recommend(classification(
            scored(RiskCategory.ACCIDENT, RiskLevel.HIGH, 75),
            unknown(RiskCategory.THEFT),
            unknown(RiskCategory.FIRE)));

        assertEquals(RecommendationStatus.PRODUCED, result.status());
        assertEquals(1, result.recommendations().size());
        assertEquals(
            InsuranceType.COLLISION_COVERAGE,
            result.recommendations().get(0).insuranceType());
    }

    @Test
    void everyProducedRecommendationCarriesAtLeastOneJustification() {
        InsuranceRecommendationResult result = advisor.recommend(classification(
            scored(RiskCategory.ACCIDENT, RiskLevel.HIGH, 80),
            scored(RiskCategory.FIRE, RiskLevel.MEDIUM, 40)));

        assertTrue(result.recommendations().stream()
            .allMatch(r -> r.justifiedBy() != null && !r.justifiedBy().isEmpty()));
    }
}
