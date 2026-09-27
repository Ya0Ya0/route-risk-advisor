package com.routeriskadvisor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.routeriskadvisor.domain.model.RiskCategory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Verifies that {@link RouteRiskService} wires correctly in the Spring context (domain beans,
 * providers, timeout enforcement) and that the placeholder-backed classification flow produces a
 * result and maps the key business outcomes to the expected typed exceptions.
 *
 * <p>This is orchestration-wiring verification for task 13.1; the exhaustive property/integration
 * coverage lives in tasks 13.2-13.5.
 */
@SpringBootTest(properties = {
    "route-risk-advisor.providers.geocoding=placeholder",
    "route-risk-advisor.providers.routing=placeholder"
})
class RouteRiskServiceWiringTest {

    @Autowired
    RouteRiskService service;

    @Test
    void happyPathClassifiesAndRecommends() throws Exception {
        RouteClassificationResult result = service.classify("Downtown Miami", "Miami Beach");

        assertThat(result).isNotNull();
        assertThat(result.originCoordinate()).isNotNull();
        assertThat(result.destinationCoordinate()).isNotNull();
        assertThat(result.route()).isNotNull();
        // One assessment per Accident, Theft, Fire (Req 2.6).
        assertThat(result.classification().assessments())
            .extracting(a -> a.category())
            .contains(RiskCategory.ACCIDENT, RiskCategory.THEFT, RiskCategory.FIRE);
        assertThat(result.recommendation()).isNotNull();
    }

    @Test
    void blankOriginIsRejectedAsInvalidInputNamingTheField() {
        assertThatThrownBy(() -> service.classify("   ", "Miami Beach"))
            .isInstanceOf(InvalidLocationInputException.class)
            .satisfies(ex -> {
                InvalidLocationInputException e = (InvalidLocationInputException) ex;
                assertThat(e.field()).isEqualTo("origin");
                assertThat(e.code()).isEqualTo("INVALID_INPUT");
            });
    }

    @Test
    void tooLongDestinationIsRejectedNamingTheField() {
        String tooLong = "a".repeat(251);
        assertThatThrownBy(() -> service.classify("Downtown Miami", tooLong))
            .isInstanceOf(InvalidLocationInputException.class)
            .satisfies(ex -> assertThat(((InvalidLocationInputException) ex).field()).isEqualTo("destination"));
    }

    @Test
    void unresolvableLocationIsReportedByName() {
        assertThatThrownBy(() -> service.classify("Nowhere Unknownville", "Miami Beach"))
            .isInstanceOf(LocationNotResolvedException.class)
            .satisfies(ex -> assertThat(((LocationNotResolvedException) ex).field()).isEqualTo("origin"));
    }

    @Test
    void identicalLocationsShortCircuitAsSameLocation() {
        assertThatThrownBy(() -> service.classify("Downtown Miami", "downtown miami"))
            .isInstanceOf(SameLocationException.class);
    }
}
