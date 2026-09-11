package com.sidpatchy;

import com.sidpatchy.Tile.ElevationService;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes a georeferenced PNG and KML GroundOverlay as a KMZ archive. */
public final class KmzExporter {
    private static final int MAX_OVERLAY_TILE_SIZE = 2048;
    private KmzExporter() {
    }

    public static void writeViewshed(Path output, BufferedImage image,
                                     ElevationService.ElevationGrid grid) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        String title = buildTitle(output);

        try (OutputStream file = Files.newOutputStream(output);
             ZipOutputStream zip = new ZipOutputStream(file, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("doc.kml"));
            zip.write(buildKml(grid, title, image.getWidth(), image.getHeight()).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            int columns = tileCount(image.getWidth());
            int rows = tileCount(image.getHeight());
            for (int row = 0; row < rows; row++) {
                for (int column = 0; column < columns; column++) {
                    int x = column * MAX_OVERLAY_TILE_SIZE;
                    int y = row * MAX_OVERLAY_TILE_SIZE;
                    int tileWidth = Math.min(MAX_OVERLAY_TILE_SIZE, image.getWidth() - x);
                    int tileHeight = Math.min(MAX_OVERLAY_TILE_SIZE, image.getHeight() - y);
                    zip.putNextEntry(new ZipEntry(tileName(row, column)));
                    writeReprojectedPng(image, grid, zip, x, y, tileWidth, tileHeight);
                    zip.closeEntry();
                }
            }
        }
    }

    private static int tileCount(int pixels) {
        return Math.max(1, (pixels + MAX_OVERLAY_TILE_SIZE - 1) / MAX_OVERLAY_TILE_SIZE);
    }

    private static String tileName(int row, int column) {
        return "viewshed/r" + row + "c" + column + ".png";
    }

    private static String buildTitle(Path output) {
        String fileName = output.getFileName().toString();
        int extension = fileName.lastIndexOf('.');
        String baseName = extension > 0 ? fileName.substring(0, extension) : fileName;
        return escapeXml(baseName + " viewshed");
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * Converts the Web Mercator image rows into rows spaced linearly in latitude.
     * KML LatLonBox overlays use geographic (Plate Carrée) row placement, while
     * the terrain grid and its source tiles use Web Mercator row placement.
     */
    private static void writeReprojectedPng(BufferedImage image,
                                             ElevationService.ElevationGrid grid,
                                             OutputStream output,
                                             int xOffset, int yOffset,
                                             int width, int height) throws IOException {
        int fullHeight = image.getHeight();
        output.write(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
        writePngChunk(output, "IHDR", pngHeader(width, height));
        if (height == 0 || width == 0) {
            writePngChunk(output, "IEND", new byte[0]);
            return;
        }

        int n = 1 << grid.zoom;
        int radiusY = (grid.tilesHigh - 1) / 2;
        double top = (double) (grid.centerTileY - radiusY) / n;
        double bottom = (double) (grid.centerTileY + radiusY + 1) / n;
        double north = tileYToLatitude(grid.centerTileY - radiusY, n);
        double south = tileYToLatitude(grid.centerTileY + radiusY + 1, n);

        int[] lowerPixels = new int[width];
        int[] upperPixels = new int[width];
        byte[] row = new byte[1 + width * 4];
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        PngChunkOutputStream idat = new PngChunkOutputStream(output, "IDAT");
        try {
            for (int y = 0; y < height; y++) {
                int globalY = yOffset + y;
                double latitude = north + (south - north) * ((globalY + 0.5) / fullHeight);
                double mercator = latitudeToTileY(latitude);
                double sourceY = ((mercator - top) / (bottom - top)) * fullHeight - 0.5;
                int lower = (int) Math.floor(sourceY);
                double fraction = sourceY - lower;
                int upper = lower + 1;
                lower = Math.max(0, Math.min(fullHeight - 1, lower));
                upper = Math.max(0, Math.min(fullHeight - 1, upper));
                image.getRGB(xOffset, lower, width, 1, lowerPixels, 0, width);
                image.getRGB(xOffset, upper, width, 1, upperPixels, 0, width);
                row[0] = 0;
                for (int x = 0; x < width; x++) {
                    int firstAlpha = ((lowerPixels[x] >>> 24) & 0xff) == 0 ? 0 : 96;
                    int secondAlpha = ((upperPixels[x] >>> 24) & 0xff) == 0 ? 0 : 96;
                    int alpha = blendChannel(firstAlpha, secondAlpha, fraction);
                    int offset = 1 + x * 4;
                    row[offset] = (byte) 255;
                    row[offset + 1] = 0;
                    row[offset + 2] = 0;
                    row[offset + 3] = (byte) alpha;
                }
                deflater.setInput(row);
                while (!deflater.needsInput()) {
                    byte[] compressed = new byte[8192];
                    int count = deflater.deflate(compressed);
                    if (count == 0) break;
                    idat.write(compressed, 0, count);
                }
            }
            deflater.finish();
            byte[] compressed = new byte[8192];
            while (!deflater.finished()) {
                int count = deflater.deflate(compressed);
                if (count > 0) idat.write(compressed, 0, count);
            }
        } finally {
            deflater.end();
        }
        writePngChunk(output, "IEND", new byte[0]);
    }

    private static byte[] pngHeader(int width, int height) {
        return new byte[]{
                (byte) (width >>> 24), (byte) (width >>> 16), (byte) (width >>> 8), (byte) width,
                (byte) (height >>> 24), (byte) (height >>> 16), (byte) (height >>> 8), (byte) height,
                8, 6, 0, 0, 0
        };
    }
    private static void writePngChunk(OutputStream output, String type, byte[] data) throws IOException {
        writeInt(output, data.length);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        output.write(typeBytes);
        output.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(output, (int) crc.getValue());
    }

    private static void writeInt(OutputStream output, int value) throws IOException {
        output.write(value >>> 24);
        output.write(value >>> 16);
        output.write(value >>> 8);
        output.write(value);
    }
    private static final class PngChunkOutputStream extends OutputStream {
        private final OutputStream output;
        private final byte[] type;
        private final CRC32 crc = new CRC32();

        private PngChunkOutputStream(OutputStream output, String type) {
            this.output = output;
            this.type = type.getBytes(StandardCharsets.US_ASCII);
        }
        @Override
        public void write(int value) throws IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }
        @Override
        public void write(byte[] bytes, int offset, int count) throws IOException {
            if (count == 0) return;
            writeInt(output, count);
            output.write(type);
            output.write(bytes, offset, count);
            crc.reset();
            crc.update(type);
            crc.update(bytes, offset, count);
            writeInt(output, (int) crc.getValue());
        }
    }

    private static double latitudeToTileY(double latitude) {
        double clamped = Math.max(-85.05112878, Math.min(85.05112878, latitude));
        double radians = Math.toRadians(clamped);
        return (1.0 - Math.log(Math.tan(radians) + 1.0 / Math.cos(radians)) / Math.PI) / 2.0;
    }

    private static int blendChannel(int first, int second, double fraction) {
        return (int) Math.round(first + (second - first) * fraction);
    }

    private static String buildKml(ElevationService.ElevationGrid grid, String title,
                                   int imageWidth, int imageHeight) {
        int n = 1 << grid.zoom;
        int radiusX = (grid.tilesWide - 1) / 2;
        int radiusY = (grid.tilesHigh - 1) / 2;
        double west = tileXToLongitude(grid.centerTileX - radiusX, n);
        double east = tileXToLongitude(grid.centerTileX + radiusX + 1, n);
        double north = tileYToLatitude(grid.centerTileY - radiusY, n);
        double south = tileYToLatitude(grid.centerTileY + radiusY + 1, n);
        StringBuilder kml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n"
                + "  <Document>\n");
        int columns = tileCount(imageWidth);
        int rows = tileCount(imageHeight);
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int x = column * MAX_OVERLAY_TILE_SIZE;
                int y = row * MAX_OVERLAY_TILE_SIZE;
                int tileWidth = Math.min(MAX_OVERLAY_TILE_SIZE, imageWidth - x);
                int tileHeight = Math.min(MAX_OVERLAY_TILE_SIZE, imageHeight - y);
                double tileWest = west + (east - west) * x / imageWidth;
                double tileEast = west + (east - west) * (x + tileWidth) / imageWidth;
                double tileNorth = north + (south - north) * y / imageHeight;
                double tileSouth = north + (south - north) * (y + tileHeight) / imageHeight;
                kml.append("  <GroundOverlay>\n")
                        .append("    <name>").append(title).append("</name>\n")
                        .append("    <Icon><href>").append(tileName(row, column)).append("</href></Icon>\n")
                        .append("    <LatLonBox>\n")
                        .append(String.format(Locale.ROOT, "      <north>%.12f</north>\n", tileNorth))
                        .append(String.format(Locale.ROOT, "      <south>%.12f</south>\n", tileSouth))
                        .append(String.format(Locale.ROOT, "      <east>%.12f</east>\n", tileEast))
                        .append(String.format(Locale.ROOT, "      <west>%.12f</west>\n", tileWest))
                        .append("    </LatLonBox>\n")
                        .append("  </GroundOverlay>\n");
            }
        }
        return kml.append("  </Document>\n</kml>\n").toString();
    }

    private static double tileXToLongitude(int x, int n) {
        return normalizeLongitude((double) x / n * 360.0 - 180.0);
    }

    private static double tileYToLatitude(int y, int n) {
        double mercatorY = Math.PI * (1.0 - 2.0 * y / n);
        return Math.toDegrees(Math.atan(Math.sinh(mercatorY)));
    }

    private static double normalizeLongitude(double longitude) {
        double normalized = longitude % 360.0;
        if (normalized < -180.0) normalized += 360.0;
        if (normalized >= 180.0) normalized -= 360.0;
        return normalized;
    }
}