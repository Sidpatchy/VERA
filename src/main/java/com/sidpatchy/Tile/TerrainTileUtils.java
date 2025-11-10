package com.sidpatchy.Tile;

public class TerrainTileUtils {

    public static class TileCoord {
        public final int x;
        public final int y;
        public final int zoom;

        public TileCoord(int x, int y, int zoom) {
            this.x = x;
            this.y = y;
            this.zoom = zoom;
        }

        public String getUrl() {
            return String.format(
                    "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/%d/%d/%d.png",
                    zoom, x, y
            );
        }
    }

    public static TileCoord latLonToTile(double lat, double lon, int zoom) {
        double latRad = Math.toRadians(lat);
        double n = Math.pow(2.0, zoom);

        int x = (int) Math.floor((lon + 180.0) / 360.0 * n);
        int y = (int) Math.floor(
                (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0 * n
        );

        return new TileCoord(x, y, zoom);
    }
}
