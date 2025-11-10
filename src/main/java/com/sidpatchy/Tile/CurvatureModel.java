package com.sidpatchy.Tile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Curvature model utilities. Provides optimized curvature-compensation for elevation grids
 * with optional on-disk caching. The cache is safe to delete; files are recomputed on demand.
 */
public final class CurvatureModel {
    private static boolean isCurvatureCacheEnabledByDefault() {
        // Default: disabled, can be enabled with sysprop/env
        String sProp = System.getProperty("los.curvature.cache.enabled");
        String sEnv = (sProp == null || sProp.isBlank()) ? System.getenv("LOS_CURVATURE_CACHE_ENABLED") : null;
        if (sProp != null && !sProp.isBlank()) return parseBool(sProp, false);
        if (sEnv != null && !sEnv.isBlank()) return parseBool(sEnv, false);
        return false; // compute in real time by default
    }
    private static String getCurvatureCacheDir() {
        String sProp = System.getProperty("los.curvature.cache.dir");
        String sEnv = (sProp == null || sProp.isBlank()) ? System.getenv("LOS_CURVATURE_CACHE_DIR") : null;
        if (sProp != null && !sProp.isBlank()) return sProp.trim();
        if (sEnv != null && !sEnv.isBlank()) return sEnv.trim();
        return DEFAULT_CACHE_DIR;
    }
    private static boolean parseBool(String s, boolean def) {
        String v = s.trim().toLowerCase();
        if (v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on")) return true;
        if (v.equals("0") || v.equals("false") || v.equals("no") || v.equals("off")) return false;
        return def;
    }
    private CurvatureModel() {}

    private static final double DEFAULT_EARTH_RADIUS_M = 6_371_008.8; // IUGG mean radius
    private static final String DEFAULT_CACHE_DIR = "./terrain_cache/curvature_cache";

    /**
     * Applies Earth curvature drop relative to the geometric center of the grid.
     * Returns a NEW grid (the input is not modified).
     */
    public static ElevationService.ElevationGrid applyCenterCurvature(
            ElevationService.ElevationGrid grid,
            double centerLatDeg) {
        return applyCenterCurvature(grid, centerLatDeg, DEFAULT_EARTH_RADIUS_M);
    }

    public static ElevationService.ElevationGrid applyCenterCurvature(
            ElevationService.ElevationGrid grid,
            double centerLatDeg,
            double earthRadiusMeters) {
        // Compute meters per pixel at latitude for Web Mercator
        double metersPerPixel = (2.0 * Math.PI * earthRadiusMeters * Math.cos(Math.toRadians(centerLatDeg)))
                / (grid.tileSize * Math.pow(2.0, grid.zoom));

        int w = grid.width;
        int h = grid.height;
        double[][] out = new double[h][w];
        double cx = (w - 1) / 2.0;
        double cy = (h - 1) / 2.0;

        // Vectorized-style inner loops (but in Java)
        for (int y = 0; y < h; y++) {
            double[] inRow = grid.data[y];
            double[] outRow = out[y];
            double dy = (y - cy);
            for (int x = 0; x < w; x++) {
                double v = inRow[x];
                if (Double.isNaN(v)) { outRow[x] = Double.NaN; continue; }
                double dx = (x - cx);
                double sMeters = Math.hypot(dx, dy) * metersPerPixel;
                double drop = (sMeters * sMeters) / (2.0 * earthRadiusMeters);
                outRow[x] = v - drop;
            }
        }
        return new ElevationService.ElevationGrid(out, grid.tileSize, grid.tilesWide, grid.tilesHigh, grid.zoom, grid.centerTileX, grid.centerTileY);
    }

    /**
     * Applies Earth curvature drop around an arbitrary observer using AGL/ASL/GROUND height modes.
     * Optionally uses a file cache to persist adjusted grids for reuse across requests.
     */
    public static ElevationService.ElevationGrid applyObserverCurvature(
            ElevationService.ElevationGrid grid,
            double observerLatDeg,
            double observerLonDeg,
            ElevationService.ObserverHeightMode mode,
            Double heightMeters,
            TileCache cache) throws IOException {
        boolean enableCache = isCurvatureCacheEnabledByDefault();
        String cacheDir = getCurvatureCacheDir();
        return applyObserverCurvature(grid, observerLatDeg, observerLonDeg, mode, heightMeters, cache, DEFAULT_EARTH_RADIUS_M, cacheDir, enableCache);
    }

    public static ElevationService.ElevationGrid applyObserverCurvature(
            ElevationService.ElevationGrid grid,
            double observerLatDeg,
            double observerLonDeg,
            ElevationService.ObserverHeightMode mode,
            Double heightMeters,
            TileCache cache,
            double earthRadiusMeters,
            String cacheDir,
            boolean enableCache) throws IOException {
        // Prepare cache path
        if (enableCache) {
            try { Files.createDirectories(Paths.get(cacheDir)); } catch (IOException ignore) {}
        }
        String key = null;
        Path filePath = null;
        if (enableCache) {
            key = buildKey(grid, observerLatDeg, observerLonDeg, mode, heightMeters, earthRadiusMeters);
            filePath = Paths.get(cacheDir, key + ".bin");
            if (Files.exists(filePath)) {
                ElevationService.ElevationGrid cached = tryReadGrid(filePath, grid);
                if (cached != null) return cached;
            }
        }

        // Compute meters per pixel at observer latitude
        double metersPerPixel = (2.0 * Math.PI * earthRadiusMeters * Math.cos(Math.toRadians(observerLatDeg)))
                / (grid.tileSize * Math.pow(2.0, grid.zoom));

        // Observer pixel in the grid
        double[] obsPx = latLonToGridPixel(grid, observerLatDeg, observerLonDeg);

        // Determine eye height AGL
        double eyeAGL = 0.0;
        if (mode == ElevationService.ObserverHeightMode.AGL && heightMeters != null) {
            eyeAGL = Math.max(0.0, heightMeters);
        } else if (mode == ElevationService.ObserverHeightMode.ASL && heightMeters != null) {
            double ground = Double.NaN;
            if (isInside(grid, obsPx[0], obsPx[1])) {
                ground = sampleNearest(grid, obsPx[0], obsPx[1]);
            }
            if (Double.isNaN(ground) && cache != null) {
                try { ground = ElevationService.getElevationAt(observerLatDeg, observerLonDeg, grid.zoom, cache); }
                catch (Exception ignore) { ground = Double.NaN; }
            }
            if (!Double.isNaN(ground)) eyeAGL = Math.max(0.0, heightMeters - ground); else eyeAGL = Math.max(0.0, heightMeters);
        }
        double horizonMeters = eyeAGL > 0.0 ? Math.sqrt(2.0 * earthRadiusMeters * eyeAGL + eyeAGL * eyeAGL) : 0.0;

        int w = grid.width, h = grid.height;
        double[][] out = new double[h][w];
        for (int y = 0; y < h; y++) {
            double[] inRow = grid.data[y];
            double[] outRow = out[y];
            for (int x = 0; x < w; x++) {
                double v = inRow[x];
                if (Double.isNaN(v)) { outRow[x] = Double.NaN; continue; }
                double dx = x - obsPx[0];
                double dy = y - obsPx[1];
                double sMeters = Math.hypot(dx, dy) * metersPerPixel;
                if (horizonMeters > 0.0 && sMeters <= horizonMeters) {
                    outRow[x] = v;
                } else {
                    double drop = (sMeters * sMeters) / (2.0 * earthRadiusMeters);
                    outRow[x] = v - drop;
                }
            }
        }
        ElevationService.ElevationGrid result = new ElevationService.ElevationGrid(out, grid.tileSize, grid.tilesWide, grid.tilesHigh, grid.zoom, grid.centerTileX, grid.centerTileY);

        if (enableCache && filePath != null) {
            tryWriteGrid(filePath, result);
        }
        return result;
    }

    private static String buildKey(ElevationService.ElevationGrid g, double olat, double olon, ElevationService.ObserverHeightMode m, Double h, double R) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String meta = g.width+"x"+g.height+"_ts"+g.tileSize+"_tw"+g.tilesWide+"_th"+g.tilesHigh+"_z"+g.zoom+"_cx"+g.centerTileX+"_cy"+g.centerTileY+
                    "|olat="+String.format("%.6f", olat)+"|olon="+String.format("%.6f", olon)+"|mode="+m+"|h="+(h==null?"":String.format("%.2f", h))+"|R="+R;
            md.update(meta.getBytes());
            // Also mix in a lightweight checksum of grid values to avoid mismatches when tiles change
            long checksum = 0;
            for (int y = 0; y < g.height; y+=Math.max(1, g.height/64)) {
                double[] row = g.data[y];
                for (int x = 0; x < g.width; x+=Math.max(1, g.width/64)) {
                    long bits = Double.doubleToLongBits(row[x]);
                    checksum = (checksum * 1315423911L) ^ bits;
                }
            }
            md.update(Long.toHexString(checksum).getBytes());
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            // Fallback key
            return "nocache";
        }
    }

    private static void tryWriteGrid(Path file, ElevationService.ElevationGrid grid) {
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            dos.writeInt(1); // version
            dos.writeInt(grid.width);
            dos.writeInt(grid.height);
            for (int y = 0; y < grid.height; y++) {
                double[] row = grid.data[y];
                for (int x = 0; x < grid.width; x++) {
                    dos.writeDouble(row[x]);
                }
            }
        } catch (IOException ignore) { }
    }

    private static ElevationService.ElevationGrid tryReadGrid(Path file, ElevationService.ElevationGrid like) {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int ver = dis.readInt();
            if (ver != 1) return null;
            int w = dis.readInt();
            int h = dis.readInt();
            if (w != like.width || h != like.height) return null;
            double[][] data = new double[h][w];
            for (int y = 0; y < h; y++) {
                double[] row = data[y];
                for (int x = 0; x < w; x++) {
                    row[x] = dis.readDouble();
                }
            }
            return new ElevationService.ElevationGrid(data, like.tileSize, like.tilesWide, like.tilesHigh, like.zoom, like.centerTileX, like.centerTileY);
        } catch (IOException e) {
            return null;
        }
    }

    // Utilities duplicated in this class for decoupling
    private static boolean isInside(ElevationService.ElevationGrid grid, double px, double py) {
        return px >= 0 && py >= 0 && px < grid.width && py < grid.height;
    }
    private static double sampleNearest(ElevationService.ElevationGrid grid, double px, double py) {
        int ix = (int)Math.max(0, Math.min(grid.width - 1, Math.round(px)));
        int iy = (int)Math.max(0, Math.min(grid.height - 1, Math.round(py)));
        return grid.data[iy][ix];
    }
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
