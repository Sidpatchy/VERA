package com.sidpatchy.api;

import com.sidpatchy.Tile.ElevationService;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory registry of LoS overlay definitions.
 * Not persisted; resets on server restart. Suitable for prototyping.
 */
public class OverlayRegistry {
    public static class OverlayDef {
        public final String id;
        public final String name; // user-friendly name
        public final String observers; // semicolon-separated lat,lon pairs
        public final ElevationService.ObserverHeightMode mode; // GROUND, AGL, ASL
        public final Double heightMeters; // null if GROUND
        public final double agl; // legacy field for backward-compat (equals heightMeters when mode AGL)
        public final int angleBins;
        public final String base; // none|carto|thunder (usually none for overlay)
        public final String colorHex; // e.g., #FF000080 (rgba)
        public final String terrainCache;
        public final String cartoCache;
        public final String tfCache;
        public final Integer gridSize; // allowed: 10,15,20,25 — interpreted as tiles across; radius = floor(gridSize/2)
        // We intentionally do NOT store API keys in the registry

        public OverlayDef(String id, String name, String observers,
                          ElevationService.ObserverHeightMode mode, Double heightMeters,
                          double agl, int angleBins, String base, String colorHex,
                          String terrainCache, String cartoCache, String tfCache,
                          Integer gridSize) {
            this.id = id;
            this.name = name;
            this.observers = observers;
            this.mode = mode == null ? ElevationService.ObserverHeightMode.AGL : mode;
            this.heightMeters = heightMeters;
            this.agl = agl;
            this.angleBins = angleBins;
            this.base = base == null ? "none" : base;
            this.colorHex = colorHex;
            this.terrainCache = terrainCache;
            this.cartoCache = cartoCache;
            this.tfCache = tfCache;
            this.gridSize = gridSize;
        }
    }

    private static final OverlayRegistry INSTANCE = new OverlayRegistry();

    public static OverlayRegistry getInstance() { return INSTANCE; }

    private final Map<String, OverlayDef> map = new ConcurrentHashMap<>();

    public OverlayDef get(String id) { return id == null ? null : map.get(id); }

    public OverlayDef register(String requestedId,
                               String name,
                               String observers,
                               ElevationService.ObserverHeightMode mode,
                               Double heightMeters,
                               double agl,
                               int angleBins,
                               String base,
                               String colorHex,
                               String terrainCache,
                               String cartoCache,
                               String tfCache,
                               Integer gridSize) {
        String id = (requestedId != null && !requestedId.isBlank()) ? requestedId : UUID.randomUUID().toString();
        OverlayDef def = new OverlayDef(id, name, observers, mode, heightMeters, agl, angleBins, base, colorHex, terrainCache, cartoCache, tfCache, gridSize);
        map.put(id, def);
        return def;
    }

    public Map<String, OverlayDef> listAll() { return map; }
}