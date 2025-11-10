package com.sidpatchy.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping
public class LosSelectPageController {

    @GetMapping("/los/select")
    public ResponseEntity<String> page() {
        String html = "<!DOCTYPE html>" +
                "<html lang='en'>" +
                "<head>" +
                "  <meta charset='utf-8'/>" +
                "  <meta name='viewport' content='width=device-width, initial-scale=1'/>" +
                "  <title>Select LoS</title>" +
                "  <style>html,body{height:100%;margin:0} body{font-family:sans-serif;font-size:14px;background:#f6f7fb;color:#222} .wrap{max-width:900px;margin:24px auto;padding:16px;background:#fff;border-radius:10px;box-shadow:0 6px 24px rgba(0,0,0,.08)} .row{margin:10px 0} input[type=text]{width:100%;padding:8px 10px;border:1px solid #d0d5dd;border-radius:6px;font-size:14px} .btn{padding:10px 14px;background:#1976d2;color:#fff;border:none;border-radius:6px;cursor:pointer} .list{max-height:420px;overflow:auto;border:1px solid #e7e8ea;border-radius:6px} .item{padding:8px 10px;border-bottom:1px solid #eee;display:flex;gap:8px;align-items:center} .item:last-child{border-bottom:none} .muted{color:#666;font-size:12px}</style>" +
                "</head>" +
                "<body>" +
                "  <div class='wrap'>" +
                "    <h3 style='margin-top:0'>Select LoS overlays</h3>" +
                "    <div class='row'><input id='search' type='text' placeholder='Search by name or ID'></div>" +
                "    <div id='list' class='list'></div>" +
                "    <div class='row'><button id='open' class='btn'>Open selected on map</button> <span id='status' class='muted'></span></div>" +
                "  </div>" +
                "  <script>" +
                "    const $=(id)=>document.getElementById(id); const state={ items:[], sel:new Set() };" +
                "    function render(){ const q=$('search').value.trim().toLowerCase(); const list=$('list'); list.innerHTML=''; const items=state.items.filter(it=> !q || (it.id.toLowerCase().includes(q) || (it.name||'').toLowerCase().includes(q))); if(items.length===0){ list.innerHTML='<div class\\'item\\'><em class\\'muted\\'>No overlays</em></div>'; return;} items.forEach(it=>{ const div=document.createElement('div'); div.className='item'; const cb=document.createElement('input'); cb.type='checkbox'; cb.checked=state.sel.has(it.id); cb.onchange=()=>{ if(cb.checked) state.sel.add(it.id); else state.sel.delete(it.id); }; const label=document.createElement('label'); label.style.flex='1'; label.textContent=(it.name? it.name+' ' : '')+'['+it.id+']'; const small=document.createElement('span'); small.className='muted'; small.textContent=' observers='+it.observers; label.appendChild(document.createElement('br')); label.appendChild(small); div.appendChild(cb); div.appendChild(label); list.appendChild(div); }); }" +
                "    async function load(){ $('status').textContent='Loading...'; try{ const res=await fetch('/overlays'); const j=await res.json(); const arr=[]; for(const id in j){ if(Object.prototype.hasOwnProperty.call(j,id)){ const it=j[id]; arr.push({ id, name: it.name||'', observers: it.observers||'' }); } } arr.sort((a,b)=> (a.name||'').localeCompare(b.name||'')); state.items=arr; $('status').textContent=''; render(); } catch(e){ console.error(e); $('status').textContent='Failed to load'; } }" +
                "    $('search').addEventListener('input', ()=>{ render(); });" +
                "    $('open').onclick=()=>{ if(state.sel.size===0){ $('status').textContent='No overlays selected'; return;} const ids=[...state.sel]; window.location = '/los/loading?overlays='+ids.map(encodeURIComponent).join(','); };" +
                "    load();" +
                "  </script>" +
                "</body></html>";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_HTML);
        return new ResponseEntity<>(html, headers, HttpStatus.OK);
    }
}
