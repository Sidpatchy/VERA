package com.sidpatchy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.sidpatchy.Tile.ElevationProvider;
import com.sidpatchy.Tile.ElevationService;
import com.sidpatchy.Tile.TileCache;
import com.sidpatchy.Tile.VisibilityEngine;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.json.JavalinJackson3;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** HTTP API for clients that need asynchronous viewshed generation and progress. */
public final class ApiServer implements AutoCloseable {
    private final Path outputDirectory;
    private final String defaultCacheDirectory;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, ViewshedJob> jobMap = new ConcurrentHashMap<>();
    private Javalin app;

    public ApiServer(String outputDirectory, String defaultCacheDirectory) {
        this.outputDirectory = Path.of(outputDirectory);
        this.defaultCacheDirectory = defaultCacheDirectory;
    }

    /** Starts the API and keeps the process alive while Javalin is serving requests. */
    public void start(int port) {
        app = Javalin.create(config -> {
            config.http.defaultContentType = "application/json";
            config.jsonMapper(new JavalinJackson3(mapper, false));
            config.routes.post("/api/viewsheds", ctx -> createJob(ctx, ctx.body()));
            config.routes.get("/api/viewsheds/{id}", ctx -> getJob(ctx, ctx.pathParam("id")));
            config.routes.get("/api/viewsheds/{id}/result", ctx -> getResult(ctx, ctx.pathParam("id")));
        }).start(port);
    }

    private void createJob(io.javalin.http.Context context, String body) {
        final ViewshedRequest parameters;
        try {
            JsonNode request = mapper.readTree(body == null ? "" : body);
            parameters = ViewshedRequest.from(request);
        } catch (Exception e) {
            throw new io.javalin.http.BadRequestResponse("Invalid viewshed request: " + e.getMessage());
        }
        String id = UUID.randomUUID().toString();
        ViewshedJob job = new ViewshedJob(id, parameters);
        jobMap.put(id, job);
        jobs.submit(() -> run(job));
        context.status(HttpStatus.ACCEPTED).json(job.response());
    }

    private void getJob(io.javalin.http.Context context, String id) {
        ViewshedJob job = jobMap.get(id);
        if (job == null) throw new io.javalin.http.NotFoundResponse("Unknown viewshed job");
        context.status(HttpStatus.OK).json(job.response());
    }

    private void getResult(io.javalin.http.Context context, String id) throws IOException {
        ViewshedJob job = jobMap.get(id);
        if (job == null) throw new io.javalin.http.NotFoundResponse("Unknown viewshed job");
        if (job.state != State.COMPLETED) {
            context.status(job.state == State.FAILED ? HttpStatus.GONE : HttpStatus.CONFLICT)
                    .json(job.response());
            return;
        }
        context.contentType(job.output.toString().endsWith(".kmz")
                ? "application/vnd.google-earth.kmz" : "image/png");
        if (job.parameters.kmz && job.parameters.name != null) {
            context.header("Content-Disposition", "attachment; filename=\""
                    + safeDownloadName(job.parameters.name) + "\"");
        }
        // Javalin writes an InputStream after the handler returns, so closing it
        // here causes the deferred response copy to fail with ClosedChannelException.
        context.result(Files.readAllBytes(job.output));
    }

    private void run(ViewshedJob job) {
        job.state = State.RUNNING;
        try {
            Files.createDirectories(outputDirectory);
            Path output = outputDirectory.resolve(job.id + (job.parameters.kmz ? ".kmz" : ".png"));
            TileCache cache = new TileCache(job.parameters.cacheDirectory == null
                    ? defaultCacheDirectory : job.parameters.cacheDirectory,
                    ElevationProvider.parse(job.parameters.source));
            int radius = ElevationService.tileRadiusForMeters(job.parameters.lat, job.parameters.zoom,
                    job.parameters.radiusMeters);
            int tileTotal = (radius * 2 + 1) * (radius * 2 + 1);
            job.progress.start("Loading elevation tiles", tileTotal);
            ElevationService.ElevationGrid grid = ElevationService.getElevationGridAround(
                    job.parameters.lat, job.parameters.lon, job.parameters.zoom, radius,
                    ElevationService.AreaShape.SQUARE, cache,
                    current -> job.progress.update(current));

            int effectiveRays = VisibilityEngine.effectiveRayCount(grid, job.parameters.lat,
                    job.parameters.lon, job.parameters.angleBins);
            job.progress.start("Running LoS raycasts", effectiveRays);
            ElevationService.ElevationGrid masked = ElevationService.applyLineOfSightMask(
                    grid, job.parameters.lat, job.parameters.lon,
                    ElevationService.ObserverHeightMode.AGL, job.parameters.agl, cache,
                    job.parameters.angleBins, job.progress::update);

            job.progress.start("Rendering elevation image", masked.height);
            BufferedImage image = ElevationService.elevationGridToImage(masked, job.progress::update);
            job.progress.start("Writing image", 1);
            if (job.parameters.kmz) {
                KmzExporter.writeViewshed(output, image, masked, job.parameters.name);
            } else {
                ElevationService.writeImageFile(image, output.toFile());
            }
            job.output = output;
            job.state = State.COMPLETED;
            job.progress.complete();
        } catch (Exception e) {
            job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            job.state = State.FAILED;
        }
    }

    private static String safeDownloadName(String name) {
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank()) return "viewshed.kmz";
        return safe.toLowerCase().endsWith(".kmz") ? safe : safe + ".kmz";
    }

    @Override
    public void close() {
        jobs.close();
        if (app != null) app.stop();
    }

    private enum State { QUEUED, RUNNING, COMPLETED, FAILED }

    private static final class Progress {
        private volatile String phase = "Queued";
        private final AtomicInteger current = new AtomicInteger();
        private volatile int total;

        void start(String phase, int total) {
            this.phase = phase;
            this.total = Math.max(1, total);
            current.set(0);
        }

        void update(int value) { current.set(Math.max(0, Math.min(value, total))); }
        void complete() { current.set(total); }
        double percent() { return Math.round(current.get() * 10000.0 / Math.max(1, total)) / 100.0; }
    }

    private static final class ViewshedJob {
        final String id;
        final ViewshedRequest parameters;
        final Progress progress = new Progress();
        volatile State state = State.QUEUED;
        volatile String error;
        volatile Path output;
        ViewshedJob(String id, ViewshedRequest parameters) { this.id = id; this.parameters = parameters; }

        Map<String, Object> response() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("status", state.name().toLowerCase());
            result.put("phase", progress.phase);
            result.put("current", progress.current.get());
            result.put("total", progress.total);
            result.put("percent", progress.percent());
            result.put("statusUrl", "/api/viewsheds/" + id);
            result.put("resultUrl", "/api/viewsheds/" + id + "/result");
            if (error != null) result.put("error", error);
            return result;
        }
    }

    private static final class ViewshedRequest {
        final double lat, lon, agl, radiusMeters;
        final int zoom, angleBins;
        final String source, cacheDirectory;
        final String name;
        final boolean kmz;

        private ViewshedRequest(double lat, double lon, double agl, double radiusMeters, int zoom,
                                int angleBins, String source, String cacheDirectory, String name, boolean kmz) {
            this.lat = lat; this.lon = lon; this.agl = agl; this.radiusMeters = radiusMeters;
            this.zoom = zoom; this.angleBins = angleBins; this.source = source;
            this.cacheDirectory = cacheDirectory; this.name = name; this.kmz = kmz;
        }

        static ViewshedRequest from(JsonNode n) {
            if (n == null || !n.isObject()) throw new IllegalArgumentException("JSON object required");
            double lat = requiredDouble(n, "lat");
            double lon = requiredDouble(n, "lon");
            double radius = n.path("radiusMeters").asDouble(200_000.0);
            int zoom = n.path("zoom").asInt(12);
            int angleBins = n.path("angleBins").asInt(1440);
            double agl = n.path("agl").asDouble(10.0);
            if (!Double.isFinite(lat) || !Double.isFinite(lon) || !Double.isFinite(agl)
                    || !Double.isFinite(radius) || radius < 0 || zoom < 0 || zoom > 30 || angleBins < 8)
                throw new IllegalArgumentException("invalid coordinate, radius, zoom, or angleBins");
            String format = n.path("format").asText("png").toLowerCase();
            if (!format.equals("png") && !format.equals("kmz")) throw new IllegalArgumentException("format must be png or kmz");
            return new ViewshedRequest(lat, lon, agl, radius, zoom, angleBins,
                    n.path("source").asText("terrarium"), textOrNull(n, "cache"),
                    textOrNull(n, "name"), format.equals("kmz"));
        }

        private static double requiredDouble(JsonNode n, String name) {
            if (!n.has(name) || !n.get(name).isNumber()) throw new IllegalArgumentException(name + " is required");
            return n.get(name).asDouble();
        }

        private static String textOrNull(JsonNode n, String name) {
            return n.has(name) && !n.get(name).isNull() ? n.get(name).asText() : null;
        }
    }
}