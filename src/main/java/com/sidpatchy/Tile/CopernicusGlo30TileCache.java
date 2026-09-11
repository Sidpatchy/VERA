package com.sidpatchy.Tile;

import javax.imageio.ImageIO;
import com.twelvemonkeys.imageio.plugins.tiff.TIFFImageReaderSpi;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Reads the public Copernicus GLO-30 one-degree COGs and provides elevation tiles.
 */
final class CopernicusGlo30TileCache {
    private static final String BUCKET = "https://copernicus-dem-30m.s3.amazonaws.com/";
    private static final String TILE_LIST_URL = BUCKET + "tileList.txt";
    private final Path cacheDir;
    private final Path tileListPath;
    private final Executor downloadExecutor;
    private final Map<Path, CompletableFuture<Void>> activeDownloads = new ConcurrentHashMap<>();
    private final Map<String, Raster> sourceImages = new LinkedHashMap<>(4, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Raster> eldest) {
            return size() > 2;
        }
    };
    private volatile Set<String> availableStems;
    private Raster activeRaster;
    private int activeSouth;
    private int activeWest;

    CopernicusGlo30TileCache(Path cacheDir) {
        this(cacheDir, Runnable::run);
    }

    CopernicusGlo30TileCache(Path cacheDir, Executor downloadExecutor) {
        this.cacheDir = cacheDir;
        this.tileListPath = cacheDir.resolve("cog").resolve("tileList.txt");
        this.downloadExecutor = downloadExecutor;
    }

    float[][] getElevationData(int zoom, int x, int y) throws IOException {
        int n = 1 << zoom;
        int wrappedX = ((x % n) + n) % n;
        float[][] output = new float[256][256];
        for (int py = 0; py < 256; py++) {
            double globalY = (y + (py + 0.5) / 256.0) / n;
            double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * globalY))));
            for (int px = 0; px < 256; px++) {
                double lon = (wrappedX + (px + 0.5) / 256.0) / n * 360.0 - 180.0;
                output[py][px] = (float) sample(lat, lon);
            }
        }
        return output;
    }

    /** Ensures the one-degree COGs touched by a tile are present without decoding the tile. */
    void prefetchTile(int zoom, int x, int y) throws IOException {
        int n = 1 << zoom;
        int wrappedX = Math.floorMod(x, n);
        double west = (double) wrappedX / n * 360.0 - 180.0;
        double east = (double) (wrappedX + 1) / n * 360.0 - 180.0;
        double north = latitudeAtTileEdge(y, n);
        double south = latitudeAtTileEdge(y + 1, n);
        int westCell = Math.max(-180, Math.min(179, (int) Math.floor(west)));
        int eastCell = Math.max(-180, Math.min(179, (int) Math.floor(Math.nextDown(east))));
        int southCell = Math.max(-90, Math.min(89, (int) Math.floor(south)));
        int northCell = Math.max(-90, Math.min(89, (int) Math.floor(north)));
        List<CompletableFuture<Void>> downloads = new ArrayList<>();
        for (int cellSouth = southCell; cellSouth <= northCell; cellSouth++) {
            for (int cellWest = westCell; cellWest <= eastCell; cellWest++) {
                String stem = cogStem(cellSouth, cellWest);
                if (availableStems().contains(stem)) {
                    downloads.add(ensureDownloadedAsync(cogPath(cellSouth, cellWest), stem));
                }
            }
        }
        try {
            CompletableFuture.allOf(downloads.toArray(CompletableFuture[]::new)).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof IOException ioException) throw ioException;
            throw e;
        }
    }

    /** Returns the one-degree COG cells touched by a web-mercator tile. */
    String prefetchCoverageKey(int zoom, int x, int y) {
        return coverageKey(zoom, x, y, false);
    }

    /** Returns only published COG cells touched by a web-mercator tile. */
    String prefetchAvailableCoverageKey(int zoom, int x, int y) {
        return coverageKey(zoom, x, y, true);
    }

    private String coverageKey(int zoom, int x, int y, boolean publishedOnly) {
        int n = 1 << zoom;
        int wrappedX = Math.floorMod(x, n);
        double west = (double) wrappedX / n * 360.0 - 180.0;
        double east = (double) (wrappedX + 1) / n * 360.0 - 180.0;
        double north = latitudeAtTileEdge(y, n);
        double south = latitudeAtTileEdge(y + 1, n);
        int westCell = Math.max(-180, Math.min(179, (int) Math.floor(west)));
        int eastCell = Math.max(-180, Math.min(179, (int) Math.floor(Math.nextDown(east))));
        int southCell = Math.max(-90, Math.min(89, (int) Math.floor(south)));
        int northCell = Math.max(-90, Math.min(89, (int) Math.floor(north)));
        Set<String> cells = new TreeSet<>();
        Set<String> published = publishedOnly ? availableStems() : Set.of();
        for (int cellSouth = southCell; cellSouth <= northCell; cellSouth++) {
            for (int cellWest = westCell; cellWest <= eastCell; cellWest++) {
                if (!publishedOnly || published.contains(cogStem(cellSouth, cellWest))) {
                    cells.add(cellSouth + ":" + cellWest);
                }
            }
        }
        return String.join(",", cells);
    }

    static boolean isTileListEntry(String line) {
        return line != null && line.startsWith("Copernicus_DSM_COG_10_") && line.endsWith("_DEM");
    }

    private Set<String> availableStems() {
        Set<String> result = availableStems;
        if (result != null) return result;
        synchronized (this) {
            result = availableStems;
            if (result == null) {
                try {
                    Files.createDirectories(tileListPath.getParent());
                    if (!Files.exists(tileListPath) || Files.size(tileListPath) == 0) {
                        downloadTileList();
                    }
                    Set<String> loaded = new HashSet<>();
                    for (String line : Files.readAllLines(tileListPath)) {
                        String trimmed = line.trim();
                        if (isTileListEntry(trimmed)) loaded.add(trimmed);
                    }
                    if (loaded.isEmpty()) {
                        throw new IOException("Copernicus tile list is empty: " + tileListPath);
                    }
                    result = Set.copyOf(loaded);
                    availableStems = result;
                } catch (IOException e) {
                    throw new IllegalStateException("Unable to load Copernicus tile index: " + TILE_LIST_URL, e);
                }
            }
            return result;
        }
    }

    private void downloadTileList() throws IOException {
        Path part = Files.createTempFile(tileListPath.getParent(), "tileList", ".part");
        HttpURLConnection connection = (HttpURLConnection) URI.create(TILE_LIST_URL).toURL().openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(120_000);
        connection.setInstanceFollowRedirects(true);
        try {
            if (connection.getResponseCode() / 100 != 2) {
                throw new IOException("Unable to download Copernicus tile index: HTTP "
                        + connection.getResponseCode() + " for " + connection.getURL());
            }
            try (InputStream in = connection.getInputStream()) {
                Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.size(part) == 0) throw new IOException("Downloaded empty Copernicus tile index");
            Files.move(part, tileListPath, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            connection.disconnect();
            Files.deleteIfExists(part);
        }
    }

    private static double latitudeAtTileEdge(int y, int tileCount) {
        double globalY = (double) y / tileCount;
        return Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * globalY))));
    }


    private double sample(double lat, double lon) throws IOException {
        if (lat <= -90.0 || lat >= 90.0) return 0.0;
        int south = (int) Math.floor(lat);
        int west = (int) Math.floor(lon);
        Raster raster;
        if (activeRaster != null && activeSouth == south && activeWest == west) {
            raster = activeRaster;
        } else {
            raster = loadRaster(south, west);
            activeRaster = raster;
            activeSouth = south;
            activeWest = west;
        }
        int px = Math.max(0, Math.min(raster.getWidth() - 1,
                (int) Math.floor((lon - west) * raster.getWidth())));
        int py = Math.max(0, Math.min(raster.getHeight() - 1,
                (int) Math.floor((south + 1.0 - lat) * raster.getHeight())));
        return raster.getSampleDouble(px, py, 0);
    }

    private Raster loadRaster(int south, int west) throws IOException {
        String stem = cogStem(south, west);
        Path local = cogPath(south, west);
        Files.createDirectories(local.getParent());
        Raster raster = sourceImages.get(local.toString());
        if (raster == null) {
            if (Files.exists(local)) {
                try {
                    raster = readTiffRaster(local);
                    validateRaster(local, raster);
                } catch (IOException e) {
                    Files.deleteIfExists(local);
                    raster = null;
                }
            }
        }
        if (raster == null) {
            ensureDownloaded(local, stem);
            raster = readTiffRaster(local);
            validateRaster(local, raster);
        }
        sourceImages.put(local.toString(), raster);
        return raster;
    }

    private Path cogPath(int south, int west) {
        return cacheDir.resolve("cog").resolve(cogStem(south, west) + ".tif");
    }

    private static String cogStem(int south, int west) {
        String northing = (south >= 0 ? "N" : "S") + String.format("%02d_00", Math.abs(south));
        String easting = (west >= 0 ? "E" : "W") + String.format("%03d_00", Math.abs(west));
        return "Copernicus_DSM_COG_10_" + northing + "_" + easting + "_DEM";
    }

    private void ensureDownloaded(Path local, String stem) throws IOException {
        try {
            ensureDownloadedAsync(local, stem).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof IOException ioException) throw ioException;
            throw e;
        }
    }

    private CompletableFuture<Void> ensureDownloadedAsync(Path local, String stem) {
        try {
            if (Files.exists(local) && Files.size(local) > 0) {
                return CompletableFuture.completedFuture(null);
            }
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
        CompletableFuture<Void> download = new CompletableFuture<>();
        CompletableFuture<Void> existing = activeDownloads.putIfAbsent(local, download);
        if (existing == null) {
            CompletableFuture.runAsync(() -> {
                try {
                    downloadFile(local, stem);
                    download.complete(null);
                } catch (Exception e) {
                    download.completeExceptionally(e);
                }
            }, downloadExecutor).whenComplete((ignored, error) -> activeDownloads.remove(local, download));
            existing = download;
        }
        return existing;
    }

    private void downloadFile(Path local, String stem) throws IOException {
        Files.createDirectories(local.getParent());
        Path part = Files.createTempFile(local.getParent(), stem, ".part");
        HttpURLConnection connection = (HttpURLConnection) URI.create(
                BUCKET + stem + "/" + stem + ".tif").toURL().openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(120_000);
        connection.setInstanceFollowRedirects(true);
        try {
            if (connection.getResponseCode() / 100 != 2) {
                throw new IOException("Unable to download Copernicus COG: HTTP "
                        + connection.getResponseCode() + " for " + connection.getURL());
            }
            try (InputStream in = new BufferedInputStream(connection.getInputStream(), 1024 * 1024)) {
                Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
                if (Files.size(part) == 0) {
                    throw new IOException("Downloaded empty Copernicus COG: " + local);
                }
                Raster raster = readTiffRaster(part);
                validateRaster(part, raster);
                try {
                    Files.move(part, local, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(part, local, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            connection.disconnect();
            Files.deleteIfExists(part);
        }
    }

    private static Raster readTiffRaster(Path file) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) throw new IOException("Unable to open TIFF: " + file);
            ImageReader reader = new TIFFImageReaderSpi().createReaderInstance();
            try {
                reader.setInput(input);
                return reader.readRaster(0, null);
            } finally {
                reader.dispose();
            }
        }
    }

    private static void validateRaster(Path file, Raster raster) throws IOException {
        if (raster == null || raster.getWidth() <= 0 || raster.getHeight() <= 0
                || raster.getNumBands() == 0) {
            throw new IOException("Copernicus COG has no raster data: " + file);
        }
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        int[][] points = {
                {0, 0},
                {raster.getWidth() / 2, raster.getHeight() / 2},
                {raster.getWidth() - 1, raster.getHeight() - 1}
        };
        for (int[] point : points) {
            double sample = raster.getSampleDouble(point[0], point[1], 0);
            if (Double.isFinite(sample)) {
                minimum = Math.min(minimum, sample);
                maximum = Math.max(maximum, sample);
            }
        }
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum)) {
            throw new IOException("Copernicus COG contains no finite elevation samples: " + file);
        }
    }
}