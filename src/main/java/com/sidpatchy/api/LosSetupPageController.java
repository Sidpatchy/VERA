package com.sidpatchy.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class LosSetupPageController {

    @GetMapping("/los/create")
    public ResponseEntity<String> setupPage(
            @RequestParam(name = "lat", defaultValue = "42.3263") double lat,
            @RequestParam(name = "lon", defaultValue = "-113.6556") double lon
    ) {
        String html = "<!DOCTYPE html>" +
                "<html lang='en'>" +
                "<head>" +
                "  <meta charset='utf-8'/>" +
                "  <meta name='viewport' content='width=device-width, initial-scale=1'/>" +
                "  <title>Create LoS</title>" +
                "  <style>html,body{height:100%;margin:0} body{font-family:sans-serif;font-size:14px;background:#f6f7fb;color:#222} .wrap{max-width:720px;margin:24px auto;padding:16px;background:#fff;border-radius:10px;box-shadow:0 6px 24px rgba(0,0,0,.08)} .row{margin:10px 0} label{display:block;margin:6px 0 4px} input[type=text],input[type=number],select{width:100%;padding:8px 10px;border:1px solid #d0d5dd;border-radius:6px;font-size:14px} .grid{display:grid;grid-template-columns:1fr 1fr;gap:12px} .btn{padding:10px 14px;background:#1976d2;color:#fff;border:none;border-radius:6px;cursor:pointer} .btn:disabled{opacity:.6;cursor:default} .hint{color:#666;font-size:12px} .pill{display:inline-block;background:#eef;padding:4px 8px;border-radius:999px;margin-left:8px}</style>" +
                "</head>" +
                "<body>" +
                "  <div class='wrap'>" +
                "    <h3 style='margin-top:0'>Create LoS</h3>" +
                "    <div class='grid'>" +
                "      <div class='row'><label>Latitude</label><input id='lat' type='number' step='0.000001' value='" + lat + "'></div>" +
                "      <div class='row'><label>Longitude</label><input id='lon' type='number' step='0.000001' value='" + lon + "'></div>" +
                "    </div>" +
                "    <div class='grid'>" +
                "      <div class='row'><label>Elevation mode</label><select id='mode'><option value='GROUND'>GROUND (on ground)</option><option value='AGL' selected>AGL (m above ground)</option><option value='ASL'>ASL (m above sea level)</option></select></div>" +
                "      <div class='row'><label id='hLabel'>Height (m)</label><input id='height' type='number' step='0.1' min='0' value='10'></div>" +
                "    </div>" +
                "    <div class='grid'>" +
                "      <div class='row'><label>Tile radius (grid size)</label><select id='gridSize'><option value='10'>10 x 10</option><option value='15'>15 x 15</option><option value='20' selected>20 x 20</option><option value='25'>25 x 25</option></select><div class='hint'>Only tiles within this grid are generated. Others return transparent.</div></div>" +
                "    </div>" +
                "    <div class='row'><label>LoS name</label><input id='name' type='text' placeholder='e.g., Hilltop A'></div>" +
                "    <div class='row hint'>Tip: You can open existing LoS overlays from <a href='/los/select'>the selector</a>.</div>" +
                "    <div class='row'><button id='create' class='btn'>Create</button><span id='status' class='pill'></span></div>" +
                "  </div>" +
                "  <script>" +
                "    const $ = (id)=>document.getElementById(id);" +
                "    function updateHeightLabel(){ const m=$('mode').value; const lab=$('hLabel'); if(m==='GROUND'){ lab.textContent='Height (unused)'; $('height').disabled=true; } else if(m==='AGL'){ lab.textContent='Height AGL (m)'; $('height').disabled=false; } else { lab.textContent='Height ASL (m)'; $('height').disabled=false; } }" +
                "    $('mode').addEventListener('change', updateHeightLabel); updateHeightLabel();" +
                "    async function create(){ const st=$('status'); st.textContent='Registering...'; const lat=parseFloat($('lat').value); const lon=parseFloat($('lon').value); const mode=$('mode').value; const height=parseFloat($('height').value); const name=$('name').value.trim(); const gridSize=parseInt($('gridSize').value,10); const observers=lat.toFixed(6)+','+lon.toFixed(6); const params = new URLSearchParams(); if(name) params.append('name', name); params.append('observers', observers); params.append('mode', mode); if(mode!=='GROUND') params.append('height', isFinite(height)? height : 0); if(Number.isFinite(gridSize)) params.append('gridSize', String(gridSize)); try{ const res = await fetch('/overlays/register?'+params.toString()); const j = await res.json(); if(!j.id){ st.textContent='Failed to create'; return; } st.textContent='Created '+j.id+' — preparing tiles...'; setTimeout(()=>{ const next='/los/loading?overlays='+encodeURIComponent(j.id)+'&centerLat='+encodeURIComponent(lat)+'&centerLon='+encodeURIComponent(lon)+'&zoom=12&gridSize='+encodeURIComponent(gridSize); window.location = next; }, 300); } catch(e){ console.error(e); st.textContent='Error'; } }" +
                "    $('create').onclick = create;" +
                "  </script>" +
                "</body></html>";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        return new ResponseEntity<>(html, headers, HttpStatus.OK);
    }
}
