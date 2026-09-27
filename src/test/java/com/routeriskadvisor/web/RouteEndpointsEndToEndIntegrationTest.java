package com.routeriskadvisor.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.routeriskadvisor.domain.ServiceAreaValidator;
import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.provider.CrashDataProvider;
import com.routeriskadvisor.provider.CrimeDataProvider;
import com.routeriskadvisor.provider.FireDataProvider;
import com.routeriskadvisor.provider.GeocodingProvider;
import com.routeriskadvisor.provider.RoutingProvider;
import com.routeriskadvisor.provider.placeholder.PlaceholderCrashDataProvider;
import com.routeriskadvisor.provider.placeholder.PlaceholderCrimeDataProvider;
import com.routeriskadvisor.provider.placeholder.PlaceholderFireDataProvider;
import com.routeriskadvisor.provider.placeholder.PlaceholderGeocodingProvider;
import com.routeriskadvisor.provider.placeholder.PlaceholderRoutingProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

/**
 * End-to-end integration tests for both REST endpoints, wired through the full Spring application
 * context with the default (placeholder) providers (task 16.2).
 *
 * <p>These tests exercise the complete stack — controller → application services → domain logic →
 * placeholder providers — with no mocking, confirming the placeholder-backed happy paths for
 * {@code POST /api/routes/classify} and {@code POST /api/routes/safest} return well-formed 200
 * responses within a generous time budget, and that the application is correctly scoped to the
 * Miami-Dade, FL Service_Area:
 *
 * <ul>
 *   <li>Classify happy path (Req 2.6, 3.1): a route between two curated in-area places yields a
 *       classification with one assessment per Accident/Theft/Fire category and an insurance
 *       recommendation.</li>
 *   <li>Safest happy path (Req 5.2): a set of curated in-area places yields a per-category
 *       safest-route result.</li>
 *   <li>Smoke (Req 5.2, 6.1, 6.2): exactly one bean per provider interface resolves to the
 *       placeholder implementation, and the configured Service_Area is Miami-Dade, FL with a known
 *       in-county coordinate accepted and an out-of-county coordinate rejected.</li>
 * </ul>
 */
@SpringBootTest(properties = {
    "route-risk-advisor.providers.geocoding=placeholder",
    "route-risk-advisor.providers.routing=placeholder"
})
@AutoConfigureMockMvc
class RouteEndpointsEndToEndIntegrationTest {

    /** Generous per-request wall-clock budget for the placeholder-backed flows. */
    private static final long REQUEST_BUDGET_MS = 5_000L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Test
    @DisplayName("classify happy path returns 200 with a well-formed classification + recommendation")
    void classifyHappyPathReturnsWellFormedBodyWithinBudget() throws Exception {
        long start = System.currentTimeMillis();

        mockMvc.perform(post("/api/routes/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"origin\":\"Downtown Miami\",\"destination\":\"Coral Gables\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.originCoordinate").exists())
            .andExpect(jsonPath("$.destinationCoordinate").exists())
            .andExpect(jsonPath("$.route").exists())
            // One assessment per Accident/Theft/Fire category (Req 2.6).
            .andExpect(jsonPath("$.classification.assessments").isArray())
            .andExpect(jsonPath("$.classification.assessments.length()").value(3))
            .andExpect(jsonPath(
                "$.classification.assessments[?(@.category == 'ACCIDENT')]").exists())
            .andExpect(jsonPath(
                "$.classification.assessments[?(@.category == 'THEFT')]").exists())
            .andExpect(jsonPath(
                "$.classification.assessments[?(@.category == 'FIRE')]").exists())
            // Insurance recommendation is present (Req 3.1).
            .andExpect(jsonPath("$.recommendation").exists())
            .andExpect(jsonPath("$.recommendation.status").exists());

        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed)
            .as("classify should complete within a reasonable budget")
            .isLessThan(REQUEST_BUDGET_MS);
    }

    @Test
    @DisplayName("safest happy path returns 200 with a per-category result")
    void safestHappyPathReturnsPerCategoryResultWithinBudget() throws Exception {
        long start = System.currentTimeMillis();

        mockMvc.perform(post("/api/routes/safest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"locations\":[\"Downtown Miami\",\"Coral Gables\",\"Miami Beach\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.perCategory").isArray())
            .andExpect(jsonPath("$.perCategory.length()").value(
                org.hamcrest.Matchers.greaterThan(0)))
            .andExpect(jsonPath("$.perCategory[0].category").exists());

        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed)
            .as("safest should complete within a reasonable budget")
            .isLessThan(REQUEST_BUDGET_MS);
    }

    @Test
    @DisplayName("exactly one placeholder bean is wired for each of the five provider interfaces")
    void exactlyOnePlaceholderBeanPerProviderInterface() {
        assertSinglePlaceholderBean(GeocodingProvider.class, PlaceholderGeocodingProvider.class);
        assertSinglePlaceholderBean(RoutingProvider.class, PlaceholderRoutingProvider.class);
        assertSinglePlaceholderBean(CrashDataProvider.class, PlaceholderCrashDataProvider.class);
        assertSinglePlaceholderBean(CrimeDataProvider.class, PlaceholderCrimeDataProvider.class);
        assertSinglePlaceholderBean(FireDataProvider.class, PlaceholderFireDataProvider.class);
    }

    @Test
    @DisplayName("Service_Area resolves to Miami-Dade, FL and governs coordinate acceptance")
    void serviceAreaResolvesToMiamiDadeFlorida() {
        // Configured Service_Area is Miami-Dade County, FL (Req 6.1).
        assertThat(environment.getProperty("route-risk-advisor.service-area.county"))
            .isEqualTo("Miami-Dade");
        assertThat(environment.getProperty("route-risk-advisor.service-area.state"))
            .isEqualTo("FL");

        // Containment governs acceptance: a known Miami-Dade coordinate is accepted, and a clearly
        // out-of-county coordinate (Seattle) is rejected (Req 6.2).
        ServiceAreaValidator validator = context.getBean(ServiceAreaValidator.class);
        GeoCoordinate downtownMiami = new GeoCoordinate(25.7743, -80.1937);
        GeoCoordinate seattle = new GeoCoordinate(47.6062, -122.3321);
        assertThat(validator.contains(downtownMiami))
            .as("a Miami-Dade coordinate must be inside the Service_Area").isTrue();
        assertThat(validator.contains(seattle))
            .as("a coordinate outside Miami-Dade must be rejected").isFalse();
    }

    private <T> void assertSinglePlaceholderBean(Class<T> iface, Class<? extends T> placeholder) {
        Map<String, T> beans = context.getBeansOfType(iface);
        assertThat(beans)
            .as("exactly one bean should be registered for %s", iface.getSimpleName())
            .hasSize(1);
        assertThat(beans.values().iterator().next())
            .as("%s should resolve to the placeholder implementation", iface.getSimpleName())
            .isInstanceOf(placeholder);
    }
}
