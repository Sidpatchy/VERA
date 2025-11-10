package com.sidpatchy.Tile;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Simple cache for Carto basemap raster tiles.
 * Uses the Carto light_all style and stores PNG tiles locally for up to 365 days.
 */
public class CartoTileCache {
    private final Path cacheDir;
    private final long maxAgeSeconds = 365L * 24 * 60 * 60; // 365 days

    public CartoTileCache(String cachePath) {
        this.cacheDir = Paths.get(cachePath);
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create Carto cache directory", e);
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
        String url = String.format(
                "https://basemaps.cartocdn.com/light_all/%d/%d/%d.png",
                zoom, x, y
        );

        try (InputStream in = new URL(url).openStream()) {
            Files.copy(in, tilePath, StandardCopyOption.REPLACE_EXISTING);
        }

        return tileFile;
    }
}
