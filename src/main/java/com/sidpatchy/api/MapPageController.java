package com.sidpatchy.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Map viewing page.
 * - Use /los/create to create overlays
 * - Use /los/select to pick existing overlays
 * - Supply overlays param (comma-separated IDs) to show them here.
 */
@RestController
@RequestMapping
public class MapPageController {

    @GetMapping(value = "/map")
    public ResponseEntity<String> renderMap(
            @RequestParam(name = "centerLat", defaultValue = "42.3263") double centerLat,
            @RequestParam(name = "centerLon", defaultValue = "-113.6556") double centerLon,
            @RequestParam(name = "zoom", defaultValue = "12") int zoom,
            @RequestParam(name = "overlays", required = false) String overlays
    ) {
        String elevTemplate = "/tiles/elevationview/{z}/{x}/{y}.png";

        String html = "<!DOCTYPE html>" +
                "<html lang='en'>" +
                "<head>" +
                "  <meta charset='utf-8'/>" +
                "  <meta name='viewport' content='width=device-width, initial-scale=1'/>" +
                "  <title>VERA Map</title>" +
                "  <link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css' crossorigin=''/>" +
                "  <style>html,body,#map{height:100%;margin:0;padding:0} .leaflet-control-layers-expanded{max-height:240px;overflow:auto} .banner{position:absolute;bottom:10px;left:10px;z-index:1000;background:#fff;padding:8px 10px;border-radius:6px;box-shadow:0 2px 8px rgba(0,0,0,0.15);font-family:sans-serif;font-size:13px} .link{color:#1976d2;text-decoration:none;margin-right:8px}</style>" +
                "</head>" +
                "<body>" +
                "  <div id='map'></div>" +
                "  <div class='banner'>" +
                "    <a class='link' href='/los/create'>Create LoS</a>" +
                "    <a class='link' href='/los/select'>Select LoS</a>" +
                "  </div>" +
                "  <script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js' crossorigin=''></script>" +
                "  <script>" +
                "    const overlaysParam = '" + (overlays == null ? "" : overlays) + "';" +
                "    const center = [" + centerLat + ", " + centerLon + "];" +
                "    const map = L.map('map', {center: center, zoom: " + zoom + ", preferCanvas: true});" +
                "    const cartoBase = L.tileLayer('/tiles/carto/{z}/{x}/{y}.png', {maxZoom: 20});" +
                "    const elevGrayBase = L.tileLayer('" + elevTemplate + "', {maxZoom: 20});" +
                "    cartoBase.addTo(map);" +
                "    const urlBase = '/tiles/los/{z}/{x}/{y}.png';" +
                "    async function initOverlays(){" +
                "      const overlayLayers = {};" +
                "      if (overlaysParam && overlaysParam.length > 0) {" +
                "        const ids = overlaysParam.split(',').map(s=>s.trim()).filter(Boolean);" +
                "        let meta = {};" +
                "        try { const res = await fetch('/overlays'); if(res.ok){ meta = await res.json(); } } catch(e) { console.warn('overlay fetch failed', e); }" +
                "        ids.forEach(id => {" +
                "          const layer = L.tileLayer(urlBase + '?overlayId=' + encodeURIComponent(id) + '&base=none', {maxZoom:20});" +
                "          layer.addTo(map);" +
                "          const def = meta && meta[id] ? meta[id] : null;" +
                "          const friendly = def && def.name ? def.name : ('LoS '+id);" +
                "          overlayLayers[friendly] = layer;" +
                "        });" +
                "      }" +
                "      const baseLayers = {'Carto': cartoBase, 'Elevation (Gray)': elevGrayBase};" +
                "      L.control.layers(baseLayers, overlayLayers, {collapsed:false}).addTo(map);" +
                "    }" +
                "    initOverlays();" +
                "  </script>" +
                "</body></html>";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        return new ResponseEntity<>(html, headers, HttpStatus.OK);
    }
}
