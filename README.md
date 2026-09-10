# VERA

VERA is a command-line utility for retrieving and composing terrain elevations from Mapzen Terrarium tiles, exporting stitched grids to PNG, and applying Earth-curvature and line-of-sight analysis.

## Quick start

Run VERA with Gradle using `./gradlew run --args="<command> <options>"`.

```bash
# Print elevation at a coordinate
./gradlew run --args="elevation --lat 42.3263 --lon -113.6556"

# Generate a line-of-sight PNG
./gradlew run --args="los --lat 42.3263 --lon -113.6556 --agl 10 --out los.png"

# Generate a LOS overlay using a basemap provider
./gradlew run --args="los --lat 42.3263 --lon -113.6556 --overlay --tfKey YOUR_KEY"

# Download terrain tiles for a geographic bounding box
./gradlew run --args="prefetch --type terrain --minLat 42 --minLon -114 --maxLat 43 --maxLon -113 --zoom 12"
```

Run VERA without arguments to print the complete option reference.

## Commands

### `elevation`

Prints elevation in meters at a latitude and longitude.

- `--lat`, `--lon`: coordinate values (defaults are provided when omitted)
- `--zoom`: tile zoom, default `12`
- `--cache`: terrain cache directory, default `./terrain_cache`

### `los`

Builds an elevation grid, applies a line-of-sight mask, and saves it as a PNG.

- `--lat`, `--lon`: observer coordinate
- `--zoom`: tile zoom, default `12`
- `--agl`: observer height above ground in meters, default `10`
- `--radius`: grid radius in tiles, default `22`
- `--angleBins`: angular samples, default `1440`
- `--out`: output path, default `./los.png`
- `--overlay`: render the result over a Carto or Thunderforest basemap
- `--tfKey`: Thunderforest key; `--cartoKey` can be used as the fallback provider
- `--cache`, `--tfCache`, `--cartoCache`: cache directories for the providers

### `prefetch`

Downloads tiles into a local cache for later CLI operations.

- `--type`: `terrain`, `carto`, or `thunder`
- `--minLat`, `--minLon`, `--maxLat`, `--maxLon`: geographic bounding box
- `--zoom`, or `--zMin` and `--zMax`: requested zoom level or range
- `--cache`: destination cache directory
- `--tfKey`: required for `thunder` tiles

## Library behavior

The LOS mask accounts for Earth curvature using a spherical Earth model. It
supports ground-level, above-ground-level, and above-sea-level observer modes.
Distances use Web Mercator resolution at the observer latitude, which is a good
approximation for local areas.

Elevation grids can also be composed and exported directly from Java using
`ElevationService` and `TileCache`.
