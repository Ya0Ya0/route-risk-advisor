package com.routeriskadvisor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.routeriskadvisor.config.TimeoutProperties;
import com.routeriskadvisor.domain.InsuranceAdvisor;
import com.routeriskadvisor.domain.RouteClassifier;
import com.routeriskadvisor.domain.ServiceAreaValidator;
import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.domain.model.InsuranceRecommendationResult;
import com.routeriskadvisor.domain.model.RecommendationStatus;
import com.routeriskadvisor.domain.model.Route;
import com.routeriskadvisor.domain.model.RouteClassification;
import com.routeriskadvisor.provider.GeocodingProvider;
import com.routeriskadvisor.provider.ProviderException;
import com.routeriskadvisor.provider.RoutingProvider;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-based test for {@link RouteRiskService} input validation.
 *
 * <p>Implements design correctness Property 1: a classification submission is accepted — and only
 * then forwarded to the Geocoding_Provider — if and only if each of the origin and destination
 * strings, after trimming, is non-empty and at most 250 characters. When a submission is rejected
 * the service raises an {@link InvalidLocationInputException} naming exactly the invalid field,
 * touches no provider (so nothing is forwarded to geocoding), and mutates no stored data — the
 * caller's prior values are retained because rejection is pure.
 *
 * <p>Validation happens in origin-then-destination order, so when both are invalid the first
 * offending field (origin) is the one named.
 *
 * <p><strong>Validates: Requirements 1.1, 1.2, 6.5</strong>
 */
class InputValidationProperties {

    /** Records whether a provider method was invoked so the test can assert "no side effects on rejection". */
    private static final class RecordingGeocodingProvider implements GeocodingProvider {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Optional<GeoCoordinate> geocode(String locationDescription) {
            calls.incrementAndGet();
            // Return empty: a valid input reaches geocoding and then fails to resolve. The test only
            // asserts that geocoding was reached for valid input, not the downstream outcome.
            return Optional.empty();
        }
    }

    /** Records any routing call; on rejection or geocoding-empty paths it must never be invoked. */
    private static final class RecordingRoutingProvider implements RoutingProvider {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Optional<Route> route(GeoCoordinate origin, GeoCoordinate destination) {
            calls.incrementAndGet();
            return Optional.empty();
        }

        @Override
        public List<Route> candidateRoutes(List<GeoCoordinate> orderedPoints) {
            calls.incrementAndGet();
            return List.of();
        }
    }

    private RouteRiskService newService(
            RecordingGeocodingProvider geocoding, RecordingRoutingProvider routing) {
        // Classifier/advisor are never reached in this test (geocoding returns empty first), but the
        // service requires non-null collaborators. Supply inert stubs.
        RouteClassifier classifier = route -> new RouteClassification(route, List.of());
        InsuranceAdvisor advisor = classification -> new InsuranceRecommendationResult(
            List.of(), RecommendationStatus.NONE_PRODUCED, classification.assessments());
        return new RouteRiskService(
            geocoding,
            routing,
            new ServiceAreaValidator(),
            classifier,
            advisor,
            new TimeoutExecutor(),
            new TimeoutProperties());
    }

    private static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        int trimmed = value.trim().length();
        return trimmed >= 1 && trimmed <= 250;
    }

    // Feature: route-risk-advisor, Property 1: Input validation accepts iff within bounds
    @Property(tries = 200)
    void submissionAcceptedIffBothLocationsWithinBounds(
            @ForAll("locationInputs") String origin,
            @ForAll("locationInputs") String destination) {

        RecordingGeocodingProvider geocoding = new RecordingGeocodingProvider();
        RecordingRoutingProvider routing = new RecordingRoutingProvider();
        RouteRiskService service = newService(geocoding, routing);

        boolean originValid = isValid(origin);
        boolean destinationValid = isValid(destination);
        boolean bothValid = originValid && destinationValid;

        Throwable thrown = catchThrowable(() -> service.classify(origin, destination));

        if (bothValid) {
            // Accepted: the submission is forwarded to geocoding. Both locations are geocoded before
            // any other step, and (here) resolution returns empty, so the flow ends in a not-resolved
            // error rather than an input-validation error.
            assertThat(geocoding.calls.get())
                .as("valid submission (origin=%d, dest=%d trimmed chars) must be forwarded to geocoding",
                    origin.trim().length(), destination.trim().length())
                .isGreaterThanOrEqualTo(1);
            assertThat(thrown)
                .as("valid submission must not be rejected as invalid input")
                .isNotInstanceOf(InvalidLocationInputException.class);
        } else {
            // Rejected: an InvalidLocationInputException naming exactly the first invalid field, with
            // no geocoding or routing call (nothing forwarded, no stored data altered).
            assertThat(thrown)
                .as("submission with an out-of-bounds location must be rejected as invalid input")
                .isInstanceOf(InvalidLocationInputException.class);

            InvalidLocationInputException ex = (InvalidLocationInputException) thrown;
            String expectedField = !originValid
                ? RouteRiskService.FIELD_ORIGIN
                : RouteRiskService.FIELD_DESTINATION;
            assertThat(ex.field())
                .as("rejection must identify exactly the invalid location field")
                .isEqualTo(expectedField);
            assertThat(ex.code())
                .as("rejection carries the INVALID_INPUT code")
                .isEqualTo(InvalidLocationInputException.CODE);

            assertThat(geocoding.calls.get())
                .as("rejected submission must not be forwarded to geocoding")
                .isZero();
            assertThat(routing.calls.get())
                .as("rejected submission must not reach routing")
                .isZero();
        }
    }

    /**
     * Generates origin/destination candidate strings spanning the boundary lengths (0, 1, 250, 251)
     * plus whitespace-only content, mixing valid and invalid inputs so both branches of the iff are
     * exercised.
     */
    @Provide
    Arbitrary<String> locationInputs() {
        // Non-whitespace payloads at boundary lengths: 0 (empty), 1, 250 (max valid), 251 (over).
        Arbitrary<Integer> boundaryLength = Arbitraries.of(0, 1, 249, 250, 251, 300);
        Arbitrary<String> exactLength = boundaryLength.map(InputValidationProperties::alpha);

        // Whitespace-only strings of assorted lengths: always invalid because they trim to empty.
        Arbitrary<String> whitespaceOnly = Arbitraries.integers().between(1, 10)
            .map(n -> " \t\n".repeat(Math.max(1, n / 3 + 1)).substring(0, Math.min(n, 5)));

        // Padded content: leading/trailing whitespace around a valid core, to confirm trimming drives
        // the decision (a 250-char core with padding is still valid; a 251-char core is not).
        Arbitrary<String> padded = Combinators.combine(
                Arbitraries.of(1, 250, 251),
                Arbitraries.strings().withChars(' ', '\t').ofMinLength(0).ofMaxLength(4))
            .as((core, pad) -> pad + alpha(core) + pad);

        // Empty and blank literals to nail the lower boundary explicitly.
        Arbitrary<String> literals = Arbitraries.of("", " ", "   ", "\t", "\n");

        return Arbitraries.oneOf(exactLength, whitespaceOnly, padded, literals);
    }

    private static String alpha(int length) {
        if (length <= 0) {
            return "";
        }
        return "a".repeat(length);
    }
}
