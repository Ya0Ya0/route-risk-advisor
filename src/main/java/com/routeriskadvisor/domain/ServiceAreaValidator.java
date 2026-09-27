package com.routeriskadvisor.domain;

import com.routeriskadvisor.domain.model.GeoCoordinate;

/**
 * Determines whether a {@link GeoCoordinate} falls within the Service_Area, defined as
 * Miami-Dade County, Florida, United States (Requirement 6.1).
 *
 * <p>Miami-Dade County is represented as a coarse polygon that approximates the county
 * boundary. This is the placeholder version described in the design's
 * "Service_Area Boundary Checking" section: a conservative bounding polygon of the county,
 * to be refined from the county's published GeoJSON as future work. Containment is decided
 * with a standard ray-casting point-in-polygon test, which is O(n) in the number of polygon
 * vertices and therefore returns well within the 2-second budget (Requirement 6.2).
 *
 * <p>Coordinates inside the polygon are accepted so processing proceeds (Requirement 6.4);
 * coordinates outside it are rejected (Requirement 6.3).
 */
public class ServiceAreaValidator {

    /**
     * Vertices of the coarse Miami-Dade County boundary polygon, in decimal degrees.
     *
     * <p>Each entry is {@code {longitude, latitude}} (x, y). The polygon is an open ring —
     * the closing edge from the last vertex back to the first is handled implicitly by the
     * ray-casting algorithm. Miami-Dade County spans roughly latitude 25.13–25.98 and
     * longitude -80.87 to -80.12; these vertices trace that extent while trimming the
     * Atlantic corner in the northeast and following the more southerly/western county line,
     * giving a closer fit than a plain bounding box.
     */
    private static final double[][] MIAMI_DADE_POLYGON = {
        // Northwest corner (inland, near the Broward line / Everglades edge)
        {-80.87, 25.98},
        // North edge running east toward the coast
        {-80.30, 25.98},
        // Northeast coastal corner (Sunny Isles / Aventura area)
        {-80.12, 25.90},
        // East coast heading south (barrier islands / Biscayne Bay shoreline)
        {-80.12, 25.55},
        {-80.16, 25.35},
        // Southeast corner (near Homestead / Biscayne Bay south)
        {-80.30, 25.18},
        // South edge (Florida City / county's southern extent)
        {-80.50, 25.13},
        // Southwest corner (Everglades)
        {-80.87, 25.20},
        // Back up the west edge to the northwest corner (implicit close)
    };

    /**
     * Returns whether the given coordinate lies within the Miami-Dade County Service_Area.
     *
     * @param coordinate the coordinate to test; must not be {@code null}
     * @return {@code true} if the coordinate is inside the county polygon, {@code false} otherwise
     */
    public boolean contains(GeoCoordinate coordinate) {
        if (coordinate == null) {
            return false;
        }
        return isPointInPolygon(coordinate.longitude(), coordinate.latitude(), MIAMI_DADE_POLYGON);
    }

    /**
     * Standard ray-casting (even-odd rule) point-in-polygon test.
     *
     * <p>Casts a ray from the point along the +x axis and counts how many polygon edges it
     * crosses. An odd number of crossings means the point is inside. The polygon is treated
     * as closed, so the edge from the last vertex to the first is included.
     *
     * @param x       the point's x coordinate (longitude)
     * @param y       the point's y coordinate (latitude)
     * @param polygon the polygon vertices as {@code {x, y}} pairs
     * @return {@code true} if the point is inside the polygon
     */
    private static boolean isPointInPolygon(double x, double y, double[][] polygon) {
        boolean inside = false;
        int n = polygon.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = polygon[i][0];
            double yi = polygon[i][1];
            double xj = polygon[j][0];
            double yj = polygon[j][1];

            boolean crossesRay = ((yi > y) != (yj > y))
                && (x < (xj - xi) * (y - yi) / (yj - yi) + xi);
            if (crossesRay) {
                inside = !inside;
            }
        }
        return inside;
    }
}
