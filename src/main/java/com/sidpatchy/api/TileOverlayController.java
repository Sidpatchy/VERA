package com.sidpatchy.api;

import com.sidpatchy.Tile.CartoTileCache;
import com.sidpatchy.Tile.ElevationService;
import com.sidpatchy.Tile.ImageResponseCache;
import com.sidpatchy.Tile.ThunderforestTileCache;
import com.sidpatchy.Tile.TileCache;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

@RestController
@RequestMapping("/tiles")
public class TileOverlayController {
    private static final String DEFAULT_TERRAIN_CACHE = "./terrain_cache";
    private static final String DEFAULT_CARTO_CACHE = "./carto_cache";
    private static final String DEFAULT_TF_CACHE = "./thunder_cache";
    private static final String DEFAULT_IMAGE_CACHE = "./los_cache";

    // --- Utility: compute the center lat/lon of a z/x/y Web Mercator tile ---
    private static double[] tileCenterLatLon(int z, int x, int y) {
        double n = (double)(1 << z);
        double lon = (x + 0.5) / n * 360.0 - 180.0;
        double latRad = Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 * (y + 0.5) / n)));
        double lat = Math.toDegrees(latRad);
        return new double[]{lat, lon};
    }

    // --- Elevation view tile ---
    @GetMapping(value = "/elevationview/{z}/{x}/{y}.png")
    public ResponseEntity<byte[]> getElevationViewTile(
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y,
            @RequestParam(name = "terrainCache", required = false) String terrainCacheDir,
            @RequestParam(name = "min", required = false) Double min,
            @RequestParam(name = "max", required = false) Double max,
            @RequestParam(name = "cacheImages", defaultValue = "true") boolean cacheImages,
            @RequestParam(name = "imageCacheDir", required = false) String imageCacheDir,
            @RequestParam(name = "format", defaultValue = "png") String format,
            @RequestParam(name = "quality", required = false) Float quality
    ) throws IOException {
        TileCache terrainCache = new TileCache(terrainCacheDir != null ? terrainCacheDir : DEFAULT_TERRAIN_CACHE);
        ImageResponseCache.Format fmt = "webp".equalsIgnoreCase(format) ? ImageResponseCache.Format.WEBP : ImageResponseCache.Format.PNG;
        String imgCacheDir = imageCacheDir != null ? imageCacheDir : DEFAULT_IMAGE_CACHE;
        ImageResponseCache imgCache = new ImageResponseCache(imgCacheDir, "elev-tiles-v1");

        // Resolve effective min/max: request params > system properties > environment vars
        Double globalMin = readGlobalDouble("vera.elev.min", "VERA_ELEV_MIN");
        Double globalMax = readGlobalDouble("vera.elev.max", "VERA_ELEV_MAX");
        Double effMin = (min != null) ? min : globalMin;
        Double effMax = (max != null) ? max : globalMax;

        String ext = (fmt == ImageResponseCache.Format.WEBP) ? "webp" : "png";
        String relPath = "elev/" + z + "/" + x + "/" + y + "." + ext;
        if (cacheImages) {
            byte[] cached = imgCache.tryReadAt(relPath);
            if (cached != null) {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(fmt == ImageResponseCache.Format.WEBP ? MediaType.valueOf("image/webp") : MediaType.IMAGE_PNG);
                headers.setContentLength(cached.length);
                return new ResponseEntity<>(cached, headers, HttpStatus.OK);
            }
        }

        double[] center = tileCenterLatLon(z, x, y);
        ElevationService.ElevationGrid grid = ElevationService.getElevationGridAround(center[0], center[1], z, 0, ElevationService.AreaShape.SQUARE, terrainCache);
        BufferedImage image;
        if (effMin != null && effMax != null) {
            image = ElevationService.elevationGridToImage(grid, effMin, effMax);
        } else {
            image = ElevationService.elevationGridToImage(grid);
        }

        byte[] bytes;
        if (cacheImages) {
            bytes = imgCache.encodeAndWriteAt(relPath, image, fmt, quality);
        } else {
            try {
                bytes = ImageResponseCache.encode(image, fmt, quality);
            } catch (IOException e) {
                bytes = ImageResponseCache.encode(image, ImageResponseCache.Format.PNG, null);
                fmt = ImageResponseCache.Format.PNG;
            }
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(fmt == ImageResponseCache.Format.WEBP ? MediaType.valueOf("image/webp") : MediaType.IMAGE_PNG);
        headers.setContentLength(bytes.length);
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }

    // --- LOS overlay tile (multi-observer supported) ---
    @GetMapping(value = "/los/{z}/{x}/{y}.png")
    public ResponseEntity<byte[]> getLosTile(
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y,
            @RequestParam(name = "observers", required = false) String observers,
            @RequestParam(name = "agl", defaultValue = "10.0") double agl,
            @RequestParam(name = "angleBins", defaultValue = "720") int angleBins,
            @RequestParam(name = "base", defaultValue = "none") String base,
            @RequestParam(name = "terrainCache", required = false) String terrainCacheDir,
            @RequestParam(name = "cartoCache", required = false) String cartoCacheDir,
            @RequestParam(name = "cartoKey", required = false) String cartoApiKey,
            @RequestParam(name = "tfCache", required = false) String tfCacheDir,
            @RequestParam(name = "tfKey", required = false) String thunderforestApiKey,
            @RequestParam(name = "color", required = false) String colorHex,
            @RequestParam(name = "cacheImages", defaultValue = "true") boolean cacheImages,
            @RequestParam(name = "imageCacheDir", required = false) String imageCacheDir,
            @RequestParam(name = "format", defaultValue = "png") String format,
            @RequestParam(name = "quality", required = false) Float quality,
            @RequestParam(name = "saveGrid", defaultValue = "false") boolean saveGrid,
            @RequestParam(name = "overlayId", required = false) String overlayId
    ) throws IOException {
        ElevationService.ObserverHeightMode obsMode = ElevationService.ObserverHeightMode.AGL;
        Double obsHeight = agl; // for AGL defaults
        // If overlayId provided, resolve it; otherwise require observers
        Integer gridSizeLimit = null;
        if ((observers == null || observers.isBlank())) {
            if (overlayId != null && !overlayId.isBlank()) {
                OverlayRegistry.OverlayDef def = OverlayRegistry.getInstance().get(overlayId);
                if (def == null) {
                    return ResponseEntity.status(HttpStatus.NOT_FOUND)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"error\":\"overlayId not found\"}".getBytes());
                }
                observers = def.observers;
                // Use mode/height if provided; fall back to stored agl
                obsMode = def.mode != null ? def.mode : ElevationService.ObserverHeightMode.AGL;
                obsHeight = def.heightMeters != null ? def.heightMeters : def.agl;
                agl = def.agl;
                angleBins = def.angleBins;
                base = def.base;
                if (def.colorHex != null && !def.colorHex.isBlank()) colorHex = def.colorHex;
                if (def.terrainCache != null) terrainCacheDir = def.terrainCache;
                if (def.cartoCache != null) cartoCacheDir = def.cartoCache;
                if (def.tfCache != null) tfCacheDir = def.tfCache;
                if (def.gridSize != null) gridSizeLimit = def.gridSize;
            } else {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"Provide observers or overlayId\"}".getBytes());
            }
        }
        TileCache terrainCache = new TileCache(terrainCacheDir != null ? terrainCacheDir : DEFAULT_TERRAIN_CACHE);
        ImageResponseCache.Format fmt = "webp".equalsIgnoreCase(format) ? ImageResponseCache.Format.WEBP : ImageResponseCache.Format.PNG;
        String imgCacheDir = imageCacheDir != null ? imageCacheDir : DEFAULT_IMAGE_CACHE;
        ImageResponseCache imgCache = new ImageResponseCache(imgCacheDir, "los-tiles-v1");

        // New: path-based cache under los/{overlayKey}/{z}/{x}/{y}.{ext}
        String overlayKey = (overlayId != null && !overlayId.isBlank()) ? overlayId : ("adhoc-" + com.sidpatchy.Tile.ImageResponseCache.sha256(observers));
        String ext = (fmt == ImageResponseCache.Format.WEBP) ? "webp" : "png";
        String relPath = "los/" + overlayKey + "/" + z + "/" + x + "/" + y + "." + ext;
        if (cacheImages) {
            byte[] cached2 = imgCache.tryReadAt(relPath);
            if (cached2 != null) {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(fmt == ImageResponseCache.Format.WEBP ? MediaType.valueOf("image/webp") : MediaType.IMAGE_PNG);
                headers.setContentLength(cached2.length);
                return new ResponseEntity<>(cached2, headers, HttpStatus.OK);
            }
        }

        // If overlay has a grid size limit, serve transparent tile outside radius without computing LOS
        if (gridSizeLimit != null && gridSizeLimit > 0) {
            int radius = gridSizeLimit / 2; // e.g., 20 -> 10
            java.util.List<double[]> obsList = parseObservers(observers);
            if (!obsList.isEmpty()) {
                double olat = obsList.get(0)[0];
                double olon = obsList.get(0)[1];
                int nForZ = 1 << z;
                double xx = (olon + 180.0) / 360.0 * nForZ;
                double latRad = Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, olat)));
                double yy = (1.0 - (Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI)) / 2.0 * nForZ;
                int cx = (int)Math.floor(((xx % nForZ) + nForZ) % nForZ);
                int cy = (int)Math.floor(Math.max(0.0, Math.min(Math.nextDown((double)nForZ), yy)));
                int dx = (int)Math.round(((cx - x + nForZ/2.0) % nForZ) - nForZ/2.0);
                int dy = cy - y;
                if (Math.max(Math.abs(dx), Math.abs(dy)) > radius) {
                    byte[] blank = getTransparentTile(imgCache, fmt, quality);
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(fmt == ImageResponseCache.Format.WEBP ? MediaType.valueOf("image/webp") : MediaType.IMAGE_PNG);
                    headers.setContentLength(blank.length);
                    return new ResponseEntity<>(blank, headers, HttpStatus.OK);
                }
            }
        }

        // Determine required region radius (in tiles) to include all observers relative to requested tile
        int n = 1 << z;
        int requiredRadius = 0;
        String[] obs = observers.split(";");
        for (String o : obs) {
            String[] parts = o.split(",");
            if (parts.length != 2) continue;
            try {
                double olat = Double.parseDouble(parts[0].trim());
                double olon = Double.parseDouble(parts[1].trim());
                // compute tile indices of observer
                double xx = (olon + 180.0) / 360.0 * n;
                double latRad = Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, olat)));
                double yy = (1.0 - (Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI)) / 2.0 * n;
                int ox = (int)Math.floor(((xx % n) + n) % n);
                int oy = (int)Math.floor(Math.max(0.0, Math.min(Math.nextDown((double)n), yy)));
                int dx = (int)Math.round(((ox - x + n/2.0) % n) - n/2.0); // shortest wrapped distance
                int dy = oy - y;
                requiredRadius = Math.max(requiredRadius, Math.max(Math.abs(dx), Math.abs(dy)));
            } catch (NumberFormatException ignore) { }
        }
        // Add a small margin of 1 tile, then clamp by configured limits to avoid massive grids
        int effRadius = Math.max(0, requiredRadius + 1);
        // Global cap via system property or environment variable
        {
            int def = 10; // sensible default cap => up to 21x21 tiles
            String sProp = System.getProperty("los.maxRadius");
            String sEnv = (sProp == null || sProp.isBlank()) ? System.getenv("LOS_MAX_RADIUS") : null;
            int v = def;
            try {
                if (sProp != null && !sProp.isBlank()) v = Integer.parseInt(sProp.trim());
                else if (sEnv != null && !sEnv.isBlank()) v = Integer.parseInt(sEnv.trim());
            } catch (Exception ignored) {}
            if (v < 0) v = 0;
            if (v > 25) v = 25; // hard upper bound
            if (effRadius > v) effRadius = v;
        }
        // If overlay has an explicit grid size, clamp to its radius
        if (gridSizeLimit != null && gridSizeLimit > 0) {
            int rLim = Math.max(0, gridSizeLimit / 2);
            if (effRadius > rLim) effRadius = rLim;
        }

        // Build regional elevation grid centered on requested tile
        double[] center = tileCenterLatLon(z, x, y);
        ElevationService.ElevationGrid regionGrid = ElevationService.getElevationGridAround(center[0], center[1], z, effRadius, ElevationService.AreaShape.SQUARE, terrainCache);

        // Merge LOS across all observers on the region grid
        double[][] merged = new double[regionGrid.height][regionGrid.width];
        for (int i = 0; i < merged.length; i++) {
            java.util.Arrays.fill(merged[i], Double.NaN);
        }
        for (String o : obs) {
            String[] parts = o.split(",");
            if (parts.length != 2) continue;
            double lat, lon;
            try {
                lat = Double.parseDouble(parts[0].trim());
                lon = Double.parseDouble(parts[1].trim());
            } catch (NumberFormatException nfe) {
                continue;
            }
            ElevationService.ElevationGrid losMasked = ElevationService.applyLineOfSightMask(
                    regionGrid, lat, lon, obsMode, obsHeight, terrainCache, angleBins);
            for (int py = 0; py < regionGrid.height; py++) {
                double[] srcRow = losMasked.data[py];
                double[] dstRow = merged[py];
                double[] baseRow = regionGrid.data[py];
                for (int px = 0; px < regionGrid.width; px++) {
                    if (!Double.isNaN(srcRow[px])) {
                        dstRow[px] = baseRow[px];
                    }
                }
            }
        }
        ElevationService.ElevationGrid mergedRegion = new ElevationService.ElevationGrid(
                merged, regionGrid.tileSize, regionGrid.tilesWide, regionGrid.tilesHigh, regionGrid.zoom, regionGrid.centerTileX, regionGrid.centerTileY);

        // Optionally save the full region grid image for debugging/analysis
        if (saveGrid) {
            BufferedImage dbg = ElevationService.elevationGridToImage(mergedRegion);
            java.nio.file.Path dir = java.nio.file.Paths.get(imgCacheDir, "grids");
            java.nio.file.Files.createDirectories(dir);
            String safe = (colorHex == null ? "" : colorHex.replace("#",""));
            java.nio.file.Path out = dir.resolve(String.format("los_region_z%d_x%d_y%d_bins%d_agl%.1f_%s.png", z, x, y, angleBins, agl, safe));
            javax.imageio.ImageIO.write(dbg, "png", out.toFile());
        }

        // Crop center 1x1 tile from region for output
        ElevationService.ElevationGrid cropped = ElevationService.cropCenterTile(mergedRegion);
        
        // If no visible cells within this tile and base is none, serve a shared transparent tile without caching per-tile
        String baseNorm = base == null ? "none" : base.toLowerCase();
        if ("none".equals(baseNorm) && !gridHasAnyVisible(cropped)) {
            byte[] blank = getTransparentTile(imgCache, fmt, quality);
            HttpHeaders headers2 = new HttpHeaders();
            headers2.setContentType(fmt == ImageResponseCache.Format.WEBP ? MediaType.valueOf("image/webp") : MediaType.IMAGE_PNG);
            headers2.setContentLength(blank.length);
            return new ResponseEntity<>(blank, headers2, HttpStatus.OK);
        }

        BufferedImage outImg;
        // Determine base layer sized 256x256
        Color overlayColor = parseColor(colorHex, new Color(255, 0, 0, 120));
        switch (baseNorm) {
            case "carto": {
                CartoTileCache carto = new CartoTileCache(cartoCacheDir != null ? cartoCacheDir : DEFAULT_CARTO_CACHE, cartoApiKey);
                java.io.File f = carto.getTile(z, x, y);
                BufferedImage baseImg = javax.imageio.ImageIO.read(f);
                outImg = ElevationService.overlayLosOnBase(cropped, baseImg, overlayColor);
                break;
            }
            case "thunder": {
                if (thunderforestApiKey == null || thunderforestApiKey.isBlank()) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("{\"error\":\"base=thunder requires tfKey\"}".getBytes());
                }
                ThunderforestTileCache tf = new ThunderforestTileCache(tfCacheDir != null ? tfCacheDir : DEFAULT_TF_CACHE, thunderforestApiKey);
                java.io.File f = tf.getTile(z, x, y);
                BufferedImage baseImg = javax.imageio.ImageIO.read(f);
                outImg = ElevationService.overlayLosOnBase(cropped, baseImg, overlayColor);
                break;
            }
            case "none":
            default: {
                BufferedImage baseImg = new BufferedImage(cropped.width, cropped.height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = baseImg.createGraphics();
                try { g.setComposite(AlphaComposite.Clear); g.fillRect(0,0,baseImg.getWidth(), baseImg.getHeight()); }
                finally { g.dispose(); }
                outImg = ElevationService.overlayLosOnBase(cropped, baseImg, overlayColor);
                break;
            }
        }

        byte[] bytes;
        if (cacheImages) {
            bytes = imgCache.encodeAndWriteAt(relPath, outImg, fmt, quality);
        } else {
            try {
                bytes = ImageResponseCache.encode(outImg, fmt, quality);
            } catch (IOException e) {
                bytes = ImageResponseCache.encode(outImg, ImageResponseCache.Format.PNG, null);
                fmt = ImageResponseCache.Format.PNG;
            }
        }
        HttpHeaders headers2 = new HttpHeaders();
        headers2.setContentType(fmt == ImageResponseCache.Format.WEBP ? MediaType.valueOf("image/webp") : MediaType.IMAGE_PNG);
        headers2.setContentLength(bytes.length);
        return new ResponseEntity<>(bytes, headers2, HttpStatus.OK);
    }

    private static Color parseColor(String hex, Color fallback) {
        if (hex == null || hex.isBlank()) return fallback;
        String s = hex.trim();
        if (s.startsWith("#")) s = s.substring(1);
        try {
            if (s.length() == 6) {
                int r = Integer.parseInt(s.substring(0, 2), 16);
                int g = Integer.parseInt(s.substring(2, 4), 16);
                int b = Integer.parseInt(s.substring(4, 6), 16);
                return new Color(r, g, b, 120);
            } else if (s.length() == 8) {
                int r = Integer.parseInt(s.substring(0, 2), 16);
                int g = Integer.parseInt(s.substring(2, 4), 16);
                int b = Integer.parseInt(s.substring(4, 6), 16);
                int a = Integer.parseInt(s.substring(6, 8), 16);
                return new Color(r, g, b, a);
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    // Parse observers string into list of [lat,lon]. Accepts separators ';', '|', whitespace, and newlines.
    private static java.util.List<double[]> parseObservers(String observers) {
        java.util.List<double[]> list = new java.util.ArrayList<>();
        if (observers == null) return list;
        String s = observers.trim();
        if (s.isEmpty()) return list;
        String[] toks = s.split("[;|\n\r\t ]+");
        for (String tok : toks) {
            String t = tok.trim();
            if (t.isEmpty()) continue;
            String[] parts = t.split(",");
            if (parts.length != 2) continue;
            try {
                double lat = Double.parseDouble(parts[0].trim());
                double lon = Double.parseDouble(parts[1].trim());
                list.add(new double[]{lat, lon});
            } catch (NumberFormatException ignored) {}
        }
        return list;
    }

    private static Double readGlobalDouble(String sysProp, String envVar) {
        // System property has precedence over environment variable
        String v = System.getProperty(sysProp);
        if (v == null || v.isBlank()) {
            v = System.getenv(envVar);
        }
        if (v == null || v.isBlank()) return null;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --- Bulk pre-render LOS tiles over a bounding box ---
    @GetMapping(value = "/los/prefetch", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> prefetchLos(
            @RequestParam(name = "minLat") double minLat,
            @RequestParam(name = "minLon") double minLon,
            @RequestParam(name = "maxLat") double maxLat,
            @RequestParam(name = "maxLon") double maxLon,
            @RequestParam(name = "zoom", required = false) Integer zoom,
            @RequestParam(name = "zMin", required = false) Integer zMin,
            @RequestParam(name = "zMax", required = false) Integer zMax,
            @RequestParam(name = "observers", required = false) String observers,
            @RequestParam(name = "agl", required = false) Double agl,
            @RequestParam(name = "angleBins", required = false) Integer angleBins,
            @RequestParam(name = "base", required = false) String base,
            @RequestParam(name = "terrainCache", required = false) String terrainCacheDir,
            @RequestParam(name = "cartoCache", required = false) String cartoCacheDir,
            @RequestParam(name = "cartoKey", required = false) String cartoApiKey,
            @RequestParam(name = "tfCache", required = false) String tfCacheDir,
            @RequestParam(name = "tfKey", required = false) String thunderforestApiKey,
            @RequestParam(name = "color", required = false) String colorHex,
            @RequestParam(name = "cacheImages", defaultValue = "true") boolean cacheImages,
            @RequestParam(name = "imageCacheDir", required = false) String imageCacheDir,
            @RequestParam(name = "format", defaultValue = "png") String format,
            @RequestParam(name = "quality", required = false) Float quality,
            @RequestParam(name = "overlayId", required = false) String overlayId,
            @RequestParam(name = "gridSize", required = false) Integer gridSize
    ) throws IOException {
        // If overlayId is provided, fill in any missing parameters from registry
        if (overlayId != null && !overlayId.isBlank()) {
            OverlayRegistry.OverlayDef def = OverlayRegistry.getInstance().get(overlayId);
            if (def != null) {
                if (observers == null || observers.isBlank()) observers = def.observers;
                if (agl == null) {
                    if (def.mode == ElevationService.ObserverHeightMode.AGL && def.heightMeters != null) agl = def.heightMeters;
                    else agl = def.agl;
                }
                if (angleBins == null) angleBins = def.angleBins;
                if (base == null || base.isBlank()) base = def.base;
                if (colorHex == null) colorHex = def.colorHex;
                if (terrainCacheDir == null) terrainCacheDir = def.terrainCache;
                if (cartoCacheDir == null) cartoCacheDir = def.cartoCache;
                if (tfCacheDir == null) tfCacheDir = def.tfCache;
            }
        }
        if (observers == null || observers.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("{\"error\":\"observers required\"}");
        }
        // Resolve effective primitives/defaults now that overlay defaults may be applied
        double effAgl = (agl != null) ? agl : 10.0;
        int effAngleBins = (angleBins != null) ? angleBins : 18000;
        String effBase = (base != null && !base.isBlank()) ? base : "none";
        // Normalize inputs
        minLat = Math.max(-85.05112878, Math.min(85.05112878, minLat));
        maxLat = Math.max(-85.05112878, Math.min(85.05112878, maxLat));
        if (maxLat < minLat) { double t = minLat; minLat = maxLat; maxLat = t; }
        while (minLon < -180) { minLon += 360; }
        while (minLon > 180) { minLon -= 360; }
        while (maxLon < -180) { maxLon += 360; }
        while (maxLon > 180) { maxLon -= 360; }

        int zLo, zHi;
        if (zoom != null) { zLo = zoom; zHi = zoom; }
        else {
            if (zMin == null || zMax == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("{\"error\":\"Provide zoom or zMin & zMax\"}");
            }
            zLo = Math.min(zMin, zMax);
            zHi = Math.max(zMin, zMax);
        }

        // Common dependencies
        TileCache terrainCache = new TileCache(terrainCacheDir != null ? terrainCacheDir : DEFAULT_TERRAIN_CACHE);
        CartoTileCache carto = null;
        ThunderforestTileCache tf = null;
        if ("carto".equalsIgnoreCase(base)) {
            carto = new CartoTileCache(cartoCacheDir != null ? cartoCacheDir : DEFAULT_CARTO_CACHE, cartoApiKey);
        } else if ("thunder".equalsIgnoreCase(base)) {
            thunderforestApiKey = ThunderforestTileCache.resolveApiKey(thunderforestApiKey);
            if (thunderforestApiKey == null || thunderforestApiKey.isBlank()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("{\"error\":\"base=thunder requires tfKey\"}");
            }
            tf = new ThunderforestTileCache(tfCacheDir != null ? tfCacheDir : DEFAULT_TF_CACHE, thunderforestApiKey);
        }
        ImageResponseCache.Format fmt = "webp".equalsIgnoreCase(format) ? ImageResponseCache.Format.WEBP : ImageResponseCache.Format.PNG;
        String imgCacheDir = imageCacheDir != null ? imageCacheDir : DEFAULT_IMAGE_CACHE;
        ImageResponseCache imgCache = new ImageResponseCache(imgCacheDir, "los-tiles-v1");

        int total = 0;
        java.util.concurrent.atomic.AtomicInteger renderedAtomic = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.List<int[]> tileJobs = new java.util.ArrayList<>(); // each: {z,x,y}

        for (int z = zLo; z <= zHi; z++) {
            int n = 1 << z;
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

            // Determine overlay key and optional radius/center for limiting and path-based storage
            String overlayKey = (overlayId != null && !overlayId.isBlank()) ? overlayId : ("adhoc-" + com.sidpatchy.Tile.ImageResponseCache.sha256(observers == null ? "" : observers));
            Integer radiusLimit = (gridSize != null && gridSize > 0) ? (gridSize / 2) : null;
            Integer centerTileX = null;
            Integer centerTileY = null;
            java.util.List<double[]> obsListZ = parseObservers(observers);
            if (!obsListZ.isEmpty()) {
                double olat = obsListZ.get(0)[0];
                double olon = obsListZ.get(0)[1];
                double xx0 = (olon + 180.0) / 360.0 * n;
                double latRad0 = Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, olat)));
                double yy0 = (1.0 - (Math.log(Math.tan(latRad0) + 1.0 / Math.cos(latRad0)) / Math.PI)) / 2.0 * n;
                centerTileX = (int) Math.floor(((xx0 % n) + n) % n);
                centerTileY = (int) Math.floor(Math.max(0.0, Math.min(Math.nextDown((double) n), yy0)));
            }

            int yMin = latToY.apply(maxLat);
            int yMax = latToY.apply(minLat);

            // Hard cap the grid height to avoid planet-wide elevation downloads
            int maxAxis;
            {
                int def = 20; // default sensible cap
                String sProp = System.getProperty("los.prefetch.maxAxis");
                String sEnv = (sProp == null || sProp.isBlank()) ? System.getenv("LOS_PREFETCH_MAX_AXIS") : null;
                int v = def;
                try { if (sProp != null && !sProp.isBlank()) v = Integer.parseInt(sProp.trim()); else if (sEnv != null && !sEnv.isBlank()) v = Integer.parseInt(sEnv.trim()); } catch (Exception ignored) {}
                if (v < 1) v = 1;
                if (v > 30) v = 30; // absolute upper bound
                maxAxis = v;
            }
            int height = yMax - yMin + 1;
            if (height > maxAxis) {
                int mid = (yMin + yMax) / 2;
                yMin = Math.max(0, mid - maxAxis / 2);
                yMax = Math.min(n - 1, yMin + maxAxis - 1);
            }

            double startLon = minLon;
            double endLon = maxLon;
            boolean wraps = endLon < startLon;
            int segments = wraps ? 2 : 1;
            for (int seg = 0; seg < segments; seg++) {
                double segMinLon = (seg == 0) ? startLon : -180.0;
                double segMaxLon = (seg == 0) ? 180.0 : endLon;
                int xMin = lonToX.apply(segMinLon);
                int xMax = lonToX.apply(segMaxLon);
                boolean fullWorld = Math.abs(segMaxLon - segMinLon) >= 360.0 - 1e-9;
                if (fullWorld) { xMin = 0; xMax = n - 1; }

                // Cap the grid width per segment
                int width = xMax - xMin + 1;
                if (width > maxAxis) {
                    int midX = (xMin + xMax) / 2;
                    xMin = Math.max(0, midX - maxAxis / 2);
                    xMax = Math.min(n - 1, xMin + maxAxis - 1);
                }

                // Determine if this rectangle can be handled via a single composite grid (10x10 default)
                int compositeMaxTiles;
                {
                    int def = 100; // 10x10 by default
                    String sProp = System.getProperty("los.prefetch.compositeMaxTiles");
                    String sEnv = (sProp == null || sProp.isBlank()) ? System.getenv("LOS_PREFETCH_COMPOSITE_MAX_TILES") : null;
                    int v = def;
                    try { if (sProp != null && !sProp.isBlank()) v = Integer.parseInt(sProp.trim()); else if (sEnv != null && !sEnv.isBlank()) v = Integer.parseInt(sEnv.trim()); } catch (Exception ignored) {}
                    if (v < 1) v = 1; if (v > 400) v = 400; compositeMaxTiles = v;
                }

                // Process rectangles: either single [xMin..xMax] or wrapped into two ranges
                if (!fullWorld && xMax < xMin) {
                    int a1 = 0, b1 = xMax;
                    int a2 = xMin, b2 = n - 1;
                    total += handleRectCompositeAndEnqueue(z, yMin, yMax, a1, b1, maxAxis, compositeMaxTiles, observers, effAgl, effAngleBins, effBase, colorHex, thunderforestApiKey, cartoApiKey, cartoCacheDir, tfCacheDir, terrainCache, carto, tf, imgCache, fmt, quality, renderedAtomic, tileJobs, overlayKey, radiusLimit, centerTileX, centerTileY);
                    total += handleRectCompositeAndEnqueue(z, yMin, yMax, a2, b2, maxAxis, compositeMaxTiles, observers, effAgl, effAngleBins, effBase, colorHex, thunderforestApiKey, cartoApiKey, cartoCacheDir, tfCacheDir, terrainCache, carto, tf, imgCache, fmt, quality, renderedAtomic, tileJobs, overlayKey, radiusLimit, centerTileX, centerTileY);
                } else {
                    total += handleRectCompositeAndEnqueue(z, yMin, yMax, xMin, xMax, maxAxis, compositeMaxTiles, observers, effAgl, effAngleBins, effBase, colorHex, thunderforestApiKey, cartoApiKey, cartoCacheDir, tfCacheDir, terrainCache, carto, tf, imgCache, fmt, quality, renderedAtomic, tileJobs, overlayKey, radiusLimit, centerTileX, centerTileY);
                }
            }
        }

        int threads;
        {
            int def = Math.max(2, Runtime.getRuntime().availableProcessors());
            String sProp = System.getProperty("los.prefetch.threads");
            String sEnv = (sProp == null || sProp.isBlank()) ? System.getenv("LOS_PREFETCH_THREADS") : null;
            int v = def;
            try { if (sProp != null && !sProp.isBlank()) v = Integer.parseInt(sProp.trim()); else if (sEnv != null && !sEnv.isBlank()) v = Integer.parseInt(sEnv.trim()); } catch (Exception ignored) {}
            if (v < 1) v = 1; if (v > 64) v = 64; threads = v;
        }
        final String obsFinal = observers;
        final double aglFinal = effAgl;
        final int angleBinsFinal = effAngleBins;
        final String baseFinal = effBase;
        final TileCache terrainCacheFinal = terrainCache;
        final CartoTileCache cartoFinal = carto;
        final String cartoKeyFinal = cartoApiKey;
        final ThunderforestTileCache tfFinal = tf;
        final String tfKeyFinal = thunderforestApiKey;
        final String colorFinal = colorHex;
        final Float qualFinal = quality;

        java.util.concurrent.ExecutorService exec = java.util.concurrent.Executors.newFixedThreadPool(threads);
        for (int[] job : tileJobs) {
            final int jz = job[0], jx = job[1], jy = job[2];
            exec.submit(() -> {
                try {
                    if (generateLosTileIfMissing(imgCache, fmt, jz, jx, jy, obsFinal, aglFinal, angleBinsFinal, baseFinal, terrainCacheFinal, cartoFinal, cartoKeyFinal, tfFinal, tfKeyFinal, colorFinal, qualFinal)) {
                        renderedAtomic.incrementAndGet();
                    }
                } catch (IOException ignored) {}
            });
        }
        exec.shutdown();
        try {
            long timeoutSec = Math.max(30, Math.min(600, total * 2L)); // scale with job count, clamp 30s..10m
            exec.awaitTermination(timeoutSec, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}

        int rendered = renderedAtomic.get();

        String json = "{" +
                "\"total\":" + total + "," +
                "\"rendered\":" + rendered +
                "}";
        return ResponseEntity.ok(json);
    }

    // Helper for prefetch: either render a whole rectangle using one composite grid (10x10 default)
    // or enqueue per-tile jobs when too large. Returns number of tiles processed or enqueued.
    private int handleRectCompositeAndEnqueue(
            int z,
            int yMin,
            int yMax,
            int a,
            int b,
            int maxAxis,
            int compositeMaxTiles,
            String observers,
            double effAgl,
            int effAngleBins,
            String base,
            String colorHex,
            String thunderforestApiKey,
            String cartoApiKey,
            String cartoCacheDir,
            String tfCacheDir,
            TileCache terrainCache,
            CartoTileCache carto,
            ThunderforestTileCache tf,
            ImageResponseCache imgCache,
            ImageResponseCache.Format fmt,
            Float quality,
            java.util.concurrent.atomic.AtomicInteger renderedAtomic,
            java.util.List<int[]> tileJobs,
            String overlayKey,
            Integer radiusLimit,
            Integer centerTileX,
            Integer centerTileY
    ) throws IOException {
        int n = 1 << z;
        // Cap range width if needed
        if (b - a + 1 > maxAxis) {
            int midX2 = (a + b) / 2;
            a = Math.max(0, midX2 - maxAxis / 2);
            b = Math.min(n - 1, a + maxAxis - 1);
        }
        int widthLocal = b - a + 1;
        int heightLocal = yMax - yMin + 1;
        long tilesCount = (long) widthLocal * (long) heightLocal;
        boolean canComposite = tilesCount <= compositeMaxTiles;
        int totalLocal = 0;
        // If observers parse to empty, skip composite to let per-tile path decide and avoid transparent outputs
        if (parseObservers(observers).isEmpty()) {
            canComposite = false;
        }
        if (canComposite) {
            try {
                int cxTile = (a + b) / 2;
                int cyTile = (yMin + yMax) / 2;
                double[] ctr = tileCenterLatLon(z, cxTile, cyTile);
                int halfW = (int) Math.ceil(widthLocal / 2.0);
                int halfH = (int) Math.ceil(heightLocal / 2.0);
                int radiusTiles = Math.max(halfW, halfH);
                // Safety clamp to default 10x10 grid
                radiusTiles = Math.max(0, Math.min(10, radiusTiles));
                ElevationService.ElevationGrid region = ElevationService.getElevationGridAround(ctr[0], ctr[1], z, radiusTiles, ElevationService.AreaShape.SQUARE, terrainCache);

                java.util.List<double[]> obsList = parseObservers(observers);
                double[][] merged = new double[region.height][region.width];
                for (int i = 0; i < merged.length; i++) java.util.Arrays.fill(merged[i], Double.NaN);
                for (double[] ob : obsList) {
                    double lat = ob[0];
                    double lon = ob[1];
                    ElevationService.ElevationGrid losMasked = ElevationService.applyLineOfSightMask(
                            region, lat, lon, ElevationService.ObserverHeightMode.AGL, effAgl, terrainCache, effAngleBins);
                    for (int py = 0; py < region.height; py++) {
                        double[] srcRow = losMasked.data[py];
                        double[] dstRow = merged[py];
                        double[] baseRow = region.data[py];
                        for (int px = 0; px < region.width; px++) {
                            if (!Double.isNaN(srcRow[px])) dstRow[px] = baseRow[px];
                        }
                    }
                }
                ElevationService.ElevationGrid mergedRegion = new ElevationService.ElevationGrid(
                        merged, region.tileSize, region.tilesWide, region.tilesHigh, region.zoom, region.centerTileX, region.centerTileY);

                Color overlayColor = parseColor(colorHex, new Color(255, 0, 0, 120));
                String baseNorm2 = base == null ? "none" : base.toLowerCase();
                for (int xx = a; xx <= b; xx++) {
                    for (int yy = yMin; yy <= yMax; yy++) {
                        totalLocal++;
                        int dxTiles = xx - cxTile;
                        int dyTiles = yy - cyTile;
                        ElevationService.ElevationGrid cropped = ElevationService.cropTileAt(mergedRegion, dxTiles, dyTiles);
                        // Skip writing per-tile cache if overlay has no visible cells and base is none
                        if ("none".equals(baseNorm2) && !gridHasAnyVisible(cropped)) {
                            continue;
                        }
                        String key = String.join("|",
                                "type=losTile",
                                "z=" + z,
                                "x=" + xx,
                                "y=" + yy,
                                "observers=" + observers,
                                "agl=" + effAgl,
                                "bins=" + effAngleBins,
                                "base=" + base,
                                "tfPresent=" + (thunderforestApiKey != null && !thunderforestApiKey.isBlank()),
                                "color=" + (colorHex == null ? "" : colorHex)
                        );
                        byte[] cached = imgCache.tryRead(key, fmt);
                        if (cached != null) { continue; }
                        BufferedImage outImg;
                        switch (baseNorm2) {
                            case "carto": {
                                CartoTileCache cartoUse = carto;
                                if (cartoUse == null) cartoUse = new CartoTileCache(cartoCacheDir != null ? cartoCacheDir : DEFAULT_CARTO_CACHE, cartoApiKey);
                                java.io.File f = cartoUse.getTile(z, xx, yy);
                                BufferedImage baseImg = javax.imageio.ImageIO.read(f);
                                outImg = ElevationService.overlayLosOnBase(cropped, baseImg, overlayColor);
                                break;
                            }
                            case "thunder": {
                                ThunderforestTileCache tfUse = tf;
                                if (tfUse == null && thunderforestApiKey != null && !thunderforestApiKey.isBlank()) tfUse = new ThunderforestTileCache(tfCacheDir != null ? tfCacheDir : DEFAULT_TF_CACHE, thunderforestApiKey);
                                if (tfUse == null) { continue; }
                                java.io.File f = tfUse.getTile(z, xx, yy);
                                BufferedImage baseImg = javax.imageio.ImageIO.read(f);
                                outImg = ElevationService.overlayLosOnBase(cropped, baseImg, overlayColor);
                                break;
                            }
                            case "gray": case "grey": case "elev": case "elevation": {
                                BufferedImage baseImg = ElevationService.elevationGridToImage(cropped);
                                outImg = ElevationService.overlayLosOnBase(cropped, baseImg, overlayColor);
                                break;
                            }
                            case "none": default: {
                                BufferedImage baseImg = new BufferedImage(cropped.width, cropped.height, BufferedImage.TYPE_INT_ARGB);
                                Graphics2D g = baseImg.createGraphics();
                                try { g.setComposite(AlphaComposite.Clear); g.fillRect(0,0,baseImg.getWidth(), baseImg.getHeight()); }
                                finally { g.dispose(); }
                                outImg = ElevationService.overlayLosOnBase(cropped, baseImg, overlayColor);
                                break;
                            }
                        }
                        imgCache.encodeAndWrite(key, outImg, fmt, quality);
                        renderedAtomic.incrementAndGet();
                    }
                }
                return totalLocal;
            } catch (IOException ex) {
                // Fall through to enqueue per-tile below
            }
        }
        for (int x = a; x <= b; x++) {
            for (int y = yMin; y <= yMax; y++) {
                totalLocal++;
                tileJobs.add(new int[]{z, x, y});
            }
        }
        return totalLocal;
    }

    private boolean generateLosTileIfMissing(ImageResponseCache imgCache,
                                             ImageResponseCache.Format fmt,
                                             int z, int x, int y,
                                             String observers,
                                             double agl,
                                             int angleBins,
                                             String base,
                                             TileCache terrainCache,
                                             CartoTileCache carto,
                                             String cartoApiKey,
                                             ThunderforestTileCache tf,
                                             String thunderforestApiKey,
                                             String colorHex,
                                             Float quality) throws IOException {
        String key = String.join("|",
                "type=losTile",
                "z=" + z,
                "x=" + x,
                "y=" + y,
                "observers=" + observers,
                "agl=" + agl,
                "bins=" + angleBins,
                "base=" + base,
                "tfPresent=" + (thunderforestApiKey != null && !thunderforestApiKey.isBlank()),
                "color=" + (colorHex == null ? "" : colorHex)
        );
        byte[] cached = imgCache.tryRead(key, fmt);
        if (cached != null) {
            return false; // already present
        }
        // Compute required region radius to include observers
        int n = 1 << z;
        int requiredRadius = 0;
        java.util.List<double[]> obsList = parseObservers(observers);
        for (double[] ob : obsList) {
            double olat = ob[0];
            double olon = ob[1];
            double xx = (olon + 180.0) / 360.0 * n;
            double latRad = Math.toRadians(Math.max(-85.05112878, Math.min(85.05112878, olat)));
            double yy = (1.0 - (Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI)) / 2.0 * n;
            int ox = (int)Math.floor(((xx % n) + n) % n);
            int oy = (int)Math.floor(Math.max(0.0, Math.min(Math.nextDown((double)n), yy)));
            int dx = (int)Math.round(((ox - x + n/2.0) % n) - n/2.0);
            int dy = oy - y;
            requiredRadius = Math.max(requiredRadius, Math.max(Math.abs(dx), Math.abs(dy)));
        }
        if (obsList.isEmpty()) {
            // No valid observers parsed: avoid writing a transparent image; do not cache
            return false;
        }
        // Cap the radius of the elevation grid to avoid huge in-memory grids and downloads
        int maxRadius;
        {
            int def = 5; // tighter default cap for prefetch (=> up to 11x11 tiles)
            String sProp = System.getProperty("los.prefetch.maxRadius");
            String sEnv = (sProp == null || sProp.isBlank()) ? System.getenv("LOS_PREFETCH_MAX_RADIUS") : null;
            int v = def;
            try { if (sProp != null && !sProp.isBlank()) v = Integer.parseInt(sProp.trim()); else if (sEnv != null && !sEnv.isBlank()) v = Integer.parseInt(sEnv.trim()); } catch (Exception ignored) {}
            if (v < 0) v = 0;
            if (v > 10) v = 10; // hard upper bound to keep <= 21x21 tiles
            maxRadius = v;
        }
        requiredRadius = Math.max(0, Math.min(maxRadius, requiredRadius + 1));

        double[] center = tileCenterLatLon(z, x, y);
        ElevationService.ElevationGrid regionGrid = ElevationService.getElevationGridAround(center[0], center[1], z, requiredRadius, ElevationService.AreaShape.SQUARE, terrainCache);

        double[][] merged = new double[regionGrid.height][regionGrid.width];
        for (int i = 0; i < merged.length; i++) { java.util.Arrays.fill(merged[i], Double.NaN); }
        for (double[] ob : obsList) {
            double lat = ob[0];
            double lon = ob[1];
            ElevationService.ElevationGrid losMasked = ElevationService.applyLineOfSightMask(
                    regionGrid, lat, lon, ElevationService.ObserverHeightMode.AGL, agl, terrainCache, angleBins);
            for (int py = 0; py < regionGrid.height; py++) {
                double[] srcRow = losMasked.data[py];
                double[] dstRow = merged[py];
                double[] baseRow = regionGrid.data[py];
                for (int px = 0; px < regionGrid.width; px++) {
                    if (!Double.isNaN(srcRow[px])) { dstRow[px] = baseRow[px]; }
                }
            }
        }
        ElevationService.ElevationGrid mergedRegion = new ElevationService.ElevationGrid(
                merged, regionGrid.tileSize, regionGrid.tilesWide, regionGrid.tilesHigh, regionGrid.zoom, regionGrid.centerTileX, regionGrid.centerTileY);
        ElevationService.ElevationGrid cropped = ElevationService.cropCenterTile(mergedRegion);
        
        String baseNorm = base == null ? "none" : base.toLowerCase();
        // Avoid writing per-tile cache for a fully transparent overlay when base is none
        if ("none".equals(baseNorm) && !gridHasAnyVisible(cropped)) {
            return false;
        }
        Color overlayColor = parseColor(colorHex, new Color(255, 0, 0, 120));
        BufferedImage outImg;
        switch (baseNorm) {
            case "carto": {
                if (carto == null) carto = new CartoTileCache(DEFAULT_CARTO_CACHE, cartoApiKey);
                java.io.File f = carto.getTile(z, x, y);
                BufferedImage baseImg = javax.imageio.ImageIO.read(f);
                outImg = ElevationService.overlayLosOnBase(cropped, baseImg, overlayColor);
                break;
            }
            case "thunder": {
                if (tf == null) return false; // tfKey missing handled earlier
                java.io.File f = tf.getTile(z, x, y);
                BufferedImage baseImg = javax.imageio.ImageIO.read(f);
                outImg = ElevationService.overlayLosOnBase(cropped, baseImg, overlayColor);
                break;
            }
            case "gray":
            case "grey":
            case "elev":
            case "elevation": {
                BufferedImage baseImg = ElevationService.elevationGridToImage(cropped);
                outImg = ElevationService.overlayLosOnBase(cropped, baseImg, overlayColor);
                break;
            }
            case "none":
            default: {
                BufferedImage baseImg = new BufferedImage(cropped.width, cropped.height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = baseImg.createGraphics();
                try { g.setComposite(AlphaComposite.Clear); g.fillRect(0,0,baseImg.getWidth(), baseImg.getHeight()); }
                finally { g.dispose(); }
                outImg = ElevationService.overlayLosOnBase(cropped, baseImg, overlayColor);
                break;
            }
        }
        imgCache.encodeAndWrite(key, outImg, fmt, quality);
        return true;
    }

    // Utility: check if a cropped elevation grid has any visible (non-NaN) cells
    private static boolean gridHasAnyVisible(ElevationService.ElevationGrid grid) {
        if (grid == null || grid.data == null) return false;
        for (int y = 0; y < grid.height; y++) {
            double[] row = grid.data[y];
            if (row == null) continue;
            for (int x = 0; x < grid.width; x++) {
                if (!Double.isNaN(row[x])) return true;
            }
        }
        return false;
    }

    // Utility: generate or read a cached fully-transparent 256x256 tile
    private byte[] getTransparentTile(ImageResponseCache imgCache, ImageResponseCache.Format fmt, Float quality) throws IOException {
        String ext = (fmt == ImageResponseCache.Format.WEBP) ? "webp" : "png";
        String rel = "placeholders/transparent_256." + ext;
        byte[] cached = imgCache.tryReadAt(rel);
        if (cached != null) return cached;
        BufferedImage baseImg = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = baseImg.createGraphics();
        try { g.setComposite(AlphaComposite.Clear); g.fillRect(0,0,256,256); }
        finally { g.dispose(); }
        return imgCache.encodeAndWriteAt(rel, baseImg, fmt, quality);
    }
}
