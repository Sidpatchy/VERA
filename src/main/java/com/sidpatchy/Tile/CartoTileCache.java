package com.sidpatchy.Tile;

import java.io.*;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Simple cache for Carto basemap raster tiles.
 * Uses the Carto light_all style and stores PNG tiles locally for up to 365 days.
 * Supports optional Carto API key to prevent "API KEY REQUIRED" watermarks.
 */
public class CartoTileCache {
    private final Path cacheDir;
    private final long maxAgeSeconds = 365L * 24 * 60 * 60; // 365 days
    private final String apiKey;

    public CartoTileCache(String cachePath) {
        this(cachePath, null);
    }

    public CartoTileCache(String cachePath, String apiKey) {
        this.cacheDir = Paths.get(cachePath);
        this.apiKey = resolveApiKey(apiKey);
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create Carto cache directory", e);
        }
    }

    /**
     * Resolves the Carto API key using explicit parameter, JVM system property, or environment variable.
     */
    public static String resolveApiKey(String explicitKey) {
        if (explicitKey != null && !explicitKey.isBlank()) {
            return explicitKey.trim();
        }
        String sysProp = System.getProperty("carto.api.key");
        if (sysProp != null && !sysProp.isBlank()) {
            return sysProp.trim();
        }
        sysProp = System.getProperty("cartoKey");
        if (sysProp != null && !sysProp.isBlank()) {
            return sysProp.trim();
        }
        String env = System.getenv("CARTO_API_KEY");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        env = System.getenv("CARTO_KEY");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return null;
    }

    public String getApiKey() {
        return apiKey;
    }

    /**
     * Constructs the remote URL for a Carto tile, appending the API key if configured.
     */
    public String buildTileUrl(int zoom, int x, int y) {
        if (apiKey != null && !apiKey.isBlank()) {
            return String.format(
                    "https://basemaps.cartocdn.com/light_all/%d/%d/%d.png?api_key=%s",
                    zoom, x, y, URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
            );
        } else {
            return String.format(
                    "https://basemaps.cartocdn.com/light_all/%d/%d/%d.png",
                    zoom, x, y
            );
        }
    }

    public File getTile(int zoom, int x, int y) throws IOException {
        String filename = String.format("%d_%d_%d.png", zoom, x, y);
        Path tilePath = cacheDir.resolve(filename);
        File tileFile = tilePath.toFile();

        // Check if file exists and is fresh
        if (tileFile.exists()) {
            long fileAge = ChronoUnit.SECONDS.between(
                    Instant.ofEpochMilli(tileFile.lastModified()),
                    Instant.now()
            );
            if (fileAge < maxAgeSeconds) {
                return tileFile;
            }
        }

        // Download Carto basemap tile (light_all)
        String url = buildTileUrl(zoom, x, y);

        try (InputStream in = new URL(url).openStream()) {
            Files.copy(in, tilePath, StandardCopyOption.REPLACE_EXISTING);
        }

        return tileFile;
    }
}
