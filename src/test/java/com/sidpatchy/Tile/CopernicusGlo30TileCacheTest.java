package com.sidpatchy.Tile;

import org.junit.jupiter.api.Test;

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
}