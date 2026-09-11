package com.sidpatchy;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.PrintWriter;

/** Small terminal UI used by the CLI for long-running operations. */
public final class CliProgress implements AutoCloseable {
    private static final int BAR_WIDTH = 32;
    private static final int MAP_WIDTH = 48;
    private static final int MAP_HEIGHT = 16;

    private final Terminal terminal;
    private final PrintWriter out;
    private int renderedMapLines;
    private int mapZoom = Integer.MIN_VALUE;
    private int mapMinX;
    private int mapMinY;
    private int mapWidth;
    private int mapHeight;
    private char[][] tileMap;

    private CliProgress(Terminal terminal) {
        this.terminal = terminal;
        this.out = terminal.writer();
    }

    public static CliProgress open() {
        try {
            return new CliProgress(TerminalBuilder.builder().system(true).build());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize terminal output", e);
        }
    }

    public void bar(String label, int current, int total) {
        clearMap();
        out.print(progressLine(label, current, total));
        out.flush();
        if (total > 0 && current >= total) {
            out.println();
            renderedMapLines = 0;
        } else {
            renderedMapLines = 1;
        }
    }

    private String progressLine(String label, int current, int total) {
        int safeTotal = Math.max(1, total);
        int safeCurrent = Math.max(0, Math.min(current, safeTotal));
        int filled = (int) Math.round((safeCurrent / (double) safeTotal) * BAR_WIDTH);
        return "\r" + label + " [" + "=".repeat(filled) + " ".repeat(BAR_WIDTH - filled) + "] "
                + safeCurrent + "/" + total;
    }

    public void complete(String label) {
        clearMap();
        out.println("\r" + label + " complete");
        out.flush();
    }

    /**
     * Prints a durable message without leaving it inside the live progress frame.
     * The next progress update will draw a new frame below this message.
     */
    public void message(String message) {
        clearMap();
        out.println(message);
        out.flush();
    }

    /**
     * Draws a bounded projection of the tiles completed at the current zoom.
     * The projection is deliberately approximate for large tile rectangles so
     * that prefetch output remains usable on a normal terminal.
     */
    public void tileMap(String label, int current, int total, int zoom,
                        int tileX, int tileY, int minX, int maxX, int minY, int maxY,
                        boolean succeeded) {
        clearMap();
        int width = Math.min(MAP_WIDTH, Math.max(1, maxX - minX + 1));
        int height = Math.min(MAP_HEIGHT, Math.max(1, maxY - minY + 1));
        if (mapZoom != zoom || mapMinX != minX || mapMinY != minY
                || mapWidth != width || mapHeight != height) {
            mapZoom = zoom;
            mapMinX = minX;
            mapMinY = minY;
            mapWidth = width;
            mapHeight = height;
            tileMap = new char[height][width];
            for (char[] row : tileMap) java.util.Arrays.fill(row, '.');
        }
        int cellX = Math.min(width - 1, Math.max(0,
                (int) ((long) (tileX - minX) * width / Math.max(1, maxX - minX + 1))));
        int cellY = Math.min(height - 1, Math.max(0,
                (int) ((long) (tileY - minY) * height / Math.max(1, maxY - minY + 1))));
        if (succeeded || tileMap[cellY][cellX] != '#') {
            tileMap[cellY][cellX] = succeeded ? '#' : 'x';
        }

        out.print(progressLine(label, current, total));
        out.println();
        out.println("Coverage z" + zoom + " (completed tile: x=" + tileX + ", y=" + tileY + ")");
        for (char[] row : tileMap) {
            out.print("  ");
            out.print(row);
            out.println();
        }
        renderedMapLines = height + 2;
        out.flush();
    }

    private void clearMap() {
        if (renderedMapLines == 0) return;
        out.print("\033[" + renderedMapLines + "A");
        for (int line = 0; line < renderedMapLines; line++) {
            out.print("\r\033[2K");
            if (line < renderedMapLines - 1) out.print("\033[1B");
        }
        out.print("\033[" + renderedMapLines + "A\r");
        renderedMapLines = 0;
    }

    public Spinner spinner(String label) {
        Spinner spinner = new Spinner(label);
        spinner.start();
        return spinner;
    }

    @Override
    public void close() {
        out.flush();
        try {
            terminal.close();
        } catch (java.io.IOException ignored) {
            // Terminal cleanup must not hide the command result.
        }
    }

    public final class Spinner implements AutoCloseable {
        private final String label;
        private final Thread thread;
        private volatile boolean running = true;

        private Spinner(String label) {
            this.label = label;
            this.thread = new Thread(this::animate, "vera-cli-spinner");
            this.thread.setDaemon(true);
        }

        private void start() {
            thread.start();
        }

        private void animate() {
            char[] frames = {'|', '/', '-', '\\'};
            int index = 0;
            while (running) {
                out.print("\r" + label + " " + frames[index++ % frames.length]);
                out.flush();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        @Override
        public void close() {
            running = false;
            thread.interrupt();
            try {
                thread.join(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            out.print("\r" + label + " done\n");
            out.flush();
        }
    }

}
