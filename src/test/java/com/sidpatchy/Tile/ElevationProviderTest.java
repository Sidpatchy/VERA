package com.sidpatchy.Tile;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElevationProviderTest {
    @Test
    void parsesCopernicusAliases() {
        assertEquals(ElevationProvider.COPERNICUS_GLO30, ElevationProvider.parse("copernicus"));
        assertEquals(ElevationProvider.COPERNICUS_GLO30, ElevationProvider.parse("glo30"));
        assertEquals(ElevationProvider.TERRARIUM, ElevationProvider.parse(null));
    }

    @Test
    void reusesDecodedTileFromMemoryAndSmileCache() throws Exception {
        Path directory = Files.createTempDirectory("vera-elevation-cache");
        try {
            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            int encoded = 1000 * 256 + 32768 * 256;
            int rgb = ((encoded / (256 * 256)) << 16) | (((encoded / 256) & 0xff) << 8) | (encoded & 0xff);
            for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) image.setRGB(x, y, rgb);
            Path terrarium = directory.resolve("terrarium");
            Files.createDirectories(terrarium);
            ImageIO.write(image, "png", terrarium.resolve("0_0_0.png").toFile());

            TileCache first = new TileCache(directory.toString());
            float[][] decoded = first.getElevationData(0, 0, 0);
            assertEquals(1000.0f, decoded[0][0], 0.01f);
            assertSame(decoded, first.getElevationData(0, 0, 0));
            assertTrue(Files.exists(directory.resolve("terrarium/0_0_0.png")));
            assertTrue(!Files.exists(directory.resolve("0_0_0.png")));
            Files.delete(directory.resolve("terrarium/0_0_0.png"));

            TileCache second = new TileCache(directory.toString());
            assertEquals(1000.0f, second.getElevationData(0, 0, 0)[0][0], 0.01f);
            try (var entries = Files.list(directory.resolve("decoded"))) {
                assertEquals(1, entries.filter(path -> path.toString().endsWith(".smile")).count());
            }
        } finally {
            deleteRecursively(directory);
        }
    }

    @Test
    void storesDecodedTilesInGeographicSmileCells() throws Exception {
        Path directory = Files.createTempDirectory("vera-geographic-cache");
        try {
            writeElevationTile(directory, 10, 190, 377);
            writeElevationTile(directory, 10, 189, 377);
            writeElevationTile(directory, 10, 194, 377);

            TileCache cache = new TileCache(directory.toString());
            cache.getElevationData(10, 190, 377);
            cache.getElevationData(10, 189, 377);
            cache.getElevationData(10, 194, 377);

            try (var entries = Files.list(directory.resolve("decoded"))) {
                var files = entries.filter(path -> path.toString().endsWith(".smile")).toList();
                assertEquals(2, files.size());
                assertTrue(files.stream().allMatch(path -> path.getFileName().toString().startsWith("TERRARIUM-")));
                assertTrue(files.stream().anyMatch(path -> path.getFileName().toString().contains("lon-114")));
                assertTrue(files.stream().anyMatch(path -> path.getFileName().toString().contains("lon-112")));
            }
        } finally {
            deleteRecursively(directory);
        }
    }

    private static void writeElevationTile(Path directory, int zoom, int x, int y) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        Path terrarium = directory.resolve("terrarium");
        Files.createDirectories(terrarium);
        ImageIO.write(image, "png", terrarium.resolve(zoom + "_" + x + "_" + y + ".png").toFile());
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