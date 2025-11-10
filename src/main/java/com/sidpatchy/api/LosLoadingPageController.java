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
public class LosLoadingPageController {

    @GetMapping("/los/loading")
    public ResponseEntity<String> loading(
            @RequestParam(name = "overlays") String overlays,
            @RequestParam(name = "centerLat", required = false) Double centerLat,
            @RequestParam(name = "centerLon", required = false) Double centerLon,
            @RequestParam(name = "zoom", required = false) Integer zoom,
            @RequestParam(name = "gridSize", required = false) Integer gridSize
    ) {
        String html = "<!DOCTYPE html>" +
                "<html lang='en'>" +
                "<head>" +
                "  <meta charset='utf-8'/>" +
                "  <meta name='viewport' content='width=device-width, initial-scale=1'/>" +
                "  <title>Preparing map…</title>" +
                "  <style>html,body{height:100%;margin:0} body{display:flex;align-items:center;justify-content:center;font-family:sans-serif;background:#0b1220;color:#eef} .box{background:#111a2b;border:1px solid #223255;border-radius:10px;padding:20px 24px;max-width:560px;text-align:center;box-shadow:0 10px 30px rgba(0,0,0,0.35)} h3{margin:0 0 10px 0;font-weight:600} .muted{color:#9ab} progress{width:100%;height:12px} .row{margin-top:10px} .small{font-size:12px;color:#9ab}</style>" +
                "</head>" +
                "<body>" +
                "  <div class='box'>" +
                "    <h3>Loading…</h3>" +
                "    <div id='msg' class='muted'>Preparing tiles for selected overlay(s). This may take a moment on first view.</div>" +
                "    <div class='row'><progress id='prog' max='100' value='5'></progress></div>" +
                "    <div class='row small' id='detail'></div>" +
                "  </div>" +
                "  <script>" +
                "    const overlays=('" + overlays + "'||'').split(',').map(s=>s.trim()).filter(Boolean);" +
                "    const centerLat=" + (centerLat == null ? "null" : ("" + centerLat)) + ";" +
                "    const centerLon=" + (centerLon == null ? "null" : ("" + centerLon)) + ";" +
                "    const zoom=" + (zoom == null ? "null" : ("" + zoom)) + ";" +
                "    const gridSize=" + (gridSize == null ? "null" : ("" + gridSize)) + ";" +
                "    const prog=document.getElementById('prog'); const msg=document.getElementById('msg'); const detail=document.getElementById('detail');" +
                "    function degPadForTiles(z, tiles){ const n = Math.pow(2, z); return tiles * (360.0 / n); }" +
                "    function normObsString(s){ if(!s) return ''; s = String(s).trim(); if(!s) return ''; s = s.replace(/\\s+/g,''); if(s.indexOf(';')>=0) return s; const parts = s.split(','); if(parts.length===2) return s; return s; }" +
                "    function bboxFromObservers(obsStr, z){ const s = normObsString(obsStr); const pts=s.split(';').map(t=>t.trim()).filter(Boolean).map(p=>p.split(',').map(Number)); if(pts.length===0) return null; let minLat=90,maxLat=-90,minLon=180,maxLon=-180; pts.forEach(([la,lo])=>{ if(!isFinite(la)||!isFinite(lo)) return; minLat=Math.min(minLat,la); maxLat=Math.max(maxLat,la); minLon=Math.min(minLon,lo); maxLon=Math.max(maxLon,lo); }); const useZ = Number.isFinite(z)? z : 12; const radiusTiles = Number.isFinite(gridSize)? Math.floor(gridSize/2) : 4; const padDeg = degPadForTiles(useZ, radiusTiles); return {minLat:minLat-padDeg, maxLat:maxLat+padDeg, minLon:minLon-padDeg, maxLon:maxLon+padDeg}; }" +
                "    async function prefetchFor(def){ let obs = normObsString(def.observers||''); if((!obs || !obs.includes(',')) && Number.isFinite(centerLat) && Number.isFinite(centerLon)){ obs = centerLat.toFixed(6)+','+centerLon.toFixed(6); } const bbox=bboxFromObservers(obs, zoom||12) || (Number.isFinite(centerLat)&&Number.isFinite(centerLon)? (function(){ const useZ=zoom||12; const radiusTiles = Number.isFinite(gridSize)? Math.floor(gridSize/2) : 4; const pad=degPadForTiles(useZ, radiusTiles); return {minLat:centerLat-pad, minLon:centerLon-pad, maxLat:centerLat+pad, maxLon:centerLon+pad}; })() : null); if(!bbox){ return; } const zCenter = zoom||12; const zMin = Math.max(5, zCenter-2); const zMax = Math.min(18, zCenter+1); const params=new URLSearchParams({ minLat: bbox.minLat, minLon: bbox.minLon, maxLat: bbox.maxLat, maxLon: bbox.maxLon, zMin: String(zMin), zMax: String(zMax) }); if(obs) params.append('observers', obs); if(def.id) params.append('overlayId', def.id); if(def.agl!=null) params.append('agl', String(def.agl)); if(def.angleBins!=null) params.append('angleBins', String(def.angleBins)); if(def.base) params.append('base', def.base); if(def.colorHex) params.append('color', def.colorHex); if(Number.isFinite(gridSize)) params.append('gridSize', String(gridSize)); detail.textContent='Prefetching '+(def.name||def.id)+'…'; try{ const res=await fetch('/tiles/los/prefetch?'+params.toString()); if(res.ok){ const j=await res.json(); detail.textContent = 'Prefetched '+(def.name||def.id)+': '+j.rendered+' of '+j.total+' tiles'; return j; } else { detail.textContent='Prefetch failed for '+(def.name||def.id); } }catch(e){ console.error(e); } }" +
                "    async function run(){ if(overlays.length===0){ window.location='/map'; return;} msg.textContent='Warming up tiles…'; prog.value=10; let anyWork=false; const start=Date.now(); try{ const resp=await fetch('/overlays'); const all=await resp.json(); const defs=overlays.map(id=>{const d=all[id]||{}; d.id=id; return d;}); let done=0; for(const def of defs){ const j = await prefetchFor(def); if(j && (j.rendered>0 || j.total>0)) anyWork=true; done++; prog.value = 10 + Math.round(80*done/defs.length); } } catch(e){ console.error(e); } const elapsed=Date.now()-start; const minWait=1500; if(elapsed<minWait){ await new Promise(r=>setTimeout(r, minWait-elapsed)); } if(!anyWork){ detail.textContent='No tiles reported yet; continuing to map.'; } msg.textContent='Opening map…'; prog.value=100; setTimeout(()=>{ const next='/map?overlays='+overlays.map(encodeURIComponent).join(',') + (centerLat!=null? '&centerLat='+encodeURIComponent(centerLat):'') + (centerLon!=null? '&centerLon='+encodeURIComponent(centerLon):'') + (zoom!=null? '&zoom='+encodeURIComponent(zoom):'') + (gridSize!=null? '&gridSize='+encodeURIComponent(gridSize):''); window.location = next; }, 300); }" +
                "    run();" +
                "  </script>" +
                "</body></html>";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        return new ResponseEntity<>(html, headers, HttpStatus.OK);
    }
}
