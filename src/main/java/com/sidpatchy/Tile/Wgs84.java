package com.sidpatchy.Tile;

/** WGS84 oblate-spheroid geometry shared by curvature and line-of-sight code. */
final class Wgs84 {
    static final double SEMI_MAJOR_METERS = 6_378_137.0;
    static final double ECCENTRICITY_SQUARED = 6.6943799901413165e-3;

    static double surfaceDrop(double observerLatDeg, double observerLonDeg,
                              double targetLatDeg, double targetLonDeg) {
        double lat = Math.toRadians(observerLatDeg);
        double lon = Math.toRadians(observerLonDeg);
        double[] observer = ecef(lat, lon, 0.0);
        double[] target = ecef(Math.toRadians(targetLatDeg), Math.toRadians(targetLonDeg), 0.0);
        double upX = Math.cos(lat) * Math.cos(lon);
        double upY = Math.cos(lat) * Math.sin(lon);
        double upZ = Math.sin(lat);
        double tangentComponent = (target[0] - observer[0]) * upX
                + (target[1] - observer[1]) * upY
                + (target[2] - observer[2]) * upZ;
        return Math.max(0.0, -tangentComponent);
    }

    static double[] ecef(double latitudeRad, double longitudeRad, double elevationMeters) {
        double sinLat = Math.sin(latitudeRad);
        double cosLat = Math.cos(latitudeRad);
        double radius = SEMI_MAJOR_METERS / Math.sqrt(
                1.0 - ECCENTRICITY_SQUARED * sinLat * sinLat);
        return new double[]{
                (radius + elevationMeters) * cosLat * Math.cos(longitudeRad),
                (radius + elevationMeters) * cosLat * Math.sin(longitudeRad),
                (radius * (1.0 - ECCENTRICITY_SQUARED) + elevationMeters) * sinLat
        };
    }
}