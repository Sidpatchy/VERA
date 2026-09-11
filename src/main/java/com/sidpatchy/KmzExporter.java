package com.sidpatchy;

import com.sidpatchy.Tile.ElevationService;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes a georeferenced PNG and KML GroundOverlay as a KMZ archive. */
public final class KmzExporter {
    private KmzExporter() {
    }

    public static void writeViewshed(Path output, BufferedImage image,
                                     ElevationService.ElevationGrid grid) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        BufferedImage overlay = reprojectToLatitude(image, grid);
        String title = buildTitle(output);

        try (OutputStream file = Files.newOutputStream(output);
             ZipOutputStream zip = new ZipOutputStream(file, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("doc.kml"));
            zip.write(buildKml(grid, title).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("viewshed.png"));
            if (!ImageIO.write(overlay, "png", zip)) {
                throw new IOException("No PNG ImageIO writer is available");
            }
            zip.closeEntry();
        }
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

    private static BufferedImage toRedOverlay(BufferedImage image) {
        BufferedImage overlay = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            image.getRGB(0, y, image.getWidth(), 1, pixels, 0, image.getWidth());
            for (int x = 0; x < pixels.length; x++) {
                if (((pixels[x] >>> 24) & 0xff) != 0) {
                    pixels[x] = (96 << 24) | (255 << 16);
                } else {
                    pixels[x] = 0;
                }
            }
            overlay.setRGB(0, y, image.getWidth(), 1, pixels, 0, image.getWidth());
        }
        return overlay;
    }

    /**
     * Converts the Web Mercator image rows into rows spaced linearly in latitude.
     * KML LatLonBox overlays use geographic (Plate Carrée) row placement, while
     * the terrain grid and its source tiles use Web Mercator row placement.
     */
    private static BufferedImage reprojectToLatitude(BufferedImage image,
                                                      ElevationService.ElevationGrid grid) {
        BufferedImage mercatorOverlay = toRedOverlay(image);
        int width = mercatorOverlay.getWidth();
        int height = mercatorOverlay.getHeight();
        if (height == 0 || width == 0) return mercatorOverlay;

        int n = 1 << grid.zoom;
        int radius = (grid.tilesWide - 1) / 2;
        double top = (double) (grid.centerTileY - radius) / n;
        double bottom = (double) (grid.centerTileY + radius + 1) / n;
        double north = tileYToLatitude(grid.centerTileY - radius, n);
        double south = tileYToLatitude(grid.centerTileY + radius + 1, n);

        BufferedImage geographic = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            double latitude = north + (south - north) * ((y + 0.5) / height);
            double mercator = latitudeToTileY(latitude);
            double sourceY = ((mercator - top) / (bottom - top)) * height - 0.5;
            int lower = (int) Math.floor(sourceY);
            double fraction = sourceY - lower;
            int upper = lower + 1;
            lower = Math.max(0, Math.min(height - 1, lower));
            upper = Math.max(0, Math.min(height - 1, upper));
            for (int x = 0; x < width; x++) {
                int a = mercatorOverlay.getRGB(x, lower);
                int b = mercatorOverlay.getRGB(x, upper);
                geographic.setRGB(x, y, blend(a, b, fraction));
            }
        }
        return geographic;
    }

    private static double latitudeToTileY(double latitude) {
        double clamped = Math.max(-85.05112878, Math.min(85.05112878, latitude));
        double radians = Math.toRadians(clamped);
        return (1.0 - Math.log(Math.tan(radians) + 1.0 / Math.cos(radians)) / Math.PI) / 2.0;
    }

    private static int blend(int first, int second, double fraction) {
        int a = blendChannel((first >>> 24) & 0xff, (second >>> 24) & 0xff, fraction);
        int r = blendChannel((first >>> 16) & 0xff, (second >>> 16) & 0xff, fraction);
        int g = blendChannel((first >>> 8) & 0xff, (second >>> 8) & 0xff, fraction);
        int b = blendChannel(first & 0xff, second & 0xff, fraction);
        return new Color(r, g, b, a).getRGB();
    }

    private static int blendChannel(int first, int second, double fraction) {
        return (int) Math.round(first + (second - first) * fraction);
    }

    private static String buildKml(ElevationService.ElevationGrid grid, String title) {
        int n = 1 << grid.zoom;
        int radius = (grid.tilesWide - 1) / 2;
        double west = tileXToLongitude(grid.centerTileX - radius, n);
        double east = tileXToLongitude(grid.centerTileX + radius + 1, n);
        double north = tileYToLatitude(grid.centerTileY - radius, n);
        double south = tileYToLatitude(grid.centerTileY + radius + 1, n);

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n" +
                "  <GroundOverlay>\n" +
                "    <name>" + title + "</name>\n" +
                "    <Icon><href>viewshed.png</href></Icon>\n" +
                "    <LatLonBox>\n" +
                String.format(Locale.ROOT, "      <north>%.12f</north>\n", north) +
                String.format(Locale.ROOT, "      <south>%.12f</south>\n", south) +
                String.format(Locale.ROOT, "      <east>%.12f</east>\n", east) +
                String.format(Locale.ROOT, "      <west>%.12f</west>\n", west) +
                "    </LatLonBox>\n" +
                "  </GroundOverlay>\n" +
                "</kml>\n";
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