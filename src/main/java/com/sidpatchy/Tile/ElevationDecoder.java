package com.sidpatchy.Tile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class ElevationDecoder {

    public static double getElevation(BufferedImage img, int pixelX, int pixelY) {
        int rgb = img.getRGB(pixelX, pixelY);

        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;

        return (r * 256.0 + g + b / 256.0) - 32768.0;
    }

    public static double[][] loadElevationData(File tileFile) throws IOException {
        BufferedImage img = ImageIO.read(tileFile);
        int width = img.getWidth();
        int height = img.getHeight();

        double[][] elevations = new double[width][height];

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                elevations[x][y] = getElevation(img, x, y);
            }
        }

        return elevations;
    }
}
