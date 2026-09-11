package com.sidpatchy;

import com.sidpatchy.Tile.ElevationService;
import com.sidpatchy.Tile.CurvatureModel;
import com.sidpatchy.Tile.TileCache;
import com.sidpatchy.Tile.ElevationProvider;
import com.sidpatchy.Tile.ThunderforestTileCache;
import com.sidpatchy.Tile.VisibilityEngine;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

public class Main {
    private static final int DEFAULT_PREFETCH_THREADS = 8;
    private record PrefetchTile(int zoom, int x, int y) { }

    private static List<PrefetchTile> planPrefetchTiles(
            double minLat, double minLon, double maxLat, double maxLon, int zMin, int zMax) {
        List<PrefetchTile> tiles = new ArrayList<>();
        for (int z = zMin; z <= zMax; z++) {
            int n = 1 << z;
            int yMin = latitudeToTileY(maxLat, n);
            int yMax = latitudeToTileY(minLat, n);
            int xMin = longitudeToTileX(minLon, n);
            int xMax = longitudeToTileX(maxLon, n);

            if (minLon == -180.0 && maxLon == 180.0) {
                addPrefetchTiles(tiles, z, 0, n - 1, yMin, yMax);
            } else if (xMax < xMin) {
                addPrefetchTiles(tiles, z, 0, xMax, yMin, yMax);
                addPrefetchTiles(tiles, z, xMin, n - 1, yMin, yMax);
            } else {
                addPrefetchTiles(tiles, z, xMin, xMax, yMin, yMax);
            }
        }
        return tiles;
    }

    private static void addPrefetchTiles(
            List<PrefetchTile> tiles, int zoom, int xMin, int xMax, int yMin, int yMax) {
        for (int x = xMin; x <= xMax; x++) {
            for (int y = yMin; y <= yMax; y++) {
                tiles.add(new PrefetchTile(zoom, x, y));
            }
        }
    }

    private static int latitudeToTileY(double latitude, int tileCount) {
        double sine = Math.sin(Math.toRadians(latitude));
        double mercatorY = Math.log((1 + sine) / (1 - sine));
        int y = (int) Math.floor((1 - mercatorY / (2 * Math.PI)) / 2 * tileCount);
        return Math.max(0, Math.min(tileCount - 1, y));
    }

    private static int longitudeToTileX(double longitude, int tileCount) {
        int x = (int) Math.floor((longitude + 180.0) / 360.0 * tileCount);
        return Math.floorMod(x, tileCount);
    }
    private static void printUsage() {
        System.out.println("VERA CLI\n" +
                "Commands:\n" +
                "  elevation --lat <v> --lon <v> [--zoom <z>] [--source <terrarium|copernicus>] [--cache <dir>]\n" +
                "                                           Print elevation (meters) at lat/lon\n" +
                "  los --lat <v> --lon <v> [--zoom <z>] [--agl <m>] [--radius <distance>]\n" +
                "      [--angleBins <n>] [--overlay] [--tfKey <key>] [--out <file>]\n" +
                "      [--name <name>]\n" +
                "      [--elevation-out <file>] [--curvature-out <file>] [--source <terrarium|copernicus>]\n" +
                "                                           Generate LOS image/KMZ (optionally overlay on TF)\n" +
                "                                           --angleBins is requested azimuth resolution; large grids may use more rays\n" +
                "                                           --radius accepts km, mi, m, or ft (for example 40km); default: 200km\n" +
                "                                           Bare integer radii remain supported as legacy tile-radius values\n" +
                "                                           Optionally export stitched elevation/curvature maps\n" +
                "                                           Output formats: .png, .jpg/.jpeg, .webp, .kmz\n" +
                "  prefetch --type <terrain|carto|thunder> --source <terrarium|copernicus> --minLat <v> --minLon <v> --maxLat <v> --maxLon <v>\n" +
                "           [--zoom <z> | --zMin <z> --zMax <z>] [--cache <dir>] [--tfKey <key>] [--threads <n>] [--compile]\n" +
                "                                           Bulk download tiles into local cache; --compile also builds decoded terrain data\n" +
                "  server [--port <n>] [--cache <dir>] [--output <dir>]\n" +
                "                                           Start the asynchronous viewshed HTTP API\n");
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        String key = null;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                key = a.substring(2);
                // flags (no value)
                if (key.equals("overlay") || key.equals("compile")) {
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
        if (cmd.equals("server")) {
            Map<String, String> p = parseArgs(args);
            int port = Integer.parseInt(p.getOrDefault("port", "7070"));
            new ApiServer(p.getOrDefault("output", "./api_output"),
                    p.getOrDefault("cache", "./terrain_cache")).start(port);
            return;
        }
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
                String radiusArgument = p.getOrDefault("radius", "200km");
                int radius = parseRadiusTiles(radiusArgument, lat, zoom);
                int angleBins = Integer.parseInt(p.getOrDefault("angleBins", "1440"));
                boolean overlay = Boolean.parseBoolean(p.getOrDefault("overlay", "false"));
                String out = p.getOrDefault("out", overlay ? "./los_overlay.png" : "./los.png");
                String name = p.get("name");
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
                int effectiveAngleBins = VisibilityEngine.effectiveRayCount(grid, lat, lon, angleBins);
                progress.bar("Running LoS raycasts", 0, effectiveAngleBins);
                ElevationService.ElevationGrid losMasked = ElevationService.applyLineOfSightMask(
                        grid, lat, lon, ElevationService.ObserverHeightMode.AGL, agl, cache, angleBins,
                        current -> progress.bar("Running LoS raycasts", current, effectiveAngleBins));
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
                            KmzExporter.writeViewshed(Path.of(out), image, losMasked, name);
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
                boolean compile = Boolean.parseBoolean(p.getOrDefault("compile", "false"));
                double minLat = Double.parseDouble(p.get("minLat"));
                double minLon = Double.parseDouble(p.get("minLon"));
                double maxLat = Double.parseDouble(p.get("maxLat"));
                double maxLon = Double.parseDouble(p.get("maxLon"));
                boolean hasZoom = p.containsKey("zoom");
                int zMin = hasZoom ? Integer.parseInt(p.get("zoom")) : Integer.parseInt(p.getOrDefault("zMin", "0"));
                int zMax = hasZoom ? Integer.parseInt(p.get("zoom")) : Integer.parseInt(p.getOrDefault("zMax", "0"));
                if (zMax < zMin) { int tmp = zMin; zMin = zMax; zMax = tmp; }
                String cacheDir = p.getOrDefault("cache", type.equals("carto") ? "./carto_cache" : (type.equals("thunder") ? "./thunder_cache" : "./terrain_cache"));
                int prefetchThreads = Integer.parseInt(p.getOrDefault("threads", String.valueOf(DEFAULT_PREFETCH_THREADS)));

                String tfKey = p.get("tfKey");
                if (!type.equals("terrain") && !type.equals("carto") && !type.equals("thunder")) {
                    System.err.println("Unknown prefetch type: " + type + " (expected terrain, carto, or thunder)");
                    progress.close();
                    return;
                }
                if (compile && !type.equals("terrain")) {
                    System.err.println("--compile requires --type terrain");
                    progress.close();
                    return;
                }
                if (type.equals("thunder") && (tfKey == null || tfKey.isBlank())) {
                    System.err.println("--type thunder requires --tfKey <key>");
                    progress.close();
                    return;
                }

                if (zMin < 0 || zMin > 30 || zMax > 30) {
                    System.err.println("Zoom must be between 0 and 30");
                    progress.close();
                    return;
                }
                if (prefetchThreads < 1) {
                    System.err.println("--threads must be at least 1");
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

                List<PrefetchTile> tiles = planPrefetchTiles(minLat, minLon, maxLat, maxLon, zMin, zMax);
                ThreadFactory downloadThreadFactory = runnable -> {
                    Thread thread = new Thread(runnable, "copernicus-download");
                    thread.setDaemon(true);
                    return thread;
                };
                ExecutorService downloads = type.equals("terrain") && provider == ElevationProvider.COPERNICUS_GLO30
                        ? Executors.newFixedThreadPool(prefetchThreads, downloadThreadFactory) : null;
                Executor downloadExecutor = downloads == null ? Runnable::run : downloads;
                TileCache terrainCache = type.equals("terrain")
                        ? new TileCache(cacheDir, provider, downloadExecutor) : null;
                Map<PrefetchTile, String> coverageByTile = new HashMap<>();
                if (terrainCache != null && provider == ElevationProvider.COPERNICUS_GLO30) {
                    Set<String> seenCoverage = new HashSet<>();
                    tiles.removeIf(tile -> {
                        String coverageKey = terrainCache.prefetchAvailableCoverageKey(
                                tile.zoom(), tile.x(), tile.y());
                        if (coverageKey.isEmpty()) return true;
                        boolean hasNewCell = false;
                        for (String cell : coverageKey.split(",")) {
                            if (seenCoverage.add(cell)) hasNewCell = true;
                        }
                        if (hasNewCell) coverageByTile.put(tile, coverageKey);
                        return !hasNewCell;
                    });
                }
                int total = tiles.size();
                int success = 0;
                int processed = 0;
                Set<String> coverageCells = new HashSet<>();
                coverageByTile.values().forEach(key -> {
                    for (String cell : key.split(",")) coverageCells.add(cell);
                });
                com.sidpatchy.Tile.CartoTileCache cartoCache = type.equals("carto")
                        ? new com.sidpatchy.Tile.CartoTileCache(cacheDir, p.get("cartoKey")) : null;
                ThunderforestTileCache thunderCache = type.equals("thunder")
                        ? new ThunderforestTileCache(cacheDir, tfKey) : null;
                progress.bar("Prefetching " + type, 0, total);
                Map<Integer, int[]> mapBounds = new HashMap<>();
                for (PrefetchTile tile : tiles) {
                    int[] bounds = mapBounds.computeIfAbsent(tile.zoom(), ignored ->
                            new int[]{tile.x(), tile.x(), tile.y(), tile.y()});
                    bounds[0] = Math.min(bounds[0], tile.x());
                    bounds[1] = Math.max(bounds[1], tile.x());
                    bounds[2] = Math.min(bounds[2], tile.y());
                    bounds[3] = Math.max(bounds[3], tile.y());
                }
                boolean parallelTerrain = terrainCache != null && provider == ElevationProvider.COPERNICUS_GLO30;
                ExecutorService workers = parallelTerrain ? Executors.newFixedThreadPool(prefetchThreads) : null;
                List<Future<Void>> futures = parallelTerrain ? new ArrayList<>(tiles.size()) : null;
                if (parallelTerrain) {
                    for (PrefetchTile tile : tiles) {
                        futures.add(workers.submit(() -> {
                            terrainCache.prefetchTile(tile.zoom(), tile.x(), tile.y());
                            return null;
                        }));
                    }
                }
                try {
                    for (int tileIndex = 0; tileIndex < tiles.size(); tileIndex++) {
                        PrefetchTile tile = tiles.get(tileIndex);
                        boolean succeeded = false;
                        try {
                            if (parallelTerrain) {
                                futures.get(tileIndex).get();
                            } else if (terrainCache != null) {
                                terrainCache.prefetchTile(tile.zoom(), tile.x(), tile.y());
                            } else if (cartoCache != null) {
                                cartoCache.getTile(tile.zoom(), tile.x(), tile.y());
                            } else if (thunderCache != null) {
                                thunderCache.getTile(tile.zoom(), tile.x(), tile.y());
                            } else {
                                throw new IllegalStateException("No tile cache configured for " + type);
                            }
                            if (compile) {
                                terrainCache.getElevationData(tile.zoom(), tile.x(), tile.y());
                            }
                            success++;
                            succeeded = true;
                        } catch (Exception e) {
                            progress.message("Failed z=" + tile.zoom() + " x=" + tile.x() + " y=" + tile.y()
                                    + ": " + (e.getMessage() == null ? e : e.getMessage()));
                        }
                        processed++;
                        if (provider == ElevationProvider.COPERNICUS_GLO30 && terrainCache != null) {
                            progress.coverageMap("Prefetching " + type, processed, total, coverageCells,
                                    coverageByTile.get(tile), succeeded);
                        } else {
                            int[] bounds = mapBounds.get(tile.zoom());
                            progress.tileMap("Prefetching " + type + " (z" + tile.zoom() + ")",
                                    processed, total, tile.zoom(), tile.x(), tile.y(),
                                    bounds[0], bounds[1], bounds[2], bounds[3], succeeded);
                        }
                    }
                } finally {
                    if (workers != null) workers.shutdown();
                    if (downloads != null) downloads.shutdown();
                }
                progress.complete("Prefetching " + type);
                System.out.println("Prefetch complete: " + success + "/" + total + " tiles ready ("
                        + (total - success) + " failed), cache=" + cacheDir);
                progress.close();
                break;
            }
            default:
                printUsage();
                progress.close();
        }
    }

    /**
     * Converts a user-facing physical radius to the whole-tile radius needed by the stitched grid.
     * Bare integers retain the old tile-radius syntax for existing scripts.
     */
    static int parseRadiusTiles(String value, double latitude, int zoom) {
        String normalized = value.trim().toLowerCase();
        if (normalized.matches("[+]?[0-9]+")) {
            try {
                int tiles = Integer.parseInt(normalized);
                if (tiles < 0) throw new IllegalArgumentException("radius must be >= 0");
                return tiles;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid tile radius: " + value, e);
            }
        }

        String unit;
        double multiplier;
        if (normalized.endsWith("km")) {
            unit = "km";
            multiplier = 1_000.0;
        } else if (normalized.endsWith("mi")) {
            unit = "mi";
            multiplier = 1_609.344;
        } else if (normalized.endsWith("m")) {
            unit = "m";
            multiplier = 1.0;
        } else if (normalized.endsWith("ft")) {
            unit = "ft";
            multiplier = 0.3048;
        } else {
            throw new IllegalArgumentException("Invalid radius '" + value
                    + "'; use a number followed by m, km, mi, or ft");
        }

        String number = normalized.substring(0, normalized.length() - unit.length()).trim();
        final double meters;
        try {
            meters = Double.parseDouble(number) * multiplier;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid radius: " + value, e);
        }
        if (!Double.isFinite(meters) || meters < 0.0) {
            throw new IllegalArgumentException("Radius must be a finite non-negative distance: " + value);
        }
        return ElevationService.tileRadiusForMeters(latitude, zoom, meters);
    }
}
