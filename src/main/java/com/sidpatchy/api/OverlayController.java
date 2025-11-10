package com.sidpatchy.api;

import com.sidpatchy.Tile.ElevationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/overlays")
public class OverlayController {

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, OverlayRegistry.OverlayDef> list() {
        return OverlayRegistry.getInstance().listAll();
    }

    @PostMapping(value = "/register", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> registerPost(
            @RequestParam(name = "id", required = false) String id,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "observers") String observers,
            @RequestParam(name = "mode", defaultValue = "AGL") ElevationService.ObserverHeightMode mode,
            @RequestParam(name = "height", required = false) Double heightMeters,
            @RequestParam(name = "agl", defaultValue = "10.0") double agl,
            @RequestParam(name = "angleBins", defaultValue = "1440") int angleBins,
            @RequestParam(name = "base", defaultValue = "none") String base,
            @RequestParam(name = "color", required = false) String colorHex,
            @RequestParam(name = "terrainCache", required = false) String terrainCache,
            @RequestParam(name = "cartoCache", required = false) String cartoCache,
            @RequestParam(name = "tfCache", required = false) String tfCache,
            @RequestParam(name = "gridSize", required = false) Integer gridSize
    ) {
        if (observers == null || observers.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "observers required"));
        }
        // Backward-compat: if height not provided, derive from agl for AGL mode
        Double effHeight = heightMeters;
        if (effHeight == null && mode == ElevationService.ObserverHeightMode.AGL) {
            effHeight = agl;
        }
        OverlayRegistry.OverlayDef def = OverlayRegistry.getInstance().register(
                id, name, observers, mode, effHeight, agl, angleBins, base, colorHex, terrainCache, cartoCache, tfCache, gridSize);
        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("id", def.id);
        resp.put("name", def.name);
        resp.put("observers", def.observers);
        resp.put("mode", def.mode == null ? null : def.mode.toString());
        resp.put("height", def.heightMeters);
        resp.put("agl", def.agl);
        resp.put("angleBins", def.angleBins);
        resp.put("base", def.base);
        resp.put("color", def.colorHex);
        return ResponseEntity.ok(resp);
    }

    @GetMapping(value = "/register", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> registerGet(
            @RequestParam(name = "id", required = false) String id,
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "observers") String observers,
            @RequestParam(name = "mode", defaultValue = "AGL") ElevationService.ObserverHeightMode mode,
            @RequestParam(name = "height", required = false) Double heightMeters,
            @RequestParam(name = "agl", defaultValue = "10.0") double agl,
            @RequestParam(name = "angleBins", defaultValue = "1440") int angleBins,
            @RequestParam(name = "base", defaultValue = "none") String base,
            @RequestParam(name = "color", required = false) String colorHex,
            @RequestParam(name = "terrainCache", required = false) String terrainCache,
            @RequestParam(name = "cartoCache", required = false) String cartoCache,
            @RequestParam(name = "tfCache", required = false) String tfCache,
            @RequestParam(name = "gridSize", required = false) Integer gridSize
    ) {
        return registerPost(id, name, observers, mode, heightMeters, agl, angleBins, base, colorHex, terrainCache, cartoCache, tfCache, gridSize);
    }
}