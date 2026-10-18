// Generates the show-cue-editing boards. Each board is one dark artboard that renders standalone;
// `node gen.mjs` rewrites all four. The vocabulary is the busk-chrome boards' (../../busk-chrome-design/),
// restated here rather than read, so a change there cannot move these.
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

const rail = `<div class="rail"><i></i><i></i><i></i><i></i><i></i><i></i><i class="on"></i><i></i><i></i><i></i></div>`
const top = `<div class="top">Chris' DMX Controller v7<span class="sp"></span><span class="chip" style="background:#0f3a1f;border-color:#2f7a45;color:#86efac;height:28px;font-size:12px">Connected</span></div>`
const showhead = (warn) => `<div class="showhead${warn ? ' warn' : ''}"><span class="crumb">Projects › Sleeping Beauty › <b>Show</b></span><span class="sp"></span><span class="btn sm">⤢</span><span class="seg"><span>Programmer</span><span class="on">Show</span><span>Prompt Book</span><span>Busk</span></span><span class="btn sm pri">${warn ? '■ Stop' : '▶ Start'}</span>${warn ? '<span class="lbl" style="color:var(--amber)">Unlocked</span><span class="btn sm">🔓 L</span>' : ''}</div>`
const showbar = (live) => `<div class="showbar"><div class="tile"><span class="lbl">Blackout</span><b>DBO</b></div><div class="tile"><span class="lbl">M1 · Master 1</span><b class="mono">120</b></div><div class="tile" style="flex:1">${live ? '<span class="lbl" style="color:var(--green)">Live · Act 1</span><span><b class="mono">Q4</b> Snooze front of scrim · change to market <span class="dim">→ next</span> <b class="mono">Q5</b> Scrim out · market square</span>' : '<span class="dim">No cue running</span>'}</div><span class="btn" style="height:44px">◀ BACK</span><div class="go">GO</div></div>`
const stackhead = (warn, view) => `<div class="tabsstrip"><span class="on">Act 1 <span class="dim">13</span></span><span>Act 2 <span class="dim">14</span></span></div>
<div class="stackhead${warn ? ' warn' : ''}"><span class="btn">← Stacks</span><b>Act 1</b><span class="dim">13 cues</span><span class="sp"></span><span class="seg"><span class="${view === 'cards' ? 'on' : ''}">Cards</span><span class="${view === 'table' ? 'on' : ''}">Table</span></span><span class="btn">⚡ Record into Act 1</span><span class="btn">Separator</span><span class="btn">Stack settings…</span></div>`
const actions = ({ face = 'edit', locked = false, words = true, several = false, sm = false } = {}) => {
  const b = sm ? 'btn sm' : 'btn'
  const a = (t, cls = '', one = false) => `<span class="${b}${locked || (one && several) ? ' ask' : ''} ${cls}">${t}</span>`
  const split = face === 'blind'
    ? `<span class="${b} blind">◌ Blind edit in programmer<span class="chev">▾</span></span>`
    : `<span class="${b} pri${several ? ' ask' : ''}">Edit in programmer<span class="chev">▾</span></span>`
  return `${split}${a(words ? 'Load into programmer' : 'Load', '', true)}${a('Record…', '', true)}<span class="sep"></span>${a('Duplicate')}${a(words ? 'Remove from stack' : 'Remove')}${a('Delete…', 'red')}${a('⋯')}`
}
const notes = (title, items) => `<div class="notes"><div class="t">${title}</div>${items.map(([n, t]) => `<div><b>${n}</b>${t}</div>`).join('')}</div>`

// ── Card ─────────────────────────────────────────────────────────────────────
board('Card.dc.html', 'Card view · the editor in an open card', `
${top}
<div class="shell">${rail}
<div class="main" style="position:relative">
${showhead(true)}
${showbar(true)}
${stackhead(true, 'cards')}
<div class="list">
  <div class="card"><div class="cuehead"><span class="pip"></span><span class="num">Q0</span><span>Pre-show · house lights</span><span class="row"><span class="chip shared">in 2 stacks</span><span class="mono dim" style="font-size:10px">top of p. 4</span></span><span class="mono">SNAP</span><span class="dim">›</span></div></div>
  <div class="card open">
    <div class="cuehead"><span class="pip"></span><span class="num">Q1</span><span>Tabs out · Snooze in front of scrim</span><span class="mono dim" style="font-size:10px">upper of p. 4</span><span class="mono">SNAP</span><span class="dim">⌄</span></div>
    <div class="body">
      <div class="strip">${actions({ face: 'blind' })}<span class="sp"></span><span class="dim" style="font-size:11.5px">✓ Saved</span><span class="btn sm">◐ Show on stage</span></div>
      <div class="etabs"><span class="on">Values<b>17</b></span><span>Looks<b>5</b></span><span>FX<b>0</b></span><span>Scenery<b>1</b></span><span>Events</span><span>Cue</span></div>
      <div class="selbar"><b>2 cells</b><span class="chip">Intensity</span><span class="dim">⏎ edit · ⌫ clear own</span><span class="sp"></span><span class="btn sm">Set</span><span class="btn sm">Clear</span><span class="btn sm">Spread</span><span class="sep"></span><span class="btn sm">Own only</span><span class="btn sm">+ Fixtures…</span><span class="btn sm">Whole rig</span><span class="btn sm ghost">Deselect</span></div>
      <table class="g">
        <thead><tr><th style="width:250px">Fixture</th><th>Dimmer</th><th>Colour</th><th>Position</th><th>Zoom</th><th>Strobe</th></tr></thead>
        <tbody>
          <tr><td class="n">LX1 Orbit 70 SR</td><td><span class="c own open"><span class="bar"><i style="width:80%"></i></span> 80% <span class="src own">own</span></span></td><td><span class="c look"><span class="sw" style="background:#8070ff"></span><span class="mono">128,112,255</span> <span class="src look">Scrim · opaque</span></span></td><td><span class="c look mono">120,150</span></td><td class="dim">—</td><td class="dim">—</td></tr>
          <tr><td class="n">LX1 Orbit 70 SL</td><td><span class="c own mq"><span class="bar"><i style="width:80%"></i></span> 80% <span class="src own">own</span></span></td><td><span class="c look"><span class="sw" style="background:#8070ff"></span><span class="mono">128,112,255</span> <span class="src look">Scrim · opaque</span></span></td><td><span class="c look mono">132,150</span></td><td class="dim">—</td><td class="dim">—</td></tr>
          <tr><td class="n">⌄ LX1 Shehds 19×15 SR</td><td><span class="c look"><span class="bar"><i style="width:70%"></i></span> 70% <span class="src look">Heads · shutters open</span></span></td><td class="dim"></td><td><span class="c look mono">124,148</span></td><td><span class="c own">60% <span class="src own">own</span></span></td><td class="dim">—</td></tr>
          <tr><td class="sub">Head 1</td><td></td><td><span class="c look"><span class="sw" style="background:#8070ff"></span><span class="mono">128,112,255</span> <span class="src look">Shehds colour</span></span></td><td></td><td></td><td></td></tr>
          <tr><td class="sub">Head 2</td><td></td><td><span class="c own"><span class="sw" style="background:#ff9a52"></span><span class="mono">255,154,82</span> <span class="src own">own · overrides Shehds colour</span></span></td><td></td><td></td><td></td></tr>
          <tr><td class="n">ADV1 Robe 575 SR</td><td><span class="c look"><span class="bar"><i style="width:100%"></i></span> 100% <span class="src look">Front of scrim</span></span></td><td class="dim">—</td><td><span class="c look mono">96,170</span></td><td><span class="c look">35%</span></td><td><span class="c look">0%</span></td></tr>
          <tr><td class="n">Front Profiles <span class="chip">group</span></td><td><span class="c own"><span class="bar"><i style="width:45%"></i></span> 45% <span class="src own">own · group row</span></span></td><td class="dim"></td><td class="dim"></td><td class="dim"></td><td class="dim"></td></tr>
        </tbody>
      </table>
    </div>
  </div>
  <div class="card live"><div class="cuehead"><span class="pip live"></span><span class="num">Q4</span><span>Snooze front of scrim · change to market</span><span class="row"><span class="chip stale">Edited since GO</span><span class="mono dim" style="font-size:10px">top of p. 22</span></span><span class="mono">SNAP</span><span class="dim">›</span></div>
    <div class="stale-band"><span>The stage still shows Q4 as it was at its GO. The next GO into it lands your edit.</span><span class="sp"></span><span class="btn sm stale">Update stage…</span></div></div>
  <div class="card next"><div class="cuehead"><span class="pip next"></span><span class="num">Q5</span><span>Scrim out · market square</span><span class="mono dim" style="font-size:10px">lower of p. 22</span><span class="mono">SNAP</span><span class="dim">›</span></div></div>
</div>
<div class="pop" style="left:362px; top:520px; width:288px">
  <div class="ph"><b>2 heads · Q1 own</b><span class="sp"></span><span class="dim">Dimmer</span></div>
  <div class="pb">
    <div class="row"><div class="slider" style="flex:1"><i style="width:80%"></i><b style="left:calc(80% - 8px)"></b></div><div class="field focus" style="width:84px">80<span class="dim">%</span></div></div>
    <div class="readout">204 of 255 · was 100% from Scrim · opaque (front-lit) v2 · writes the cue, nothing on stage</div>
  </div>
</div>
<span class="note" style="left:2px; top:283px">1</span>
<span class="note" style="left:536px; top:268px">2</span>
<span class="note" style="left:1360px; top:268px">3</span>
<span class="note" style="left:2px; top:323px">4</span>
<span class="note" style="left:640px; top:510px">5</span>
<span class="note" style="left:600px; top:422px">6</span>
<span class="note" style="left:2px; top:708px">7</span>
</div></div>
${notes('Card view — Act 1, show running, unlocked (the amber wash). Q1 is open; Q4 is live and edited since its GO.', [
  ['1', 'The action set sits straight under the cue row, not under the body. Running, the split button\'s face is Blind edit (D6).'],
  ['2', 'The rest of the set, in §5\'s order. Delete… is red and opens R1\'s in-use confirm.'],
  ['3', 'The save state (D10) and Show on stage (D13), which points this window\'s Stage at Q1.'],
  ['4', 'The tabs replace the long read surface and Cue properties (D15). Values first; the tab is remembered per window.'],
  ['5', 'A cell editor over two own cells: the programmer\'s, in the popover form, its label line naming the cue as the scope. It writes the cue (D2).'],
  ['6', 'Blue edge: own row. Teal edge: the Look that won it. A typed value over a Look-won cell becomes own and says what it overrides. A group own row draws on the group\'s row.'],
  ['7', 'Edited since GO, with Update stage (D4). The chip is on the row even while the card is closed.'],
])}
`, 1180)

// ── Table ────────────────────────────────────────────────────────────────────
const srow = (pip, num, name, fade, follow, book, layers, fx, scen, cls = '') =>
  `<div class="sheetrow ${cls}"><span class="dim">⋮</span><span class="pip ${pip}"></span><span class="num">${num}</span><span>${name}</span><span class="mono">${fade}</span><span class="dim">—</span><span class="dim">${follow}</span><span class="dim" style="font-size:11px">${book}</span><span>${layers}</span><span>${fx}</span><span style="font-size:11px">${scen}</span></div>`
board('Table.dc.html', 'Table view · the bottom pane', `
${top}
<div class="shell">${rail}
<div class="main" style="position:relative">
${showhead(false)}
${showbar(false)}
${stackhead(false, 'table')}
<div class="selbar" style="height:40px"><b>1 cue</b><span class="dim">Q1</span><span class="sp"></span><span class="btn sm">Set</span><span class="btn sm">Clear</span><span class="btn sm">Spread</span><span class="sep"></span>${actions({ words: false, sm: true })}<span class="btn sm ghost">Deselect</span></div>
<div class="sheetrow head"><span></span><span></span><span>Cue</span><span>Name</span><span>Fade</span><span>Curve</span><span>Follow</span><span>Book</span><span>Layers</span><span>FX</span><span>Scenery</span></div>
${srow('', 'Q0', 'Pre-show · house lights <span class="chip shared">in 2 stacks</span>', 'SNAP', '—', 'top of p. 4', '1', '', '<span class="dim">tracked</span>')}
<div class="sheetrow div">Act 1 Scene 1 · Royal Palace</div>
${srow('', 'Q1', 'Tabs out · Snooze in front of scrim', 'SNAP', '—', 'upper of p. 4', '5', '1', 'House tabs → drawn · 4 s', 'sel')}
${srow('', 'Q2', 'Scrim out · palace ballroom', 'SNAP', '—', 'upper of p. 6', '', '', 'SB Scrim → drawn · 6 s')}
${srow('', 'Q3', 'Blackout · scrim in', 'SNAP', '—', 'middle of p. 21', '', '', 'SB Scrim → closed · 6 s')}
<div class="sheetrow div">Act 1 Scene 2 · Market Square</div>
${srow('', 'Q4', 'Snooze front of scrim · change to market', 'SNAP', '—', 'top of p. 22', '5', '', 'SB Forest cloth → in · 10 s')}
<div class="handle"><i></i></div>
<div style="flex:1; display:flex; flex-direction:column; background:var(--bg)" data-key-region>
  <div class="strip" style="background:var(--bg)"><span class="num">Q1</span><span>Tabs out · Snooze in front of scrim</span><span class="chip">Act 1</span><span class="mono dim">fade SNAP · follow —</span><span class="sp"></span><span class="dim" style="font-size:11.5px">✓ Saved</span><span class="btn sm">◐ Show on stage</span></div>
  <div class="etabs"><span>Values<b>17</b></span><span class="on">Looks<b>5</b></span><span>FX<b>1</b></span><span>Scenery<b>1</b></span><span>Events</span><span>Cue</span></div>
  <div class="selbar"><span class="lbl">Layers · top wins</span><span class="dim">Edits change this cue's layer. A Look's contents change in the Looks library.</span><span class="sp"></span><span class="btn sm">+ Look</span><span class="btn sm">+ Palette</span></div>
  <div class="layer"><span class="dim">⋮</span><span class="mono dim">5</span><span class="row" style="min-width:0">Special · Snooze SR forestage v2 <span class="chip">Intensity</span><span class="chip">Position</span><span class="chip">Colour</span><span class="chip">Beam</span></span><span class="row"><span class="bar" style="width:50px"><i style="width:100%"></i></span><span class="mono">100%</span></span><span class="tog"></span><span class="btn sm">Open Look</span><span class="dim">×</span></div>
  <div class="layer"><span class="dim">⋮</span><span class="mono dim">4</span><span class="row">Front of scrim · ADV1 Robes key <span class="chip">adv1-robe575-sr</span><span class="chip">adv1-robe575-sl</span><span class="btn sm ghost">Targets ⟵ selection</span></span><span class="row"><span class="bar" style="width:50px"><i style="width:100%"></i></span><span class="mono">100%</span></span><span class="tog"></span><span class="btn sm">Open Look</span><span class="dim">×</span></div>
  <div class="layer"><span class="dim">⋮</span><span class="mono dim">3</span><span class="row">Shehds colour · scrim lavender <span class="chip">Colour</span></span><span class="row"><span class="bar" style="width:50px"><i style="width:100%"></i></span><span class="mono">100%</span></span><span class="tog"></span><span class="btn sm">Open Look</span><span class="dim">×</span></div>
  <div class="layer" style="background:oklch(0.62 0.16 260 / 0.08)"><span class="dim">⋮</span><span class="mono dim">2</span><span class="row">Scrim · opaque (front-lit) v2 <span class="chip amber">stomp</span><span class="chip">Intensity · Colour · Position · Beam</span></span><span class="row"><span class="bar" style="width:50px"><i style="width:60%"></i></span><span class="mono">60%</span></span><span class="tog"></span><span class="btn sm">Open Look</span><span class="dim">×</span></div>
  <div class="layer"><span class="dim">⋮</span><span class="mono dim">1</span><span class="row dim">Heads · shutters open <span class="chip">Intensity</span></span><span class="row dim"><span class="bar" style="width:50px"><i style="width:100%"></i></span><span class="mono">100%</span></span><span class="tog off"></span><span class="btn sm">Open Look</span><span class="dim">×</span></div>
  <div style="padding:8px 12px" class="dim">Pulse on LX1 Orbit 70s · from Special · Snooze SR forestage v2 <span class="chip">read-only here</span></div>
</div>
<span class="note" style="left:739px; top:193px">1</span>
<span class="note" style="left:2px; top:327px">2</span>
<span class="note" style="left:696px; top:490px">3</span>
<span class="note" style="left:2px; top:514px">4</span>
<span class="note" style="left:1216px; top:663px">5</span>
<span class="note" style="left:496px; top:817px">6</span>
</div></div>
${notes('Table view — Act 1, stopped, unlocked. Q1 is the one selected row, so its editor is in the pane, on Looks.', [
  ['1', 'The action set on the selection bar, after Set · Clear · Spread and before Deselect (library-sheets D11). Words fold first on a narrow bar; one-cue actions disable over several rows.'],
  ['2', 'One selected row opens the pane (D5). Two rows, or none, fold it and give the height back.'],
  ['3', 'The grip sets the pane\'s height for this window (sessionStorage), magnetic at its 160 px floor and at half the column.'],
  ['4', 'The pane\'s header names the stack, since the sheet\'s row names only the cue. The keys go to the region with focus (§4.5).'],
  ['5', 'Open Look leaves for the Looks library: a Look\'s contents edit changes every cue that layers it (D3). Everything else on the row is the cue\'s layer and sends nothing.'],
  ['6', 'A Look\'s deferred effects are listed under the Looks, read-only; the FX tab edits only the cue\'s own effects.'],
])}
`, 1180)

// ── Tabs ─────────────────────────────────────────────────────────────────────
const panel = (w, title, inner) => `<div class="panel" style="width:${w}px"><div class="pt row"><span class="lbl">${title}</span></div>${inner}</div>`
board('Tabs.dc.html', 'The FX, Scenery and Cue tabs, and the header', `
<div style="padding:24px; display:flex; flex-direction:column; gap:20px">
<div class="row" style="gap:16px; align-items:flex-start">
${panel(820, 'FX tab · an effect open, and the picker in draft', `
  <div class="etabs"><span>Values<b>17</b></span><span>Looks<b>5</b></span><span class="on">FX<b>2</b></span><span>Scenery<b>1</b></span><span>Events</span><span>Cue</span></div>
  <div class="selbar"><span class="lbl">Effects · this cue</span><span class="sp"></span><span class="btn sm">+ Effect</span></div>
  <div class="fxrow" style="background:oklch(0.62 0.16 260 / 0.08)"><span style="color:var(--fx)">∿</span><span><b>Pulse</b> <span class="dim">on Dimmer</span></span><span class="dim">LX1 Orbit 70 SR</span><span class="mono">4 beats · M1</span><span class="dim">after 2 s</span><span class="dim">×</span></div>
  <div style="padding:12px 16px 14px 44px; display:flex; flex-direction:column; gap:12px; border-bottom:1px solid var(--bd)">
    <div class="row" style="gap:18px"><div style="display:flex; flex-direction:column; gap:5px"><span class="lbl">Speed · beats a cycle</span><span class="seg"><span>½</span><span>1</span><span>2</span><span class="on">4</span><span>8</span></span></div><div style="display:flex; flex-direction:column; gap:5px"><span class="lbl">Master</span><span class="chip">M1 · 120</span></div><div style="display:flex; flex-direction:column; gap:5px"><span class="lbl">Over the level underneath</span><span class="seg"><span class="on">Replace it</span><span>Within it</span></span></div></div>
    <div class="row" style="gap:18px"><div style="flex:1; display:flex; flex-direction:column; gap:5px"><span class="lbl">Min</span><div class="row"><div class="slider" style="flex:1"><i style="width:20%"></i><b style="left:calc(20% - 8px)"></b></div><div class="field" style="width:70px">20<span class="dim">%</span></div></div></div><div style="flex:1; display:flex; flex-direction:column; gap:5px"><span class="lbl">Max</span><div class="row"><div class="slider" style="flex:1"><i style="width:100%"></i><b style="left:calc(100% - 8px)"></b></div><div class="field" style="width:70px">100<span class="dim">%</span></div></div></div></div>
    <div class="row" style="gap:18px"><span class="lbl" style="width:90px">Cue timing</span><div class="row"><span class="dim">Delay</span><div class="field" style="width:70px">2<span class="dim">s</span></div></div><div class="row"><span class="dim">Interval</span><div class="field" style="width:70px"><span class="dim">—</span></div></div><div class="row"><span class="dim">Random</span><div class="field" style="width:70px"><span class="dim">—</span></div></div><span class="sp"></span><span class="dim" style="font-size:11px">Shape ▸ · Advanced ▸</span></div>
    <div class="readout">Writes the cue. Nothing runs until a GO into Q1, or Update stage while it is live.</div>
  </div>
  <div class="fxrow"><span style="color:var(--fx)">∿</span><span><b>Colour Cycle</b> <span class="dim">on Colour · draft, just added</span></span><span class="dim">Front Profiles</span><span class="mono">1 bar · M2</span><span class="dim">—</span><span class="dim">×</span></div>
  <div style="padding:12px; display:flex; gap:12px">
    <div class="menu" style="min-width:300px"><div class="row" style="padding:4px 6px 8px"><span class="seg"><span>Intensity</span><span class="on">Colour</span><span>Position</span><span>Controls</span></span></div><div class="mi">Colour Cycle <small>tap again to swap</small></div><div class="mi on">Rainbow Cycle</div><div class="mi">Colour Pulse</div><div class="mi">Colour Fade</div><div class="ms"></div><div class="mi dim">On: Front Profiles — the Values selection</div></div>
    <div class="dim" style="font-size:12px; max-width:340px">+ Effect opens the programmer's picker over the Values tab's selection. A tap adds the effect to the cue with its defaults, and nothing runs; a second tap swaps it, as it does live (D17). Its fields are FxEffectFields over the stored effect, plus the cue's own timing.</div>
  </div>
`)}
<div style="display:flex; flex-direction:column; gap:16px">
${panel(540, 'Scenery tab', `
  <div class="etabs"><span>Values</span><span>Looks</span><span>FX</span><span class="on">Scenery<b>1</b></span><span>Events</span><span>Cue</span></div>
  <div style="padding:10px 12px; display:flex; flex-direction:column; gap:8px">
    <div class="row"><b>House tabs</b><span class="dim">drawn drape</span><span class="sp"></span><span class="seg"><span>Closed</span><span>Half</span><span class="on">Drawn</span></span><span class="field" style="width:60px">4<span class="dim">s</span></span><span class="dim">×</span></div>
    <div class="dim" style="font-size:11.5px; padding:6px 8px; border-radius:6px; background:repeating-linear-gradient(135deg, transparent 0 6px, rgba(255,255,255,0.04) 6px 12px)">Tracked into Q1: SB Bench hidden, SB Scrim closed, SB Throne SL shown … (12 more, held by the stack's set)</div>
    <span class="btn sm" style="align-self:flex-start">+ Scenery</span>
  </div>`)}
${panel(540, 'Cue tab', `
  <div class="etabs"><span>Values</span><span>Looks</span><span>FX</span><span>Scenery</span><span>Events</span><span class="on">Cue</span></div>
  <div style="padding:10px 12px; display:flex; flex-direction:column; gap:10px">
    <div><span class="lbl">Notes</span><div class="field" style="height:auto; padding:6px 8px; font-family:inherit; line-height:1.4">Called on # Good fairy sound. House tabs open; scrim stays closed. Behind the scrim: palace back wall cloth, thrones, cradle, Christmas tree.</div></div>
    <div class="row"><span class="lbl">Script hooks</span><span class="sp"></span><span class="btn sm">+ Hook</span></div><div class="dim">None.</div>
    <div class="row"><span class="lbl">Busk pages</span><span class="sp"></span><span class="btn sm">Add to a bank…</span></div>
  </div>`)}
</div>
</div>
${panel(1392, 'The editor header · one 40 px chrome row, in both hosts', `
  <div class="strip" style="background:var(--bg); gap:10px"><span class="lbl" style="color:var(--own)">Act 1 entry</span><span class="field" style="width:56px">Q0</span><span class="field" style="width:84px"><span class="dim">follow</span> —</span><span class="sep"></span><span class="field" style="width:250px; font-family:inherit">Pre-show · house lights</span><span class="field" style="width:70px">SNAP</span><span class="field" style="width:90px"><span class="dim">curve</span> —</span><span class="chip shared">in 2 stacks: Act 1 Q0 · Act 2 Q0</span><span class="sp"></span><span class="dim" style="font-size:11.5px">Saving…</span><span class="btn sm">◐ Show on stage</span></div>
  <div class="dim" style="padding:8px 12px; font-size:12px">Number and follow are this stack's entry (P5) and say so; name, fade and curve are the cue's, shared by every stack it is in (P4). The shared chip lists the others on hover, as R1 §4 has it. Saving… · Saved · Save failed is the row store's (D10); Undo is a toast after each flush and ⌘Z while the editor has focus.</div>
`)}
</div>
${notes('The tabs the cards and the pane share. Every one of them writes the stored cue; none sends output (P2).', [
  ['FX', 'Rows are the cue\'s stored effects, matched by uuid once session 2 keeps them. A Look\'s effects are under Looks, read-only.'],
  ['Scenery', 'CueSceneryEditor\'s body, as Cue properties drew it. Scenery is drawn, never output, so it follows the edit at once, as today.'],
  ['Cue', 'Notes, hooks and busk pages: what is left of Cue properties once the header takes name, number, fade, curve and follow (D15).'],
])}
`, 1080)

// ── Actions ──────────────────────────────────────────────────────────────────
const stateRow = (label, sub, inner) => `<div class="row" style="padding:10px 14px; border-bottom:1px solid var(--bd); gap:14px"><div style="width:170px"><b>${label}</b><div class="dim" style="font-size:11.5px">${sub}</div></div><div class="row" style="flex:1">${inner}</div></div>`
board('Actions.dc.html', 'The action set · states, menus, confirms, the phone, the way back', `
<div style="padding:24px; display:flex; flex-direction:column; gap:20px">
${panel(1392, 'The card strip in four states', `
  ${stateRow('Stopped', 'unlocked', actions())}
  ${stateRow('Running', 'unlocked, amber wash', actions({ face: 'blind' }))}
  ${stateRow('Locked', 'running', actions({ face: 'blind', locked: true }) + '<span class="dim" style="font-size:11px">🔒 a press asks to unlock; Blind edit works (D8)</span>')}
  ${stateRow('Table, 3 rows', 'the selection bar', '<span class="btn sm">Set</span><span class="btn sm">Clear</span><span class="btn sm">Spread</span><span class="sep"></span>' + actions({ words: false, several: true, sm: true }) + '<span class="dim" style="font-size:11px">Edit, Load and Record: one cue at a time</span>')}
`)}
<div class="row" style="gap:20px; align-items:flex-start">
  <div style="display:flex; flex-direction:column; gap:8px"><span class="lbl">The split menu · running</span><div class="menu"><div class="mi on">◌ Blind edit in programmer <small>default while running</small></div><div class="mi">Edit in programmer <small>live</small></div></div>
    <span class="lbl" style="margin-top:8px">The split menu · stopped</span><div class="menu"><div class="mi on">Edit in programmer <small>default while stopped</small></div><div class="mi">◌ Blind edit in programmer</div></div>
    <span class="lbl" style="margin-top:8px">⋯</span><div class="menu"><div class="mi">Add to stack…</div><div class="mi">Move to stack…</div><div class="ms"></div><div class="mi">Copy link</div></div></div>
  <div class="panel" style="width:430px"><div class="pt"><b>Delete Pre-show · house lights?</b></div><div style="padding:12px; display:flex; flex-direction:column; gap:8px; font-size:12.5px"><span>It is in use. Deleting it also removes:</span><div class="dim">· Act 1 Q0 and Act 2 Q0 (entries)<br>· 1 busk pad on <i>Pre-show</i><br>· 2 prompt-book anchors</div><span class="dim">R1 D8: the confirm always lists them and sends force.</span><div class="row" style="justify-content:flex-end"><span class="btn">Cancel</span><span class="btn" style="background:#7f1d1d; border-color:#b91c1c">Delete cue</span></div></div></div>
  <div class="panel" style="width:430px"><div class="pt"><b>Update the stage with Q4?</b></div><div style="padding:12px; display:flex; flex-direction:column; gap:8px; font-size:12.5px"><span>Q4's rows fade in over its own fade (SNAP: at once).</span><div class="dim">· 14 values change<br>· 1 effect edited in place, phase kept<br>· 1 effect stopped<br>· scenery and events are not re-fired</div><div class="row" style="justify-content:flex-end"><span class="btn">Cancel</span><span class="btn pri">Update stage</span></div></div></div>
</div>
<div class="row" style="gap:20px; align-items:flex-start">
  <div class="panel" style="width:340px"><div class="pt"><span class="lbl">Phone · Now playing, ⋯ open</span></div><div style="padding:12px; display:flex; flex-direction:column; gap:10px; background:var(--bg)">
    <div class="card live" style="padding:12px; display:flex; flex-direction:column; gap:8px; background:oklch(0.2 0.03 150)"><div class="row"><span class="lbl" style="color:var(--green)">● Now playing</span><span class="sp"></span><span class="chip">5 / 13</span><span class="btn sm">⋯</span></div><div class="row" style="gap:10px"><span class="num" style="font-size:24px">Q4</span><b>Snooze front of scrim · change to market</b></div><span class="chip stale" style="align-self:flex-start">Edited since GO</span><div class="row dim" style="font-size:11px">Fade SNAP</div></div>
    <div class="menu"><div class="mi on">◌ Blind edit in programmer</div><div class="mi">Edit in programmer</div><div class="mi">Load into programmer</div><div class="mi">Record…</div><div class="mi" style="color:var(--stale)">Update stage…</div><div class="ms"></div><div class="mi">Duplicate</div><div class="mi">Remove from stack</div><div class="mi red">Delete…</div></div></div></div>
  <div class="panel" style="width:800px"><div class="pt"><span class="lbl">The programmer after Blind edit, from the Show view</span></div><div style="padding:10px 12px; display:flex; flex-direction:column; gap:10px; background:var(--bg)">
    <div class="row"><span class="btn sm">← Show</span><span class="chip">Q4 · Snooze front of scrim</span><span class="btn sm pri">Update</span><span class="btn sm">Revert</span><span class="sep"></span><span class="btn sm">Clear</span><span class="btn sm">Snap ▾</span><span class="btn sm blind">◌ Blind</span><span class="btn sm">Include…</span><span class="btn sm">● Record</span></div>
    <div class="dim" style="font-size:12px">← Show returns to Act 1 with Q4 open (D16), drawn while this include is the current one. Blind holds the included values, layers and the cue's effects (D7) until it goes off.</div>
    <div class="row"><span class="chip amber">Blind · values and effects held</span><span class="dim" style="font-size:11.5px">the reporters' new wording</span></div></div></div>
</div>
</div>
${notes('The action set is one component, CueActions, in three hosts. Semantics are R1\'s; the order is the same everywhere.', [
  ['Face', 'The split button\'s face follows the run state (D6), so locked it is always the one action that works.'],
  ['Several', 'Duplicate and Remove act on each row; Delete is one confirm for all, on R1\'s bulk route.'],
  ['Phone', 'The ⋯ carries the set in the same order, with Update stage while the cue is edited since GO (D9).'],
])}
`, 1240)

console.log('wrote 4 boards')
