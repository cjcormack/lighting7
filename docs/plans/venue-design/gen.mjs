// Generates the venue design boards from the Stage boards' stylesheet.
import { readFileSync, writeFileSync } from 'node:fs'

const OUT = new URL('.', import.meta.url).pathname
// The Stage boards' stylesheet, lines 5–223 of ../stage-view-design/Stage.dc.html.
const BASE = readFileSync(new URL('../stage-view-design/Stage.dc.html', import.meta.url), 'utf8').split('\n').slice(4, 223).join('\n')

const EXTRA = `
    /* ── additions for the venue boards ───────────────────────────────────── */
    :root { --ven:oklch(0.82 0.09 190); --venbd:oklch(0.6 0.09 190 / 0.55); --venbg:oklch(0.3 0.05 190 / 0.35);
      --shw:oklch(0.8 0.12 255); --shwbd:oklch(0.55 0.14 255 / 0.55); --shwbg:oklch(0.3 0.08 255 / 0.3);
      --ovr:oklch(0.82 0.12 300); --ovrbd:oklch(0.55 0.14 300 / 0.6); --ovrbg:oklch(0.3 0.08 300 / 0.3);
      --out:oklch(0.8 0.13 35); --outbd:oklch(0.58 0.16 35 / 0.6); --outbg:oklch(0.3 0.08 35 / 0.3); }
    .own { display:inline-flex; align-items:center; gap:4px; height:20px; padding:0 7px; border-radius:999px; font-size:10.5px; font-weight:600; white-space:nowrap; border:1px solid var(--bd); color:var(--mfg); }
    .own.v { color:var(--ven); border-color:var(--venbd); background:var(--venbg); }
    .own.s { color:var(--shw); border-color:var(--shwbd); background:var(--shwbg); }
    .own.o { color:var(--ovr); border-color:var(--ovrbd); background:var(--ovrbg); }
    .own.d { color:oklch(0.86 0.13 80); border-color:oklch(0.65 0.13 80 / 0.6); background:oklch(0.32 0.07 80 / 0.35); }
    .own.x { color:var(--out); border-color:var(--outbd); background:var(--outbg); }
    .own.h { border-style:dashed; }
    .src { width:22px; height:22px; border-radius:6px; display:grid; place-items:center; }
    .src.v { color:var(--ven); background:var(--venbg); }
    .src.s { color:var(--shw); background:var(--shwbg); }
    .main { flex:1; min-width:0; display:flex; flex-direction:column; position:relative; }
    .body { flex:1; min-height:0; position:relative; display:flex; }
    .tbl { width:100%; border-collapse:collapse; font-size:12px; }
    .tbl th { text-align:left; font-size:9px; font-weight:700; text-transform:uppercase; letter-spacing:0.08em; color:var(--mfg); padding:7px 8px; border-bottom:1px solid var(--bd); white-space:nowrap; background:oklch(0.17 0.005 286); }
    .tbl td { height:36px; padding:0 8px; border-bottom:1px solid oklch(0.274 0.006 286.033 / 0.6); white-space:nowrap; vertical-align:middle; }
    .tbl td.m { font-family:ui-monospace,Menlo,monospace; font-variant-numeric:tabular-nums; font-size:11.5px; }
    .tbl td.dim { color:var(--mfg); }
    .tbl tr.pick td { background:oklch(0.623 0.214 259.815 / 0.12); }
    .tbl tr.gone td.nm, .tbl tr.gone td.m, .tbl tr.hid td.nm, .tbl tr.hid td.m { text-decoration:line-through; color:var(--mfg); }
    .tbl tr.hid td { opacity:0.75; }
    .tbl .ovv { color:var(--ovr); font-weight:600; }
    .uchips { display:flex; align-items:center; gap:8px; padding:8px 12px; border-bottom:1px solid var(--bd); }
    .uchip { display:inline-flex; flex-direction:column; gap:3px; padding:4px 9px; border-radius:8px; border:1px solid var(--bd); background:var(--card); font-size:11px; font-weight:600; min-width:72px; }
    .uchip i { display:block; height:3px; border-radius:2px; background:var(--muted); position:relative; overflow:hidden; }
    .uchip i b { position:absolute; left:0; top:0; bottom:0; background:var(--pri); }
    .uchip.new { border-color:oklch(0.62 0.17 145 / 0.6); }
    .toolbar { display:flex; align-items:center; gap:8px; padding:8px 12px; border-bottom:1px solid var(--bd); }
    .vsheet { position:absolute; top:0; right:0; bottom:0; width:430px; background:var(--card); border-left:1px solid var(--bd); box-shadow:-16px 0 36px rgba(0,0,0,0.5); display:flex; flex-direction:column; z-index:5; }
    .vsheet .sh { padding:14px 16px 12px; border-bottom:1px solid var(--bd); display:flex; flex-direction:column; gap:6px; }
    .vsheet .sh .t { font-size:15px; font-weight:600; display:flex; align-items:center; gap:8px; }
    .vsheet .sb { padding:14px 16px; display:flex; flex-direction:column; gap:14px; flex:1; overflow:hidden; }
    .vsheet .sf { padding:10px 16px; border-top:1px solid var(--bd); display:flex; gap:8px; align-items:center; }
    .fl { display:flex; flex-direction:column; gap:5px; position:relative; }
    .fl > label { font-size:11.5px; font-weight:600; }
    .ta { min-height:54px; padding:7px 9px; border-radius:8px; border:1px solid var(--bd); background:oklch(0.274 0.006 286.033 / 0.3); font-size:12px; line-height:1.45; }
    .sel.open { border-color:var(--pri); box-shadow:0 0 0 2px oklch(0.623 0.214 259.815 / 0.25); }
    .sel .caret { margin-left:auto; color:var(--mfg); }
    .menu { position:absolute; background:var(--card); border:1px solid var(--bd); border-radius:10px; box-shadow:0 14px 32px rgba(0,0,0,0.55); padding:4px; z-index:7; min-width:230px; }
    .menu .mi { display:flex; align-items:center; gap:8px; min-height:30px; padding:4px 8px; border-radius:6px; font-size:12px; }
    .menu .mi.on { background:var(--muted); }
    .menu .mi small { margin-left:auto; color:var(--mfg); font-size:10.5px; padding-left:12px; }
    .menu .mi .sub { display:block; font-size:10.5px; color:var(--mfg); }
    .menu .ms { height:1px; background:var(--bd); margin:4px 0; }
    .menu .mi.red { color:oklch(0.72 0.18 27); }
    .menu .mi.new { color:var(--ven); }
    .menu .ml { font-size:9px; font-weight:700; text-transform:uppercase; letter-spacing:0.08em; color:var(--mfg); padding:6px 8px 2px; }
    .pcard { border:1px solid var(--bd); border-radius:12px; background:var(--card); padding:14px 14px 12px; display:flex; flex-direction:column; gap:8px; position:relative; }
    .pcard.cur { background:oklch(0.623 0.214 259.815 / 0.10); border-color:oklch(0.623 0.214 259.815 / 0.45); }
    .pcard.ven { border-color:var(--venbd); background:oklch(0.25 0.03 190 / 0.35); }
    .pcard .pn { font-size:15px; font-weight:600; display:flex; align-items:center; gap:8px; }
    .pcard .pd { font-size:12px; color:var(--mfg); }
    .pcard .kebab { position:absolute; right:10px; top:12px; color:var(--mfg); }
    .badges { display:flex; flex-wrap:wrap; gap:6px; align-items:center; }
    .badge { display:inline-flex; align-items:center; gap:4px; height:20px; padding:0 8px; border-radius:6px; font-size:10.5px; font-weight:600; border:1px solid var(--bd); color:var(--fg); white-space:nowrap; }
    .badge.fill { background:var(--pri); border-color:var(--pri); color:#fff; }
    .banner { display:flex; align-items:center; gap:12px; padding:10px 16px; border-bottom:1px solid; font-size:12.5px; flex-shrink:0; }
    .banner .bt { flex:1; line-height:1.45; }
    .banner .bt b { font-weight:700; }
    .banner .bt span { color:oklch(0.8 0.01 286); }
    .banner.info { background:var(--venbg); border-color:var(--venbd); }
    .banner.info > svg { color:var(--ven); }
    .banner.hold { background:oklch(0.34 0.08 70 / 0.4); border-color:oklch(0.72 0.14 75 / 0.6); }
    .banner.hold > svg { color:oklch(0.86 0.14 80); }
    .banner.edit { background:var(--venbg); border-color:var(--venbd); padding:7px 16px; font-size:12px; }
    .blind { display:inline-flex; align-items:center; gap:6px; height:28px; padding:0 10px; border-radius:8px; border:1px solid oklch(0.75 0.15 80 / 0.8); background:oklch(0.4 0.1 80 / 0.55); color:oklch(0.92 0.12 85); font-size:12px; font-weight:700; }
    .facet { border:1px solid var(--bd); border-radius:10px; padding:9px 10px; display:flex; flex-direction:column; gap:7px; background:var(--bg); }
    .facet.ovr { border-color:var(--ovrbd); }
    .facet .fh { display:flex; align-items:center; gap:8px; }
    .facet .fh b { font-size:12.5px; flex:1; }
    .facet .fv { font-size:11.5px; color:var(--mfg); display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
    .facet .fv .mono { color:var(--fg); }
    .seg.vs span.on.v { background:var(--venbg); color:var(--ven); }
    .seg.vs span.on.s { background:var(--ovrbg); color:var(--ovr); }
    .chg { display:flex; align-items:center; gap:8px; min-height:30px; padding:4px 9px; border-radius:7px; background:var(--bg); border:1px solid var(--bd); font-size:11.5px; line-height:1.35; }
    .chg .sym { width:14px; flex:0 0 auto; text-align:center; font:800 13px ui-monospace,Menlo,monospace; }
    .chg .sym.p { color:oklch(0.8 0.15 145); }
    .chg .sym.m { color:oklch(0.75 0.17 27); }
    .chg .sym.c { color:oklch(0.85 0.13 80); }
    .chg .tx { flex:1; min-width:0; }
    .chg .tx small { display:block; color:var(--mfg); font-size:10.5px; }
    .grouph { display:flex; align-items:center; gap:8px; font-size:11px; font-weight:700; text-transform:uppercase; letter-spacing:0.07em; color:var(--mfg); }
    .grouph .ct { display:inline-flex; align-items:center; justify-content:center; min-width:18px; height:16px; padding:0 5px; border-radius:999px; background:var(--muted); color:var(--fg); font-size:10px; letter-spacing:0; }
    .grouph.x { color:var(--out); }
    .grouph.ok { color:oklch(0.8 0.15 145); }
    .grouph.d { color:oklch(0.86 0.13 80); }
    .stack { display:flex; flex-direction:column; gap:6px; }
    .dlg { position:absolute; background:var(--card); border:1px solid var(--bd); border-radius:14px; box-shadow:0 24px 60px rgba(0,0,0,0.6); display:flex; flex-direction:column; z-index:6; overflow:hidden; }
    .dlg .dh { padding:16px 20px 12px; border-bottom:1px solid var(--bd); display:flex; flex-direction:column; gap:6px; }
    .dlg .dh .t { font-size:17px; font-weight:600; }
    .dlg .db { padding:16px 20px; display:grid; gap:20px; flex:1; min-height:0; }
    .dlg .df { padding:12px 20px; border-top:1px solid var(--bd); display:flex; gap:8px; align-items:center; background:oklch(0.17 0.005 286); }
    .scrim { position:absolute; inset:0; background:rgba(0,0,0,0.45); z-index:5; }
    .fr { border:1px solid var(--bd); border-radius:10px; overflow:hidden; background:var(--bg); }
    .fr .fcap { font-size:10px; font-weight:700; text-transform:uppercase; letter-spacing:0.08em; color:var(--mfg); padding:6px 10px; background:oklch(0.17 0.005 286); border-bottom:1px solid var(--bd); }
    .hdrrow { display:flex; align-items:center; gap:10px; padding:0 16px; height:48px; border-bottom:1px solid var(--bd); flex-shrink:0; }
    .hdrrow .vt { font-size:14px; font-weight:600; }
    .vtag { display:inline-flex; align-items:center; gap:4px; height:18px; padding:0 7px; border-radius:5px; font-size:9.5px; font-weight:800; letter-spacing:0.08em; background:var(--venbg); color:var(--ven); border:1px solid var(--venbd); }
    .board { padding:20px 24px; display:flex; flex-direction:column; gap:16px; }
    .kvt { display:grid; grid-template-columns:150px 1fr; gap:6px 14px; font-size:11.5px; line-height:1.45; }
    .kvt .k { color:var(--fg); font-weight:600; }
    .kvt > span:nth-child(even) { color:var(--mfg); }
    .wtbl { width:100%; border-collapse:collapse; font-size:11.5px; }
    .wtbl th { text-align:left; font-size:9px; font-weight:700; text-transform:uppercase; letter-spacing:0.08em; color:var(--mfg); padding:6px 8px; border-bottom:1px solid var(--bd); }
    .wtbl td { padding:6px 8px; border-bottom:1px solid oklch(0.274 0.006 286.033 / 0.6); vertical-align:top; line-height:1.4; }
    .wtbl td:first-child { white-space:nowrap; font-weight:600; }
    .dec { display:grid; grid-template-columns:34px 1fr; gap:4px 10px; font-size:11.5px; line-height:1.45; color:var(--mfg); }
    .dec b { color:var(--fg); font-weight:600; }
    .dec .dn { font:700 11px ui-monospace,Menlo,monospace; color:var(--amber); }
`

const ico = (d, cls = 'ico14') => `<svg class="${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${d}</svg>`
const I = {
  menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
  hall: '<path d="M3 21h18"/><path d="M5 21V8l7-5 7 5v13"/><path d="M9 21v-6h6v6"/>',
  ticket: '<path d="M3 8a2 2 0 0 0 2-2h14a2 2 0 0 0 2 2v2a2 2 0 0 0 0 4v2a2 2 0 0 0-2 2H5a2 2 0 0 0-2-2v-2a2 2 0 0 0 0-4Z"/><path d="M13 6v12" stroke-dasharray="2 2"/>',
  plug: '<path d="M9 2v6M15 2v6M6 8h12v4a6 6 0 0 1-12 0Z"/><path d="M12 18v4"/>',
  grid: '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
  layers: '<path d="m12 2 9 5-9 5-9-5 9-5Z"/><path d="m3 12 9 5 9-5"/>',
  code: '<path d="m8 6-6 6 6 6M16 6l6 6-6 6"/>',
  wand: '<path d="M15 4V2M15 16v-2M8 9h2M20 9h2M17.8 11.8 19 13M15 9h.01M17.8 6.2 19 5M3 21l9-9M12.2 6.2 11 5"/>',
  palette: '<circle cx="13.5" cy="6.5" r="1"/><circle cx="17.5" cy="10.5" r="1"/><circle cx="8.5" cy="7.5" r="1"/><circle cx="6.5" cy="12.5" r="1"/><path d="M12 2a10 10 0 0 0 0 20c1 0 2-1 2-2 0-.5-.2-1-.5-1.3-.3-.4-.5-.8-.5-1.3 0-1 1-2 2-2h2.3A5.6 5.6 0 0 0 22 10c0-4.4-4.5-8-10-8Z"/>',
  sliders: '<path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6"/>',
  play: '<path d="m6 4 14 8-14 8Z"/>',
  book: '<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20V3H6.5A2.5 2.5 0 0 0 4 5.5Z"/><path d="M4 19.5A2.5 2.5 0 0 0 6.5 22H20v-5"/>',
  pads: '<rect x="3" y="3" width="8" height="8" rx="2"/><rect x="13" y="3" width="8" height="8" rx="2"/><rect x="3" y="13" width="8" height="8" rx="2"/><rect x="13" y="13" width="8" height="8" rx="2"/>',
  win: '<rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8M12 17v4"/>',
  chan: '<path d="M4 20V10M9 20V4M14 20v-7M19 20V8"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9 7 7M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1"/>',
  cloud: '<path d="M17.5 19a4.5 4.5 0 1 0-1.4-8.8A6 6 0 0 0 4 12a4 4 0 0 0 1 7.9Z"/>',
  dots: '<circle cx="12" cy="5" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="12" cy="19" r="1"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  x: '<path d="M18 6 6 18M6 6l12 12"/>',
  check: '<path d="m5 12 5 5L20 7"/>',
  warn: '<path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z"/><path d="M12 9v4M12 17h.01"/>',
  eyeOff: '<path d="M3 3l18 18"/><path d="M10.6 5.1A10 10 0 0 1 12 5c6.5 0 10 7 10 7a17 17 0 0 1-3.2 4.2M6.6 6.6C3.8 8.4 2 12 2 12s3.5 7 10 7a9.7 9.7 0 0 0 5.4-1.6"/><path d="M9.9 9.9a3 3 0 0 0 4.2 4.2"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  upload: '<path d="M12 15V3"/><path d="m7 8 5-5 5 5"/><path d="M5 21h14"/>',
  up: '<path d="M12 19V5"/><path d="m5 12 7-7 7 7"/>',
  lock: '<rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
  pencil: '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/>',
  reset: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/>',
  seat: '<path d="M6 19v-4h12v4M7 15V7a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v8"/>',
  split: '<path d="M16 3h5v5M8 3H3v5M21 3l-7 7M3 3l7 7M12 22v-8"/>',
  filter: '<path d="M3 5h18l-7 8v6l-4 2v-8Z"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="m21 21-4.3-4.3"/>',
  dot: '<circle cx="12" cy="12" r="4" fill="currentColor"/>',
  diff: '<path d="M12 3v14M5 10h14"/><path d="M5 21h14"/>',
  tag: '<path d="M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0L3 13V3h10l7.6 7.6a2 2 0 0 1 0 2.8Z"/><circle cx="7.5" cy="7.5" r="1.5"/>',
  arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>',
}

const RAIL = [
  ['plug', 'patch'], ['grid'], ['layers'], 0,
  ['code'], ['wand'], ['palette'], ['sliders'], 0,
  ['sliders', 'prog'], ['play', 'show'], ['book', 'book'], ['pads', 'busk'], 0,
  ['win', 'stage'], ['chan'], 0, ['gear'],
]
/** The collapsed nav rail. `venue` drops the show-only views, as a venue project would. */
const rail = (on, { venue = false } = {}) => {
  const showOnly = new Set(['code', 'wand', 'palette', 'show', 'book', 'busk'])
  const items = RAIL.filter((r, i) => !(venue && r && (showOnly.has(r[1]) || (i >= 4 && i <= 7))))
  const cleaned = items.filter((r, i, a) => !(r === 0 && (a[i - 1] === 0 || i === 0)))
  return `<div class="rail"><div class="rh">${ico(I.menu, 'ico16')}</div>${cleaned.map((r) => r === 0 ? '<div class="rsep"></div>' : `<div class="ri ${r[1] && r[1] === on ? 'on' : ''}">${ico(I[r[0]], 'ico16')}</div>`).join('')}</div>`
}
const appbar = (extra = '') => `<div class="appbar"><span class="title">Chris' DMX Controller v7</span>${extra}<span class="conn">${ico('<circle cx="12" cy="12" r="9"/><path d="m8 12 3 3 5-6"/>')}Connected</span><span class="avatar">CC</span></div>`

const doc = (title, w, h, body) => `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>${title}</title>
  <style>
${BASE}
${EXTRA}
  </style>
</head>
<body>
<x-dc>
<div class="app" style="width:${w}px; height:${h}px; display:flex; flex-direction:column; overflow:hidden; position:relative;">
${body}
</div>
</x-dc>
<script data-dc-script data-props='{"$preview":{"width":${w},"height":${h}}}'>
class Component extends (window.DCLogic || class {}) { renderVals() { return {}; } }
</script>
</body>
</html>
`

const legend = (items) => `<div class="legend">${items.map(([n, t]) => `<div><span class="n">${n}</span><span>${t}</span></div>`).join('')}</div>`
const callout = (n, style) => `<span class="callout" style="${style}">${n}</span>`
const own = (k, label, icon) => `<span class="own ${k}">${icon ? ico(I[icon], 'ico12') : ''}${label}</span>`
const src = (k) => `<span class="src ${k}">${ico(k === 'v' ? I.hall : I.ticket, 'ico12')}</span>`
const venueChip = (v, extra = '') => `<span class="own v">${ico(I.hall, 'ico12')}Commemoration Hall · ${v}</span>${extra}`
const W = 1440
const boards = {}

/* ─────────────────────────────── Projects ─────────────────────────────── */
{
  const H = 1060
  const card = ({ name, desc, cur, badges, kebabOpen }) => `
    <div class="pcard ${cur ? 'cur' : ''}"><span class="kebab">${ico(I.dots)}</span>
      <div class="pn">${ico(I.ticket, 'ico16')}${name}</div>
      <div class="pd">${desc}</div>
      <div class="badges">${badges}</div>
    </div>`
  const body = `
<div style="display:flex; flex:1; min-height:0;">
${rail('')}
<div class="main">
${appbar()}
<div class="hdrrow"><span class="vt">Projects</span><span class="spacer"></span>
  <span class="btn out sm">${ico(I.upload, 'ico12')}Import</span><span class="btn out sm">${ico(I.cloud, 'ico12')}Add remote project</span>
  <span class="btn out sm">${ico(I.hall, 'ico12')}Add venue</span><span class="btn pri sm">${ico(I.plus, 'ico12')}Create Project</span></div>
<div class="body">
<div style="flex:1; padding:18px 20px; display:flex; flex-direction:column; gap:14px; padding-right:460px; position:relative">
  <div class="lbl" style="display:flex; align-items:center; gap:8px">${ico(I.hall, 'ico12')}Venues</div>
  <div style="display:grid; grid-template-columns:1fr 1fr; gap:12px">
    <div class="pcard ven"><span class="kebab">${ico(I.dots)}</span>
      <div class="pn"><span style="color:var(--ven)">${ico(I.hall, 'ico16')}</span>Commemoration Hall<span class="vtag">VENUE</span></div>
      <div class="pd">The hall as hung — house patch, bars, room, tabs. 7.2 × 6.0 m stage, balcony.</div>
      <div class="badges"><span class="badge">${ico(I.tag, 'ico12')}v5 · 3 Oct 2026</span><span class="own d">${ico(I.pencil, 'ico12')}Draft · 6 changes</span><span class="badge">${ico(I.cloud, 'ico12')}synced</span></div>
      <div class="pd" style="display:flex; gap:14px; font-size:11px"><span>31 house fixtures · 4 universes · 6 riggings</span><span>3 shows · 2 behind</span></div>
    </div>
    <div class="pcard" style="border-style:dashed; align-items:center; justify-content:center; color:var(--mfg); font-size:12px; gap:4px">
      ${ico(I.split, 'ico16')}<span><b style="color:var(--fg)">Extract a venue from a project…</b></span><span style="font-size:11px; text-align:center">Moves a project's room, house patch, universes and bars into a new venue at v1, and points the project at it.</span>
    </div>
  </div>
  <div class="lbl" style="display:flex; align-items:center; gap:8px; margin-top:6px">${ico(I.ticket, 'ico12')}Shows</div>
  <div style="display:grid; grid-template-columns:1fr 1fr 1fr; gap:12px; position:relative">
    ${card({ name: 'TCH 2026', desc: 'Summer show — movers, cannon, LX3 batten.', cur: true, badges: `<span class="badge fill">active</span>${venueChip('v5')}<span class="badge">${ico(I.cloud, 'ico12')}synced</span>` })}
    ${card({ name: 'Panto 2027', desc: 'Cinderella. Rehearsals from November.', badges: `<span class="badge">inactive</span>${venueChip('v4')}<span class="own d">${ico(I.up, 'ico12')}v5 available</span>` })}
    ${card({ name: 'Spring Concert 2026', desc: 'Archived after the April run.', badges: `<span class="badge">inactive</span>${venueChip('v3')}<span class="own x">${ico(I.up, 'ico12')}v5 · re-patched</span>` })}
    ${card({ name: 'Studio test', desc: 'Bench rig for fixture work.', badges: `<span class="badge">inactive</span><span class="own h">No venue</span>` })}
    <div class="menu" style="left:calc(33.3% + 4px); top:150px; width:250px">
      <div class="mi">Activate</div><div class="mi">Scripts</div><div class="mi">Configure</div><div class="mi">Clone</div><div class="mi">Export</div>
      <div class="ms"></div>
      <div class="mi new">${ico(I.up)}Update venue to v5…</div>
      <div class="mi">${ico(I.split)}Detach from venue…</div>
      <div class="ms"></div>
      <div class="mi red">Delete</div>
    </div>
    ${callout(5, 'left:calc(33.3% + 240px); top:140px')}
  </div>
  ${callout(1, 'left:10px; top:44px')}
  ${callout(2, 'left:calc(66.6% - 30px); top:262px')}
</div>
<div class="vsheet">
  <div class="sh"><span class="t">Create New Project</span><p class="cap">A show. It starts from a venue's rig and layers its own changes on top.</p></div>
  <div class="sb">
    <div class="fl"><label>Project Name *</label><div class="field">Panto 2027</div></div>
    <div class="fl"><label>Description</label><div class="ta" style="min-height:40px">Cinderella. Rehearsals from November.</div></div>
    <div class="fl"><label>Venue</label><div class="sel open">${ico(I.hall, 'ico12')}Commemoration Hall · v5 (latest)</div>
      <div class="menu" style="top:56px; left:0; right:0">
        <div class="mi on">${ico(I.hall)}<span>Commemoration Hall · v5<span class="sub">latest · 3 Oct 2026 · “Added house lights on U4”</span></span>${ico(I.check)}</div>
        <div class="ms"></div>
        <div class="mi">${ico(I.x)}<span>None — standalone<span class="sub">The project holds its whole rig, as every project does today.</span></span></div>
      </div>
    </div>
    <div style="height:84px"></div>
    <div class="fl"><label>Seating</label><div class="sel">${ico(I.seat, 'ico12')}Theatre rows · 168 seats</div>
      <p class="cap">Copied in from the venue's presets: Theatre rows · Cabaret, 12 tables · Concert, standing · None. The copy is this show's — moving chairs never changes the venue or another show.</p></div>
    <div class="note"><p class="cap"><b>What the show starts with.</b> The venue's rig at v5 — 31 house fixtures, 4 universes, 6 riggings, the room and tabs — referenced, not copied. Hired kit, the set and seating are the show's own.</p></div>
    ${callout(3, 'left:-10px; top:238px')}
    ${callout(4, 'left:-10px; top:372px')}
  </div>
  <div class="sf"><span class="spacer"></span><span class="btn out">Cancel</span><span class="btn pri">Create</span></div>
</div>
</div>
</div></div>
${legend([
    [1, '<b>Venues get their own row.</b> A venue is a project of kind <i>Venue</i>: open it to edit the house rig in the views you already have. It never holds cues, Looks or templates. Admins see its draft.'],
    [2, '<b>Every show names its venue and version.</b> Amber when a newer version exists; red-amber when the newer one changes output (addresses, universes, types) — that show opens in Blind.'],
    [3, '<b>A new show picks a venue</b>, latest version by default, or none — a standalone project keeps working exactly as today.'],
    [4, '<b>Seating is the show\'s.</b> The venue keeps named layouts; one is copied in at creation (and <i>Replace from preset…</i> later).'],
    [5, '<b>The show\'s menu gains Update venue and Detach.</b> Detach flattens venue + show into one standalone project — the archive and the rare move elsewhere.'],
  ])}`
  boards['Projects.dc.html'] = { title: 'Projects and venues · the list, a new show', h: H, html: doc('Projects and venues', W, H, body) }
}

/* ─────────────────────────────── Venue ─────────────────────────────── */
{
  const H = 1180
  const r = (name, head, addr, type, key, mount, lantern, draft = '', cls = '') =>
    `<tr class="${cls}"><td class="nm" style="font-weight:600">${name}</td><td class="m dim">${head}</td><td class="m">${addr}</td><td>${type}</td><td class="m dim">${key}</td><td>${mount}</td><td class="dim">${lantern}</td><td>${draft}</td></tr>`
  const body = `
<div style="display:flex; flex:1; min-height:0;">
${rail('patch', { venue: true })}
<div class="main">
${appbar()}
<div class="hdrrow"><span style="color:var(--ven)">${ico(I.hall, 'ico16')}</span><span class="vt">Commemoration Hall</span><span class="vtag">VENUE</span><span class="crumbs" style="font-size:13px">› <b>Patch List</b></span>
  <span class="spacer"></span>
  <span class="own d">${ico(I.pencil, 'ico12')}Draft · 6 changes since v5</span>
  <span class="btn out sm">${ico(I.clock, 'ico12')}History</span>
  <span class="btn pri sm" style="margin-right:430px">${ico(I.upload, 'ico12')}Publish v6…</span></div>
<div class="banner edit"><span style="color:var(--ven)">${ico(I.hall)}</span><span class="bt"><b>Editing the venue.</b> <span>Changes collect in a draft. No show sees them until you publish, and then only when each show takes the update.</span></span></div>
<div class="body">
<div style="flex:1; min-width:0; display:flex; flex-direction:column; padding-right:430px">
  <div class="uchips"><span class="lbl">Universes</span>
    <span class="uchip">U1 <i><b style="width:24%"></b></i></span><span class="uchip">U2 <i><b style="width:12%"></b></i></span>
    <span class="uchip new">U3 <span class="tag new" style="height:14px">new</span><i><b style="width:5%"></b></i></span><span class="uchip">U4 <i><b style="width:1%"></b></i></span></div>
  <div class="toolbar"><span class="seg"><span class="on">All</span><span>1</span><span>2</span><span>3</span><span>4</span></span><span class="field" style="width:200px; color:var(--mfg)">${ico(I.search, 'ico12')}Filter…</span>
    <span class="seg"><span>All</span><span class="on">Changed in draft</span></span><span class="spacer"></span><span class="btn out sm">Groups</span><span class="btn out sm">Columns ▾</span><span class="btn pri sm">${ico(I.plus, 'ico12')}Patch</span></div>
  <table class="tbl">
    <tr><th>Fixture</th><th>Head</th><th>Address</th><th>Type</th><th>Key</th><th>Mount</th><th>Lantern</th><th>Draft</th></tr>
    ${r('SR FACE 19°', 5, '1-005', 'Generic dimmer', 'sr-face', 'ADV2', 'Source Four 19°')}
    ${r('C FACE 19°', 6, '1-006', 'Generic dimmer', 'c-face', 'ADV2', 'Source Four 19°')}
    ${r('LX2 FRES', 10, '1-010', 'Generic dimmer', 'lx2-fres', 'LX2', 'Cantata F', own('', 'Position'))}
    ${r('LX2 C FRES', 21, '1-021', 'Generic dimmer', 'lx2-c-fres', 'LX2', 'Cantata F', own('', 'Media · L201 → L202'))}
    ${r('NON DIM (LX3)', 23, '1-023', 'Generic dimmer', 'non-dim-lx3', 'LX3', '—', own('x', 'Removed'), 'gone')}
    ${r('LX3 BATTEN 1', 31, '3-001', 'Kam Liteobar 252', 'lx3-batten-1', 'LX3', '—', own('x', 'Added'))}
    ${r('LX3 BATTEN 2', 32, '3-012', 'Kam Liteobar 252', 'lx3-batten-2', 'LX3', '—', own('x', 'Added'))}
    ${r('HAZER', '—', '1-512', 'ADJ Fog Fury Jett', 'hazer', 'Free', '—', own('x', 'Address · 1-500 → 1-512'))}
    ${r('house 1', '—', '4-001', 'House light', 'house-1', 'Free', '—')}
    ${r('Revolution SR', 41, '2-001', 'Source 4 Revolution', 'rev-sr', 'LX1', '—')}
  </table>
  <div style="display:grid; grid-template-columns:1.3fr 1fr; gap:16px; padding:16px 12px; position:relative">
    <div class="fr"><div class="fcap">History · Commemoration Hall</div>
      <table class="wtbl">
        <tr><th>Version</th><th>Published</th><th>Note</th><th>Shows on it</th><th></th></tr>
        <tr><td>Draft</td><td style="color:var(--mfg)">editing</td><td>6 changes</td><td>—</td><td><span class="btn ghost sm">Discard</span></td></tr>
        <tr><td>v5</td><td>3 Oct 2026 · Chris</td><td>Added house lights on U4</td><td>TCH 2026</td><td><span class="btn ghost sm">Compare</span></td></tr>
        <tr><td>v4</td><td>12 Aug 2026 · Chris</td><td>LX2 extended to 6 m</td><td>Panto 2027</td><td><span class="btn ghost sm">Compare</span></td></tr>
        <tr><td>v3</td><td>2 Apr 2026 · Chris</td><td>Re-patch: dimmers to 1–24</td><td>Spring Concert 2026</td><td><span class="btn ghost sm">Compare</span></td></tr>
        <tr><td>v1</td><td>28 Mar 2026 · Chris</td><td>Extracted from TCH 2026</td><td style="color:var(--mfg)">—</td><td><span class="btn ghost sm">Compare</span></td></tr>
      </table></div>
    <div class="stack">
      <div class="note"><p class="cap"><b>The rail is the venue's.</b> Patch, Fixtures, Groups, Programmer (to test and focus — <i>Record</i> is off), Stage, Channels, and Project Settings: Regions, Rigging, Sync. Scripts, FX, Looks, Templates, Show, Prompt Book and Busk are hidden.</p></div>
      <div class="note"><p class="cap"><b>Operators</b> can open a venue to look, read-only: no draft, no Publish, no History actions.</p></div>
    </div>
    ${callout(5, 'left:2px; top:8px')}
    ${callout(6, 'right:-8px; top:8px')}
  </div>
  ${callout(1, 'left:12px; top:-30px')}
  ${callout(2, 'left:calc(100% - 470px); top:96px')}
</div>
<div class="vsheet">
  <div class="sh"><span class="t">${ico(I.upload, 'ico16')}Publish Commemoration Hall v6</span><p class="cap">v6 becomes the hall as hung. It is immutable; the draft starts empty again.</p></div>
  <div class="sb">
    <div class="fl"><label>What changed?</label><div class="ta">Rehung LX3 as two LED battens on a new universe 3; re-gelled LX2 centre; moved the hazer to 512.</div></div>
    <div class="stack"><div class="grouph x">${ico(I.warn, 'ico12')}Changes output<span class="ct">4</span></div>
      <div class="chg"><span class="sym p">+</span><span class="tx">Universe 3 · Art-Net 0:3</span></div>
      <div class="chg"><span class="sym p">+</span><span class="tx">LX3 BATTEN 1–2 · 3-001, 3-012</span></div>
      <div class="chg"><span class="sym m">−</span><span class="tx">NON DIM (LX3) · 1-023</span></div>
      <div class="chg"><span class="sym c">~</span><span class="tx">HAZER · 1-500 → 1-512</span></div>
    </div>
    <div class="stack"><div class="grouph">${ico(I.win, 'ico12')}Drawing only<span class="ct">2</span></div>
      <div class="chg"><span class="sym c">~</span><span class="tx">LX2 FRES position<small>x −1.20 → −0.90 m on LX2</small></span></div>
      <div class="chg"><span class="sym c">~</span><span class="tx">LX2 C FRES media<small>gel L201 → L202</small></span></div>
    </div>
    <div class="stack"><div class="grouph">${ico(I.ticket, 'ico12')}Shows on this venue<span class="ct">3</span></div>
      <div class="chg"><span class="tx"><b>TCH 2026</b> · v5<small>Offered v6 when next opened — opens in Blind until updated</small></span><span class="own x">output</span></div>
      <div class="chg"><span class="tx"><b>Panto 2027</b> · v4<small>Already behind; will be offered v6</small></span></div>
      <div class="chg"><span class="tx"><b>Spring Concert 2026</b> · v3<small>Already behind; will be offered v6</small></span></div>
    </div>
    ${callout(3, 'left:-10px; top:120px')}
    ${callout(4, 'left:-10px; top:400px')}
  </div>
  <div class="sf"><span class="btn ghost" style="color:oklch(0.72 0.18 27)">Discard draft</span><span class="spacer"></span><span class="btn out">Cancel</span><span class="btn pri">Publish v6</span></div>
</div>
</div>
</div></div>
${legend([
    [1, '<b>Same views, in venue mode.</b> The header says VENUE and the draft\'s size; the band says nothing reaches a show until it is published and taken.'],
    [2, '<b>Every draft change is marked on its row</b> — Added, Removed, or the facet that changed — red-amber when it changes output.'],
    [3, '<b>Publish splits output from drawing</b>, the same classification a show\'s update uses, so the note writes itself.'],
    [4, '<b>Who is affected</b>: every show on this venue and what it will see next time it opens.'],
    [5, '<b>Versions are immutable.</b> Compare any two; a version a show is pinned to is never pruned.'],
    [6, '<b>Admins only</b> edit and publish. The Programmer stays for testing and focusing; it cannot record into a venue.'],
  ])}`
  boards['Venue.dc.html'] = { title: 'Editing the venue · draft, publish, history', h: H, html: doc('Editing the venue', W, H, body) }
}

/* ─────────────────────────────── Show ─────────────────────────────── */
{
  const H = 1160
  const r = (s, name, head, addr, type, key, mount, gel, changes = '', cls = '') =>
    `<tr class="${cls}"><td>${src(s)}</td><td class="nm" style="font-weight:600">${name}</td><td class="m dim">${head}</td><td class="m">${addr}</td><td>${type}</td><td class="m dim">${key}</td><td>${mount}</td><td>${gel}</td><td>${changes}</td></tr>`
  const plan = `
<svg viewBox="0 0 620 330" style="width:100%; height:auto; display:block">
  <defs><pattern id="hatch" width="6" height="6" patternUnits="userSpaceOnUse"><path d="M0 6 6 0" stroke="oklch(0.6 0.09 190 / 0.35)" stroke-width="1"/></pattern></defs>
  <rect x="10" y="10" width="600" height="310" rx="4" fill="none" stroke="oklch(0.82 0.09 190)" stroke-width="2"/>
  <text x="18" y="26" fill="oklch(0.82 0.09 190)" font-size="10" font-weight="700">ROOM · venue</text>
  <rect x="150" y="18" width="320" height="104" fill="url(#hatch)" stroke="oklch(0.82 0.09 190)" stroke-width="1.5"/>
  <text x="158" y="34" fill="oklch(0.82 0.09 190)" font-size="10">Stage · venue</text>
  <line x1="130" y1="122" x2="490" y2="122" stroke="oklch(0.82 0.09 190)" stroke-width="4"/>
  <text x="495" y="126" fill="oklch(0.82 0.09 190)" font-size="10">Proscenium · venue</text>
  <path d="M160 116 q10 -4 20 0 t20 0 t20 0 t20 0 t20 0 t20 0 t20 0 t20 0 t20 0 t20 0 t20 0 t20 0 t20 0 t20 0 t20 0" fill="none" stroke="oklch(0.82 0.09 190)" stroke-width="1.5" stroke-dasharray="3 2"/>
  <line x1="165" y1="44" x2="455" y2="44" stroke="oklch(0.82 0.09 190)" stroke-width="2"/><text x="460" y="47" fill="oklch(0.82 0.09 190)" font-size="9">LX3</text>
  <line x1="165" y1="70" x2="455" y2="70" stroke="oklch(0.82 0.09 190)" stroke-width="2"/><text x="460" y="73" fill="oklch(0.82 0.09 190)" font-size="9">LX2</text>
  <line x1="165" y1="98" x2="455" y2="98" stroke="oklch(0.82 0.09 190)" stroke-width="2"/><text x="460" y="101" fill="oklch(0.82 0.09 190)" font-size="9">LX1</text>
  <rect x="200" y="24" width="90" height="10" fill="oklch(0.8 0.12 255 / 0.5)" stroke="oklch(0.8 0.12 255)"/><rect x="330" y="24" width="90" height="10" fill="oklch(0.8 0.12 255 / 0.5)" stroke="oklch(0.8 0.12 255)"/>
  <text x="250" y="60" fill="oklch(0.8 0.12 255)" font-size="10" text-anchor="middle">Set · flats</text>
  <rect x="270" y="78" width="80" height="14" rx="2" fill="oklch(0.8 0.12 255 / 0.35)" stroke="oklch(0.8 0.12 255)"/><text x="310" y="88" fill="#dfe6ff" font-size="9" text-anchor="middle">rostrum</text>
  <circle cx="140" cy="60" r="6" fill="none" stroke="oklch(0.8 0.12 255)" stroke-width="2"/><text x="100" y="64" fill="oklch(0.8 0.12 255)" font-size="9">boom SR</text>
  <circle cx="480" cy="60" r="6" fill="none" stroke="oklch(0.8 0.12 255)" stroke-width="2"/><text x="492" y="64" fill="oklch(0.8 0.12 255)" font-size="9">boom SL</text>
  ${Array.from({ length: 7 }, (_, row) => Array.from({ length: 14 }, (_, c) => `<rect x="${150 + c * 23 + (c > 6 ? 14 : 0)}" y="${150 + row * 20}" width="16" height="12" rx="2" fill="oklch(0.8 0.12 255 / 0.28)" stroke="oklch(0.8 0.12 255 / 0.8)"/>`).join('')).join('')}
  <text x="310" y="300" fill="oklch(0.8 0.12 255)" font-size="10" text-anchor="middle">Seating · Theatre rows · this show's (copied from the venue's preset)</text>
  <line x1="40" y1="290" x2="120" y2="290" stroke="oklch(0.82 0.09 190)" stroke-width="2"/><text x="40" y="282" fill="oklch(0.82 0.09 190)" font-size="9">FOH bridge</text>
</svg>`
  const body = `
<div style="display:flex; flex:1; min-height:0;">
${rail('patch')}
<div class="main">
${appbar()}
<div class="hdrrow"><span class="vt">TCH 2026</span><span class="crumbs" style="font-size:13px"><span class="active">active</span> › <b>Patch List</b></span><span class="spacer"></span>
  ${venueChip('v5')}<span class="btn out sm" style="margin-right:430px">${ico(I.hall, 'ico12')}Open venue</span></div>
<div class="body">
<div style="flex:1; min-width:0; display:flex; flex-direction:column; padding-right:430px; position:relative">
  <div class="uchips"><span class="lbl">Groups</span>${own('v', 'FOH', 'hall')}${own('v', 'LX1', 'hall')}${own('v', 'LX2', 'hall')}${own('v', 'House', 'hall')}${own('s', 'Movers', 'ticket')}${own('s', 'Cannon', 'ticket')}</div>
  <div class="toolbar"><span class="seg"><span class="on">All</span><span>${ico(I.hall, 'ico12')}Venue</span><span>${ico(I.ticket, 'ico12')}This show</span><span>Changed</span><span>Hidden</span></span><span class="field" style="width:180px; color:var(--mfg)">${ico(I.search, 'ico12')}Filter…</span><span class="spacer"></span><span class="btn out sm">Groups</span><span class="btn out sm">Columns ▾</span><span class="btn pri sm">${ico(I.plus, 'ico12')}Patch</span></div>
  <table class="tbl">
    <tr><th></th><th>Fixture</th><th>Head</th><th>Address</th><th>Type</th><th>Key</th><th>Mount</th><th>Gel</th><th>This show</th></tr>
    ${r('v', 'SR FACE 19°', 5, '1-005', 'Generic dimmer', 'sr-face', 'ADV2', 'L201')}
    ${r('v', 'C FACE 19°', 6, '1-006', 'Generic dimmer', 'c-face', 'ADV2', 'L201')}
    ${r('v', 'LX1 FRES', 9, '1-009', 'Generic dimmer', 'lx1-fres', '<span class="ovv">Boom SR</span>', 'L201', own('o', 'Position') + ' ' + own('o', 'Focus'))}
    ${r('v', 'LX2 C FRES', 21, '1-021', 'Generic dimmer', 'lx2-c-fres', 'LX2', '<span class="ovv">L201</span>', own('o', 'Media') + ' ' + own('d', 'venue changed', 'warn'), 'pick')}
    ${r('v', 'ADV CANS', 12, '1-012', 'Generic dimmer', 'adv-cans', 'ADV1', '—', own('h', 'Hidden in this show', 'eyeOff'), 'hid')}
    ${r('s', 'MOVER 1', 101, '1-101', 'Gear4Music Orbit-70', 'mover-1', 'LX3', '—', own('s', 'Added', 'ticket'))}
    ${r('s', 'MAC 250 SR', 111, '1-140', 'Martin MAC 250', 'mac-sr', 'Boom SR', '—', own('s', 'Added', 'ticket'))}
    ${r('s', 'TWIN SHOT', '—', '5-001', 'Equinox Twin Shot MKII', 'cannon', 'Free', '—', own('s', 'Added', 'ticket'))}
  </table>
  <div style="display:grid; grid-template-columns:1.25fr 1fr; gap:16px; padding:16px 12px; position:relative">
    <div class="fr"><div class="fcap">Stage · Plan · who owns what</div>${plan}</div>
    <div class="stack" style="gap:10px">
      <div style="display:flex; gap:6px; flex-wrap:wrap">${own('v', 'Venue v5', 'hall')}${own('s', 'This show', 'ticket')}${own('o', 'Overridden here')}${own('d', 'Venue changed under it', 'warn')}</div>
      <div class="note"><p class="cap"><b>The venue's:</b> the room, the stage and its deck regions, the proscenium and house tabs, LX1–3 and the FOH bridge, the house patch and its universes, camera viewpoints.</p></div>
      <div class="note"><p class="cap"><b>This show's:</b> the set, <b>seating</b> and the seat viewpoints in it, booms and stands, hired fixtures, show groups — and everything that was already a show's: cues, Looks, templates, busk, prompt book.</p></div>
      <div class="note"><p class="cap"><b>Keys are one namespace.</b> A show can't patch a fixture called <span class="mono">c-face</span> while the venue has one; to replace a house unit, hide it and add your own.</p></div>
    </div>
    ${callout(7, 'left:2px; top:8px')}
  </div>
  ${callout(1, 'left:4px; top:84px')}
  ${callout(2, 'left:845px; top:178px')}
  ${callout(3, 'left:900px; top:214px')}
  ${callout(4, 'left:870px; top:250px')}
  ${callout(5, 'left:820px; top:286px')}
</div>
<div class="vsheet">
  <div class="sh"><span class="t">Edit Fixture</span><div style="display:flex; gap:6px; align-items:center">${venueChip('v5')}<span class="cap">house fixture</span></div></div>
  <div class="sb" style="gap:10px">
    <div class="facet"><div class="fh">${ico(I.lock, 'ico12')}<b>Identity</b><span class="btn ghost sm">${ico(I.hall, 'ico12')}Edit in venue</span></div>
      <div class="kv"><span class="k">Name</span><span>LX2 C FRES</span><span class="k">Key</span><span class="mono">lx2-c-fres</span><span class="k">Type</span><span>Generic dimmer · head 21</span></div>
      <p class="cap">The venue's. A show can hide this fixture, not rename or retype it.</p></div>
    <div class="facet"><div class="fh"><b>Address</b><span class="seg vs"><span class="on v">Venue</span><span>This show</span></span></div><div class="fv"><span class="mono">1-021</span> · house patch</div></div>
    <div class="facet"><div class="fh"><b>Position</b><span class="seg vs"><span class="on v">Venue</span><span>This show</span></span></div><div class="fv">LX2 · <span class="mono">x 0.00 m</span> · yaw 0°</div></div>
    <div class="facet"><div class="fh"><b>Focus</b><span class="seg vs"><span class="on v">Venue</span><span>This show</span></span></div><div class="fv">Cantata F · zoom 32° · <span class="mono">sharp</span></div></div>
    <div class="facet ovr"><div class="fh"><b>Media</b><span class="seg vs"><span class="on v" style="background:none;color:var(--mfg)">Venue</span><span class="on s">This show</span></span></div>
      <div class="fv">Gel <span class="mono">L201</span> Full CT Blue <span class="sw" style="background:#7fa8ff"></span></div>
      <div class="note warn"><p class="cap"><b>The venue changed this under you.</b> v5 re-gelled it L200 → <span class="mono">L202</span>. Yours is kept.</p>
        <div style="display:flex; gap:6px; margin-top:6px"><span class="btn out sm">Keep mine</span><span class="btn out sm">${ico(I.reset, 'ico12')}Take venue's L202</span></div></div>
    </div>
    ${callout(6, 'left:-10px; top:14px')}
  </div>
  <div class="sf"><span class="btn ghost">${ico(I.eyeOff, 'ico12')}Hide in this show</span><span class="spacer"></span><span class="btn pri">Done</span></div>
</div>
</div>
</div></div>
${legend([
    [1, '<b>Every row says whose it is.</b> Hall icon: the venue\'s, at the pinned version. Ticket: this show\'s. Filter by either, by changed or by hidden.'],
    [2, '<b>An override is a whole facet</b> — Address, Position, Focus or Media — and the row names which. Shutters without the position they were cut against mean nothing.'],
    [3, '<b>Drift</b>: an update moved the venue under an override. Amber until you keep yours or take the venue\'s.'],
    [4, '<b>Hidden</b>: a house unit this show doesn\'t use. Rows that name it stay, marked <i>Missing fixture</i> as today.'],
    [5, '<b>Additions</b> are ordinary patches owned by the show — hires, the cannon, booms.'],
    [6, '<b>The fixture sheet</b> gains a Venue | This show switch per facet; identity is read-only with a way into the venue.'],
    [7, '<b>Seating is the show\'s</b>, with the set and booms; the room, stage, pros, tabs and bars are the venue\'s.'],
  ])}`
  boards['Show.dc.html'] = { title: 'A show on a venue · inherited, overridden, added, hidden', h: H, html: doc('A show on a venue', W, H, body) }
}

/* ─────────────────────────────── Update ─────────────────────────────── */
{
  const H = 1090
  const body = `
<div style="display:flex; flex:1; min-height:0;">
${rail('show')}
<div class="main">
${appbar(`<span class="blind">${ico(I.eyeOff)}Blind · venue</span>`)}
<div class="banner hold">${ico(I.eyeOff, 'ico16')}<span class="bt"><b>Output held — this show is in Blind.</b> <span>It uses Commemoration Hall v3; the hall has been re-patched since (v5 is as hung). Its addresses would drive whatever is hung there now.</span></span>
  <span class="btn pri sm">${ico(I.diff, 'ico12')}Review update</span><span class="btn out sm">Run against v3…</span></div>
<div class="body" style="flex-direction:column">
  <div style="padding:16px 20px; display:grid; grid-template-columns:1fr 1fr; gap:16px">
    <div class="fr"><div class="fcap">If only drawing changed — a banner, output live</div>
      <div class="banner info" style="border-bottom:0">${ico(I.hall, 'ico16')}<span class="bt"><b>Commemoration Hall has moved on.</b> <span>Panto 2027 uses v4; v5 is the hall as hung — 2 drawing changes, nothing that changes output.</span></span><span class="btn out sm">Review update</span><span class="btn ghost sm">Later</span></div></div>
    <div class="fr"><div class="fcap">After <i>Run against v3…</i> — this session only</div>
      <div class="banner hold" style="border-bottom:0; background:oklch(0.3 0.06 35 / 0.35); border-color:var(--outbd)">${ico(I.warn, 'ico16')}<span class="bt"><b>Running against v3.</b> <span>Live output uses the old house patch. v5's trigger and command channels stay guarded.</span></span><span class="btn out sm">Review update</span></div></div>
  </div>
  <div style="position:relative; flex:1">
    <div class="scrim"></div>
    <div class="dlg" style="left:70px; right:70px; top:10px; bottom:24px">
      <div class="dh"><span class="t">Update Spring Concert 2026 to Commemoration Hall v5</span><p class="cap">From v3 · “Re-patch: dimmers to 1–24” → v5 · “Added house lights on U4”. Nothing changes until you press Update.</p></div>
      <div class="db" style="grid-template-columns:1fr 1.25fr">
        <div class="stack" style="gap:12px">
          <span class="h2">What changed in the hall</span>
          <div class="stack"><div class="grouph x">${ico(I.warn, 'ico12')}Changes output<span class="ct">4</span></div>
            <div class="chg"><span class="sym p">+</span><span class="tx">Universe 4 · house lights<small>house 1–3 · 4-001 to 4-003</small></span></div>
            <div class="chg"><span class="sym c">~</span><span class="tx">LX1 FRES · 1-001 → 1-009</span></div>
            <div class="chg"><span class="sym c">~</span><span class="tx">HAZER · 1-500 → 1-512</span></div>
            <div class="chg"><span class="sym m">−</span><span class="tx">FOS 7 · 1-007<small>Front-of-house spot, struck in v4</small></span></div>
          </div>
          <div class="stack"><div class="grouph">${ico(I.win, 'ico12')}Drawing only<span class="ct">3</span></div>
            <div class="chg"><span class="sym c">~</span><span class="tx">LX2 extended to 6 m</span></div>
            <div class="chg"><span class="sym c">~</span><span class="tx">LX1 FRES moved +0.20 m on LX1</span></div>
            <div class="chg"><span class="sym c">~</span><span class="tx">LX2 C FRES · gel L200 → L202</span></div>
          </div>
        </div>
        <div class="stack" style="gap:12px">
          <span class="h2">What happens to this show</span>
          <div class="chg" style="border-color:oklch(0.5 0.12 145 / 0.5)"><span class="sym p">${ico(I.check, 'ico12')}</span><span class="tx"><b>14 carry over unchanged</b><small>Overrides, hides and additions on records v5 didn't touch.</small></span><span class="btn ghost sm">Show</span></div>
          <div class="stack"><div class="grouph x">${ico(I.warn, 'ico12')}Needs a decision<span class="ct">1</span></div>
            <div class="chg" style="border-color:var(--outbd); flex-wrap:wrap"><span class="sym m">!</span><span class="tx"><b>Your SMOKE 2 at 1-512 clashes with v5's HAZER at 1-512.</b><small>Two fixtures can't share an address.</small></span>
              <span class="seg" style="margin-left:22px"><span class="on">Re-address mine → 1-480</span><span>Hide the venue's</span><span>Remove mine</span></span></div>
          </div>
          <div class="stack"><div class="grouph d">${ico(I.warn, 'ico12')}Kept — the venue changed underneath<span class="ct">2</span></div>
            <div class="chg"><span class="sym c">~</span><span class="tx">LX1 FRES · Position<small>v5 moved it on LX1; your boom position is kept</small></span><span class="btn ghost sm">Take venue's</span></div>
            <div class="chg"><span class="sym c">~</span><span class="tx">LX2 C FRES · Media<small>v5 re-gelled it L202; your L201 is kept</small></span><span class="btn ghost sm">Take venue's</span></div>
          </div>
          <div class="stack"><div class="grouph">${ico(I.x, 'ico12')}Dropped<span class="ct">1</span></div>
            <div class="chg"><span class="sym m">−</span><span class="tx">Your Focus override on FOS 7<small>FOS 7 is gone in v5, so the override has nothing to apply to.</small></span></div>
          </div>
          <div class="stack"><div class="grouph">${ico(I.tag, 'ico12')}Will point at nothing<span class="ct">5</span></div>
            <div class="chg"><span class="sym c">?</span><span class="tx"><span class="mono">fos-7</span> is named by Q12, Q14, Q31, Look <i>Warm front</i> and template <i>Fronts 50%</i><small>The rows stay, marked <i>Missing fixture</i> as a deleted fixture's are today. Re-point or remove them later.</small></span><span class="btn ghost sm">List</span></div>
          </div>
        </div>
      </div>
      <div class="df"><span class="cap">The update replays this show's layer onto v5. Output returns when it completes.</span><span class="spacer"></span><span class="btn out">Stay on v3</span><span class="btn pri">${ico(I.up, 'ico12')}Update to v5</span></div>
    </div>
    ${callout(3, 'left:60px; top:2px; z-index:9')}
    ${callout(4, 'left:calc(44% + 26px); top:150px; z-index:9')}
    ${callout(5, 'right:60px; top:330px; z-index:9')}
    ${callout(6, 'right:60px; top:540px; z-index:9')}
  </div>
  ${callout(2, 'left:20px; top:8px')}
</div>
${callout(1, 'right:330px; top:14px')}
</div></div>
${legend([
    [1, '<b>Output-affecting drift opens in Blind</b> — the same Blind as the programmer\'s, with <i>venue</i> as its reason. Drawing-only drift is just a banner and output is live.'],
    [2, '<b>Run against v3</b> lifts the hold for this session only, behind a confirm that lists the addresses that moved. Whatever v3 sends, v5\'s trigger and command channels stay idle and guarded.'],
    [3, '<b>The review</b> shows the hall\'s changes (v3 → v5) beside what they do to this show, before anything is written.'],
    [4, '<b>Clashes block the update</b> until each has a decision. Everything else has a default.'],
    [5, '<b>Kept and dropped</b>: overrides survive the venue changing underneath (drift); an override on a record the venue removed has nothing to apply to.'],
    [6, '<b>Dead references don\'t fail.</b> Cue, Look and template rows naming a removed key stay and are marked, by the health check that already exists.'],
  ])}`
  boards['Update.dc.html'] = { title: 'Opening a show that\'s behind · Blind, review, update', h: H, html: doc('Updating a show', W, H, body) }
}

/* ─────────────────────────────── Model ─────────────────────────────── */
{
  const H = 1330
  const vx = (i) => 70 + i * 150
  const timeline = `
<svg viewBox="0 0 960 230" style="width:100%; height:auto; display:block">
  <line x1="40" y1="60" x2="${vx(5) + 20}" y2="60" stroke="oklch(0.6 0.09 190)" stroke-width="2"/>
  ${['v1', 'v2', 'v3', 'v4', 'v5'].map((v, i) => `<circle cx="${vx(i)}" cy="60" r="13" fill="oklch(0.3 0.05 190)" stroke="oklch(0.82 0.09 190)" stroke-width="2"/><text x="${vx(i)}" y="64" fill="oklch(0.9 0.05 190)" font-size="11" font-weight="700" text-anchor="middle">${v}</text>`).join('')}
  <circle cx="${vx(5)}" cy="60" r="13" fill="none" stroke="oklch(0.82 0.13 80)" stroke-width="2" stroke-dasharray="3 3"/><text x="${vx(5)}" y="64" fill="oklch(0.86 0.13 80)" font-size="9" font-weight="700" text-anchor="middle">draft</text>
  ${[['28 Mar', 'Extracted from TCH 2026'], ['1 Apr', 'Tabs re-measured'], ['2 Apr', 'Re-patch 1–24'], ['12 Aug', 'LX2 to 6 m'], ['3 Oct', 'House lights U4'], ['', '6 changes · admins']].map(([d, n], i) => `<text x="${vx(i)}" y="30" fill="#9aa" font-size="10" text-anchor="middle">${d}</text><text x="${vx(i)}" y="94" fill="#c8ccd4" font-size="10" text-anchor="middle">${n}</text>`).join('')}
  ${[['Spring Concert 2026', 2, 'oklch(0.8 0.13 35)', 'behind · output'], ['Panto 2027', 3, 'oklch(0.86 0.13 80)', 'behind · drawing'], ['TCH 2026', 4, 'oklch(0.8 0.12 255)', 'current']].map(([n, v, c, s], k) => {
    const x = vx(v) - 70, y = 150
    return `<rect x="${x}" y="${y}" width="140" height="44" rx="8" fill="oklch(0.21 0.006 285.885)" stroke="${c}"/><text x="${x + 70}" y="${y + 18}" fill="#eee" font-size="11" font-weight="600" text-anchor="middle">${n}</text><text x="${x + 70}" y="${y + 34}" fill="${c}" font-size="10" text-anchor="middle">pins v${v + 1} · ${s}</text><line x1="${vx(v)}" y1="${y}" x2="${vx(v)}" y2="76" stroke="${c}" stroke-width="1.5" stroke-dasharray="4 3"/>`
  }).join('')}
  <text x="40" y="222" fill="#9aa" font-size="10">A show moves along the line only by Update. Publishing never moves a show.</text>
</svg>`
  const body = `
<div class="board" style="flex:1">
  <div style="display:flex; align-items:baseline; gap:12px"><span class="h1">Venues and shows · the model</span><span class="cap">Decisions from Chris, 2026-10-10. The boards before this one draw them.</span></div>
  <div style="display:grid; grid-template-columns:1.15fr 1fr; gap:20px">
    <div class="stack" style="gap:12px">
      <div class="fr"><div class="fcap">Versions and pins</div><div style="padding:10px 12px">${timeline}</div></div>
      <div class="fr"><div class="fcap">The show's rig = venue at its pinned version ⊕ the show's layer</div>
        <div style="padding:10px 12px; display:grid; grid-template-columns:repeat(3,1fr); gap:10px">
          <div class="note"><p class="cap">${own('s', 'Additions', 'plus')}<br>Patches, riggings, elements, groups and viewpoints the show owns — hires, booms, the set, seating.</p></div>
          <div class="note"><p class="cap">${own('o', 'Facet overrides')}<br>One row per (venue record uuid, facet): Address, Position, Focus, Media. A whole facet or nothing.</p></div>
          <div class="note"><p class="cap">${own('h', 'Hides', 'eyeOff')}<br>A venue fixture, rigging or element this show doesn't use. The venue keeps it.</p></div>
        </div></div>
      <div class="fr"><div class="fcap">What's whose</div>
        <table class="wtbl">
          <tr><th>Record</th><th>Owner</th><th>Notes</th></tr>
          <tr><td>Room, proscenium, tabs, masking</td><td>${own('v', 'Venue')}</td><td>Scene elements, layer VENUE. A show may hide one.</td></tr>
          <tr><td>Stage regions (the deck)</td><td>${own('v', 'Venue')} ${own('s', '+ show')}</td><td>A thrust or extension is a show addition.</td></tr>
          <tr><td>Stage bounds</td><td>${own('v', 'Venue')}</td><td>Moves off <span class="mono">projects</span> onto the venue.</td></tr>
          <tr><td>Riggings</td><td>${own('v', 'Venue')} ${own('s', '+ show')}</td><td>Bars and bridge the venue's; booms and stands the show's.</td></tr>
          <tr><td>Universes</td><td>${own('v', 'Venue')} ${own('s', '+ show')}</td><td>Addresses stay machine-local overrides, keyed by uuid as today.</td></tr>
          <tr><td>House patch</td><td>${own('v', 'Venue')} ${own('o', 'facets')}</td><td>Identity is the venue's; Address, Position, Focus, Media overridable.</td></tr>
          <tr><td>Groups</td><td>${own('v', 'Venue')} ${own('s', '+ show')}</td><td>A show group may hold venue fixtures; a venue group never a show's.</td></tr>
          <tr><td>Viewpoints</td><td>${own('v', 'cameras')} ${own('s', 'seats')}</td><td>A seat view names its seating, which is the show's.</td></tr>
          <tr><td>Seating</td><td>${own('s', 'Show')}</td><td>Copied from a venue preset; never a reference.</td></tr>
          <tr><td>Set, scenery, cues, Looks, templates, busk, prompt book, speed masters, scripts, FX</td><td>${own('s', 'Show')}</td><td>Unchanged.</td></tr>
        </table></div>
    </div>
    <div class="stack" style="gap:12px">
      <div class="fr"><div class="fcap">Decisions</div><div class="dec" style="padding:12px">
        <span class="dn">D1</span><span><b>A venue is a project of kind VENUE.</b> A show references one — venue uuid + version — or none. Every existing table keeps its project FK; extracting a venue moves rows between projects with their uuids.</span>
        <span class="dn">D2</span><span><b>Pinned, not live.</b> A venue change reaches a show only through Update.</span>
        <span class="dn">D3</span><span><b>Explicit publish</b> from one draft, with a note. Versions are immutable. Admins only.</span>
        <span class="dn">D4</span><span><b>Overrides are per facet:</b> Address, Position, Focus, Media. Identity (name, key, type, head) is the venue's.</span>
        <span class="dn">D5</span><span><b>The show layer</b> is additions, facet overrides and hides, over one key namespace.</span>
        <span class="dn">D6</span><span><b>Seating is the show's.</b> The venue keeps presets, copied in at creation or by <i>Replace from preset</i>.</span>
        <span class="dn">D7</span><span><b>Output-affecting drift opens in Blind</b>; drawing-only drift is a banner. <i>Run against vN</i> is per session; the installed version's trigger and command channels stay guarded.</span>
        <span class="dn">D8</span><span><b>A venue syncs as its own repo.</b> A version is a commit tagged <span class="mono">v5</span>; a show's <span class="mono">project.json</span> carries <span class="mono">venueUuid</span> + <span class="mono">venueVersion</span>, and importing one whose venue is absent asks for the venue repo.</span>
        <span class="dn">D9</span><span><b>No touring tool.</b> Detach flattens venue ⊕ show into a standalone project.</span>
      </div></div>
      <div class="fr"><div class="fcap">Stored · wire · the prerequisite</div><div class="kvt" style="padding:12px">
        <span class="k">projects</span><span><span class="mono">kind</span> (SHOW | VENUE), <span class="mono">venue_uuid</span>, <span class="mono">venue_version</span>; stage bounds read from the venue.</span>
        <span class="k">venue_versions</span><span>venue, number, note, published at/by, and a frozen read-only snapshot project holding that version's rows. DB uuids aren't unique-indexed, so snapshots share them.</span>
        <span class="k">show_overrides · show_hides</span><span>Portable with the show: record table + uuid, facet, value. Canonical JSON; <span class="mono">formatVersion</span> 24.</span>
        <span class="k">uuid links first</span><span>Group members, busk rig tiles, cue events and scenery rows point at patches and elements by row id. They must point by uuid so a show can change version without rewriting its rows.</span>
        <span class="k">Migrations</span><span>Additive columns and tables only; the extract step moves rows. If a second install exists by then, the migration seam comes back first.</span>
      </div></div>
      <div class="fr"><div class="fcap">Sessions</div><div class="dec" style="padding:12px">
        <span class="dn">S1</span><span><b>uuid links</b> for group members, rig tiles, cue events, scenery. No behaviour change.</span>
        <span class="dn">S2</span><span><b>Venue kind, versions, publish</b> and the frozen snapshot; <i>Extract a venue</i> (TCH 2026 → Commemoration Hall v1).</span>
        <span class="dn">S3</span><span><b>The effective rig</b>: loader merges venue vN ⊕ show; additions, hides, key namespace; the Patch list's source column.</span>
        <span class="dn">S4</span><span><b>Facet overrides</b>, the fixture sheet's switches, drift.</span>
        <span class="dn">S5</span><span><b>Behind</b>: banners, the Blind hold, the update review, <i>Run against vN</i>.</span>
        <span class="dn">S6</span><span><b>Projects page</b>: venues, a new show with seating presets, Detach.</span>
        <span class="dn">S7</span><span><b>Sync</b>: the venue repo, tags, a show import resolving its venue.</span>
      </div></div>
    </div>
  </div>
  <div style="display:grid; grid-template-columns:1fr 1fr; gap:20px">
    <div class="stack"><span class="h2">Considered and declined</span>
      <div class="note no"><p class="cap"><b>Live inheritance.</b> A venue fix would reach every show at once, including archived ones whose addresses then drive the wrong kit.</p></div>
      <div class="note no"><p class="cap"><b>A version per save.</b> The list fills with noise and every show is always behind.</p></div>
      <div class="note no"><p class="cap"><b>The venue copied into each show's repo.</b> Self-contained, but two sources of truth for one hall.</p></div>
      <div class="note no"><p class="cap"><b>Per-field overrides.</b> A shutter cut without the position it was cut against is meaningless; facets keep related fields together.</p></div>
      <div class="note no"><p class="cap"><b>Separate venue tables</b> re-pointed from project to venue: a non-additive change to every table, with no migrations.</p></div>
    </div>
    <div class="stack"><span class="h2">Left open by the boards</span>
      <div class="note warn"><p class="cap"><b>Q1 · What does the hall own?</b> The split drawn here (dimmers, Revolutions, hazer and house lights the venue's; movers, MACs and the cannon TCH 2026's) is illustrative. Extract needs the real list.</p></div>
      <div class="note warn"><p class="cap"><b>Q2 · Programmer in a venue.</b> Drawn as available for testing with Record off. Or no programmer at all?</p></div>
      <div class="note warn"><p class="cap"><b>Q3 · Venue groups.</b> Drawn as allowed (FOH, LX1, House). Or groups always the show's?</p></div>
      <div class="note warn"><p class="cap"><b>Q4 · Do the facets fit?</b> Address · Position (rigging, pose, extra placements) · Focus (lantern, zoom, shutters, iris, softness) · Media (gel, gobos).</p></div>
    </div>
  </div>
</div>`
  boards['Model.dc.html'] = { title: 'The model · versions, ownership, decisions, sessions', h: H, html: doc('Venue model', W, H, body) }
}

const layout = [
  ['Projects.dc.html', 0, 0], ['Venue.dc.html', 1520, 0],
  ['Show.dc.html', 0, 1260], ['Update.dc.html', 1520, 1260],
  ['Model.dc.html', 0, 2500],
]
for (const [f] of layout) writeFileSync(`${OUT}/${f}`, boards[f].html)
writeFileSync(`${OUT}/canvas.json`, JSON.stringify({
  v: 3,
  attachments: {},
  boards: Object.fromEntries(layout.map(([f, x, y]) => [f, { h: boards[f].h, title: boards[f].title, w: W, x, y }])),
}, null, 2) + '\n')

// The one-page copy published as the live artifact: each board in its own frame, scaled to fit.
const esc = (h) => h.replace(/&/g, '&amp;').replace(/"/g, '&quot;')
const SECTIONS = [
  ['projects', 'Projects.dc.html', 'Projects and venues', 'Venues get their own row; every show names its venue and version; a new show picks a venue and a seating preset.'],
  ['venue', 'Venue.dc.html', 'Editing the venue', 'Admins edit the house rig in a draft and publish an immutable version with a note. Publish shows who it will affect.'],
  ['show', 'Show.dc.html', 'A show on a venue', 'The Patch list says whose each row is. A show overrides whole facets, hides house units and adds its own kit. Seating and the set are the show\'s.'],
  ['update', 'Update.dc.html', 'Opening a show that\'s behind', 'Output-affecting drift opens in Blind. The update review shows the hall\'s changes beside what they do to this show.'],
  ['model', 'Model.dc.html', 'The model', 'Versions and pins, what\'s whose, the decisions, the storage and the seven sessions.'],
]
const combined = `<title>Venues and Shows</title>
<meta name="color-scheme" content="dark light">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;500;600;700&family=IBM+Plex+Mono:wght@500&display=swap">
<style>
  /* Layout: one column of boards, each scaled to the column, behind a sticky board index. */
  :root { --bg:#0e0f13; --panel:#16181e; --fg:#eceef3; --muted:#9097a6; --line:#2a2d36; --venue:#6fd3cf; --show:#8fb3ff; --amber:#f4c15d;
    --sans:"IBM Plex Sans", ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif; --mono:"IBM Plex Mono", ui-monospace, Menlo, monospace; color-scheme:dark; }
  @media (prefers-color-scheme: light) { :root:not([data-theme="dark"]) { --bg:#f3f4f7; --panel:#ffffff; --fg:#16181e; --muted:#5b6270; --line:#d9dce3; --venue:#127a76; --show:#2f5fd0; --amber:#9a6a00; color-scheme:light; } }
  :root[data-theme="light"] { --bg:#f3f4f7; --panel:#ffffff; --fg:#16181e; --muted:#5b6270; --line:#d9dce3; --venue:#127a76; --show:#2f5fd0; --amber:#9a6a00; color-scheme:light; }
  body { background:var(--bg); color:var(--fg); font-family:var(--sans); line-height:1.5; }
  .wrap { max-width:1480px; margin:0 auto; padding-inline:20px; padding-block:28px 64px; display:flex; flex-direction:column; gap:28px; }
  header { display:flex; flex-direction:column; gap:10px; max-width:72ch; }
  .eyebrow { font:500 12px var(--mono); letter-spacing:0.06em; text-transform:uppercase; color:var(--muted); display:flex; gap:10px; flex-wrap:wrap; }
  .eyebrow b { color:var(--amber); font-weight:500; }
  h1 { margin:0; font-size:clamp(28px, 4vw, 40px); line-height:1.1; font-weight:700; letter-spacing:-0.015em; text-wrap:balance; }
  h1 .v { color:var(--venue); } h1 .s { color:var(--show); }
  header p { margin:0; color:var(--muted); font-size:15px; }
  .asks { display:grid; grid-template-columns:repeat(auto-fit, minmax(260px, 1fr)); gap:12px; }
  .ask { background:var(--panel); border:1px solid var(--line); border-radius:10px; padding:12px 14px; font-size:13.5px; min-width:0; }
  .ask b { display:block; font-size:12px; font-family:var(--mono); font-weight:500; color:var(--amber); margin-bottom:2px; }
  nav { position:sticky; top:env(safe-area-inset-top, 0px); z-index:2; background:var(--bg); border-bottom:1px solid var(--line); padding-block:10px; display:flex; gap:8px; flex-wrap:wrap; }
  nav a { font-size:13px; font-weight:500; color:var(--fg); text-decoration:none; border:1px solid var(--line); border-radius:999px; padding:4px 12px; background:var(--panel); }
  nav a:hover, nav a:focus-visible { border-color:var(--venue); outline:none; }
  section { display:flex; flex-direction:column; gap:10px; scroll-margin-top:64px; }
  section h2 { margin:0; font-size:20px; font-weight:600; text-wrap:balance; }
  section h2 small { font:500 12px var(--mono); color:var(--muted); margin-left:8px; }
  section p { margin:0; color:var(--muted); font-size:14px; max-width:80ch; }
  .board { position:relative; width:100%; border:1px solid var(--line); border-radius:10px; overflow:hidden; background:#0b0c10; }
  .board iframe { position:absolute; left:0; top:0; width:1440px; border:0; transform-origin:0 0; display:block; }
  footer { color:var(--muted); font-size:13px; border-top:1px solid var(--line); padding-top:14px; }
  footer code { font-family:var(--mono); font-size:12px; }
</style>
<div class="wrap">
  <header>
    <div class="eyebrow"><span>lighting7 · design for sign-off</span><b>not yet a plan</b><span>2026-10-10</span></div>
    <h1>Separating the <span class="v">venue</span> from the <span class="s">show</span></h1>
    <p>A venue is the hall as hung: room, tabs, bars, house patch, universes. It is versioned; a show pins one version and layers its own kit, overrides and hides on top. Seating is the show's. Five boards show how it looks and works, from the Projects page to opening a show the hall has moved on from.</p>
  </header>
  <div class="asks">
    <div class="ask"><b>Q1 · What does the hall own?</b>The boards treat the dimmers, Revolutions, hazer and house lights as the hall's, and the movers, MACs and cannon as TCH 2026's. That split is only an illustration.</div>
    <div class="ask"><b>Q2 · Programmer in a venue</b>Drawn as available for testing and focusing, with Record off. Or no programmer at all?</div>
    <div class="ask"><b>Q3 · Venue groups</b>Drawn as allowed (FOH, LX1, House). Or are groups always the show's?</div>
    <div class="ask"><b>Q4 · The four facets</b>Address · Position · Focus · Media. Do they cover what a show changes?</div>
  </div>
  <nav aria-label="Boards">${SECTIONS.map(([id, , t]) => `<a href="#${id}">${t}</a>`).join('')}</nav>
  ${SECTIONS.map(([id, f, t, d]) => `<section id="${id}"><h2>${t}<small>${f}</small></h2><p>${d}</p>
    <div class="board" data-h="${boards[f].h}"><iframe title="${t}" height="${boards[f].h}" loading="lazy" srcdoc="${esc(boards[f].html)}"></iframe></div></section>`).join('\n  ')}
  <footer>Generated from <code>docs/plans/venue-design/gen.mjs</code>. The board files in that folder are the authority; this page is a copy.</footer>
</div>
<script>
  const fit = () => document.querySelectorAll('.board').forEach((b) => {
    const k = Math.min(1, b.clientWidth / 1440)
    b.style.height = (Number(b.dataset.h) * k) + 'px'
    b.querySelector('iframe').style.transform = 'scale(' + k + ')'
  })
  fit()
  try { new ResizeObserver(fit).observe(document.querySelector('.wrap')) } catch (e) { addEventListener('resize', fit) }
</script>
`
writeFileSync(`${OUT}/venue.html`, combined)
console.log('ok')
