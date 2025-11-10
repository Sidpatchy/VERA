package com.sidpatchy.api;

import com.sidpatchy.Tile.CartoTileCache;
import com.sidpatchy.Tile.ThunderforestTileCache;
import com.sidpatchy.Tile.TileCache;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

@RestController
@RequestMapping("/tiles")
public class TileController {
    private static final String DEFAULT_TERRAIN_CACHE = "./terrain_cache";
    private static final String DEFAULT_CARTO_CACHE = "./carto_cache";
    private static final String DEFAULT_THUNDER_CACHE = "./thunder_cache";

    @GetMapping(value = "/terrain/{z}/{x}/{y}.png")
    public ResponseEntity<byte[]> getTerrainTile(
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y,
            @RequestParam(name = "cacheDir", required = false) String cacheDir
    ) {
        try {
            TileCache cache = new TileCache(cacheDir != null ? cacheDir : DEFAULT_TERRAIN_CACHE);
            File f = cache.getTile(z, x, y);
            byte[] data = Files.readAllBytes(f.toPath());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.setContentLength(data.length);
            return new ResponseEntity<>(data, headers, HttpStatus.OK);
        } catch (IOException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{" + "\"error\":\"" + ex.getMessage().replace("\"", "'") + "\"}").getBytes());
        }
    }

    @GetMapping(value = "/carto/{z}/{x}/{y}.png")
    public ResponseEntity<byte[]> getCartoTile(
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y,
            @RequestParam(name = "cacheDir", required = false) String cacheDir
    ) {
        try {
            CartoTileCache cache = new CartoTileCache(cacheDir != null ? cacheDir : DEFAULT_CARTO_CACHE);
            File f = cache.getTile(z, x, y);
            byte[] data = Files.readAllBytes(f.toPath());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.setContentLength(data.length);
            return new ResponseEntity<>(data, headers, HttpStatus.OK);
        } catch (IOException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{" + "\"error\":\"" + ex.getMessage().replace("\"", "'") + "\"}").getBytes());
        }
    }

    @GetMapping(value = "/thunder/{z}/{x}/{y}.png")
    public ResponseEntity<byte[]> getThunderTile(
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y,
            @RequestParam(name = "apiKey") String apiKey,
            @RequestParam(name = "cacheDir", required = false) String cacheDir
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"Missing apiKey\"}".getBytes());
        }
        try {
            ThunderforestTileCache cache = new ThunderforestTileCache(cacheDir != null ? cacheDir : DEFAULT_THUNDER_CACHE, apiKey);
            File f = cache.getTile(z, x, y);
            byte[] data = Files.readAllBytes(f.toPath());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.IMAGE_PNG);
            headers.setContentLength(data.length);
            return new ResponseEntity<>(data, headers, HttpStatus.OK);
        } catch (IOException ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(("{" + "\"error\":\"" + ex.getMessage().replace("\"", "'") + "\"}").getBytes());
        }
    }
}
