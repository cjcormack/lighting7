// Generates the navigation and attach boards. Each board is one dark artboard that renders
// standalone; `node gen.mjs` rewrites all three. The stylesheet is the apply-model boards'
// (../apply-model-design/gen.mjs), itself the show-cue-editing and busk-chrome vocabulary, restated
// here with the sidebar, switcher, home and attach classes appended, so a change there cannot move these.
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
    /* navigation boards */
    .frame { display:flex; flex-direction:column; border-bottom:1px solid var(--bd); position:relative; }
    .top .desk { font-size:12px; font-weight:500; color:rgba(255,255,255,0.72); }
    .top .div { width:1px; height:22px; background:rgba(255,255,255,0.35); }
    .switch { display:inline-flex; align-items:center; gap:8px; height:32px; padding:0 10px; border-radius:8px; background:rgba(255,255,255,0.14); font-size:15px; font-weight:700; }
    .switch .led { width:9px; height:9px; border-radius:50%; background:var(--amber); box-shadow:0 0 0 3px rgba(245,192,74,0.25); }
    .switch .led.on { background:var(--green); box-shadow:0 0 0 3px rgba(74,222,128,0.25); }
    .pinchip { display:inline-flex; align-items:center; gap:6px; height:24px; padding:0 9px; border-radius:999px; font-size:11.5px; font-weight:500; background:rgba(0,0,0,0.22); }
    .pinchip b { color:#fde68a; font-weight:600; }
    .nav { width:240px; border-right:1px solid var(--bd); background:var(--bg); display:flex; flex-direction:column; padding:8px; gap:1px; flex-shrink:0; }
    .nav .ph { display:flex; flex-wrap:wrap; align-items:center; gap:6px 8px; padding:8px 8px 10px; border-bottom:1px solid var(--bd); margin-bottom:6px; font-weight:700; font-size:13.5px; }
    .nav .sec { font-size:9px; font-weight:700; letter-spacing:0.09em; text-transform:uppercase; color:var(--mfg); padding:10px 8px 4px; }
    .nav .ni { display:flex; align-items:center; gap:9px; height:28px; padding:0 8px; border-radius:6px; font-size:12.5px; }
    .nav .ni i { width:14px; height:14px; border-radius:4px; border:1.5px solid currentColor; opacity:0.8; flex-shrink:0; }
    .nav .ni.on { background:var(--muted); }
    .nav .ni.sub { padding-left:30px; }
    .nav .ni.dim { color:var(--mfg); }
    .nav .ni.off { color:var(--mfg); }
    .nav .ni.off::after { content:""; margin-left:auto; width:6px; height:6px; border-radius:50%; border:1.5px solid var(--mfg); }
    .nav .ni.new { box-shadow:inset 2px 0 0 #4ade80; }
    .badge { display:inline-flex; align-items:center; height:18px; padding:0 7px; border-radius:999px; border:1px solid var(--bd); font-size:10px; font-weight:500; color:var(--mfg); white-space:nowrap; }
    .badge.venue { color:#5cd1c1; border-color:rgba(92,209,193,0.5); letter-spacing:0.06em; font-size:9px; font-weight:700; }
    .badge.rig { color:#86efac; border-color:rgba(74,222,128,0.5); background:rgba(74,222,128,0.1); }
    .badge.held { color:var(--amber); border-color:oklch(0.6 0.12 80 / 0.6); background:oklch(0.3 0.06 80 / 0.3); }
    .badge.new { color:var(--stale); border-color:oklch(0.65 0.14 30 / 0.6); background:oklch(0.3 0.06 30 / 0.3); }
    .smenu { position:absolute; width:400px; background:var(--card); border:1px solid var(--bd); border-radius:12px; box-shadow:0 18px 40px rgba(0,0,0,0.6); overflow:hidden; z-index:6; }
    .smenu .search { height:38px; border-bottom:1px solid var(--bd); display:flex; align-items:center; padding:0 12px; color:var(--mfg); font-size:12.5px; gap:8px; }
    .smenu .grp { padding:6px; display:flex; flex-direction:column; gap:1px; border-bottom:1px solid var(--bd); }
    .smenu .grp .lbl { padding:4px 8px; }
    .smenu .pr { display:grid; grid-template-columns:12px minmax(0,1fr) auto; align-items:center; gap:8px; padding:6px 8px; border-radius:6px; font-size:12.5px; }
    .smenu .pr.hover { background:var(--muted); }
    .smenu .pr .sub { font-size:10.5px; color:var(--mfg); }
    .smenu .pr .acts { display:flex; gap:4px; }
    .smenu .ft { display:flex; gap:6px; padding:8px 10px; background:oklch(0.19 0.005 286); }
    .led2 { width:8px; height:8px; border-radius:50%; background:var(--amber); }
    .ghostpage { flex:1; padding:20px; display:flex; flex-direction:column; gap:10px; opacity:0.45; }
    .ghostpage i { display:block; height:30px; border-radius:6px; background:var(--muted); }
    .offrig { margin:40px auto; width:520px; border:1px solid var(--bd); border-radius:12px; background:var(--card); padding:20px 22px; display:flex; flex-direction:column; gap:12px; }
    .offrig h3 { margin:0; font-size:17px; }
    .offrig p { margin:0; color:var(--mfg); font-size:13px; line-height:1.5; }
    /* home */
    .home { flex:1; padding:18px 22px; display:flex; flex-direction:column; gap:16px; background:var(--page); }
    .home h1 { margin:0; font-size:26px; letter-spacing:-0.01em; }
    .status { border:1px solid var(--bd); border-radius:10px; background:var(--card); display:flex; flex-direction:column; }
    .status .sl { display:grid; grid-template-columns:80px minmax(0,1fr) auto; gap:12px; align-items:center; padding:9px 14px; border-bottom:1px solid var(--bd); font-size:12.5px; }
    .status .sl:last-child { border-bottom:none; }
    .status .sl.held { background:oklch(0.3 0.06 30 / 0.22); }
    .secs { display:flex; flex-direction:column; gap:12px; }
    .secrow { display:grid; grid-template-columns:76px repeat(6, minmax(0,1fr)); gap:8px; align-items:stretch; }
    .hcard { border:1px solid var(--bd); border-radius:9px; background:var(--card); padding:9px 11px; display:flex; flex-direction:column; gap:4px; min-height:62px; }
    .hcard .t { display:flex; align-items:center; gap:7px; font-size:12.5px; font-weight:600; }
    .hcard .t i { width:13px; height:13px; border-radius:4px; border:1.5px solid currentColor; opacity:0.8; }
    .hcard .c { font-size:11px; color:var(--mfg); }
    .hcard.off { color:var(--mfg); }
    .hcard.off .t::after { content:""; margin-left:auto; width:6px; height:6px; border-radius:50%; border:1.5px solid var(--mfg); }
    /* attach */
    .asheet { width:620px; border-left:1px solid var(--bd); background:var(--card); display:flex; flex-direction:column; }
    .asheet .hd { padding:14px 16px; border-bottom:1px solid var(--bd); display:flex; flex-direction:column; gap:8px; }
    .asheet .grp { padding:10px 16px; border-bottom:1px solid var(--bd); display:flex; flex-direction:column; gap:6px; }
    .arow { display:grid; grid-template-columns:minmax(0,1fr) auto; gap:10px; align-items:center; padding:6px 10px; border-radius:7px; background:oklch(0.19 0.005 286); font-size:12px; }
    .arow .w { font-size:10.5px; color:var(--mfg); }
    .seg2 { display:inline-flex; border:1px solid var(--bd); border-radius:7px; overflow:hidden; }
    .seg2 span { padding:3px 9px; font-size:11px; color:var(--mfg); }
    .seg2 span.on { background:var(--pri); color:#fff; }
    .three { padding:7px 10px; border-radius:7px; background:oklch(0.3 0.05 185 / 0.25); color:#5cd1c1; font-size:11.5px; }
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

const notes = (title, items) => `<div class="notes"><div class="t">${title}</div>${items.map(([n, t]) => `<div><b>${n}</b>${t}</div>`).join('')}</div>`
const note = (n, x, y) => `<span class="note" style="left:${x}px; top:${y}px">${n}</span>`
const btn = (t, cls = '') => `<span class="btn sm ${cls}">${t}</span>`
const badge = (t, cls = '') => `<span class="badge ${cls}">${t}</span>`

// The app bar after D1/D5: the desk's name, then the switcher naming the show on the rig.
const top = (held = true) => `<div class="top"><span class="desk">Hall desk</span><span class="div"></span>
  <span class="switch"><span class="led${held ? '' : ' on'}"></span>Sleeping Beauty <span style="opacity:0.7;font-weight:400">▾</span></span>
  <span class="pinchip">TCH venue v7 · <b>${held ? 'held' : 'v8 published'}</b></span>
  <span class="sp"></span>
  <span class="chip" style="background:#0f3a1f;border-color:#2f7a45;color:#86efac;height:28px;font-size:12px">Connected</span>
  ${held ? '<span class="chip amber" style="height:28px;font-size:12px">Blind · venue</span>' : ''}
  <span class="chip" style="height:28px;width:28px;justify-content:center;border-radius:50%;background:rgba(255,255,255,0.15);color:#fff;border:none">CS</span></div>`

// The sidebar after D3: named sections by job. `off` marks needsRig rows on a project that isn't on the rig.
const SECTIONS = [
  ['Run', [['Programmer', 1], ['Show', 1], ['Prompt Book', 1], ['Busk', 1], ['Stage', 1]]],
  ['Rig', [['Patch List'], ['Fixtures', 1], ['Groups', 1], ['Regions'], ['Rigging'], ['Channels', 1]]],
  ['Library', [['Looks'], ['Palettes'], ['Effects'], ['Speed Masters'], ['Scripts']]],
  ['Project', [['Settings'], ['Surfaces', 1], ['Sync']]],
  ['Desk', [['Projects', 0, 'new'], ['Install Settings'], ['Users', 0, 'sub'], ['Sync', 0, 'sub'], ['Remote Access', 0, 'sub'], ['Updates', 0, 'sub'], ['Diagnostics', 0, 'sub']]],
]
const VENUE_HIDDEN = new Set(['Show', 'Prompt Book', 'Busk', 'Looks', 'Palettes', 'Effects', 'Speed Masters', 'Scripts'])
const nav = ({ project, tag = '', on = '', offRig = false, venue = false, desk = true }) => `<div class="nav">
  <div class="ph">${project}${tag}</div>
  ${SECTIONS.filter(([s]) => desk || s !== 'Desk').map(([sec, rows]) => {
    const vis = rows.filter(([r]) => !(venue && VENUE_HIDDEN.has(r)))
    if (!vis.length) return ''
    return `<div class="sec">${sec}</div>` + vis.map(([r, needsRig, cls = '']) =>
      `<div class="ni ${cls} ${r === on ? 'on' : ''} ${offRig && needsRig ? 'off' : ''} ${sec === 'Desk' && cls !== 'new' ? 'dim' : ''}"><i></i>${r}</div>`).join('')
  }).join('')}
</div>`

// ───────────────────────── Navigation board ─────────────────────────
board('Navigation.dc.html', 'Navigation · the switcher, the sections and the rows off the rig', `
<div class="frame" style="height:720px">
  ${top(true)}
  <div class="shell" style="position:relative">
    ${nav({ project: 'Sleeping Beauty', on: 'Programmer' })}
    <div class="main"><div class="ghostpage"><i style="width:60%"></i><i></i><i></i><i></i><i style="width:80%"></i><i></i><i></i></div></div>
    <div class="smenu" style="left:150px; top:6px">
      <div class="search">⌕ Find a show or venue</div>
      <div class="grp"><span class="lbl">On the rig</span>
        <div class="pr"><span class="led2"></span><span>Sleeping Beauty <span class="sub">· TCH venue v7 · held</span></span><span class="acts">${btn('Open')}</span></div></div>
      <div class="grp"><span class="lbl">Shows</span>
        <div class="pr hover"><span></span><span>TCH Test <span class="sub">· TCH venue v7</span></span><span class="acts">${btn('Open')}${btn('Make current', 'pri')}</span></div>
        <div class="pr"><span></span><span>Default <span class="sub">· no venue</span></span><span class="acts">${btn('Open')}${btn('Make current')}</span></div></div>
      <div class="grp"><span class="lbl">Venues</span>
        <div class="pr"><span></span><span>TCH venue ${badge('VENUE', 'venue')} <span class="sub">v8 · 2 shows</span></span><span class="acts">${btn('Open')}${btn('Make current')}</span></div></div>
      <div class="ft">${btn('All projects…')}<span class="sp"></span>${btn('New venue…')}${btn('New show…')}</div>
    </div>
    <div class="pp" style="position:absolute; left:600px; top:250px; width:440px">
      <div class="ph"><b>Put TCH Test on the rig?</b></div>
      <div class="bd" style="font-size:12.5px; line-height:1.5">
        <div>Sleeping Beauty's cues, programmer and effects stop, and every channel goes dark except those TCH Test parks (4).</div>
        <div style="color:var(--amber)">TCH Test is behind TCH venue on output (v7, v8 re-patched). It opens held: only parked channels reach the rig.</div>
      </div>
      <div class="ft"><span class="sp"></span>${btn('Cancel')}${btn('Put TCH Test on the rig', 'pri')}</div>
    </div>
  </div>
  ${note(1, 74, 2)}${note(2, 264, 2)}${note(3, 428, 2)}${note(4, 560, 120)}${note(5, 120, 104)}${note(6, 120, 562)}${note(7, 590, 240)}
</div>
<div class="frame" style="height:620px">
  ${top(true)}
  <div class="shell">
    ${nav({ project: 'TCH venue', tag: ` ${badge('VENUE', 'venue')} ${badge('Not on the rig')}`, on: 'Stage', offRig: true, venue: true })}
    <div class="main">
      <div style="height:40px; display:flex; align-items:center; gap:8px; padding:0 14px; border-bottom:1px solid var(--bd); background:oklch(0.3 0.05 185 / 0.25); color:#5cd1c1; font-size:12px"><b style="color:var(--fg)">Editing the venue.</b> Changes collect in a draft.<span class="sp"></span>${btn('History')}${btn('Publish v9…', 'ask')}</div>
      <div style="padding:10px 16px" class="crumb">Projects › <b>TCH venue</b> › Stage</div>
      <div class="offrig">
        <h3>TCH venue isn't on the rig.</h3>
        <p>The Stage view draws the rig that's live, and <b style="color:var(--fg)">Sleeping Beauty</b> is on it now. Put TCH venue on the rig to draw and focus its stage, or open Sleeping Beauty's.</p>
        <div class="row">${btn('Make TCH venue current…', 'pri')}${btn("Open Sleeping Beauty's Stage")}</div>
      </div>
    </div>
  </div>
  ${note(8, 214, 52)}${note(9, 220, 186)}${note(10, 860, 160)}
</div>
${notes('Navigation: the app bar names the show on the rig and switches it (D1, D5); the sidebar is named sections by job (D3); a project that isn\'t on the rig keeps every row (D4).', [
  ['1', 'The desk\'s friendly name from Install Settings, small, before a divider. It hides below 640 px (D5).'],
  ['2', 'The switcher names the current project. Amber means held behind its venue; green means sending.'],
  ['3', 'A show on a venue gets its pin chip, coloured as the behind banner is. It opens the show\'s home.'],
  ['4', 'The menu: search, then On the rig, Shows and Venues. Each row has Open (its home) and Make current. The footer has All projects, New show and, for an admin, New venue.'],
  ['5', 'Run, Rig, Library, Project and Desk, with headings. Stage joins Run; Regions and Rigging join Rig; Projects is Desk\'s first row (new, green edge).'],
  ['6', 'Desk keeps the install pages under Install Settings, muted as today.'],
  ['7', 'One confirmation for every door. It says what a switch does (blackout but the incoming parks) and warns when the show will open held.'],
  ['8', 'Viewing the venue while Sleeping Beauty is on the rig: Run keeps Programmer and Stage (venue mode), Library\'s heading goes, and the header says Not on the rig.'],
  ['9', 'Rows that need the rig stay, muted, with a hollow dot at the right edge: "Needs the project on the rig" (D4). Patch List, Regions, Rigging and Settings work as today.'],
  ['10', 'Opening one shows NotOnRig in place of the view, naming the show on the rig, with Make current through the same confirmation.'],
])}
`, 1520)

// ───────────────────────── Home board ─────────────────────────
const hcard = (t, c = '', off = false) => `<div class="hcard ${off ? 'off' : ''}"><div class="t"><i></i>${t}</div><div class="c">${c}</div></div>`
const secrow = (name, cards) => `<div class="secrow"><span class="lbl" style="padding-top:10px">${name}</span>${cards.join('')}</div>`
board('Home.dc.html', 'The project home · a show and a venue', `
<div class="frame" style="height:700px">
  ${top(true)}
  <div class="shell">
    ${nav({ project: 'Sleeping Beauty', desk: false })}
    <div class="home">
      <div class="crumb">Projects › <b>Sleeping Beauty</b></div>
      <div class="row" style="align-items:flex-start"><div><div class="row"><h1>Sleeping Beauty</h1>${badge('On the rig', 'rig')}</div><div class="dim" style="margin-top:2px">Panto at the Commemoration Hall, built on TCH venue.</div></div><span class="sp"></span>${btn('⋮')}</div>
      <div class="status">
        <div class="sl held"><span class="lbl">Venue</span><span>On <b>TCH venue v7</b> · ${badge('v8 · re-patched', 'new')} <span class="dim">· output held: only parked channels reach the rig</span></span><span class="row">${btn('Review update', 'pri')}${btn('Run against v7…')}${btn('Open venue')}</span></div>
        <div class="sl"><span class="lbl">Sync</span><span>Synced · <span class="mono dim">cjcormack/lighting7-tch-2026-sleeping-beauty</span> · 2 min ago</span><span>${btn('Sync now')}</span></div>
        <div class="sl"><span class="lbl">Live</span><span>Act 1 · <b>cue 12</b> live, 12.5 standing by · Panto FX · nothing live</span><span>${btn('Show')}</span></div>
      </div>
      <div class="secs">
        ${secrow('Run', [hcard('Programmer'), hcard('Show', '2 stacks · 35 cues'), hcard('Prompt Book', '12 pages'), hcard('Busk', '3 pages'), hcard('Stage', '9 viewpoints'), ''])}
        ${secrow('Rig', [hcard('Patch List', '60 patches'), hcard('Fixtures', '57'), hcard('Groups', '24'), hcard('Regions', '6'), hcard('Rigging', '7 bars'), hcard('Channels', '4 universes')])}
        ${secrow('Library', [hcard('Looks', '9'), hcard('Palettes', '31'), hcard('Effects', '24 built-in'), hcard('Speed Masters', '4'), hcard('Scripts', '0'), ''])}
        ${secrow('Project', [hcard('Settings'), hcard('Surfaces', '0 bindings'), hcard('Sync', 'synced'), '', '', ''])}
      </div>
    </div>
    <div class="menu" style="position:absolute; right:22px; top:150px; width:240px">
      <div class="mi">Settings</div><div class="ms"></div>
      <div class="mi">Clone…</div><div class="mi">Export…</div><div class="ms"></div>
      <div class="mi">Update venue to v8…</div><div class="mi">Detach from venue…</div><div class="ms"></div>
      <div class="mi red" style="opacity:0.5">Delete <small>on the rig</small></div>
    </div>
  </div>
  ${note(1, 1362, 100)}${note(2, 250, 196)}${note(3, 250, 278)}${note(4, 250, 360)}${note(5, 1150, 336)}
</div>
<div class="frame" style="height:640px">
  ${top(true)}
  <div class="shell">
    ${nav({ project: 'TCH venue', tag: ` ${badge('VENUE', 'venue')} ${badge('Not on the rig')}`, offRig: true, venue: true, desk: false })}
    <div class="home">
      <div class="crumb">Projects › <b>TCH venue</b></div>
      <div class="row"><h1>TCH venue</h1>${badge('VENUE', 'venue')}<span class="sp"></span>${btn('Make current…')}${btn('⋮')}</div>
      <div class="status">
        <div class="sl"><span class="lbl">Version</span><span><b>v8</b> · 14 Oct 2026 · <span class="dim">"Re-patched LX2 for the panto"</span></span><span>${btn('History')}</span></div>
        <div class="sl"><span class="lbl">Draft</span><span>3 changes · <span style="color:var(--stale)">1 changes output</span></span><span class="row">${btn('Discard…')}${btn('Publish v9…', 'pri')}</span></div>
        <div class="sl"><span class="lbl">Sync</span><span>Synced · <span class="mono dim">cjcormack/lighting7-tch-venue</span> · tags v1–v8</span><span></span></div>
      </div>
      <div class="panel"><div class="pt row"><span class="lbl">Shows on this venue</span></div>
        <div class="lrow" style="grid-template-columns:minmax(0,1fr) auto auto"><span>Sleeping Beauty ${badge('On the rig', 'rig')}</span><span>v7 ${badge('behind · output', 'new')}</span><span>${btn('Open')}</span></div>
        <div class="lrow" style="grid-template-columns:minmax(0,1fr) auto auto"><span>TCH Test</span><span>v7 ${badge('behind · output', 'new')}</span><span class="row">${btn('Open')}${btn('Make current')}</span></div>
      </div>
      <div class="secs">
        ${secrow('Run', [hcard('Programmer', '', true), hcard('Stage', '', true), '', '', '', ''])}
        ${secrow('Rig', [hcard('Patch List', '58 patches'), hcard('Fixtures', '', true), hcard('Groups', '', true), hcard('Regions', '6'), hcard('Rigging', '7 bars'), hcard('Channels', '', true)])}
      </div>
    </div>
  </div>
  ${note(6, 250, 132)}${note(7, 1230, 168)}${note(8, 250, 282)}${note(9, 250, 400)}
</div>
${notes('The project home (D2): status first, every action in one ⋮, and the registry\'s sections as cards with counts.', [
  ['1', 'On the rig, or Make current. The ⋮ renders projectActions (P3), the same set as the card, the switcher and ⌘K. Configure is now Settings in it.'],
  ['2', 'The venue line reuses the behind banner\'s actions. A standalone show reads "No venue · Attach to venue…".'],
  ['3', 'Sync and, only on the rig, what\'s live per stack.'],
  ['4', 'The registry\'s sections minus Desk, from the same navItems, so nothing is left out. Counts come from NavItem.homeCount.'],
  ['5', 'Delete is disabled on the rig, with the reason.'],
  ['6', 'A venue\'s home: its version and History, the draft with Publish and Discard (admin), and its repo.'],
  ['7', 'Make current through the one confirmation. The venue isn\'t on the rig, so its needsRig cards are dotted (D4).'],
  ['8', 'Shows on this venue, each with its pin, Open and Make current.'],
  ['9', 'Venue mode: Run keeps Programmer and Stage; no Library.'],
])}
`, 1460)

// ───────────────────────── Attach board ─────────────────────────
const arow = (what, why, right = '') => `<div class="arow"><span>${what}<div class="w">${why}</div></span><span class="row">${right}</span></div>`
const seg = (a, b, on = 0) => `<span class="seg2"><span class="${on === 0 ? 'on' : ''}">${a}</span><span class="${on === 1 ? 'on' : ''}">${b}</span></span>`
board('Attach.dc.html', 'Attach to venue · the review', `
<div class="frame" style="height:1170px">
  ${top(false)}
  <div class="shell">
    ${nav({ project: 'TCH Test', desk: false })}
    <div class="home" style="opacity:0.5">
      <div class="crumb">Projects › <b>TCH Test</b></div>
      <div class="row"><h1>TCH Test</h1><span class="sp"></span>${btn('Make current…')}${btn('⋮')}</div>
      <div class="status"><div class="sl"><span class="lbl">Venue</span><span>No venue · detached from TCH venue v7</span><span>${btn('Attach to venue…', 'pri')}</span></div></div>
    </div>
    <div class="asheet">
      <div class="hd">
        <div class="row"><b style="font-size:15px">Attach TCH Test to a venue</b><span class="sp"></span>${btn('✕', 'ghost')}</div>
        <div class="row"><span class="field" style="width:300px; font-family:inherit">TCH venue <span class="dim" style="margin-left:auto; font-size:11px">Detached from here</span></span><span class="field" style="width:170px; font-family:inherit">v8 · installed <span class="dim" style="margin-left:auto">▾</span></span></div>
        <div class="three">Three-way: TCH Test left TCH venue at v7. A venue record the show removed since then stays hidden; one the venue added since comes in.</div>
      </div>
      <div class="grp"><span class="lbl">Output</span>
        ${arow('<b style="color:var(--stale)">Changes output · 2</b>', 'LX2 Orbit 70 SR re-addressed by the venue in v8: 2.101 → 2.121. Park on 1.512 added in v8 comes in at 40%.')}
        ${arow('Drawing only · 3', 'Stage depth 9.6 → 9.8 m · LX2 bar lowered 0.2 m · House tabs re-coloured')}
      </div>
      <div class="grp"><span class="lbl">From the venue</span>
        ${arow('Inherited', '54 patches · 4 universes · 7 riggings · 6 regions · 11 elements · balcony seating · 3 viewpoints · 18 groups · 6 parks')}
      </div>
      <div class="grp"><span class="lbl">Become overrides · 3</span>
        ${arow('ADV2 Front SR · Focus', 'yours: 24° · 750 W · venue: 19° · 750 W · base v8')}
        ${arow('LX1 Fresnel C · Media', 'yours: L201 · venue: none · base v8')}
        ${arow('LX2 Shehds SR · Position <span class="badge new">both changed</span>', 'yours: LX2 +0.4 m · v7: LX2 0.0 · v8: LX2 −0.2 m', seg('Keep mine', "Take venue's"))}
      </div>
      <div class="grp"><span class="lbl">Decisions · 3</span>
        ${arow('hex-7 · key differs <span class="dim">(venue: hex-07)</span>', "Keep mine hides the venue's Hex 7. Take venue's renames hex-7 → hex-07 in 12 cue rows, 3 Looks, 1 palette, 1 binding.", seg('Keep mine', "Take venue's"))}
        ${arow('Group "Front wash" · members differ', 'yours: 6 (adds Hex 7) · venue: 5. Groups keep yours by default (D3).', seg('Keep mine', "Take venue's"))}
        ${arow('Rigging "Boom SL" · height differs', 'yours: 2.4 m · venue: 2.1 m. Hall structure takes the venue\'s (D3).', seg('Keep mine', "Take venue's", 1))}
      </div>
      <div class="grp"><span class="lbl">Hidden in this show · 2</span>
        ${arow('House light 4', 'removed from TCH Test since v7')}
        ${arow('Hex 7 (venue)', 'hidden by Keep mine')}
      </div>
      <div class="grp"><span class="lbl">Yours · 4</span>
        ${arow('Hired: 2 × Robe Pointe, Haze 2, SET layer: the castle', 'show additions, kept as they are')}
      </div>
      <div class="grp"><span class="lbl">Address overlap · 1</span>
        ${arow('Robe Pointe SL (yours) 3.1–3.24 overlaps Fog FX (venue, added v8) 3.20', 'P8, as in Update', '<span class="seg2"><span class="on">Re-address mine → 3.25</span><span>Keep both</span><span>Hide venue\'s</span></span>')}
      </div>
      <div class="grp" style="border-bottom:none"><div class="row"><span class="dim" style="font-size:11.5px">Nothing blocks the attach. TCH Test isn't on the rig, so nothing reloads.</span><span class="sp"></span>${btn('Cancel')}${btn('Attach to TCH venue v8', 'pri')}</div></div>
    </div>
  </div>
  ${note(1, 822, 96)}${note(2, 1404, 150)}${note(3, 822, 222)}${note(4, 822, 420)}${note(5, 822, 600)}${note(6, 822, 792)}${note(7, 822, 922)}${note(8, 822, 1003)}
</div>
${notes('Attach to venue (venue-attach plan): pick the venue and version, review what each of the show\'s records becomes, then attach in one transaction.', [
  ['1', 'The detachedFrom venue first, marked. The version defaults to the installed one (D1).'],
  ['2', 'Three-way or not, said plainly: with an origin, removed-since records are hidden and added-since ones come in.'],
  ['3', 'Output first. A clean round trip reads "Attaching changes no output" (P2).'],
  ['4', 'Facet differences become overrides with their base. Where both changed, it is drift, answered as Update\'s is.'],
  ['5', 'Identity (D2) keeps mine by default; Take venue\'s shows the rename\'s reach. Groups keep mine; hall structure takes the venue\'s (D3).'],
  ['6', 'Hides, each with why.'],
  ['7', 'The show\'s own additions stay its own.'],
  ['8', 'Address overlaps are P8\'s decisions. Attach is disabled while one is unanswered or a blocker stands.'],
])}
`, 1490)

console.log('wrote 3 boards')
