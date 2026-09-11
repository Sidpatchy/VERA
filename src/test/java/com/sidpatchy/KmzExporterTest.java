package com.sidpatchy;

import com.sidpatchy.Tile.ElevationService;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KmzExporterTest {
    @Test
    void reprojectsMercatorRowsForLatitudeLinearGroundOverlay() throws Exception {
        BufferedImage source = new BufferedImage(1, 256, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 64, 0xffff0000);
        float[][] values = new float[256][256];
        ElevationService.ElevationGrid grid = new ElevationService.ElevationGrid(
                values, 256, 1, 1, 2, 2, 2);
        Path output = Files.createTempFile("viewshed", ".kmz");
        try {
            KmzExporter.writeViewshed(output, source, grid);
            try (ZipFile zip = new ZipFile(output.toFile());
                 InputStream png = zip.getInputStream(zip.getEntry("viewshed/r0c0.png"))) {
                BufferedImage result = ImageIO.read(png);
                int expectedY = 84;
                int redY = -1;
                for (int y = 0; y < result.getHeight(); y++) {
                    if ((result.getRGB(0, y) >>> 24) != 0) {
                        redY = y;
                        break;
                    }
                }
                assertEquals(expectedY, redY);
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Test
    void splitsLargeImagesIntoGoogleEarthSizedOverlays() throws Exception {
        BufferedImage source = new BufferedImage(4096, 4096, BufferedImage.TYPE_INT_ARGB);
        float[][] values = new float[4096][4096];
        ElevationService.ElevationGrid grid = new ElevationService.ElevationGrid(
                values, 4096, 1, 1, 2, 2, 2);
        Path output = Files.createTempFile("large-viewshed", ".kmz");
        try {
            KmzExporter.writeViewshed(output, source, grid);
            try (ZipFile zip = new ZipFile(output.toFile());
                 InputStream first = zip.getInputStream(zip.getEntry("viewshed/r0c0.png"));
                 InputStream last = zip.getInputStream(zip.getEntry("viewshed/r1c1.png"))) {
                assertEquals(2048, ImageIO.read(first).getWidth());
                assertEquals(2048, ImageIO.read(last).getHeight());
                String kml = new String(zip.getInputStream(zip.getEntry("doc.kml")).readAllBytes());
                assertEquals(4, kml.split("<GroundOverlay>", -1).length - 1);
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Test
    void usesCustomNameInKml() throws Exception {
        BufferedImage source = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        ElevationService.ElevationGrid grid = new ElevationService.ElevationGrid(
                new float[1][1], 1, 1, 1, 2, 2, 2);
        Path output = Files.createTempFile("viewshed", ".kmz");
        try {
            KmzExporter.writeViewshed(output, source, grid, "My & Viewshed");
            try (ZipFile zip = new ZipFile(output.toFile())) {
                String kml = new String(zip.getInputStream(zip.getEntry("doc.kml")).readAllBytes());
                org.junit.jupiter.api.Assertions.assertTrue(kml.contains("My &amp; Viewshed"));
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }
}