package com.sidpatchy.Tile;

import java.io.IOException;

/**
 * High-performance visibility (raycasting) engine for elevation grids.
 * Computes a boolean visibility mask from a given observer, accounting for Earth curvature
 * by leveraging CurvatureModel.applyObserverCurvature.
 */
public final class VisibilityEngine {
    private VisibilityEngine() {}

    /**
     * Compute LOS mask using equally spaced angle bins and a grid DDA traversal.
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
        if (angleBins < 8) angleBins = 8;
        // Allow high angular resolution; cap generously to prevent runaway allocations
        if (angleBins > 200000) angleBins = 200000;

        final double earthRadiusMeters = 6_371_008.8;
        // Meters per pixel at observer latitude
        double metersPerPixel = (2.0 * Math.PI * earthRadiusMeters * Math.cos(Math.toRadians(observerLatDeg)))
                / (grid.tileSize * Math.pow(2.0, grid.zoom));

        // Curvature-adjusted grid
        ElevationService.ElevationGrid curved = CurvatureModel.applyObserverCurvature(
                grid, observerLatDeg, observerLonDeg, mode, heightMeters, cache);

        // Observer pixel
        double[] obsPx = latLonToGridPixel(grid, observerLatDeg, observerLonDeg);
        int w = grid.width, h = grid.height;
        boolean[][] visible = new boolean[h][w];

        // Mark observer pixel as visible if inside
        int cx = (int)Math.round(obsPx[0]);
        int cy = (int)Math.round(obsPx[1]);
        if (cx >= 0 && cy >= 0 && cx < w && cy < h) visible[cy][cx] = true;

        // Determine eye level for slope baseline
        double groundAtObserver = Double.NaN;
        if (cx >= 0 && cy >= 0 && cx < w && cy < h) groundAtObserver = grid.data[cy][cx];
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
            castRayDDA(curved, obsPx[0], obsPx[1], Math.cos(theta), Math.sin(theta), metersPerPixel, eyeLevel, visible);
        }
        return visible;
    }

    private static void castRayDDA(ElevationService.ElevationGrid grid, double ox, double oy, double dirX, double dirY,
                                   double metersPerPixel, double eyeLevel, boolean[][] visible) {
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
        // Traverse until outside
        while (x >= 0 && y >= 0 && x < w && y < h) {
            // Sample current cell
            double v = grid.data[y][x];
            if (!Double.isNaN(v)) {
                double dx = (x + 0.5) - ox; // center of cell
                double dy = (y + 0.5) - oy;
                double sMeters = Math.hypot(dx, dy) * metersPerPixel;
                if (sMeters > 0) {
                    double slope = (v - eyeLevel) / sMeters;
                    if (slope >= maxSlope) { visible[y][x] = true; maxSlope = slope; }
                }
            }
            // Step to next grid boundary
            if (tMaxX < tMaxY) { x += stepX; tMaxX += tDeltaX; }
            else { y += stepY; tMaxY += tDeltaY; }
        }
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
