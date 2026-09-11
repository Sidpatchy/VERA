package com.sidpatchy;

import com.sidpatchy.Tile.ElevationProvider;
import com.sidpatchy.Tile.TileCache;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

}
