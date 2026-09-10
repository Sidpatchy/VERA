package com.sidpatchy;

import com.sidpatchy.Tile.ElevationService;
import com.sidpatchy.Tile.TileCache;
import com.sidpatchy.Tile.ThunderforestTileCache;
import com.sidpatchy.Tile.CartoTileCache;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Main {
    private static void printUsage() {
        System.out.println("VERA CLI\n" +
                "Commands:\n" +
                "  serve [--host <addr>] [--port <p>] [--tfKey <key>] [--cartoKey <key>]\n" +
                "                                           Start REST API server (Spring Boot)\n" +
                "  elevation --lat <v> --lon <v> [--zoom <z>] [--cache <dir>]\n" +
                "                                           Print elevation (meters) at lat/lon\n" +
                "  los --lat <v> --lon <v> [--zoom <z>] [--agl <m>] [--radius <tiles>]\n" +
                "      [--angleBins <n>] [--overlay] [--tfKey <key>] [--out <file>]\n" +
                "                                           Generate LOS PNG (optionally overlay on TF)\n" +
                "  prefetch --type <terrain|carto|thunder> --minLat <v> --minLon <v> --maxLat <v> --maxLon <v>\n" +
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
        switch (cmd) {
            case "serve": {
                Map<String, String> p = parseArgs(args);
                if (p.containsKey("host")) System.setProperty("server.address", p.get("host"));
                if (p.containsKey("port")) System.setProperty("server.port", p.get("port"));
                if (p.containsKey("tfKey")) System.setProperty("thunderforest.api.key", p.get("tfKey"));
                if (p.containsKey("cartoKey")) System.setProperty("carto.api.key", p.get("cartoKey"));
                ApiApplication.main(new String[]{});
                break;
            }
            case "elevation": {
                Map<String, String> p = parseArgs(args);
                double lat = Double.parseDouble(p.getOrDefault("lat", Double.toString(42.32626564830605)));
                double lon = Double.parseDouble(p.getOrDefault("lon", Double.toString(-113.6556245641089)));
                int zoom = Integer.parseInt(p.getOrDefault("zoom", "12"));
                String cacheDir = p.getOrDefault("cache", "./terrain_cache");
                TileCache cache = new TileCache(cacheDir);
                double elevation = ElevationService.getElevationAt(lat, lon, zoom, cache);
                System.out.println("Elevation(m): " + elevation);
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

                TileCache cache = new TileCache(p.getOrDefault("cache", "./terrain_cache"));
                ElevationService.ElevationGrid grid = ElevationService.getElevationGridAround(
                        lat, lon, zoom, radius, ElevationService.AreaShape.SQUARE, cache);
                ElevationService.ElevationGrid losMasked = ElevationService.applyLineOfSightMask(
                        grid, lat, lon, ElevationService.ObserverHeightMode.AGL, agl, cache, angleBins);

                if (overlay) {
                    String tfKey = ThunderforestTileCache.resolveApiKey(p.get("tfKey"));
                    if (tfKey != null && !tfKey.isBlank()) {
                        ThunderforestTileCache tf = new ThunderforestTileCache(
                                p.getOrDefault("tfCache", "./thunder_cache"), tfKey);
                        ElevationService.saveLosOverlayOnThunderforest(losMasked, tf, new File(out), lat, lon);
                    } else {
                        String cartoKey = com.sidpatchy.Tile.CartoTileCache.resolveApiKey(p.get("cartoKey"));
                        if (cartoKey == null || cartoKey.isBlank()) {
                            System.err.println("--overlay requires a Thunderforest or Carto API key");
                            return;
                        }
                        com.sidpatchy.Tile.CartoTileCache carto = new com.sidpatchy.Tile.CartoTileCache(
                                p.getOrDefault("cartoCache", "./carto_cache"), cartoKey);
                        ElevationService.saveLosOverlayOnCarto(losMasked, carto, new File(out));
                    }
                } else {
                    ElevationService.saveElevationGridAsPng(losMasked, new File(out));
                }
                System.out.println("Saved: " + new File(out).getAbsolutePath());
                break;
            }
            case "prefetch": {
                Map<String, String> p = parseArgs(args);
                String type = p.getOrDefault("type", "terrain").toLowerCase();
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
                                                new TileCache(cacheDir).getTile(z, x, y);
                                            } else if (type.equals("carto")) {
                                                new com.sidpatchy.Tile.CartoTileCache(cacheDir, p.get("cartoKey")).getTile(z, x, y);
                                            } else {
                                                new com.sidpatchy.Tile.ThunderforestTileCache(cacheDir, tfKey).getTile(z, x, y);
                                            }
                                            success++;
                                        } catch (Exception e) {
                                            System.err.println("Failed z="+z+" x="+x+" y="+y+": "+e.getMessage());
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
                                            new TileCache(cacheDir).getTile(z, x, y);
                                        } else if (type.equals("carto")) {
                                            new com.sidpatchy.Tile.CartoTileCache(cacheDir, p.get("cartoKey")).getTile(z, x, y);
                                        } else {
                                            new com.sidpatchy.Tile.ThunderforestTileCache(cacheDir, tfKey).getTile(z, x, y);
                                        }
                                        success++;
                                    } catch (Exception e) {
                                        System.err.println("Failed z="+z+" x="+x+" y="+y+": "+e.getMessage());
                                    }
                                }
                            }
                        }
                    }
                }
                System.out.println("Prefetch complete. Downloaded " + success + "/" + total + " tiles to " + cacheDir);
                break;
            }
            default:
                printUsage();
        }
    }
}
