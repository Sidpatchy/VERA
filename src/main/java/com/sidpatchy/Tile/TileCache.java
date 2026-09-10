package com.sidpatchy.Tile;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import javax.imageio.ImageIO;

public class TileCache {
    private final Path cacheDir;
    private final long maxAgeSeconds = 365 * 24 * 60 * 60; // 365 days

    public TileCache(String cachePath) {
        this.cacheDir = Paths.get(cachePath);
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create cache directory", e);
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
}

