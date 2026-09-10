package com.sidpatchy.Tile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Simple cache for Thunderforest basemap raster tiles.
 * Uses the Landscape style and stores PNG tiles locally for up to 365 days.
 */
public class ThunderforestTileCache {
    private final Path cacheDir;
    private final long maxAgeSeconds = 365L * 24 * 60 * 60; // 365 days
    private final String apiKey;

    public static String resolveApiKey(String explicitKey) {
        if (explicitKey != null && !explicitKey.isBlank()) return explicitKey.trim();
        String value = System.getProperty("thunderforest.api.key");
        if (value != null && !value.isBlank()) return value.trim();
        value = System.getProperty("tfKey");
        if (value != null && !value.isBlank()) return value.trim();
        value = System.getenv("THUNDERFOREST_API_KEY");
        if (value != null && !value.isBlank()) return value.trim();
        value = System.getenv("TF_KEY");
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    /**
     * @param cachePath directory where tiles are cached
     * @param apiKey Thunderforest API key
     */
    public ThunderforestTileCache(String cachePath, String apiKey) {
        this.cacheDir = Paths.get(cachePath);
        this.apiKey = resolveApiKey(apiKey);
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create Thunderforest cache directory", e);
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

        // Download Thunderforest basemap tile (landscape)
        String url = String.format(
                "https://tile.thunderforest.com/landscape/%d/%d/%d.png?apikey=%s",
                zoom, x, y, apiKey
        );

        try (InputStream in = new URL(url).openStream()) {
            Files.copy(in, tilePath, StandardCopyOption.REPLACE_EXISTING);
        }

        return tileFile;
    }
}
