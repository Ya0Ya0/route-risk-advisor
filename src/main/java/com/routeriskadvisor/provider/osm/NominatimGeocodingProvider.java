package com.routeriskadvisor.provider.osm;

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
 * Real {@link GeocodingProvider} backed by the keyless OpenStreetMap Nominatim public geocoder.
 *
 * <p>Issues {@code GET /search?q={q}&format=json&limit=1} against the configured Nominatim base URL
 * and biases results to Miami-Dade County via a bounded {@code viewbox}. The response is a JSON
 * array; the first element's {@code lat}/{@code lon} (returned as strings) are parsed into a
 * {@link GeoCoordinate}. An empty array resolves to {@link Optional#empty()}.
 *
 * <p>Nominatim's usage policy requires a descriptive, non-default {@code User-Agent} header or it
 * responds with HTTP 403; the header value is supplied from configuration and installed as a default
 * header on the {@link RestClient}.
 *
 * <p>Failure policy (mirrors {@link ProviderException.Kind} and the ORS provider):
 * <ul>
 *   <li>A well-formed response with no results resolves to {@link Optional#empty()} — a normal
 *       "cannot resolve" outcome, not an error.</li>
 *   <li>A read/connect timeout throws {@link ProviderException.Kind#TIMEOUT}.</li>
 *   <li>A non-2xx response or any other transport/parse error throws
 *       {@link ProviderException.Kind#FAILURE}.</li>
 * </ul>
 *
 * <p>Bean registration is centralized in {@code ProviderConfiguration} via
 * {@code @ConditionalOnProperty} on {@code route-risk-advisor.providers.geocoding = nominatim}, so
 * this class carries no Spring stereotype of its own.
 */
public class NominatimGeocodingProvider implements GeocodingProvider {

    /**
     * Miami-Dade County bounding box in Nominatim {@code viewbox} order
     * ({@code left,top,right,bottom} i.e. {@code minLon,maxLat,maxLon,minLat}).
     */
    private static final String MIAMI_DADE_VIEWBOX = "-80.90,25.98,-80.10,25.13";

    private final RestClient restClient;

    /**
     * @param baseUrl        Nominatim base URL (e.g. {@code https://nominatim.openstreetmap.org})
     * @param userAgent      descriptive {@code User-Agent} sent on every request (required by
     *                       Nominatim's usage policy)
     * @param connectTimeout connect timeout for the underlying HTTP client
     * @param readTimeout    read timeout for the underlying HTTP client (the geocoding budget)
     */
    public NominatimGeocodingProvider(
        String baseUrl, String userAgent, Duration connectTimeout, Duration readTimeout) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(connectTimeout)
            .withReadTimeout(readTimeout);
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactories.get(settings);
        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .defaultHeader(HttpHeaders.USER_AGENT,
                userAgent == null || userAgent.isBlank() ? "route-risk-advisor/1.0" : userAgent)
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
                    .path("/search")
                    .queryParam("q", locationDescription)
                    .queryParam("format", "json")
                    .queryParam("limit", 1)
                    .queryParam("addressdetails", 0)
                    .queryParam("countrycodes", "us")
                    .queryParam("viewbox", MIAMI_DADE_VIEWBOX)
                    .queryParam("bounded", 1)
                    .build())
                .retrieve()
                .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            // Non-2xx status from Nominatim.
            throw new ProviderException(
                "Nominatim geocoding returned status " + e.getStatusCode() + ".",
                ProviderException.Kind.FAILURE, e);
        } catch (RestClientException e) {
            throw classifyTransportError(e);
        }

        if (body == null || !body.isArray() || body.isEmpty()) {
            return Optional.empty();
        }

        JsonNode first = body.get(0);
        JsonNode latNode = first.path("lat");
        JsonNode lonNode = first.path("lon");
        if (latNode.isMissingNode() || lonNode.isMissingNode()) {
            return Optional.empty();
        }

        // Nominatim returns lat/lon as strings; asDouble parses them.
        double latitude = latNode.asDouble();
        double longitude = lonNode.asDouble();
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
                "Nominatim geocoding timed out.", ProviderException.Kind.TIMEOUT, e);
        }
        return new ProviderException(
            "Nominatim geocoding call failed.", ProviderException.Kind.FAILURE, e);
    }

    /** Walks the cause chain looking for a {@link SocketTimeoutException}. */
    private static boolean isTimeout(Throwable t) {
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
