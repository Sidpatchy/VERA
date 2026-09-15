package com.sidpatchy.Tile;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopernicusGlo30TileCacheTest {
    @Test
    void recognizesPublishedCopernicusTileListEntries() {
        assertTrue(CopernicusGlo30TileCache.isTileListEntry(
                "Copernicus_DSM_COG_10_N42_00_W113_00_DEM"));
        assertFalse(CopernicusGlo30TileCache.isTileListEntry(""));
        assertFalse(CopernicusGlo30TileCache.isTileListEntry(
                "Copernicus_DSM_COG_30_N42_00_W113_00_DEM"));
    }

    @Test
    void unpublishedCellsReturnZeroElevationWithoutAttemptingDownload() throws Exception {
        Path directory = Files.createTempDirectory("copernicus-unpublished-test");
        try {
            Path cogDir = directory.resolve("cog");
            Files.createDirectories(cogDir);
            Files.write(cogDir.resolve("tileList.txt"), List.of(
                    "Copernicus_DSM_COG_10_N42_00_W113_00_DEM"
            ));

            CopernicusGlo30TileCache cache = new CopernicusGlo30TileCache(directory);
            // Tile z=12 x=113 y=1009 is in the ocean / unpublished COG cell
            cache.prefetchTile(12, 113, 1009);
            float[][] elevation = cache.getElevationData(12, 113, 1009);
            assertEquals(256, elevation.length);
            assertEquals(256, elevation[0].length);
            for (int r = 0; r < 256; r++) {
                for (int c = 0; c < 256; c++) {
                    assertEquals(0.0f, elevation[r][c], 0.001f);
                }
            }
        } finally {
            deleteRecursively(directory);
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                entries.forEach(child -> {
                    try { deleteRecursively(child); } catch (Exception e) { throw new RuntimeException(e); }
                });
            }
        }
        Files.deleteIfExists(path);
    }
}