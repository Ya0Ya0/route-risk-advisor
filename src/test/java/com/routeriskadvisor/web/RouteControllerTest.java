package com.routeriskadvisor.web;

import com.routeriskadvisor.domain.model.CategoryRecommendation;
import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.domain.model.RiskCategory;
import com.routeriskadvisor.domain.model.RiskLevel;
import com.routeriskadvisor.domain.model.SafestRouteResult;
import com.routeriskadvisor.provider.GeocodingProvider;
import com.routeriskadvisor.service.InvalidLocationInputException;
import com.routeriskadvisor.service.LocationNotResolvedException;
import com.routeriskadvisor.service.NoRouteFoundException;
import com.routeriskadvisor.service.OutOfServiceAreaException;
import com.routeriskadvisor.service.RouteRiskService;
import com.routeriskadvisor.service.SafestRouteException;
import com.routeriskadvisor.service.SafestRouteService;
import com.routeriskadvisor.service.SameLocationException;
import com.routeriskadvisor.service.ServiceUnavailableException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for {@link RouteController} + {@link ApiExceptionHandler}: verify the two endpoints
 * wire to the services and that each typed business outcome maps to the shared
 * {@code { code, message, field? }} envelope with the correct HTTP status (task 16.1).
 */
@WebMvcTest(RouteController.class)
class RouteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RouteRiskService routeRiskService;

    @MockBean
    private SafestRouteService safestRouteService;

    @MockBean
    private GeocodingProvider geocodingProvider;

    // --- classify: error status mapping -----------------------------------------------------

    @Test
    void classifyInvalidInputMapsTo400WithField() throws Exception {
        when(routeRiskService.classify(any(), any()))
            .thenThrow(new InvalidLocationInputException("origin", "bad"));

        mockMvc.perform(post("/api/routes/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"origin\":\"\",\"destination\":\"x\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.field").value("origin"))
            .andExpect(jsonPath("$.message").value("bad"));
    }

    @Test
    void classifyOutOfServiceAreaMapsTo400() throws Exception {
        when(routeRiskService.classify(any(), any()))
            .thenThrow(new OutOfServiceAreaException("destination", "outside"));

        mockMvc.perform(post("/api/routes/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"origin\":\"a\",\"destination\":\"b\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("OUT_OF_SERVICE_AREA"))
            .andExpect(jsonPath("$.field").value("destination"));
    }

    @Test
    void classifySameLocationMapsTo400WithoutField() throws Exception {
        when(routeRiskService.classify(any(), any()))
            .thenThrow(new SameLocationException("same"));

        mockMvc.perform(post("/api/routes/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"origin\":\"a\",\"destination\":\"a\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("SAME_LOCATION"))
            .andExpect(jsonPath("$.field").doesNotExist());
    }

    @Test
    void classifyLocationNotResolvedMapsTo404() throws Exception {
        when(routeRiskService.classify(any(), any()))
            .thenThrow(new LocationNotResolvedException("origin", "unresolved"));

        mockMvc.perform(post("/api/routes/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"origin\":\"a\",\"destination\":\"b\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("LOCATION_NOT_RESOLVED"))
            .andExpect(jsonPath("$.field").value("origin"));
    }

    @Test
    void classifyNoRouteMapsTo404() throws Exception {
        when(routeRiskService.classify(any(), any()))
            .thenThrow(new NoRouteFoundException("no route"));

        mockMvc.perform(post("/api/routes/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"origin\":\"a\",\"destination\":\"b\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NO_ROUTE"));
    }

    @Test
    void classifyServiceUnavailableMapsTo503() throws Exception {
        when(routeRiskService.classify(any(), any()))
            .thenThrow(new ServiceUnavailableException("down"));

        mockMvc.perform(post("/api/routes/classify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"origin\":\"a\",\"destination\":\"b\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    // --- safest: count guard + geocoding + error mapping -------------------------------------

    @Test
    void safestTooFewLocationsMapsTo400AndSkipsGeocodingAndService() throws Exception {
        mockMvc.perform(post("/api/routes/safest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"locations\":[\"only one\"]}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("LOCATION_COUNT_OUT_OF_RANGE"))
            .andExpect(jsonPath("$.field").value("locations"));

        // Req 4.6: an out-of-range count must not trigger geocoding or routing.
        verify(geocodingProvider, never()).geocode(anyString());
        verify(safestRouteService, never()).findSafestRoute(any());
    }

    @Test
    void safestUnresolvedLocationMapsTo404() throws Exception {
        when(geocodingProvider.geocode("A")).thenReturn(Optional.of(new GeoCoordinate(25.7, -80.2)));
        when(geocodingProvider.geocode("B")).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/routes/safest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"locations\":[\"A\",\"B\"]}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("LOCATION_NOT_RESOLVED"))
            .andExpect(jsonPath("$.field").value("locations[1]"));

        verify(safestRouteService, never()).findSafestRoute(any());
    }

    @Test
    void safestNoRoutesRetrievedMapsTo503() throws Exception {
        when(geocodingProvider.geocode(anyString()))
            .thenReturn(Optional.of(new GeoCoordinate(25.7, -80.2)));
        when(safestRouteService.findSafestRoute(any()))
            .thenThrow(new SafestRouteException(
                SafestRouteException.Reason.NO_ROUTES_RETRIEVED, "no routes"));

        mockMvc.perform(post("/api/routes/safest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"locations\":[\"A\",\"B\"]}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("NO_ROUTES_RETRIEVED"));
    }

    @Test
    void safestHappyPathReturnsResult() throws Exception {
        when(geocodingProvider.geocode(anyString()))
            .thenReturn(Optional.of(new GeoCoordinate(25.7, -80.2)));
        SafestRouteResult result = new SafestRouteResult(List.of(
            new CategoryRecommendation(RiskCategory.ACCIDENT, null, 10, RiskLevel.LOW)));
        when(safestRouteService.findSafestRoute(any())).thenReturn(result);

        mockMvc.perform(post("/api/routes/safest")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"locations\":[\"A\",\"B\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.perCategory[0].category").value("ACCIDENT"))
            .andExpect(jsonPath("$.perCategory[0].score").value(10));
    }
}
