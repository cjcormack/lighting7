// Generates the editor-kit design artboards: one editor kit for the programmer and the busk view —
// Spread (née Fan), the colour editor, the value editors, and where the code goes.
// `node gen.mjs` rewrites every `.dc.html`, `canvas.json` and the seeded artifact `editor-kit.html`.
// Dark-only, in the busk-further record's vocabulary (its stylesheet is the base of CSS below).
import { writeFileSync, readFileSync } from 'node:fs'

// ---- tokens (dark) ------------------------------------------------------------------------------
const T = {
  bg: 'oklch(0.141 0.005 285.823)',
  card: 'oklch(0.21 0.006 285.885)',
  muted: 'oklch(0.274 0.006 286.033)',
  border: 'oklch(0.274 0.006 286.033)',
  fg: 'oklch(0.985 0 0)',
  mfg: 'oklch(0.705 0.015 286.067)',
  primary: 'oklch(0.623 0.214 259.815)',
  pfg: 'oklch(0.21 0.006 285.885)',
  amber: 'oklch(0.828 0.189 84.429)',
  green: '#4ade80',
  red: 'oklch(0.704 0.191 22.216)',
  violet: 'oklch(0.606 0.25 292.717)',
}

// ---- icons (lucide, 24-grid) --------------------------------------------------------------------
const P = {
  wave: 'M2 12c2-5 4-5 6 0s4 5 6 0 4-5 6 0',
  pipette: 'm3 21 9-9M13 5l6 6M14 4a2 2 0 0 1 3 0l3 3a2 2 0 0 1 0 3l-2 2-6-6 2-2Z',
  save: 'M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2ZM17 21v-8H7v8M7 3v5h8',
  swap: 'M8 3 4 7l4 4M4 7h16M16 21l4-4-4-4M20 17H4',
  pencil: 'M21.17 6.83 17.17 2.83a2 2 0 0 0-2.83 0L3 14v5h5L21.17 9.66a2 2 0 0 0 0-2.83zM15 5l4 4',
  backspace: 'M10 5a2 2 0 0 0-1.34.51L2 12l6.66 6.49A2 2 0 0 0 10 19h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2zm2 4 6 6m0-6-6 6',
  crosshair: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20ZM22 12h-4M6 12H2M12 6V2M12 22v-4',
  flashlight: 'M18 6c0 2-2 2-2 4v10a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2V10c0-2-2-2-2-4V2h12zM6 6h12M12 13v1',
  x: 'M18 6 6 18M6 6l12 12',
  check: 'M9 12l2 2 4-4',
  chevR: 'm9 18 6-6-6-6',
  chevD: 'm6 9 6 6 6-6',
  marquee: 'M5 3a2 2 0 0 0-2 2M19 3a2 2 0 0 1 2 2M21 19a2 2 0 0 1-2 2M5 21a2 2 0 0 1-2-2M9 3h1M9 21h2M14 3h1M3 9v1M21 9v2M3 14v1m9-3 4 10 1.7-4.3L22 16Z',
  fan: 'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6ZM3 12h6M15 12h6',
  layers: 'm12 2 9 4.5-9 4.5-9-4.5ZM3 12l9 4.5 9-4.5M3 17l9 4.5 9-4.5',
  grid: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z',
  plus: 'M5 12h14M12 5v14',
  palette: 'M12 22a10 10 0 1 1 0-20c5.5 0 10 3.6 10 8a4 4 0 0 1-4 4h-1.5a2 2 0 0 0-1.5 3.3c.4.5.6 1 .6 1.7a2 2 0 0 1-2 2ZM7.5 10.5h.01M12 7h.01M16.5 10.5h.01',
  clock: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18ZM12 7v5l3 2',
  play: 'M6 4v16l14-8Z',
  target: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm0 5a5 5 0 1 0 0 10 5 5 0 0 0 0-10Zm0 4a1 1 0 1 0 0 2 1 1 0 0 0 0-2Z',
  book: 'M4 5.5A2.5 2.5 0 0 1 6.5 3H20v18H6.5A2.5 2.5 0 0 0 4 18.5ZM12 7v10',
  panel: 'M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2ZM15 3v18',
  chevL: 'm15 18-6-6 6-6',
  sort: 'M3 6h18M7 12h10M11 18h2',
  updown: 'm7 3-4 4 4 4M3 7h14M17 21l4-4-4-4M21 17H7',
}
const ico = (name, size = 14, style = '') =>
  `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="flex:0 0 auto;${style}"><path d="${P[name]}"/></svg>`

// ---- stylesheet: the busk-further record's, plus what these boards add -------------------------
const CSS = `
    body { margin: 0; }
    a { color: oklch(0.707 0.165 254.624); }
    .app { --fg:${T.fg}; --card:${T.card}; --muted:${T.muted}; --mfg:${T.mfg}; --bd:${T.border}; --pri:${T.primary}; --bg:${T.bg}; --amber:${T.amber}; --green:${T.green}; --red:${T.red};
      font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", "Helvetica Neue", sans-serif; -webkit-font-smoothing: antialiased; background:var(--bg); color:var(--fg); box-sizing:border-box; }
    .app * { box-sizing:border-box; }
    .mono { font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace; }
    .num { font-variant-numeric: tabular-nums; }
    .lbl { font-size:9px; font-weight:700; text-transform:uppercase; letter-spacing:0.08em; color:var(--mfg); white-space:nowrap; }
    .h1 { font-size:22px; font-weight:700; letter-spacing:-0.01em; }
    .h2 { font-size:13px; font-weight:700; letter-spacing:-0.005em; }
    .cap { font-size:11px; line-height:1.45; color:var(--mfg); margin:0; }
    .cap b { color:var(--fg); font-weight:600; }
    .note { border-radius:8px; border:1px solid var(--bd); background:var(--card); padding:9px 12px; }
    .note.key { border-color:oklch(0.45 0.16 300); background:oklch(0.27 0.09 302 / 0.25); }
    .note.warn { border-color:oklch(0.6 0.14 80); background:oklch(0.5 0.12 80 / 0.12); }
    .note.bad { border-color:oklch(0.55 0.16 25); background:oklch(0.5 0.15 25 / 0.12); }
    .note p { margin:0; font-size:11px; line-height:1.45; color:var(--mfg); }
    .note p + p { margin-top:5px; }
    .note b { color:var(--fg); font-weight:600; }
    .col { display:flex; flex-direction:column; gap:8px; min-width:0; flex:0 0 auto; }
    .row { display:flex; align-items:center; gap:8px; min-width:0; }
    .k { width:44px; font-size:11px; color:var(--mfg); flex:0 0 auto; }
    /* panels */
    .pop { border:1px solid var(--bd); border-radius:10px; background:var(--card); box-shadow:0 12px 40px rgba(0,0,0,0.6); display:flex; flex-direction:column; overflow:hidden; flex:0 0 auto; }
    .rail { border:1px solid var(--bd); border-radius:10px; background:var(--bg); display:flex; flex-direction:column; overflow:hidden; flex:0 0 auto; }
    .tabs { display:flex; align-items:stretch; height:40px; border-bottom:1px solid var(--bd); flex:0 0 auto; }
    .tabs span { flex:1; display:flex; align-items:center; justify-content:center; gap:6px; font-size:11px; font-weight:600; color:var(--mfg); border-bottom:2px solid transparent; margin-bottom:-1px; }
    .tabs span.on { color:var(--fg); border-bottom-color:var(--pri); }
    .tabs .fold { flex:0 0 40px; border-left:1px solid var(--bd); color:var(--mfg); }
    .body { display:flex; flex-direction:column; gap:8px; padding:12px 14px; flex:1 1 auto; min-height:0; }
    .foot { display:flex; align-items:center; gap:6px; border-top:1px solid var(--bd); padding:8px 12px; flex:0 0 auto; }
    .readout { display:flex; align-items:center; gap:8px; font-size:10px; color:var(--mfg); min-height:16px; }
    .readout .sw { width:14px; height:14px; border-radius:50%; border:1px solid var(--bd); flex:0 0 auto; }
    .seg { display:flex; align-items:center; gap:2px; border:1px solid var(--bd); background:var(--card); border-radius:8px; padding:2px; min-width:0; }
    .seg span { flex:1; display:inline-flex; align-items:center; justify-content:center; gap:5px; height:24px; padding:0 6px; border-radius:6px; font-size:11px; font-weight:600; color:var(--mfg); white-space:nowrap; }
    .seg span.on { background:var(--muted); color:var(--fg); }
    .seg span.off { opacity:0.45; }
    .field { height:28px; padding:0 8px; border-radius:8px; border:1px solid var(--bd); background:oklch(0.274 0.006 286.033 / 0.3); font-size:12px; display:flex; align-items:center; gap:6px; min-width:0; white-space:nowrap; }
    .field.focus { border-color:var(--pri); box-shadow:0 0 0 3px oklch(0.623 0.214 259.815 / 0.3); }
    .field.sel { background:oklch(0.623 0.214 259.815 / 0.25); }
    .field .u { color:var(--mfg); font-size:10px; margin-left:auto; }
    .end { flex:1; height:32px; padding:0 8px; border-radius:8px; border:1px solid var(--bd); background:var(--card); display:flex; align-items:center; gap:6px; font-size:12px; min-width:0; }
    .end.on { border-color:var(--pri); background:oklch(0.623 0.214 259.815 / 0.1); }
    .end .w { font-size:10px; font-weight:700; text-transform:uppercase; color:var(--mfg); }
    .end .sw { width:14px; height:14px; border-radius:50%; border:1px solid var(--bd); flex:0 0 auto; }
    .btn { display:inline-flex; align-items:center; justify-content:center; gap:6px; height:28px; padding:0 10px; border-radius:8px; font-size:12px; font-weight:500; white-space:nowrap; color:var(--fg); flex:0 0 auto; }
    .btn.out { border:1px solid var(--bd); background:oklch(0.274 0.006 286.033 / 0.3); }
    .btn.pri { background:var(--pri); color:var(--pfg, oklch(0.21 0.006 285.885)); font-weight:600; }
    .btn.ghost { color:var(--mfg); }
    .btn.dim { opacity:0.5; }
    .btn.h32 { height:32px; padding:0 12px; }
    .btn.icon { width:28px; padding:0; }
    .slider { position:relative; height:6px; border-radius:3px; background:var(--muted); flex:1; min-width:0; }
    .slider i { position:absolute; top:-5px; width:16px; height:16px; border-radius:50%; background:var(--fg); box-shadow:0 0 0 1px var(--bd); margin-left:-8px; }
    .slider b { position:absolute; left:0; top:0; bottom:0; border-radius:3px; background:var(--pri); }
    .curve { display:grid; grid-template-columns:repeat(4, 1fr); gap:6px; }
    .cv { display:flex; flex-direction:column; align-items:center; gap:4px; padding:6px 4px; border:1px solid var(--bd); border-radius:8px; background:var(--card); }
    .cv.on { border-color:var(--pri); background:oklch(0.623 0.214 259.815 / 0.12); }
    .cv svg { width:44px; height:22px; }
    .cv span { font-size:10px; font-weight:600; color:var(--mfg); }
    .cv.on span { color:var(--fg); }
    .picker { position:relative; border-radius:6px; border:1px solid var(--bd); background:linear-gradient(to top, #000, rgba(0,0,0,0)), linear-gradient(to right, #fff, hsl(38 100% 50%)); }
    .picker .knob { position:absolute; width:20px; height:20px; margin:-10px 0 0 -10px; border-radius:50%; border:2px solid #fff; box-shadow:0 0 0 1px rgba(0,0,0,0.6); }
    .hue { position:relative; height:12px; border-radius:6px; margin-top:16px; background:linear-gradient(to right, #f00, #ff0, #0f0, #0ff, #00f, #f0f, #f00); }
    .hue .knob { position:absolute; top:-4px; width:20px; height:20px; margin-left:-10px; border-radius:50%; border:2px solid #fff; box-shadow:0 0 0 1px rgba(0,0,0,0.6); }
    .nf { display:flex; align-items:center; gap:6px; }
    .nf .l { width:16px; font-size:10px; font-weight:700; color:var(--mfg); }
    .nf .field { flex:1; }
    .em { display:flex; align-items:center; gap:8px; font-size:11px; }
    .em .dot { width:8px; height:8px; border-radius:50%; border:1px solid var(--bd); flex:0 0 auto; }
    .em .n { width:36px; display:flex; align-items:center; gap:5px; flex:0 0 auto; }
    .em .v { width:34px; height:24px; padding:0 4px; font-size:11px; justify-content:flex-end; }
    .em .c { width:36px; text-align:right; font-size:9px; color:var(--mfg); flex:0 0 auto; }
    .chip { display:inline-flex; align-items:center; gap:6px; height:28px; padding:0 8px; border-radius:6px; border:1px solid var(--bd); background:var(--card); font-size:11.5px; white-space:nowrap; flex:0 0 auto; }
    .chip .sw { width:12px; height:12px; border-radius:3px; border:1px solid oklch(0.274 0.006 286.033 / 0.6); }
    .pill { display:inline-flex; align-items:center; height:20px; padding:0 8px; border-radius:999px; border:1px solid var(--bd); font-size:10px; color:var(--fg); white-space:nowrap; flex:0 0 auto; }
    kbd { border-radius:4px; border:1px solid var(--bd); background:oklch(0.274 0.006 286.033 / 0.5); padding:0 5px; font-size:9.5px; font-family:ui-monospace,Menlo,monospace; }
    /* the programmer slice */
    .rowc { height:40px; display:flex; align-items:center; gap:8px; padding:0 12px; border-bottom:1px solid var(--bd); background:oklch(0.985 0 0 / 0.05); flex:0 0 auto; }
    .rowc .cnt { font-size:12px; font-weight:600; white-space:nowrap; }
    .rowc .dot { color:oklch(0.705 0.015 286.067 / 0.5); }
    .vbtn { display:inline-flex; align-items:center; gap:6px; height:32px; padding:0 10px; border-radius:6px; border:1px solid var(--bd); background:oklch(0.274 0.006 286.033 / 0.3); font-size:12px; font-weight:500; white-space:nowrap; flex:0 0 auto; }
    .vbtn.on { border-color:var(--pri); background:oklch(0.623 0.214 259.815 / 0.15); }
    .vbtn.ghost { border-color:transparent; background:transparent; color:var(--mfg); }
    .ghdr { height:30px; display:flex; border-bottom:1px solid var(--bd); background:var(--bg); flex:0 0 auto; }
    .ghdr span { padding:0 8px; display:flex; align-items:center; font-size:11px; font-weight:500; text-transform:uppercase; letter-spacing:0.05em; color:var(--mfg); flex:0 0 auto; }
    .grow { height:36px; display:flex; align-items:center; border-bottom:1px solid var(--bd); font-size:13px; flex:0 0 auto; background:var(--bg); }
    .grow .nm { padding:0 8px; display:flex; align-items:center; gap:6px; height:100%; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; flex:0 0 auto; }
    .grow.sel { background:oklch(0.985 0 0 / 0.06); box-shadow:inset 3px 0 0 var(--fg); }
    .grow.sel .nm { font-weight:600; }
    .cell { padding:2px 1px; height:100%; display:flex; flex:0 0 auto; }
    .cell > span { flex:1; display:flex; align-items:center; gap:6px; padding:0 8px; border-radius:4px; font-size:12px; color:var(--mfg); }
    .cell.you > span { box-shadow:inset 0 0 0 1px var(--pri); background:oklch(0.623 0.214 259.815 / 0.10); }
    .cell.mq > span { box-shadow:inset 0 0 0 1px var(--fg); background:oklch(0.985 0 0 / 0.08); }
    .cell.open > span { box-shadow:inset 0 0 0 2px var(--pri); }
    .cell .bar { position:relative; flex:1; height:6px; border-radius:999px; background:var(--muted); overflow:hidden; }
    .cell .bar b { position:absolute; inset:0 auto 0 0; background:var(--pri); border-radius:999px; }
    .cell .sw { width:16px; height:16px; border-radius:4px; border:1px solid var(--bd); flex:0 0 auto; }
    .cell .xy { width:16px; height:16px; border-radius:3px; border:1px solid var(--bd); background:oklch(0.274 0.006 286.033 / 0.5); position:relative; flex:0 0 auto; }
    .cell .xy i { position:absolute; width:4px; height:4px; border-radius:50%; background:var(--pri); margin:-2px 0 0 -2px; }
    .xypad { position:relative; border-radius:8px; border:1px solid var(--bd); background:oklch(0.274 0.006 286.033 / 0.3); background-image:linear-gradient(var(--bd) 1px, transparent 1px), linear-gradient(90deg, var(--bd) 1px, transparent 1px); background-size:25% 25%; }
    .xypad i { position:absolute; width:14px; height:14px; margin:-7px 0 0 -7px; border-radius:50%; background:var(--pri); box-shadow:0 0 0 2px var(--bg); }
    .list { display:flex; flex-direction:column; }
    .list span { display:flex; align-items:center; gap:8px; padding:6px 8px; border-radius:6px; font-size:12px; }
    .list span.on { background:var(--muted); }
    .list .sw { width:12px; height:12px; border-radius:3px; border:1px solid var(--bd); }
    table { border-collapse:collapse; width:100%; table-layout:fixed; font-size:11px; line-height:1.4; }
    th { text-align:left; font-size:9px; font-weight:700; text-transform:uppercase; letter-spacing:0.08em; color:var(--mfg); padding:6px 8px; border-bottom:1px solid var(--bd); background:oklch(0.274 0.006 286.033 / 0.3); }
    td { padding:6px 8px; border-bottom:1px solid var(--bd); vertical-align:top; color:var(--mfg); }
    td b { color:var(--fg); font-weight:600; }
    td.y { color:var(--fg); }
    .kv { display:grid; grid-template-columns:150px 1fr; gap:3px 10px; font-size:11px; line-height:1.4; }
    .kv .kk { color:var(--mfg); font-family: ui-monospace, Menlo, monospace; font-size:10.5px; }
    .tag { display:inline-flex; align-items:center; height:16px; padding:0 6px; border-radius:999px; font-size:9px; font-weight:700; letter-spacing:0.06em; text-transform:uppercase; white-space:nowrap; }
    .tag.go { background:oklch(0.5 0.15 150 / 0.3); color:#86efac; }
    .tag.ask { background:oklch(0.5 0.12 80 / 0.3); color:oklch(0.9 0.15 85); }
    .tag.no { background:oklch(0.5 0.15 25 / 0.25); color:oklch(0.85 0.12 25); }
    .tag.be { background:oklch(0.45 0.16 300 / 0.35); color:oklch(0.85 0.1 300); }
    .scrim { position:absolute; inset:0; background:rgba(0,0,0,0.35); }
    .cap9 { font-size:9.5px; color:var(--mfg); line-height:1.35; }
`
const HEAD = `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
  <style>${CSS}</style>
</helmet>`
const TAIL = (w, h) => `</x-dc>
<script data-dc-script data-props='{"$preview":{"width":${w},"height":${h}}}'>
class Component extends DCLogic {
  renderVals() { return {}; }
}
</script>
</body>
</html>`

// Board heights, measured in a browser after each layout change (the `.app` scrollHeight at 1440).
const H = { Survey: 2730, Spread: 1940, Colour: 1910, Editors: 1140, Model: 2010, RailTabs: 3040 }

// ---- primitives ---------------------------------------------------------------------------------
const seg = (items, on, extra = '') => `<div class="seg" style="${extra}">${items.map((t) => `<span class="${t === on ? 'on' : ''}${typeof t === 'object' ? ' off' : ''}">${typeof t === 'object' ? t.t : t}</span>`).join('')}</div>`
const lbl = (t, extra = '') => `<span class="lbl" style="${extra}">${t}</span>`
const btn = (label, kind = 'out', icon = null, extra = '') => `<span class="btn ${kind}" style="${extra}">${icon ? ico(icon, 12) : ''}${label ? `<span>${label}</span>` : ''}</span>`
const liveBtn = (on) => `<span class="btn ${on ? 'pri' : 'out'}"><span style="width:8px; height:8px; border-radius:50%; background:${on ? T.pfg : 'oklch(0.705 0.015 286.067 / 0.6)'}; display:inline-block;"></span>Live</span>`
const field = (t, extra = '', cls = '') => `<div class="field ${cls}" style="${extra}">${t}</div>`
const swatch = (c, size = 14) => `<span style="width:${size}px; height:${size}px; border-radius:50%; background:${c}; border:1px solid var(--bd); flex:0 0 auto; display:inline-block;"></span>`
const note = (html, kind = '') => `<div class="note ${kind}"><p>${html}</p></div>`
const notes = (list, kind = '') => `<div class="note ${kind}">${list.map((p) => `<p>${p}</p>`).join('')}</div>`
const title = (h1, cap) => `<div><div class="h1">${h1}</div><p class="cap" style="margin-top:4px; max-width:1180px;">${cap}</p></div>`
const sec = (t) => `<div class="row" style="gap:10px;"><span class="lbl" style="font-size:10px;">${t}</span><span style="flex:1; height:1px; background:var(--bd);"></span></div>`
const sliderRow = (pct, fill = true) => `<div class="slider">${fill ? `<b style="width:${pct}%"></b>` : ''}<i style="left:${pct}%"></i></div>`
const curvePics = {
  Line: '<path d="M2 20 42 2"/>',
  Mirror: '<path d="M2 2 22 20 42 2"/>',
  Arrow: '<path d="M2 20 22 2 42 20"/>',
  Wings: '<path d="M2 2 19 20M25 20 42 2M22 16v5"/>',
}
const curveRow = (on = 'Line', compact = false) => `<div class="curve">${Object.entries(curvePics).map(([n, p]) => `<div class="cv ${n === on ? 'on' : ''}" style="${compact ? 'padding:4px 2px;' : ''}"><svg viewBox="0 0 44 22" fill="none" stroke="currentColor" stroke-width="2" style="${compact ? 'width:36px; height:18px;' : ''}">${p}</svg><span>${n}</span></div>`).join('')}</div>`
const tag = (t, k) => `<span class="tag ${k}">${t}</span>`
const cell9 = (t) => `<span class="cap9">${t}</span>`
const frameLabel = (t, sub = '') => `<div class="col" style="gap:2px; margin-bottom:2px;"><span class="lbl" style="font-size:10px;">${t}</span>${sub ? `<span class="cap9">${sub}</span>` : ''}</div>`

// ---- the colour picker body (one drawing; three variants) ----------------------------------------
function pickerSquare({ w, h, knob = [0.78, 0.18], hueAt = 0.1 }) {
  // A fluid square is a block at the column's full width — never `flex:1`, which in the column
  // that holds it governs the height and collapsed the square to a line.
  return `<div class="picker" style="height:${h}px; flex:0 0 auto; ${w ? `width:${w}px;` : 'width:100%; min-width:0;'}"><span class="knob" style="left:${knob[0] * 100}%; top:${knob[1] * 100}%;"></span></div><div class="hue"><span class="knob" style="left:${hueAt * 100}%;"></span></div>`
}
function rgbFields({ r = 245, g = 179, b = 66, seedR = false }) {
  const f = (l, v, cls = '') => `<div class="nf"><span class="l">${l}</span>${field(`<span class="mono num">${v}</span>`, 'height:28px; width:64px;', cls)}</div>`
  return `<div class="col" style="gap:6px; width:80px; flex:0 0 auto;">${f('R', r, seedR ? 'focus sel' : '')}${f('G', g)}${f('B', b)}</div>`
}
function emitterRows({ rows, counts = null, compact = false }) {
  const tints = { W: '#fffbe6', A: '#ffbf00', UV: '#7f00ff' }
  return `<div class="col" style="gap:${compact ? 10 : 7}px; ${compact ? 'min-width:13rem; flex:1;' : 'border-top:1px solid var(--bd); padding-top:8px;'}">${rows.map(([n, v, pct, c]) => `<div class="em"><span class="n"><span class="dot" style="background:${tints[n]};"></span>${n}</span>${sliderRow(pct)}${field(`<span class="mono num">${v}</span>`, '', 'v')}${counts ? `<span class="c">${c}</span>` : ''}</div>`).join('')}</div>`
}
/**
 * The colour editor, proposed: one body in every host.
 * host: 'popover' | 'docked' | 'endpoint' | 'sheet'
 */
function colourEditor({ width = 352, compact = false, recent = false, footer = true, counts = true, heads = '14 heads', mixed = false, hex = '#F5B342', seedR = false, host = 'popover', hostLine = null, pickerH = 200, tabs = null, iconVerbs = false }) {
  const rows = [['W', 80, 31, '6 of 14'], ['A', 160, 63, '4 of 14'], ['UV', 0, 0, '2 of 14']]
  const pickerRow = `<div class="row" style="align-items:flex-start; gap:12px; ${compact ? 'flex:1; min-width:16rem;' : ''}"><div class="col" style="flex:1; min-width:0; gap:0;">${pickerSquare({ h: compact ? 176 : pickerH })}</div>${rgbFields({ seedR })}</div>`
  const body = compact
    ? `<div class="row" style="align-items:flex-start; gap:16px; flex-wrap:wrap;">${pickerRow}${emitterRows({ rows, counts, compact: true })}</div>`
    : `${pickerRow}${emitterRows({ rows, counts })}`
  const readout = `<div class="readout">${counts ? `<span>Emitters on 6 of 14 heads · the rest take RGB only</span>` : ''}<span style="margin-left:auto; display:inline-flex; align-items:center; gap:6px;">${mixed ? `<span style="color:var(--amber);">mixed</span>` : ''}<span class="sw" style="background:${hex};"></span><span class="mono num" style="font-size:11px; color:var(--fg);">${hex}</span></span></div>`
  const recentRow = recent
    ? `<div class="col" style="gap:6px; border-top:1px solid var(--bd); padding-top:8px;">${lbl('Recent from templates')}<div class="row" style="gap:6px; flex-wrap:wrap;">${[['Warm Amber', '#f5b342'], ['Deep Blue', '#2456ff'], ['UV wash', '#7f00ff'], ['Pink', '#ff8ac9']].map(([n, c]) => `<span class="chip"><span class="sw" style="background:${c};"></span>${n}</span>`).join('')}</div></div>`
    : ''
  const foot = footer
    ? `<div class="foot">${btn('Save as template…', 'out', 'save')}<span style="flex:1;"></span>${iconVerbs ? btn(null, 'out icon', 'pipette') + btn(null, 'out icon', 'wave') : btn('Pick', 'out', 'pipette') + btn('Spread…', 'out', 'wave')}</div>`
    : ''
  const head = hostLine ? `<div class="readout" style="margin-bottom:2px;">${hostLine}</div>` : ''
  return `<div class="${host === 'docked' ? 'rail' : 'pop'}" style="width:${width}px;">${host === 'docked' ? tabs ?? `<div class="tabs"><span>${ico('clock', 12)}Speed</span><span class="on">${ico('palette', 12)}Colour</span><span>${ico('wave', 12)}Spread</span><span>${ico('play', 12)}Show</span><span class="fold">${ico('chevR', 14)}</span></div>` : ''}<div class="body" style="gap:8px;">${head}${body}${readout}${recentRow}</div>${foot}</div>`
}

// ---- the spread panel (one drawing; every host) ---------------------------------------------------
function endpoints({ kind = 'colour', editing = 'from', from = '#F5B342', to = '#2456FF', fromT = null, toT = null }) {
  if (kind === 'colour') {
    const e = (w, hex, name, on) => `<span class="end ${on ? 'on' : ''}"><span class="w">${w}</span><span class="sw" style="background:${hex};"></span><span class="mono num" style="overflow:hidden; text-overflow:ellipsis;">${name ?? hex}</span></span>`
    return `<div class="row" style="gap:6px;">${e('From', from, fromT, editing === 'from')}<span class="btn ghost icon">${ico('swap', 14)}</span>${e('To', to, toT, editing === 'to')}</div>`
  }
  const unit = kind === 'percent' ? '%' : kind === 'duration' ? 's' : ''
  if (kind === 'position') {
    // Four fields and the swap do not fit one row at the sheet's floor, so a position pair is two
    // rows — From over To, each Pan · Tilt — with the swap at the end of the second.
    const pair = (v) => `${field(`<span class="mono num">${v[0]}</span><span class="u">pan°</span>`, 'flex:1;')}${field(`<span class="mono num">${v[1]}</span><span class="u">tilt°</span>`, 'flex:1;')}`
    return `<div class="col" style="gap:6px;"><div class="row" style="gap:6px;">${lbl('From', 'width:34px;')}${pair(from)}<span style="width:28px; flex:0 0 auto;"></span></div><div class="row" style="gap:6px;">${lbl('To', 'width:34px;')}${pair(to)}<span class="btn ghost icon">${ico('swap', 14)}</span></div></div>`
  }
  const f = (w, v) => `<div class="col" style="gap:4px; flex:1; min-width:0;">${lbl(w)}${field(`<span class="mono num">${v}</span><span class="u">${unit}</span>`)}</div>`
  return `<div class="row" style="gap:6px; align-items:flex-end;">${f('From', from)}<span class="btn ghost icon" style="margin-bottom:2px;">${ico('swap', 14)}</span>${f('To', to)}</div>`
}
function spreadPanel({
  host = 'popover', width = 320, family = 'Colour', property = null, properties = null, kind = 'colour', editing = 'from',
  from, to, fromT = null, toT = null, curve = 'Line', order = 'Rig', parts = '1', over = 'Heads', cells = null, overOff = false,
  live = false, save = null, applyLabel = 'Apply', footerNote = null, picker = true, templateRow = true, headLine = null, compact = false, tabs: tabsOverride = null,
}) {
  const fams = seg(['Intensity', 'Colour', 'Position', 'Beam'], family)
  const props = properties ? seg(properties, property) : ''
  const ends = endpoints({ kind, editing, from, to, fromT, toT })
  const colourBody = kind === 'colour' && picker
    ? `<div class="row" style="align-items:flex-start; gap:12px;"><div class="col" style="flex:1; min-width:0; gap:0;">${pickerSquare({ h: compact ? 150 : 176 })}</div>${rgbFields({})}</div>${templateRow ? `<div class="row" style="gap:8px; flex-wrap:wrap;"><span class="row" style="gap:6px; flex:1; min-width:0; font-size:10px; color:var(--mfg);">or a template ${field(`<span style="color:var(--mfg);">— a colour —</span>${ico('chevD', 12, 'margin-left:auto; color:var(--mfg);')}`, 'flex:1; height:24px;')}</span>${seg(['Extract', 'Additive', 'RGB only'], 'Extract', 'flex:0 0 auto;')}</div>` : ''}`
    : ''
  const orderRow = `<div class="col" style="gap:4px;">${lbl('Order')}${seg(['Rig', 'Reverse', 'Centre', 'Random'], order)}<span class="cap9">Stage L→R: not on the desk yet</span></div>`
  const partsOver = `<div class="row" style="gap:12px; align-items:flex-end;"><div class="col" style="gap:4px; flex:1;">${lbl('Parts')}<div class="row" style="gap:4px;">${seg(['1', '2', '3', '4'], parts, 'flex:1;')}${field(`<span class="mono num">${parts}</span>`, 'width:48px; height:28px;')}</div></div><div class="col" style="gap:4px;">${lbl('Over')}${seg(['Heads', overOff ? { t: 'Cells' } : `Cells${cells ? ` <span class="mono num" style="color:var(--mfg); font-weight:500;">${cells}</span>` : ''}`], over)}</div></div>`
  const foot = `<div class="foot">${save ? btn(save, 'out', 'save') : ''}${footerNote ? `<span class="cap9" style="min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">${footerNote}</span>` : ''}<span style="flex:1;"></span>${liveBtn(live)}${btn(live ? 'Send again' : applyLabel, live ? 'out' : 'pri')}</div>`
  const tabs = host === 'docked' ? tabsOverride ?? `<div class="tabs"><span>${ico('clock', 12)}Speed</span><span>${ico('palette', 12)}Colour</span><span class="on">${ico('wave', 12)}Spread</span><span>${ico('play', 12)}Show</span><span class="fold">${ico('chevR', 14)}</span></div>` : ''
  const head = headLine ? `<div class="readout" style="margin-bottom:-2px;">${headLine}</div>` : ''
  return `<div class="${host === 'docked' ? 'rail' : 'pop'}" style="width:${width}px;">${tabs}<div class="body">${head}${fams}${props}${ends}${colourBody}<div class="col" style="gap:4px;">${lbl('Curve')}${curveRow(curve, compact)}</div>${orderRow}${partsOver}</div>${foot}</div>`
}

// ---- the programmer slice: row C + a few rows, the marquee on one column ---------------------------
const COLS = ['Dimmer', 'Colour', 'Position', 'Zoom', 'Strobe']
function rowC({ verb = 'Spread', verbOn = false, cells = '4 cells', fixtures = '4 fixtures', family = 'Colour', chips = true, words = true }) {
  const v = (n, icon, on = false, ghost = false) => `<span class="vbtn ${on ? 'on' : ''} ${ghost ? 'ghost' : ''}">${ico(icon, 14)}${words ? `<span>${n}</span>` : ''}</span>`
  const strip = chips ? `<span style="width:1px; height:22px; background:var(--bd); flex:0 0 auto;"></span><div class="row" style="gap:6px; flex:1 1 0; min-width:0; overflow:hidden; -webkit-mask-image:linear-gradient(to right, black calc(100% - 24px), transparent); mask-image:linear-gradient(to right, black calc(100% - 24px), transparent);">${[['Warm wash', 'oklch(0.75 0.16 60)'], ['Amber', 'oklch(0.8 0.17 80)'], ['UV blue', 'oklch(0.55 0.2 300)'], ['Deep red', 'oklch(0.7 0.19 20)']].map(([n, c]) => `<span class="chip"><span class="sw" style="background:${c};"></span>${n}</span>`).join('')}</div><span class="chip" style="background:oklch(0.274 0.006 286.033 / 0.3);">${ico('grid', 14)}All <span class="mono" style="font-size:9px; background:var(--muted); border-radius:999px; padding:0 5px;">84</span></span><span class="chip" style="border-style:dashed; background:transparent;">${ico('plus', 14)}New</span>` : '<span style="flex:1;"></span>'
  return `<div class="rowc">${ico('marquee', 14)}<span class="cnt">${fixtures}</span><span class="dot">·</span><span class="cnt">${cells}</span><span class="pill">${family}</span><span class="row" style="gap:4px; font-size:10px; color:var(--mfg);"><kbd>⏎</kbd> edit <kbd style="margin-left:4px;">⌫</kbd> clear</span>${strip}<div class="row" style="gap:8px; flex:0 0 auto; margin-left:auto;">${v('Set', 'pencil')}${v('Clear', 'backspace')}${v(verb, 'wave', verbOn)}${v('Locate', 'crosshair')}${v('Highlight', 'flashlight')}${v('Deselect', 'x', false, true)}</div></div>`
}
function gridSlice({ nameW = 220, colW = 150, rows, marqueeCol = 'Colour', openRow = -1 }) {
  const hdr = `<div class="ghdr"><span style="width:${nameW}px;">Fixture</span>${COLS.map((c) => `<span style="width:${colW}px;">${c}</span>`).join('')}</div>`
  const dash = `<span class="cell" style="width:${colW}px;"><span>—</span></span>`
  const bar = (pct, cls = '') => `<span class="cell ${cls}" style="width:${colW}px;"><span><span class="bar"><b style="width:${pct}%"></b></span><span class="num" style="width:32px; text-align:right;">${pct}%</span></span></span>`
  const sw = (c, cls = '', t = '') => `<span class="cell ${cls}" style="width:${colW}px;"><span><span class="sw" style="background:${c};"></span>${t}</span></span>`
  const xy = (x, y, cls = '', t = '') => `<span class="cell ${cls}" style="width:${colW}px;"><span><span class="xy"><i style="left:${x}%; top:${y}%;"></i></span><span class="num">${t}</span></span></span>`
  const r = (name, cells, sel) => `<div class="grow ${sel ? 'sel' : ''}"><span class="nm" style="width:${nameW}px;">${name}</span>${cells}</div>`
  return `<div class="col" style="gap:0;">${hdr}${rows.map((row, i) => {
    const mq = row.sel ? 'mq' : ''
    const open = i === openRow ? 'open' : ''
    const c = {
      Dimmer: row.dim == null ? dash : bar(row.dim, marqueeCol === 'Dimmer' ? `${mq} ${open}` : row.you ? 'you' : ''),
      Colour: row.col == null ? dash : sw(row.col, marqueeCol === 'Colour' ? `${mq} ${open}` : row.you ? 'you' : '', row.mixed ? 'Mixed' : ''),
      Position: row.pos == null ? dash : xy(row.pos[0], row.pos[1], marqueeCol === 'Position' ? `${mq} ${open}` : '', row.posT ?? ''),
      Zoom: row.zoom == null ? dash : bar(row.zoom, marqueeCol === 'Zoom' ? `${mq} ${open}` : ''),
      Strobe: dash,
    }
    return r(row.name, COLS.map((k) => c[k]).join(''), row.sel)
  }).join('')}</div>`
}
const RIG_ROWS = [
  { name: 'Front wash 1', dim: 80, col: 'oklch(0.75 0.16 60)', pos: [50, 50], zoom: 40, sel: true },
  { name: 'Front wash 2', dim: 80, col: 'oklch(0.75 0.16 60)', pos: [50, 50], zoom: 40, sel: true },
  { name: 'Front wash 3', dim: 80, col: 'oklch(0.75 0.16 60)', pos: [50, 50], zoom: 40, sel: true },
  { name: 'Front wash 4', dim: 80, col: 'oklch(0.75 0.16 60)', pos: [50, 50], zoom: 40, sel: true },
  { name: 'LED Bar L', dim: 60, col: 'oklch(0.55 0.25 320)', mixed: true, pos: null, zoom: null, sel: false },
  { name: 'Mover 1', dim: 100, col: 'oklch(0.9 0.02 90)', pos: [30, 60], posT: '128,64', zoom: 55, sel: false },
  { name: 'Mover 2', dim: 100, col: 'oklch(0.9 0.02 90)', pos: [70, 60], posT: '192,64', zoom: 55, sel: false },
  { name: 'LED Bar R', dim: 60, col: 'oklch(0.55 0.25 320)', mixed: true, pos: null, zoom: null, sel: false },
  { name: 'Side wash L', dim: 0, col: 'oklch(0.3 0.02 260)', pos: null, zoom: null, sel: false },
  { name: 'Side wash R', dim: 0, col: 'oklch(0.3 0.02 260)', pos: null, zoom: null, sel: false },
  { name: 'Cyc 1', dim: 45, col: 'oklch(0.6 0.2 240)', pos: null, zoom: null, sel: false },
  { name: 'Cyc 2', dim: 45, col: 'oklch(0.6 0.2 240)', pos: null, zoom: null, sel: false },
  { name: 'Cyc 3', dim: 45, col: 'oklch(0.6 0.2 240)', pos: null, zoom: null, sel: false },
  { name: 'Cyc 4', dim: 45, col: 'oklch(0.6 0.2 240)', pos: null, zoom: null, sel: false },
  { name: 'Spot 1', dim: 0, col: null, pos: [50, 50], posT: '128,128', zoom: 20, sel: false },
  { name: 'Spot 2', dim: 0, col: null, pos: [50, 50], posT: '128,128', zoom: 20, sel: false },
  { name: 'Blinder L', dim: 0, col: null, pos: null, zoom: null, sel: false },
  { name: 'Blinder R', dim: 0, col: null, pos: null, zoom: null, sel: false },
]

// =====================================================================================================
// Board 1 · Survey — today, side by side
// =====================================================================================================
function todayFan() {
  return `<div class="pop" style="width:330px;"><div class="body" style="gap:10px;">
    <span style="font-size:11px; color:var(--mfg);">Spread first→last across 4 targets</span>
    <div class="row"><span style="font-size:12px; font-weight:500;">Colour</span><label class="row" style="gap:5px; font-size:11px;"><span style="width:14px; height:14px; border:1px solid var(--bd); border-radius:3px;"></span>Reverse</label></div>
    <div class="row" style="align-items:flex-start; gap:16px;"><div class="col" style="gap:4px;"><span style="font-size:11px; color:var(--mfg);">From</span>${pickerSquare({ w: 150, h: 120 })}</div><div class="col" style="gap:4px;"><span style="font-size:11px; color:var(--mfg);">To</span>${pickerSquare({ w: 150, h: 120, knob: [0.2, 0.3], hueAt: 0.62 })}</div></div>
    <div class="row" style="justify-content:flex-end;">${btn('Apply', 'pri')}</div>
  </div></div>`
}
function todayFanValue() {
  return `<div class="pop" style="width:300px;"><div class="body" style="gap:10px;">
    <span style="font-size:11px; color:var(--mfg);">Spread first→last across 4 targets</span>
    <div class="row"><span style="font-size:12px; font-weight:500;">Dimmer</span><label class="row" style="gap:5px; font-size:11px;"><span style="width:14px; height:14px; border:1px solid var(--bd); border-radius:3px;"></span>Reverse</label></div>
    <div class="col" style="gap:4px;"><div class="row" style="justify-content:space-between; font-size:11px; color:var(--mfg);">From ${field('<span class="mono num">0</span>', 'width:80px; height:28px;')}</div>${sliderRow(0, false)}</div>
    <div class="col" style="gap:4px;"><div class="row" style="justify-content:space-between; font-size:11px; color:var(--mfg);">To ${field('<span class="mono num">255</span>', 'width:80px; height:28px;')}</div>${sliderRow(100, false)}</div>
    <div class="row" style="justify-content:flex-end;">${btn('Apply', 'pri')}</div>
  </div></div>`
}
function todayColourPopover() {
  return `<div class="pop" style="width:330px;"><div class="body" style="gap:10px;">
    <span style="font-size:11px; color:var(--mfg);">Applying to 4 targets</span>
    <div class="row" style="align-items:flex-start; gap:12px;"><div class="col" style="gap:0; width:200px;">${pickerSquare({ w: 200, h: 200 })}</div>${rgbFields({})}</div>
    <span style="font-size:10.5px; color:oklch(0.705 0.015 286.067 / 0.6);">Pure white in the picker drives the white LED; the boxes set one channel each.</span>
    ${emitterRows({ rows: [['W', 80, 31], ['A', 160, 63]] })}
  </div></div>`
}
function todayCells() {
  const level = `<div class="pop" style="width:256px;"><div class="body" style="gap:10px;"><span style="font-size:11px; color:var(--mfg);">Applying to 4 targets</span><div class="row" style="gap:12px;">${sliderRow(80)}${field('<span class="mono num">204</span>', 'width:80px; height:32px;')}</div></div></div>`
  const position = `<div class="pop" style="width:256px;"><div class="body" style="gap:10px;"><span style="font-size:11px; color:var(--mfg);">Applying to 4 targets</span><div class="col" style="gap:4px;"><div class="row" style="justify-content:space-between; font-size:11px; color:var(--mfg);">Pan ${field('<span class="mono num">128</span>', 'width:80px; height:28px;')}</div>${sliderRow(50, false)}</div><div class="col" style="gap:4px;"><div class="row" style="justify-content:space-between; font-size:11px; color:var(--mfg);">Tilt ${field('<span class="mono num">64</span>', 'width:80px; height:28px;')}</div>${sliderRow(25, false)}</div></div></div>`
  const setting = `<div class="pop" style="width:224px;"><div class="body" style="gap:4px; padding:4px;"><span style="font-size:11px; color:var(--mfg); padding:6px 8px;">Applying to 4 targets</span>${field('<span style="color:var(--mfg);">Type to filter</span>', 'height:32px; margin:0 0 4px;')}<div class="list"><span class="on"><span class="sw" style="background:transparent;"></span>Open ${ico('check', 12, 'margin-left:auto; color:var(--pri);')}</span><span><span class="sw" style="background:transparent;"></span>Gobo 1 · Dots</span><span><span class="sw" style="background:transparent;"></span>Gobo 2 · Breakup</span><span><span class="sw" style="background:transparent;"></span>Gobo 3 · Stars</span></div></div></div>`
  return { level, position, setting }
}
function surveyBoard() {
  const w = 1440, h = H.Survey
  const diff = [
    ['The name', 'Fan (row C, <span class="mono">FanPopover</span>, <span class="mono">fanMath.ts</span>)', 'Spread (the tab, <span class="mono">SpreadSheet</span>, <span class="mono">spreadIntent.ts</span>, the route)', 'Spread everywhere. The desk already calls the route spread; a Look press "spreads"; nothing on either side calls anything else fan but this one verb.'],
    ['Who interpolates', 'The browser: <span class="mono">fanValues</span> / <span class="mono">fanColours</span> lerp bytes over the rows it can see', 'The desk: <span class="mono">POST /programmer/spread</span> in the intent\'s own space, one literal per head', 'The desk, for both — the rule <span class="mono">templateIntent.ts</span> already keeps. The programmer\'s rows are heads the desk knows better than the browser does.'],
    ['The ends', 'Bytes 0–255, clamped to each resolution', 'Intents: a percent, a colour + policy or a template, degrees', 'Intents. A spread from 0 to full is <i>0% → 100%</i> on every head whatever its range, which the byte form never was.'],
    ['Shape', 'From · To · Reverse', 'From · To · Swap, Curve × 4, Order × 4, Parts, Over', 'The busk tab\'s. Reverse is <i>Order: Reverse</i>; nothing is lost.'],
    ['Position', 'Excluded ("a naive pan lerp is rarely what anyone wants")', 'Offered, in degrees about the desk\'s centre', 'Offered — the desk lerps degrees, the objection was to a byte lerp.'],
    ['Live', 'Apply only', 'Live (<span class="mono">useLivePush</span>) or Apply / Send again', 'Both, on both.'],
    ['Multi-head', 'One point per <b>cell</b> (planBatchWrites expands elements)', 'One point per <b>head</b>; Over: Cells is the switch', 'Heads by default, Cells on the switch — one default, said once.'],
    ['Where it lands', 'Local, or a focused Look layer\'s draft', 'Local only', 'Both: the desk resolves and writes, or resolves and <i>answers</i> for the draft — one flag.'],
    ['Where it opens', 'A popover at the Spread button; a sheet on a phone', 'A docked tab, or the phone overlay', 'The same panel in every form; each host chooses the form it has.'],
  ]
  const colourDiff = [
    ['Body', 'Picker 200px fixed · R/G/B · emitter rows', 'Picker fluid · R/G/B · emitter rows', 'Fluid; a popover host sets its own width and the picker takes it.'],
    ['Count line', '<i>Applying to 4 targets</i>, top', '<i>Emitters on 6 of 14 heads · the rest take RGB only</i>, under the picker', 'The busk read-out line: emitter counts, the swatch, the hex, <i>mixed</i>. It answers what the top line did and more.'],
    ['Guidance', '<i>Pure white in the picker drives the white LED…</i> as a paragraph', 'On the picker\'s title', 'On the title — as the compact layout already does.'],
    ['Pick', 'None: the editor opens at the first cell\'s value', 'Reads the selection\'s live colour without writing', 'On both. In the programmer a Pick after a drag is how a batch is re-read.'],
    ['Recent · Save · Spread…', 'None in the editor (row C carries the strip and New)', 'Recent chips; Save as template…; Spread… hands the colour to the Spread tab', 'Save and Spread… in the footer on both; Recent only where no strip is on screen.'],
    ['Labels', '11px sentence-case muted', '9px uppercase <span class="mono">BuskLabel</span>', 'One <span class="mono">EditorLabel</span>.'],
    ['Keyboard', 'Enter applies, comma steps R→G→B→W→A', 'None', 'The kit\'s keyboard reaches every host.'],
  ]
  const cells = todayCells()
  const html = `${HEAD}
<div class="app" style="width:${w}px; height:${h}px; padding:24px 28px; display:flex; flex-direction:column; gap:16px; overflow:hidden;">
  ${title('Today: two answers to each of three questions', 'The busk view shipped a <b>Spread</b> tab, a <b>Colour</b> tab and a pair of numeric endpoint editors between 2026-09-17 and 2026-09-22, and each is the better design. The programmer\'s <b>Fan</b> popover, colour popover and four cell editors predate them. This board is the survey: what each side draws today, and the differences that matter. The three boards after it draw the one answer; <b>Model</b> says where the code goes and what is open.')}
  ${sec('1 · Fan and Spread')}
  <div class="row" style="align-items:flex-start; gap:20px;">
    <div class="col">${frameLabel('Programmer · Fan on a Colour marquee', 'sheet/FanPopover.tsx · two pickers, Reverse, Apply')}${todayFan()}</div>
    <div class="col">${frameLabel('Programmer · Fan on a Dimmer marquee', 'bytes 0–255, client lerp')}${todayFanValue()}</div>
    <div class="col">${frameLabel('Busk · the Spread tab', 'busking/SpreadSheet.tsx · desk-resolved, 320px docked')}${spreadPanel({ host: 'docked', width: 320, properties: null, live: false, save: 'Save as Look…', compact: true })}</div>
    <div class="col" style="flex:1;">${notes(['<b>Same question, two panels.</b> Both take a selection and two ends and put a value on every head between them. The left one asks the browser to interpolate bytes over the rows it can see and lands them through the cell writers; the right one asks the desk to interpolate an <i>intent</i> and lands what the desk answers. The right one is the one the template system, the Look press and every other write on this desk already agree with.', '<b>What the programmer\'s has that the busk tab does not</b>: the column chooser when the marquee spans two fannable columns (a select), Reverse, and the focused-Look-layer arm — a spread there lands in the layer\'s row draft. All three survive in the proposal; the first two as the family segment and <i>Order: Reverse</i>, the third as one flag on the route.'])}</div>
  </div>
  <table><tr><th style="width:90px;"></th><th>Programmer · Fan</th><th>Busk · Spread</th><th style="width:34%;">Proposed</th></tr>${diff.map(([k, a, b, c]) => `<tr><td><b>${k}</b></td><td>${a}</td><td>${b}</td><td class="y">${c}</td></tr>`).join('')}</table>
  ${sec('2 · The colour editor')}
  <div class="row" style="align-items:flex-start; gap:20px;">
    <div class="col">${frameLabel('Programmer · the colour cell\'s popover', 'fixtures/ColourPickerPopover.tsx over ColourPickerBody')}${todayColourPopover()}</div>
    <div class="col">${frameLabel('Busk · the Colour tab', 'busking/ColourSheet.tsx over the same body')}${colourEditor({ host: 'docked', width: 320, recent: true, pickerH: 176 })}</div>
    <div class="col" style="flex:1;">${note('<b>The body is already shared</b> — <span class="mono">ColourPickerBody</span> was extracted for the busk tab in session 5. What is not shared is everything around it: the read-out, Pick, Recent, the footer, and the count line. The tab grew them; the popover did not get them back.', 'key')}${note('<b>The popover is the older layout of the same body</b>: a 200px square pinned in <span class="mono">index.css</span>, a guidance paragraph under it, emitter rows with no counts, and a count line at the top saying what row C one line up already says. The tab\'s version is fluid, counts its emitters per head, reads the selection back with Pick, and keeps its verbs in a footer.')}</div>
  </div>
  <table><tr><th style="width:110px;"></th><th>Programmer</th><th>Busk</th><th style="width:34%;">Proposed</th></tr>${colourDiff.map(([k, a, b, c]) => `<tr><td><b>${k}</b></td><td>${a}</td><td>${b}</td><td class="y">${c}</td></tr>`).join('')}</table>
  ${sec('3 · The value editors, and what the busk view has instead')}
  <div class="row" style="align-items:flex-start; gap:20px;">
    <div class="col">${frameLabel('Level', 'SliderCell · w-64')}${cells.level}</div>
    <div class="col">${frameLabel('Position', 'PositionCell · two ValueFieldRows')}${cells.position}</div>
    <div class="col">${frameLabel('Setting', 'SettingCell · type-ahead over 3+')}${cells.setting}</div>
    <div class="col">${frameLabel('Busk · a Level endpoint', 'the Spread tab\'s NumericEndpoints, in %')}<div class="pop" style="width:288px;"><div class="body">${endpoints({ kind: 'percent', from: 0, to: 100 })}</div></div>${frameLabel('Busk · a Position endpoint', 'degrees about the centre')}<div class="pop" style="width:288px;"><div class="body">${endpoints({ kind: 'position', from: [240, 135], to: [300, 135] })}</div></div></div>
    <div class="col" style="flex:1;">${notes([
      '<b>Five things differ, none of them a design.</b> The label style (11px sentence vs 9px uppercase); the count line (<i>Applying to 4 targets</i> at the top vs nothing, the band carrying it); the field height (32 in the level editor, 28 in every other); the unit (bytes in the level editor where the cell reads <i>80%</i>; bytes in the position editor where the busk view types degrees); and the popover width (256 for three editors, <span class="mono">w-auto</span> for the colour one, 224 for settings).',
      '<b>The busk view has no value editor of its own</b> beyond the two endpoint fields, and it should not grow one — but its endpoint fields are the anatomy the cell editors should take: a label over a field with the unit inside it, at 28px.',
      '<b>What already is one thing</b>, and stays: <span class="mono">CellEditorSurface</span>\'s three forms, <span class="mono">useCellEditorKeyboard</span> (Enter applies, comma steps, the first field focused), <span class="mono">useNumberFieldDraft</span>, <span class="mono">ValueFieldRow</span>, <span class="mono">UnsetCellMark</span>. The kit is real; it stops one component short of the busk view.',
    ])}</div>
  </div>
  ${sec('The rules the next three boards apply')}
  <div style="display:grid; grid-template-columns:repeat(3, minmax(0, 1fr)); gap:12px 20px;">
    ${[
      ['One name: Spread', 'The verb on row C, the panel, the plan kinds and the module. "Fan" survives only in the desk survey as what other desks call it.'],
      ['One panel per question', 'A spread is one panel whatever hosts it: the programmer\'s popover, the busk tab, the phone sheet. The colour editor is one body with one read-out and one footer. Hosts choose a form and fill slots; they do not redraw.'],
      ['The desk resolves', 'Every spread over a property in the template vocabulary goes through <span class="mono">POST /programmer/spread</span>. The client lerps only what the desk has no grammar for: addresses and fade times, and raw bytes on a column outside the vocabulary.'],
      ['Drawn once on a screen', 'Recent chips are on row C\'s strip in the programmer and in the sheet on the busk view; the same screen never shows them twice. The count is on row C or the band, so no editor repeats it as a top line — the read-out under a picker says what the count line cannot (which heads take which emitter).'],
      ['The anatomy is the busk tab\'s', 'A 9px uppercase label over a control; 28px fields with the unit inside; segmented rows for a choice of four or fewer; a picker padded to its knob; a read-out line; a static footer with the save first and the verbs last.'],
      ['Live where a value is judged by eye', 'Cell editors already write as they go. Spread gains Live on both sides, off by default; Apply always sends; Send again while Live is on.'],
    ].map(([h, p]) => `<div class="col" style="gap:3px;"><span class="h2">${h}</span>${note(p)}</div>`).join('')}
  </div>
</div>
${TAIL(w, h)}`
  writeFileSync('Survey.dc.html', html)
  return { w, h, title: 'Today · Fan vs Spread, the two colour editors, the cell editors' }
}

// =====================================================================================================
// Board 2 · Spread — the programmer's, and the same panel everywhere
// =====================================================================================================
function spreadBoard() {
  const w = 1440, h = H.Spread
  const gridW = 220 + 150 * 5
  const slice = `<div style="width:${gridW}px; border:1px solid var(--bd); border-radius:8px; overflow:hidden; position:relative; flex:0 0 auto;">${rowC({ verb: 'Spread', verbOn: true })}${gridSlice({ rows: RIG_ROWS, marqueeCol: 'Colour' })}
    <div style="position:absolute; right:150px; top:44px;">${spreadPanel({ host: 'popover', width: 336, family: 'Colour', properties: ['Colour', 'White', 'Amber', 'UV'], property: 'Colour', kind: 'colour', from: '#F5B342', to: '#2456FF', live: false, cells: null, overOff: true, headLine: '<span>4 heads · Local</span><span style="margin-left:auto;">Colour marquee</span>' })}</div>
  </div>`
  const lookPanel = spreadPanel({ host: 'popover', width: 320, family: 'Intensity', properties: ['Level', 'Strobe'], property: 'Level', kind: 'percent', from: 0, to: 100, live: false, applyLabel: 'Apply', headLine: `<span>${ico('layers', 12)} into <b style="color:var(--fg);">Warm Wash</b> · 8 heads</span>`, footerNote: 'resolved on the desk, written to the layer\'s draft' })
  const positionPanel = spreadPanel({ host: 'popover', width: 320, family: 'Position', properties: null, kind: 'position', from: [240, 135], to: [300, 135], live: true, curve: 'Mirror', order: 'Centre', cells: null, overOff: true, headLine: '<span>6 heads · Local</span>' })
  const addressPanel = `<div class="pop" style="width:300px;"><div class="body"><div class="readout"><span>8 fixtures · visible order</span></div>${seg(['Address'], 'Address')}<div class="row" style="gap:6px; align-items:flex-end;"><div class="col" style="gap:4px; flex:1;">${lbl('From <span class="mono" style="text-transform:none; letter-spacing:0;">u1</span>')}${field('<span class="mono num">1</span>')}</div><div class="col" style="gap:4px; flex:1;">${lbl('Step')}${field('<span style="color:var(--mfg);">footprint</span>')}</div></div><span class="cap9">1-001, 1-021, 1-041 … 1-141 · a fixed gap, or each head\'s own footprint</span></div><div class="foot"><span style="flex:1;"></span>${btn('Apply', 'pri')}</div></div>`
  const durationPanel = `<div class="pop" style="width:300px;"><div class="body"><div class="readout"><span>4 cues · stack order</span></div>${seg(['Fade'], 'Fade')}${endpoints({ kind: 'duration', from: '1', to: '4' })}<div class="col" style="gap:4px;">${lbl('Curve')}${curveRow('Line', true)}</div><span class="cap9">Cue 1 1.0s · 2 2.0s · 3 3.0s · 4 4.0s</span></div><div class="foot"><span style="flex:1;"></span>${btn('Apply', 'pri')}</div></div>`
  const html = `${HEAD}
<div class="app" style="width:${w}px; height:${h}px; padding:24px 28px; display:flex; flex-direction:column; gap:16px; overflow:hidden;">
  ${title('Spread: the busk tab\'s panel, opened from the programmer\'s row C', 'Fan is renamed and replaced. The verb on row C reads <b>Spread</b>, and it opens the same panel the busk view docks as a tab — <span class="mono">components/editor/SpreadPanel.tsx</span>, one component — in the cell editor\'s three forms. The marquee answers the panel\'s first two questions (which heads, which family) so the programmer\'s panel opens one row further down than the busk tab does, with the family segment already answered and the <b>Property</b> row drawn from the column: a Colour marquee offers Colour · White · Amber · UV, a Dimmer marquee Level · Strobe, a Zoom marquee Zoom alone.')}
  <div class="row" style="align-items:flex-start; gap:20px;">
    ${slice}
    <div class="col" style="flex:1; gap:10px;">
      ${note('<b>The desk resolves, on both sides.</b> The programmer\'s Spread sends the marquee\'s heads as <span class="mono">targets</span> — a group row to its <i>visible</i> members, an element row as <span class="mono">{fixture, element.key}</span> (the cells contract the desk already takes), a fixture row as itself — plus the family pill\'s mask, and <span class="mono">POST /programmer/spread</span> writes one literal per head into Local. <span class="mono">fanValues</span> and <span class="mono">fanColours</span> are deleted with it; the position exclusion goes too, because the objection was to a byte lerp and the desk lerps degrees.', 'key')}
      ${note('<b>The family is the marquee\'s, so the segment is answered and drawn checked</b> — never hidden: the panel is one component, and a row that appears only in some hosts is how two hosts drift. A marquee spanning two families (Dimmer + Colour) draws the segment live with both offered, as the chooser Fan drew for the same case. The <b>Property</b> row appears wherever the family has more than one, exactly as the busk tab draws it.')}
      ${note('<b>The count moved off the top line and into the read-out</b>: <i>4 heads · Local</i> on the left, the marquee\'s family on the right — the busk tab has the band for this and draws neither; the popover has no band, so the read-out line the colour editor already carries takes it. No editor says <i>Applying to N targets</i> any more.')}
      ${note('<b>Live on the programmer too</b>, off by default, through the same <span class="mono">useLivePush</span>; Apply always sends and reads <i>Send again</i> while Live is on. A cell editor writes as it goes already, so the two surfaces agree: a value judged by eye lands as the hand moves.')}
      ${note('<b>No <i>Save as Look…</i> in the programmer\'s footer</b> (drawn without it; open call 1). Record is one row up and is how every programmer state is kept; the busk tab has no Record and so carries its own save. The footer is one component with a <span class="mono">save</span> slot the busk host fills.')}
      ${note('<b>Over: Cells is offered when a selected fixture has elements</b>, with the count, as the busk tab draws it; disabled with the reason otherwise. This changes the programmer\'s default: Fan expanded a bar into its cells always, Spread treats a fixture row as one head unless the switch says otherwise — the busk default, said once (open call 5). An element row selected on its own is a cell by construction.')}
    </div>
  </div>
  ${sec('The same panel in its other hosts')}
  <div class="row" style="align-items:flex-start; gap:20px;">
    <div class="col" style="width:320px;">${frameLabel('Busk · the docked tab', 'unchanged in shape; the family segment is the tab\'s first row')}${spreadPanel({ host: 'docked', width: 320, properties: ['Colour', 'White', 'Amber', 'UV'], property: 'Colour', live: true, save: 'Save as Look…', cells: 16, over: 'Cells' })}</div>
    <div class="col" style="width:320px;">${frameLabel('Programmer · a focused Look layer', 'the one scope Fan reached that the desk route did not')}${lookPanel}${note('<b>One flag on the route: <span class="mono">write: false</span></b> (or a <span class="mono">into</span> arm). The desk resolves exactly as it does for Local and answers <span class="mono">written[]</span> with each head\'s <b>literal</b> — today it answers the interpolated <i>intent</i>, which a Look row cannot hold — and the client lands them in the layer\'s draft through <span class="mono">setLookValue</span>, which coalesces and PUTs as every layer-scope edit does. The client still never lerps. Output and a focused template layer refuse as they do today.', 'key')}</div>
    <div class="col" style="width:320px;">${frameLabel('Programmer · a Position marquee, Live', 'degrees, the busk convention; the desk clamps to each head')}${positionPanel}${note('The position editor types <b>degrees</b> here and the cell still reads bytes (<i>128,64</i>) — open call 7 is whether the position <i>cell editor</i> takes degrees too, which needs each head\'s <span class="mono">degMax</span> annotation on this side. Every fixture annotates pan and tilt today, so the read is there.')}</div>
    <div class="col" style="flex:1;">${frameLabel('The kit\'s own kinds keep the client walk', 'patch list · cue sheet — the same anatomy, the desk has no grammar for these')}<div class="col" style="gap:12px;">${addressPanel}${durationPanel}</div>${note('<b>Four plan kinds</b>: <span class="mono">intent</span> (desk-resolved: the template vocabulary — level, strobe, colour, white, amber, uv, position, zoom, focus, iris, frost), <span class="mono">raw</span> (bytes on a column outside it — Speed today; open call 4 is whether to keep it), <span class="mono">address</span> (From · Step, visible order) and <span class="mono">duration</span> (From · To, a curve). The first two draw Curve · Order · Parts · Over; address draws From · Step and its landing line; duration draws From · To and the curve row, which is where a second spread than Linear was always going to go.')}</div>
  </div>
  ${sec('What the request carries from the programmer, and what comes back')}
  <div class="row" style="align-items:flex-start; gap:20px;">
    <div class="note" style="flex:1;"><p><b>Request</b> — unchanged but for the flag</p><div class="kv" style="margin-top:6px;">
      <span class="kk">targets</span><span>the marquee\'s heads: group rows expanded to their visible members, element rows as cells, fixture rows as themselves — <span class="mono">templateTargetsFor</span>\'s rule, so a press and a spread cannot name different heads</span>
      <span class="kk">families</span><span>the pair the press sends (<span class="mono">usePressFamilies</span>) — the desk\'s while following, the tab\'s when unlinked</span>
      <span class="kk">property</span><span>the column\'s property, or the one the Property row chose</span>
      <span class="kk">from / to / curve / order / parts / over / seed</span><span>as the busk tab sends them</span>
      <span class="kk">write</span><span><b>new</b>: <span class="mono">true</span> (default, Local) or <span class="mono">false</span> — resolve and answer only, for a focused Look layer</span>
    </div></div>
    <div class="note" style="flex:1;"><p><b>Response</b></p><div class="kv" style="margin-top:6px;">
      <span class="kk">written[]</span><span><span class="mono">{target, propertyName, value}</span> — <b>the literal</b> the head got, in the Look row grammar, not the intent (a backend change: today <span class="mono">intent.serialize()</span>)</span>
      <span class="kk">skipped[]</span><span>heads without the property, with the reason</span>
      <span class="kk">skippedFamilies</span><span>toasted in <span class="mono">skippedRowsMessage</span>\'s vocabulary, as the busk tab does</span>
    </div><p style="margin-top:6px;">The programmer reads the response for <span class="mono">skippedFamilies</span> and, in layer scope, for <span class="mono">written[]</span>; it never draws a preview from it — the grid is the preview, as the rig is on the busk view.</p></div>
    <div class="note" style="flex:1;"><p><b>The keyboard reaches both hosts.</b> <span class="mono">useCellEditorKeyboard</span> wraps the panel: the first field is focused on open in the popover (not in either sheet), comma steps From → To, Enter applies — on the busk tab too, which had no keyboard convention. Escape is the surface\'s and closes the popover; the docked tab has no Escape, as the docked rail has none.</p><p>Row C\'s <kbd>⏎</kbd> <i>edit</i> · <kbd>⌫</kbd> <i>clear</i> hints stay; a third for Spread is not added — Spread is a verb with a panel, not a key.</p></div>
  </div>
</div>
${TAIL(w, h)}`
  writeFileSync('Spread.dc.html', html)
  return { w, h, title: 'Spread · one panel, every host' }
}

// =====================================================================================================
// Board 3 · Colour — one editor
// =====================================================================================================
function colourBoard() {
  const w = 1440, h = H.Colour
  const gridW = 220 + 150 * 5
  const slice = `<div style="width:${gridW}px; border:1px solid var(--bd); border-radius:8px; overflow:hidden; position:relative; flex:0 0 auto;">${rowC({ verb: 'Spread' })}${gridSlice({ rows: RIG_ROWS, marqueeCol: 'Colour', openRow: 0 })}
    <div style="position:absolute; left:${220 + 150 * 2 + 8}px; top:${40 + 30 + 36 + 2}px;">${colourEditor({ host: 'popover', width: 352, recent: false, footer: true, counts: true, seedR: true, hostLine: '<span>4 heads · Local</span><span style="margin-left:auto;">Colour</span>' })}</div>
  </div>`
  const phone = `<div style="width:375px; height:520px; border:1px solid var(--bd); border-radius:24px; overflow:hidden; position:relative; background:var(--bg); flex:0 0 auto;">
    <div class="rowc" style="padding:0 12px;">${ico('marquee', 14)}<span class="cnt">4 cells</span><span style="flex:1;"></span><span class="vbtn" style="height:28px; padding:0 8px;">${ico('pencil', 14)}</span><span class="vbtn" style="height:28px; padding:0 8px;">${ico('backspace', 14)}</span><span class="vbtn ghost" style="height:28px; padding:0 8px;">${ico('x', 14)}</span></div>
    ${gridSlice({ nameW: 150, colW: 112, rows: RIG_ROWS.slice(0, 3), marqueeCol: 'Colour' })}
    <div class="scrim"></div>
    <div class="pop" style="position:absolute; left:0; right:0; bottom:0; width:auto; border-radius:12px 12px 0 0; box-shadow:0 -12px 40px rgba(0,0,0,0.6);"><div class="row" style="height:44px; padding:0 12px; border-bottom:1px solid var(--bd);"><span style="position:absolute; top:6px; left:50%; width:36px; height:4px; margin-left:-18px; border-radius:999px; background:var(--muted);"></span><span style="font-size:14px; font-weight:600;">Colour</span><span style="flex:1;"></span>${ico('x', 16, 'color:var(--mfg);')}</div>${colourEditor({ host: 'sheet', width: 375, recent: true, footer: true, counts: true, pickerH: 160 }).replace('class="pop"', 'class="x"').replace(/style="width:375px;"/, 'style="border:0; box-shadow:none;"')}</div>
  </div>`
  const short = `<div style="width:600px; height:340px; border:1px solid var(--bd); border-radius:8px; overflow:hidden; position:relative; background:var(--bg); flex:0 0 auto;">
    ${rowC({ verb: 'Spread', chips: false, words: false })}${gridSlice({ nameW: 160, colW: 120, rows: RIG_ROWS.slice(0, 4), marqueeCol: 'Colour' })}
    <div class="scrim"></div>
    <div class="pop" style="position:absolute; top:0; right:0; bottom:0; width:528px; border-radius:0; border-left:1px solid var(--bd); box-shadow:-12px 0 40px rgba(0,0,0,0.6);"><div class="row" style="height:40px; padding:0 12px; border-bottom:1px solid var(--bd); flex:0 0 auto;"><span style="font-size:14px; font-weight:600;">Colour</span><span style="flex:1;"></span>${ico('x', 16, 'color:var(--mfg);')}</div>${colourEditor({ host: 'sheet', width: 528, compact: true, recent: false, footer: true, counts: true }).replace('class="pop"', 'class="x"').replace(/style="width:528px;"/, 'style="border:0; box-shadow:none; flex:1; min-height:0;"')}</div>
  </div>`
  const html = `${HEAD}
<div class="app" style="width:${w}px; height:${h}px; padding:24px 28px; display:flex; flex-direction:column; gap:16px; overflow:hidden;">
  ${title('The colour editor: one body, one read-out, one footer', '<span class="mono">ColourPickerBody</span> becomes <span class="mono">components/editor/ColourEditor.tsx</span> and takes with it everything the busk tab grew around it: the read-out line (emitter counts · <i>mixed</i> · swatch · hex), <b>Pick</b>, and the footer with <b>Save as template…</b> and <b>Spread…</b>. The programmer\'s colour cell hosts it in the popover and the two sheets; the busk view docks it; the Spread panel\'s endpoint hosts the picker half alone. The two property visualisers keep the picker-only form they have.')}
  <div class="row" style="align-items:flex-start; gap:20px;">
    ${slice}
    <div class="col" style="flex:1; gap:10px;">
      ${note('<b>The popover is 352 wide and the picker is fluid</b>: the square takes what the R/G/B column leaves, as it does in the busk sheet at 320–480. Today it is a fixed 200px square (<span class="mono">index.css</span>, <span class="mono">!important</span>) in a <span class="mono">w-auto</span> popover; the six mount sites that rule names become one.', 'key')}
      ${note('<b>The read-out replaces the count line.</b> <i>Emitters on 6 of 14 heads · the rest take RGB only</i> is the selection\'s union read off the colour descriptors, per head, with the count beside each emitter row — the same probe the busk tab runs (<span class="mono">emitterHeadCounts</span>). The swatch, the hex and <i>mixed</i> sit at its right. <i>4 heads · Local</i> moves to the label line above the picker, where the Spread panel puts it.')}
      ${note('<b>Pick reads the selection\'s live colour</b> off <span class="mono">lib/liveAppearance.ts</span> into the picker without writing — the first head in rig order, <i>mixed</i> where they disagree — exactly as the busk tab does. In the programmer the editor already opens at the first cell\'s value (the placeholder rule); Pick is how a batch is re-read after a drag moved only some of it. The grid\'s rows report into the store as the rig tiles do, so no hidden leaves are needed here.')}
      ${note('<b>Save as template…</b> opens <span class="mono">NewTemplateFromSelectionSheet</span> over the marquee\'s heads, family Colour — the strip\'s <i>New</i> with the family answered. <b>Spread…</b> opens the Spread panel with From set to this colour\'s RGB, the busk hand-over (<span class="mono">SpreadSeed</span>): on the programmer it opens at the Spread button, since that is where the panel lives. Both are the footer the busk tab has; neither is new.')}
      ${note('<b>Recent is not drawn in the desk popover</b> (open call 2): row C\'s strip is on screen one row up, and a chip set drawn twice is the double the rule refuses. It <i>is</i> drawn in the bottom sheet, where the strip is folded away below 600px — so the phone gets Recent in the editor, as the busk phone does.')}
      ${note('<b>The white-LED guidance is the picker\'s title everywhere</b>, as the compact layout already made it; the paragraph is gone. The <b>keyboard</b> is unchanged: R focused on open in the popover, comma R → G → B → W → A → UV, Enter closes, a typed character seeds R.')}
    </div>
  </div>
  ${sec('The other hosts')}
  <div class="row" style="align-items:flex-start; gap:20px;">
    <div class="col">${frameLabel('Busk · the docked Colour tab', 'the same body; the tab adds Recent, since no strip is on this screen')}${colourEditor({ host: 'docked', width: 320, recent: true, footer: true, pickerH: 176 })}</div>
    <div class="col">${frameLabel('Phone · the bottom sheet', 'Recent drawn: row C\'s strip is folded below 600px')}${phone}</div>
    <div class="col" style="width:600px;">${frameLabel('Short viewport · the side sheet, compact', 'emitters beside the picker; 528 wide, as today')}${short}
      ${frameLabel('Spread · the endpoint picker', 'picker and R/G/B only — no emitters, no footer: a colour intent has no emitter component')}<div class="pop" style="width:320px;"><div class="body">${endpoints({ kind: 'colour' })}<div class="row" style="align-items:flex-start; gap:12px;"><div class="col" style="flex:1; min-width:0; gap:0;">${pickerSquare({ h: 150 })}</div>${rgbFields({})}</div></div></div></div>
  </div>
  ${sec('The pieces, and which host draws which')}
  <table style="max-width:1100px;"><tr><th>Piece</th><th>Programmer popover</th><th>Programmer sheets</th><th>Busk tab</th><th>Spread endpoint</th><th>Property visualisers</th></tr>
    ${[
      ['Picker (fluid) + R/G/B', '●', '●', '●', '●', 'picker only'],
      ['Emitter rows, with head counts', '●', '●', '●', '—', 'read-out sliders'],
      ['Read-out: counts · mixed · swatch · hex', '●', '●', '●', '—', '—'],
      ['Label line: heads · scope', '●', 'the sheet title', '— (the band)', '— (the panel\'s)', '—'],
      ['Recent chips', '— (row C)', '● bottom sheet only', '●', '—', '—'],
      ['Footer: Save as template… · Pick · Spread…', '●', '●', '●', '—', '—'],
      ['Keyboard: R focused, comma, Enter', '●', 'comma, Enter', '●', '●', '—'],
    ].map(([p, ...cs]) => `<tr><td><b>${p}</b></td>${cs.map((c) => `<td style="text-align:center; ${c === '●' ? 'color:var(--fg);' : ''}">${c}</td>`).join('')}</tr>`).join('')}
  </table>
</div>
${TAIL(w, h)}`
  writeFileSync('Colour.dc.html', html)
  return { w, h, title: 'Colour · one editor in five hosts' }
}

// =====================================================================================================
// Board 4 · Editors — the value editors on one anatomy
// =====================================================================================================
function editorsBoard() {
  const w = 1440, h = H.Editors
  const labelLine = (l, r) => `<div class="readout" style="margin-bottom:-2px;"><span>${l}</span><span style="margin-left:auto;">${r}</span></div>`
  const level = `<div class="pop" style="width:288px;"><div class="body">${labelLine('4 heads · Local', 'Dimmer')}<div class="col" style="gap:4px;">${lbl('Level')}<div class="row" style="gap:10px;">${sliderRow(80)}${field('<span class="mono num">80</span><span class="u">%</span>', 'width:72px;', 'focus sel')}</div></div><div class="readout"><span>204 of 255 · 0–255 on every head</span></div></div></div>`
  const levelDmx = `<div class="pop" style="width:288px;"><div class="body">${labelLine('8 channels', 'u1 · 001–008')}<div class="col" style="gap:4px;">${lbl('Value')}<div class="row" style="gap:10px;">${sliderRow(80)}${field('<span class="mono num">204</span>', 'width:72px;', 'focus sel')}</div></div><div class="readout"><span>raw, 0–255 — the DMX sheet reads bytes</span></div></div></div>`
  const position = `<div class="pop" style="width:288px;"><div class="body">${labelLine('6 heads · Local', 'Position')}<div class="row" style="align-items:flex-start; gap:12px;"><div class="xypad" style="width:120px; height:120px; flex:0 0 auto;"><i style="left:50%; top:50%;"></i></div><div class="col" style="flex:1; gap:8px;"><div class="col" style="gap:4px;">${lbl('Pan')}${field('<span class="mono num">270</span><span class="u">°</span>', '', 'focus sel')}${sliderRow(50, false)}</div><div class="col" style="gap:4px;">${lbl('Tilt')}${field('<span class="mono num">135</span><span class="u">°</span>')}${sliderRow(50, false)}</div></div></div><div class="readout"><span>128 · 128 on Mover 1 (540° · 270°)</span><span style="margin-left:auto;">2 heads differ</span></div></div></div>`
  const setting = `<div class="pop" style="width:256px;"><div class="body" style="gap:6px;">${labelLine('4 heads · Local', 'Gobo')}${field('<span style="color:var(--mfg);">Type to filter</span>', '', 'focus')}<div class="list"><span class="on"><span class="sw" style="background:transparent;"></span>Open ${ico('check', 12, 'margin-left:auto; color:var(--pri);')}</span><span><span class="sw" style="background:transparent;"></span>Gobo 1 · Dots</span><span><span class="sw" style="background:transparent;"></span>Gobo 2 · Breakup</span><span><span class="sw" style="background:transparent;"></span>Gobo 3 · Stars</span></div><div class="readout"><span>2 heads have no gobo · skipped</span></div></div></div>`
  const text = `<div class="pop" style="width:288px;"><div class="body">${labelLine('4 cues', 'Notes')}<div class="col" style="gap:4px;">${lbl('Notes')}${field('<span>Blackout on the last beat</span>', '', 'focus')}</div></div><div class="foot"><span style="flex:1;"></span>${btn('Apply', 'pri')}</div></div>`
  const address = `<div class="pop" style="width:288px;"><div class="body">${labelLine('4 fixtures · visible order', 'Address')}<div class="col" style="gap:4px;">${lbl('From <span class="mono" style="text-transform:none; letter-spacing:0;">u1</span>')}${field('<span class="mono num">41</span>', '', 'focus sel')}</div><div class="col" style="gap:2px;"><span class="cap9">Par 1 → 1-041</span><span class="cap9">Par 2 → 1-047</span><span class="cap9" style="color:var(--red);">Par 3 → 1-053 · collides with Mover 1</span><span class="cap9">Par 4 → 1-059</span></div></div><div class="foot"><span style="flex:1;"></span>${btn('Apply', 'pri dim')}</div></div>`
  const anatomy = `<div class="pop" style="width:300px; position:relative;"><div class="body" style="gap:10px;">
    <div class="readout" style="outline:1px dashed oklch(0.6 0.14 80); outline-offset:3px;"><span>N heads · scope</span><span style="margin-left:auto;">the column</span></div>
    <div class="col" style="gap:4px; outline:1px dashed var(--pri); outline-offset:4px;">${lbl('Label · 9px uppercase')}${field('<span class="mono num">value</span><span class="u">unit</span>', 'height:28px;')}</div>
    <div class="col" style="gap:4px; outline:1px dashed var(--pri); outline-offset:4px;">${lbl('A choice of ≤ 4')}${seg(['A', 'B', 'C', 'D'], 'A')}</div>
    <div class="readout" style="outline:1px dashed oklch(0.6 0.14 80); outline-offset:3px;"><span>read-out: what the desk holds, what was skipped</span></div>
  </div><div class="foot" style="outline:1px dashed var(--green); outline-offset:-3px;">${btn('Save…', 'out', 'save')}<span style="flex:1;"></span>${btn('verb', 'out')}${btn('Apply', 'pri')}</div></div>`
  const html = `${HEAD}
<div class="app" style="width:${w}px; height:${h}px; padding:24px 28px; display:flex; flex-direction:column; gap:16px; overflow:hidden;">
  ${title('The value editors on one anatomy', 'Nothing here is a new gesture. The four programmer cells and the kit\'s three keep their editors and their keyboard; what changes is that every panel is built from the same five pieces the busk tabs are — a <b>label line</b> (heads · scope on the left, the column on the right), <b>9px uppercase labels</b> over <b>28px fields with the unit inside</b>, a <b>segmented row</b> for any choice of four or fewer, a <b>read-out</b> line, and a <b>static footer</b> where a panel writes on Apply rather than as it is edited. <span class="mono">components/editor/</span> exports each piece once and every host imports it.')}
  <div class="row" style="align-items:flex-start; gap:20px;">
    <div class="col" style="width:300px;">${frameLabel('The anatomy', 'label line · labelled controls · read-out · footer (only where Apply exists)')}${anatomy}
      <div class="kv" style="margin-top:6px;"><span class="kk">EditorLabel</span><span><span class="mono">BuskLabel</span> moved and renamed — 9px, 700, uppercase, 0.08em, muted; a <span class="mono">&lt;div&gt;</span> so a test can walk up from it</span><span class="kk">EditorField</span><span>28px, radius 8, the unit as a trailing muted glyph; the draft rule (<span class="mono">useNumberFieldDraft</span>) built in</span><span class="kk">EditorReadout</span><span>the 10px line: the busk tab\'s emitter line, the level editor\'s byte, the address editor\'s landing lines (<span class="mono">LandingLines</span> becomes its multi-line arm)</span><span class="kk">EditorFooter</span><span>save slot · note · spacer · verbs; the Colour and Spread tabs\' footer, generalised</span><span class="kk">EditorSurface</span><span><span class="mono">CellEditorSurface</span>, moved: the three forms, the double click, the anchor</span><span class="kk">padding</span><span><span class="mono">px-3.5</span> on any row holding a picker or a slider knob — the knob\'s half-width, the busk tabs\' reason</span></div>
    </div>
    <div class="col" style="flex:1;">
      <div class="row" style="align-items:flex-start; gap:16px; flex-wrap:wrap;">
        <div class="col">${frameLabel('Level · the programmer', 'the field in the cell\'s unit, %; the byte on the read-out')}${level}</div>
        <div class="col">${frameLabel('Level · the DMX sheet', 'bytes, because the sheet reads bytes — the same panel, its own unit')}${levelDmx}</div>
        <div class="col">${frameLabel('Position', 'an XY pad beside Pan · Tilt — the cell\'s 16px thumbnail, at a size a finger can use')}${position}</div>
      </div>
      <div class="row" style="align-items:flex-start; gap:16px; flex-wrap:wrap; margin-top:4px;">
        <div class="col">${frameLabel('Setting', 'unchanged but for the label line and the skip read-out')}${setting}</div>
        <div class="col">${frameLabel('Text · the cue sheet', 'commit on Apply or ⏎, so it has a footer')}${text}</div>
        <div class="col">${frameLabel('Address · the patch list', 'the landing lines are the read-out\'s multi-line arm; a collision is red and disables Apply')}${address}</div>
      </div>
    </div>
  </div>
  <div style="display:grid; grid-template-columns:repeat(3, minmax(0, 1fr)); gap:12px 20px;">
    ${[
      ['The unit is the cell\'s', 'The programmer\'s dimmer cell reads <i>80%</i>, so its editor\'s field is a percent and the byte is the read-out; the DMX sheet reads <i>204</i>, so its field is the byte. Today both fields are bytes. One panel, the host names the unit (open call 3). Strobe, zoom, focus and iris are percents for the same reason; a template of them is a percent already.'],
      ['Position gets the pad', 'The cell draws a 16px pan/tilt thumbnail; the editor draws it at 120px beside the two rows, and a drag on it writes both. Degrees in the fields where the head annotates a range (every fixture does), bytes on the read-out. The Spread panel\'s position endpoints use the same two fields, so a spread and a set type the same thing.'],
      ['The label line replaces the count line', '<i>Applying to 4 targets</i> was drawn only for a batch; the label line is drawn always, says the scope as well (<i>Local</i>, or the Look\'s name in layer scope — which no editor says today), and names the column on the right where a bottom sheet\'s title used to. The sheets keep their title row; the label line is the popover\'s.'],
      ['The read-out says what was skipped', 'A commit over a batch where two heads lack the property already skips them silently at write time. The read-out says so: <i>2 heads have no gobo · skipped</i>. It is the same line the busk Colour tab uses for <i>the rest take RGB only</i>.'],
      ['Live is the cell editors\' already', 'Every value editor writes as it is typed or dragged through the sheet\'s throttled commit; the busk tabs write through <span class="mono">useLivePush</span>. Same discipline (a floor, a dedupe, a release that always lands); the plan makes <span class="mono">useSheet</span>\'s commit throttle that hook rather than a second copy.'],
      ['Widths', '288 for level, position, text and address (the busk endpoint editors\' 2 × 128 + a gutter, up from 256); 256 for the setting list; 352 for colour; 336 for Spread on the programmer, 320 docked. The sheets are as today. Not one number, because the content asks — the reason <span class="mono">wide</span> exists on the surface.'],
    ].map(([h, p]) => `<div class="col" style="gap:3px;"><span class="h2">${h}</span>${note(p)}</div>`).join('')}
  </div>
</div>
${TAIL(w, h)}`
  writeFileSync('Editors.dc.html', html)
  return { w, h, title: 'The value editors · one anatomy' }
}

// =====================================================================================================
// Board 5 · Model — where the code goes, the rename, the wire, ideas, open calls, sessions
// =====================================================================================================
function modelBoard() {
  const w = 1440, h = H.Model
  const moves = [
    ['components/editor/EditorSurface.tsx', 'sheet/cells/CellEditorSurface.tsx', 'moved; three forms, the double click, `wide`, the anchor — unchanged'],
    ['components/editor/EditorLabel.tsx', 'busking/BuskLabel.tsx', 'moved and renamed; the busk view imports it back'],
    ['components/editor/EditorField.tsx', 'the field in ValueFieldRow · SpreadSheet\'s NumberField · ChannelNumberInput', 'one 28px field with the unit and the draft rule; three fields become one'],
    ['components/editor/EditorReadout.tsx · EditorFooter.tsx', 'ColourSheet\'s read-out and footer · SpreadSheet\'s footer · sheet/cells/LandingLines.tsx', 'new, from the busk tabs'],
    ['components/editor/ValueFieldRow.tsx', 'sheet/cells/ValueFieldRow.tsx', 'moved; takes EditorField'],
    ['components/editor/ColourEditor.tsx', 'fixtures/ColourPickerBody.tsx + ColourSheet\'s read-out, Pick, Recent, footer', 'one body; `recent` · `footer` · `counts` are props the host fills'],
    ['components/editor/SpreadPanel.tsx', 'busking/SpreadSheet.tsx (the body) + sheet/FanPopover.tsx (the surface, the chooser, the keyboard)', 'one panel; plan kinds intent · raw · address · duration'],
    ['components/editor/spreadPlans.ts', 'sheet/fanMath.ts', '`fanValues` · `fanColours` deleted (the desk resolves); `fanAddresses` → `walkAddresses`, `fanDurations` → `spreadDurations`'],
    ['components/editor/useEditorKeyboard.ts', 'sheet/cells/useCellEditorKeyboard.ts', 'moved; unchanged'],
    ['components/editor/useLivePush.ts', 'hooks/useLivePush.ts', 'moved beside its callers; `useSheet`\'s commit throttle takes it'],
    ['fixtures-list/SpreadPopover.tsx', 'fixtures-list/FanPopover.tsx', 'renamed; builds `intent` plans from the marquee, the scope arm, the target rule'],
    ['patches/PatchSheet.tsx · runner/CueSheet.tsx · channels/DmxSheet.tsx', '(their FanPopover instances)', 'take `SpreadPanel` with `address` · `duration` · `raw` plans; no behaviour change'],
    ['busking/SpreadSheet.tsx · ColourSheet.tsx', '', 'become thin hosts: the selection → targets, the hidden leaves for Pick, the seed hand-over, the docked frame'],
    ['sheet/CellSelectionActions.tsx', '', '`fan` slot → `spread`; the verb reads Spread with the wave glyph (the busk tab\'s), never the fan glyph'],
  ]
  const renames = [
    ['Fan (row C verb, aria-label)', 'Spread'],
    ['FanPopover (sheet, fixtures-list)', 'SpreadPanel · SpreadPopover'],
    ['FanPlan · FanColumn · fanColumnsForTargets', 'SpreadPlan · SpreadColumn · spreadColumnsForTargets'],
    ['fanMath.ts', 'spreadPlans.ts'],
    ['fan (the CellSelectionActions slot) · PHONE_FOLDED_CLASS use', 'spread'],
    ['BuskLabel', 'EditorLabel'],
    ['CellEditorSurface · useCellEditorKeyboard · useCellEditorOpen', 'EditorSurface · useEditorKeyboard · useEditorOpen'],
    ['ColourPickerBody', 'ColourEditor'],
    ['CLAUDE.md §Sheet kit "Fan"; docs/ mentions; the desk survey keeps "fan" as the other desks\' word', 'Spread'],
  ]
  const ideas = [
    ['Colour · Spread as tabs on the programmer rail', 'The rail and the busk sheet are one instrument in two views (CLAUDE.md §Focus and the side sheet); giving the rail a Colour and a Spread tab beside Layers · FX would make the docked form available on the programmer for a long busk over one marquee, with the popover as the quick form. Same components, a new host each.', 'ask', 'medium — a tab strip on the rail header, two hosts, the marquee as the selection'],
    ['The XY pad in the position editor', 'Drawn on Editors. The cell already has the thumbnail; a 120px pad is the touch form of the two sliders.', 'go', 'small'],
    ['The kit keyboard on the busk tabs', 'Comes free from SpreadPanel and ColourEditor: comma steps From → To and R → G → B, Enter applies — the busk tabs had no keyboard convention at all.', 'go', 'free'],
    ['Percent in the programmer\'s level editor', 'The cell reads 80%; the field says 204. Drawn as a percent with the byte on the read-out; the DMX sheet keeps bytes.', 'ask', 'small — open call 3'],
    ['A "spread again" or a recent-spreads row', 'The desk keeps no spread state; Send again resends the form as it stands, and a spread worth keeping is a Look. Declined.', 'no', ''],
    ['Pick in the level and position editors', 'The editor opens at the live value already (the placeholder rule), and re-reads on reopen. Declined; Pick is the colour editor\'s because a colour is the one value a batch can disagree on invisibly.', 'no', ''],
    ['Save as Look… in the programmer\'s Spread footer', 'Record is one row up. Drawn without; open call 1.', 'ask', 'trivial either way'],
    ['A Beam tab on the busk sheet (the setting editor on the busk view)', 'Out of scope: the busk view has no gobo or prism control by design (a template cannot carry a gobo). Not this plan.', 'no', ''],
    ['`useLivePush` behind every cell commit', 'The sheet\'s commit throttle and the busk tabs\' push are two copies of one discipline. Fold the throttle into the hook.', 'go', 'small'],
    ['Delete the six `react-colorful` mount sites\' size rules', 'With one ColourEditor there is one mount site; `index.css`\'s 200px pin and the `.colour-picker-*` classes reduce to the fluid rule.', 'go', 'small, with the kit'],
  ]
  const open = [
    ['1 · Save as Look… on the programmer\'s Spread', 'Drawn without it: Record is one row up. Adding it is one prop on the footer.'],
    ['2 · Recent chips in the desk colour popover', 'Drawn without: row C\'s strip is one row up. The phone sheet draws them, where the strip is folded.'],
    ['3 · Percent in the level editor', 'Drawn as %, byte on the read-out; the DMX sheet keeps bytes. Today both are bytes.'],
    ['4 · The Speed column', 'It is outside the template vocabulary, so the desk cannot spread it. Drawn: a `raw` plan kind keeps the client byte lerp for it alone. The alternative is to drop Spread on Speed.'],
    ['5 · Over: Heads as the programmer\'s default', 'Fan expanded a multi-head fixture into its cells always; Spread treats a fixture row as one head unless Over says Cells, as the busk tab does. A change in what a fixture-row marquee on a bar does.'],
    ['6 · The rail tabs (idea 1)', 'Now as a session 4, later, or never. The boards do not draw it.'],
    ['7 · Degrees in the position cell editor', 'Drawn in degrees (the Spread endpoints\' unit); the cell keeps bytes. Needs each head\'s degree annotation read on this side, which `store/fixtures.ts` already carries for pan and tilt.'],
  ]
  const sessions = [
    ['1 · The kit', 'Move and rename: `components/editor/` with EditorSurface, EditorLabel, EditorField, EditorReadout, EditorFooter, ValueFieldRow, the keyboard and open hooks, useLivePush. No behaviour change; every import site follows. The four programmer cells and the kit\'s three take the label line, the 28px field and the read-out (Editors board). Percent and the XY pad if calls 3 and 7 say so.', 'lighting-react only'],
    ['2 · The colour editor', 'ColourEditor from ColourPickerBody + the busk tab\'s read-out, Pick, Recent, footer; the programmer\'s colour cell and the busk tab host it; the Spread endpoint hosts the picker half. `liveAppearance` reporting from the grid rows for Pick. The `react-colorful` size rules reduce to the fluid rule.', 'lighting-react only'],
    ['3 · Spread', 'SpreadPanel from SpreadSheet + FanPopover; the programmer\'s SpreadPopover builds intent plans from the marquee with the target rule and the scope arm; the patch list, cue sheet and DMX sheet take the panel with their own kinds; `fanValues` / `fanColours` deleted; the rename lands everywhere including CLAUDE.md. Backend: `write: false` on the spread route and `written[].value` as the literal.', 'lighting-react + one lighting7 route change'],
    ['4 · The rail tabs (if called)', 'Colour and Spread as tabs on the programmer rail, docked hosts of the two components over the marquee.', 'lighting-react only'],
  ]
  const html = `${HEAD}
<div class="app" style="width:${w}px; height:${h}px; padding:24px 28px; display:flex; flex-direction:column; gap:16px; overflow:hidden;">
  ${title('Model: where the code goes, what is renamed, the one wire change, and what is open', 'Nothing here changes a desk fact, a document or a selection. The wire gains one optional field on one route and one response field changes meaning. Everything else is the client folding two implementations of each editor into one module, <span class="mono">components/editor/</span>, that the programmer\'s sheets and the busk view\'s sheet both import.')}
  <div class="row" style="align-items:flex-start; gap:20px;">
    <div class="col" style="flex:1.3;">${sec('Where the code goes')}<table><tr><th style="width:34%;">Becomes</th><th style="width:30%;">From</th><th>Note</th></tr>${moves.map(([a, b, c]) => `<tr><td class="mono" style="color:var(--fg); font-size:10.5px;">${a}</td><td class="mono" style="font-size:10.5px;">${b}</td><td>${c.replace(/`([^`]+)`/g, '<span class="mono">$1</span>')}</td></tr>`).join('')}</table></div>
    <div class="col" style="flex:0.8;">${sec('The rename')}<table><tr><th>Today</th><th>Proposed</th></tr>${renames.map(([a, b]) => `<tr><td class="mono" style="font-size:10.5px;">${a}</td><td class="mono" style="color:var(--fg); font-size:10.5px;">${b}</td></tr>`).join('')}</table>
      ${sec('The wire')}
      ${notes(['<b><span class="mono">POST /programmer/spread</span> gains <span class="mono">write: Boolean = true</span>.</b> False resolves and answers without writing — the focused-Look-layer arm, where the client lands the literals in the layer\'s draft. Nothing else on the route changes.', '<b><span class="mono">written[].value</span> becomes the head\'s literal</b> in the Look row grammar (<span class="mono">serializeLevel</span> / <span class="mono">serializeColour</span> / position), not the interpolated intent. Nothing on this side reads it today, so the change is free; the layer arm is its first reader.', 'No new frame, no new fact, no schema. The busk tabs\' requests are unchanged.'], 'key')}
    </div>
  </div>
  <div class="row" style="align-items:flex-start; gap:20px;">
    <div class="col" style="flex:1.3;">${sec('Other ideas, each with a verdict')}<table><tr><th style="width:26%;">Idea</th><th></th><th>Why</th><th style="width:20%;">Cost</th></tr>${ideas.map(([a, b, v, c]) => `<tr><td><b>${a}</b></td><td>${tag(v === 'go' ? 'drawn' : v === 'ask' ? 'open' : 'declined', v)}</td><td>${b.replace(/`([^`]+)`/g, '<span class="mono">$1</span>')}</td><td>${c}</td></tr>`).join('')}</table></div>
    <div class="col" style="flex:0.8;">${sec('Open — for Chris to call')}${open.map(([h, p]) => `<div class="col" style="gap:2px;"><span class="h2">${h}</span>${note(p.replace(/`([^`]+)`/g, '<span class="mono">$1</span>'), 'warn')}</div>`).join('')}</div>
  </div>
  <div class="row" style="align-items:flex-start; gap:20px;">
    <div class="col" style="flex:1;">${sec('Sessions, as first drawn')}<div style="display:grid; grid-template-columns:repeat(4, minmax(0, 1fr)); gap:12px;">${sessions.map(([h, p, s]) => `<div class="col" style="gap:3px;"><span class="h2">${h}</span>${note(p.replace(/`([^`]+)`/g, '<span class="mono">$1</span>'))}<span class="cap9">${s}</span></div>`).join('')}</div>
    ${notes(['<b>What does not move.</b> The programmer\'s cell selection, the marquee, the scope band, the four cells\' triggers and their placeholder rule; the busk view\'s selection, its rig band, the Speed and Show tabs; the sheet kit\'s columns and writers; every route but the one above. The busk view stays the design authority for anything the boards do not draw: where a board and the shipped busk tab disagree on a measurement, the busk tab wins.', '<b>What the desk survey adds</b> is already in <span class="mono">completed/busk-further-design/Spread.dc.html</span> (Titan · grandMA3 · MagicQ · Hog 4 · Eos on fan) and <span class="mono">sheet-views-design/Spec.dc.html</span> (the cell contract); nothing here re-surveys them. The one new reading: every desk surveyed has <i>one</i> fan, reached from its programmer, and the busk view\'s tab is that fan docked — which is the whole argument for one component.'])}</div>
  </div>
</div>
${TAIL(w, h)}`
  writeFileSync('Model.dc.html', html)
  return { w, h, title: 'Model · code, rename, wire, ideas, open calls, sessions' }
}

// =====================================================================================================
// Board 6 · RailTabs — Colour · Spread as tabs on the programmer rail (session 4, if called)
// =====================================================================================================
function railTabsBoard() {
  const w = 1440, h = H.RailTabs
  const RAIL = 300
  const cnt = (n) => `<span class="mono" style="font-size:9px; background:var(--muted); border-radius:999px; padding:0 5px; line-height:1.5;">${n}</span>`
  // The rail's header as a tab strip. The busk sheet's D3 fold at the rail's own width: below 400
  // only the open tab keeps its word, and the Stack tab's words are the two counts' — the strip's
  // glyph-and-count pairs, which the collapsed rail already draws.
  const railTabs = (on, { words = false } = {}) => {
    const tab = (id, inner, open) => `<span class="${open ? 'on' : ''}" style="${open || words ? 'flex:1 1 auto; padding:0 10px;' : 'flex:0 0 auto; padding:0 10px;'}">${inner}</span>`
    const stack = tab('stack', `${ico('layers', 12)}${on === 'stack' || words ? 'Layers' : ''} ${cnt(3)}<span style="width:4px;"></span><span style="color:oklch(0.7 0.18 293);">${ico('wave', 12)}</span>${on === 'stack' || words ? '<span style="color:oklch(0.7 0.18 293);">FX</span>' : ''} ${cnt(2)}`, on === 'stack')
    const colour = tab('colour', `${ico('palette', 12)}${on === 'colour' || words ? 'Colour' : ''}`, on === 'colour')
    const spread = tab('spread', `${ico('wave', 12)}${on === 'spread' || words ? 'Spread' : ''}`, on === 'spread')
    return `<div class="tabs">${stack}${colour}${spread}<span class="fold" style="flex:0 0 32px; border-left:0; color:var(--mfg);">${ico('panel', 13)}</span><span class="fold">${ico('chevR', 14)}</span></div>`
  }
  const bare = (html, width, extra = '') => html.replace('class="rail"', 'class="x"').replace(new RegExp(`style="width:${width}px;"`), `style="width:${width}px; display:flex; flex-direction:column; flex:0 0 auto; border-left:1px solid var(--bd); background:var(--bg); min-height:0; ${extra}"`)
  // The Stack tab: the rail's body as it is today, under the strip instead of the two labels.
  const layerRow = (n, name, fam, tmpl = false) => `<div class="row" style="gap:6px; height:28px; padding:0 6px; border:1px solid var(--bd); border-radius:6px; background:var(--card); font-size:11px;"><span class="mono" style="font-size:9px; color:var(--mfg); width:12px;">${n}</span>${ico(tmpl ? 'palette' : 'layers', 12, 'color:var(--mfg);')}<span style="flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">${name}</span><span class="pill" style="height:16px; font-size:9px; padding:0 6px;">${fam}</span>${ico('sort', 12, 'color:var(--mfg);')}</div>`
  const fxRow = (name, tempo, prop, target) => `<div class="col" style="gap:2px; padding:5px 6px; border:1px solid var(--bd); border-radius:6px; background:var(--card); font-size:11px;"><div class="row" style="gap:6px;"><span style="color:oklch(0.7 0.18 293);">${ico('wave', 12)}</span><span style="flex:1; font-weight:600; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">${name}</span><span class="cap9">${tempo}</span><span class="cap9">⋯</span></div><div class="row" style="gap:6px;"><span class="cap9">${prop}</span><span class="cap9">·</span><span class="cap9">${target}</span><span class="cap9" style="margin-left:auto;">Local</span></div></div>`
  const stackBody = `<div class="body" style="gap:6px; padding:8px 10px;">
    <div class="row" style="gap:6px;">${lbl('Values', 'color:var(--pri);')}<span class="cap9">top wins</span></div>
    <div class="row" style="gap:6px; height:28px; padding:0 6px; border:1px dashed var(--bd); border-radius:6px; font-size:11px;"><span style="flex:1;">Local values</span><span class="cap9">12 set</span></div>
    ${layerRow(2, 'Warm Wash', 'Colour')}${layerRow(1, 'Spot centre', 'Position', true)}${layerRow(0, 'House half', 'Intensity')}
    <div class="row" style="gap:6px; margin:4px -10px 0; padding:5px 10px; border-top:1px solid oklch(0.45 0.1 60); border-bottom:1px solid oklch(0.45 0.1 60); background:oklch(0.4 0.1 60 / 0.25);">${ico('updown', 12, 'color:var(--amber);')}${lbl('Values above beat effects below', 'color:var(--amber); letter-spacing:0.06em;')}</div>
    <div class="row" style="gap:6px; padding-top:2px;">${lbl('Effects', 'color:oklch(0.7 0.18 293);')}</div>
    ${fxRow('Colour Pulse', '½ · M2', 'Colour', 'Front wash')}${fxRow('Slow Circle', '2 · M1', 'Position', 'Mover 1')}
    <div class="row" style="gap:6px; margin-top:auto; padding-top:6px; font-size:11px; color:var(--mfg);">${ico('chevR', 13)}Per-fixture FX</div>
  </div><div class="foot" style="gap:6px; padding:8px 10px;">${btn('Look', 'out', 'plus', 'flex:1; height:28px;')}${btn('Template', 'out', 'plus', 'flex:1; height:28px;')}${btn('Effect', 'out', 'plus', 'flex:1; height:28px;')}</div>`
  const stackRail = (words = false) => `<div class="rail" style="width:${RAIL}px; height:560px;">${railTabs('stack', { words })}${stackBody}</div>`
  // The Colour tab, docked: the busk tab's body under the rail's strip, with the label line and no Recent.
  // Pick and Spread… fold to their glyphs, as the busk tab's footer does below `FOOTER_WORDS` (340): the rail is narrower than that at every width.
  const colourTab = (width = RAIL, hostLine = '<span>4 heads · Local</span><span style="margin-left:auto;">Colour</span>') => colourEditor({ host: 'docked', width, recent: false, footer: true, counts: true, seedR: true, pickerH: 176, hostLine, tabs: railTabs('colour'), iconVerbs: true })
  const emptyTab = `<div class="rail" style="width:${RAIL}px; height:560px;">${railTabs('colour')}<div class="body" style="align-items:center; justify-content:center; text-align:center; gap:6px; padding:24px;">${ico('marquee', 22, 'color:var(--mfg);')}<span style="font-size:12px; font-weight:600;">Nothing selected</span><span class="cap9" style="max-width:200px;">Drag over Colour cells, or select rows. The tab writes to what row C names, and follows it as it changes.</span></div><div class="foot">${btn('Save as template…', 'out dim', 'save')}<span style="flex:1;"></span>${btn(null, 'out icon dim', 'pipette')}${btn(null, 'out icon dim', 'wave')}</div></div>`
  const spreadTab = spreadPanel({ host: 'docked', width: RAIL, family: 'Position', kind: 'position', from: [250, 120], to: [290, 150], curve: 'Mirror', order: 'Rig', parts: '2', over: 'Heads', overOff: true, live: true, headLine: '<span>6 heads · Local</span><span style="margin-left:auto;">Position</span>', tabs: railTabs('spread') }).replace(`style="width:${RAIL}px;"`, `style="width:${RAIL}px; height:560px;"`)
  // The collapsed strip: the two counts, then one glyph per tab, then +.
  const stripCell = (inner, t = '') => `<div class="col" style="align-items:center; justify-content:center; gap:1px; height:40px; width:40px; color:var(--mfg);">${inner}${t ? `<span class="mono" style="font-size:8px; line-height:1;">${t}</span>` : ''}</div>`
  const strip = `<div class="col" style="width:40px; height:560px; border:1px solid var(--bd); border-radius:10px; background:var(--card); gap:0; align-items:center; overflow:hidden;">${stripCell(ico('chevL', 14))}${stripCell(ico('layers', 14), '3')}${stripCell(`<span style="color:oklch(0.7 0.18 293);">${ico('wave', 14)}</span>`, '2')}<span style="width:24px; height:1px; background:var(--bd); margin:4px 0;"></span>${stripCell(ico('palette', 14))}${stripCell(ico('wave', 14))}<span style="flex:1;"></span>${stripCell(ico('plus', 14))}</div>`
  // The hero: row C over the grid with a 4 × Colour marquee, the rail docked at 300 with the Colour tab open.
  const nameW = 170, colW = 118
  const gridW = nameW + colW * 5
  const hero = `<div style="width:${gridW + RAIL}px; height:${40 + 30 + 36 * 18}px; border:1px solid var(--bd); border-radius:8px; overflow:hidden; display:flex; flex:0 0 auto;">
    <div class="col" style="gap:0; width:${gridW}px;">${rowC({ verb: 'Spread', words: false })}${gridSlice({ nameW, colW, rows: RIG_ROWS, marqueeCol: 'Colour' })}</div>
    ${bare(colourTab(), RAIL, 'height:100%;')}
  </div>`
  const gestures = [
    ['A drag, a click, ⌘A, ↑/↓ — the marquee moves', 'The tab re-targets: its label line, batch and emitter union follow row C, the way the busk tab follows the desk selection. Nothing closes.', 'as today'],
    ['Double click, Set, ⏎ or a typed digit on a <b>Colour</b> cell', '<b>Lands in the tab</b> — R focused and the character seeded — and no popover opens. The tab is that column\'s editor while it is open; two colour editors over one marquee would be the double the kit refuses.', 'the popover'],
    ['The same on a Dimmer, Position or Gobo cell', 'The popover, as today. The tab claims its own column only.', 'the popover'],
    ['<b>Spread</b> on row C, with the Spread tab open', 'Focuses the tab (From). With the Colour tab open instead, the popover opens as today — a tab claims its own verb only.', 'the popover'],
    ['<b>Spread…</b> in the Colour tab\'s footer', 'Opens the Spread tab with From set to the colour\'s RGB — the busk hand-over, <span class="mono">useSpreadSeed</span>\'s shape, one seed the tab drops once read.', 'the popover at Spread'],
    ['Deselect · Escape with nothing open · a scope switch', 'The tab draws its empty state (Deselect), or re-targets to the rows the marquee dropped to (scope switch). A docked panel takes no Escape — the rail\'s rule already.', 'the popover closes'],
    ['The chevron · the strip\'s glyph · the mode toggle', 'Collapse and expand are the rail\'s; a press on the strip\'s palette or wave glyph expands the rail <b>onto that tab</b>, the way its two counts open it on a band. Overlay mode floats the rail with the tab it holds.', 'Layers · FX only'],
  ]
  const scopes = [
    ['Local', 'Writes as the cell does: every drag is <span class="mono">setColour</span> per target through the Colour column\'s writer, the sheet\'s throttled commit.', 'Sends; lands in Local. Live and Apply / Send again.'],
    ['Output', 'Read-only, as the cell\'s trigger is: the picker, fields and rows drawn disabled, the label line reading <i>Output · read-only</i>, the footer\'s Pick still live (a read).', 'Disabled with the popover\'s reason.'],
    ['A focused Look layer', 'Into the layer\'s draft through the same writer arm the cell uses (<span class="mono">LookRowStore.setValue</span>, 400 ms coalesced). Save as template… stays: it records from the selection, not the scope.', '<span class="mono">write: false</span>; the literals land in the draft — session 3\'s arm, unchanged.'],
    ['A focused template layer', 'Refused with the cell\'s words; the tab stays open, disabled.', 'Refused with the popover\'s words.'],
  ]
  const code = [
    ['programmer/ProgrammerWorkspace.tsx', '`railTab: \'stack\' | \'colour\' | \'spread\'` beside `collapsed` in the arm — **not persisted**: every arrival rests on Stack, because a rail that opens on a picker for a marquee that does not exist yet is a panel saying nothing. `openTab(tab)` expands a collapsed rail as `expand` does.'],
    ['programmer/ProgrammerRail.tsx', '`RailHeader` becomes `RailTabs` — the strip, the mode toggle, the chevron, on the same 40px chrome row — with `tabWordClass`\'s fold at the rail\'s own `@container` (open tab keeps its word; the Stack tab\'s words fold to the strip\'s glyph-and-count pairs). `RailBody` + `RailFooter` are the Stack tab. `RailStrip` gains two glyph cells (`onTab`).'],
    ['programmer/RailColourTab.tsx', 'The docked host of `ColourEditor`: targets from the marquee\'s Colour batch through `colourTargetsOf` (the cell\'s rule, so the two cannot count heads two ways), writes through `commitToCells` over the Colour column, `pickOnTargets` on every change of heads, `onSpread` → `openTab(\'spread\')` with the seed. `docked`, `recent` off, `footer` on, the label line as `labelLine`.'],
    ['programmer/RailSpreadTab.tsx', 'The docked host of `SpreadPanel`: plans from the marquee through `useMarqueeSpreadPlans`, lifted out of `SpreadPopover` so the popover and the tab build one plan list; `desk` on; the scope arm as the popover\'s.'],
    ['fixtures-list/FixturesListContainer.tsx', '**Publishes the marquee** — `marqueeBatches`, `columnTargets`, the scope\'s `cellKeyboardPermission` and `scopeLabel` — through a `MarqueeContext` provided by `ProgrammerPage` above both the grid and the rail. The cells stay local state (`useCellSelection`\'s reason stands); the two plain lists provide nothing, and the rail reads null there. The open-gesture claim is one branch in `keyboardOpen`\'s consumer: a Colour open with the Colour tab open focuses the tab.'],
    ['Tests', '`RailTabs.test.tsx` pins the fold as an ordering; `RailColourTab.test.tsx` pins its targets equal to `ColourCell`\'s over one batch and the four scope arms; `FixturesListContainer.test.tsx` gains the claimed open (Colour → the tab, Dimmer → the popover); `ProgrammerPage.test.tsx` keeps `gridMounts` across a tab change.'],
  ]
  const calls = [
    ['8 · Whether at all (call 6, restated)', 'What it buys: a long busk over one marquee with no popover covering the grid, the busk tab\'s shape on the desk\'s other live view. What it costs: a strip on the rail header, the marquee published outside the list, and a third place a colour can be edited from. Drawn; the boards recommend asking.'],
    ['9 · A tab claims its column\'s open gesture', 'Drawn: with the Colour tab open, ⏎ / Set / a double click on a Colour cell land in the tab. The alternative — the popover opens over the tab — draws two editors of one marquee at once.'],
    ['10 · Resting on Stack', 'Drawn: not persisted, every arrival on Layers · FX. The alternative is a desk preference like `collapsed`; it would open a picker on an empty marquee at every visit.'],
    ['11 · The label line in the tab', 'Drawn with (<i>4 heads · Local</i>): row C says the count and the scope band the scope, but the tab is a column away from both. The busk tab draws none, since the band is one row up.'],
    ['12 · The rail\'s floor with a tab open', 'Drawn at 260, the rail\'s own, with the compact curve row below 300. The alternative lifts the floor to 300 while a tab is open — the busk sheet raised its own to 320 for its header, not its tabs.'],
  ]
  const declined = [
    ['A second tab row under LAYERS · FX', '40px of rail height spent saying the counts twice; the busk sheet puts its strip on its one chrome row, and so does this.'],
    ['Recent chips in the tab', 'The tab exists only in the docked arm, where row C\'s strip is on the same screen — D11 answers it with no new rule.'],
    ['The tabs in the overlay arm (704–1200)', 'The overlay closes on the next pointer down outside it (`onPointerDownCapture`), which a picker over the grid needs to survive. Docked only; the overlay strip carries no tab glyphs and the popover is the form there.'],
    ['The tabs on the phone\'s bottom sheet', 'The cell\'s own bottom sheet is that form already, with Recent drawn. The handle opens the stack.'],
    ['A Speed tab, a Show tab', 'The Speed Masters overview panel is every view\'s; the programmer has no transport by decision (§ShowBar).'],
    ['A `viewOptions` key · a MIDI target', 'No two-screen flow needs the rail\'s tab moved from elsewhere. Add the key the day one does; the announce\'s key set is pinned.'],
  ]
  const html = `${HEAD}
<div class="app" style="width:${w}px; height:${h}px; padding:24px 28px; display:flex; flex-direction:column; gap:16px; overflow:hidden;">
  ${title('The rail tabs: Colour and Spread docked on the programmer (session 4, if called)', 'The rail and the busk sheet are one instrument in two views, and both pieces are docked-capable already — <span class="mono">ColourEditor</span>\'s <span class="mono">docked</span> frame and <span class="mono">SpreadPanel</span>\'s <span class="mono">docked</span> host shipped in sessions 2 and 3. What session 4 adds is a <b>tab strip on the rail\'s header</b> — <b>Stack</b> (Layers · FX, as today) · <b>Colour</b> · <b>Spread</b> — and two hosts that read the <b>marquee</b> as the busk tabs read the desk selection. The popover stays the quick form; the tab is the long busk over one marquee, with the grid uncovered. Nothing here changes a desk fact or the wire.')}
  <div class="row" style="align-items:flex-start; gap:20px;">
    <div class="col">${frameLabel('The desk board · a 4 × Colour marquee, the rail docked at 300 with the Colour tab open', 'the rail\'s header is the tab strip; the open tab keeps its word, the others their glyphs; the counts stay on the Stack tab')}${hero}</div>
    <div class="col" style="flex:1; gap:10px; min-width:0;">
      ${note('<b>The strip is the header.</b> <span class="mono">LAYERS n · FX n</span> becomes the Stack tab\'s face, on the same 40px chrome row as today with the mode toggle and the chevron after it. Below 400px of rail — which is every width the rail has, 260 to 480 — only the open tab keeps its word (the busk sheet\'s D3 fold, <span class="mono">tabWordClass</span>), and the Stack tab\'s words fold to the glyph-and-count pairs the collapsed strip already draws. So <i>Layers 3 · FX 2</i> is never lost: it is a face when open and two badges when not.', 'key')}
      ${note('<b>The tab reads the marquee</b> the way the busk tab reads the desk selection: the Colour cells\' heads (<span class="mono">colourTargetsOf</span>, the cell\'s own rule), or with rows selected and no cells, the rows\' heads that take colour. The label line says <i>4 heads · Local</i> because the tab is a column away from row C and the scope band, where a popover sits on the cell. With nothing selected it draws its empty state and stays open.')}
      ${note('<b>No Recent.</b> Row C\'s strip is on this screen whenever the tab is — the tab is offered in the docked arm only — so D11 answers it without a new rule. The footer is the busk tab\'s: <b>Save as template…</b> over the marquee\'s heads, <b>Pick</b> off the hidden leaves, <b>Spread…</b> handing the colour to the Spread tab.')}
      ${note('<b>A tab claims its own column\'s open gesture.</b> With the Colour tab open, ⏎, Set, a typed digit and a double click on a Colour cell land in the tab — R focused, the character seeded — rather than opening a second colour editor over the first. Every other column opens its popover as today. Open call 9.')}
      ${note('<b>Every arrival rests on Stack</b> (call 10): the fact is not persisted, since a rail that opened on a picker for a marquee that is not there yet would be a panel saying nothing. Collapse, expand, overlay and the resize handle are untouched; a press on the strip\'s palette or wave glyph expands the rail onto that tab.')}
      ${note('<b>Docked only.</b> The 704–1200 overlay shuts on the next pointer down outside it, and a picker over the grid needs to survive a press on the grid; the phone\'s bottom sheet is the cell\'s own form already. In both, the popover is the form and the strip carries no tab glyphs.', 'warn')}
    </div>
  </div>
  ${sec('The other three faces of the rail')}
  <div class="row" style="align-items:flex-start; gap:20px;">
    <div class="col">${frameLabel('Spread · a 6 × Position marquee, Live on', 'the panel as the busk tab draws it, at the rail\'s 300; the position pair is two rows, the sheet\'s reason')}${spreadTab}</div>
    <div class="col">${frameLabel('Stack · Layers 3 · FX 2 — the rail as it is today', 'the two labels are the open tab\'s face; the body and footer are unchanged')}${stackRail()}</div>
    <div class="col">${frameLabel('Colour · nothing selected', 'the empty state; the tab stays open and follows the next marquee')}${emptyTab}</div>
    <div class="col">${frameLabel('Collapsed', 'the strip: counts, then a glyph per tab, then +')}${strip}</div>
  </div>
  <div style="display:grid; grid-template-columns:repeat(3, minmax(0, 1fr)); gap:12px 20px;">
    ${note('<b>The Spread tab is <span class="mono">SpreadPopover</span> docked</b>: the family segment answered by the marquee and drawn checked, the Property row where the family holds more than one, Over: Heads, Live off by default on the programmer (D7), Apply always sending. The plans come from one hook the popover and the tab share, so the two cannot build different targets. The scope arm is session 3\'s — a focused Look layer sends <span class="mono">write: false</span> and lands the literals in the draft.')}
    ${note('<b>The Stack tab is the rail as shipped</b> — Values · top wins, the Local row, the dense layer rows, the amber boundary, the effects, Per-fixture FX, and the footer\'s three adds. The only change on it is the header row above.')}
    ${note('<b>Width.</b> 300 by default, 260 to 480 by the shared handle, in <span class="mono">localStorage</span> as today. The colour editor is fluid from 260 (the R/G/B column is 80, the picker takes the rest); the Spread panel takes the compact curve row below 300. The floor is not raised for the tabs (call 12): the busk sheet lifted its own to 320 for its four worded tabs, and this strip folds to glyphs.')}
  </div>
  ${sec('What a gesture does while a tab is open')}
  <table style="max-width:1384px;"><tr><th style="width:26%;">Gesture</th><th>With the Colour or Spread tab open</th><th style="width:16%;">Today (Stack)</th></tr>${gestures.map(([g, a, t]) => `<tr><td class="y">${g}</td><td>${a}</td><td>${t}</td></tr>`).join('')}</table>
  <div class="row" style="align-items:flex-start; gap:20px;">
    <div class="col" style="flex:1;">${sec('The scope, per tab')}<table><tr><th style="width:20%;">Scope</th><th>Colour tab</th><th style="width:34%;">Spread tab</th></tr>${scopes.map(([s, c, p]) => `<tr><td class="y"><b>${s}</b></td><td>${c}</td><td>${p}</td></tr>`).join('')}</table>
      ${sec('Where the code goes')}<table><tr><th style="width:26%;">File</th><th>What</th></tr>${code.map(([f, w2]) => `<tr><td class="mono" style="color:var(--fg); font-size:10.5px;">${f}</td><td>${w2.replace(/`([^`]+)`/g, '<span class="mono">$1</span>').replace(/\\*\\*([^*]+)\\*\\*/g, '<b>$1</b>')}</td></tr>`).join('')}</table></div>
    <div class="col" style="width:420px;">${sec('Open — for Chris to call')}${calls.map(([h2, p]) => `<div class="col" style="gap:2px;"><span class="h2">${h2}</span>${note(p.replace(/`([^`]+)`/g, '<span class="mono">$1</span>'), 'warn')}</div>`).join('')}
      ${sec('Declined')}<table><tr><th style="width:40%;">Not drawn</th><th>Why</th></tr>${declined.map(([a, b]) => `<tr><td class="y">${a}</td><td>${b.replace(/`([^`]+)`/g, '<span class="mono">$1</span>')}</td></tr>`).join('')}</table></div>
  </div>
</div>
${TAIL(w, h)}`
  writeFileSync('RailTabs.dc.html', html)
  return { w, h, title: 'The rail tabs · Colour and Spread docked on the programmer' }
}

// ---- run ----------------------------------------------------------------------------------------
const boards = {}
boards.Survey = surveyBoard()
boards.Spread = spreadBoard()
boards.Colour = colourBoard()
boards.Editors = editorsBoard()
boards.Model = modelBoard()
boards.RailTabs = railTabsBoard()

// ---- canvas.json (the busk-chrome record's v3 shape) ---------------------------------------------
const GAPX = 80, GAPY = 120
const order = ['Survey', 'Spread', 'Colour', 'Editors', 'Model', 'RailTabs']
const pos = {}
let x = 0
for (const k of order) { pos[k] = { x, y: 0 }; x += boards[k].w + GAPX }
const canvas = {
  v: 3,
  attachments: {},
  boards: Object.fromEntries(order.map((k) => [`${k}.dc.html`, { title: boards[k].title, w: boards[k].w, h: boards[k].h, x: pos[k].x, y: pos[k].y }])),
  createdOnFiles: { at: '2026-09-22T12:00:00Z', v: 1 },
  designSystems: [],
  launch: { view: 'canvas' },
  notes: {
    t1: { kind: 'title1', maxW: 3000, text: 'One editor kit: Spread, the colour editor and the value editors across the programmer and the busk view', w: 240, x: 0, y: -300 },
    how: { color: 'gray', text: 'How to read this. Survey is today, side by side. Spread, Colour and Editors are the one answer, each drawn in every host. Model is where the code goes, the rename, the one wire change, ideas with verdicts, the seven open calls and the session split. Dark-only, the busk view\'s vocabulary — the intended pixels, not structure to copy.', w: 360, x: 0, y: -180 },
    q1: { color: 'orange', text: 'Open calls 1–7 are on Model. Each is drawn one way; the boards say which.', w: 360, x: pos.Model.x, y: -180 },
    q2: { color: 'orange', text: 'RailTabs, drawn 2026-09-23 after sessions 1–3 shipped: call 6 (the rail tabs) drawn out, with calls 8–12 of its own. Session 4 waits on it.', w: 360, x: pos.RailTabs.x, y: -180 },
  },
  order: order.map((k) => `${k}.dc.html`),
  pages: [],
  title: 'Editor Kit',
}
writeFileSync('canvas.json', JSON.stringify(canvas, null, 2) + '\n')

// ---- the seeded artifact: every board on one scrolling page, scaled to the window -----------------
const boardBody = (file) => {
  const html = readFileSync(file, 'utf8')
  return html.slice(html.indexOf('<div class="app"'), html.lastIndexOf('</x-dc>'))
}
// Body-only on purpose: the artifact host wraps it in its own skeleton, and a browser renders it as it is.
const artifact = `<title>Editor Kit Boards</title>
<style>
  :root { --bg:${T.bg}; --fg:${T.fg}; --mfg:${T.mfg}; --bd:${T.border}; --card:${T.card}; --pri:${T.primary}; }
  :root:not([data-theme="light"]) { --bg:${T.bg}; }
  :root[data-theme="dark"] { --bg:${T.bg}; }
  html, body { margin:0; background:var(--bg); color:var(--fg); font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif; }
  nav { position:sticky; top:0; z-index:5; display:flex; align-items:center; gap:6px; padding:10px 16px; background:${T.bg}f2; border-bottom:1px solid var(--bd); backdrop-filter:blur(8px); flex-wrap:wrap; }
  nav .t { font-size:13px; font-weight:700; margin-right:8px; }
  nav a { color:var(--mfg); text-decoration:none; font-size:12px; padding:4px 8px; border-radius:6px; border:1px solid transparent; }
  nav a:hover { color:var(--fg); border-color:var(--bd); }
  nav .z { margin-left:auto; display:flex; gap:4px; font-size:11px; color:var(--mfg); align-items:center; }
  nav .z button { background:var(--card); color:var(--fg); border:1px solid var(--bd); border-radius:6px; padding:3px 8px; font-size:11px; cursor:pointer; }
  nav .z button[aria-pressed="true"] { border-color:var(--pri); }
  main { padding:16px; display:flex; flex-direction:column; gap:28px; }
  section h2 { font-size:12px; font-weight:700; text-transform:uppercase; letter-spacing:0.08em; color:var(--mfg); margin:0 0 8px; }
  .frame { overflow:hidden; border:1px solid var(--bd); border-radius:10px; }
  .scale { transform-origin: top left; }
  .intro { max-width:820px; font-size:13px; line-height:1.5; color:var(--mfg); }
  .intro b { color:var(--fg); }
  ${CSS}
</style>
<nav><span class="t">Editor kit · design boards · 2026-09-22 · RailTabs 2026-09-23</span>${order.map((k) => `<a href="#${k.toLowerCase()}">${k}</a>`).join('')}<span class="z">Zoom <button data-z="fit" aria-pressed="true">Fit</button><button data-z="0.5">50%</button><button data-z="0.75">75%</button><button data-z="1">100%</button></span></nav>
<main>
  <p class="intro"><b>One editor kit for the programmer and the busk view.</b> Five boards: <b>Survey</b> is today, side by side; <b>Spread</b>, <b>Colour</b> and <b>Editors</b> draw the one answer in every host; <b>Model</b> is where the code goes, the rename, the one wire change, ideas with verdicts, the seven open calls and the session split. <b>RailTabs</b>, drawn after sessions 1–3 shipped, is call 6 drawn out — Colour and Spread as tabs on the programmer rail, session 4 if called — with five calls of its own. The checked-in files in <code>lighting7/docs/plans/editor-kit-design/</code> are the authority; this page is the same boards on one scroll. Dark-only, by design.</p>
  ${order.map((k) => `<section id="${k.toLowerCase()}"><h2>${k} — ${boards[k].title}</h2><div class="frame" data-w="${boards[k].w}" data-h="${boards[k].h}"><div class="scale">${boardBody(`${k}.dc.html`)}</div></div></section>`).join('')}
</main>
<script>
  const frames = [...document.querySelectorAll('.frame')]
  let mode = 'fit'
  function layout() {
    const avail = document.querySelector('main').clientWidth - 32
    for (const f of frames) {
      const w = +f.dataset.w, h = +f.dataset.h
      const s = mode === 'fit' ? Math.min(1, avail / w) : +mode
      f.querySelector('.scale').style.transform = 'scale(' + s + ')'
      f.style.width = Math.round(w * s) + 'px'
      f.style.height = Math.round(h * s) + 'px'
      f.style.overflowX = s > (avail / w) ? 'auto' : 'hidden'
      if (s > avail / w) { f.style.width = avail + 'px' }
    }
  }
  document.querySelectorAll('nav .z button').forEach((b) => b.addEventListener('click', () => {
    mode = b.dataset.z
    document.querySelectorAll('nav .z button').forEach((o) => o.setAttribute('aria-pressed', String(o === b)))
    layout()
  }))
  window.addEventListener('resize', layout)
  layout()
</script>`
writeFileSync('editor-kit.html', artifact)
console.log(Object.entries(boards).map(([k, b]) => `${k} ${b.w}×${b.h}`).join('\n'))
