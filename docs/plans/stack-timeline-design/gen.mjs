// Generates the stack-timeline boards. Each board is one dark artboard that renders standalone;
// `node gen.mjs` rewrites all four and canvas.json. The vocabulary is show-cue-editing-design's
// (../completed/show-cue-editing-design/), restated here so a change there cannot move these.
import { writeFileSync } from 'node:fs'

const OUT = new URL('.', import.meta.url).pathname

const CSS = `
    body { margin:0; }
    .app { --fg:oklch(0.985 0 0); --card:oklch(0.21 0.006 285.885); --muted:oklch(0.274 0.006 286.033);
      --mfg:oklch(0.705 0.015 286.067); --bd:oklch(0.274 0.006 286.033); --bd2:oklch(0.274 0.006 286.033 / 0.6);
      --pri:oklch(0.623 0.214 259.815); --bg:oklch(0.141 0.005 285.823); --page:oklch(0.17 0.005 286);
      --amber:oklch(0.82 0.16 80); --green:#4ade80; --blue:#60a5fa; --stale:#ff8f78;
      --look:#5cd1c1; --pal:#d4d4d8; --fx:#b98cff; --scen:#e0b878;
      font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", "Helvetica Neue", sans-serif;
      -webkit-font-smoothing:antialiased; background:var(--bg); color:var(--fg); box-sizing:border-box; font-size:13px; line-height:1.35;
      width:1440px; display:flex; flex-direction:column; position:relative; }
    .app.narrow { width:1240px; }
    .app * { box-sizing:border-box; }
    .mono { font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace; font-variant-numeric:tabular-nums; }
    .dim { color:var(--mfg); } .sp { flex:1; }
    .lbl { font-size:9px; font-weight:700; text-transform:uppercase; letter-spacing:0.08em; color:var(--mfg); white-space:nowrap; }
    .row { display:flex; align-items:center; gap:8px; }
    .btn { display:inline-flex; align-items:center; gap:5px; height:28px; padding:0 10px; border:1px solid var(--bd); border-radius:7px; background:var(--card); font-size:12.5px; white-space:nowrap; color:var(--fg); }
    .btn.sm { height:24px; padding:0 8px; font-size:11.5px; border-radius:6px; }
    .btn.pri { background:var(--pri); border-color:var(--pri); color:#fff; }
    .btn.ghost { background:transparent; border-color:transparent; }
    .btn.on { background:var(--muted); }
    .btn.ask { opacity:0.5; } .btn.stale { color:var(--stale); border-color:oklch(0.65 0.14 30 / 0.6); }
    .sep { width:1px; height:18px; background:var(--bd); flex:0 0 auto; }
    .chip { display:inline-flex; align-items:center; gap:5px; height:20px; padding:0 7px; border-radius:999px; border:1px solid var(--bd); font-size:10.5px; color:var(--mfg); white-space:nowrap; flex:0 0 auto; }
    .chip.on { color:var(--fg); border-color:oklch(0.985 0 0 / 0.5); background:oklch(0.985 0 0 / 0.06); }
    .chip.shared { color:#7aa2ff; border-color:oklch(0.6 0.12 260 / 0.55); }
    .chip.stale { color:var(--stale); border-color:oklch(0.65 0.14 30 / 0.6); background:oklch(0.3 0.06 30 / 0.3); }
    .chip.amber { color:var(--amber); border-color:oklch(0.6 0.12 80 / 0.6); background:oklch(0.3 0.06 80 / 0.3); }
    .chip i { width:7px; height:7px; border-radius:2px; display:inline-block; }
    kbd { border-radius:4px; border:1px solid var(--bd); background:oklch(0.274 0.006 286.033 / 0.5); padding:0 5px; font-size:9.5px; font-family:ui-monospace,Menlo,monospace; color:var(--fg); }
    /* chrome */
    .top { height:48px; background:#2563eb; display:flex; align-items:center; padding:0 16px; gap:10px; font-weight:600; font-size:16px; }
    .shell { display:flex; flex:1; min-height:0; }
    .rail { width:64px; flex:0 0 auto; background:var(--bg); border-right:1px solid var(--bd); display:flex; flex-direction:column; align-items:center; gap:14px; padding-top:14px; }
    .rail i { width:22px; height:22px; border-radius:6px; background:var(--muted); display:block; }
    .rail i.on { background:oklch(0.35 0.08 260); }
    .main { flex:1; min-width:0; display:flex; flex-direction:column; background:var(--page); position:relative; }
    .showhead { height:40px; display:flex; align-items:center; gap:8px; padding:0 12px; border-bottom:1px solid var(--bd); }
    .showhead.warn { background:oklch(0.3 0.06 80 / 0.35); border-bottom-color:oklch(0.6 0.12 80 / 0.6); }
    .crumb { font-size:13px; color:var(--mfg); } .crumb b { color:var(--fg); font-weight:600; }
    .seg { display:inline-flex; border:1px solid var(--bd); border-radius:8px; padding:2px; gap:2px; background:var(--bg); flex:0 0 auto; }
    .seg span { height:24px; padding:0 9px; display:inline-flex; align-items:center; gap:5px; border-radius:6px; font-size:12px; color:var(--mfg); white-space:nowrap; }
    .seg span.on { background:var(--muted); color:var(--fg); }
    .seg.sm span { height:20px; padding:0 7px; font-size:11px; }
    .showbar { height:56px; display:flex; align-items:center; gap:10px; padding:0 12px; border-bottom:1px solid var(--bd); }
    .tile { height:44px; border:1px solid var(--bd); border-radius:8px; background:var(--card); padding:4px 10px; display:flex; flex-direction:column; justify-content:center; position:relative; overflow:hidden; }
    .go { width:120px; height:44px; border-radius:8px; background:var(--pri); display:grid; place-items:center; font-weight:800; letter-spacing:0.1em; }
    .tabsstrip { height:40px; display:flex; align-items:flex-end; gap:2px; padding:0 12px; border-bottom:1px solid var(--bd); }
    .tabsstrip span { padding:9px 14px; font-size:12.5px; color:var(--mfg); border-bottom:2px solid transparent; }
    .tabsstrip span.on { color:var(--fg); border-bottom-color:var(--pri); }
    .stackhead { height:48px; display:flex; align-items:center; gap:10px; padding:0 12px; border-bottom:1px solid var(--bd); }
    .stackhead.warn { background:oklch(0.3 0.06 80 / 0.25); }
    .rowb { height:40px; display:flex; align-items:center; gap:8px; padding:0 12px; border-bottom:1px solid var(--bd); }
    .selbar { min-height:36px; display:flex; align-items:center; gap:8px; padding:0 12px; border-bottom:1px solid var(--bd); font-size:12px; flex-wrap:wrap; }
    .handle { height:12px; display:grid; place-items:center; border-top:1px solid var(--bd); border-bottom:1px solid var(--bd); background:oklch(0.19 0.005 286); }
    .handle i { width:40px; height:3px; border-radius:2px; background:#5a5a63; }
    .foot { height:26px; display:flex; align-items:center; gap:14px; padding:0 12px; border-top:1px solid var(--bd); font-size:10.5px; color:var(--mfg); white-space:nowrap; }
    .foot .k { display:inline-flex; align-items:center; gap:5px; }
    .foot .k i { width:12px; height:10px; border-radius:3px; display:inline-block; }
    /* the timeline grid */
    .tl { position:relative; display:flex; flex-direction:column; overflow:hidden; background:var(--bg); }
    .tr { display:flex; height:36px; border-bottom:1px solid var(--bd2); position:relative; flex:0 0 auto; }
    .tr.gh { height:22px; border-bottom:none; }
    .tr.ch { height:44px; border-bottom:1px solid var(--bd); }
    .tr.mk { height:30px; }
    .tr.live { background:rgba(74,222,128,0.07); }
    .tr.next { background:oklch(0.623 0.214 259.815 / 0.05); }
    .tr .edge { position:absolute; left:0; top:0; bottom:0; width:3px; z-index:4; }
    .tr.live .edge { background:var(--green); } .tr.next .edge { background:var(--blue); }
    .left .prog { position:absolute; left:0; bottom:0; height:2px; background:var(--green); }
    .left { position:relative; display:grid; gap:8px; align-items:center; padding:0 10px 0 12px; flex:0 0 auto; height:100%; border-right:1px solid var(--bd); overflow:hidden; white-space:nowrap; font-size:12.5px; }
    .tr.ch .left { align-items:end; padding-bottom:7px; }
    .hl { font-size:9px; font-weight:700; letter-spacing:0.08em; text-transform:uppercase; color:var(--mfg); }
    .nm { overflow:hidden; text-overflow:ellipsis; display:flex; align-items:center; gap:6px; min-width:0; }
    .nm > span:first-child { overflow:hidden; text-overflow:ellipsis; min-width:0; }
    .pip { width:9px; height:9px; border-radius:50%; background:#52525b; justify-self:center; }
    .pip.live { background:var(--green); box-shadow:0 0 0 3px rgba(74,222,128,0.2); }
    .pip.next { background:var(--blue); }
    .pip.done { background:#3f3f46; }
    .num { font-weight:700; font-family:ui-monospace,Menlo,monospace; }
    .mklab { grid-column:1 / -1; display:flex; align-items:center; gap:10px; color:var(--mfg); font-size:11px; }
    .mklab::before, .mklab::after { content:""; flex:1; height:1px; background:var(--bd); }
    .gcell { flex:0 0 auto; height:100%; display:flex; align-items:center; gap:6px; padding:0 8px; border-left:1px solid var(--bd); }
    .gcell i { width:8px; height:8px; border-radius:2px; }
    .hc { flex:0 0 auto; height:100%; display:flex; flex-direction:column; justify-content:flex-end; padding:0 8px 6px; gap:1px; overflow:hidden; white-space:nowrap; }
    .hc.g0, .mc.g0 { border-left:1px solid var(--bd); }
    .hc b { font-size:11.5px; font-weight:600; overflow:hidden; text-overflow:ellipsis; display:flex; align-items:center; gap:5px; }
    .hc b i { width:9px; height:9px; border-radius:2px; flex:0 0 auto; }
    .hc span { font-size:10px; color:var(--mfg); overflow:hidden; text-overflow:ellipsis; }
    .hc.sel { background:oklch(0.623 0.214 259.815 / 0.12); box-shadow:inset 0 -2px 0 var(--pri); }
    .mc { position:relative; flex:0 0 auto; height:100%; }
    .run { position:absolute; left:8px; right:8px; top:-1px; bottom:-1px; display:flex; align-items:center; gap:4px; padding:0 6px 0 8px; font-size:10px; z-index:1; white-space:nowrap; overflow:hidden; }
    .run::before { content:""; position:absolute; left:0; top:0; bottom:0; width:3px; background:var(--edge, transparent); }
    .run.s { top:4px; border-top-left-radius:6px; border-top-right-radius:6px; }
    .run.e { bottom:4px; border-bottom-left-radius:6px; border-bottom-right-radius:6px; }
    .run.look { background:#17302d; --edge:var(--look); }
    .run.pal { background:#25252a; --edge:var(--pal); }
    .run.fx { background:#2a2141; --edge:var(--fx); }
    .run.scen { background:#352d21; --edge:var(--scen); top:5px; bottom:5px; border-radius:6px; color:var(--fg); font-size:10.5px; }
    .run.off { background:transparent; border:1px solid rgba(92,209,193,0.6); color:var(--mfg); --edge:transparent; }
    .run.ghost { background:transparent; --edge:transparent; border:1px dashed oklch(0.985 0 0 / 0.55); color:var(--mfg); }
    .run.rel { background:transparent; --edge:transparent; border:1px dashed var(--stale); color:var(--stale); top:4px; bottom:4px; border-radius:6px; }
    .rk { font-family:ui-monospace,Menlo,monospace; font-style:normal; font-size:10px; color:oklch(0.985 0 0 / 0.75); }
    .gl { font-style:normal; font-size:9.5px; color:var(--mfg); }
    .gl.r { margin-left:auto; }
    .gl.st { color:var(--amber); font-weight:700; }
    .gl.fxc { color:var(--fx); }
    .sw { width:10px; height:10px; border-radius:3px; display:inline-block; flex:0 0 auto; }
    .trk { position:absolute; left:50%; margin-left:-1px; width:2px; top:-1px; bottom:-1px; background:rgba(224,184,120,0.4); }
    .dia { position:absolute; left:50%; top:-5px; width:9px; height:9px; margin-left:-4px; transform:rotate(45deg); background:var(--fx); border:1px solid var(--bg); z-index:3; }
    .brk { position:absolute; left:14px; right:14px; top:-1px; height:0; border-top:2px dotted var(--fx); z-index:3; }
    .mc.sel::after { content:""; position:absolute; inset:2px 5px; border-radius:8px; background:oklch(0.623 0.214 259.815 / 0.22); box-shadow:inset 0 0 0 2px oklch(0.985 0 0 / 0.9); z-index:2; pointer-events:none; }
    .mc.sel.sn::after { bottom:-1px; border-bottom-left-radius:0; border-bottom-right-radius:0; box-shadow:inset 2px 0 0 oklch(0.985 0 0 / 0.9), inset -2px 0 0 oklch(0.985 0 0 / 0.9), inset 0 2px 0 oklch(0.985 0 0 / 0.9); }
    .mc.sel.sp::after { top:0; border-top-left-radius:0; border-top-right-radius:0; box-shadow:inset 2px 0 0 oklch(0.985 0 0 / 0.9), inset -2px 0 0 oklch(0.985 0 0 / 0.9), inset 0 -2px 0 oklch(0.985 0 0 / 0.9); }
    .mc.sel.sp.sn::after { border-radius:0; box-shadow:inset 2px 0 0 oklch(0.985 0 0 / 0.9), inset -2px 0 0 oklch(0.985 0 0 / 0.9); }
    .mc.inert .run { opacity:0.55; }
    .grab { position:absolute; left:50%; bottom:2px; width:22px; margin-left:-11px; height:7px; border-radius:4px; background:var(--fg); z-index:5; box-shadow:0 0 0 2px var(--bg); }
    .scope { position:absolute; z-index:6; display:inline-flex; align-items:center; gap:6px; border-radius:999px; background:var(--pri); color:#fff; padding:3px 10px; font-size:11px; white-space:nowrap; box-shadow:0 8px 16px rgba(0,0,0,0.5); }
    .fadeR { position:absolute; right:0; top:0; bottom:0; width:28px; background:linear-gradient(to right, transparent, var(--bg)); z-index:6; pointer-events:none; }
    /* popovers, menus */
    .pop { background:var(--card); border:1px solid var(--bd); border-radius:10px; box-shadow:0 16px 36px rgba(0,0,0,0.55); }
    .pop .ph { padding:9px 12px; border-bottom:1px solid var(--bd); display:flex; gap:8px; align-items:center; font-size:12px; }
    .pop .pb { padding:10px 12px; display:flex; flex-direction:column; gap:10px; font-size:12px; }
    .pop .pf { padding:9px 12px; border-top:1px solid var(--bd); display:flex; gap:8px; justify-content:flex-end; align-items:center; }
    .field { height:28px; border:1px solid var(--bd); border-radius:6px; background:var(--bg); display:flex; align-items:center; padding:0 8px; gap:6px; font-family:ui-monospace,Menlo,monospace; font-size:12px; }
    .field.sans { font-family:inherit; }
    .field.focus { border-color:var(--pri); box-shadow:0 0 0 2px oklch(0.62 0.2 260 / 0.3); }
    .slider { height:6px; border-radius:3px; background:#2f2f37; position:relative; }
    .slider i { position:absolute; inset:0 auto 0 0; background:var(--pri); border-radius:3px; }
    .slider b { position:absolute; top:-5px; width:16px; height:16px; border-radius:50%; background:#fff; }
    .tog { width:26px; height:14px; border-radius:7px; background:var(--pri); position:relative; flex:0 0 auto; }
    .tog::after { content:""; position:absolute; right:2px; top:2px; width:10px; height:10px; border-radius:50%; background:#fff; }
    .tog.off { background:#3f3f46; } .tog.off::after { left:2px; right:auto; }
    .cb { width:14px; height:14px; border-radius:3px; background:var(--pri); display:inline-grid; place-items:center; font-size:10px; color:#fff; flex:0 0 auto; }
    .readout { font-size:11px; color:var(--mfg); line-height:1.45; }
    .menu { background:var(--card); border:1px solid var(--bd); border-radius:9px; padding:4px; box-shadow:0 14px 32px rgba(0,0,0,0.55); display:flex; flex-direction:column; min-width:220px; }
    .menu .mi { display:flex; align-items:center; gap:8px; min-height:28px; padding:3px 9px; border-radius:6px; font-size:12px; }
    .menu .mi.on { background:var(--muted); }
    .menu .mi small { margin-left:auto; color:var(--mfg); font-size:10.5px; padding-left:12px; }
    .menu .ms { height:1px; background:var(--bd); margin:4px 0; }
    .menu .mh { padding:6px 9px 2px; }
    .toast { display:flex; align-items:center; gap:10px; padding:8px 12px; border-radius:8px; border:1px solid var(--bd); background:var(--card); font-size:12px; box-shadow:0 10px 24px rgba(0,0,0,0.45); }
    .runlist { display:grid; grid-template-columns:34px 1fr auto; gap:4px 10px; align-items:center; font-size:11.5px; }
    /* notes and panels */
    .note { display:inline-grid; place-items:center; min-width:18px; height:18px; padding:0 4px; border-radius:9px; background:oklch(0.82 0.16 80); color:#111; font-size:10.5px; font-weight:800; box-shadow:0 0 0 2px rgba(0,0,0,0.55); flex:0 0 auto; z-index:9; }
    .mc .note { position:absolute; right:-6px; top:-6px; }
    .notes { border-top:1px solid var(--bd); background:var(--bg); padding:14px 20px 18px; display:grid; grid-template-columns:repeat(3, 1fr); gap:10px 28px; font-size:12px; color:var(--mfg); line-height:1.45; }
    .notes div { display:flex; gap:8px; align-items:flex-start; }
    .notes .t { grid-column:1 / -1; font-size:14px; color:var(--fg); font-weight:600; display:block; }
    .panel { border:1px solid var(--bd); border-radius:10px; background:var(--page); overflow:hidden; display:flex; flex-direction:column; }
    .panel .pt { padding:9px 12px; border-bottom:1px solid var(--bd); display:flex; align-items:center; gap:8px; }
    .panel .pt b { font-size:13px; }
    .panel .pc { padding:12px; display:flex; flex-direction:column; gap:12px; flex:1; }
    .cap { font-size:11.5px; line-height:1.5; color:var(--mfg); margin:0; }
    .cap b { color:var(--fg); font-weight:600; }
    code { font-family:ui-monospace,Menlo,monospace; font-size:11px; color:var(--fg); background:oklch(0.274 0.006 286.033 / 0.5); padding:0 4px; border-radius:3px; }
    /* spec pages */
    .doc { padding:48px 56px; display:flex; flex-direction:column; gap:28px; }
    .doc h1 { margin:0; font-size:30px; font-weight:700; letter-spacing:-0.01em; }
    .doc h2 { margin:0; font-size:18px; font-weight:700; }
    table.m { border-collapse:collapse; width:100%; font-size:12px; line-height:1.45; }
    table.m th { text-align:left; font-size:9px; font-weight:700; text-transform:uppercase; letter-spacing:0.08em; color:var(--mfg); padding:7px 10px; border-bottom:1px solid var(--bd); background:oklch(0.274 0.006 286.033 / 0.3); }
    table.m td { padding:8px 10px; border-bottom:1px solid var(--bd); vertical-align:top; color:var(--mfg); }
    table.m td b { color:var(--fg); font-weight:600; }
    .tag { display:inline-flex; align-items:center; height:18px; padding:0 7px; border-radius:999px; font-size:9px; font-weight:700; letter-spacing:0.06em; text-transform:uppercase; white-space:nowrap; }
    .tag.go { background:oklch(0.5 0.15 150 / 0.3); color:#86efac; }
    .tag.ask { background:oklch(0.5 0.12 80 / 0.3); color:oklch(0.9 0.15 85); }
    .n { display:inline-flex; align-items:center; justify-content:center; min-width:22px; height:20px; padding:0 5px; border-radius:999px; background:var(--muted); color:var(--fg); font-size:10px; font-weight:700; }
    .box { border:1px solid var(--bd); border-radius:8px; background:var(--card); padding:12px 14px; }
    .box p { margin:0; font-size:12px; line-height:1.5; color:var(--mfg); } .box p + p { margin-top:6px; } .box b { color:var(--fg); font-weight:600; }
    .box.key { border-color:oklch(0.45 0.16 300); background:oklch(0.27 0.09 302 / 0.25); }
`

const boards = {}
function board(file, title, body, { w = 1440, h, narrow = false }) {
  const html = `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>${title}</title>
  <style>${CSS}
  </style>
</head>
<body>
<div class="app${narrow ? ' narrow' : ''}" style="min-height:${h}px">
${body}
</div>
</body>
</html>
`
  writeFileSync(OUT + file, html)
  boards[file] = { title, w, h }
}

// ── chrome ────────────────────────────────────────────────────────────────────
const rail = `<div class="rail"><i></i><i></i><i></i><i></i><i></i><i></i><i class="on"></i><i></i><i></i><i></i></div>`
const top = `<div class="top">Chris' DMX Controller v7<span class="sp"></span><span class="chip" style="background:#0f3a1f;border-color:#2f7a45;color:#86efac;height:28px;font-size:12px">Connected</span></div>`
const showhead = (warn) => `<div class="showhead${warn ? ' warn' : ''}"><span class="crumb">Projects › Sleeping Beauty › <b>Show</b></span><span class="sp"></span><span class="btn sm">⤢</span><span class="seg"><span>Programmer</span><span class="on">Show</span><span>Prompt Book</span><span>Busk</span></span><span class="btn sm pri">${warn ? '■ Stop' : '▶ Start'}</span>${warn ? '<span class="lbl" style="color:var(--amber)">Unlocked</span><span class="btn sm">🔓 L</span>' : ''}</div>`
const showbar = `<div class="showbar"><div class="tile"><span class="lbl">Blackout</span><b>DBO</b></div><div class="tile"><span class="lbl">M1 · Master 1</span><b class="mono">120</b></div><div class="tile" style="flex:1"><span class="lbl" style="color:var(--green)">Live · Act 1 · fading 40%</span><span><b class="mono">Q9</b> Snooze front of scrim · change to market <span class="dim">→ next</span> <b class="mono">Q10</b> Scrim out · market square</span><i style="position:absolute; left:0; bottom:0; height:2px; width:40%; background:var(--green)"></i></div><span class="btn" style="height:44px">◀ BACK</span><div class="go">GO</div></div>`
const viewSeg = (view) => `<span class="seg">${['Cards', 'Table', 'Timeline'].map((v) => `<span class="${v.toLowerCase() === view ? 'on' : ''}">${v}</span>`).join('')}</span>`
const stackhead = (warn, view, mark = '') => `<div class="tabsstrip"><span class="on">Act 1 <span class="dim">15</span></span><span>Act 2 <span class="dim">14</span></span></div>
<div class="stackhead${warn ? ' warn' : ''}"><span class="btn">← Stacks</span><b>Act 1</b><span class="dim">13 cues · 2 markers</span><span class="sp"></span>${mark}${viewSeg(view)}<span class="btn">⚡ Record into Act 1</span><span class="btn">Separator</span><span class="btn">Stack settings…</span></div>`
const notes = (title, items) => `<div class="notes"><div class="t">${title}</div>${items.map(([n, t]) => `<div><span class="note">${n}</span><span>${t}</span></div>`).join('')}</div>`
const note = (n) => `<span class="note">${n}</span>`
const kc = (kind) => `var(--${kind})`

// ── the timeline grid ─────────────────────────────────────────────────────────
// A column: { id, kind: look | pal | fx | scen, name, sub, sw?, w }. A row: { mk } or a cue
// { q, name, fade, follow, st: live | next | done, chips, prog, cells: { [colId]: spec } }.
// Layer spec { r, g: [glyphs], off, ghost, rel, n }; effect spec { k: run key, g, ch, n };
// scenery spec: the change's text, or { t, n }.
const GROUPS = { look: 'Looks', pal: 'Palettes', fx: 'Effects', scen: 'Scenery' }

const LOOKS = [
  { id: 'L1', kind: 'look', name: 'Scrim · opaque', sub: 'own fixtures', w: 84 },
  { id: 'L2', kind: 'look', name: 'Front of scrim', sub: 'ADV1 Robes', w: 84 },
  { id: 'L3', kind: 'look', name: 'Shehds colour', sub: 'own fixtures', w: 84 },
  { id: 'L4', kind: 'look', name: 'Special · Snooze SR', sub: 'own fixtures', w: 84 },
]
const PALS = [
  { id: 'P1', kind: 'pal', name: 'Lavender', sub: 'LX1 Orbits', sw: '#8f7bff', w: 84 },
  { id: 'P2', kind: 'pal', name: 'Amber', sub: 'LX1 Orbits', sw: '#ffb070', w: 84 },
  { id: 'P3', kind: 'pal', name: 'Throne DS', sub: 'ADV1 Robes', w: 84 },
]
const FXS = [
  { id: 'E1', kind: 'fx', name: 'Pulse · Dimmer', sub: 'LX1 Orbits', w: 92 },
  { id: 'E2', kind: 'fx', name: 'Colour Cycle', sub: 'Front Profiles', w: 92 },
]
const SCEN = [
  { id: 'S1', kind: 'scen', name: 'House tabs', sub: 'drawn drape', w: 92 },
  { id: 'S2', kind: 'scen', name: 'SB Scrim', sub: 'drawn drape', w: 92 },
]
const ALL = [...LOOKS, ...PALS, ...FXS, ...SCEN]
const C = Object.fromEntries(ALL.map((c) => [c.id, c]))

const ROWS = [
  { q: 'Q0', name: 'Pre-show · house lights', fade: 'SNAP', st: 'done', chips: '<span class="chip shared">2 stacks</span>', cells: { S1: 'Closed', S2: 'Closed' } },
  { mk: 'Act 1 Scene 1 · Royal Palace' },
  { q: 'Q1', name: 'Tabs out · Snooze in front of scrim', fade: 'SNAP', st: 'done', cells: { L1: { r: 2, g: ['60%', 'S'] }, L2: { r: 4 }, L3: { r: 3 }, L4: { r: 5 }, S1: 'Drawn · 4 s' } },
  { q: 'Q2', name: 'Scrim out · palace ballroom', fade: '3 s', st: 'done', cells: { L3: { r: 1 }, P1: { r: 2 }, E1: { k: 'a', g: ['½ beat'] }, S2: 'Drawn · 6 s' } },
  { q: 'Q3', name: 'Ballroom · the guests arrive', fade: '2 s', st: 'done', cells: { L3: { r: 1 }, P1: { r: 2 }, P3: { r: 3 }, E1: { k: 'a' } } },
  { q: 'Q4', name: 'Waltz build', fade: '2 s', follow: '4 s', st: 'done', cells: { L3: { r: 1 }, P1: { r: 2 }, P3: { r: 3 }, E1: { k: 'a', ch: '1 beat' } } },
  { q: 'Q5', name: 'Christening', fade: '3 s', st: 'done', cells: { L3: { r: 1, g: ['C'] }, P2: { r: 4 }, P3: { r: 3 }, E1: { k: 'a' } } },
  { q: 'Q6', name: 'Carabosse arrives', fade: '1 s', st: 'done', cells: { L4: { r: 2, g: ['⏱'] }, E1: { k: 'a' }, E2: { k: 'b' } } },
  { q: 'Q7', name: 'The curse', fade: 'SNAP', st: 'done', cells: { L4: { r: 2 }, E2: { k: 'c' } } },
  { q: 'Q8', name: 'Blackout · scrim in', fade: 'SNAP', st: 'done', cells: { S2: 'Closed · 6 s' } },
  { mk: 'Act 1 Scene 2 · Market Square' },
  { q: 'Q9', name: 'Snooze front of scrim · change to market', fade: 'SNAP', st: 'live', prog: 40, chips: '<span class="chip stale">Edited</span><span class="note">4</span>', cells: { L1: { r: 2, g: ['60%', 'S'] }, L2: { r: 3 }, L4: { r: 4 } } },
  { q: 'Q10', name: 'Scrim out · market square', fade: '4 s', st: 'next', cells: { L3: { r: 1 }, P2: { r: 2 }, E1: { k: 'd' }, S2: 'Drawn · 6 s' } },
  { q: 'Q11', name: 'Market dance', fade: '2 s', cells: { L3: { r: 1 }, P2: { r: 2 }, E1: { k: 'd' }, E2: { k: 'e' } } },
  { q: 'Q12', name: 'Spinning wheel', fade: '3 s', cells: { L3: { r: 1, g: ['50%'] }, P1: { r: 2 }, L4: { r: 3, off: true }, E2: { k: 'e' } } },
]

const spec = (row, id) => row && !row.mk ? row.cells?.[id] : undefined
const isFilled = (s) => s !== undefined && !(typeof s === 'object' && s.rel)
// Two vertically adjacent cells draw as one run: any two filled layer cells, effect cells sharing a
// run key. A ghost joins its own kind only, so a drag reads as an extension of what it drags.
function joins(col, a, b) {
  if (!isFilled(a) || !isFilled(b)) return false
  if (col.kind === 'scen') return false
  if (col.kind === 'fx') return a.k === b.k
  return !!a.ghost === !!b.ghost || (!a.ghost && b.ghost)
}

function layerInner(col, s) {
  if (s.rel) return '<i class="gl" style="color:var(--stale)">released</i>'
  const sw = col.sw ? `<i class="sw" style="background:${col.sw}"></i>` : ''
  const gl = (s.g ?? []).map((g, i) => `<i class="gl${i === 0 ? ' r' : ''}${g === 'S' ? ' st' : ''}">${g}</i>`).join('')
  return `${sw}<i class="rk">${s.ghost ? '+' : s.r}</i>${gl}${s.off ? '<i class="gl r">off</i>' : ''}`
}
function fxInner(s, startsRun, prevSpec) {
  const restart = startsRun && prevSpec && isFilled(prevSpec) && prevSpec.k !== s.k
  const gl = s.ch ? `<i class="gl r fxc">◆ ${s.ch}</i>` : (s.g ?? []).map((g, i) => `<i class="gl${i === 0 ? ' r' : ''}">${g}</i>`).join('')
  return `${restart ? '<i class="gl fxc">↺</i>' : ''}${startsRun ? '<i class="rk">∿</i>' : ''}${gl}`
}

// Scenery tracks: after a change, later cues hold its state until the next change.
function trackedAt(rows, i, id) {
  for (let j = i - 1; j >= 0; j--) { if (spec(rows[j], id) !== undefined) return true }
  return false
}

function cellHtml(col, rows, i, opts) {
  const row = rows[i]
  const g0 = opts.firstOfGroup.has(col.id) ? ' g0' : ''
  const inert = opts.inert ? ' inert' : ''
  const s = spec(row, col.id)
  // neighbours, looking across marker rows
  const prevIdx = (() => { for (let j = i - 1; j >= 0; j--) if (!rows[j].mk) return j; return -1 })()
  const nextIdx = (() => { for (let j = i + 1; j < rows.length; j++) if (!rows[j].mk) return j; return -1 })()
  const prev = prevIdx >= 0 ? spec(rows[prevIdx], col.id) : undefined
  const isSel = (j) => j >= 0 && !rows[j].mk && opts.sel?.has(`${rows[j].q}:${col.id}`)
  const sel = isSel(i) ? ` sel${isSel(prevIdx) && prevIdx === i - 1 ? ' sp' : ''}${isSel(nextIdx) && nextIdx === i + 1 ? ' sn' : ''}` : ''
  const next = nextIdx >= 0 ? spec(rows[nextIdx], col.id) : undefined
  let inner = ''
  if (row.mk) {
    // a marker row: a run passes through, scenery tracking passes through
    const before = (() => { for (let j = i - 1; j >= 0; j--) if (!rows[j].mk) return spec(rows[j], col.id); return undefined })()
    const after = (() => { for (let j = i + 1; j < rows.length; j++) if (!rows[j].mk) return spec(rows[j], col.id); return undefined })()
    if (col.kind === 'scen') inner = trackedAt(rows, i, col.id) ? '<i class="trk"></i>' : ''
    else if (joins(col, before, after)) inner = `<span class="run ${col.kind}"></span>`
    return `<div class="mc${g0}" style="width:${col.w}px">${inner}</div>`
  }
  const mark = s && typeof s === 'object' && s.n ? note(s.n) : ''
  if (col.kind === 'scen') {
    if (s !== undefined) {
      const t = typeof s === 'object' ? s.t : s
      inner = `${trackedAt(rows, i, col.id) ? '<i class="trk"></i>' : ''}<span class="run scen">${t}</span>`
    } else if (trackedAt(rows, i, col.id)) inner = '<i class="trk"></i>'
    return `<div class="mc${g0}${sel}${inert}" style="width:${col.w}px">${inner}${typeof s === 'object' && s.n ? note(s.n) : ''}${opts.extra?.[`${row.q}:${col.id}`] ?? ''}</div>`
  }
  if (s !== undefined) {
    const startsRun = !joins(col, prev, s)
    const endsRun = !joins(col, s, next)
    const cls = ['run', s.rel ? '' : col.kind, startsRun || s.rel ? 's' : '', endsRun || s.rel ? 'e' : '', s.off ? 'off' : '', s.ghost ? 'ghost' : '', s.rel ? 'rel' : ''].filter(Boolean).join(' ')
    const body = col.kind === 'fx' ? fxInner(s, startsRun, prev) : layerInner(col, s)
    const brk = col.kind === 'fx' && startsRun && prev && isFilled(prev) && prev.k !== s.k ? '<i class="brk"></i>' : ''
    const dia = col.kind === 'fx' && s.ch ? '<i class="dia"></i>' : ''
    inner = `<span class="${cls}">${body}</span>${brk}${dia}`
  }
  return `<div class="mc${g0}${sel}${inert}" style="width:${col.w}px">${inner}${mark}${opts.extra?.[`${row.q}:${col.id}`] ?? ''}</div>`
}

function grid({ cols, rows, sel = [], mini = false, nameW = 176, inert = false, extra = {}, selCols = [], fade = false, headMarks = {} }) {
  const left = mini ? `14px 40px ${nameW}px` : `14px 18px 46px ${nameW}px 48px 56px`
  const firstOfGroup = new Set()
  const groups = []
  cols.forEach((c, i) => {
    if (i === 0 || cols[i - 1].kind !== c.kind) { firstOfGroup.add(c.id); groups.push({ kind: c.kind, w: 0, n: 0 }) }
    groups[groups.length - 1].w += c.w
    groups[groups.length - 1].n += 1
  })
  const opts = { firstOfGroup, sel: new Set(sel), inert, extra }
  const leftCell = (inner, style = '') => `<div class="left" style="grid-template-columns:${left}; ${style}">${inner}</div>`
  const gh = `<div class="tr gh">${leftCell('', 'border-right-color:transparent')}${groups.map((g) => `<div class="gcell" style="width:${g.w}px"><i style="background:${kc(g.kind)}"></i><span class="lbl">${GROUPS[g.kind]} · ${g.n}</span></div>`).join('')}</div>`
  const heads = mini ? '<span></span><span class="hl">Cue</span><span class="hl">Name</span>' : '<span></span><span></span><span class="hl">Cue</span><span class="hl">Name</span><span class="hl">Fade</span><span class="hl">Follow</span>'
  const ch = `<div class="tr ch">${leftCell(heads)}${cols.map((c) => `<div class="hc${firstOfGroup.has(c.id) ? ' g0' : ''}${selCols.includes(c.id) ? ' sel' : ''}" style="width:${c.w}px"><b>${c.sw ? `<i style="background:${c.sw}"></i>` : ''}<span style="overflow:hidden;text-overflow:ellipsis">${c.name}</span>${headMarks[c.id] ?? ''}</b><span>${c.sub}</span></div>`).join('')}</div>`
  const body = rows.map((r, i) => {
    if (r.mk) return `<div class="tr mk">${leftCell(`<span class="mklab">${r.mk}</span>`)}${cols.map((c) => cellHtml(c, rows, i, opts)).join('')}</div>`
    const lead = mini
      ? `<span class="pip ${r.st ?? ''}"></span><span class="num">${r.q}</span><span class="nm"><span>${r.name}</span>${r.chips ?? ''}</span>`
      : `<span class="dim" style="font-size:11px">⋮</span><span class="pip ${r.st ?? ''}"></span><span class="num">${r.q}</span><span class="nm"><span>${r.name}</span>${r.chips ?? ''}</span><span class="mono">${r.fade}</span><span class="mono dim">${r.follow ? `<span style="color:var(--blue);font-size:9px">AUTO</span> ${r.follow}` : '—'}</span>`
    return `<div class="tr ${r.st === 'live' || r.st === 'next' ? r.st : ''}">${r.st === 'live' || r.st === 'next' ? '<i class="edge"></i>' : ''}${leftCell(lead + (r.prog ? `<i class="prog" style="width:${r.prog}%"></i>` : ''))}${cols.map((c) => cellHtml(c, rows, i, opts)).join('')}</div>`
  }).join('')
  return `<div class="tl">${gh}${ch}${body}${fade ? '<i class="fadeR"></i>' : ''}</div>`
}

const legend = `<div class="foot"><span>13 cues · 2 markers · 11 columns</span>
<span class="k"><i style="background:rgba(92,209,193,0.3); box-shadow:inset 2px 0 0 var(--look)"></i>Look</span>
<span class="k"><i style="background:rgba(212,212,216,0.2); box-shadow:inset 2px 0 0 var(--pal)"></i>Palette</span>
<span class="k"><i style="background:rgba(185,140,255,0.3); box-shadow:inset 2px 0 0 var(--fx)"></i>Effect run</span>
<span class="k"><i style="background:rgba(224,184,120,0.3)"></i>Scenery change, <span style="display:inline-block;width:2px;height:10px;background:rgba(224,184,120,0.6)"></span> tracked</span>
<span><span class="rk">2</span> rank in the cue, 1 at the bottom</span>
<span><span style="color:var(--fx)">◆</span> changed in place · <span style="color:var(--fx)">↺</span> restarts</span>
<span><span class="gl st">S</span> stomp · <span class="gl">60%</span> amount · <span class="gl">C</span> mask · <span class="gl">⏱</span> timed</span>
<span style="margin-left:auto">${'<kbd>⏎</kbd>'} on/off · <kbd>⌫</kbd> off · <kbd>⌘D</kbd> fill down</span></div>`

// ── Timeline ──────────────────────────────────────────────────────────────────
{
  const sel = ['Q2:P1', 'Q3:P1', 'Q4:P1']
  const rowB = `<div class="rowb">
<span class="lbl">Show</span>${LOOKS.length ? '<span class="chip on"><i style="background:var(--look)"></i>Looks 4</span><span class="chip on"><i style="background:var(--pal)"></i>Palettes 3</span><span class="chip on"><i style="background:var(--fx)"></i>Effects 2</span><span class="chip on"><i style="background:var(--scen)"></i>Scenery 2</span>' : ''}
<span class="sep"></span><span class="seg sm"><span class="on">All</span><span>Intensity</span><span>Colour</span><span>Position</span><span>Beam</span></span>
<span class="sep"></span><span class="chip">◎ Selection only</span><span class="dim" style="font-size:11px">desk selection: LX1 Orbits · 4 heads</span>${note(2)}
<span class="sp"></span><span class="btn sm">+ Column</span></div>`
  const selbar = `<div class="selbar"><b>3 cells</b><span class="chip"><i style="background:#8f7bff"></i>Lavender · LX1 Orbits</span><span class="dim">Q2 to Q4, all on</span>${note(5)}<span class="sp"></span><span class="btn sm ask">On</span><span class="btn sm">Off</span><span class="btn sm">Fill ↓</span><span class="btn sm">Replace with…</span><span class="btn sm">Settings…</span><span class="sep"></span><span class="btn sm ghost">Deselect</span></div>`
  board('Timeline.dc.html', 'Timeline · Act 1, running', `
${top}
<div class="shell">${rail}
<div class="main">
${showhead(true)}
${showbar}
${stackhead(true, 'timeline', note(1))}
${rowB}
${selbar}
${grid({ cols: ALL, rows: ROWS, sel, extra: { 'Q1:L1': note(3), 'Q4:E1': note(6), 'Q7:E2': note(7), 'Q2:S1': note(8) }, headMarks: {} })}
<div class="handle"><i></i></div>
<div class="row" style="height:30px; padding:0 12px; font-size:11.5px; background:var(--bg)"><span class="dim">The cue editor pane is folded: the selection spans three cues.</span>${note(9)}</div>
${legend}
</div></div>
${notes('Timeline mode of Act 1, show running and unlocked. Q9 is live and fading in; Q10 is next. Lavender on LX1 Orbits is selected in Q2 to Q4.', [
    ['1', 'Timeline is a third mode beside Cards and Table, at <code>/show/stacks/:id/timeline</code>. It keeps the Table\'s rows, row drag, edit lock and bottom pane (D2).'],
    ['2', 'Row B filters. The kind chips hide whole groups. The family filter keeps palette, effect and Look columns that touch a family. <i>Selection only</i> keeps columns whose targets meet the desk selection (D11).'],
    ['3', 'A cell is that cue\'s own layer (D4). The small number is its rank in the cue, 1 at the bottom. Glyphs mark non-default settings: <b>S</b> stomp, <b>60%</b> amount, <b>C</b> a Colour-only mask, <b>⏱</b> a timed layer, <b>off</b> disabled.'],
    ['4', 'Q9 is live: a green edge and a fade bar along the row\'s foot, as the show bar\'s tile draws it. <i>Edited</i> is show-cue-editing\'s D4: the cue changed since its GO, and selecting the row offers <i>Update stage…</i>.'],
    ['5', 'The selection bar names the column and the range. Verbs apply per cell: <i>On</i> is greyed because every cell is already on. <i>Replace with…</i> and <i>Settings…</i> are the two popovers on the Editing board.'],
    ['6', 'One effect run, Q2 to Q6, sharing a run key (D7). At Q4 the Pulse slows to one beat: the GO edits it in place with its phase kept, marked ◆ (D8).'],
    ['7', 'Two keys meet at Q6/Q7: a dotted break and ↺. Colour Cycle restarts at Q7\'s GO. This is how effects copied between cues before the run key read: <i>Join run</i> fixes it.'],
    ['8', 'Scenery: a change is a chip, and a thin line carries the state down until the next change (D9). The house tabs hold <i>Drawn</i> from Q1 to the end; SB Scrim tracks <i>Closed</i> from Q0 through the marker.'],
    ['9', 'The pane follows one selected cell to its cue, on the column\'s tab (call C2). A range over several cues folds it, as the Table does for several rows.'],
  ])}
`, { h: 1210 })
}

// ── Editing ───────────────────────────────────────────────────────────────────
const panel = (w, title, inner, cap, extra = '') => `<div class="panel" style="width:${w}px; ${extra}"><div class="pt"><b>${title}</b></div><div class="pc">${inner}${cap ? `<p class="cap">${cap}</p>` : ''}</div></div>`
const sub = (ids) => ids.map((id) => ({ ...C[id], w: 74 }))
const cue = (q, name, cells, more = {}) => ({ q, name, cells, ...more })
{
  // (a) one cell, the press rule
  const a = grid({ mini: true, nameW: 104, cols: sub(['P1', 'P2', 'P3']), rows: [
    cue('Q3', 'Ballroom', { P1: { r: 2 }, P3: { r: 3 } }),
    cue('Q4', 'Waltz build', { P1: { r: 2 }, P3: { r: 3 } }),
    cue('Q5', 'Christening', { P1: { rel: true }, P2: { r: 4, n: 1 }, P3: { r: 3 } }),
    cue('Q6', 'Carabosse arrives', {}),
  ], sel: ['Q5:P2'] })
  // (b) drag a run's end
  const b = grid({ mini: true, nameW: 104, cols: sub(['L3', 'L4', 'P3']), rows: [
    cue('Q1', 'Tabs out', { L3: { r: 3 }, L4: { r: 5 } }),
    cue('Q2', 'Scrim out', { L3: { r: 1 } }),
    cue('Q3', 'Ballroom', { L3: { r: 1 }, P3: { r: 3 } }),
    cue('Q4', 'Waltz build', { L3: { ghost: true }, P3: { r: 3 } }),
    cue('Q5', 'Christening', { L3: { ghost: true }, P3: { r: 3 } }),
    cue('Q6', 'Carabosse', { L3: { ghost: true }, L4: { r: 2 } }),
  ], extra: { 'Q6:L3': '<i class="grab"></i>' } })
  // (c) a range
  const cRows = [
    cue('Q9', 'Snooze front of scrim', { L1: { r: 2, g: ['60%', 'S'] }, L2: { r: 3 }, L4: { r: 4 } }),
    cue('Q10', 'Scrim out · market', { L4: { r: 3 } }),
    cue('Q11', 'Market dance', { L2: { r: 2 } }),
    cue('Q12', 'Spinning wheel', { L4: { r: 3 } }),
  ]
  const c = grid({ mini: true, nameW: 104, cols: sub(['L1', 'L2', 'L4']), rows: cRows, sel: ['Q10:L1', 'Q11:L1', 'Q12:L1', 'Q10:L2', 'Q11:L2', 'Q12:L2'] })
  const cBar = `<div class="selbar" style="border:1px solid var(--bd); border-radius:8px; background:var(--bg)"><b>6 cells</b><span class="dim">2 columns · Q10 to Q12 · 1 on</span><span class="sp"></span><span class="btn sm">On</span><span class="btn sm">Off</span><span class="btn sm">Fill ↓</span><span class="btn sm">Replace…</span></div>`

  // (d) replace across range
  const d = `<div class="pop"><div class="ph"><b>Replace in Q2 to Q5</b><span class="sp"></span><span class="dim">4 cells</span></div><div class="pb">
    <div class="row"><span class="chip"><i style="background:#8f7bff"></i>Lavender · LX1 Orbits</span><span class="dim">with</span></div>
    <div class="row"><span class="field sans" style="flex:1"><i class="sw" style="background:#ffb070"></i>Amber<span class="sp"></span><span class="dim">▾</span></span><span class="field sans" style="width:130px">LX1 Orbits<span class="sp"></span><span class="dim">▾</span></span></div>
    <div class="row"><span class="cb">✓</span><span>Keep each layer's rank and settings</span></div>
    <div class="readout">Q2, Q3 and Q4 swap their Lavender layer for Amber in place. Q5 already holds Amber on LX1 Orbits, so its Lavender comes off and Amber stays as it is. Nothing is sent to the stage.</div>
  </div><div class="pf"><span class="btn sm">Cancel</span><span class="btn sm pri">Replace 4</span></div></div>`
  // (e) one layer's settings
  const e = `<div class="pop"><div class="ph"><b>Q1 · Scrim · opaque</b><span class="chip">rank 2 of 5</span><span class="sp"></span><span class="tog"></span></div><div class="pb">
    <div class="row"><span class="lbl" style="width:62px">Amount</span><div class="slider" style="flex:1"><i style="width:60%"></i><b style="left:calc(60% - 8px)"></b></div><span class="field" style="width:62px">60<span class="dim">%</span></span></div>
    <div class="row"><span class="lbl" style="width:62px">Blend</span><span class="seg sm"><span class="on">Replace</span><span>Max</span><span>Min</span><span>Multiply</span></span></div>
    <div class="row"><span class="lbl" style="width:62px">Mask</span><span class="chip on">Intensity</span><span class="chip on">Colour</span><span class="chip on">Position</span><span class="chip on">Beam</span></div>
    <div class="row"><span class="lbl" style="width:62px">Stomp</span><span class="tog"></span><span class="dim" style="font-size:11px">lower layers' effects stop under it</span></div>
    <div class="row"><span class="lbl" style="width:62px">Timing</span><span class="dim">Delay</span><span class="field" style="width:52px"><span class="dim">—</span></span><span class="dim">Every</span><span class="field" style="width:52px"><span class="dim">—</span></span><span class="dim">±</span><span class="field" style="width:52px"><span class="dim">—</span></span></div>
    <div class="row"><span class="lbl" style="width:62px">Masters</span><span class="chip">Speed · M1</span><span class="chip">Rate · none</span></div>
    <div class="readout">Writes Q1's layer and sends nothing. Its order among Q1's five layers changes in the cue's Looks tab.</div>
  </div><div class="pf"><span class="btn sm ghost">Open Look</span><span class="sp"></span><span class="btn sm">Copy down to Q9…</span></div></div>`
  // (f) an effect run
  const runRow = (q, t, m = '') => `<span class="num">${q}</span><span>${t}</span><span class="dim" style="font-size:10.5px">${m}</span>`
  const f = `<div class="pop"><div class="ph"><span style="color:var(--fx)">∿</span><b>Pulse · Dimmer · LX1 Orbits</b><span class="sp"></span><span class="chip">run Q2 to Q6</span></div><div class="pb">
    <div class="runlist">${runRow('Q2', '½ beat · M1 · 20 to 100%', 'starts')}${runRow('Q3', 'same', 'continues')}${runRow('Q4', '<b style="color:var(--fx)">◆ 1 beat</b> · M1 · 20 to 100%', 'edited in place')}${runRow('Q5', 'same as Q4', 'continues')}${runRow('Q6', 'same as Q4', 'continues')}</div>
    <div class="readout">One run key across five cues. Each GO inside the run hands the running Pulse to the next cue with its phase kept; Q4's GO also applies its new speed.</div>
  </div><div class="pf"><span class="btn sm">Break run at Q4</span><span class="btn sm ask">Join run above</span><span class="sp"></span><span class="btn sm">Edit Q4's effect…</span></div></div>`

  // (g) scenery
  const g = grid({ mini: true, nameW: 104, cols: sub(['S1', 'S2']), rows: [
    cue('Q1', 'Tabs out', { S1: 'Drawn · 4 s' }),
    cue('Q2', 'Scrim out', { S2: 'Drawn · 6 s' }),
    cue('Q7', 'The curse', {}),
    cue('Q8', 'Blackout · scrim in', { S2: 'Closed · 6 s' }),
    cue('Q9', 'Snooze front of scrim', {}),
  ], sel: ['Q8:S2'] })
  const gPop = `<div class="pop"><div class="ph"><b>SB Scrim in Q8</b><span class="dim">drawn drape</span><span class="sp"></span><span class="dim">change</span></div><div class="pb">
    <div class="row"><span class="seg sm"><span class="on">Closed</span><span>Half</span><span>Drawn</span></span><span class="dim">over</span><span class="field" style="width:60px">6<span class="dim">s</span></span></div>
    <div class="readout">Clearing the change makes Q8 track <i>Drawn</i> from Q2, and the scrim stays out through Q9.</div>
  </div><div class="pf"><span class="btn sm">Clear change</span></div></div>`
  // (h) filters and + Column
  const h = `<div class="rowb" style="height:auto; flex-wrap:wrap; padding:6px 10px; border:1px solid var(--bd); border-radius:8px; background:var(--bg)"><span class="seg sm"><span>All</span><span>Intensity</span><span class="on">Colour</span><span>Position</span><span>Beam</span></span><span class="chip on">◎ Selection only</span><span class="sp"></span><span class="btn sm on">+ Column</span></div>
  <div class="menu" style="min-width:0">
    <div class="mh"><div class="field sans" style="height:26px"><span class="dim">⌕</span>blue</div></div>
    <div class="mh lbl">Looks</div><div class="mi">Blue hour <small>own fixtures</small></div>
    <div class="mh lbl">Palettes</div><div class="mi on"><i class="sw" style="background:#4d7dff"></i>Deep blue <small>on LX1 Orbits, the selection</small></div><div class="mi"><i class="sw" style="background:#4d7dff"></i>Deep blue <small>on a target…</small></div>
    <div class="mh lbl">Effects</div><div class="mi">Colour Fade · Colour <small>on LX1 Orbits</small></div>
  </div>`
  // (i) live and locked
  const iRows = [
    cue('Q8', 'Blackout · scrim in', {}, { st: 'done' }),
    cue('Q9', 'Snooze front of scrim', { L1: { r: 2, g: ['60%'] }, L4: { r: 4 } }, { st: 'live', prog: 70, chips: '<span class="chip stale">Edited</span>' }),
    cue('Q10', 'Scrim out · market', { L3: { r: 1 }, L4: { r: 3 } }, { st: 'next' }),
  ]
  const iGrid = grid({ mini: true, nameW: 112, cols: sub(['L1', 'L3', 'L4']), rows: iRows, sel: ['Q9:L4'] })
  const iBar = `<div class="selbar" style="border:1px solid var(--bd); border-radius:8px; background:var(--bg)"><b>1 cell</b><span class="dim">Q9 is live</span><span class="sp"></span><span class="btn sm">Off</span><span class="btn sm">Settings…</span><span class="btn sm stale">Update stage…</span></div>`
  const iLocked = `<div class="selbar" style="border:1px solid var(--bd); border-radius:8px; background:var(--bg)"><span>🔒</span><span class="dim">Locked: cells are read-only, the filters and the selection still work.</span><span class="sp"></span><kbd>L</kbd><span class="dim">to edit</span></div>`

  const W = 448
  board('Editing.dc.html', 'Editing · cells, runs, ranges and the popovers', `
<div style="padding:24px; display:flex; flex-direction:column; gap:20px">
<div class="row" style="gap:24px; align-items:stretch">
${panel(W, 'Turn one cell on: a press', `${a}<div class="toast"><span>Amber on LX1 Orbits in Q5. Lavender released there.</span><span class="sp"></span><span class="btn sm">Undo</span></div>`, 'A palette cell is a press of that palette on that target in that cue (D5). Amber and Lavender are both Colour, so turning Amber on takes Lavender off LX1 Orbits in Q5, dashed red for a beat and named in the toast. The new layer goes on top, rank 4, with settings copied from the nearest Amber cell, else the defaults (D6).')}
${panel(W, 'Drag a run\'s end', `<div style="position:relative">${b}<span class="scope" style="left:150px; top:262px">Shehds colour on in Q4 to Q6</span></div>`, 'The handle on a run\'s last cell drags it down or up. Each cell it crosses becomes that cue\'s own layer, copying the dragged cell\'s settings; dragging up takes cells off. The cells stay separate layers, so lighting still does not track (D4).')}
${panel(W, 'Select a range', `${c}${cBar}`, 'A marquee or shift-click selects cells across columns and cues. <i>On</i> fills the empty cells of each column from its nearest filled cell, <i>Off</i> clears them, and <i>Fill ↓</i> copies the top row\'s state of each column down. A range is one write and one Undo (call C3).')}
</div>
<div class="row" style="gap:24px; align-items:stretch">
${panel(W, 'Replace across a range', d, 'Eos\'s <i>Replace With</i> for references: one record for another over the selected cells, each layer keeping its rank, amount, mask and timing. A cue already holding the replacement keeps that layer, and the replaced one comes off (D11).')}
${panel(W, 'One layer\'s settings', e, 'Click a filled cell, or <i>Settings…</i> on a range, for the layer\'s own fields: the cue editor\'s Looks-tab row, in a popover. On a range, a field the cells disagree on shows <i>mixed</i> until it is set. A palette layer covering several targets says so, since one write changes every cell it covers.')}
${panel(W, 'An effect run', f, 'The run key makes a bar mean what it shows (D7). A parameter change inside a run is an in-place edit at that GO, ◆ in the grid (D8). <i>Break run</i> gives this cue and those below a fresh key; <i>Join run above</i> takes the key from above when the signature matches.')}
</div>
<div class="row" style="gap:24px; align-items:stretch">
${panel(W, 'Scenery cells', `${g}${gPop}`, 'Scenery already tracks, so a cell is a change or nothing (D9). The popover is the Scenery tab\'s row for that element and cue; clearing it hands the cue back to tracking.')}
${panel(W, 'Filters and + Column', h, 'With Colour and <i>Selection only</i> on, only Colour columns whose targets meet the desk selection stay. <i>+ Column</i> searches every record; a palette or effect column needs a target, which is the desk selection unless one is picked. A new column starts empty.')}
${panel(W, 'Live, and locked', `${iGrid}${iBar}${iLocked}`, 'A write to a live cue sends nothing. It marks the cue edited since GO, and the selection bar offers <i>Update stage…</i> for the live row (show-cue-editing D4). Locked, every cell is read-only and the verbs ask to unlock, as in the Table.')}
</div>
</div>
${notes('Every gesture writes stored cues and sends nothing to the stage.', [
  ['1', 'Copied settings come from the nearest cell above in the same column, else below, else the press defaults (D6). A dragged run copies from the cell dragged.'],
  ['⌘Z', 'One gesture is one Undo, however many cues it touched: a range, a drag, a replace.'],
  ['⏎', '⏎ toggles the selected cells, ⌫ turns them off and ⌘D fills down: the sheet kit\'s keys, as a typed column commits them.'],
])}
`, { h: 1660 })
}

// ── Spec ──────────────────────────────────────────────────────────────────────
const decided = (n, call, answer) => `<tr><td><span class="n">${n}</span></td><td><b>${call}</b></td><td>${answer}</td><td><span class="tag go">Decided</span></td></tr>`
const second = (n, call, rec, alt) => `<tr><td><span class="n">${n}</span></td><td><b>${call}</b></td><td>${rec}${alt ? `<br><span style="opacity:0.8">Declined: ${alt}</span>` : ''}</td><td><span class="tag go">Decided</span></td></tr>`
board('Spec.dc.html', 'Stack timeline · the brief, the survey, the calls', `
<div class="doc">
<div style="display:flex; flex-direction:column; gap:10px">
<div class="lbl">Design · 20 Oct 2026 · all eighteen calls decided</div>
<h1>A stack's timeline: which Looks, palettes, effects and scenery each cue applies</h1>
<p class="cap" style="max-width:960px; font-size:13px">The brief: <i>"a timeline view for a stack, to see and edit when looks, etc. are applied to stacks and not. It should be in a table view so that whether a look is applied or not to a cue can easily be changed."</i> Today the Table shows a count of layers per cue and the cue editor shows one cue at a time, so a question like <i>where does Lavender come off?</i> means opening cue after cue. Drawn dark and 1440 wide, like the earlier records; phone and tablet are out of scope (C6).</p>
</div>

<div style="display:grid; grid-template-columns:minmax(0,1.35fr) minmax(0,1fr); gap:24px; align-items:start">
<div style="display:flex; flex-direction:column; gap:10px">
<h2>What other desks do</h2>
<table class="m">
<tr><th style="width:96px">Desk</th><th>The view, and its shape</th><th style="width:210px">Borrowed here</th></tr>
<tr><td><b>grandMA3</b></td><td>Sequence Sheet plus Track Sheet: cue rows, settings columns, then attribute columns in one sheet. A bar marks a value stored in the cue, a thin line one tracked. The live cue is framed green and fade bars run in the row. Recipes are cooked into cues, as this desk cooks layers.</td><td>Settings left, layers right, one sheet. The bar. The live frame and the running fade.</td></tr>
<tr><td><b>ETC Eos</b></td><td>Blind Spreadsheet: cue rows across channels. A reference reads <code>CP 5</code> or its label. <i>Replace With</i> swaps one palette for another over a cue range.</td><td>References as cells. Replace across a range.</td></tr>
<tr><td><b>MagicQ</b></td><td>Cue Stack window and a Timeline window with three views of one list: sheet, cueline and a Gantt editor. Filters to the selected heads and to an attribute mask.</td><td>Selection-only and family filters.</td></tr>
<tr><td><b>Avolites Titan</b></td><td>Tracking View with an IPCGBES bank filter, legend or value per palette, <i>Set Hard</i> and <i>Delete Redundant</i>.</td><td>The family filter. Names over values.</td></tr>
<tr><td><b>Hog 4</b></td><td>Excel-like spreadsheets: drag, Thru or a whole column selects a range, Set edits all of it. The cue editor switches one grid between Value, Fade and Delay.</td><td>Range selection, one edit over it.</td></tr>
<tr><td><b>Lightkey</b></td><td>Cues built from presets plus their own properties; exclusive preset groups; <i>which cues use this preset</i>. The nearest model to this desk's.</td><td>Nothing to copy: no desk draws a cue × preset grid.</td></tr>
</table>
</div>
<div style="display:flex; flex-direction:column; gap:10px">
<h2>What the code says</h2>
<div class="box key"><p><b>Lighting does not track.</b> Each cue is a complete state; a GO is built from the incoming cue alone. Only scenery tracks (<code>SceneryResolver</code>). So <i>Lavender in Q2 to Q4</i> is three separate layers, and the grid has to make three read and edit as one.</p></div>
<div class="box"><p><b>A GO carries an effect only on its stored row's uuid and settings</b> (<code>CueHolds.continues</code>). A Look's effect continues across the cues that layer it. An ad-hoc effect copied into the next cue is another row with another uuid, so it fades out and grows back from phase 0.</p></div>
<div class="box"><p><b>The press already works on a stored cue.</b> <code>POST /cues/{cid}/layers/press</code> applies the programmer's per-target widen, narrow and replace to a cue's layers, and sends nothing.</p></div>
<div class="box"><p><b>The stack list carries counts only</b>: <code>layerCount</code> and <code>adHocEffectCount</code> per cue. The grid needs a read of its own. <code>components/sheet/</code> already gives typed keys, the marquee and row drag, and <code>CueSheet</code> uses it.</p></div>
</div>
</div>

<div style="display:flex; flex-direction:column; gap:10px">
<h2>Calls · decided with Chris, 20 Oct 2026</h2>
<table class="m">
<tr><th style="width:44px">#</th><th style="width:300px">Call</th><th>Decision</th><th style="width:80px"></th></tr>
${decided('D1', 'Which way round?', 'Cues as rows, as every desk\'s track sheet. Cue, name, fade and follow at the left, the grid to the right.')}
${decided('D2', 'Where does it live?', 'A third mode of the stack view, <i>Cards · Table · Timeline</i>, keeping the Table\'s rows, row drag, edit lock and bottom pane. A separate page, and columns added to the Table, were declined.')}
${decided('D3', 'What is a column?', 'One per record and target: a Look (its own fixtures, or a target it is filtered to), a palette on one target, an effect by type, property and targets, a scenery element. Grouping palettes into family columns, and Looks only, were declined.')}
${decided('D4', 'A Look across several cues?', 'Each cell stays that cue\'s own layer; adjacent cells draw as one bar, and drag, fill and range on/off edit them. Adding tracking to layers was declined.')}
${decided('D5', 'Turning a palette cell on', 'Follows the press rule: other palettes of its family come off the same heads in that cue, and the grid shows it.')}
${decided('D6', 'Settings of a new cell, and order', 'Copied from the nearest cell above in the column, else below, else the press defaults. A new layer goes on top; each cell shows its rank.')}
${decided('D7', 'Effects', 'Columns, like layers. Ad-hoc effect rows gain a run key that GO adopts on, so a bar continues across its GOs. A summary cell, and steering runs into Looks, were declined.')}
${decided('D8', 'A parameter change mid-run', 'Edited in place at that GO, phase kept, marked ◆. Crossfading at the change was declined.')}
${decided('D9', 'Scenery', 'A column per element: a change is a cell, the tracked state a line, both editable.')}
${decided('D10', 'What else shows', 'Fade and follow; the live cue framed with its fade running; the next cue; <i>Edited since GO</i>. Own values, events and speed masters were left out.')}
${decided('D11', 'In the first version', 'Filter by the desk selection and by family; <i>Replace</i> across a range; a settings popover per cell. A picker of unused records with use counts was left out; a plain <i>+ Column</i> stays.')}
</table>
</div>

<div style="display:flex; flex-direction:column; gap:10px">
<h2>Second round · decided with Chris, 20 Oct 2026, each as recommended</h2>
<table class="m">
<tr><th style="width:44px">#</th><th style="width:300px">Call</th><th>Decision</th><th style="width:80px"></th></tr>
${second('C1', 'Its name', '<b>Timeline</b>, the word the brief used and one the desk has not spent.', '<i>Track sheet</i>, which MA and Eos users know but which promises tracking this desk does not do.')}
${second('C2', 'The bottom pane', '<b>One selected cell opens the pane on its cue, at the column\'s tab</b> (Looks, FX or Scenery). A range over several cues folds it.', 'the pane only on a selected row, as in the Table.')}
${second('C3', 'How a range writes', '<b>One stack route, one transaction, one Undo</b> for any gesture, all or nothing.', 'one write per cue, with partial failures to report.')}
${second('C4', 'Copies of a cue', '<b>A fresh run key</b> on <i>Duplicate</i> and <i>Copy from another show</i>: a copy lands at the end of a stack, away from the run.', 'keep the key, so a GO between copies continues the effect.')}
${second('C5', 'Effects stored before the run key', '<b>No migration.</b> Neighbours with the same signature draw as broken runs (↺), and <i>Join run</i> fixes the ones that should continue.', 'join identical neighbours once, at load.')}
${second('C6', 'Narrow screens', '<b>Timeline offered from 1100 px.</b> Below that the switcher hides it and the Table stays.', 'a scrolled grid on a tablet.')}
${second('C7', 'Column order', '<b>First use, grouped by kind</b>; a drag reorders for this window only (sessionStorage), so nothing is stored or synced.', 'a stored order per stack.')}
</table>
</div>
</div>
`, { w: 1240, h: 1880, narrow: true })

// ── Model ─────────────────────────────────────────────────────────────────────
const lane = (go, kind, text, cls) => `<div class="row" style="gap:14px; padding:10px 0; border-bottom:1px solid var(--bd2)"><span class="num" style="width:96px">${go}</span><span class="chip ${cls}" style="width:150px; justify-content:center">${kind}</span><span class="cap" style="flex:1">${text}</span></div>`
board('Model.dc.html', 'Stack timeline · columns, the run key, reads and writes', `
<div class="doc">
<div style="display:flex; flex-direction:column; gap:10px">
<div class="lbl">Design · 20 Oct 2026 · the data behind the boards</div>
<h1>Columns, the run key, and one route for every write</h1>
<p class="cap" style="max-width:960px; font-size:13px">The grid is derived: nothing stores a column. The client reads every cue's layers, effects and scenery for one stack, groups them into columns, and writes back through one stack route. The one model change is a run key on a cue's ad-hoc effects.</p>
</div>

<div style="display:flex; flex-direction:column; gap:10px">
<h2>What a column is</h2>
<table class="m">
<tr><th style="width:90px">Kind</th><th style="width:220px">Column key</th><th>A filled cell is</th><th style="width:280px">On / off</th></tr>
<tr><td><b>Look</b></td><td><code>lookId</code> + one target, or none for the Look's own fixtures</td><td>A <code>cue_layers</code> row for the Look whose targets include the target (or are empty)</td><td>The press, on that target or on none; settings copied (D6)</td></tr>
<tr><td><b>Palette</b></td><td><code>templateId</code> + one target</td><td>A layer of the palette covering the target. One layer over Front and Back is two cells.</td><td>The press: same-family palettes come off those heads (D5)</td></tr>
<tr><td><b>Effect</b></td><td><code>effectType</code> + <code>propertyName</code> + targets as stored</td><td>A <code>cue_ad_hoc_effects</code> row with that signature; cells sharing <code>runUuid</code> draw as one run</td><td>On copies the nearest cell's row, run key included; off deletes the row</td></tr>
<tr><td><b>Scenery</b></td><td><code>elementUuid</code></td><td>A <code>cue_scenery</code> row; between changes, the tracked state</td><td>Set writes the state and transition; clear deletes the row and tracking resumes</td></tr>
</table>
<p class="cap">A Look's own effects stay inside its column: they already continue across the cues that layer it. A palette that holds an effect is a palette column like any other.</p>
</div>

<div style="display:grid; grid-template-columns:minmax(0,1fr) minmax(0,1fr); gap:24px; align-items:start">
<div style="display:flex; flex-direction:column; gap:10px">
<h2>The run key</h2>
<div class="box">
${lane('Q2 → Q3', 'same key, same settings', 'Adopted: the running instance moves to Q3, id and phase kept, as a Look\'s effect is today.', '')}
${lane('Q3 → Q4', 'same key, new speed', 'Adopted, then edited in place through <code>applyEffectUpdate</code>, phase kept (D8). A move between <i>Seconds</i> and a master restarts the phase, as Update stage does.', 'amber')}
${lane('Q6 → Q7', 'different keys', 'Q6\'s effect shrinks out over the fade and Q7\'s grows in from phase 0: today\'s behaviour, drawn ↺.', 'stale')}
<p style="margin-top:10px">A paused or N-shot effect is never adopted (<code>mayContinue</code>), key or not.</p>
</div>
<div class="box"><p><b>Stored</b> as <code>cue_ad_hoc_effects.run_uuid</code>, nullable, and <code>runUuid</code> on the cue's effect DTO and its sync record. <b>Sync</b> <code>formatVersion</code> 36 → 37; <code>RichProjectFixture</code> sets one so the round trip sees it.</p>
<p><b>Assigned</b> when a cell is filled from a neighbour (On, drag, Fill ↓). <i>Break run</i> gives a cell and those below it a fresh key; <i>Join run</i> takes the key from above when the signature matches. Look effects keep today's rule.</p></div>
</div>
<div style="display:flex; flex-direction:column; gap:10px">
<h2>Reading it</h2>
<div class="box"><p><code>GET /projects/{id}/cue-stacks/{sid}/timeline</code></p>
<p class="mono" style="font-size:11px; line-height:1.6; white-space:pre; color:var(--fg)">{ entries: [{ entryId, cueId,
    layers: [{ id, source: {kind, id, name}, targets,
      sortOrder, enabled, amount, blendMode, propertyMask,
      stomp, timed, speedMasterUuid, rateSpeedMasterUuid }],
    effects: [{ id, uuid, runUuid, effectType,
      propertyName, targets, settings }],
    scenery: [{ elementUuid, state, transitionMs }],
    trackedScenery: [{ elementUuid, state, fromCueId }] }] }</p>
<p>Its own read keeps <code>CueStackDetails</code> light for the Cards and Table. Columns, runs and ranks are computed in one memo on the client.</p></div>
<h2 style="margin-top:6px">Keeping it live</h2>
<table class="m">
<tr><th>Frame</th><th>Does</th></tr>
<tr><td><code>cueStackListChanged</code>, <code>cueListChanged</code></td><td>Refetch: entries moved, a cue written, renamed or deleted</td></tr>
<tr><td><code>lookListChanged</code>, <code>paletteListChanged</code>, <code>stageElementListChanged</code></td><td>Refetch: a column's name or record changed</td></tr>
<tr><td><code>cueRunStateChanged</code></td><td>The live and next rows and the fade bar, drawn from <code>fadeElapsedMs</code></td></tr>
<tr><td><code>cueHolds.state</code></td><td><i>Edited since GO</i> on a live row</td></tr>
</table>
</div>
</div>

<div style="display:flex; flex-direction:column; gap:10px">
<h2>Writing it</h2>
<p class="cap">Every gesture is a batch of ops on one new route, <code>POST /projects/{id}/cue-stacks/{sid}/timeline/edit</code> <code>{ops, dryRun?}</code>.</p>
<table class="m">
<tr><th style="width:260px">Gesture</th><th>Op</th></tr>
<tr><td><b>A layer cell on or off</b></td><td><code>layerOn {cueId, source, target, settingsFrom?}</code> runs <code>LayerPress.press</code> and then applies the copied settings, in one transaction; <code>layerOff</code> is the off press. Answers what it released, for the toast.</td></tr>
<tr><td><b>Drag, Fill ↓, a range On or Off</b></td><td>One op per cell, one request.</td></tr>
<tr><td><b>Replace across a range</b></td><td><code>replace {cueIds, from, to, keepSettings}</code>: the layer's source swapped in place where the cue lacks <code>to</code>; removed where it has it.</td></tr>
<tr><td><b>A layer's settings</b></td><td><code>layerSettings {cueId, layerId, fields}</code>, the cue editor's layer fields.</td></tr>
<tr><td><b>An effect cell, a run</b></td><td><code>effectOn {cueId, fromEffectUuid}</code>, <code>effectOff</code>, <code>breakRun</code>, <code>joinRun</code>.</td></tr>
<tr><td><b>A scenery cell</b></td><td><code>scenerySet</code>, <code>sceneryClear</code>, checked as <code>PUT /cues/{cid}/scenery</code> checks.</td></tr>
</table>
<p class="cap">All or nothing, every problem named at once. It answers the cues it changed; a live one among them is marked edited since GO and nothing is sent (show-cue-editing D4). It fires <code>cueListChanged</code> and <code>cueStackListChanged</code>. The client keeps the inverse ops, so a gesture is one Undo (C3).</p>
</div>

<div style="display:grid; grid-template-columns:repeat(3, minmax(0,1fr)); gap:16px">
<div class="box"><p><b>Size.</b> A stack of 60 cues and 30 columns is 1,800 cells, which the sheet draws without virtualising. Measure at 150 × 40 on the iPad frame before deciding otherwise.</p></div>
<div class="box"><p><b>Tests.</b> The sync bump runs the full suites locally. <code>CueHolds</code> gains run-key adoption tests beside the P4 ones; the route gets one test per op and one for a mixed batch rolling back.</p></div>
<div class="box"><p><b>MCP.</b> No new tool. The ops are the route's; an AI tool for runs can follow once the route has settled.</p></div>
</div>
</div>
`, { w: 1240, h: 1610, narrow: true })

// ── canvas.json ───────────────────────────────────────────────────────────────
const GAP = 100
const place = {
  'Spec.dc.html': [0, 0],
  'Model.dc.html': [1240 + GAP, 0],
  'Timeline.dc.html': [0, boards['Spec.dc.html'].h + GAP],
  'Editing.dc.html': [1440 + GAP, boards['Spec.dc.html'].h + GAP],
}
const canvas = {
  v: 3,
  title: 'Stack timeline',
  boards: Object.fromEntries(Object.entries(place).map(([f, [x, y]]) => [f, { x, y, w: boards[f].w, h: boards[f].h, title: boards[f].title }])),
  order: Object.keys(place),
}
writeFileSync(OUT + 'canvas.json', JSON.stringify(canvas, null, 2) + '\n')
console.log('wrote', Object.keys(boards).join(', '), 'and canvas.json')
