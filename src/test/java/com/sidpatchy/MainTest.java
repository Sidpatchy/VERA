package com.sidpatchy;

import com.sidpatchy.Tile.ElevationProvider;
import com.sidpatchy.Tile.TileCache;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {
    @Test
    void parsesPhysicalRadiusUnits() {
        assertEquals(5, Main.parseRadiusTiles("40km", 0.0, 12));
        assertEquals(5, Main.parseRadiusTiles("25mi", 0.0, 12));
        assertEquals(1, Main.parseRadiusTiles("500m", 0.0, 12));
        assertEquals(1, Main.parseRadiusTiles("1000ft", 0.0, 12));
    }

    @Test
    void retainsBareTileRadiusForCompatibility() {
        assertEquals(22, Main.parseRadiusTiles("22", 42.0, 12));
    }

    @Test
    void rejectsInvalidPhysicalRadius() {
        assertThrows(IllegalArgumentException.class, () -> Main.parseRadiusTiles("-5km", 0.0, 12));
        assertThrows(IllegalArgumentException.class, () -> Main.parseRadiusTiles("tenkm", 0.0, 12));
        assertThrows(IllegalArgumentException.class, () -> Main.parseRadiusTiles("5yards", 0.0, 12));
    }

    @Test
    void copernicusCoverageKeyIsIndependentOfWebTileZoom() {
        TileCache cache = new TileCache("build/test-copernicus-cache", ElevationProvider.COPERNICUS_GLO30);
        assertEquals(cache.prefetchCoverageKey(12, 1000, 1500),
                cache.prefetchCoverageKey(13, 2000, 3000));
    }

    @Test
    void prefetchWithoutCompilePrunesToUniquePublishedCogCells() throws Exception {
        Path directory = Files.createTempDirectory("vera-prefetch-test");
        try {
            Path cogDir = directory.resolve("cog");
            Files.createDirectories(cogDir);
            Files.write(cogDir.resolve("tileList.txt"), List.of(
                    "Copernicus_DSM_COG_10_N42_00_W114_00_DEM",
                    "Copernicus_DSM_COG_10_N42_00_W113_00_DEM"
            ));

            TileCache cache = new TileCache(directory.toString(), ElevationProvider.COPERNICUS_GLO30);
            List<Main.PrefetchTile> planned = new ArrayList<>(Main.planPrefetchTiles(
                    42.0, -114.0, 42.5, -113.0, 12, 12));
            assertTrue(planned.size() > 10);

            Map<Main.PrefetchTile, String> coverage = new HashMap<>();
            List<Main.PrefetchTile> prepared = Main.preparePrefetchTiles(
                    planned, cache, ElevationProvider.COPERNICUS_GLO30, false, coverage);

            // With compile=false, should be pruned down to 1 representative tile per published cell
            assertEquals(2, prepared.size());
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    void prefetchWithCompileRetainsAllPlannedTiles() throws Exception {
        Path directory = Files.createTempDirectory("vera-compile-test");
        try {
            Path cogDir = directory.resolve("cog");
            Files.createDirectories(cogDir);
            Files.write(cogDir.resolve("tileList.txt"), List.of(
                    "Copernicus_DSM_COG_10_N42_00_W114_00_DEM",
                    "Copernicus_DSM_COG_10_N42_00_W113_00_DEM"
            ));

            TileCache cache = new TileCache(directory.toString(), ElevationProvider.COPERNICUS_GLO30);
            List<Main.PrefetchTile> planned = new ArrayList<>(Main.planPrefetchTiles(
                    42.0, -114.0, 42.5, -113.0, 12, 12));
            int totalPlanned = planned.size();
            assertTrue(totalPlanned > 10);

            Map<Main.PrefetchTile, String> coverage = new HashMap<>();
            List<Main.PrefetchTile> prepared = Main.preparePrefetchTiles(
                    planned, cache, ElevationProvider.COPERNICUS_GLO30, true, coverage);

            // With compile=true, all planned tiles must be preserved for compilation
            assertEquals(totalPlanned, prepared.size());
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
