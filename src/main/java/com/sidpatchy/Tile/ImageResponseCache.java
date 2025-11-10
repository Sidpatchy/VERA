package com.sidpatchy.Tile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Locale;

/**
 * Very simple disk cache for generated images, keyed by a string built from request parameters.
 * Supports PNG and WebP encodings. Requires the Luciad imageio-webp plugin on the classpath for WebP.
 */
public class ImageResponseCache {
    private final Path cacheDir;
    private final String versionTag;

    public enum Format { PNG, WEBP }

    public ImageResponseCache(String cachePath, String versionTag) {
        this.cacheDir = Paths.get(cachePath);
        this.versionTag = versionTag != null ? versionTag : "v1";
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create image cache directory", e);
        }
    }

    public static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private Path pathForKey(String key, Format fmt) {
        String safe = sha256(versionTag + "|" + key);
        String ext = fmt == Format.WEBP ? ".webp" : ".png";
        return cacheDir.resolve(safe + ext);
    }

    public byte[] tryRead(String key, Format fmt) throws IOException {
        Path p = pathForKey(key, fmt);
        if (Files.exists(p)) {
            return Files.readAllBytes(p);
        }
        return null;
    }

    public byte[] encodeAndWrite(String key, BufferedImage image, Format fmt, Float quality) throws IOException {
        Path p = pathForKey(key, fmt);
        byte[] data = encode(image, fmt, quality);
        try (OutputStream os = Files.newOutputStream(p)) {
            os.write(data);
        }
        return data;
    }

    // New: direct path-based read/write to support standard z/x/y tile storage
    public byte[] tryReadAt(String relativePath) throws IOException {
        Path p = cacheDir.resolve(relativePath);
        if (Files.exists(p)) {
            return Files.readAllBytes(p);
        }
        return null;
    }

    public byte[] encodeAndWriteAt(String relativePath, BufferedImage image, Format fmt, Float quality) throws IOException {
        Path p = cacheDir.resolve(relativePath);
        Path parent = p.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] data = encode(image, fmt, quality);
        try (OutputStream os = Files.newOutputStream(p)) {
            os.write(data);
        }
        return data;
    }

    public static byte[] encode(BufferedImage image, Format fmt, Float quality) throws IOException {
        String formatName = fmt == Format.WEBP ? "webp" : "png";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName(formatName);
        if (writers.hasNext()) {
            ImageWriter writer = writers.next();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);
                ImageWriteParam param = writer.getDefaultWriteParam();
                // Set compression if supported
                if (param.canWriteCompressed()) {
                    param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    if (fmt == Format.WEBP) {
                        float q = quality != null ? Math.max(0f, Math.min(1f, quality)) : 0.6f; // default 60%
                        param.setCompressionQuality(q);
                    } else {
                        // PNG: Java treats quality as a hint; lower = smaller, but plugin-dependent.
                        param.setCompressionQuality(0.0f); // max compression
                    }
                }
                writer.write(null, new IIOImage(image, null, null), param);
            } finally {
                writer.dispose();
            }
            return baos.toByteArray();
        } else {
            // Fallback to ImageIO.write directly (no explicit compression control)
            ImageIO.write(image, formatName, baos);
            return baos.toByteArray();
        }
    }
}
