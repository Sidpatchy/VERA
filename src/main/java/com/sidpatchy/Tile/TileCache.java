package com.sidpatchy.Tile;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.smile.SmileFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.Executor;

public class TileCache {
    private final Path cacheDir;
    private final ElevationProvider provider;
    private final CopernicusGlo30TileCache copernicus;
    private static final int SMILE_SHARD_DEGREES = 1;
    private static final int DECODED_SHARD_CACHE_SIZE = 8;
    private final ObjectMapper smileMapper = new ObjectMapper(new SmileFactory());
    private final Map<Path, Map<String, float[][]>> pendingDecoded = new HashMap<>();
    private final Map<Path, Map<String, float[][]>> decodedShardMemory =
            new LinkedHashMap<>(DECODED_SHARD_CACHE_SIZE, 0.75f, true) {
                @Override protected boolean removeEldestEntry(
                        Map.Entry<Path, Map<String, float[][]>> eldest) {
                    return size() > DECODED_SHARD_CACHE_SIZE;
                }
            };
    private int batchDepth;
    private final Map<String, float[][]> decodedMemory = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, float[][]> eldest) { return size() > 64; }
    };
    private final long maxAgeSeconds = 365 * 24 * 60 * 60; // 365 days

    public TileCache(String cachePath) {
        this(cachePath, ElevationProvider.TERRARIUM);
    }

    public TileCache(String cachePath, ElevationProvider provider) {
        this(cachePath, provider, Runnable::run);
    }

    public TileCache(String cachePath, ElevationProvider provider, Executor downloadExecutor) {
        this.cacheDir = Paths.get(cachePath);
        this.provider = provider == null ? ElevationProvider.TERRARIUM : provider;
        this.copernicus = this.provider == ElevationProvider.COPERNICUS_GLO30
                ? new CopernicusGlo30TileCache(cacheDir, downloadExecutor) : null;
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create cache directory", e);
        }
    }

    public File getTile(int zoom, int x, int y) throws IOException {
        if (provider == ElevationProvider.COPERNICUS_GLO30) {
            throw new UnsupportedOperationException(
                    "Copernicus data is COG-backed; use getElevationData() instead of getTile()");
        }
        String filename = String.format("%d_%d_%d.png", zoom, x, y);
        Path tilePath = cacheDir.resolve("terrarium").resolve(filename);
        File tileFile = tilePath.toFile();
        Files.createDirectories(tilePath.getParent());

        // Check if file exists and is fresh
        if (tileFile.exists()) {
            long fileAge = ChronoUnit.SECONDS.between(
                    Instant.ofEpochMilli(tileFile.lastModified()),
                    Instant.now()
            );

            if (fileAge < maxAgeSeconds) {
                try {
                    if (ImageIO.read(tileFile) != null) {
                        return tileFile;
                    }
                } catch (IOException ignored) {
                    // The cache entry is incomplete or otherwise invalid; redownload it below.
                }
                Files.deleteIfExists(tilePath);
            }
        }

        // Download tile
        String url = String.format(
                "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/%d/%d/%d.png",
                zoom, x, y
        );

        Path temporaryPath = Files.createTempFile(cacheDir, filename, ".part");
        try {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, temporaryPath, StandardCopyOption.REPLACE_EXISTING);
            }

            // Do not publish truncated or non-image responses to the cache.
            try {
                if (ImageIO.read(temporaryPath.toFile()) == null) {
                    throw new IOException("Downloaded tile is not a readable PNG: " + url);
                }
            } catch (IOException e) {
                throw new IOException("Downloaded tile is corrupt: " + url, e);
            }

            try {
                Files.move(temporaryPath, tilePath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporaryPath, tilePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporaryPath);
        }

        return tileFile;
    }

    public String prefetchCoverageKey(int zoom, int x, int y) {
        if (provider != ElevationProvider.COPERNICUS_GLO30) return zoom + ":" + x + ":" + y;
        return copernicus.prefetchCoverageKey(zoom, x, y);
    }

    public String prefetchAvailableCoverageKey(int zoom, int x, int y) {
        if (provider != ElevationProvider.COPERNICUS_GLO30) return zoom + ":" + x + ":" + y;
        return copernicus.prefetchAvailableCoverageKey(zoom, x, y);
    }

    /** Ensures the source data needed for a tile is present in the provider's native cache. */
    public void prefetchTile(int zoom, int x, int y) throws IOException {
        if (provider == ElevationProvider.COPERNICUS_GLO30) {
            copernicus.prefetchTile(zoom, x, y);
        } else {
            getTile(zoom, x, y);
        }
    }

    /** Returns decoded elevation values using a bounded memory cache. */
    public synchronized float[][] getElevationData(int zoom, int x, int y) throws IOException {
        String key = provider.name() + "_" + zoom + "_" + x + "_" + y;
        float[][] memory = decodedMemory.get(key);
        if (memory != null) return memory;
        Path file = smileShard(zoom, x, y);
        Map<String, float[][]> pending = pendingDecoded.get(file);
        if (pending != null) {
            float[][] decoded = pending.get(key);
            if (decoded != null) {
                decodedMemory.put(key, decoded);
                return decoded;
            }
        }
        try {
            float[][] decoded = readDecoded(file).get(key);
            if (decoded != null) {
                decodedMemory.put(key, decoded);
                return decoded;
            }
        } catch (IOException ignored) {
            decodedShardMemory.remove(file);
            Files.deleteIfExists(file);
        }
        if (provider == ElevationProvider.COPERNICUS_GLO30) {
            float[][] decoded = copernicus.getElevationData(zoom, x, y);
            storeDecoded(file, key, decoded);
            decodedMemory.put(key, decoded);
            return decoded;
        }
        BufferedImage image = ImageIO.read(getTile(zoom, x, y));
        if (image == null) throw new IOException("Failed to decode elevation tile");
        float[][] decoded = new float[image.getHeight()][image.getWidth()];
        for (int row = 0; row < image.getHeight(); row++) {
            for (int col = 0; col < image.getWidth(); col++) {
                decoded[row][col] = (float) ElevationDecoder.getElevation(image, col, row);
            }
        }
        storeDecoded(file, key, decoded);
        decodedMemory.put(key, decoded);
        return decoded;
    }

    /** Defers Smile shard rewrites until {@link #endBatch()} for bulk tile conversion. */
    public synchronized void beginBatch() {
        batchDepth++;
    }

    /** Flushes all deferred geographic Smile shards when the outer batch ends. */
    public synchronized void endBatch() throws IOException {
        if (batchDepth == 0) return;
        if (--batchDepth == 0) flushPendingDecoded();
    }

    private Path smileShard(int zoom, int x, int y) {
        int n = 1 << zoom;
        int wrappedX = Math.floorMod(x, n);
        double globalX = (wrappedX + 0.5) / n;
        double globalY = (y + 0.5) / n;
        int longitude = (int) Math.floor(globalX * 360.0 - 180.0);
        int latitude = (int) Math.floor(Math.toDegrees(
                Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * globalY)))));
        int latitudeCell = Math.floorDiv(latitude, SMILE_SHARD_DEGREES) * SMILE_SHARD_DEGREES;
        int longitudeCell = Math.floorDiv(longitude, SMILE_SHARD_DEGREES) * SMILE_SHARD_DEGREES;
        return cacheDir.resolve("decoded").resolve(String.format(
                "%s-lat%+d-lon%+d.smile", provider.name(), latitudeCell, longitudeCell));
    }

    private Map<String, float[][]> readDecoded(Path file) throws IOException {
        if (!Files.exists(file)) return new LinkedHashMap<>();
        Map<String, float[][]> cached = decodedShardMemory.get(file);
        if (cached != null) return cached;
        Map<String, float[][]> decoded = smileMapper.readValue(
                file.toFile(), new TypeReference<Map<String, float[][]>>() { });
        decodedShardMemory.put(file, decoded);
        return decoded;
    }

    private void storeDecoded(Path file, String key, float[][] decoded) throws IOException {
        if (batchDepth > 0) {
            pendingDecoded.computeIfAbsent(file, ignored -> new LinkedHashMap<>()).put(key, decoded);
        } else {
            writeDecoded(file, key, decoded);
        }
    }

    private void flushPendingDecoded() throws IOException {
        for (Map.Entry<Path, Map<String, float[][]>> entry : pendingDecoded.entrySet()) {
            Path file = entry.getKey();
            Map<String, float[][]> values = readDecoded(file);
            values.putAll(entry.getValue());
            writeDecoded(file, values);
        }
        pendingDecoded.clear();
    }

    private void writeDecoded(Path file, String key, float[][] decoded) throws IOException {
        Files.createDirectories(file.getParent());
        Map<String, float[][]> values = readDecoded(file);
        values.put(key, decoded);
        writeDecoded(file, values);
    }

    private void writeDecoded(Path file, Map<String, float[][]> values) throws IOException {
        Files.createDirectories(file.getParent());
        Path part = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".part");
        try {
            smileMapper.writeValue(part.toFile(), values);
            try {
                Files.move(part, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
            }
            decodedShardMemory.put(file, values);
        } finally {
            Files.deleteIfExists(part);
        }
    }
}
