package com.sidpatchy.Tile;

import javax.imageio.ImageIO;
import com.twelvemonkeys.imageio.plugins.tiff.TIFFImageReaderSpi;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Reads the public Copernicus GLO-30 one-degree COGs and provides elevation tiles.
 */
final class CopernicusGlo30TileCache {
    private static final String BUCKET = "https://copernicus-dem-30m.s3.amazonaws.com/";
    private final Path cacheDir;
    private final Map<String, Raster> sourceImages = new LinkedHashMap<>(4, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Raster> eldest) {
            return size() > 2;
        }
    };
    private Raster activeRaster;
    private int activeSouth;
    private int activeWest;

    CopernicusGlo30TileCache(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    float[][] getElevationData(int zoom, int x, int y) throws IOException {
        int n = 1 << zoom;
        int wrappedX = ((x % n) + n) % n;
        float[][] output = new float[256][256];
        for (int py = 0; py < 256; py++) {
            double globalY = (y + (py + 0.5) / 256.0) / n;
            double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * globalY))));
            for (int px = 0; px < 256; px++) {
                double lon = (wrappedX + (px + 0.5) / 256.0) / n * 360.0 - 180.0;
                output[py][px] = (float) sample(lat, lon);
            }
        }
        return output;
    }

    File getTile(int zoom, int x, int y) throws IOException {
        int n = 1 << zoom;
        int wrappedX = ((x % n) + n) % n;
        Path tile = cacheDir.resolve("copernicus").resolve(
                String.format("%d_%d_%d.png", zoom, wrappedX, y));
        Files.createDirectories(tile.getParent());
        if (Files.exists(tile) && ImageIO.read(tile.toFile()) != null) return tile.toFile();

        BufferedImage output = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int py = 0; py < 256; py++) {
            double globalY = (y + (py + 0.5) / 256.0) / n;
            double lat = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * globalY))));
            for (int px = 0; px < 256; px++) {
                double lon = (wrappedX + (px + 0.5) / 256.0) / n * 360.0 - 180.0;
                double elevation = sample(lat, lon);
                int encoded = (int) Math.round(Math.max(0.0, Math.min(65535.0, elevation + 32768.0)) * 256.0);
                encoded = Math.max(0, Math.min(65535 * 256, encoded));
                int r = Math.min(255, encoded / (256 * 256));
                int g = Math.min(255, (encoded / 256) & 0xff);
                int b = Math.min(255, encoded & 0xff);
                output.setRGB(px, py, (r << 16) | (g << 8) | b);
            }
        }
        Path part = Files.createTempFile(cacheDir, tile.getFileName().toString(), ".part");
        try {
            ImageIO.write(output, "png", part.toFile());
            try {
                Files.move(part, tile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(part, tile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(part);
        }
        return tile.toFile();
    }

    private double sample(double lat, double lon) throws IOException {
        if (lat <= -90.0 || lat >= 90.0) return 0.0;
        int south = (int) Math.floor(lat);
        int west = (int) Math.floor(lon);
        Raster raster;
        if (activeRaster != null && activeSouth == south && activeWest == west) {
            raster = activeRaster;
        } else {
            raster = loadRaster(south, west);
            activeRaster = raster;
            activeSouth = south;
            activeWest = west;
        }
        int px = Math.max(0, Math.min(raster.getWidth() - 1,
                (int) Math.floor((lon - west) * raster.getWidth())));
        int py = Math.max(0, Math.min(raster.getHeight() - 1,
                (int) Math.floor((south + 1.0 - lat) * raster.getHeight())));
        return raster.getSampleDouble(px, py, 0);
    }

    private Raster loadRaster(int south, int west) throws IOException {
        String northing = (south >= 0 ? "N" : "S") + String.format("%02d_00", Math.abs(south));
        String easting = (west >= 0 ? "E" : "W") + String.format("%03d_00", Math.abs(west));
        String stem = "Copernicus_DSM_COG_10_" + northing + "_" + easting + "_DEM";
        Path local = cacheDir.resolve("cog").resolve(stem + ".tif");
        Files.createDirectories(local.getParent());
        Raster raster = sourceImages.get(local.toString());
        if (raster == null) {
            if (Files.exists(local)) {
                try {
                    raster = readTiffRaster(local);
                    validateRaster(local, raster);
                } catch (IOException e) {
                    Files.deleteIfExists(local);
                    raster = null;
                }
            }
        }
        if (raster == null) {
            Path part = Files.createTempFile(local.getParent(), stem, ".part");
            HttpURLConnection connection = (HttpURLConnection) URI.create(
                    BUCKET + stem + "/" + stem + ".tif").toURL().openConnection();
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(120_000);
            connection.setInstanceFollowRedirects(true);
            try {
                if (connection.getResponseCode() / 100 != 2) {
                    throw new IOException("Unable to download Copernicus COG: HTTP "
                            + connection.getResponseCode() + " for " + connection.getURL());
                }
                try (InputStream in = connection.getInputStream()) {
                    Files.copy(in, part, StandardCopyOption.REPLACE_EXISTING);
                    if (Files.size(part) == 0) {
                        throw new IOException("Downloaded empty Copernicus COG: " + local);
                    }
                    raster = readTiffRaster(part);
                    validateRaster(part, raster);
                    Files.move(part, local, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(part, local, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                connection.disconnect();
                Files.deleteIfExists(part);
            }
        }
        sourceImages.put(local.toString(), raster);
        return raster;
    }

    private static Raster readTiffRaster(Path file) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) throw new IOException("Unable to open TIFF: " + file);
            ImageReader reader = new TIFFImageReaderSpi().createReaderInstance();
            try {
                reader.setInput(input);
                return reader.readRaster(0, null);
            } finally {
                reader.dispose();
            }
        }
    }

    private static void validateRaster(Path file, Raster raster) throws IOException {
        if (raster == null || raster.getWidth() <= 0 || raster.getHeight() <= 0
                || raster.getNumBands() == 0) {
            throw new IOException("Copernicus COG has no raster data: " + file);
        }
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        int[][] points = {
                {0, 0},
                {raster.getWidth() / 2, raster.getHeight() / 2},
                {raster.getWidth() - 1, raster.getHeight() - 1}
        };
        for (int[] point : points) {
            double sample = raster.getSampleDouble(point[0], point[1], 0);
            if (Double.isFinite(sample)) {
                minimum = Math.min(minimum, sample);
                maximum = Math.max(maximum, sample);
            }
        }
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum)) {
            throw new IOException("Copernicus COG contains no finite elevation samples: " + file);
        }
    }
}