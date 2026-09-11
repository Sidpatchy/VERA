package com.sidpatchy;

import com.sidpatchy.Tile.ElevationService;
import com.sidpatchy.Tile.CurvatureModel;
import com.sidpatchy.Tile.TileCache;
import com.sidpatchy.Tile.ElevationProvider;
import com.sidpatchy.Tile.ThunderforestTileCache;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class Main {
    private static void printUsage() {
        System.out.println("VERA CLI\n" +
                "Commands:\n" +
                "  elevation --lat <v> --lon <v> [--zoom <z>] [--source <terrarium|copernicus>] [--cache <dir>]\n" +
                "                                           Print elevation (meters) at lat/lon\n" +
                "  los --lat <v> --lon <v> [--zoom <z>] [--agl <m>] [--radius <tiles>]\n" +
                "      [--angleBins <n>] [--overlay] [--tfKey <key>] [--out <file>]\n" +
                "      [--elevation-out <file>] [--curvature-out <file>] [--source <terrarium|copernicus>]\n" +
                "                                           Generate LOS image/KMZ (optionally overlay on TF)\n" +
                "                                           Optionally export stitched elevation/curvature maps\n" +
                "                                           Output formats: .png, .jpg/.jpeg, .webp, .kmz\n" +
                "  prefetch --type <terrain|carto|thunder> --source <terrarium|copernicus> --minLat <v> --minLon <v> --maxLat <v> --maxLon <v>\n" +
                "           [--zoom <z> | --zMin <z> --zMax <z>] [--cache <dir>] [--tfKey <key>]\n" +
                "                                           Bulk download tiles into local cache\n");
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        String key = null;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                key = a.substring(2);
                // flags (no value)
                if (key.equals("overlay")) {
                    map.put(key, "true");
                    key = null;
                }
            } else if (key != null) {
                map.put(key, a);
                key = null;
            }
        }
        return map;
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String cmd = args[0].toLowerCase();
        CliProgress progress = CliProgress.open();
        switch (cmd) {
            case "elevation": {
                Map<String, String> p = parseArgs(args);
                double lat = Double.parseDouble(p.getOrDefault("lat", Double.toString(42.32626564830605)));
                double lon = Double.parseDouble(p.getOrDefault("lon", Double.toString(-113.6556245641089)));
                int zoom = Integer.parseInt(p.getOrDefault("zoom", "12"));
                String cacheDir = p.getOrDefault("cache", "./terrain_cache");
                 TileCache cache = new TileCache(cacheDir, ElevationProvider.parse(p.get("source")));
                 System.out.println("Elevation source: " + ElevationProvider.parse(p.get("source")));
                 double elevation = ElevationService.getElevationAt(lat, lon, zoom, cache);
                 progress.complete("Elevation lookup");
                 System.out.println("Elevation(m): " + elevation);
                 progress.close();
                 break;
            }
            case "los": {
                Map<String, String> p = parseArgs(args);
                double lat = Double.parseDouble(p.get("lat"));
                double lon = Double.parseDouble(p.get("lon"));
                int zoom = Integer.parseInt(p.getOrDefault("zoom", "12"));
                double agl = Double.parseDouble(p.getOrDefault("agl", "10.0"));
                int radius = Integer.parseInt(p.getOrDefault("radius", "22"));
                int angleBins = Integer.parseInt(p.getOrDefault("angleBins", "1440"));
                boolean overlay = Boolean.parseBoolean(p.getOrDefault("overlay", "false"));
                String out = p.getOrDefault("out", overlay ? "./los_overlay.png" : "./los.png");
                boolean kmz = out.toLowerCase().endsWith(".kmz");

                if (kmz && overlay) {
                    System.err.println("--out .kmz currently supports viewshed exports without --overlay");
                    progress.close();
                    return;
                }

                TileCache cache = new TileCache(p.getOrDefault("cache", "./terrain_cache"),
                        ElevationProvider.parse(p.get("source")));
                System.out.println("Elevation source: " + ElevationProvider.parse(p.get("source")));
                ElevationService.ElevationGrid grid;
                int elevationTiles = (radius * 2 + 1) * (radius * 2 + 1);
                progress.bar("Loading elevation tiles", 0, elevationTiles);
                grid = ElevationService.getElevationGridAround(
                        lat, lon, zoom, radius, ElevationService.AreaShape.SQUARE, cache,
                        current -> progress.bar("Loading elevation tiles", current, elevationTiles));
                progress.complete("Loading elevation tiles");
                String elevationOut = p.get("elevation-out");
                String curvatureOut = p.get("curvature-out");
                if (elevationOut != null || curvatureOut != null) {
                    if (elevationOut != null) {
                        progress.bar("Rendering elevation map", 0, grid.height);
                        BufferedImage elevationImage = ElevationService.elevationGridToImage(
                                grid, current -> progress.bar("Rendering elevation map", current, grid.height));
                        progress.complete("Rendering elevation map");
                        try (CliProgress.Spinner ignored = progress.spinner("Encoding elevation map")) {
                            ElevationService.writeImageFile(elevationImage, new File(elevationOut));
                        }
                        System.out.println("Saved elevation map: " + new File(elevationOut).getAbsolutePath());
                    }
                    if (curvatureOut != null) {
                        progress.bar("Rendering curvature map", 0, grid.height);
                        BufferedImage curvatureImage = CurvatureModel.observerCurvatureToImage(
                                grid, lat, lon, ElevationService.ObserverHeightMode.AGL, agl, cache,
                                current -> progress.bar("Rendering curvature map", current, grid.height));
                        progress.complete("Rendering curvature map");
                        try (CliProgress.Spinner ignored = progress.spinner("Encoding curvature map")) {
                            ElevationService.writeImageFile(curvatureImage, new File(curvatureOut));
                        }
                        System.out.println("Saved curvature map: " + new File(curvatureOut).getAbsolutePath());
                    }
                }
                progress.bar("Running LoS raycasts", 0, angleBins);
                ElevationService.ElevationGrid losMasked = ElevationService.applyLineOfSightMask(
                        grid, lat, lon, ElevationService.ObserverHeightMode.AGL, agl, cache, angleBins,
                        current -> progress.bar("Running LoS raycasts", current, angleBins));
                progress.complete("Running LoS raycasts");

                if (overlay) {
                    String tfKey = ThunderforestTileCache.resolveApiKey(p.get("tfKey"));
                    if (tfKey != null && !tfKey.isBlank()) {
                        ThunderforestTileCache tf = new ThunderforestTileCache(
                                p.getOrDefault("tfCache", "./thunder_cache"), tfKey);
                        int mapTiles = losMasked.tilesWide * losMasked.tilesHigh;
                        progress.bar("Loading map tiles", 0, mapTiles);
                        ElevationService.saveLosOverlayOnThunderforest(losMasked, tf, new File(out), lat, lon,
                                current -> progress.bar("Loading map tiles", current, mapTiles),
                                current -> {
                                    progress.bar("Rendering LOS overlay", current, losMasked.height);
                                    if (current >= losMasked.height) {
                                        progress.complete("Rendering LOS overlay");
                                        progress.bar("Writing image", 0, 1);
                                    }
                                });
                        progress.complete("Loading map tiles");
                        progress.complete("Writing image");
                    } else {
                        String cartoKey = com.sidpatchy.Tile.CartoTileCache.resolveApiKey(p.get("cartoKey"));
                        if (cartoKey == null || cartoKey.isBlank()) {
                            System.err.println("--overlay requires a Thunderforest or Carto API key");
                            progress.close();
                            return;
                        }
                        com.sidpatchy.Tile.CartoTileCache carto = new com.sidpatchy.Tile.CartoTileCache(
                                p.getOrDefault("cartoCache", "./carto_cache"), cartoKey);
                        int mapTiles = losMasked.tilesWide * losMasked.tilesHigh;
                        progress.bar("Loading map tiles", 0, mapTiles);
                        ElevationService.saveLosOverlayOnCarto(losMasked, carto, new File(out),
                                current -> progress.bar("Loading map tiles", current, mapTiles),
                                current -> {
                                    progress.bar("Rendering LOS overlay", current, losMasked.height);
                                    if (current >= losMasked.height) {
                                        progress.complete("Rendering LOS overlay");
                                        progress.bar("Writing image", 0, 1);
                                    }
                                });
                        progress.complete("Loading map tiles");
                        progress.complete("Writing image");
                    }
                } else {
                    progress.bar("Rendering elevation image", 0, losMasked.height);
                    BufferedImage image = ElevationService.elevationGridToImage(
                            losMasked,
                            current -> progress.bar("Rendering elevation image", current, losMasked.height));
                    progress.complete("Rendering elevation image");
                    try (CliProgress.Spinner ignored = progress.spinner("Encoding image")) {
                        if (kmz) {
                            KmzExporter.writeViewshed(Path.of(out), image, losMasked);
                        } else {
                            ElevationService.writeImageFile(image, new File(out));
                        }
                    }
                }
                System.out.println("Saved: " + new File(out).getAbsolutePath());
                progress.close();
                break;
            }
            case "prefetch": {
                Map<String, String> p = parseArgs(args);
                String type = p.getOrDefault("type", "terrain").toLowerCase();
                ElevationProvider provider = ElevationProvider.parse(p.get("source"));
                double minLat = Double.parseDouble(p.get("minLat"));
                double minLon = Double.parseDouble(p.get("minLon"));
                double maxLat = Double.parseDouble(p.get("maxLat"));
                double maxLon = Double.parseDouble(p.get("maxLon"));
                boolean hasZoom = p.containsKey("zoom");
                int zMin = hasZoom ? Integer.parseInt(p.get("zoom")) : Integer.parseInt(p.getOrDefault("zMin", "0"));
                int zMax = hasZoom ? Integer.parseInt(p.get("zoom")) : Integer.parseInt(p.getOrDefault("zMax", "0"));
                if (zMax < zMin) { int tmp = zMin; zMin = zMax; zMax = tmp; }
                String cacheDir = p.getOrDefault("cache", type.equals("carto") ? "./carto_cache" : (type.equals("thunder") ? "./thunder_cache" : "./terrain_cache"));

                String tfKey = p.get("tfKey");
                if (type.equals("thunder") && (tfKey == null || tfKey.isBlank())) {
                    System.err.println("--type thunder requires --tfKey <key>");
                    progress.close();
                    return;
                }

                // clamp inputs
                minLat = Math.max(-85.05112878, Math.min(85.05112878, minLat));
                maxLat = Math.max(-85.05112878, Math.min(85.05112878, maxLat));
                if (maxLat < minLat) { double t = minLat; minLat = maxLat; maxLat = t; }
                while (minLon < -180) { minLon += 360; }
                while (minLon > 180) { minLon -= 360; }
                while (maxLon < -180) { maxLon += 360; }
                while (maxLon > 180) { maxLon -= 360; }

                int total = 0;
                int success = 0;
                for (int z = zMin; z <= zMax; z++) {
                    int n = 1 << z;
                    // function to convert lat/lon to tile indices
                    java.util.function.DoubleFunction<Integer> latToY = (lat) -> {
                        double s = Math.sin(Math.toRadians(lat));
                        double y = Math.log((1 + s) / (1 - s));
                        int yy = (int) Math.floor((1 - y / (2 * Math.PI)) / 2 * n);
                        if (yy < 0) yy = 0; if (yy >= n) yy = n - 1; return yy;
                    };
                    java.util.function.DoubleFunction<Integer> lonToX = (lon) -> {
                        double xx = (lon + 180.0) / 360.0 * n;
                        int xi = (int) Math.floor(xx);
                        xi = ((xi % n) + n) % n; // wrap
                        return xi;
                    };

                    int yMin = latToY.apply(maxLat); // note: TMS origin top-left
                    int yMax = latToY.apply(minLat);

                    // Handle bounding boxes that cross antimeridian by splitting
                    double startLon = minLon;
                    double endLon = maxLon;
                    boolean wraps = endLon < startLon;
                    int segments = wraps ? 2 : 1;
                    for (int seg = 0; seg < segments; seg++) {
                        double segMinLon = (seg == 0) ? startLon : -180.0;
                        double segMaxLon = (seg == 0) ? 180.0 : endLon;
                        int xMin = lonToX.apply(segMinLon);
                        int xMax = lonToX.apply(segMaxLon);
                        // If segment covers full world or wraps spanning index order
                        boolean fullWorld = Math.abs(segMaxLon - segMinLon) >= 360.0 - 1e-9;
                        if (fullWorld) { xMin = 0; xMax = n - 1; }

                        if (!fullWorld && xMax < xMin) {
                            // cover wrap by two loops
                            int[] ranges = new int[]{0, xMax, xMin, n - 1};
                            for (int r = 0; r < 2; r++) {
                                int a = ranges[r * 2];
                                int b = ranges[r * 2 + 1];
                                for (int x = a; x <= b; x++) {
                                    for (int y = yMin; y <= yMax; y++) {
                                        total++;
                                        try {
                                            if (type.equals("terrain")) {
                                                new TileCache(cacheDir, provider).getTile(z, x, y);
                                            } else if (type.equals("carto")) {
                                                new com.sidpatchy.Tile.CartoTileCache(cacheDir, p.get("cartoKey")).getTile(z, x, y);
                                            } else {
                                                new com.sidpatchy.Tile.ThunderforestTileCache(cacheDir, tfKey).getTile(z, x, y);
                                            }
                                            success++;
                                            progress.bar("Downloading tiles", success, total);
                                        } catch (Exception e) {
                                            System.err.println("Failed z="+z+" x="+x+" y="+y+": "+e.getMessage());
                                            progress.bar("Downloading tiles", success, total);
                                        }
                                    }
                                }
                            }
                        } else {
                            for (int x = xMin; x <= xMax; x++) {
                                for (int y = yMin; y <= yMax; y++) {
                                    total++;
                                    try {
                                        if (type.equals("terrain")) {
                                            new TileCache(cacheDir, provider).getTile(z, x, y);
                                        } else if (type.equals("carto")) {
                                            new com.sidpatchy.Tile.CartoTileCache(cacheDir, p.get("cartoKey")).getTile(z, x, y);
                                        } else {
                                            new com.sidpatchy.Tile.ThunderforestTileCache(cacheDir, tfKey).getTile(z, x, y);
                                        }
                                        success++;
                                        progress.bar("Downloading tiles", success, total);
                                    } catch (Exception e) {
                                        System.err.println("Failed z="+z+" x="+x+" y="+y+": "+e.getMessage());
                                        progress.bar("Downloading tiles", success, total);
                                    }
                                }
                            }
                        }
                    }
                }
                progress.complete("Downloading tiles");
                System.out.println("Prefetch complete. Downloaded " + success + "/" + total + " tiles to " + cacheDir);
                progress.close();
                break;
            }
            default:
                printUsage();
                progress.close();
        }
    }
}
