package com.sidpatchy.Tile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisibilityEngineTest {
    // Geographic center of sample pixel [12][12] in the synthetic 3x3-tile grid.
    private static final double OBSERVER_LAT = -0.0494384765625;
    private static final double OBSERVER_LON = 0.0494384765625;

    @Test
    void sphericalRaycastBlocksTerrainBehindAnElevatedRidge() throws Exception {
        float[][] values = new float[24][24];
        for (int y = 0; y < values.length; y++) {
            for (int x = 0; x < values[y].length; x++) values[y][x] = 0.0f;
        }
        values[12][14] = 1_000.0f;
        values[12][15] = 1_000.0f;

        ElevationService.ElevationGrid grid = new ElevationService.ElevationGrid(
                values, 8, 3, 3, 12, 2048, 2048);

        boolean[][] visible = VisibilityEngine.computeVisibilityMask(
                grid, OBSERVER_LAT, OBSERVER_LON,
                ElevationService.ObserverHeightMode.AGL, 10.0,
                null, 360);

        assertTrue(visible[12][14], "the ridge itself must be visible");
        assertFalse(visible[12][16], "terrain directly behind the ridge must be blocked");
    }

    @Test
    void flatTerrainUsesSphericalDropAtIncreasingRange() throws Exception {
        float[][] values = new float[24][24];
        for (int y = 0; y < values.length; y++) {
            for (int x = 0; x < values[y].length; x++) values[y][x] = 100.0f;
        }
        ElevationService.ElevationGrid grid = new ElevationService.ElevationGrid(
                values, 8, 3, 3, 12, 2048, 2048);

        boolean[][] visible = VisibilityEngine.computeVisibilityMask(
                grid, OBSERVER_LAT, OBSERVER_LON,
                ElevationService.ObserverHeightMode.GROUND, null,
                null, 360);

        assertTrue(visible[12][12], "the observer cell must be visible");
        assertFalse(visible[12][20], "flat terrain falls below the spherical horizon");
    }

    @Test
    void effectiveRayCountCoversLargeGridCorners() {
        float[][] values = new float[1_024][1_024];
        ElevationService.ElevationGrid grid = new ElevationService.ElevationGrid(
                values, 1_024, 1, 1, 12, 2048, 2048);

        int effective = VisibilityEngine.effectiveRayCount(grid, OBSERVER_LAT, OBSERVER_LON, 1_440);

        assertTrue(effective > 1_440);
    }
}