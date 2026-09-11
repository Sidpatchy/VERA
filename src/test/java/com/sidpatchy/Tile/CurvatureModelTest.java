package com.sidpatchy.Tile;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CurvatureModelTest {
    @Test
    void appliesCurvatureInsideGeometricHorizon() throws Exception {
        float[][] values = {
                {100.0f, 100.0f, 100.0f},
                {100.0f, 100.0f, 100.0f},
                {100.0f, 100.0f, 100.0f}
        };
        ElevationService.ElevationGrid grid = new ElevationService.ElevationGrid(
                values, 256, 3, 3, 12, 2048, 2048);

        ElevationService.ElevationGrid adjusted = CurvatureModel.applyObserverCurvature(
                grid, 0.0, 0.0, ElevationService.ObserverHeightMode.AGL, 100_000.0,
                null, "build/test-curvature-cache", false);

        assertTrue(adjusted.data[1][1] < 99.99999f,
                "curvature must be applied before the geometric horizon");
        assertTrue(adjusted.data[1][0] < adjusted.data[1][1]);
    }
}