package com.routeriskadvisor.web;

import java.util.List;

/**
 * Request body for {@code POST /api/routes/safest} (design "REST Controllers"): the ordered set of
 * free-text locations to compare. Consistent with the classify flow, each location is a free-text
 * string that the controller geocodes via the {@link com.routeriskadvisor.provider.GeocodingProvider}
 * before handing the resolved coordinates to the
 * {@link com.routeriskadvisor.service.SafestRouteService}.
 *
 * @param locations the ordered free-text locations to connect; the 2..25 count rule (Req 4.6) is
 *                  enforced before any geocoding or routing is requested
 */
public record SafestRouteRequest(List<String> locations) {}
