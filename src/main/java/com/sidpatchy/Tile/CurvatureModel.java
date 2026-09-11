package com.sidpatchy.Tile;

import java.io.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.IntConsumer;

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

    private static final String DEFAULT_CACHE_DIR = "./terrain_cache/curvature_cache";

    /**
     * Applies Earth curvature drop relative to the geometric center of the grid.
     * Returns a NEW grid (the input is not modified).
     */
    public static ElevationService.ElevationGrid applyCenterCurvature(
            ElevationService.ElevationGrid grid,
            double centerLatDeg) {
        int w = grid.width;
        int h = grid.height;
        float[][] out = new float[h][w];
        double[] center = gridPixelToLatLon(grid, (w - 1) / 2.0, (h - 1) / 2.0);
        for (int y = 0; y < h; y++) {
            float[] inRow = grid.data[y];
            float[] outRow = out[y];
            for (int x = 0; x < w; x++) {
                double v = inRow[x];
                if (Double.isNaN(v)) { outRow[x] = Float.NaN; continue; }
                double[] target = gridPixelToLatLon(grid, x, y);
                double drop = Wgs84.surfaceDrop(center[0], center[1], target[0], target[1]);
                outRow[x] = (float) (v - drop);
            }
        }
        return new ElevationService.ElevationGrid(out, grid.tileSize, grid.tilesWide, grid.tilesHigh, grid.zoom, grid.centerTileX, grid.centerTileY);
    }

    /**
     * Renders the observer-relative curvature-adjusted grid without allocating a second grid.
     * The resulting image uses the same automatic grayscale scaling as elevation exports.
     */
    public static BufferedImage observerCurvatureToImage(
            ElevationService.ElevationGrid grid,
            double observerLatDeg,
            double observerLonDeg,
            ElevationService.ObserverHeightMode mode,
            Double heightMeters,
            TileCache cache,
            IntConsumer progress) throws IOException {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int y = 0; y < grid.height; y++) {
            float[] row = grid.data[y];
            for (int x = 0; x < grid.width; x++) {
                double value = curvatureValue(row[x], x, y, grid, observerLatDeg, observerLonDeg);
                if (!Double.isNaN(value)) {
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
            }
        }

        BufferedImage image = new BufferedImage(grid.width, grid.height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[grid.width];
        double range = max - min;
        boolean flat = !Double.isFinite(range) || range == 0.0;
        for (int y = 0; y < grid.height; y++) {
            float[] row = grid.data[y];
            for (int x = 0; x < grid.width; x++) {
                double value = curvatureValue(row[x], x, y, grid, observerLatDeg, observerLonDeg);
                if (Double.isNaN(value)) {
                    pixels[x] = 0x00000000;
                } else {
                    double normalized = flat ? 0.5 : Math.max(0.0, Math.min(1.0, (value - min) / range));
                    int gray = (int) Math.round(normalized * 255.0);
                    pixels[x] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
                }
            }
            image.setRGB(0, y, grid.width, 1, pixels, 0, grid.width);
            progress.accept(y + 1);
        }
        return image;
    }

    private static double curvatureValue(float value, int x, int y,
                                         ElevationService.ElevationGrid grid,
                                         double observerLatDeg, double observerLonDeg) {
        if (Float.isNaN(value)) return Double.NaN;
        double[] target = gridPixelToLatLon(grid, x, y);
        double drop = Wgs84.surfaceDrop(observerLatDeg, observerLonDeg, target[0], target[1]);
        return value - drop;
    }

    private static double observerEyeAgl(ElevationService.ElevationGrid grid, double[] observerPx,
                                         double observerLatDeg, double observerLonDeg,
                                         ElevationService.ObserverHeightMode mode, Double heightMeters,
                                         TileCache cache) {
        if (heightMeters == null) return 0.0;
        double height = Math.max(0.0, heightMeters);
        if (mode != ElevationService.ObserverHeightMode.ASL) return height;
        double ground = Double.NaN;
        if (isInside(grid, observerPx[0], observerPx[1])) ground = sampleNearest(grid, observerPx[0], observerPx[1]);
        if (Double.isNaN(ground) && cache != null) {
            try { ground = ElevationService.getElevationAt(observerLatDeg, observerLonDeg, grid.zoom, cache); }
            catch (Exception ignored) { }
        }
        return Double.isNaN(ground) ? height : Math.max(0.0, height - ground);
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
        return applyObserverCurvature(grid, observerLatDeg, observerLonDeg, mode, heightMeters, cache, cacheDir, enableCache);
    }

    public static ElevationService.ElevationGrid applyObserverCurvature(
            ElevationService.ElevationGrid grid,
            double observerLatDeg,
            double observerLonDeg,
            ElevationService.ObserverHeightMode mode,
            Double heightMeters,
            TileCache cache,
            String cacheDir,
            boolean enableCache) throws IOException {
        // Prepare cache path
        if (enableCache) {
            try { Files.createDirectories(Paths.get(cacheDir)); } catch (IOException ignore) {}
        }
        String key = null;
        Path filePath = null;
        if (enableCache) {
            key = buildKey(grid, observerLatDeg, observerLonDeg, mode, heightMeters);
            filePath = Paths.get(cacheDir, key + ".bin");
            if (Files.exists(filePath)) {
                ElevationService.ElevationGrid cached = tryReadGrid(filePath, grid);
                if (cached != null) return cached;
            }
        }

        int w = grid.width, h = grid.height;
        float[][] out = new float[h][w];
        for (int y = 0; y < h; y++) {
            float[] inRow = grid.data[y];
            float[] outRow = out[y];
            for (int x = 0; x < w; x++) {
                double v = inRow[x];
                if (Double.isNaN(v)) { outRow[x] = Float.NaN; continue; }
                double[] target = gridPixelToLatLon(grid, x, y);
                double drop = Wgs84.surfaceDrop(observerLatDeg, observerLonDeg, target[0], target[1]);
                outRow[x] = (float) (v - drop);
            }
        }
        ElevationService.ElevationGrid result = new ElevationService.ElevationGrid(out, grid.tileSize, grid.tilesWide, grid.tilesHigh, grid.zoom, grid.centerTileX, grid.centerTileY);

        if (enableCache && filePath != null) {
            tryWriteGrid(filePath, result);
        }
        return result;
    }

    private static double sampleBilinear(ElevationService.ElevationGrid grid, double px, double py) {
        if (!isInside(grid, px, py)) return Double.NaN;
        int x0 = (int) Math.floor(px);
        int y0 = (int) Math.floor(py);
        int x1 = Math.min(x0 + 1, grid.width - 1);
        int y1 = Math.min(y0 + 1, grid.height - 1);
        double tx = px - x0;
        double ty = py - y0;
        double a = grid.data[y0][x0], b = grid.data[y0][x1];
        double c = grid.data[y1][x0], d = grid.data[y1][x1];
        if (Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(c) || Double.isNaN(d)) return Double.NaN;
        return (a * (1.0 - tx) + b * tx) * (1.0 - ty)
                + (c * (1.0 - tx) + d * tx) * ty;
    }

    private static double[] gridPixelToLatLon(ElevationService.ElevationGrid grid, double px, double py) {
        int radiusTiles = (grid.tilesWide - 1) / 2;
        double n = Math.pow(2.0, grid.zoom);
        double globalX = grid.centerTileX - radiusTiles + (px + 0.5) / grid.tileSize;
        double globalY = grid.centerTileY - radiusTiles + (py + 0.5) / grid.tileSize;
        double lon = normalizeLongitude(globalX / n * 360.0 - 180.0);
        double mercator = Math.PI * (1.0 - 2.0 * globalY / n);
        double lat = Math.toDegrees(Math.atan(Math.sinh(mercator)));
        return new double[]{lat, lon};
    }

    private static String buildKey(ElevationService.ElevationGrid g, double olat, double olon, ElevationService.ObserverHeightMode m, Double h) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String meta = g.width+"x"+g.height+"_ts"+g.tileSize+"_tw"+g.tilesWide+"_th"+g.tilesHigh+"_z"+g.zoom+"_cx"+g.centerTileX+"_cy"+g.centerTileY+
                    "|olat="+String.format("%.6f", olat)+"|olon="+String.format("%.6f", olon)+"|mode="+m+"|h="+(h==null?"":String.format("%.2f", h))+"|wgs84";
            md.update(meta.getBytes());
            // Also mix in a lightweight checksum of grid values to avoid mismatches when tiles change
            long checksum = 0;
            for (int y = 0; y < g.height; y+=Math.max(1, g.height/64)) {
                float[] row = g.data[y];
                for (int x = 0; x < g.width; x+=Math.max(1, g.width/64)) {
                    long bits = Float.floatToIntBits(row[x]);
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
            dos.writeInt(2); // version; values are float32
            dos.writeInt(grid.width);
            dos.writeInt(grid.height);
            for (int y = 0; y < grid.height; y++) {
                float[] row = grid.data[y];
                for (int x = 0; x < grid.width; x++) {
                    dos.writeFloat(row[x]);
                }
            }
        } catch (IOException ignore) { }
    }

    private static ElevationService.ElevationGrid tryReadGrid(Path file, ElevationService.ElevationGrid like) {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int ver = dis.readInt();
            if (ver != 2) return null;
            int w = dis.readInt();
            int h = dis.readInt();
            if (w != like.width || h != like.height) return null;
            float[][] data = new float[h][w];
            for (int y = 0; y < h; y++) {
                float[] row = data[y];
                for (int x = 0; x < w; x++) {
                    row[x] = dis.readFloat();
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
