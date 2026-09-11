# VERA

VERA is a command-line utility for retrieving and composing terrain elevations from Mapzen Terrarium tiles or Copernicus GLO-30, exporting stitched grids to PNG, JPEG, WebP, or georeferenced KMZ, and applying Earth-curvature and line-of-sight analysis.

## Quick start

Run VERA with Gradle using `./gradlew run --args="<command> <options>"`.

```bash
# Print elevation at a coordinate
./gradlew run --args="elevation --lat 42.3263 --lon -113.6556"

# Generate a georeferenced line-of-sight KMZ for Google Earth
./gradlew run --args="los --lat 42.3263 --lon -113.6556 --agl 10 --out viewshed.kmz"

# Generate a line-of-sight PNG
./gradlew run --args="los --lat 42.3263 --lon -113.6556 --agl 10 --out los.png"

# Generate a LOS overlay using a basemap provider
./gradlew run --args="los --lat 42.3263 --lon -113.6556 --overlay --tfKey YOUR_KEY"

# Download terrain tiles for a geographic bounding box
./gradlew run --args="prefetch --type terrain --minLat 42 --minLon -114 --maxLat 43 --maxLon -113 --zoom 12"
```

Run VERA without arguments to print the complete option reference.

## HTTP API

Start the asynchronous Javalin API with:

```bash
./gradlew run --args="server --port 7070 --cache ./terrain_cache --output ./api_output"
```

Submit a viewshed job with `POST /api/viewsheds`:

```json
{
  "lat": 42.3263,
  "lon": -113.6556,
  "agl": 10,
  "radiusMeters": 40000,
  "zoom": 12,
  "angleBins": 1440,
  "format": "png",
  "name": "optional-kmz-name"
}
```

The optional `name` is used for the KMZ title and download filename. The response is `202 Accepted` and contains `statusUrl` and `resultUrl`. Poll
the status URL to receive `status`, `phase`, `current`, `total`, and `percent`
while the job runs. When `status` is `completed`, download `resultUrl` to get
the PNG (or use `"format": "kmz"` for a georeferenced KMZ). Failed jobs report
an `error` field from the same status endpoint.

## Commands

### `elevation`

Prints elevation in meters at a latitude and longitude.

- `--lat`, `--lon`: coordinate values (defaults are provided when omitted)
- `--zoom`: tile zoom, default `12`
- `--cache`: terrain cache directory, default `./terrain_cache`
- `--source`: `terrarium` (default) or `copernicus` for public Copernicus GLO-30 data

### `los`

Builds an elevation grid, applies a line-of-sight mask, and saves it as a PNG.

- `--lat`, `--lon`: observer coordinate
- `--zoom`: tile zoom, default `12`
- `--agl`: observer height above ground in meters, default `10`
- `--radius`: physical grid radius, such as `40km`, `25mi`, `500m`, or `1000ft`; default `200km`
  (bare integers remain supported as legacy tile-radius values)
- `--angleBins`: requested azimuth samples, default `1440`; the engine may use
  more rays for large grids to avoid gaps, and reports the effective count
- `--out`: output path, default `./los.png`; format is selected by extension: `.png`, `.jpg`/`.jpeg`, `.webp`, or `.kmz`
- `--name`: optional KMZ name; it is used for the KMZ title
- `--elevation-out`: optionally save the stitched, unmasked elevation grid as a grayscale image
- `--curvature-out`: optionally save the observer-relative curvature-adjusted grid as a grayscale image
- `--overlay`: render the result over a Carto or Thunderforest basemap
- `--tfKey`: Thunderforest key; `--cartoKey` can be used as the fallback provider
- `--cache`, `--tfCache`, `--cartoCache`: cache directories for the providers
- `--source`: `terrarium` (default) or `copernicus` for public Copernicus GLO-30 data

PNG preserves transparency, while JPEG is written with a white background. WebP is
available through the bundled ImageIO plugin. JPEG XL (`.jxl`) is not currently
supported because the project does not include a JPEG XL ImageIO writer.

KMZ contains translucent red PNG tiles and KML `GroundOverlay` entries with matching geographic
bounding boxes, so large viewsheds remain renderable in Google Earth Desktop. Visible viewshed
cells use the same red transparency as `--overlay`; occluded cells remain transparent. KMZ is supported
for the viewshed export without `--overlay`; basemap compositing remains image-only.

### `prefetch`

Downloads tiles into a local cache for later CLI operations.

- `--type`: `terrain`, `carto`, or `thunder`
- `--minLat`, `--minLon`, `--maxLat`, `--maxLon`: geographic bounding box
- `--zoom`, or `--zMin` and `--zMax`: requested zoom level or range
- `--cache`: destination cache directory
- `--tfKey`: required for `thunder` tiles
- `--threads`: concurrent Copernicus prefetch workers, default `8`

Prefetch displays a stable overall progress bar while it processes the planned
tile set. The bar advances for both successful and failed tiles, and the final
summary reports ready and failed counts. A zoom range is processed from the
lowest requested zoom to the highest; bounding boxes crossing the antimeridian
are supported. Its bounded tile-coverage projection uses `#` for ready tiles,
`x` for failed tiles, and `.` for tiles not yet processed. For Copernicus,
the preview is a longitude/latitude grid of published one-degree COG cells,
so it roughly follows the geographic shape of the requested area rather than
the Web Mercator download tiles. Copernicus COG downloads use a dedicated
bounded network worker pool controlled by `--threads`, and overlapping web tiles are
deduplicated so each required one-degree COG is processed once, including
across a requested zoom range. A tile that touches multiple COGs downloads
those COGs concurrently as well.
VERA downloads and caches the bucket's
`tileList.txt` availability index first, so unpublished ocean and restricted
GLO-30 cells are skipped instead of generating 404 download failures.

Elevation tiles use a bounded in-memory decoded cache and an atomic on-disk Smile cache. Terrarium source PNGs are stored under `terrarium/`, while Copernicus source data is retained as COGs under `cog/`. Copernicus prefetch ensures the required COGs are present without generating decoded tiles; normal elevation reads populate the geographic, provider-prefixed Smile files under `decoded/`. Source files and decoded values are safe to delete and will be rebuilt.

## Library behavior

The LOS mask and curvature output use the WGS84 oblate spheroid consistently.
Terrain points are converted to Earth-centered, Earth-fixed coordinates and
compared against the observer's local ellipsoidal up direction. The calculation
supports ground-level, above-ground-level, and above-sea-level observer modes;
there is no configurable spherical-radius approximation.

LOS resolution has two parts: `--angleBins` controls the requested azimuth
sweep, while the engine adapts the effective ray count to the grid extent. The
terrain profile is sampled along each ray as it traverses the grid; it is not a
separate vertical-angle binning pass. The progress bar reflects the effective
ray count used for the grid.

Elevation grids can also be composed and exported directly from Java using
`ElevationService` and `TileCache`.
