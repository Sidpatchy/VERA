package com.sidpatchy.Tile;

import java.io.IOException;
import java.util.function.IntConsumer;

/**
 * Visibility engine for elevation grids.
 * Computes a boolean visibility mask from a given observer using sub-cell terrain
 * interpolation and spherical observer-to-terrain geometry.
 */
public final class VisibilityEngine {
    private static final double[] SEGMENT_SAMPLE_FRACTIONS = {0.05, 0.5, 0.95};

    private VisibilityEngine() {}

    /**
     * Compute LOS mask using equally spaced angle bins and a grid DDA traversal.
     * Each traversed cell is sampled at multiple points along the ray segment,
     * rather than only at the cell centre. Elevation is bilinearly interpolated
     * at those points and the sight angle is calculated on a spherical Earth.
     * Returns a 2D boolean array with true where visible.
     */
    public static boolean[][] computeVisibilityMask(
            ElevationService.ElevationGrid grid,
            double observerLatDeg,
            double observerLonDeg,
            ElevationService.ObserverHeightMode mode,
            Double heightMeters,
            TileCache cache,
            int angleBins) throws IOException {
        return computeVisibilityMask(grid, observerLatDeg, observerLonDeg, mode, heightMeters, cache, angleBins, null);
    }

    public static boolean[][] computeVisibilityMask(
            ElevationService.ElevationGrid grid,
            double observerLatDeg,
            double observerLonDeg,
            ElevationService.ObserverHeightMode mode,
            Double heightMeters,
            TileCache cache,
            int angleBins,
            IntConsumer progress) throws IOException {
        if (angleBins < 8) angleBins = 8;
        // Allow high angular resolution; cap generously to prevent runaway allocations
        if (angleBins > 200000) angleBins = 200000;

        // Observer pixel
        double[] obsPx = latLonToGridPixel(grid, observerLatDeg, observerLonDeg);
        int w = grid.width, h = grid.height;
        boolean[][] visible = new boolean[h][w];

        // Mark observer pixel as visible if inside
        int cx = clamp((int)Math.floor(obsPx[0]), 0, w - 1);
        int cy = clamp((int)Math.floor(obsPx[1]), 0, h - 1);
        if (cx >= 0 && cy >= 0 && cx < w && cy < h) visible[cy][cx] = true;

        // Determine eye level for slope baseline
        double groundAtObserver = Double.NaN;
        if (isInsideGrid(grid, obsPx[0], obsPx[1])) groundAtObserver = sampleBilinear(grid, obsPx[0], obsPx[1]);
        if (Double.isNaN(groundAtObserver) && cache != null) {
            try { groundAtObserver = ElevationService.getElevationAt(observerLatDeg, observerLonDeg, grid.zoom, cache); } catch (Exception ignore) {}
        }
        if (Double.isNaN(groundAtObserver)) groundAtObserver = 0.0;
        double eyeAGL = 0.0;
        if (mode == ElevationService.ObserverHeightMode.AGL && heightMeters != null) eyeAGL = Math.max(0.0, heightMeters);
        else if (mode == ElevationService.ObserverHeightMode.ASL && heightMeters != null) eyeAGL = Math.max(0.0, heightMeters - groundAtObserver);
        final double eyeLevel = groundAtObserver + eyeAGL;
        // Angular sweep using grid DDA ray marching
        final double twoPi = Math.PI * 2.0;
        for (int ai = 0; ai < angleBins; ai++) {
            double theta = (ai / (double) angleBins) * twoPi;
            castRayDDA(grid, obsPx[0], obsPx[1], Math.cos(theta), Math.sin(theta),
                    observerLatDeg, observerLonDeg, eyeLevel, visible);
            if (progress != null) progress.accept(ai + 1);
        }
        return visible;
    }

    private static void castRayDDA(ElevationService.ElevationGrid grid, double ox, double oy, double dirX, double dirY,
                                   double observerLatDeg, double observerLonDeg, double eyeLevel,
                                   boolean[][] visible) {
        int w = grid.width, h = grid.height;
        // Normalize direction to avoid scaling artifacts
        double len = Math.hypot(dirX, dirY);
        dirX /= len; dirY /= len;

        // DDA setup
        int x = (int)Math.floor(ox);
        int y = (int)Math.floor(oy);
        int stepX = (dirX > 0) ? 1 : (dirX < 0 ? -1 : 0);
        int stepY = (dirY > 0) ? 1 : (dirY < 0 ? -1 : 0);

        double tMaxX, tMaxY; // distance to cross first vertical/horizontal grid line
        double tDeltaX, tDeltaY; // distance between crossings

        if (stepX != 0) {
            double nextGridX = (stepX > 0) ? (Math.floor(ox) + 1.0) : Math.floor(ox);
            tMaxX = (nextGridX - ox) / dirX;
            tDeltaX = 1.0 / Math.abs(dirX);
        } else {
            tMaxX = Double.POSITIVE_INFINITY; tDeltaX = Double.POSITIVE_INFINITY;
        }
        if (stepY != 0) {
            double nextGridY = (stepY > 0) ? (Math.floor(oy) + 1.0) : Math.floor(oy);
            tMaxY = (nextGridY - oy) / dirY;
            tDeltaY = 1.0 / Math.abs(dirY);
        } else {
            tMaxY = Double.POSITIVE_INFINITY; tDeltaY = Double.POSITIVE_INFINITY;
        }

        double maxSlope = -Double.MAX_VALUE;
        double segmentStart = 0.0;
        // Traverse until outside
        while (x >= 0 && y >= 0 && x < w && y < h) {
            double segmentEnd = Math.min(tMaxX, tMaxY);
            double segmentLength = segmentEnd - segmentStart;
            double cellMaxAngle = -Double.MAX_VALUE;
            // A DEM sample can be a narrow peak. Three samples retain peaks at
            // either edge or in the middle while keeping the raycast tractable.
            for (double fraction : SEGMENT_SAMPLE_FRACTIONS) {
                double sampleT = segmentStart + segmentLength * fraction;
                double sampleX = ox + sampleT * dirX;
                double sampleY = oy + sampleT * dirY;
                double v = sampleBilinear(grid, sampleX, sampleY);
                if (Double.isNaN(v)) {
                    // A missing DEM cell is unknown, not empty terrain. Do not
                    // allow a ray to claim visibility through missing data.
                    return;
                }
                cellMaxAngle = Math.max(cellMaxAngle, sphericalSightAngle(
                        observerLatDeg, observerLonDeg, grid, sampleX, sampleY, v, eyeLevel));
            }
            if (cellMaxAngle >= maxSlope) {
                visible[y][x] = true;
                maxSlope = cellMaxAngle;
            }
            // Step to next grid boundary
            segmentStart = segmentEnd;
            if (tMaxX < tMaxY) { x += stepX; tMaxX += tDeltaX; }
            else { y += stepY; tMaxY += tDeltaY; }
        }
    }

    /** Bilinear DEM interpolation at a continuous grid coordinate. */
    private static double sampleBilinear(ElevationService.ElevationGrid grid, double px, double py) {
        if (!isInsideGrid(grid, px, py)) return Double.NaN;
        int x0 = (int) Math.floor(px);
        int y0 = (int) Math.floor(py);
        int x1 = Math.min(x0 + 1, grid.width - 1);
        int y1 = Math.min(y0 + 1, grid.height - 1);
        double tx = px - x0;
        double ty = py - y0;
        double a = grid.data[y0][x0];
        double b = grid.data[y0][x1];
        double c = grid.data[y1][x0];
        double d = grid.data[y1][x1];
        if (Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c) || Double.isNaN(d)) return Double.NaN;
        return (a * (1.0 - tx) + b * tx) * (1.0 - ty)
                + (c * (1.0 - tx) + d * tx) * ty;
    }

    /**
     * Returns the elevation angle from the observer to a terrain point on a
     * spherical Earth. This compares rays in the observer's local tangent
     * plane and therefore includes curvature without a small-angle approximation.
     */
    private static double sphericalSightAngle(double observerLatDeg, double observerLonDeg,
                                              ElevationService.ElevationGrid grid,
                                              double sampleX, double sampleY,
                                              double terrainElevation, double eyeLevel) {
        double[] sampleLatLon = gridPixelToLatLon(grid, sampleX, sampleY);
        double lat1 = Math.toRadians(observerLatDeg);
        double lat2 = Math.toRadians(sampleLatLon[0]);
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(shortestLongitudeDelta(sampleLatLon[1] - observerLonDeg));
        double haversine = Math.sin(deltaLat * 0.5) * Math.sin(deltaLat * 0.5)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon * 0.5) * Math.sin(deltaLon * 0.5);
        double centralAngle = 2.0 * Math.atan2(Math.sqrt(haversine), Math.sqrt(Math.max(0.0, 1.0 - haversine)));
        if (centralAngle < 1.0e-12) return -Double.MAX_VALUE;

        final double earthRadiusMeters = 6_371_008.8;
        double terrainRadius = earthRadiusMeters + terrainElevation;
        double observerRadius = earthRadiusMeters + eyeLevel;
        double vertical = terrainRadius * Math.cos(centralAngle) - observerRadius;
        double horizontal = terrainRadius * Math.sin(centralAngle);
        return Math.atan2(vertical, horizontal);
    }

    private static double[] gridPixelToLatLon(ElevationService.ElevationGrid grid, double px, double py) {
        int radiusTiles = (grid.tilesWide - 1) / 2;
        double n = Math.pow(2.0, grid.zoom);
        double globalX = grid.centerTileX - radiusTiles + px / grid.tileSize;
        double globalY = grid.centerTileY - radiusTiles + py / grid.tileSize;
        double lon = normalizeLongitude(globalX / n * 360.0 - 180.0);
        double mercator = Math.PI * (1.0 - 2.0 * globalY / n);
        double lat = Math.toDegrees(Math.atan(Math.sinh(mercator)));
        return new double[]{lat, lon};
    }

    private static double shortestLongitudeDelta(double delta) {
        double result = delta % 360.0;
        if (result < -180.0) result += 360.0;
        if (result >= 180.0) result -= 360.0;
        return result;
    }

    private static boolean isInsideGrid(ElevationService.ElevationGrid grid, double px, double py) {
        return px >= 0.0 && py >= 0.0 && px < grid.width && py < grid.height;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // Minimal helpers (duplicated) to avoid coupling
    private static double[] latLonToGridPixel(ElevationService.ElevationGrid grid, double latDeg, double lonDeg) {
        double[] tf = latLonToTileFractional(latDeg, lonDeg, grid.zoom);
        double xTileF = tf[0];
        double yTileF = tf[1];
        int n = 1 << grid.zoom;
        int radiusTiles = (grid.tilesWide - 1) / 2;
        int xTile = (int) Math.floor(xTileF);
        int yTile = (int) Math.floor(yTileF);
        double fracX = xTileF - xTile;
        double fracY = yTileF - yTile;
        int dxTilesInt = xTile - grid.centerTileX;
        dxTilesInt = (int) Math.round(((dxTilesInt + n / 2.0) % n) - n / 2.0);
        int dyTilesInt = yTile - grid.centerTileY;
        double px = (dxTilesInt + radiusTiles) * (double) grid.tileSize + fracX * grid.tileSize;
        double py = (dyTilesInt + radiusTiles) * (double) grid.tileSize + fracY * grid.tileSize;
        return new double[]{px, py};
    }
    private static double[] latLonToTileFractional(double latDeg, double lonDeg, int zoom) {
        double MAX_MERCATOR_LAT = 85.05112878;
        double lat = Math.max(-MAX_MERCATOR_LAT, Math.min(MAX_MERCATOR_LAT, latDeg));
        double lon = normalizeLongitude(lonDeg);
        double n = Math.pow(2.0, zoom);
        double xTileF = (lon + 180.0) / 360.0 * n;
        double latRad = Math.toRadians(lat);
        double yTileF = (1.0 - (Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI)) / 2.0 * n;
        xTileF = wrap(xTileF, 0.0, n);
        yTileF = clamp(yTileF, 0.0, Math.nextDown(n));
        return new double[]{xTileF, yTileF};
    }
    private static double clamp(double v, double min, double max){ return Math.max(min, Math.min(max, v)); }
    private static double wrap(double v, double minInclusive, double maxExclusive) {
        double range = maxExclusive - minInclusive;
        double x = (v - minInclusive) % range;
        if (x < 0) x += range;
        return x + minInclusive;
    }
    private static double normalizeLongitude(double lonDeg) {
        double lon = lonDeg;
        while (lon < -180.0) lon += 360.0;
        while (lon >= 180.0) lon -= 360.0;
        return lon;
    }
}
