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
                 InputStream png = zip.getInputStream(zip.getEntry("viewshed.png"))) {
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
}