# VERA

Utilities for retrieving and composing terrain elevations from Mapzen Terrarium tiles, exporting stitched grids to PNG, and applying an Earth curvature correction centered on an observer.

## Quick start

### Server API keys

The server accepts provider keys on the CLI, so keys do not need to be included in every tile request:

```bash
./gradlew bootRun --args="serve --tfKey YOUR_THUNDERFOREST_KEY --cartoKey YOUR_CARTO_KEY"
```

The same values can be supplied through `THUNDERFOREST_API_KEY`/`TF_KEY` and
`CARTO_API_KEY`/`CARTO_KEY`, or the corresponding JVM properties
`thunderforest.api.key` and `carto.api.key`. Request parameters `tfKey` and
`cartoKey` remain supported and override the defaults.

```java
TileCache cache = new TileCache("./terrain_cache");

// Choose a center
double lat = 44.44716977797951;
double lon = -95.79181999770933;
int zoom = 12;
int radiusTiles = 5;

// Build a stitched elevation grid (circle selection)
ElevationService.ElevationGrid grid = ElevationService.getElevationGridAround(
        lat, lon, zoom, radiusTiles, ElevationService.AreaShape.CIRCLE, cache);

// Save the raw grid to PNG (auto-scaled grayscale; NaN -> transparent)
ElevationService.saveElevationGridAsPng(grid, new File("./elevation_grid.png"));

// Apply Earth curvature around an explicit observer with height modes
// GROUND: observer at ground level
ElevationService.ElevationGrid curvedGround = ElevationService.applyEarthCurvatureDrop(
        grid, lat, lon, ElevationService.ObserverHeightMode.GROUND, null, cache);
ElevationService.saveElevationGridAsPng(curvedGround, new File("./elevation_grid_curved_ground.png"));

// AGL: eye height above local ground at the observer
ElevationService.ElevationGrid curvedAgl = ElevationService.applyEarthCurvatureDrop(
        grid, lat, lon, ElevationService.ObserverHeightMode.AGL, 2.0, cache);
ElevationService.saveElevationGridAsPng(curvedAgl, new File("./elevation_grid_curved_agl2m.png"));

// ASL: eye altitude above mean sea level; AGL derived by subtracting local ground elevation
ElevationService.ElevationGrid curvedAsl = ElevationService.applyEarthCurvatureDrop(
        grid, lat, lon, ElevationService.ObserverHeightMode.ASL, 1500.0, cache);
ElevationService.saveElevationGridAsPng(curvedAsl, new File("./elevation_grid_curved_asl1500.png"));
```

### Height modes
- GROUND: No input height; eye at ground level.
- AGL: Height is above ground level at the observer location (meters).
- ASL: Height is above mean sea level; the library samples ground elevation and converts to AGL.

Notes:
- Curvature model uses a spherical Earth (R ≈ 6,371,008.8 m) without refraction.
- Distances are approximated using Web Mercator resolution at the observer latitude.
- For ASL mode, if the observer lies outside the composed grid or that pixel is NaN, the code falls back to sampling elevation via TileCache; if that fails, it treats ASL as AGL.

# VERA

Utilities for retrieving and composing terrain elevations from Mapzen Terrarium tiles, exporting stitched grids to PNG, and applying an Earth curvature correction centered on an observer.

## Quick start

```java
TileCache cache = new TileCache("./terrain_cache");

// Choose a center
double lat = 44.44716977797951;
double lon = -95.79181999770933;
int zoom = 12;
int radiusTiles = 5;

// Build a stitched elevation grid (circle selection)
ElevationService.ElevationGrid grid = ElevationService.getElevationGridAround(
        lat, lon, zoom, radiusTiles, ElevationService.AreaShape.CIRCLE, cache);

// Save the raw grid to PNG (auto-scaled grayscale; NaN -> transparent)
ElevationService.saveElevationGridAsPng(grid, new File("./elevation_grid.png"));

// Apply Earth curvature around an explicit observer with height modes
// GROUND: observer at ground level
ElevationService.ElevationGrid curvedGround = ElevationService.applyEarthCurvatureDrop(
        grid, lat, lon, ElevationService.ObserverHeightMode.GROUND, null, cache);
ElevationService.saveElevationGridAsPng(curvedGround, new File("./elevation_grid_curved_ground.png"));

// AGL: eye height above local ground at the observer
ElevationService.ElevationGrid curvedAgl = ElevationService.applyEarthCurvatureDrop(
        grid, lat, lon, ElevationService.ObserverHeightMode.AGL, 2.0, cache);
ElevationService.saveElevationGridAsPng(curvedAgl, new File("./elevation_grid_curved_agl2m.png"));

// ASL: eye altitude above mean sea level; AGL derived by subtracting local ground elevation
ElevationService.ElevationGrid curvedAsl = ElevationService.applyEarthCurvatureDrop(
        grid, lat, lon, ElevationService.ObserverHeightMode.ASL, 1500.0, cache);
ElevationService.saveElevationGridAsPng(curvedAsl, new File("./elevation_grid_curved_asl1500.png"));
```

### Height modes
- GROUND: No input height; eye at ground level.
- AGL: Height is above ground level at the observer location (meters).
- ASL: Height is above mean sea level; the library samples ground elevation and converts to AGL.

Notes:
- Curvature model uses a spherical Earth (R ≈ 6,371,008.8 m) without refraction.
- Distances are approximated using Web Mercator resolution at the observer latitude.
- For ASL mode, if the observer lies outside the composed grid or that pixel is NaN, the code falls back to sampling elevation via TileCache; if that fails, it treats ASL as AGL.

## Line-of-sight (LOS) mask and Earth curvature

Yes — the LOS mask accounts for Earth curvature.

How it works:
- The LOS function first computes a curvature-adjusted copy of the grid using the sphere-drop model s^2/(2R) centered on the observer. This effectively “lowers” distant terrain to the local tangent plane so visibility can be tested with straight-line slopes.
- Observer height modes are honored:
  - GROUND: eye at ground level at the observer pixel.
  - AGL: specified meters above local ground at the observer.
  - ASL: specified meters above mean sea level; converted to AGL by sampling the ground at the observer location.
- Visibility is then computed by casting rays around the observer and tracking the maximum apparent slope along each ray on the curvature-adjusted grid. Cells whose slope is ≥ the running maximum are marked visible; others are masked to NaN (rendering black/transparent in the PNG exporter).

Configuration:
- Angular resolution is configurable via the overload `applyLineOfSightMask(..., int angleBins)`. Example: 720 ≈ 0.5°, 1440 ≈ 0.25°.

Assumptions and limitations:
- Spherical Earth model; no atmospheric refraction (k-factor) applied. A refraction option could be added in the future.
- Ground distances use Web Mercator meters-per-pixel at the observer latitude, which is a good approximation for local areas.



## Tile overlays: Elevation view and Line-of-Sight (LoS)

New endpoints expose ready-to-use map tiles you can plug into web maps (z/x/y scheme).

- Elevation view (grayscale from Terrarium elevation):
  - GET /tiles/elevationview/{z}/{x}/{y}.png
  - Optional query params:
    - terrainCache: override cache dir (default ./terrain_cache)
    - min, max: force grayscale scaling range
    - cacheImages=true|false, imageCacheDir, format=png|webp, quality

- LoS overlay tiles (supports multiple observers):
  - GET /tiles/los/{z}/{x}/{y}.png?observers=lat1,lon1;lat2,lon2&agl=10.0&angleBins=1440&base=none|carto|thunder
  - Query params:
    - observers: required. Semicolon-separated list of lat,lon pairs. Example: observers=42.3,-113.6;42.31,-113.59
    - agl: observer height above ground in meters (default 10.0)
    - angleBins: number of angular samples (default 1440)
    - base: background imagery. none (transparent, default), carto, or thunder
    - cartoCache: cache dir for Carto tiles (default ./carto_cache)
    - tfKey: Thunderforest API key (required if base=thunder)
    - tfCache: cache dir for Thunderforest tiles (default ./thunder_cache)
    - terrainCache: cache dir for Terrarium tiles (default ./terrain_cache)
    - color: overlay color hex (e.g., #FF0000 or #FF000080 for alpha)
    - cacheImages=true|false, imageCacheDir, format=png|webp, quality

Examples
- Transparent LoS overlay (for client-side stacking):
  - GET http://localhost:8080/tiles/los/12/745/1287.png?observers=42.3263,-113.6556;42.33,-113.65&agl=10
- LoS over Carto basemap:
  - GET http://localhost:8080/tiles/los/12/745/1287.png?observers=42.3263,-113.6556&base=carto
- LoS over Thunderforest basemap (requires key):
  - GET http://localhost:8080/tiles/los/12/745/1287.png?observers=42.3263,-113.6556&base=thunder&tfKey=YOUR_TF_KEY
- Elevation view tile:
  - GET http://localhost:8080/tiles/elevationview/12/745/1287.png

Notes
- Multiple observers are combined using a union: a pixel is visible if any observer can see it.
- All tiles are 256x256 to match the underlying Terrarium/Carto/Thunderforest tiles.
- Server-side image caching is enabled by default and stored in ./los_cache (configurable).


## Consistent elevation tile scaling

For mosaic-friendly elevation overlays, you often want every tile to use the same grayscale range rather than auto-scaling per tile. The elevation view endpoint supports two ways to enforce consistent scaling:

1) Per-request parameters
- Pass both min and max to the tile URL: /tiles/elevationview/{z}/{x}/{y}.png?min=0&max=3000
- These override all other settings for that request.

2) Global defaults via env vars or JVM properties
- Set both values to make all elevation tiles use the same range unless the request supplies explicit min/max.
- Environment variables (take effect for the running process):
  - Windows PowerShell:
    $env:VERA_ELEV_MIN = "0"
    $env:VERA_ELEV_MAX = "3000"
  - Linux/macOS shell:
    export VERA_ELEV_MIN=0
    export VERA_ELEV_MAX=3000
- Or pass JVM system properties when starting the server:
  java -Dvera.elev.min=0 -Dvera.elev.max=3000 -jar build\libs\VERA.jar serve --host 0.0.0.0 --port 8080

Precedence
- Request params min/max > JVM system properties (vera.elev.min/max) > Environment variables (VERA_ELEV_MIN/MAX) > Auto-scale per tile if none provided.

Notes
- You must provide both a min and a max through either request params or global settings to enable fixed scaling. If only one bound is present, the tile will fall back to per-tile auto-scaling.
- NaN cells (outside of coverage) remain transparent regardless of scaling.


## LOS generation radius caps

To prevent excessive memory use when generating LoS overlay tiles, on-demand tile rendering now clamps the elevation grid radius:

- Overlay grid size cap: If the overlay was created with a gridSize (e.g., 10, 15, 20, 25), tiles are generated only within radius=floor(gridSize/2). Requests outside this radius return a shared transparent tile and skip computation.
- Global cap: You can configure a maximum radius for ad‑hoc/on‑demand tile generation via:
  - Java system property: -Dlos.maxRadius=<int>
  - Environment variable: LOS_MAX_RADIUS=<int>
  Default is 10 (i.e., up to a 21x21 tile grid). Hard upper bound is 25.

Prefetch endpoints already include safeguards to avoid creating huge in-memory grids and to keep work constrained to the requested area and grid size.
