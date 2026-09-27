package com.routeriskadvisor.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.provider.CrashDataProvider;
import com.routeriskadvisor.provider.CrimeDataProvider;
import com.routeriskadvisor.provider.FireDataProvider;
import com.routeriskadvisor.provider.GeocodingProvider;
import com.routeriskadvisor.provider.RoutingProvider;
import com.routeriskadvisor.provider.ors.OpenRouteServiceGeocodingProvider;
import com.routeriskadvisor.provider.ors.OpenRouteServiceRoutingProvider;
import com.routeriskadvisor.provider.placeholder.PlaceholderCrashDataProvider;
import com.routeriskadvisor.provider.placeholder.PlaceholderCrimeDataProvider;
import com.routeriskadvisor.provider.placeholder.PlaceholderFireDataProvider;
import com.routeriskadvisor.provider.placeholder.PlaceholderGeocodingProvider;
import com.routeriskadvisor.provider.placeholder.PlaceholderRoutingProvider;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Integration tests for provider selection and startup validation (Task 12.3; Req 5.4, 5.5, 5.7).
 *
 * <p>These tests exercise the real {@link ProviderConfiguration} {@code @ConditionalOnProperty}
 * beans together with the {@link ProviderStartupValidator} using Spring Boot's
 * {@link ApplicationContextRunner}. The context runner is preferred over a full
 * {@code @SpringBootTest} so each scenario can drive the {@code route-risk-advisor.providers.*}
 * keys independently and assert on startup failure without aborting the whole JVM.
 *
 * <p>Scenarios covered:
 * <ol>
 *   <li><b>Default (Req 5.5):</b> with no {@code providers.*} keys set — mirroring
 *       {@code matchIfMissing = true} — each of the five interfaces resolves to its placeholder
 *       bean.</li>
 *   <li><b>Real implementation selected (Req 5.4):</b> when a key selects a stub "real" bean via a
 *       distinct {@code havingValue}, calls route to the real bean and the placeholder bean is not
 *       registered at all.</li>
 *   <li><b>Unresolvable implementation (Req 5.7):</b> when a key names an implementation with no
 *       matching bean, startup fails with {@link ProviderConfigurationException} whose message
 *       names both the interface and the unresolved implementation.</li>
 * </ol>
 */
class ProviderSelectionIntegrationTest {

    /**
     * Base runner wiring the production {@link ProviderConfiguration} and
     * {@link ProviderStartupValidator}. The validator runs as a {@code SmartInitializingSingleton},
     * so any resolution failure surfaces as a startup failure on the context.
     */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(ProviderConfiguration.class, ProviderStartupValidator.class)
        // ProviderConfiguration's ORS beans depend on these @ConfigurationProperties holders; the
        // full application enables them via @EnableConfigurationProperties, but the slice runner
        // must supply them explicitly.
        .withBean(OrsProperties.class)
        .withBean(TimeoutProperties.class);

    // ------------------------------------------------------------------
    // Scenario 1: default configuration routes each interface to its default implementation.
    // With no providers.* keys set, geocoding/routing resolve to the OpenRouteService providers
    // (the new default), while crash/crime/fire fall back to their placeholders via
    // matchIfMissing = true (Req 5.5).
    // ------------------------------------------------------------------

    @Test
    void defaultConfigurationResolvesGeocodingAndRoutingToOrsAndDataProvidersToPlaceholders() {
        contextRunner
            .withPropertyValues(
                "route-risk-advisor.providers.geocoding=openrouteservice",
                "route-risk-advisor.providers.routing=openrouteservice")
            .run(context -> {
                assertThat(context).hasNotFailed();

                assertThat(context.getBean(GeocodingProvider.class))
                    .isInstanceOf(OpenRouteServiceGeocodingProvider.class);
                assertThat(context.getBean(RoutingProvider.class))
                    .isInstanceOf(OpenRouteServiceRoutingProvider.class);
                assertThat(context.getBean(CrashDataProvider.class))
                    .isInstanceOf(PlaceholderCrashDataProvider.class);
                assertThat(context.getBean(CrimeDataProvider.class))
                    .isInstanceOf(PlaceholderCrimeDataProvider.class);
                assertThat(context.getBean(FireDataProvider.class))
                    .isInstanceOf(PlaceholderFireDataProvider.class);

                // Exactly one bean per interface (the validator's invariant).
                assertThat(context.getBeanNamesForType(GeocodingProvider.class)).hasSize(1);
                assertThat(context.getBeanNamesForType(RoutingProvider.class)).hasSize(1);
                assertThat(context.getBeanNamesForType(CrashDataProvider.class)).hasSize(1);
                assertThat(context.getBeanNamesForType(CrimeDataProvider.class)).hasSize(1);
                assertThat(context.getBeanNamesForType(FireDataProvider.class)).hasSize(1);
            });
    }

    @Test
    void explicitPlaceholderValueResolvesToPlaceholder() {
        contextRunner
            .withPropertyValues(
                "route-risk-advisor.providers.geocoding=placeholder",
                "route-risk-advisor.providers.routing=placeholder",
                "route-risk-advisor.providers.crash=placeholder",
                "route-risk-advisor.providers.crime=placeholder",
                "route-risk-advisor.providers.fire=placeholder")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(GeocodingProvider.class))
                    .isInstanceOf(PlaceholderGeocodingProvider.class);
            });
    }

    // ------------------------------------------------------------------
    // Scenario 2: a configured "real" implementation receives calls; placeholder is NOT registered
    // (Req 5.4)
    // ------------------------------------------------------------------

    @Test
    void configuredRealImplementationReceivesCallsAndPlaceholderIsNotRegistered() {
        contextRunner
            .withUserConfiguration(StubRealGeocodingConfiguration.class)
            .withPropertyValues(
                "route-risk-advisor.providers.geocoding=stub",
                // Routing has no matchIfMissing default anymore, so select the placeholder
                // explicitly to keep exactly one routing bean in this scenario.
                "route-risk-advisor.providers.routing=placeholder")
            .run(context -> {
                assertThat(context).hasNotFailed();

                // Exactly one GeocodingProvider bean, and it is the stub real one.
                assertThat(context.getBeanNamesForType(GeocodingProvider.class)).hasSize(1);
                GeocodingProvider geocoding = context.getBean(GeocodingProvider.class);
                assertThat(geocoding).isInstanceOf(StubRealGeocodingProvider.class);

                // The placeholder bean is not present in the context at all.
                assertThat(context.getBeanNamesForType(PlaceholderGeocodingProvider.class))
                    .isEmpty();

                // Calls route to the real implementation, which records that it was invoked.
                StubRealGeocodingProvider stub = (StubRealGeocodingProvider) geocoding;
                Optional<GeoCoordinate> result = geocoding.geocode("anywhere");

                assertThat(stub.invocationCount()).isEqualTo(1);
                assertThat(result).contains(StubRealGeocodingProvider.STUB_COORDINATE);

                // The remaining four interfaces still fall back to their placeholders.
                assertThat(context.getBean(RoutingProvider.class))
                    .isInstanceOf(PlaceholderRoutingProvider.class);
                assertThat(context.getBean(CrashDataProvider.class))
                    .isInstanceOf(PlaceholderCrashDataProvider.class);
                assertThat(context.getBean(CrimeDataProvider.class))
                    .isInstanceOf(PlaceholderCrimeDataProvider.class);
                assertThat(context.getBean(FireDataProvider.class))
                    .isInstanceOf(PlaceholderFireDataProvider.class);
            });
    }

    // ------------------------------------------------------------------
    // Scenario 3: an unresolvable implementation halts startup with a naming error (Req 5.7)
    // ------------------------------------------------------------------

    @Test
    void unresolvableImplementationHaltsStartupWithNamingError() {
        contextRunner
            .withPropertyValues("route-risk-advisor.providers.geocoding=google")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .isInstanceOf(ProviderConfigurationException.class)
                    .hasMessageContaining(GeocodingProvider.class.getName())
                    .hasMessageContaining("google");
            });
    }

    @Test
    void unresolvableImplementationForNonGeocodingInterfaceNamesThatInterface() {
        contextRunner
            .withPropertyValues(
                // Keep geocoding/routing resolvable so the CRASH binding is the one that fails.
                "route-risk-advisor.providers.geocoding=placeholder",
                "route-risk-advisor.providers.routing=placeholder",
                "route-risk-advisor.providers.crash=nhtsa-fars")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .isInstanceOf(ProviderConfigurationException.class)
                    .hasMessageContaining(CrashDataProvider.class.getName())
                    .hasMessageContaining("nhtsa-fars");
            });
    }

    // ------------------------------------------------------------------
    // Test-only stub "real" provider wiring
    // ------------------------------------------------------------------

    /**
     * Registers a stub "real" {@link GeocodingProvider} selected by
     * {@code route-risk-advisor.providers.geocoding=stub}. Because the production placeholder bean
     * is keyed on {@code havingValue = "placeholder"}, selecting {@code stub} leaves the placeholder
     * unregistered, demonstrating Req 5.4 (calls route to the configured real implementation and the
     * placeholder is not invoked).
     */
    @Configuration
    static class StubRealGeocodingConfiguration {

        @Bean
        @ConditionalOnProperty(
            name = "route-risk-advisor.providers.geocoding",
            havingValue = "stub")
        GeocodingProvider stubRealGeocodingProvider() {
            return new StubRealGeocodingProvider();
        }
    }

    /**
     * Deterministic stub standing in for a real geocoding implementation. It records how many times
     * it was invoked so the test can prove calls were routed to it rather than the placeholder.
     */
    static final class StubRealGeocodingProvider implements GeocodingProvider {

        static final GeoCoordinate STUB_COORDINATE = new GeoCoordinate(25.7617, -80.1918);

        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public Optional<GeoCoordinate> geocode(String locationDescription) {
            invocations.incrementAndGet();
            return Optional.of(STUB_COORDINATE);
        }

        int invocationCount() {
            return invocations.get();
        }
    }
}
