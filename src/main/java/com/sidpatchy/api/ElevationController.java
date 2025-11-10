package com.sidpatchy.api;

import com.sidpatchy.Tile.ElevationService;
import com.sidpatchy.Tile.ImageResponseCache;
import com.sidpatchy.Tile.ThunderforestTileCache;
import com.sidpatchy.Tile.TileCache;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ElevationController {

    private static final String DEFAULT_TERRAIN_CACHE = "./terrain_cache";
    private static final String DEFAULT_TF_CACHE = "./thunder_cache";
    private static final String DEFAULT_IMAGE_CACHE = "./los_cache";

    @GetMapping("/elevation")
    public ResponseEntity<?> getElevation(
            @RequestParam double lat,
            @RequestParam double lon,
            @RequestParam(defaultValue = "12") int zoom,
            @RequestParam(name = "cacheDir", required = false) String cacheDir
    ) {
        try {
            TileCache cache = new TileCache(cacheDir != null ? cacheDir : DEFAULT_TERRAIN_CACHE);
            double elevation = ElevationService.getElevationAt(lat, lon, zoom, cache);
            Map<String, Object> result = new HashMap<>();
            result.put("lat", lat);
            result.put("lon", lon);
            result.put("zoom", zoom);
            result.put("elevationMeters", elevation);
            return ResponseEntity.ok(result);
        } catch (IOException e) {
            java.util.Map<String, Object> err = new java.util.HashMap<>();
            err.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(err);
        }
    }

    @GetMapping(value = "/los")
    public ResponseEntity<byte[]> getLos(
            @RequestParam double lat,
            @RequestParam double lon,
            @RequestParam(defaultValue = "12") int zoom,
            @RequestParam(defaultValue = "10.0") double agl,
            @RequestParam(defaultValue = "10") int radiusTiles,
            @RequestParam(defaultValue = "1440") int angleBins,
            @RequestParam(defaultValue = "false") boolean overlay,
            @RequestParam(name = "tfKey", required = false) String thunderforestApiKey,
            @RequestParam(name = "terrainCache", required = false) String terrainCacheDir,
            @RequestParam(name = "tfCache", required = false) String tfCacheDir,
            @RequestParam(name = "format", defaultValue = "png") String format,
            @RequestParam(name = "quality", required = false) Float quality,
            @RequestParam(name = "cacheImages", defaultValue = "true") boolean cacheImages,
            @RequestParam(name = "imageCacheDir", required = false) String imageCacheDir
    ) throws IOException {
        TileCache terrainCache = new TileCache(terrainCacheDir != null ? terrainCacheDir : DEFAULT_TERRAIN_CACHE);

        ImageResponseCache.Format fmt = "webp".equalsIgnoreCase(format) ? ImageResponseCache.Format.WEBP : ImageResponseCache.Format.PNG;
        String imgCacheDir = imageCacheDir != null ? imageCacheDir : DEFAULT_IMAGE_CACHE;
        ImageResponseCache imgCache = new ImageResponseCache(imgCacheDir, "los-v1");

        // Build a stable cache key from request parameters that affect the image
        String key = String.join("|",
                "lat=" + lat,
                "lon=" + lon,
                "zoom=" + zoom,
                "agl=" + agl,
                "radius=" + radiusTiles,
                "bins=" + angleBins,
                "overlay=" + overlay,
                // do not include API key value; it does not affect image content
                "tfPresent=" + (thunderforestApiKey != null && !thunderforestApiKey.isBlank())
        );

        if (cacheImages) {
            byte[] cached = imgCache.tryRead(key, fmt);
            if (cached != null) {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(fmt == ImageResponseCache.Format.WEBP ? MediaType.valueOf("image/webp") : MediaType.IMAGE_PNG);
                headers.setContentLength(cached.length);
                return new ResponseEntity<>(cached, headers, HttpStatus.OK);
            }
        }

        // Generate image
        ElevationService.ElevationGrid grid = ElevationService.getElevationGridAround(
                lat, lon, zoom, radiusTiles, ElevationService.AreaShape.SQUARE, terrainCache);

        ElevationService.ElevationGrid losMasked = ElevationService.applyLineOfSightMask(
                grid, lat, lon, ElevationService.ObserverHeightMode.AGL, agl, terrainCache, angleBins);

        BufferedImage image;
        if (overlay && thunderforestApiKey != null && !thunderforestApiKey.isBlank()) {
            ThunderforestTileCache tfCache = new ThunderforestTileCache(tfCacheDir != null ? tfCacheDir : DEFAULT_TF_CACHE, thunderforestApiKey);
            BufferedImage base = ElevationService.buildThunderforestBaseMapImage(losMasked, tfCache);
            Color overlayColor = new Color(255, 0, 0, 120); // semi-transparent red
            image = ElevationService.overlayLosOnBase(losMasked, base, overlayColor);
        } else {
            image = ElevationService.elevationGridToImage(losMasked);
        }

        byte[] bytes;
        if (cacheImages) {
            bytes = imgCache.encodeAndWrite(key, image, fmt, quality);
        } else {
            try {
                bytes = ImageResponseCache.encode(image, fmt, quality);
            } catch (IOException e) {
                // Fallback to PNG if WEBP encoding unavailable
                bytes = ImageResponseCache.encode(image, ImageResponseCache.Format.PNG, null);
                fmt = ImageResponseCache.Format.PNG;
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(fmt == ImageResponseCache.Format.WEBP ? MediaType.valueOf("image/webp") : MediaType.IMAGE_PNG);
        headers.setContentLength(bytes.length);
        return new ResponseEntity<>(bytes, headers, HttpStatus.OK);
    }
}
