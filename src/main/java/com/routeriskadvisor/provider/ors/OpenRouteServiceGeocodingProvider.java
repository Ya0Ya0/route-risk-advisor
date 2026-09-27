package com.routeriskadvisor.provider.ors;

import com.fasterxml.jackson.databind.JsonNode;
import com.routeriskadvisor.domain.model.GeoCoordinate;
import com.routeriskadvisor.provider.GeocodingProvider;
import com.routeriskadvisor.provider.ProviderException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Optional;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Real {@link GeocodingProvider} backed by the OpenRouteService (ORS) Pelias geocoder.
 *
 * <p>Issues {@code GET /geocode/search?text={q}&size=1} against the configured ORS base URL and
 * parses the returned GeoJSON {@code FeatureCollection}. The first feature's
 * {@code geometry.coordinates} is a {@code [longitude, latitude]} pair, which is flipped into a
 * {@link GeoCoordinate}{@code (latitude, longitude)}.
 *
 * <p>Failure policy (mirrors {@link ProviderException.Kind}):
 * <ul>
 *   <li>A well-formed response with no features resolves to {@link Optional#empty()} — this is a
 *       normal "cannot resolve" outcome, not an error.</li>
 *   <li>A read timeout (or other socket timeout) throws {@link ProviderException.Kind#TIMEOUT}.</li>
 *   <li>A non-2xx response or any other transport/parse error throws
 *       {@link ProviderException.Kind#FAILURE}.</li>
 * </ul>
 *
 * <p>Bean registration is centralized in {@code ProviderConfiguration} via
 * {@code @ConditionalOnProperty} on {@code route-risk-advisor.providers.geocoding = openrouteservice},
 * so this class carries no Spring stereotype of its own.
 */
public class OpenRouteServiceGeocodingProvider implements GeocodingProvider {

    private final RestClient restClient;

    /**
     * @param baseUrl       ORS base URL (e.g. {@code https://api.openrouteservice.org})
     * @param apiKey        ORS API key, sent in the {@code Authorization} header
     * @param connectTimeout connect timeout for the underlying HTTP client
     * @param readTimeout    read timeout for the underlying HTTP client (the geocoding budget)
     */
    public OpenRouteServiceGeocodingProvider(
        String baseUrl, String apiKey, Duration connectTimeout, Duration readTimeout) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(connectTimeout)
            .withReadTimeout(readTimeout);
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactories.get(settings);
        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .defaultHeader(HttpHeaders.AUTHORIZATION, apiKey == null ? "" : apiKey)
            .defaultHeader(HttpHeaders.ACCEPT, "application/json")
            .build();
    }

    @Override
    public Optional<GeoCoordinate> geocode(String locationDescription) throws ProviderException {
        if (locationDescription == null || locationDescription.trim().isEmpty()) {
            return Optional.empty();
        }

        JsonNode body;
        try {
            body = restClient.get()
                .uri(uriBuilder -> uriBuilder
                    .path("/geocode/search")
                    .queryParam("text", locationDescription)
                    .queryParam("size", 1)
                    .build())
                .retrieve()
                .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            // Non-2xx status from ORS.
            throw new ProviderException(
                "OpenRouteService geocoding returned status " + e.getStatusCode() + ".",
                ProviderException.Kind.FAILURE, e);
        } catch (RestClientException e) {
            throw classifyTransportError(e);
        }

        if (body == null) {
            return Optional.empty();
        }

        JsonNode features = body.path("features");
        if (!features.isArray() || features.isEmpty()) {
            return Optional.empty();
        }

        JsonNode coordinates = features.get(0).path("geometry").path("coordinates");
        if (!coordinates.isArray() || coordinates.size() < 2) {
            // A feature without a usable geometry is treated as "cannot resolve".
            return Optional.empty();
        }

        // GeoJSON order is [longitude, latitude].
        double longitude = coordinates.get(0).asDouble();
        double latitude = coordinates.get(1).asDouble();
        return Optional.of(new GeoCoordinate(latitude, longitude));
    }

    /**
     * Maps a transport-level {@link RestClientException} to the appropriate {@link ProviderException}
     * kind: a socket read/connect timeout becomes {@link ProviderException.Kind#TIMEOUT}; anything
     * else becomes {@link ProviderException.Kind#FAILURE}.
     */
    private ProviderException classifyTransportError(RestClientException e) {
        if (isTimeout(e)) {
            return new ProviderException(
                "OpenRouteService geocoding timed out.", ProviderException.Kind.TIMEOUT, e);
        }
        return new ProviderException(
            "OpenRouteService geocoding call failed.", ProviderException.Kind.FAILURE, e);
    }

    /** Walks the cause chain looking for a {@link SocketTimeoutException}. */
    static boolean isTimeout(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
