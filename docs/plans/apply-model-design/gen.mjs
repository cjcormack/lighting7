// Generates the apply-model boards. Each board is one dark artboard that renders standalone;
// `node gen.mjs` rewrites all three. The stylesheet is the show-cue-editing boards'
// (../show-cue-editing-design/gen.mjs), itself the busk-chrome vocabulary, restated here with the
// popup's own classes appended, so a change there cannot move these.
import { writeFileSync } from 'node:fs'

const OUT = new URL('.', import.meta.url).pathname

const CSS = `
    body { margin:0; }
    .app { --fg:oklch(0.985 0 0); --card:oklch(0.21 0.006 285.885); --muted:oklch(0.274 0.006 286.033);
      --mfg:oklch(0.705 0.015 286.067); --bd:oklch(0.274 0.006 286.033); --pri:oklch(0.623 0.214 259.815); --bg:oklch(0.141 0.005 285.823);
      --page:oklch(0.17 0.005 286); --amber:oklch(0.82 0.16 80); --green:#4ade80; --blue:#60a5fa;
      --own:#7aa2ff; --look:#5cd1c1; --fx:#b98cff; --stale:#ff8f78;
      font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", "Helvetica Neue", sans-serif;
      -webkit-font-smoothing:antialiased; background:var(--bg); color:var(--fg); box-sizing:border-box; font-size:13px; line-height:1.35;
      width:1440px; display:flex; flex-direction:column; }
    .app * { box-sizing:border-box; }
    .mono { font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace; font-variant-numeric:tabular-nums; }
    .dim { color:var(--mfg); } .sp { flex:1; }
    .lbl { font-size:9px; font-weight:700; text-transform:uppercase; letter-spacing:0.08em; color:var(--mfg); }
    .row { display:flex; align-items:center; gap:8px; }
    .btn { display:inline-flex; align-items:center; gap:5px; height:28px; padding:0 10px; border:1px solid var(--bd); border-radius:7px; background:var(--card); font-size:12.5px; white-space:nowrap; color:var(--fg); }
    .btn.sm { height:24px; padding:0 8px; font-size:11.5px; border-radius:6px; }
    .btn.pri { background:var(--pri); border-color:var(--pri); color:#fff; }
    .btn.blind { background:oklch(0.3 0.06 80); border-color:oklch(0.6 0.12 80 / 0.7); color:var(--amber); }
    .btn.red { color:#ff7b7b; } .btn.ghost { background:transparent; border-color:transparent; }
    .btn.ask { opacity:0.5; } .btn.stale { color:var(--stale); border-color:oklch(0.65 0.14 30 / 0.6); }
    .btn .chev { border-left:1px solid rgba(255,255,255,0.28); padding-left:6px; margin-left:3px; }
    .sep { width:1px; height:18px; background:var(--bd); }
    .chip { display:inline-flex; align-items:center; gap:4px; height:20px; padding:0 7px; border-radius:999px; border:1px solid var(--bd); font-size:10.5px; color:var(--mfg); white-space:nowrap; }
    .chip.shared { color:var(--own); border-color:oklch(0.6 0.12 260 / 0.55); }
    .chip.stale { color:var(--stale); border-color:oklch(0.65 0.14 30 / 0.6); background:oklch(0.3 0.06 30 / 0.3); }
    .chip.amber { color:var(--amber); border-color:oklch(0.6 0.12 80 / 0.6); background:oklch(0.3 0.06 80 / 0.3); }
    /* chrome */
    .top { height:48px; background:#2563eb; display:flex; align-items:center; padding:0 16px; gap:10px; font-weight:600; font-size:16px; }
    .shell { display:flex; flex:1; min-height:0; }
    .rail { width:64px; background:var(--bg); border-right:1px solid var(--bd); display:flex; flex-direction:column; align-items:center; gap:14px; padding-top:14px; }
    .rail i { width:22px; height:22px; border-radius:6px; background:var(--muted); display:block; }
    .rail i.on { background:oklch(0.35 0.08 260); }
    .main { flex:1; min-width:0; display:flex; flex-direction:column; background:var(--page); }
    .showhead { height:40px; display:flex; align-items:center; gap:8px; padding:0 12px; border-bottom:1px solid var(--bd); }
    .showhead.warn { background:oklch(0.3 0.06 80 / 0.35); border-bottom-color:oklch(0.6 0.12 80 / 0.6); }
    .crumb { font-size:13px; color:var(--mfg); } .crumb b { color:var(--fg); font-weight:600; }
    .seg { display:inline-flex; border:1px solid var(--bd); border-radius:8px; padding:2px; gap:2px; background:var(--bg); }
    .seg span { height:24px; padding:0 9px; display:inline-flex; align-items:center; border-radius:6px; font-size:12px; color:var(--mfg); }
    .seg span.on { background:var(--muted); color:var(--fg); }
    .showbar { height:56px; display:flex; align-items:center; gap:10px; padding:0 12px; border-bottom:1px solid var(--bd); }
    .tile { height:44px; border:1px solid var(--bd); border-radius:8px; background:var(--card); padding:4px 10px; display:flex; flex-direction:column; justify-content:center; }
    .go { width:120px; height:44px; border-radius:8px; background:var(--pri); display:grid; place-items:center; font-weight:800; letter-spacing:0.1em; }
    .tabsstrip { height:40px; display:flex; align-items:flex-end; gap:2px; padding:0 12px; border-bottom:1px solid var(--bd); }
    .tabsstrip span { padding:9px 14px; font-size:12.5px; color:var(--mfg); border-bottom:2px solid transparent; }
    .tabsstrip span.on { color:var(--fg); border-bottom-color:var(--pri); }
    .stackhead { height:48px; display:flex; align-items:center; gap:10px; padding:0 12px; border-bottom:1px solid var(--bd); }
    .stackhead.warn { background:oklch(0.3 0.06 80 / 0.25); }
    /* cards */
    .list { padding:6px 8px; display:flex; flex-direction:column; gap:6px; flex:1; min-height:0; overflow:hidden; }
    .card { border:1px solid var(--bd); border-radius:10px; background:var(--muted); overflow:hidden; }
    .card.open { border-color:oklch(0.55 0.15 260 / 0.6); }
    .card.live { border-color:rgba(74,222,128,0.65); box-shadow:0 0 0 1px rgba(74,222,128,0.25); }
    .card.next { border-color:rgba(96,165,250,0.6); }
    .cuehead { display:grid; grid-template-columns:22px 52px 1fr auto 60px 16px; align-items:center; gap:12px; padding:9px 12px; }
    .pip { width:9px; height:9px; border-radius:50%; background:#52525b; justify-self:center; }
    .pip.live { background:var(--green); box-shadow:0 0 0 3px rgba(74,222,128,0.2); }
    .pip.next { background:var(--blue); }
    .num { font-weight:700; font-family:ui-monospace,Menlo,monospace; }
    .body { background:var(--bg); border-top:1px solid var(--bd); }
    .strip { display:flex; align-items:center; gap:8px; padding:7px 12px; border-bottom:1px solid var(--bd); background:oklch(0.19 0.005 286); flex-wrap:nowrap; }
    .etabs { display:flex; gap:2px; padding:0 12px; border-bottom:1px solid var(--bd); }
    .etabs span { padding:8px 11px; font-size:12px; color:var(--mfg); border-bottom:2px solid transparent; }
    .etabs span b { font-weight:500; font-size:10.5px; margin-left:4px; }
    .etabs span.on { color:var(--fg); border-bottom-color:var(--pri); }
    .selbar { height:36px; display:flex; align-items:center; gap:8px; padding:0 12px; border-bottom:1px solid var(--bd); font-size:12px; }
    .stale-band { display:flex; align-items:center; gap:10px; padding:7px 12px; background:oklch(0.3 0.06 30 / 0.3); border-top:1px solid oklch(0.65 0.14 30 / 0.45); color:var(--stale); font-size:12.5px; }
    /* grid */
    table.g { width:100%; border-collapse:collapse; font-size:12px; }
    table.g th { font-size:9px; font-weight:700; letter-spacing:0.08em; text-transform:uppercase; color:var(--mfg); text-align:left; padding:7px 10px; border-bottom:1px solid var(--bd); }
    table.g td { height:34px; padding:0 10px; border-bottom:1px solid oklch(0.274 0.006 286.033 / 0.6); white-space:nowrap; vertical-align:middle; }
    table.g td.n { padding-left:12px; }
    table.g td.sub { padding-left:30px; color:var(--mfg); }
    .c { display:inline-flex; align-items:center; gap:6px; padding:3px 7px; border-radius:5px; position:relative; }
    .c.own { box-shadow:inset 3px 0 0 var(--own); background:oklch(0.62 0.16 260 / 0.1); }
    .c.look { box-shadow:inset 3px 0 0 var(--look); }
    .c.mq { outline:2px solid var(--pri); outline-offset:1px; }
    .c.open { outline:2px solid var(--pri); outline-offset:1px; background:oklch(0.62 0.16 260 / 0.22); }
    .src { font-size:9.5px; } .src.own { color:var(--own); } .src.look { color:var(--look); }
    .bar { display:inline-block; width:64px; height:5px; border-radius:3px; background:#2f2f37; position:relative; overflow:hidden; }
    .bar i { position:absolute; inset:0 auto 0 0; background:var(--pri); }
    .sw { width:12px; height:12px; border-radius:3px; display:inline-block; }
    /* popovers, menus, sheets */
    .pop { position:absolute; background:var(--card); border:1px solid var(--bd); border-radius:10px; box-shadow:0 16px 36px rgba(0,0,0,0.55); z-index:5; }
    .pop .ph { padding:9px 12px; border-bottom:1px solid var(--bd); display:flex; gap:8px; align-items:center; font-size:11.5px; }
    .pop .pb { padding:10px 12px; display:flex; flex-direction:column; gap:10px; }
    .field { height:28px; border:1px solid var(--bd); border-radius:6px; background:var(--bg); display:flex; align-items:center; padding:0 8px; gap:6px; font-family:ui-monospace,Menlo,monospace; }
    .field.focus { border-color:var(--pri); box-shadow:0 0 0 2px oklch(0.62 0.2 260 / 0.3); }
    .slider { height:6px; border-radius:3px; background:#2f2f37; position:relative; }
    .slider i { position:absolute; inset:0 auto 0 0; background:var(--pri); border-radius:3px; }
    .slider b { position:absolute; top:-5px; width:16px; height:16px; border-radius:50%; background:#fff; }
    .readout { font-size:10.5px; color:var(--mfg); }
    .menu { background:var(--card); border:1px solid var(--bd); border-radius:9px; padding:4px; box-shadow:0 14px 32px rgba(0,0,0,0.55); display:flex; flex-direction:column; min-width:220px; }
    .menu .mi { display:flex; align-items:center; gap:8px; min-height:30px; padding:4px 9px; border-radius:6px; font-size:12px; }
    .menu .mi.on { background:var(--muted); }
    .menu .mi small { margin-left:auto; color:var(--mfg); font-size:10.5px; padding-left:12px; }
    .menu .ms { height:1px; background:var(--bd); margin:4px 0; }
    .menu .mi.red { color:#ff7b7b; }
    .layer { display:grid; grid-template-columns:16px 24px minmax(0,1fr) 90px 34px 96px 18px; gap:12px; align-items:center; padding:7px 12px; border-bottom:1px solid oklch(0.274 0.006 286.033 / 0.6); }
    .tog { width:26px; height:14px; border-radius:7px; background:var(--pri); position:relative; }
    .tog::after { content:""; position:absolute; right:2px; top:2px; width:10px; height:10px; border-radius:50%; background:#fff; }
    .tog.off { background:#3f3f46; } .tog.off::after { left:2px; right:auto; }
    .fxrow { display:grid; grid-template-columns:20px minmax(0,1fr) 140px 130px 150px 24px; gap:12px; align-items:center; padding:8px 12px; border-bottom:1px solid oklch(0.274 0.006 286.033 / 0.6); }
    .sheetrow { display:grid; grid-template-columns:14px 18px 44px minmax(0,1fr) 70px 80px 80px 100px 70px 50px 200px; gap:10px; align-items:center; padding:0 12px; height:36px; border-bottom:1px solid oklch(0.274 0.006 286.033 / 0.6); font-size:12.5px; }
    .sheetrow.head { height:30px; font-size:9px; font-weight:700; letter-spacing:0.08em; text-transform:uppercase; color:var(--mfg); }
    .sheetrow.sel { background:oklch(0.62 0.16 260 / 0.14); box-shadow:inset 3px 0 0 var(--pri); }
    .sheetrow.sel2 { background:oklch(0.62 0.16 260 / 0.14); }
    .sheetrow.div { justify-content:center; display:flex; color:var(--mfg); font-size:11px; height:30px; }
    .handle { height:12px; display:grid; place-items:center; border-top:1px solid var(--bd); border-bottom:1px solid var(--bd); background:oklch(0.19 0.005 286); cursor:row-resize; }
    .handle i { width:40px; height:3px; border-radius:2px; background:#5a5a63; }
    .note { position:absolute; width:20px; height:20px; border-radius:50%; background:oklch(0.82 0.16 80); color:#111; font-size:11px; font-weight:800; display:grid; place-items:center; z-index:9; box-shadow:0 0 0 3px rgba(0,0,0,0.5); }
    .notes { border-top:1px solid var(--bd); background:var(--bg); padding:14px 20px 18px; display:grid; grid-template-columns:repeat(3, 1fr); gap:10px 28px; font-size:12px; color:var(--mfg); }
    .notes div b { color:var(--fg); margin-right:4px; }
    .notes .t { grid-column:1 / -1; font-size:14px; color:var(--fg); font-weight:600; }
    .panel { border:1px solid var(--bd); border-radius:10px; background:var(--page); overflow:hidden; display:flex; flex-direction:column; }
    .panel .pt { padding:8px 12px; border-bottom:1px solid var(--bd); }
    /* ── apply-model additions ── */
    .pal { display:inline-flex; align-items:center; gap:6px; height:28px; padding:0 10px 0 7px; border-radius:7px; border:1px solid var(--bd); background:oklch(0.19 0.005 286); font-size:12px; white-space:nowrap; }
    .pal .d { width:14px; height:14px; border-radius:50%; flex-shrink:0; }
    .pal.on { border-color:#5cd1c1; box-shadow:inset 0 0 0 1px #5cd1c1; }
    .pal.some { border-color:#5cd1c1; border-style:dashed; }
    .pal.fx { color:var(--fx); border-color:oklch(0.6 0.12 300 / 0.5); }
    .pal.fx.on { box-shadow:inset 0 0 0 1px var(--fx); border-color:var(--fx); }
    .pal.more { color:var(--mfg); }
    .pal.out { opacity:0.42; }
    .pp { background:var(--card); border:1px solid var(--bd); border-radius:12px; box-shadow:0 16px 36px rgba(0,0,0,0.55); display:flex; flex-direction:column; overflow:hidden; }
    .pp .ph { padding:9px 12px; border-bottom:1px solid var(--bd); display:flex; gap:8px; align-items:center; }
    .pp .ph b { font-size:13px; }
    .pp .bd { padding:9px 12px; display:flex; flex-direction:column; gap:7px; border-bottom:1px solid var(--bd); }
    .pp .ft { padding:8px 12px; display:flex; gap:6px; background:oklch(0.19 0.005 286); flex-wrap:wrap; }
    .pp .into { padding:6px 12px; background:oklch(0.3 0.05 185 / 0.35); color:#5cd1c1; font-size:11.5px; border-bottom:1px solid oklch(0.6 0.08 185 / 0.4); }
    .wrapr { display:flex; gap:6px; flex-wrap:wrap; }
    .chip.mask { color:var(--amber); border-color:oklch(0.6 0.12 80 / 0.6); background:oklch(0.3 0.06 80 / 0.25); }
    .chip.sel { color:var(--own); border-color:oklch(0.6 0.12 260 / 0.55); }
    .picker { height:96px; border-radius:6px; background:linear-gradient(to top, #000, rgba(0,0,0,0)), linear-gradient(to right, #fff, #ff8a1f); }
    .hue { height:10px; border-radius:5px; background:linear-gradient(to right, #f00, #ff0, #0f0, #0ff, #00f, #f0f, #f00); }
    .xy { height:120px; border-radius:6px; background-color:var(--bg); background-image:linear-gradient(var(--bd) 1px, transparent 1px), linear-gradient(90deg, var(--bd) 1px, transparent 1px); background-size:24px 24px; position:relative; border:1px solid var(--bd); }
    .xy i { position:absolute; width:11px; height:11px; border-radius:50%; background:var(--pri); box-shadow:0 0 0 3px rgba(59,130,246,0.3); }
    .canvas { padding:24px; display:flex; flex-direction:column; gap:22px; background:var(--page); flex:1; }
    .cap { font-size:11px; color:var(--mfg); max-width:340px; }
    .stack { display:flex; flex-direction:column; gap:8px; }
    .lrow { display:grid; grid-template-columns:16px 26px minmax(0,1fr) auto 18px; gap:10px; align-items:center; padding:7px 10px; border-bottom:1px solid oklch(0.274 0.006 286.033 / 0.6); font-size:12px; }
    .lrow .who { font-size:10.5px; color:var(--mfg); }
    .lrow.narrowed { background:oklch(0.3 0.05 185 / 0.18); }
    .sidesheet { width:460px; border-left:1px solid var(--bd); background:var(--card); display:flex; flex-direction:column; }
    .stabs { display:flex; gap:2px; padding:0 10px; border-bottom:1px solid var(--bd); flex-wrap:wrap; }
    .stabs span { padding:8px 7px; font-size:12px; color:var(--mfg); border-bottom:2px solid transparent; }
    .stabs span.on { color:var(--fg); border-bottom-color:var(--pri); }
    .stabs span.new { color:#5cd1c1; }
    .pad { height:64px; border-radius:9px; border:1px solid var(--bd); background:var(--muted); display:flex; flex-direction:column; justify-content:flex-end; padding:7px 9px; font-size:11.5px; }
    .pad.on { box-shadow:inset 0 0 0 2px #5cd1c1; }
    .tile2 { height:40px; border-radius:8px; border:1px solid var(--bd); background:var(--muted); display:flex; align-items:center; padding:0 10px; font-size:11.5px; }
    .tile2.sel { border-color:var(--pri); box-shadow:inset 0 0 0 1px var(--pri); }
`

function board(file, title, body, h) {
  const html = `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>${title}</title>
  <style>${CSS}
  </style>
</head>
<body>
<div class="app" style="min-height:${h}px">
${body}
</div>
</body>
</html>
`
  writeFileSync(OUT + file, html)
}

const rail = (on = 6) => `<div class="rail">${[...Array(10)].map((_, i) => `<i${i === on ? ' class="on"' : ''}></i>`).join('')}</div>`
const top = `<div class="top">Chris' DMX Controller v7<span class="sp"></span><span class="chip" style="background:#0f3a1f;border-color:#2f7a45;color:#86efac;height:28px;font-size:12px">Connected</span></div>`
const notes = (title, items) => `<div class="notes"><div class="t">${title}</div>${items.map(([n, t]) => `<div><b>${n}</b>${t}</div>`).join('')}</div>`
const note = (n, x, y) => `<span class="note" style="left:${x}px; top:${y}px">${n}</span>`

// A palette chip. state: '' | 'on' (covers every selected head) | 'some' | 'out' (outside the mask, picker only)
const pal = (name, colour, state = '') => `<span class="pal ${state}">${colour ? `<span class="d" style="background:${colour}"></span>` : ''}${name}</span>`
const fx = (name, state = '') => `<span class="pal fx ${state}">∿ ${name}</span>`
const more = (t) => `<span class="pal more">${t}</span>`
const band = (label, chips, hint = '') => `<div class="bd"><div class="row"><span class="lbl">${label}</span><span class="sp"></span>${hint ? `<span class="readout">${hint}</span>` : ''}</div><div class="wrapr">${chips.join('')}</div></div>`
const popup = ({ w = 340, title, heads, mask, into, palettes, editor, effects, foot }) => `<div class="pp" style="width:${w}px">
  <div class="ph"><b>${title}</b><span class="chip sel">${heads}</span><span class="sp"></span><span class="chip mask">${mask}</span></div>
  ${into ? `<div class="into">${into}</div>` : ''}
  ${palettes ?? ''}
  <div class="bd">${editor}</div>
  ${effects ?? ''}
  ${foot ? `<div class="ft">${foot}</div>` : ''}
</div>`
const btn = (t, cls = '') => `<span class="btn sm ${cls}">${t}</span>`
const slider = (pct, val) => `<div class="row"><div class="slider" style="flex:1"><i style="width:${pct}%"></i><b style="left:calc(${pct}% - 8px)"></b></div><div class="field" style="width:84px">${val}</div></div>`

const colourEditor = `<div class="picker"></div><div class="hue"></div><div class="row"><span class="field">#FF9A2E</span><span class="field">W 40</span><span class="sp"></span><span class="readout">a drag is a Local value</span></div>`
const colourPalettes = band('Palettes', [pal('Amber', '#ff9a2e', 'on'), pal('Deep blue', '#2f6bff'), pal('Red', '#ff3b3b', 'some'), pal('Warm white', '#fff3d6'), pal('Lavender', '#b04cff'), pal('Steel', '#9fc4ff'), more('All 23 …')], 'recent first · fit these heads')
const colourFx = band('Effects', [fx('Amber breathe'), fx('Rainbow Cycle', 'on'), fx('Colour Fade'), more('+ Effect …')], 'on these heads · palettes · library')

// ── Popup ────────────────────────────────────────────────────────────────────
board('Popup.dc.html', 'The palette popup · one per family', `
${top}
<div class="shell">${rail(6)}
<div class="main" style="position:relative">
<div class="canvas">
  <div class="row" style="gap:22px; align-items:flex-start">
    <div class="stack">
      <span class="lbl">Colour · a programmer cell, desk</span>
      ${popup({ title: 'Colour', heads: 'Hex 1–4 · 4 heads', mask: 'Colour', palettes: colourPalettes, editor: colourEditor, effects: colourFx, foot: btn('Save as palette…') + btn('Spread…') + btn('Pick') })}
    </div>
    <div class="stack" style="margin-top:150px">
      <span class="lbl">Hold (or right-click) on a chip</span>
      <div class="menu"><div class="mi"><b>Amber</b><small>palette · Colour</small></div><div class="ms"></div><div class="mi on">Take off these heads <small>it is on</small></div><div class="mi">Apply as values <small>⌥click</small></div><div class="ms"></div><div class="mi">Pick up</div><div class="mi">Edit palette…</div></div>
      <span class="cap">On every surface the chip, pad or strip, a hold opens this menu (D8). The first item is the tap's own verb, named for what it will do.</span>
    </div>
    <div class="stack">
      <span class="lbl">Colour · with the Warm wash layer focused</span>
      ${popup({ title: 'Colour', heads: 'Hex 1–4', mask: 'Colour', into: 'Into <b>Warm wash</b> · a palette writes its values into the Look; nothing reaches the stage', palettes: band('Palettes', [pal('Amber', '#ff9a2e'), pal('Deep blue', '#2f6bff'), pal('Red', '#ff3b3b'), pal('Warm white', '#fff3d6'), more('All 23 …')], 'no rings: a Look holds values'), editor: colourEditor, effects: band('Effects', [fx('Amber breathe'), fx('Rainbow Cycle'), more('+ Effect …')], 'joins the Look'), foot: btn('Spread…') })}
    </div>
  </div>
  <div class="row" style="gap:22px; align-items:flex-start">
    <div class="stack"><span class="lbl">Level · dimmer cell</span>
      ${popup({ w: 290, title: 'Level', heads: '4 heads', mask: 'Intensity', palettes: band('Palettes', [pal('Full'), pal('Seventy', '', 'on'), pal('Half'), pal('Glow'), more('All 6 …')]), editor: slider(70, '70<span class="dim">%</span>'), effects: band('Effects', [fx('Breathe'), fx('Pulse'), more('+ Effect …')]), foot: btn('Save as palette…') })}</div>
    <div class="stack"><span class="lbl">Position · pan/tilt cell</span>
      ${popup({ w: 290, title: 'Position', heads: 'Orbit 1–2', mask: 'Position', palettes: band('Palettes', [pal('DS centre'), pal('Piano', '', 'on'), pal('Up centre'), pal('Audience'), more('All 9 …')]), editor: `<div class="xy"><i style="left:58%; top:36%"></i></div><div class="row"><span class="field">Pan +18</span><span class="field">Tilt −42</span></div>`, effects: band('Effects', [fx('Circle'), fx('Figure 8'), more('+ Effect …')]), foot: btn('Save as palette…') + btn('Aim…') })}</div>
    <div class="stack"><span class="lbl">Zoom · a Beam cell</span>
      ${popup({ w: 270, title: 'Zoom', heads: 'Robe 1–2', mask: 'Beam', palettes: band('Palettes', [pal('Narrow'), pal('Mid'), pal('Wide', '', 'on'), more('All 4 …')], 'Beam palettes that set zoom'), editor: slider(82, '82<span class="dim">%</span>'), foot: btn('Save as palette…') })}</div>
    <div class="stack"><span class="lbl">Gobo · a setting cell</span>
      ${popup({ w: 250, title: 'Gobo', heads: 'Robe 1–2', mask: 'Beam', editor: `<div class="field focus" style="width:100%">Breakup 2</div><div class="readout">Open · Dots · Breakup 1 · <b>Breakup 2</b> · Cone …</div><div class="readout">No palette band: a palette can't carry a slotted role. A Look holds one.</div>` })}</div>
  </div>
</div>
${note(1, 12, 52)}${note(2, 12, 92)}${note(3, 12, 262)}${note(4, 12, 392)}${note(5, 735, 52)}${note(6, 12, 498)}${note(7, 928, 592)}
</div></div>
${notes('The palette popup: the cell editor every family already has, with that family\'s palettes above it and its effects below (D4). Sleeping Beauty\'s fixtures; palette names illustrative.', [
  ['1', 'The header names the heads the press lands on (the cell batch) and the mask, which is the cell\'s own family, as a Sel · property fader names its own attribute.'],
  ['2', 'Palettes that fit these heads, recently pressed first, then by name; All … opens the picker on this family. A ring is the pad\'s: solid when every head has it, dashed when some do. A tap is the press: a layer that tracks (D1), replacing the family\'s other value palette on these heads (D2, D6).'],
  ['3', 'The editor is unchanged. A drag or a typed value is a Local value, above every layer, as today.'],
  ['4', 'Effects: what runs on these heads first (lit), then the family\'s effect palettes, then + Effect … for the library. An effect palette is a layer; a library effect is Local (D5).'],
  ['5', 'With a Look layer focused the press goes into the Look (D7): a palette\'s values written per head into its rows, an effect into its effects. No rings, since a Look holds values, not palettes.'],
  ['6', 'Save as palette… from every family, from the cell\'s current values (Colour had it alone).'],
  ['7', 'A setting cell keeps its type-ahead and has no palette band; Beam cells offer the Beam palettes that set their property.'],
])}
`, 1250)

// ── Programmer ───────────────────────────────────────────────────────────────
const lrow = (on, name, who, cls = '', badge = '') => `<div class="lrow ${cls}"><span class="dim">⋮⋮</span><span class="tog${on ? '' : ' off'}"></span><span><b>${name}</b><div class="who">${who}</div></span>${badge}<span class="dim">⋯</span></div>`
board('Programmer.dc.html', 'The programmer · one selection, one mask, one press', `
${top}
<div class="shell">${rail(5)}
<div class="main" style="position:relative">
  <div class="showhead"><span class="crumb">Projects › Sleeping Beauty › <b>Programmer</b></span><span class="sp"></span><span class="seg"><span class="on">Programmer</span><span>Show</span><span>Prompt Book</span><span>Busk</span></span></div>
  <div class="strip"><span class="btn sm">Clear</span><span class="btn sm">Snap ▾</span><span class="btn sm">◌ Blind</span><span class="sep"></span><span class="btn sm">Include…</span><span class="btn sm">● Record</span><span class="btn sm">Record Look…</span><span class="sp"></span><span class="seg"><span>Output</span><span class="on">Local</span><span>Layer</span></span></div>
  <div class="row" style="flex:1; min-height:0; align-items:stretch">
    <div style="flex:1; min-width:0; display:flex; flex-direction:column">
      <div class="selbar"><b>4 heads</b><span class="chip mask">Colour ▾</span><span class="chip">desk · this window</span><span class="sp"></span><span class="btn sm">Set</span><span class="btn sm">Clear</span><span class="btn sm">Spread</span><span class="btn sm">Release</span><span class="btn sm">Locate</span><span class="btn sm ghost">Deselect</span></div>
      <div class="strip" style="gap:6px"><span class="lbl">Palettes</span>${pal('Amber', '#ff9a2e', 'on')}${pal('Deep blue', '#2f6bff')}${pal('Red', '#ff3b3b', 'some')}${pal('Warm white', '#fff3d6')}${fx('Amber breathe')}<span class="pal more">All · 23</span><span class="pal more">New</span></div>
      <table class="g">
        <thead><tr><th style="width:240px">Fixture</th><th>Dimmer</th><th>Colour</th><th>Position</th><th>Zoom</th></tr></thead>
        <tbody>
          <tr><td class="n">Hex 1</td><td><span class="c"><span class="bar"><i style="width:70%"></i></span> 70%</span></td><td><span class="c look mq"><span class="sw" style="background:#ff9a2e"></span> Amber</span></td><td class="dim">—</td><td class="dim">—</td></tr>
          <tr><td class="n">Hex 2</td><td><span class="c"><span class="bar"><i style="width:70%"></i></span> 70%</span></td><td><span class="c look mq"><span class="sw" style="background:#ff9a2e"></span> Amber</span></td><td class="dim">—</td><td class="dim">—</td></tr>
          <tr><td class="n">Hex 3</td><td><span class="c"><span class="bar"><i style="width:70%"></i></span> 70%</span></td><td><span class="c look mq"><span class="sw" style="background:#ff9a2e"></span> Amber</span></td><td class="dim">—</td><td class="dim">—</td></tr>
          <tr><td class="n">Hex 4</td><td><span class="c"><span class="bar"><i style="width:70%"></i></span> 70%</span></td><td><span class="c look mq"><span class="sw" style="background:#ff9a2e"></span> Amber</span></td><td class="dim">—</td><td class="dim">—</td></tr>
          <tr><td class="n">Hex 5</td><td class="dim">—</td><td><span class="c look"><span class="sw" style="background:#ff3b3b"></span> Red</span></td><td class="dim">—</td><td class="dim">—</td></tr>
          <tr><td class="n">Orbit 1</td><td class="dim">—</td><td class="dim">—</td><td><span class="c look mono">Piano</span></td><td class="dim">—</td></tr>
        </tbody>
      </table>
      <div style="position:relative; padding:16px 20px">
        <div class="menu" style="width:250px"><div class="mi"><b>Mask</b><small>what a press may touch</small></div><div class="ms"></div><div class="mi">☐ Intensity</div><div class="mi">☐ Position</div><div class="mi on">☑ Colour</div><div class="mi">☐ Beam</div><div class="ms"></div><div class="mi">All attributes <small>clears it</small></div></div>
        <div class="cap" style="margin-top:8px; max-width:520px">The family pill is the mask's picker (D3). The marquee still sets it, a rows-only click still clears it, and Record, Record Look, Include and Make layer open on it.</div>
      </div>
    </div>
    <div style="width:300px; border-left:1px solid var(--bd); background:var(--card); display:flex; flex-direction:column">
      <div class="pt" style="padding:8px 12px; border-bottom:1px solid var(--bd)"><span class="lbl">Layers · top wins</span></div>
      ${lrow(true, 'Local', '4 values · Rainbow Cycle on Hex 1–2', '', '<span class="chip sel">Local</span>')}
      ${lrow(true, 'Amber', 'Hex 1–4 · Colour', 'narrowed', '<span class="chip">just pressed</span>')}
      ${lrow(true, 'Red', 'Hex 5 · Colour <span style="color:#5cd1c1">· lost Hex 3–4 to Amber</span>', 'narrowed')}
      ${lrow(true, 'Piano', 'Orbit 1–2 · Position')}
      ${lrow(true, 'Warm wash', 'own fixtures · Intensity, Colour')}
      <div class="strip" style="border-top:1px solid var(--bd)"><span class="btn sm">+ Look</span><span class="btn sm">+ Palette</span><span class="btn sm">+ Effect</span></div>
      <div class="menu" style="margin:10px 12px; width:auto"><div class="mi"><b>Amber</b><small>layer</small></div><div class="ms"></div><div class="mi">Detach <small>into Local as values</small></div><div class="mi">Amount · Blend · Mask…</div><div class="mi">Pick up</div><div class="mi red">Remove</div></div>
    </div>
    <div style="width:300px; border-left:1px solid var(--bd); background:var(--bg); display:flex; flex-direction:column">
      <div class="pt" style="padding:8px 12px; border-bottom:1px solid var(--bd)"><b>+ Palette</b> <span class="dim">· onto Hex 1–4 · Colour</span></div>
      <div style="padding:10px 12px; display:flex; flex-direction:column; gap:10px">
        <div class="field">Search palettes</div>
        <div class="row"><span class="seg"><span>Intensity</span><span>Position</span><span class="on">Colour</span><span>Beam</span></span></div>
        <span class="lbl">Recent</span><div class="wrapr">${pal('Amber', '#ff9a2e', 'on')}${pal('Red', '#ff3b3b', 'some')}${pal('Deep blue', '#2f6bff')}</div>
        <span class="lbl">A–Z</span><div class="wrapr">${pal('Congo', '#5a2bd6')}${pal('Lavender', '#b04cff')}${pal('Steel', '#9fc4ff')}${pal('Warm white', '#fff3d6')}${pal('UV wash', '#6b2bff')}</div>
        <span class="lbl">Effects</span><div class="wrapr">${fx('Amber breathe')}${fx('Colour Fade')}</div>
        <span class="lbl">Outside the mask</span><div class="wrapr">${pal('Seventy', '', 'out')}${pal('Piano', '', 'out')}</div>
        <div class="readout">The add-layer sheet's fixture picker is gone: + Look, + Palette and All · n all open this, on the selection. A press doesn't close it.</div>
      </div>
    </div>
  </div>
${note(1, 126, 92)}${note(2, 2, 132)}${note(3, 398, 282)}${note(4, 760, 232)}${note(5, 760, 462)}${note(6, 1056, 90)}${note(7, 10, 500)}
</div></div>
${notes('The programmer after pressing Amber on Hex 1–4 over Red. One selection pair (targets and mask), one press, one rule for the family.', [
  ['1', 'The family pill is the selection\'s mask and opens its picker (7). Every press, Record and Include reads this one mask (D3).'],
  ['2', 'The palette strip is a shortcut: recents that fit, now with the pad\'s rings, and the same press as a pad (D1). ⌥click applies values, a hold opens the menu (D8).'],
  ['3', 'A palette on the grid shows its name and the layer\'s teal edge, so the cell says what tracks it.'],
  ['4', 'Amber replaced Red on Hex 3–4 (D2): Red\'s layer was narrowed to Hex 5. A palette pressed again on heads it covers comes off.'],
  ['5', 'Detach turns a layer into Local values (and Local effects) on its heads; Make layer and Save as palette go the other way.'],
  ['6', 'The one picker, on the selection and its mask; outside the mask is shown dimmed rather than refused after a press.'],
  ['7', 'The mask picker. All attributes is S10\'s toggle, here clearing the mask.'],
])}
`, 1050)

// ── Busk ─────────────────────────────────────────────────────────────────────
board('Busk.dc.html', 'The busk side sheet · a tab per family, and effects edited by selection', `
${top}
<div class="shell">${rail(7)}
<div class="main" style="position:relative">
  <div class="showhead"><span class="crumb">Projects › Sleeping Beauty › <b>Busk</b></span><span class="sp"></span><span class="seg"><span>Programmer</span><span>Show</span><span>Prompt Book</span><span class="on">Busk</span></span></div>
  <div class="row" style="flex:1; min-height:0; align-items:stretch">
    <div style="flex:1; min-width:0; padding:12px; display:flex; flex-direction:column; gap:10px">
      <span class="lbl">Rig</span>
      <div class="row" style="gap:6px">${['Hex 1', 'Hex 2', 'Hex 3', 'Hex 4'].map(n => `<div class="tile2 sel">${n}</div>`).join('')}${['Hex 5', 'Orbit 1', 'Orbit 2', 'Robe 1'].map(n => `<div class="tile2">${n}</div>`).join('')}</div>
      <span class="lbl">Page · Act 1 colour</span>
      <div style="display:grid; grid-template-columns:repeat(6, 1fr); gap:8px">
        <div class="pad on">Amber</div><div class="pad">Deep blue</div><div class="pad">Red</div><div class="pad">Warm white</div><div class="pad">Lavender</div><div class="pad">Steel</div>
        <div class="pad">Seventy</div><div class="pad">Half</div><div class="pad">Piano</div><div class="pad">DS centre</div><div class="pad" style="color:var(--fx)">Amber breathe</div><div class="pad">Q4 · market</div>
      </div>
      <div class="readout">Pads are unchanged (they already are the model). Their press and the side sheet's chips are one press.</div>
    </div>
    <div class="sidesheet">
      <div class="stabs"><span class="new">Level</span><span>Colour</span><span class="new on">Position</span><span class="new">Beam</span><span>Spread</span><span>Effects</span><span>Speed</span><span>Show</span></div>
      <div style="padding:12px; display:flex; flex-direction:column; gap:12px">
        <div class="row"><b>Position</b><span class="chip sel">Hex 1–4</span><span class="sp"></span><span class="chip mask">Position</span></div>
        <span class="lbl">Palettes</span><div class="wrapr">${pal('DS centre')}${pal('Piano', '', 'some')}${pal('Up centre')}${pal('Audience')}${more('All 9 …')}</div>
        <div class="xy" style="height:150px"><i style="left:40%; top:55%"></i></div>
        <div class="row"><span class="field">Pan −12</span><span class="field">Tilt +30</span><span class="sp"></span>${btn('Aim…')}</div>
        <span class="lbl">Effects</span><div class="wrapr">${fx('Circle')}${fx('Figure 8')}${more('+ Effect …')}</div>
        <div class="readout">The popup's body, the same component as the programmer cell's, over the busk selection (D10).</div>
      </div>
      <div class="stabs" style="border-top:1px solid var(--bd)"><span class="on">Effects</span></div>
      <div style="padding:12px; display:flex; flex-direction:column; gap:10px">
        <div class="row"><span class="lbl">On these heads · Hex 1–2 selected</span></div>
        <div class="fxrow" style="grid-template-columns:minmax(0,1fr) auto; padding:8px 0"><span><b style="color:var(--fx)">∿ Rainbow Cycle</b><div class="readout">Local · Hex 1–4 · 1 bar · Master 1</div></span>${btn('Edit…')}</div>
        <div class="pp" style="width:100%"><div class="ph"><b>Rainbow Cycle</b><span class="chip sel">Hex 1–2 of 1–4</span></div><div class="bd">${slider(50, '2 bars')}<div class="readout">Editing with part of it selected splits Hex 1–2 off at the same phase (D5). Select all four to edit it in place.</div></div><div class="ft">${btn('Apply to Hex 1–2', 'pri')}${btn('Select all 4')}</div></div>
        <div class="wrapr">${more('+ Effect …')}</div>
      </div>
    </div>
  </div>
${note(1, 892, 50)}${note(2, 892, 150)}${note(3, 892, 470)}${note(4, 892, 600)}
</div></div>
${notes('The busk view: a side-sheet tab per family with the popup\'s body, and the Effects tab adding and editing by selection.', [
  ['1', 'Level, Position and Beam join Colour (D10). Each is the shared popup body over the busk selection pair.'],
  ['2', 'Palettes, the editor and effects in the same order as the programmer cell\'s popup.'],
  ['3', 'The Effects tab lists what runs on the selected heads, and gains + Effect …'],
  ['4', 'MagicQ\'s rule: an edit lands on the selected heads. A subset of a Local effect splits off at its phase; all of it edits in place (D5).'],
])}
`, 1060)

console.log('wrote 3 boards')
