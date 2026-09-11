package com.sidpatchy.Tile;

/** Selects the upstream elevation dataset without changing the grid API. */
public enum ElevationProvider {
    TERRARIUM,
    COPERNICUS_GLO30;

    public static ElevationProvider parse(String value) {
        if (value == null || value.isBlank() || value.equalsIgnoreCase("terrarium")) {
            return TERRARIUM;
        }
        if (value.equalsIgnoreCase("copernicus") || value.equalsIgnoreCase("glo30")
                || value.equalsIgnoreCase("copernicus-glo30")) {
            return COPERNICUS_GLO30;
        }
        throw new IllegalArgumentException("Unknown elevation source: " + value
                + " (expected terrarium or copernicus)");
    }
}