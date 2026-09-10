package com.sidpatchy;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.PrintWriter;

/** Small terminal UI used by the CLI for long-running operations. */
public final class CliProgress implements AutoCloseable {
    private static final int BAR_WIDTH = 32;

    private final Terminal terminal;
    private final PrintWriter out;

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
        int safeTotal = Math.max(1, total);
        int safeCurrent = Math.max(0, Math.min(current, safeTotal));
        int filled = (int) Math.round((safeCurrent / (double) safeTotal) * BAR_WIDTH);
        String line = "\r" + label + " [" + "=".repeat(filled) + " ".repeat(BAR_WIDTH - filled) + "] "
                + safeCurrent + "/" + total;
        out.print(line);
        out.flush();
        if (total > 0 && current >= total) out.println();
    }

    public void complete(String label) {
        out.println("\r" + label + " complete");
        out.flush();
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
