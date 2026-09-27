package com.routeriskadvisor.domain;

import com.routeriskadvisor.domain.model.InsuranceRecommendation;
import com.routeriskadvisor.domain.model.InsuranceRecommendationResult;
import com.routeriskadvisor.domain.model.InsuranceType;
import com.routeriskadvisor.domain.model.RecommendationStatus;
import com.routeriskadvisor.domain.model.RiskAssessment;
import com.routeriskadvisor.domain.model.RiskCategory;
import com.routeriskadvisor.domain.model.RiskJustification;
import com.routeriskadvisor.domain.model.RiskLevel;
import com.routeriskadvisor.domain.model.RouteClassification;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Default {@link InsuranceAdvisor} that applies the fixed category-to-coverage mapping,
 * baseline rule, and none-produced rule from the design's "Insurance Mapping Rules".
 *
 * <p>Rules (Req 3):
 * <ol>
 *   <li>Each category with a Medium or High level maps to its coverage via a fixed table:
 *       ACCIDENT&nbsp;&rarr;&nbsp;{@link InsuranceType#COLLISION_COVERAGE},
 *       THEFT&nbsp;&rarr;&nbsp;{@link InsuranceType#COMPREHENSIVE_THEFT_COVERAGE},
 *       FIRE&nbsp;&rarr;&nbsp;{@link InsuranceType#COMPREHENSIVE_FIRE_COVERAGE}
 *       (Req 3.1-3.4). Each recommendation carries the {@link RiskJustification}(s) — category
 *       name and level — that produced it (Req 3.6).</li>
 *   <li>When <em>every</em> assessed category is Low, recommend exactly one
 *       {@link InsuranceType#LIABILITY_BASELINE} and nothing else, justified by the Low
 *       categories (Req 3.5).</li>
 *   <li>{@code Unknown} categories never produce a recommendation. When the received set is
 *       empty or maps to nothing (e.g. all Unknown), return an empty recommendation set with
 *       status {@link RecommendationStatus#NONE_PRODUCED}, retaining the received assessments
 *       unchanged (Req 3.7).</li>
 * </ol>
 */
public class DefaultInsuranceAdvisor implements InsuranceAdvisor {

    /** Fixed mapping of a risk category to the coverage recommended when it is Medium/High. */
    private static final Map<RiskCategory, InsuranceType> COVERAGE_BY_CATEGORY =
        buildCoverageTable();

    private static Map<RiskCategory, InsuranceType> buildCoverageTable() {
        Map<RiskCategory, InsuranceType> table = new EnumMap<>(RiskCategory.class);
        table.put(RiskCategory.ACCIDENT, InsuranceType.COLLISION_COVERAGE);           // Req 3.2
        table.put(RiskCategory.THEFT, InsuranceType.COMPREHENSIVE_THEFT_COVERAGE);    // Req 3.3
        table.put(RiskCategory.FIRE, InsuranceType.COMPREHENSIVE_FIRE_COVERAGE);      // Req 3.4
        return table;
    }

    @Override
    public InsuranceRecommendationResult recommend(RouteClassification classification) {
        if (classification == null) {
            throw new IllegalArgumentException("classification must not be null");
        }
        List<RiskAssessment> assessments = classification.assessments();
        if (assessments == null) {
            throw new IllegalArgumentException("classification.assessments must not be null");
        }

        // Retained unchanged for the result regardless of outcome (Req 3.7). Copy defensively so
        // the returned list is not affected by later mutation of the caller's list.
        List<RiskAssessment> received = List.copyOf(assessments);

        // Map each Medium/High category to its coverage, attaching justifications (Req 3.1-3.4, 3.6).
        List<InsuranceRecommendation> recommendations = mapMediumHigh(received);

        if (!recommendations.isEmpty()) {
            return new InsuranceRecommendationResult(
                recommendations, RecommendationStatus.PRODUCED, received);
        }

        // No Medium/High category mapped. If every assessed category is Low, recommend a single
        // baseline liability coverage (Req 3.5).
        List<RiskJustification> lowJustifications = lowJustifications(received);
        if (isAllLow(received)) {
            InsuranceRecommendation baseline = new InsuranceRecommendation(
                InsuranceType.LIABILITY_BASELINE, lowJustifications);
            return new InsuranceRecommendationResult(
                List.of(baseline), RecommendationStatus.PRODUCED, received);
        }

        // Nothing maps (empty or entirely Unknown, or mixed Low/Unknown): none produced (Req 3.7).
        return new InsuranceRecommendationResult(
            List.of(), RecommendationStatus.NONE_PRODUCED, received);
    }

    /**
     * Builds one recommendation per Medium/High category in encounter order, each justified by
     * that single category and its level (Req 3.1-3.4, 3.6).
     */
    private static List<InsuranceRecommendation> mapMediumHigh(List<RiskAssessment> assessments) {
        List<InsuranceRecommendation> recommendations = new ArrayList<>();
        for (RiskAssessment assessment : assessments) {
            if (!isMediumOrHigh(assessment.level())) {
                continue;
            }
            InsuranceType coverage = COVERAGE_BY_CATEGORY.get(assessment.category());
            if (coverage == null) {
                // Category has no coverage mapping (e.g. OTHER): not mappable here (Req 3.8 scope).
                continue;
            }
            RiskJustification justification =
                new RiskJustification(assessment.category(), assessment.level());
            recommendations.add(new InsuranceRecommendation(coverage, List.of(justification)));
        }
        return recommendations;
    }

    /**
     * Returns true when at least one category was assessed and every assessed category is Low.
     * An empty set is not "all Low" — it maps to nothing (Req 3.7).
     */
    private static boolean isAllLow(List<RiskAssessment> assessments) {
        if (assessments.isEmpty()) {
            return false;
        }
        for (RiskAssessment assessment : assessments) {
            if (assessment.level() != RiskLevel.LOW) {
                return false;
            }
        }
        return true;
    }

    /** Justifications for the baseline recommendation: every Low category and its level (Req 3.6). */
    private static List<RiskJustification> lowJustifications(List<RiskAssessment> assessments) {
        List<RiskJustification> justifications = new ArrayList<>();
        for (RiskAssessment assessment : assessments) {
            if (assessment.level() == RiskLevel.LOW) {
                justifications.add(new RiskJustification(assessment.category(), assessment.level()));
            }
        }
        return List.copyOf(justifications);
    }

    private static boolean isMediumOrHigh(RiskLevel level) {
        return level == RiskLevel.MEDIUM || level == RiskLevel.HIGH;
    }
}
