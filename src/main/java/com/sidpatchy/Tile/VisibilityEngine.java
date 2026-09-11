package com.sidpatchy.Tile;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

/**
 * Visibility engine for elevation grids.
 * Computes a boolean visibility mask from a given observer using sub-cell terrain
 * interpolation and precomputed WGS84 observer-to-terrain geometry.
 */
public final class VisibilityEngine {
    private static final double[] SEGMENT_SAMPLE_FRACTIONS = {0.01, 0.25, 0.5, 0.75, 0.99};
    private static final double WGS84_SEMI_MAJOR = 6_378_137.0;
    private static final double WGS84_ECCENTRICITY_SQUARED = 6.6943799901413165e-3;

    private VisibilityEngine() {}

    /**
     * Compute LOS mask using equally spaced angle bins and a grid DDA traversal.
     * Each traversed cell is sampled at multiple points along the ray segment,
     * rather than only at the cell centre. Elevation is bilinearly interpolated
     * at those points and the sight angle is calculated using WGS84 geometry.
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
        RaycastContext context = new RaycastContext(grid, observerLatDeg, observerLonDeg, eyeLevel);
        // Angular sweep using grid DDA ray marching
        final double twoPi = Math.PI * 2.0;
        final int rayCount = angleBins;
        AtomicInteger completed = progress == null ? null : new AtomicInteger();
        Object progressLock = progress == null ? null : new Object();
        IntStream.range(0, rayCount).parallel().forEach(ai -> {
            double theta = (ai / (double) rayCount) * twoPi;
            castRayDDA(grid, obsPx[0], obsPx[1], Math.cos(theta), Math.sin(theta),
                    context, visible);
            if (progress != null) {
                int current = completed.incrementAndGet();
                synchronized (progressLock) {
                    progress.accept(current);
                }
            }
        });
        return visible;
    }

    private static final class RaycastContext {
        final double observerLonDeg;
        final double observerLatRad;
        final double observerSinLat;
        final double observerCosLat;
        final double observerSinLon;
        final double observerCosLon;
        final double observerX;
        final double observerY;
        final double observerZ;
        final double upX;
        final double upY;
        final double upZ;
        final double originTileX;
        final double originTileY;
        final double inverseWorldSize;
        final double inverseTileSize;
        final double[] longitudeSin;
        final double[] longitudeCos;
        final double[] latitudeSin;
        final double[] latitudeCos;
        final double[] ellipsoidRadius;

        RaycastContext(ElevationService.ElevationGrid grid, double observerLatDeg,
                       double observerLonDeg, double eyeLevel) {
            this.observerLonDeg = observerLonDeg;
            observerLatRad = Math.toRadians(observerLatDeg);
            double observerLonRad = Math.toRadians(observerLonDeg);
            observerSinLat = Math.sin(observerLatRad);
            observerCosLat = Math.cos(observerLatRad);
            observerSinLon = Math.sin(observerLonRad);
            observerCosLon = Math.cos(observerLonRad);
            double radius = WGS84_SEMI_MAJOR / Math.sqrt(
                    1.0 - WGS84_ECCENTRICITY_SQUARED * observerSinLat * observerSinLat);
            observerX = (radius + eyeLevel) * observerCosLat * observerCosLon;
            observerY = (radius + eyeLevel) * observerCosLat * observerSinLon;
            observerZ = (radius * (1.0 - WGS84_ECCENTRICITY_SQUARED) + eyeLevel) * observerSinLat;
            upX = observerCosLat * observerCosLon;
            upY = observerCosLat * observerSinLon;
            upZ = observerSinLat;

            int radiusTiles = (grid.tilesWide - 1) / 2;
            originTileX = grid.centerTileX - radiusTiles;
            originTileY = grid.centerTileY - radiusTiles;
            inverseWorldSize = 1.0 / Math.pow(2.0, grid.zoom);
            inverseTileSize = 1.0 / grid.tileSize;

            // Geographic geometry is constant for a grid. Precompute it once
            // in compact row/column arrays instead of recalculating trig and
            // WGS84 radius values for every ray sample.
            longitudeSin = new double[grid.width];
            longitudeCos = new double[grid.width];
            for (int x = 0; x < grid.width; x++) {
                double longitude = (originTileX + (x + 0.5) * inverseTileSize)
                        * inverseWorldSize * 2.0 * Math.PI - Math.PI;
                longitudeSin[x] = Math.sin(longitude);
                longitudeCos[x] = Math.cos(longitude);
            }
            latitudeSin = new double[grid.height];
            latitudeCos = new double[grid.height];
            ellipsoidRadius = new double[grid.height];
            for (int y = 0; y < grid.height; y++) {
                double mercator = Math.PI * (1.0 - 2.0
                        * (originTileY + (y + 0.5) * inverseTileSize) * inverseWorldSize);
                double latitude = Math.atan(Math.sinh(mercator));
                latitudeSin[y] = Math.sin(latitude);
                latitudeCos[y] = Math.cos(latitude);
                ellipsoidRadius[y] = WGS84_SEMI_MAJOR / Math.sqrt(
                        1.0 - WGS84_ECCENTRICITY_SQUARED * latitudeSin[y] * latitudeSin[y]);
            }
        }
    }

    private static void castRayDDA(ElevationService.ElevationGrid grid, double ox, double oy, double dirX, double dirY,
                                   RaycastContext context,
                                   boolean[][] visible) {
        int w = grid.width, h = grid.height;
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
            double baseX = ox + segmentStart * dirX;
            double baseY = oy + segmentStart * dirY;
            // A DEM sample can be a narrow peak. Five samples retain peaks at
            // either edge or in the middle while keeping the raycast tractable.
            for (double fraction : SEGMENT_SAMPLE_FRACTIONS) {
                double sampleDistance = segmentLength * fraction;
                double sampleX = baseX + sampleDistance * dirX;
                double sampleY = baseY + sampleDistance * dirY;
                double v = sampleBilinear(grid, sampleX, sampleY);
                if (Double.isNaN(v)) {
                    // A missing DEM cell is unknown, not empty terrain. Do not
                    // allow a ray to claim visibility through missing data.
                    return;
                }
                cellMaxAngle = Math.max(cellMaxAngle, sightAngle(context, sampleX, sampleY, v));
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

    private static double sightAngle(RaycastContext context, double sampleX, double sampleY,
                                     double terrainElevation) {
        int x0 = (int) Math.floor(sampleX);
        int y0 = (int) Math.floor(sampleY);
        int x1 = Math.min(x0 + 1, context.longitudeSin.length - 1);
        int y1 = Math.min(y0 + 1, context.latitudeSin.length - 1);
        double tx = sampleX - x0;
        double ty = sampleY - y0;

        double sinLon = interpolate(context.longitudeSin[x0], context.longitudeSin[x1], tx);
        double cosLon = interpolate(context.longitudeCos[x0], context.longitudeCos[x1], tx);
        double sinLat = interpolate(context.latitudeSin[y0], context.latitudeSin[y1], ty);
        double cosLat = interpolate(context.latitudeCos[y0], context.latitudeCos[y1], ty);
        double targetRadius = interpolate(context.ellipsoidRadius[y0], context.ellipsoidRadius[y1], ty);
        double targetX = (targetRadius + terrainElevation) * cosLat * cosLon;
        double targetY = (targetRadius + terrainElevation) * cosLat * sinLon;
        double targetZ = (targetRadius * (1.0 - WGS84_ECCENTRICITY_SQUARED)
                + terrainElevation) * sinLat;
        double dx = targetX - context.observerX;
        double dy = targetY - context.observerY;
        double dz = targetZ - context.observerZ;
        double vertical = dx * context.upX + dy * context.upY + dz * context.upZ;
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        double horizontalSquared = Math.max(0.0, distanceSquared - vertical * vertical);
        if (distanceSquared < 1.0e-12) return -Double.MAX_VALUE;
        // atan2 is monotonic over the possible sight-angle range, so compare
        // its tangent instead. This avoids one expensive transcendental call
        // for every sample while preserving the ordering used by the raycast.
        return vertical / Math.sqrt(horizontalSquared);
    }

    private static double interpolate(double a, double b, double fraction) {
        return a + (b - a) * fraction;
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
        double px = (dxTilesInt + radiusTiles) * (double) grid.tileSize + fracX * grid.tileSize - 0.5;
        double py = (dyTilesInt + radiusTiles) * (double) grid.tileSize + fracY * grid.tileSize - 0.5;
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
