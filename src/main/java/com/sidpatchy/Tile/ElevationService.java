package com.sidpatchy.Tile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * Utility service for retrieving elevations from Terrarium tiles.
 */
public class ElevationService {

    /** Simple container for a latitude/longitude pair in degrees. */
    public static class LatLon {
        public final double lat;
        public final double lon;
        public LatLon(double lat, double lon) {
            this.lat = lat;
            this.lon = lon;
        }
    }

    private static final double MAX_MERCATOR_LAT = 85.05112878; // Web Mercator limit

    /**
     * Returns the elevation (in meters) for a single latitude/longitude at the specified zoom level.
     * Uses the provided TileCache to fetch or read the appropriate Terrarium tile.
     */
    public static double getElevationAt(double latDeg, double lonDeg, int zoom, TileCache cache) throws IOException {
        if (cache == null) throw new IllegalArgumentException("TileCache must not be null");
        // Clamp latitude to Web Mercator supported range
        double lat = clamp(latDeg, -MAX_MERCATOR_LAT, MAX_MERCATOR_LAT);
        double lon = normalizeLongitude(lonDeg);

        // Compute fractional tile coordinates
        double n = Math.pow(2.0, zoom);
        double xTileF = (lon + 180.0) / 360.0 * n;
        double latRad = Math.toRadians(lat);
        double yTileF = (1.0 - (Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI)) / 2.0 * n;

        // Wrap X around antimeridian and clamp Y to [0, n)
        xTileF = wrap(xTileF, 0.0, n);
        yTileF = clamp(yTileF, 0.0, Math.nextDown(n)); // stay inside last tile

        int xTile = (int) Math.floor(xTileF);
        int yTile = (int) Math.floor(yTileF);

        float[][] tile = cache.getElevationData(zoom, xTile, yTile);
        int tileH = tile.length;
        int tileW = tileH == 0 ? 0 : tile[0].length;

        int pixelX = (int) Math.floor((xTileF - xTile) * tileW);
        int pixelY = (int) Math.floor((yTileF - yTile) * tileH);

        pixelX = clamp(pixelX, 0, tileW - 1);
        pixelY = clamp(pixelY, 0, tileH - 1);

        return tile[pixelY][pixelX];
    }

    /**
     * Returns elevations (in meters) for a list of LatLon points at the specified zoom level.
     * Results are in the same order as the input list.
     */
    public static List<Double> getElevationsAt(List<LatLon> points, int zoom, TileCache cache) throws IOException {
        if (cache == null) throw new IllegalArgumentException("TileCache must not be null");
        if (points == null) throw new IllegalArgumentException("points must not be null");
        int size = points.size();
        List<Double> results = new ArrayList<>(Collections.nCopies(size, 0.0));

        // Group points by tile to avoid redundant reads
        Map<TileKey, List<IndexedPoint>> groups = new HashMap<>();
        double n = Math.pow(2.0, zoom);

        for (int idx = 0; idx < size; idx++) {
            LatLon p = points.get(idx);
            double lat = clamp(p.lat, -MAX_MERCATOR_LAT, MAX_MERCATOR_LAT);
            double lon = normalizeLongitude(p.lon);

            double xTileF = (lon + 180.0) / 360.0 * n;
            double latRad = Math.toRadians(lat);
            double yTileF = (1.0 - (Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI)) / 2.0 * n;

            xTileF = wrap(xTileF, 0.0, n);
            yTileF = clamp(yTileF, 0.0, Math.nextDown(n));

            int xTile = (int) Math.floor(xTileF);
            int yTile = (int) Math.floor(yTileF);

            TileKey key = new TileKey(zoom, xTile, yTile);
            groups.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new IndexedPoint(idx, xTileF, yTileF));
        }

        // Load each tile once and compute its point elevations
        for (Map.Entry<TileKey, List<IndexedPoint>> entry : groups.entrySet()) {
            TileKey key = entry.getKey();
            float[][] tile = cache.getElevationData(key.zoom, key.x, key.y);
            int tileH = tile.length;
            int tileW = tileH == 0 ? 0 : tile[0].length;

            for (IndexedPoint ip : entry.getValue()) {
                double localXF = ip.xTileF - Math.floor(ip.xTileF);
                double localYF = ip.yTileF - Math.floor(ip.yTileF);
                int px = clamp((int) Math.floor(localXF * tileW), 0, tileW - 1);
                int py = clamp((int) Math.floor(localYF * tileH), 0, tileH - 1);
                double elev = tile[py][px];
                results.set(ip.index, elev);
            }
        }

        return results;
    }

    // Helpers
    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double wrap(double v, double minInclusive, double maxExclusive) {
        double range = maxExclusive - minInclusive;
        double r = (v - minInclusive) % range;
        if (r < 0) r += range;
        return r + minInclusive;
    }

    private static double normalizeLongitude(double lonDeg) {
        // Normalize to [-180, 180)
        double lon = ((lonDeg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
        return lon;
    }

    private static class TileKey {
        final int zoom; final int x; final int y;
        TileKey(int zoom, int x, int y) { this.zoom = zoom; this.x = x; this.y = y; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TileKey)) return false;
            TileKey k = (TileKey) o;
            return zoom == k.zoom && x == k.x && y == k.y;
        }
        @Override public int hashCode() { return Objects.hash(zoom, x, y); }
    }

    private static class IndexedPoint {
        final int index; final double xTileF; final double yTileF;
        IndexedPoint(int index, double xTileF, double yTileF) {
            this.index = index; this.xTileF = xTileF; this.yTileF = yTileF;
        }
    }

    /** Shape selector for building composite grids. */
    public enum AreaShape { SQUARE, CIRCLE }

        /** Observer height reference mode. */
        public enum ObserverHeightMode { GROUND, AGL, ASL }

    /** Container for a stitched elevation grid built from multiple tiles. */
    public static class ElevationGrid {
        /** Elevation values in meters; float storage keeps large grids memory efficient. */
        public final float[][] data; // [height][width]
        public final int width;
        public final int height;
        public final int tileSize;
        public final int tilesWide;
        public final int tilesHigh;
        public final int zoom;
        public final int centerTileX;
        public final int centerTileY;

        public ElevationGrid(float[][] data, int tileSize, int tilesWide, int tilesHigh,
                              int zoom, int centerTileX, int centerTileY) {
            this.data = data;
            this.height = data.length;
            this.width = data.length > 0 ? data[0].length : 0;
            this.tileSize = tileSize;
            this.tilesWide = tilesWide;
            this.tilesHigh = tilesHigh;
            this.zoom = zoom;
            this.centerTileX = centerTileX;
            this.centerTileY = centerTileY;
        }
    }

    /**
     * Builds a composite elevation grid centered on the given lat/lon by stitching Terrarium tiles
     * within a specified radius (in tiles). Supports square or circle (tile-approximation) areas.
     * The resulting grid covers (2*radius+1) x (2*radius+1) tiles; for CIRCLE shape, tiles outside
     * the circle are left as NaN values in the data array.
     *
     * Returns ElevationGrid with data indexed as data[row][col] where row increases southward.
     */
    public static ElevationGrid getElevationGridAround(double latDeg, double lonDeg, int zoom,
                                                       int radiusTiles, AreaShape shape,
                                                       TileCache cache) throws IOException {
        return getElevationGridAround(latDeg, lonDeg, zoom, radiusTiles, shape, cache, current -> { });
    }

    public static ElevationGrid getElevationGridAround(double latDeg, double lonDeg, int zoom,
                                                       int radiusTiles, AreaShape shape,
                                                       TileCache cache, IntConsumer tileProgress) throws IOException {
        if (cache == null) throw new IllegalArgumentException("TileCache must not be null");
        if (radiusTiles < 0) throw new IllegalArgumentException("radiusTiles must be >= 0");
        cache.beginBatch();

        // Clamp and normalize inputs
        double lat = clamp(latDeg, -MAX_MERCATOR_LAT, MAX_MERCATOR_LAT);
        double lon = normalizeLongitude(lonDeg);

        // Compute center tile (fractional then floor)
        double n = Math.pow(2.0, zoom);
        double xTileF = (lon + 180.0) / 360.0 * n;
        double latRad = Math.toRadians(lat);
        double yTileF = (1.0 - (Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI)) / 2.0 * n;
        int centerX = (int)Math.floor(wrap(xTileF, 0.0, n));
        int centerY = (int)Math.floor(clamp(yTileF, 0.0, Math.nextDown(n)));

        // Read a sample tile to determine tile dimensions
        float[][] centerTile = cache.getElevationData(zoom, centerX, centerY);
        final int tileH = centerTile.length;
        final int tileW = tileH == 0 ? 0 : centerTile[0].length;
        if (tileW != tileH) {
            // Still supported, but track by height for rows and width for columns
        }

        int tilesSpan = radiusTiles * 2 + 1;
        int totalW = tilesSpan * tileW;
        int totalH = tilesSpan * tileH;

        // Initialize data with NaN to easily mark missing/unused cells (e.g., outside circle or y clamp)
        float[][] data = new float[totalH][totalW];
        for (int r = 0; r < totalH; r++) {
            Arrays.fill(data[r], Float.NaN);
        }

        int maxYIndex = (int)n - 1; // valid y: [0, n-1]
        int tileNumber = 0;
        int totalTiles = tilesSpan * tilesSpan;
        for (int dy = -radiusTiles; dy <= radiusTiles; dy++) {
            for (int dx = -radiusTiles; dx <= radiusTiles; dx++) {
                tileNumber++;
                if (shape == AreaShape.CIRCLE) {
                    // Improved tiled circle selection: include a tile if its square (size 1x1 tiles)
                    // INTERSECTS the circle of radius `radiusTiles` centered at the grid center.
                    // Compute shortest distance from circle center to the tile square centered at (dx, dy)
                    // with half-size 0.5 in tile units.
                    double ax = Math.max(Math.abs(dx) - 0.5, 0.0);
                    double ay = Math.max(Math.abs(dy) - 0.5, 0.0);
                    if ((ax * ax + ay * ay) > (radiusTiles * radiusTiles)) {
                        tileProgress.accept(tileNumber);
                        continue; // skip tiles whose square lies completely outside the circle
                    }
                }
                int tx = wrapInt(centerX + dx, (int)n); // wrap across antimeridian
                int ty = centerY + dy;
                if (ty < 0 || ty > maxYIndex) {
                    tileProgress.accept(tileNumber);
                    continue; // outside Web Mercator vertical bounds
                }

                float[][] tile = cache.getElevationData(zoom, tx, ty);
                int h = tile.length;
                int w = h == 0 ? 0 : tile[0].length;

                int destX0 = (dx + radiusTiles) * tileW;
                int destY0 = (dy + radiusTiles) * tileH;

                // Copy pixel-by-pixel decoding elevation values
                for (int py = 0; py < h; py++) {
                    int row = destY0 + py;
                    if (row < 0 || row >= totalH) continue;
                    for (int px = 0; px < w; px++) {
                        int col = destX0 + px;
                        if (col < 0 || col >= totalW) continue;
                        data[row][col] = tile[py][px];
                    }
                }
                tileProgress.accept(tileNumber);
            }
        }

        cache.endBatch();
        return new ElevationGrid(data, tileW, tilesSpan, tilesSpan, zoom, centerX, centerY);
    }

    private static int wrapInt(int v, int modulo) {
        int m = v % modulo;
        if (m < 0) m += modulo;
        return m;
    }

    /**
     * Converts an ElevationGrid to a grayscale ARGB BufferedImage.
     * - Elevations are linearly scaled from [min, max] to [0, 255] (black to white).
     * - Cells with NaN are transparent (alpha = 0), others are opaque (alpha = 255).
     * If min == max (flat), all non-NaN pixels will be mid-gray (128).
     */
    public static BufferedImage elevationGridToImage(ElevationGrid grid, double min, double max) {
        return elevationGridToImage(grid, min, max, current -> { });
    }

    public static BufferedImage elevationGridToImage(ElevationGrid grid, double min, double max,
                                                     IntConsumer progress) {
        int w = grid.width;
        int h = grid.height;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        double range = max - min;
        boolean flat = range == 0.0 || Double.isNaN(range) || Double.isInfinite(range);
        int[] pixels = new int[w];
        for (int y = 0; y < h; y++) {
            float[] row = grid.data[y];
            for (int x = 0; x < w; x++) {
                double v = row[x];
                if (Double.isNaN(v)) {
                    pixels[x] = 0x00000000; // fully transparent
                } else {
                    int gray;
                    if (flat) {
                        gray = 128;
                    } else {
                        double t = (v - min) / range;
                        if (t < 0) t = 0; else if (t > 1) t = 1;
                        gray = (int)Math.round(t * 255.0);
                    }
                    int argb = (0xFF << 24) | (gray << 16) | (gray << 8) | gray;
                    pixels[x] = argb;
                }
            }
            img.setRGB(0, y, w, 1, pixels, 0, w);
            progress.accept(y + 1);
        }
        return img;
    }

    /**
     * Auto-scales the ElevationGrid to grayscale by scanning for min/max (ignoring NaN).
     */
    public static BufferedImage elevationGridToImage(ElevationGrid grid) {
        return elevationGridToImage(grid, current -> { });
    }

    public static BufferedImage elevationGridToImage(ElevationGrid grid, IntConsumer progress) {
        // First pass: compute min, max, count, and sum for mean (ignoring NaN)
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sum = 0.0;
        int count = 0;
        int h = grid.height;
        int w = grid.width;
        for (int y = 0; y < h; y++) {
            float[] row = grid.data[y];
            for (int x = 0; x < w; x++) {
                double v = row[x];
                if (!Double.isNaN(v)) {
                    if (v < min) min = v;
                    if (v > max) max = v;
                    sum += v;
                    count++;
                }
            }
        }
        if (count == 0) {
            // All NaN – return a fully transparent image
            return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        }
        double mean = sum / count;

        // Second pass: compute standard deviation (ignoring NaN)
        double sqSum = 0.0;
        for (int y = 0; y < h; y++) {
            float[] row = grid.data[y];
            for (int x = 0; x < w; x++) {
                double v = row[x];
                if (!Double.isNaN(v)) {
                    double d = v - mean;
                    sqSum += d * d;
                }
            }
        }
        double variance = count > 1 ? (sqSum / (count - 1)) : 0.0;
        double std = Math.sqrt(Math.max(0.0, variance));

        // Build a robust display window centered around the mean.
        // Start with mean ± 3σ, but clamp to actual [min, max].
        double k = 3.0;
        double winMin = mean - k * std;
        double winMax = mean + k * std;

        // If std is extremely small (very flat terrain), widen to a small fixed window
        // to avoid an almost uniform gray image. Choose ±15 m around the mean.
        final double MIN_WINDOW_METERS = 30.0; // total span
        if (!(winMax > winMin)) {
            // Handle NaN or degenerate
            winMin = mean - MIN_WINDOW_METERS / 2.0;
            winMax = mean + MIN_WINDOW_METERS / 2.0;
        }
        double currentSpan = winMax - winMin;
        if (currentSpan < MIN_WINDOW_METERS) {
            double mid = (winMin + winMax) / 2.0;
            winMin = mid - MIN_WINDOW_METERS / 2.0;
            winMax = mid + MIN_WINDOW_METERS / 2.0;
        }

        // Finally clamp to the actual data range to avoid inventing values that compress contrast
        // when the true data span is larger.
        winMin = Math.max(winMin, min);
        winMax = Math.min(winMax, max);

        // If clamping collapsed the window (all pixels same or near-same), ensure a tiny span
        // so that the conversion logic doesn't treat it as completely flat.
        if (!(winMax > winMin)) {
            double eps = 1e-6; // tiny span in meters
            winMax = winMin + eps;
        }

        return elevationGridToImage(grid, winMin, winMax, progress);
    }

    /** Writes the ElevationGrid using the output file extension and auto min/max scaling. */
    public static void saveElevationGridAsPng(ElevationGrid grid, File outFile) throws IOException {
        BufferedImage img = elevationGridToImage(grid);
        writeImage(img, outFile);
    }

    public static void saveElevationGridAsPng(ElevationGrid grid, File outFile,
                                              IntConsumer progress) throws IOException {
        BufferedImage img = elevationGridToImage(grid, progress);
        writeImage(img, outFile);
    }

    private static String imageFormat(File outFile) {
        String name = outFile.getName();
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot + 1).toLowerCase() : "png";
        if (extension.equals("jpeg")) extension = "jpg";
        if (!extension.equals("png") && !extension.equals("jpg") && !extension.equals("webp")) {
            throw new IllegalArgumentException("Unsupported output format ." + extension
                    + "; use .png, .jpg, .jpeg, or .webp (JXL is not currently supported)");
        }
        return extension;
    }

    private static void writeImage(BufferedImage image, File outFile) throws IOException {
        String format = imageFormat(outFile);
        BufferedImage toWrite = image;
        if (format.equals("jpg")) {
            toWrite = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = toWrite.createGraphics();
            try {
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, toWrite.getWidth(), toWrite.getHeight());
                g.drawImage(image, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        if (!ImageIO.write(toWrite, format, outFile)) {
            throw new IOException("No ImageIO writer is available for ." + format);
        }
    }

    public static void writeImageFile(BufferedImage image, File outFile) throws IOException {
        writeImage(image, outFile);
    }

    /**
     * Computes a line-of-sight (LOS) mask from an observer and applies it to the grid.
     * Any cell that is NOT visible from the observer (blocked by terrain considering Earth curvature)
     * is set to NaN in the returned grid. Visible cells retain their original elevation values.
     *
     * Implementation details:
     * - Terrain is compared using spherical observer-to-sample geometry, so curvature is applied
     *   continuously without materializing a curvature-adjusted copy of the grid.
     * - Then we cast rays uniformly around the observer (angular sweep). Along each ray, we track
     *   the maximum apparent sight angle, sampling each traversed cell at multiple points with
     *   bilinear DEM interpolation. A cell is visible if any sampled point rises above all previous
     *   terrain on that ray.
     * - The final output grid contains NaN for occluded points, and retains original elevations for visible points.
     */
    public static ElevationGrid applyLineOfSightMask(ElevationGrid grid,
                                                     double observerLatDeg,
                                                     double observerLonDeg,
                                                     ObserverHeightMode mode,
                                                     Double heightMeters,
                                                     TileCache cache) throws IOException {
        // Default to 720 rays (~0.5° resolution)
        return applyLineOfSightMask(grid, observerLatDeg, observerLonDeg, mode, heightMeters, cache, 720);
    }

    /**
     * Overload of applyLineOfSightMask that allows configuring the number of angular rays.
     * Higher values increase angular resolution at the cost of performance.
     *
     * @param angleBins number of angular rays (e.g., 720 = 0.5°, 1440 = 0.25°)
     */
    public static ElevationGrid applyLineOfSightMask(ElevationGrid grid,
                                                     double observerLatDeg,
                                                     double observerLonDeg,
                                                     ObserverHeightMode mode,
                                                     Double heightMeters,
                                                      TileCache cache,
                                                      int angleBins) throws IOException {
        return applyLineOfSightMask(grid, observerLatDeg, observerLonDeg, mode, heightMeters, cache, angleBins, null);
    }

    public static ElevationGrid applyLineOfSightMask(ElevationGrid grid,
                                                     double observerLatDeg,
                                                     double observerLonDeg,
                                                     ObserverHeightMode mode,
                                                     Double heightMeters,
                                                     TileCache cache,
                                                     int angleBins,
                                                     IntConsumer progress) throws IOException {
        boolean[][] visible = VisibilityEngine.computeVisibilityMask(
                grid, observerLatDeg, observerLonDeg, mode, heightMeters, cache, angleBins, progress);
        int w = grid.width, h = grid.height;
        float[][] out = new float[h][w];
        for (int y = 0; y < h; y++) {
            float[] rowIn = grid.data[y];
            float[] rowOut = out[y];
            boolean[] visRow = visible[y];
            for (int x = 0; x < w; x++) {
                rowOut[x] = (visRow != null && visRow[x]) ? rowIn[x] : Float.NaN;
            }
        }
        return new ElevationGrid(out, grid.tileSize, grid.tilesWide, grid.tilesHigh, grid.zoom, grid.centerTileX, grid.centerTileY);
    }
    /**
     * Applies earth curvature drop to an ElevationGrid, returning a NEW grid with adjusted elevations.
     * Assumes the viewer is located at the center of the grid. Each cell is lowered by s^2/(2R),
     * where s is ground distance from the center and R is Earth radius.
     * Uses Web Mercator ground resolution at the provided center latitude.
     *
     * Note: This does not consider refraction or viewer eye height; use the overload to customize.
     */
    public static ElevationGrid applyEarthCurvatureDrop(ElevationGrid grid, double centerLatDeg) {
        return CurvatureModel.applyCenterCurvature(grid, centerLatDeg);
    }

    /**
     * Applies earth curvature drop to an ElevationGrid with customization.
     * - centerLatDeg: latitude (deg) at grid center, used to compute ground resolution.
     * - earthRadiusMeters: sphere radius for curvature model.
     * - considerViewerEyeHeight and viewerEyeHeightMeters are retained for API compatibility.
     *   Observer height affects line-of-sight calculations, not the curvature drop itself.
     *
     * Returns a NEW ElevationGrid; the original grid is not modified.
     */
    public static ElevationGrid applyEarthCurvatureDrop(ElevationGrid grid,
                                                        double centerLatDeg,
                                                        double earthRadiusMeters,
                                                        boolean considerViewerEyeHeight,
                                                        double viewerEyeHeightMeters) {
        int w = grid.width;
        int h = grid.height;
        float[][] out = new float[h][w];

        // Ground resolution (meters per pixel) at latitude for Web Mercator
        double metersPerPixel = (2.0 * Math.PI * earthRadiusMeters * Math.cos(Math.toRadians(centerLatDeg)))
                / (grid.tileSize * Math.pow(2.0, grid.zoom));

        // Center pixel (viewer position). Use the exact center of the composite grid.
        double cx = (w - 1) / 2.0;
        double cy = (h - 1) / 2.0;

        for (int y = 0; y < h; y++) {
            float[] inRow = grid.data[y];
            float[] outRow = out[y];
            for (int x = 0; x < w; x++) {
                double v = inRow[x];
                if (Double.isNaN(v)) {
                    outRow[x] = Float.NaN;
                    continue;
                }
                double dx = (x - cx);
                double dy = (y - cy);
                double sPixels = Math.hypot(dx, dy);
                double sMeters = sPixels * metersPerPixel;

                double drop = (sMeters * sMeters) / (2.0 * earthRadiusMeters);
                outRow[x] = (float) (v - drop);
            }
        }
        return new ElevationGrid(out, grid.tileSize, grid.tilesWide, grid.tilesHigh, grid.zoom, grid.centerTileX, grid.centerTileY);
    }

    /**
     * In-place variant: modifies the provided grid by applying earth curvature drop using centerLatDeg.
     */
    public static void applyEarthCurvatureDropInPlace(ElevationGrid grid, double centerLatDeg) {
        ElevationGrid adjusted = applyEarthCurvatureDrop(grid, centerLatDeg);
        // Copy back into the same data array if dimensions match
        for (int y = 0; y < grid.height; y++) {
            System.arraycopy(adjusted.data[y], 0, grid.data[y], 0, grid.width);
        }
    }

    // ---- Observer-centric curvature with AGL/ASL modes ----

    private static class PixelPos { final double x; final double y; PixelPos(double x, double y){ this.x=x; this.y=y; } }

    private static double[] latLonToTileFractional(double latDeg, double lonDeg, int zoom) {
        double lat = clamp(latDeg, -MAX_MERCATOR_LAT, MAX_MERCATOR_LAT);
        double lon = normalizeLongitude(lonDeg);
        double n = Math.pow(2.0, zoom);
        double xTileF = (lon + 180.0) / 360.0 * n;
        double latRad = Math.toRadians(lat);
        double yTileF = (1.0 - (Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI)) / 2.0 * n;
        xTileF = wrap(xTileF, 0.0, n);
        yTileF = clamp(yTileF, 0.0, Math.nextDown(n));
        return new double[]{xTileF, yTileF};
    }

    private static PixelPos latLonToGridPixel(ElevationGrid grid, double latDeg, double lonDeg) {
        double[] tf = latLonToTileFractional(latDeg, lonDeg, grid.zoom);
        double xTileF = tf[0];
        double yTileF = tf[1];
        int n = 1 << grid.zoom;
        int radiusTiles = (grid.tilesWide - 1) / 2;

        // Work with integer tile indices for delta; keep fractional parts separate
        int xTile = (int) Math.floor(xTileF);
        int yTile = (int) Math.floor(yTileF);
        double fracX = xTileF - xTile;
        double fracY = yTileF - yTile;

        // delta in whole tiles from grid center tile, with wrap on X to shortest path
        int dxTilesInt = xTile - grid.centerTileX;
        // wrap to [-n/2, n/2)
        dxTilesInt = (int) Math.round(((dxTilesInt + n / 2.0) % n) - n / 2.0);
        int dyTilesInt = yTile - grid.centerTileY; // Y is clamped, no wrap

        double px = (dxTilesInt + radiusTiles) * (double) grid.tileSize + fracX * grid.tileSize;
        double py = (dyTilesInt + radiusTiles) * (double) grid.tileSize + fracY * grid.tileSize;
        return new PixelPos(px, py);
    }

    private static boolean isInsideGrid(ElevationGrid grid, double px, double py) {
        return px >= 0 && py >= 0 && px < grid.width && py < grid.height;
    }

    private static double sampleNearestGround(ElevationGrid grid, double px, double py) {
        int ix = clamp((int)Math.round(px), 0, grid.width - 1);
        int iy = clamp((int)Math.round(py), 0, grid.height - 1);
        return grid.data[iy][ix];
    }

    /**
     * Applies earth curvature drop around an arbitrary observer location with height modes.
     * - mode GROUND: height ignored (viewer at ground level)
     * - mode AGL: heightMeters interpreted above ground at observer
     * - mode ASL: heightMeters is above mean sea level; AGL derived by subtracting ground elevation
     * If observer ground cannot be sampled from the grid (outside extent or NaN), this method uses
     * TileCache to fetch ground elevation when mode == ASL. If cache is null or fails, falls back to 0 AGL.
     */
    public static ElevationGrid applyEarthCurvatureDrop(ElevationGrid grid,
                                                       double observerLatDeg,
                                                       double observerLonDeg,
                                                       ObserverHeightMode mode,
                                                       Double heightMeters,
                                                       TileCache cache) throws IOException {
        return CurvatureModel.applyObserverCurvature(grid, observerLatDeg, observerLonDeg, mode, heightMeters, cache);
    }

    /**
     * Crops the center 1x1 tile from a larger ElevationGrid (built by getElevationGridAround with radius > 0).
     * Returns a new ElevationGrid of size tileSize x tileSize at the same zoom with the same center tile indices.
     */
    public static ElevationGrid cropCenterTile(ElevationGrid grid) {
        int radiusTiles = (grid.tilesWide - 1) / 2;
        return cropTileAt(grid, 0, 0, radiusTiles);
    }

    /**
     * Crop a specific tile offset (dx, dy) from the grid where (0,0) is the center tile.
     * dx positive is east (increasing x), dy positive is south (increasing y).
     */
    public static ElevationGrid cropTileAt(ElevationGrid grid, int dxTiles, int dyTiles) {
        int radiusTiles = (grid.tilesWide - 1) / 2;
        return cropTileAt(grid, dxTiles, dyTiles, radiusTiles);
    }

    private static ElevationGrid cropTileAt(ElevationGrid grid, int dxTiles, int dyTiles, int radiusTiles) {
        int tileSize = grid.tileSize;
        int startX = (dxTiles + radiusTiles) * tileSize;
        int startY = (dyTiles + radiusTiles) * tileSize;
        if (startX < 0 || startY < 0 || startX + tileSize > grid.width || startY + tileSize > grid.height) {
            // Out of bounds; return empty NaN tile
            float[][] blank = new float[tileSize][tileSize];
            for (int y = 0; y < tileSize; y++) java.util.Arrays.fill(blank[y], Float.NaN);
            return new ElevationGrid(blank, tileSize, 1, 1, grid.zoom, grid.centerTileX, grid.centerTileY);
        }
        float[][] out = new float[tileSize][tileSize];
        for (int y = 0; y < tileSize; y++) {
            System.arraycopy(grid.data[startY + y], startX, out[y], 0, tileSize);
        }
        return new ElevationGrid(out, tileSize, 1, 1, grid.zoom, grid.centerTileX, grid.centerTileY);
    }

    // ---------- Basemap stitching (Carto / Thunderforest) and LOS overlay ----------

    /**
     * Builds a stitched Carto basemap image that matches the pixel dimensions of the given elevation grid.
     */
    public static BufferedImage buildCartoBaseMapImage(ElevationGrid grid, CartoTileCache cartoCache) throws IOException {
        return buildCartoBaseMapImage(grid, cartoCache, current -> { });
    }

    public static BufferedImage buildCartoBaseMapImage(ElevationGrid grid, CartoTileCache cartoCache,
                                                       IntConsumer progress) throws IOException {
        if (cartoCache == null) throw new IllegalArgumentException("CartoTileCache must not be null");
        int tilesSpan = grid.tilesWide; // assumes square grid tilesWide == tilesHigh
        int tileSize = grid.tileSize;
        int totalW = tilesSpan * tileSize;
        int totalH = tilesSpan * tileSize;
        BufferedImage base = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = base.createGraphics();
        try {
            int n = 1 << grid.zoom;
            int radiusTiles = (tilesSpan - 1) / 2;
            int maxYIndex = n - 1;
            for (int dy = -radiusTiles; dy <= radiusTiles; dy++) {
                for (int dx = -radiusTiles; dx <= radiusTiles; dx++) {
                    int tx = wrapInt(grid.centerTileX + dx, n);
                    int ty = grid.centerTileY + dy;
                    if (ty < 0 || ty > maxYIndex) {
                        progress.accept((dy + radiusTiles) * tilesSpan + dx + radiusTiles + 1);
                        continue;
                    }
                    File tf = cartoCache.getTile(grid.zoom, tx, ty);
                    BufferedImage img = ImageIO.read(tf);
                    if (img == null) {
                        progress.accept((dy + radiusTiles) * tilesSpan + dx + radiusTiles + 1);
                        continue;
                    }
                    int destX0 = (dx + radiusTiles) * tileSize;
                    int destY0 = (dy + radiusTiles) * tileSize;
                    g.drawImage(img, destX0, destY0, tileSize, tileSize, null);
                    progress.accept((dy + radiusTiles) * tilesSpan + dx + radiusTiles + 1);
                }
            }
        } finally {
            g.dispose();
        }
        return base;
    }

    /**
     * Overlays a semi-transparent color wherever the LOS-masked grid indicates visibility (non-NaN),
     * leaving occluded pixels (NaN) unchanged so the basemap shows through.
     */
    public static BufferedImage overlayLosOnBase(ElevationGrid losMaskedGrid, BufferedImage base, Color overlayColor) {
        return overlayLosOnBase(losMaskedGrid, base, overlayColor, current -> { });
    }

    public static BufferedImage overlayLosOnBase(ElevationGrid losMaskedGrid, BufferedImage base,
                                                 Color overlayColor, IntConsumer progress) {
        if (base.getWidth() != losMaskedGrid.width || base.getHeight() != losMaskedGrid.height) {
            throw new IllegalArgumentException("Base image size must match grid dimensions");
        }
        BufferedImage out = new BufferedImage(base.getWidth(), base.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int width = base.getWidth();
        int alpha = overlayColor.getAlpha();
        int inverseAlpha = 255 - alpha;
        int overlayRed = overlayColor.getRed();
        int overlayGreen = overlayColor.getGreen();
        int overlayBlue = overlayColor.getBlue();
        int[] pixels = new int[width];
        for (int y = 0; y < losMaskedGrid.height; y++) {
            base.getRGB(0, y, width, 1, pixels, 0, width);
            float[] row = losMaskedGrid.data[y];
            for (int x = 0; x < width; x++) {
                if (!Double.isNaN(row[x])) {
                    int basePixel = pixels[x];
                    int baseAlpha = (basePixel >>> 24) & 0xff;
                    int outAlpha = alpha + (baseAlpha * inverseAlpha + 127) / 255;
                    int red = (overlayRed * alpha + ((basePixel >>> 16) & 0xff) * inverseAlpha + 127) / 255;
                    int green = (overlayGreen * alpha + ((basePixel >>> 8) & 0xff) * inverseAlpha + 127) / 255;
                    int blue = (overlayBlue * alpha + (basePixel & 0xff) * inverseAlpha + 127) / 255;
                    pixels[x] = (outAlpha << 24) | (red << 16) | (green << 8) | blue;
                }
            }
            out.setRGB(0, y, width, 1, pixels, 0, width);
            progress.accept(y + 1);
        }
        return out;
    }

    /**
     * Convenience method to save a Carto basemap with LOS mask overlay in semi-transparent red.
     */
    public static void saveLosOverlayOnCarto(ElevationGrid losMaskedGrid, CartoTileCache cartoCache, File outFile) throws IOException {
        saveLosOverlayOnCarto(losMaskedGrid, cartoCache, outFile, current -> { });
    }

    public static void saveLosOverlayOnCarto(ElevationGrid losMaskedGrid, CartoTileCache cartoCache,
                                             File outFile, IntConsumer tileProgress) throws IOException {
        saveLosOverlayOnCarto(losMaskedGrid, cartoCache, outFile, tileProgress, current -> { });
    }

    public static void saveLosOverlayOnCarto(ElevationGrid losMaskedGrid, CartoTileCache cartoCache,
                                             File outFile, IntConsumer tileProgress,
                                             IntConsumer renderProgress) throws IOException {
        BufferedImage base = buildCartoBaseMapImage(losMaskedGrid, cartoCache, tileProgress);
        // Semi-transparent red (alpha ~ 128)
        Color redOverlay = new Color(255, 0, 0, 96);
        BufferedImage composited = overlayLosOnBase(losMaskedGrid, base, redOverlay, renderProgress);
        writeImage(composited, outFile);
    }

    /**
     * Builds a stitched Thunderforest basemap image that matches the pixel dimensions of the given elevation grid.
     */
    public static BufferedImage buildThunderforestBaseMapImage(ElevationGrid grid, ThunderforestTileCache tfCache) throws IOException {
        return buildThunderforestBaseMapImage(grid, tfCache, current -> { });
    }

    public static BufferedImage buildThunderforestBaseMapImage(ElevationGrid grid, ThunderforestTileCache tfCache,
                                                               IntConsumer progress) throws IOException {
        if (tfCache == null) throw new IllegalArgumentException("ThunderforestTileCache must not be null");
        int tilesSpan = grid.tilesWide; // assumes square grid tilesWide == tilesHigh
        int tileSize = grid.tileSize;
        int totalW = tilesSpan * tileSize;
        int totalH = tilesSpan * tileSize;
        BufferedImage base = new BufferedImage(totalW, totalH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = base.createGraphics();
        try {
            int n = 1 << grid.zoom;
            int radiusTiles = (tilesSpan - 1) / 2;
            int maxYIndex = n - 1;
            for (int dy = -radiusTiles; dy <= radiusTiles; dy++) {
                for (int dx = -radiusTiles; dx <= radiusTiles; dx++) {
                    int tx = wrapInt(grid.centerTileX + dx, n);
                    int ty = grid.centerTileY + dy;
                    if (ty < 0 || ty > maxYIndex) {
                        progress.accept((dy + radiusTiles) * tilesSpan + dx + radiusTiles + 1);
                        continue;
                    }
                    File tf = tfCache.getTile(grid.zoom, tx, ty);
                    BufferedImage img = ImageIO.read(tf);
                    if (img == null) {
                        progress.accept((dy + radiusTiles) * tilesSpan + dx + radiusTiles + 1);
                        continue;
                    }
                    int destX0 = (dx + radiusTiles) * tileSize;
                    int destY0 = (dy + radiusTiles) * tileSize;
                    g.drawImage(img, destX0, destY0, tileSize, tileSize, null);
                    progress.accept((dy + radiusTiles) * tilesSpan + dx + radiusTiles + 1);
                }
            }
        } finally {
            g.dispose();
        }
        return base;
    }

    /**
     * Convenience method to save a Thunderforest basemap with LOS mask overlay in semi-transparent red.
     */
    public static void saveLosOverlayOnThunderforest(ElevationGrid losMaskedGrid, ThunderforestTileCache tfCache, File outFile) throws IOException {
        saveLosOverlayOnThunderforest(losMaskedGrid, tfCache, outFile, current -> { });
    }

    public static void saveLosOverlayOnThunderforest(ElevationGrid losMaskedGrid, ThunderforestTileCache tfCache,
                                                     File outFile, IntConsumer tileProgress) throws IOException {
        saveLosOverlayOnThunderforest(losMaskedGrid, tfCache, outFile, tileProgress, current -> { });
    }

    public static void saveLosOverlayOnThunderforest(ElevationGrid losMaskedGrid, ThunderforestTileCache tfCache,
                                                     File outFile, IntConsumer tileProgress,
                                                     IntConsumer renderProgress) throws IOException {
        BufferedImage base = buildThunderforestBaseMapImage(losMaskedGrid, tfCache, tileProgress);
        // Semi-transparent red (alpha ~ 128)
        Color redOverlay = new Color(255, 0, 0, 96);
        BufferedImage composited = overlayLosOnBase(losMaskedGrid, base, redOverlay, renderProgress);
        writeImage(composited, outFile);
    }

    /**
     * Overload that also draws a blue observer marker (circle + crosshair) at the provided lat/lon.
     */
    public static void saveLosOverlayOnThunderforest(ElevationGrid losMaskedGrid,
                                                     ThunderforestTileCache tfCache,
                                                     File outFile,
                                                     double observerLatDeg,
                                                     double observerLonDeg) throws IOException {
        saveLosOverlayOnThunderforest(losMaskedGrid, tfCache, outFile, observerLatDeg, observerLonDeg,
                current -> { });
    }

    public static void saveLosOverlayOnThunderforest(ElevationGrid losMaskedGrid,
                                                     ThunderforestTileCache tfCache,
                                                     File outFile,
                                                     double observerLatDeg,
                                                     double observerLonDeg,
                                                     IntConsumer tileProgress) throws IOException {
        saveLosOverlayOnThunderforest(losMaskedGrid, tfCache, outFile, observerLatDeg, observerLonDeg,
                tileProgress, current -> { });
    }

    public static void saveLosOverlayOnThunderforest(ElevationGrid losMaskedGrid,
                                                     ThunderforestTileCache tfCache,
                                                     File outFile,
                                                     double observerLatDeg,
                                                     double observerLonDeg,
                                                     IntConsumer tileProgress,
                                                     IntConsumer renderProgress) throws IOException {
        BufferedImage base = buildThunderforestBaseMapImage(losMaskedGrid, tfCache, tileProgress);
        Color redOverlay = new Color(255, 0, 0, 96);
        BufferedImage composited = overlayLosOnBase(losMaskedGrid, base, redOverlay, renderProgress);

        // Draw observer marker
        Graphics2D g = composited.createGraphics();
        try {
            PixelPos px = latLonToGridPixel(losMaskedGrid, observerLatDeg, observerLonDeg);
            int x = (int) Math.round(px.x);
            int y = (int) Math.round(px.y);
            // Only draw if within image bounds
            if (x >= 0 && y >= 0 && x < composited.getWidth() && y < composited.getHeight()) {
                g.setColor(new Color(0, 122, 255)); // iOS blue-like
                g.setStroke(new BasicStroke(2f));
                int r = 8;
                g.drawOval(x - r, y - r, 2 * r, 2 * r);
                int arm = 12;
                g.drawLine(x - arm, y, x + arm, y);
                g.drawLine(x, y - arm, x, y + arm);
            }
        } finally {
            g.dispose();
        }

        writeImage(composited, outFile);
    }
}
