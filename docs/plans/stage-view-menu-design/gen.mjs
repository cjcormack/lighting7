// Generates the stage-view-menu design boards from the Stage boards' stylesheet.
import { readFileSync, writeFileSync } from 'node:fs'

const OUT = new URL('.', import.meta.url).pathname
// The Stage boards' stylesheet, lines 5–223 of ../stage-view-design/Stage.dc.html.
const BASE = readFileSync(new URL('../stage-view-design/Stage.dc.html', import.meta.url), 'utf8').split('\n').slice(4, 223).join('\n')

const EXTRA = `
    /* ── additions for the stage-view-menu boards ─────────────────────────── */
    .pop { position:absolute; width:320px; background:var(--card); border:1px solid var(--bd); border-radius:10px; box-shadow:0 14px 34px rgba(0,0,0,0.55); z-index:4; overflow:hidden; }
    .pop.ghost { box-shadow:none; outline:2px dashed oklch(0.82 0.16 80 / 0.55); outline-offset:6px; }
    .ptabs { display:flex; gap:2px; margin:8px 8px 0; padding:2px; border:1px solid var(--bd); border-radius:8px; background:var(--bg); }
    .ptabs span { flex:1; display:inline-flex; align-items:center; justify-content:center; gap:6px; height:26px; border-radius:6px; font-size:12px; font-weight:600; color:var(--mfg); }
    .ptabs span.on { background:var(--muted); color:var(--fg); }
    .pbody { padding:6px 10px 10px; display:flex; flex-direction:column; gap:10px; }
    .grp { display:flex; flex-direction:column; gap:6px; }
    .gh { display:flex; align-items:center; gap:6px; height:16px; }
    .gh .lbl { flex:1; }
    .scope { display:inline-flex; align-items:center; gap:4px; height:16px; padding:0 6px; border-radius:999px; font-size:9px; font-weight:600; color:var(--mfg); border:1px solid var(--bd); white-space:nowrap; }
    .scope.mach { color:oklch(0.82 0.12 80); border-color:oklch(0.6 0.12 80 / 0.5); }
    .scope.wire { color:oklch(0.8 0.12 255); border-color:oklch(0.5 0.12 255 / 0.6); }
    .sg { display:flex; gap:2px; border:1px solid var(--bd); background:var(--bg); border-radius:8px; padding:2px; }
    .sg span { flex:1; display:inline-flex; align-items:center; justify-content:center; gap:5px; height:24px; padding:0 6px; border-radius:6px; font-size:11.5px; font-weight:500; color:var(--mfg); white-space:nowrap; }
    .sg span.on { background:var(--muted); color:var(--fg); box-shadow:inset 0 0 0 1px oklch(0.4 0.01 286 / 0.6); }
    .g2 { display:grid; grid-template-columns:1fr 1fr; gap:4px; }
    .opt { display:flex; align-items:center; gap:7px; height:30px; padding:0 9px; border-radius:7px; border:1px solid var(--bd); background:var(--bg); font-size:12px; color:var(--fg); white-space:nowrap; }
    .opt i { width:12px; height:12px; border-radius:50%; border:1.5px solid var(--mfg); flex:0 0 auto; }
    .opt.on { border-color:oklch(0.623 0.214 259.815 / 0.6); background:oklch(0.623 0.214 259.815 / 0.14); }
    .opt.on i { border-color:var(--pri); background:radial-gradient(circle, var(--pri) 0 3px, transparent 3.5px); }
    .hint { font-size:11px; line-height:1.4; color:var(--mfg); margin:0; }
    .hint b { color:var(--fg); font-weight:600; }
    .tgs { display:flex; flex-wrap:wrap; gap:4px; }
    .tg { display:inline-flex; align-items:center; gap:4px; height:26px; padding:0 7px 0 6px; border-radius:7px; border:1px solid var(--bd); background:var(--bg); font-size:11px; color:var(--mfg); white-space:nowrap; }
    .tg svg { width:12px; height:12px; }
    .tg.on { color:var(--fg); border-color:oklch(0.623 0.214 259.815 / 0.6); background:oklch(0.623 0.214 259.815 / 0.14); }
    .tg.on svg { color:var(--pri); }
    .tg.off svg { opacity:0; }
    .tgsep { width:100%; height:0; }
    .pfoot { border-top:1px solid var(--bd); padding:7px 10px; font-size:10.5px; color:var(--mfg); display:flex; align-items:center; gap:6px; background:oklch(0.17 0.005 286); }
    .sw2 { width:28px; height:16px; border-radius:999px; background:var(--muted); position:relative; flex:0 0 auto; }
    .sw2::after { content:''; position:absolute; top:2px; left:2px; width:12px; height:12px; border-radius:50%; background:var(--mfg); }
    .sw2.on { background:var(--pri); }
    .sw2.on::after { left:14px; background:#fff; }
    .livebox { border:1px solid var(--bd); border-radius:8px; background:var(--bg); padding:8px 10px; display:flex; flex-direction:column; gap:6px; }
    .livebox .big { font:600 20px ui-monospace,Menlo,monospace; font-variant-numeric:tabular-nums; letter-spacing:-0.01em; }
    .livebox .big small { font-size:12px; font-weight:500; color:var(--mfg); }
    .livebox .row { display:flex; align-items:center; gap:8px; font-size:11px; color:var(--mfg); }
    .livebox .row b { color:var(--fg); font-weight:600; font-variant-numeric:tabular-nums; }
    .fps { position:absolute; display:inline-flex; align-items:center; gap:6px; padding:2px 7px; border-radius:5px; background:oklch(0.141 0.005 285.823 / 0.8); font:10.5px ui-monospace,Menlo,monospace; font-variant-numeric:tabular-nums; color:var(--mfg); border:1px solid transparent; }
    .fps b { color:var(--fg); font-weight:600; }
    .fps.slow { color:oklch(0.82 0.12 80); border-color:oklch(0.6 0.12 80 / 0.45); }
    .fps.slow b { color:oklch(0.88 0.14 80); }
    .fps.big { position:static; font-size:15px; padding:6px 12px; border-radius:7px; }
    .hud { position:absolute; padding:2px 7px; border-radius:5px; background:oklch(0.141 0.005 285.823 / 0.8); font:10px ui-monospace,Menlo,monospace; color:var(--mfg); }
    .zb { position:absolute; display:flex; gap:4px; }
    .zb span { width:26px; height:26px; border-radius:6px; border:1px solid var(--bd); background:var(--card); display:grid; place-items:center; color:var(--fg); font-size:13px; }
    .vbtn { display:inline-flex; align-items:center; gap:6px; height:28px; padding:0 10px; border-radius:8px; border:1px solid var(--bd); font-size:12px; font-weight:500; background:oklch(0.274 0.006 286.033 / 0.3); white-space:nowrap; }
    .vbtn.open { background:var(--muted); }
    .vbtn .src { display:inline-flex; align-items:center; height:18px; padding:0 6px; border-radius:999px; font-size:10px; font-weight:600; background:oklch(0.62 0.18 255 / 0.18); color:oklch(0.82 0.11 255); border:1px solid oklch(0.62 0.18 255 / 0.45); }
    .bar { display:flex; height:30px; border-radius:6px; overflow:hidden; position:relative; }
    .bar > span { display:flex; align-items:center; padding:0 7px; font-size:10px; font-weight:600; white-space:nowrap; overflow:hidden; color:oklch(0.16 0.01 286); border-right:1px solid oklch(0.141 0.005 285.823); }
    .barrow { display:grid; grid-template-columns:150px 1fr; gap:12px; align-items:center; }
    .barrow .bl { font-size:11.5px; color:var(--fg); }
    .barrow .bl small { display:block; font-size:10px; color:var(--mfg); }
    .foldline { position:absolute; top:-8px; bottom:-8px; width:0; border-left:2px dashed oklch(0.65 0.2 27); }
    .foldline em { position:absolute; bottom:-20px; left:4px; font-size:9.5px; font-style:normal; font-weight:700; color:oklch(0.75 0.17 27); white-space:nowrap; }
    .shot { position:relative; border-radius:8px; overflow:hidden; border:1px solid var(--bd); background:#07090d; }
    .shot img { display:block; width:100%; height:auto; }
    .shot .tagl { position:absolute; left:8px; top:8px; display:inline-flex; align-items:center; gap:6px; height:22px; padding:0 9px; border-radius:6px; background:oklch(0.141 0.005 285.823 / 0.85); font-size:11px; font-weight:600; }
    .tbl { width:100%; border-collapse:collapse; font-size:11px; }
    .tbl th { text-align:left; font-size:9px; font-weight:700; text-transform:uppercase; letter-spacing:0.08em; color:var(--mfg); padding:5px 8px; border-bottom:1px solid var(--bd); white-space:nowrap; }
    .tbl td { padding:5px 8px; border-bottom:1px solid oklch(0.274 0.006 286.033 / 0.6); vertical-align:top; line-height:1.4; }
    .tbl td.n { font-family:ui-monospace,Menlo,monospace; font-variant-numeric:tabular-nums; text-align:right; }
    .tbl td.hot { color:oklch(0.78 0.17 27); font-weight:700; }
    .tbl td.ok { color:oklch(0.8 0.15 145); }
    .tbl tr.sub td { color:var(--mfg); }
    .sect { display:flex; flex-direction:column; gap:10px; }
    .cols { display:grid; gap:16px; }
`

const ico = (d, cls = 'ico14') => `<svg class="${cls}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${d}</svg>`
const I = {
  eye: '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z"/><circle cx="12" cy="12" r="3"/>',
  gauge: '<path d="M12 14l4-4"/><path d="M3.3 19a10 10 0 1 1 17.4 0"/>',
  check: '<path d="m5 12 5 5L20 7"/>',
  win: '<rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8M12 17v4"/>',
  chip: '<rect x="6" y="6" width="12" height="12" rx="2"/><path d="M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  moon: '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/>',
  pencil: '<path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"/>',
  reset: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/>',
  link: '<path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7"/><path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7"/>',
  cam: '<path d="M3 7h4l2-3h6l2 3h4v12H3z"/><circle cx="12" cy="13" r="3.5"/>',
}

const scopeWin = (wire = false) =>
  `<span class="scope">${ico(I.win, 'ico12')}this window</span>${wire ? `<span class="scope wire">${ico(I.link, 'ico12')}Screens</span>` : ''}`
const scopeMach = `<span class="scope mach">${ico(I.chip, 'ico12')}this machine</span>`

const tg = (label, on) => `<span class="tg ${on ? 'on' : 'off'}">${ico(I.check)}${label}</span>`

/** The View tab, as the popover draws it. */
const viewTab = ({ source = 'output', look = 'readable', status = null } = {}) => {
  const srcs = [['output', 'Output'], ['outputProgrammer', 'Output + Programmer'], ['programmer', 'Programmer only'], ['nextGo', 'Next GO']]
  const hints = {
    output: 'Final merged DMX — what the desk is transmitting.',
    outputProgrammer: 'Output with the programmer laid over it. Same as Output unless Blind is on.',
    programmer: 'Only what the programmer holds. Everything else reads zero.',
    nextGo: 'What the next GO would look like, over live output. Cue values only.',
  }
  return `
  <div class="ptabs"><span class="on">${ico(I.eye, 'ico12')}View</span><span>${ico(I.gauge, 'ico12')}Performance</span></div>
  <div class="pbody">
    <div class="grp"><div class="gh"><span class="lbl">Source</span>${scopeWin(true)}</div>
      <div class="g2">${srcs.map(([k, l]) => `<span class="opt ${k === source ? 'on' : ''}"><i></i>${l}</span>`).join('')}</div>
      <p class="hint">${hints[source]}</p>${status ? `<p class="hint" style="color:oklch(0.85 0.1 80)">${status}</p>` : ''}
    </div>
    <div class="grp"><div class="gh"><span class="lbl">Show</span>${scopeWin()}</div>
      <div class="tgs">${tg('Fixtures', true)}${tg('Light', true)}${tg('Rigging', true)}${tg('Regions', false)}<span class="tgsep"></span>${tg('Venue', true)}${tg('Set', true)}${tg('Seating', true)}</div>
    </div>
    <div class="grp"><div class="gh"><span class="lbl">Haze</span>${scopeWin()}</div>
      <div class="sg"><span>Off</span><span class="on">Stage</span><span>Everywhere</span></div>
      <p class="hint">Upstage of the proscenium, or the stage edge.</p>
    </div>
    <div class="grp"><div class="gh"><span class="lbl">Labels</span>${scopeWin()}</div>
      <div class="sg"><span class="on">Positions</span><span>All fixtures</span><span>None</span></div>
    </div>
    <div class="grp"><div class="gh"><span class="lbl">Work lights</span>${scopeWin(true)}</div>
      <div class="sg"><span class="${look === 'realistic' ? 'on' : ''}">${ico(I.moon, 'ico12')}Off</span><span class="${look === 'readable' ? 'on' : ''}">${ico(I.sun, 'ico12')}On</span></div>
      <p class="hint">${look === 'readable' ? 'Lifts the dark, so unlit surfaces show their shape. Pools keep their exposure.' : 'The room as the rig lights it. What no beam reaches stays near black.'}</p>
    </div>
  </div>`
}

/** The Performance tab. */
const perfTab = ({ fps = '58', ms = '17.2', on = true } = {}) => `
  <div class="ptabs"><span>${ico(I.eye, 'ico12')}View</span><span class="on">${ico(I.gauge, 'ico12')}Performance</span></div>
  <div class="pbody">
    <div class="grp"><div class="gh"><span class="lbl">This window's canvas</span>${scopeWin()}</div>
      <div class="livebox">
        <span class="big">${fps} <small>fps</small> · ${ms} <small>ms a frame</small></span>
        <div class="row"><span>Lights <b>64</b> on surfaces of <b>71</b> lit</span><span class="spacer"></span><span>Haze <b>full</b></span></div>
        <div class="row" style="border-top:1px solid var(--bd); padding-top:6px"><span style="color:var(--fg); flex:1">Frame rate on the canvas</span><span class="sw2 ${on ? 'on' : ''}"></span></div>
      </div>
    </div>
    <div class="grp"><div class="gh"><span class="lbl">Light budget</span>${scopeMach}</div>
      <div class="sg"><span>32</span><span class="on">64</span><span>128</span><span>256</span></div>
      <p class="hint">The brightest this many light the surfaces. The rest still draw their beams.</p>
    </div>
    <div class="grp"><div class="gh"><span class="lbl">Gobos on surfaces</span>${scopeMach}</div>
      <div class="sg"><span class="on">Every gobo light</span><span>Selected heads</span></div>
    </div>
    <div class="grp"><div class="gh"><span class="lbl">Box shadows</span>${scopeMach}</div>
      <div class="sg"><span class="on">64 a light</span><span>16 a light</span><span>Off</span></div>
    </div>
    <div class="grp" style="border-top:1px solid var(--bd); padding-top:8px">
      <div style="display:flex; align-items:center; gap:8px"><span class="btn out sm">${ico(I.reset, 'ico12')}Test recovery</span><span class="hint" style="flex:1">Drops the 3D context the way Safari does under memory pressure.</span></div>
    </div>
  </div>`

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

// The rail, as on the Stage boards: Stage lit.
const rail = `<div class="rail"><div class="rh">${ico('<path d="M4 6h16M4 12h16M4 18h16"/>', 'ico16')}</div>${['<path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>', '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18M9 21V9"/>', '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>', '<path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6"/>', '<path d="M2 3h6a4 4 0 0 1 4 4v14a3 3 0 0 0-3-3H2z"/><path d="M22 3h-6a4 4 0 0 0-4 4v14a3 3 0 0 1 3-3h7z"/>'].map((d) => `<div class="ri">${ico(d, 'ico16')}</div>`).join('')}<div class="rsep"></div><div class="ri on">${ico('<path d="M12 3 4 7v10l8 4 8-4V7z"/><path d="M4 7l8 4 8-4M12 11v10"/>', 'ico16')}</div><div class="ri">${ico('<path d="M4 6h16M4 12h10M4 18h7"/>', 'ico16')}</div></div>`
const appbar = `<div class="appbar"><span class="title">Chris' DMX Controller v7</span><span class="conn">${ico('<circle cx="12" cy="12" r="9"/><path d="m8 12 3 3 5-6"/>')}Connected</span><span class="avatar">CL</span></div>`
const viewhdr = (viewBtn) => `<div class="viewhdr">
  <span class="vt">Stage</span>
  <span class="vp">${ico(I.eye)}Balcony · desk <small>▾</small></span>
  <span class="seg"><span>${ico(I.cam, 'ico12')}Orbit</span><span class="on">Eye</span><span>Plan</span><span>Front</span><span>Side</span></span>
  <span class="spacer"></span>
  ${viewBtn}
  <span class="btn out sm">${ico(I.pencil, 'ico12')}Edit</span>
</div>`

// ── Menu.dc.html ───────────────────────────────────────────────────────────
{
  const W = 1440, H = 1300
  // Today's menu, measured on the desk 2026-10-06 (y offsets inside the 1,535 px content).
  const today = [['Source', 0, 330, '#8fb3ff'], ['Show', 330, 600, '#9ad6b0'], ['Haze', 600, 807, '#b9a7f0'], ['Labels', 807, 944, '#e6c27a'], ['Light budget', 944, 1147, '#f0a58a'], ['Gobos', 1147, 1287, '#f0a58a'], ['Box shadows', 1287, 1457, '#f0a58a'], ['Test recovery', 1457, 1535, '#c9c9d0']]
  const S = 0.62 // px of bar per px of menu
  const bar = (segs, total) => `<div class="bar" style="width:${Math.round(total * S)}px">${segs.map(([l, a, b, c]) => `<span style="width:${Math.round((b - a) * S)}px; background:${c}">${l}</span>`).join('')}</div>`
  const body = `
<div style="display:flex; height:860px; flex-shrink:0;">
${rail}
<div style="flex:1; min-width:0; display:flex; flex-direction:column; position:relative;">
${appbar}
${viewhdr(`<span class="vbtn open">${ico(I.eye, 'ico12')}View <small style="color:var(--mfg)">▾</small></span>`)}
<div style="flex:1; min-height:0; display:flex;">
  <div class="canvas">
    <img src="plates/balcony-readable-a-housing.jpg" style="object-position:50% 40%">
    <div class="ov ovcap" style="left:auto; right:12px; top:10px; background:oklch(0.141 0.005 285.823 / 0.7); padding:3px 8px; border-radius:6px"><b>Balcony · desk</b> · look around · drag to turn, scroll to zoom</div>
    <span class="fps" style="left:12px; bottom:12px"><b>58</b> fps · <b>17.2</b> ms</span>
    ${callout(7, 'left:150px; bottom:9px')}
  </div>
</div>
<div class="pop" style="right:72px; top:107px">${viewTab()}</div>
<div class="pop ghost" style="right:420px; top:107px">${perfTab()}</div>
<div class="ov cap" style="right:420px; top:80px; width:320px; white-space:nowrap; color:oklch(0.85 0.12 80)">Its other tab, drawn beside it for the board</div>
${callout(1, 'right:150px; top:62px')}
${callout(2, 'right:350px; top:116px')}
${callout(3, 'right:52px; top:162px')}
${callout(4, 'right:52px; top:272px')}
${callout(5, 'right:52px; top:508px')}
${callout(6, 'right:750px; top:160px')}
</div>
</div>
<div style="flex:1; padding:18px 24px 0; display:flex; flex-direction:column; gap:12px; border-top:1px solid var(--bd)">
  <div style="display:flex; align-items:baseline; gap:12px"><span class="h2">Height, to scale</span><span class="cap">Today measured on the desk (Chromium, 1440 × 900, project 15): 27 rows in a <span class="mono">w-64</span> dropdown, <b>1,535 px</b> of content, <b>801 px</b> on screen. The two tabs as drawn above, measured in the board — both clear the fold with room for a 768 px screen.</span></div>
  <div class="barrow" style="margin-bottom:14px"><span class="bl">Today<small>one dropdown</small></span><div style="position:relative">${bar(today, 1535)}<span class="foldline" style="left:${Math.round(801 * S)}px"><em>the fold at 1440 × 900 — Labels and everything under it scroll</em></span></div></div>
  <div class="barrow"><span class="bl">View tab<small>this window</small></span><div style="display:flex;align-items:center">${bar([['Tabs + Source', 0, 159, '#8fb3ff'], ['Show', 159, 251, '#9ad6b0'], ['Haze', 251, 335, '#b9a7f0'], ['Labels', 335, 397, '#e6c27a'], ['Work lights', 397, 502, '#e3e08c']], 502)}<span class="cap" style="margin-left:10px">502 px</span></div></div>
  <div class="barrow"><span class="bl">Performance tab<small>machine + this canvas</small></span><div style="display:flex;align-items:center">${bar([['Tabs + readout', 0, 163, '#7fd6d0'], ['Light budget', 163, 262, '#f0a58a'], ['Gobos', 262, 324, '#f0a58a'], ['Box shadows', 324, 386, '#f0a58a'], ['Test recovery', 386, 457, '#c9c9d0']], 457)}<span class="cap" style="margin-left:10px">457 px</span></div></div>
</div>
${legend([
  [1, '<b>One trigger, two tabs.</b> The View button opens a 320 px popover rather than a menu: segments and toggles show every value at once, where radio rows spent a line each. It names a source that is not Output — <span class="vbtn" style="height:20px;font-size:10px;padding:0 6px">View <span class="src">Next GO</span></span> — because that is the one setting that makes the picture not the output.'],
  [2, '<b>View is what this window sees; Performance is what it costs.</b> The split is by purpose. Scope is written on every group — <span class="scope">this window</span> or <span class="scope mach">this machine</span> — rather than implied by the tab.'],
  [3, '<b>Source as a 2 × 2.</b> Only the chosen source\'s hint is shown, and Next GO\'s live status under it. The hint stays: Output + Programmer reads as broken without it.'],
  [4, '<b>Show is seven toggles in two rows</b>: the rig\'s four, then the scene\'s three. They move from one value per browser to per window, so the hall screen can drop the rigging without the desk losing it. <i>Beam cones</i> becomes <b>Light</b>: since light lands through the surface shader, off draws no beam and no pool (Model board).'],
  [5, '<b>Work lights: Off | On.</b> The stage-light plan\'s <i>Readable</i>, named for what it imitates. New, per window, and announced beside the source so a Screens row can set it. See the Work lights board.'],
  [6, '<b>Performance</b> leads with what the canvas is doing — frame rate, the lights the budget packs, the haze tier — so the three machine settings are chosen against a number. Test recovery moves here.'],
  [7, '<b>The readout</b>, off by default, bottom-left: frames per second and ms a frame while frames are drawn, <i>idle</i> otherwise. Click opens Performance. See the Readout board.'],
])}`
  writeFileSync(`${OUT}/Menu.dc.html`, doc('Stage view menu', W, H, body))
}

// ── WorkLights.dc.html ───────────────────────────────────────────────────────────
{
  const W = 1440, H = 1500
  const shot = (src, tag, style = '') => `<div class="shot" style="${style}"><img src="plates/${src}"><span class="tagl">${tag}</span></div>`
  const body = `
<div style="padding:22px 24px 12px; display:flex; flex-direction:column; gap:6px">
  <span class="h1">Work lights · off and on</span>
  <p class="cap" style="max-width:1100px">Real frames from the desk's renderer, not paintings: project 15 from <b>Balcony · desk</b>, Chromium on the desk Mac at DPR 1.5, 2026-10-06, with the three lamps the show had up. <b>Work lights on</b> (the stage-light plan's <i>Readable</i>) is here a trial made by writing the live shader uniforms in one window — the room's ambient 0.003 → <b>0.02</b>, every surface's fill +<b>0.04</b>, the housings' fill 0.225 → <b>0.8</b>. The exposure (<span class="mono">SURFACE_LIGHT_GAIN</span> 4.4) and the roll-off were not touched. Nothing was stored and nothing was sent to the desk.</p>
</div>
<div class="cols" style="grid-template-columns:1fr 1fr; padding:0 24px">
  ${shot('balcony-realistic.jpg', `${ico(I.moon, 'ico12')}Work lights off — today`)}
  ${shot('balcony-readable-a-housing.jpg', `${ico(I.sun, 'ico12')}Work lights on — level a, housings 0.8`)}
</div>
<div class="cols" style="grid-template-columns:690px 1fr; padding:16px 24px 0">
  <div class="sect">
    <span class="h2">Three levels tried, and what they measured</span>
    <div class="cols" style="grid-template-columns:repeat(3,1fr); gap:8px">
      ${shot('balcony-readable-a.jpg', 'a · 0.02 + 0.04')}
      ${shot('balcony-readable-b.jpg', 'b · 0.03 + 0.09')}
      ${shot('balcony-readable-c.jpg', 'c · 0.04 + 0.18')}
    </div>
    <table class="tbl">
      <tr><th>Mean of a 9 × 9 patch, 0–255</th><th style="text-align:right">Off</th><th style="text-align:right">a</th><th style="text-align:right">b</th><th style="text-align:right">c</th></tr>
      <tr><td>Pool on the stage floor</td><td class="n">119</td><td class="n ok">121</td><td class="n">123</td><td class="n">127</td></tr>
      <tr><td>Pool on the black backcloth</td><td class="n">80</td><td class="n ok">80</td><td class="n">81</td><td class="n">81</td></tr>
      <tr><td>Pale ceiling, unlit</td><td class="n">23</td><td class="n ok">95</td><td class="n hot">125</td><td class="n hot">155</td></tr>
      <tr><td>Seat, unlit</td><td class="n">1</td><td class="n">17</td><td class="n">26</td><td class="n">35</td></tr>
      <tr><td>Stage riser, unlit</td><td class="n">1</td><td class="n">11</td><td class="n">19</td><td class="n">28</td></tr>
      <tr><td>Black serge backcloth, unlit</td><td class="n">0</td><td class="n hot">2</td><td class="n hot">3</td><td class="n hot">5</td></tr>
    </table>
    <p class="cap">The pools hold: +2 at a, because the lift adds under them as it does everywhere. <b>The ceiling sets the level</b>: at b it is brighter than the floor pool, at c by a quarter, and the eye goes to the ceiling. a keeps it at 79 % of the pool. <b>Black serge stays black</b> at every level — a lift that scales with the finish gives a 0.6 % cloth almost nothing.</p>
  </div>
  <div class="sect">
    <span class="h2">On the Plan, the rig went missing</span>
    <div class="cols" style="grid-template-columns:repeat(3,1fr); gap:8px">
      ${shot('plan-realistic.jpg', 'Off')}
      ${shot('plan-readable-a.jpg', 'a, housings 0.225')}
      ${shot('plan-readable-a-housing.jpg', 'a, housings 0.8')}
    </div>
    <p class="cap">Matt near-black housings read against a black room. Lift the room and the FOH bar mid-house fades into the lit floor (middle). Work lights therefore raise the housings' own fill with the room (right) — the rig reads on the Plan and on the balcony view alike, and a selected housing's blue keeps its lead by riding the same tint.</p>
  </div>
</div>
<div class="cols" style="grid-template-columns:1fr 1fr 1fr; padding:16px 24px 0">
  <div class="note key"><div class="h2" style="margin-bottom:6px">What work lights are</div><p class="cap">One table, two rows: <span class="mono">off {ambient 0.003, lift 0, housing 0.225}</span>, <span class="mono">on {ambient 0.02, lift 0.04, housing 0.8}</span>. The lift is the materials' existing directional fill, so planes facing different ways read differently — shape, not a flat grey. All uniforms: switching recompiles nothing and costs one frame. <b>Unchanged:</b> the exposure and roll-off, the haze, the lenses (dark glass until lit), the canvas's background.</p></div>
  <div class="note"><div class="h2" style="margin-bottom:6px">Black finishes (a decision)</div><p class="cap">Proposed: <b>the lift sees a floor albedo</b> (about 4 %, tuned in <span class="mono">?profileHarness=cyc</span> beside the white cyc), while every light still sees the finish's own. So black serge shows its folds as a dark grey with work lights on, and a spot on it is exactly as bright as with them off. The stage-light plan's no-floor rule (D6) stays true of the light. Declined alternative: accept that black stays black.</p></div>
  <div class="note"><div class="h2" style="margin-bottom:6px">Whose it is</div><p class="cap"><b>Per window</b>, in <span class="mono">sessionStorage</span> (<span class="mono">stage.workLights</span>), default off — the desk screen plots with work lights on while the hall screen shows the room as lit. It rides <span class="mono">viewOptions</span> as <span class="mono">workLights</span> beside the source, so another window's Screens row can set it, and <i>Copy link</i> carries <span class="mono">workLights=</span>. Every canvas in the window follows it: the Stage view and the Positions plan.</p></div>
</div>
<div class="cols" style="grid-template-columns:1fr 1fr; padding:16px 24px 0">
  <div class="sect">
    <span class="h2">The Screens row gains one segment</span>
    <div class="note" style="display:flex;gap:8px;flex-wrap:wrap;align-items:center"><b style="font-size:12px">Hall screen</b><span class="cap" style="font-size:10px">Viewpoint</span><span class="sel" style="min-width:140px">Row F centre</span><span class="cap" style="font-size:10px">Source</span><span class="seg"><span class="on">Output</span><span>Next GO</span></span><span class="cap" style="font-size:10px">Work lights</span><span class="seg"><span class="on">Off</span><span>On</span></span><span class="tag new">new</span></div>
    <p class="cap">One <span class="mono">WindowViewOption</span> on the Stage entry of <span class="mono">lib/windowViews.ts</span>; the sheet draws it generically. <span class="mono">viewOptions</span> is a free map, so the announce's pinned key set does not move.</p>
  </div>
  <div class="sect">
    <span class="h2">render_view</span>
    <div class="note"><p class="cap">Today a capture draws the default layers, no labels, the request's source and the machine's light budget — and so a scene Claude has just built with nothing lit comes back nearly black. Proposed: <b>an optional <span class="mono">workLights</span> on the tool</b>, off by default so a capture still means "what the room looks like", with the description saying to turn them on to check geometry. It rides <span class="mono">stageRender.request</span> beside <span class="mono">source</span> and echoes in the result. The capture never reads or writes the window's own work lights.</p></div>
  </div>
</div>`
  writeFileSync(`${OUT}/WorkLights.dc.html`, doc('Work lights', W, H, body))
}

// ── Readout.dc.html ────────────────────────────────────────────────────────
{
  const W = 1440, H = 1240
  const body = `
<div style="padding:22px 24px 12px; display:flex; flex-direction:column; gap:6px">
  <span class="h1">The frame-rate readout</span>
  <p class="cap" style="max-width:1100px">The canvas draws on demand, so a frame rate only means something while frames are being drawn. The readout says what the canvas is doing <i>now</i>: frames per second and ms a frame while it draws, <b>idle</b> when it has drawn nothing for a second. Off by default, per window, toggled from Performance. The numbers below are illustrative; the pane this board was drawn from throttles <span class="mono">requestAnimationFrame</span>, so no honest rate could be read there.</p>
</div>
<div class="cols" style="grid-template-columns:repeat(4,1fr); padding:4px 24px 0">
  <div class="note"><div style="margin-bottom:8px"><span class="fps big"><b>58</b> fps · <b>17.2</b> ms</span></div><p class="cap"><b>Drawing.</b> An orbit, a fade, a spinning gobo. Frames counted over the trailing second; ms is the median gap between frames less than a second apart.</p></div>
  <div class="note"><div style="margin-bottom:8px"><span class="fps big slow"><b>24</b> fps · <b>41.6</b> ms</span></div><p class="cap"><b>Slow.</b> Amber past 28 ms a frame — the haze governor's own step-down line — so when the beams turn grainy the operator can see why.</p></div>
  <div class="note"><div style="margin-bottom:8px"><span class="fps big">idle</span></div><p class="cap"><b>Idle.</b> Nothing drawn for a second: the stage is still. A timer flips it, not a frame, and a hidden tab reads idle when it is shown again.</p></div>
  <div class="note"><div style="margin-bottom:8px;height:30px;display:flex;align-items:center"><span class="cap">— (not drawn)</span></div><p class="cap"><b>3D paused.</b> While the context is lost the paused card owns the canvas; the readout hides with the label layer.</p></div>
</div>
<div class="cols" style="grid-template-columns:1fr 1fr; padding:18px 24px 0">
  <div class="sect">
    <span class="h2">In view mode: the bottom-left corner</span>
    <div class="shot" style="height:420px"><img src="plates/balcony-realistic.jpg" style="height:100%; width:100%; object-fit:cover">
      <span class="hud" style="right:10px; top:10px; font-family:inherit; font-size:11px"><b style="color:var(--fg)">Balcony · desk</b> look around</span>
      <span class="fps" style="left:10px; bottom:10px"><b>58</b> fps · <b>17.2</b> ms</span>
      <span class="hud" style="left:50%; transform:translateX(-50%); bottom:10px; font-family:inherit; font-size:11px"><b style="color:var(--fg)">ADV2 Front C</b> Source Four 19°</span>
    </div>
    <p class="cap">The only free corner: top-right is the viewpoint caption, top-centre the placing and seat hints, bottom-centre the selection. The section editor's HUD takes the other three corners.</p>
  </div>
  <div class="sect">
    <span class="h2">On a section in Edit: stacked over the cursor strip</span>
    <div class="shot" style="height:420px; background:#0a0d14"><img src="plates/plan-realistic.jpg" style="height:100%; width:auto; margin:0 auto">
      <span class="hud" style="left:8px; top:8px; line-height:1.6">→ x · stage right to left<br>↑ y · downstage to up</span>
      <span class="fps" style="left:8px; bottom:34px"><b>60</b> fps · <b>16.7</b> ms</span>
      <span class="hud" style="left:8px; bottom:8px">−1.20, 4.85 m <span style="border-left:1px solid var(--bd); padding-left:6px; margin-left:4px">snap 0.25 m</span></span>
      <span class="zb" style="right:8px; bottom:8px"><span>−</span><span>+</span><span>⤢</span></span>
    </div>
    <p class="cap">The same chip, one row up, in the HUD's own mono strip style. It never joins the strip: the strip is the edit layer's, which a render and view mode never mount.</p>
  </div>
</div>
<div class="cols" style="grid-template-columns:340px 1fr; padding:18px 24px 0">
  <div class="pop" style="position:relative; width:320px">${perfTab()}</div>
  <div class="sect">
    <span class="h2">Rules</span>
    <div class="note"><p class="cap"><b>It never asks for a frame.</b> A readout that invalidated would keep a demand canvas drawing forever and measure itself. It is fed from the frame loop it measures (beside <span class="mono">HazeGovernorProbe</span>, priority 2) and writes its text straight to its DOM node at most four times a second; the idle flip is a <span class="mono">setTimeout</span>. A test mounts it in a demand canvas and asserts no frame is requested.</p></div>
    <div class="note"><p class="cap"><b>It counts what is drawn, not what it costs.</b> ms a frame is the interval: a light scene on a 60 Hz display reads 16.7 however little the GPU did. A GPU timer is not portable (Safari has none), and the per-pixel cost of a setting stays the occlusion bench's job.</p></div>
    <div class="note"><p class="cap"><b>Per window, in <span class="mono">sessionStorage</span>, not announced</b> (<span class="mono">stage.frameRate</span>). Each window draws its own canvas at its own rate; as a machine setting it would switch on over the hall screen too. Never in a <span class="mono">render_view</span> capture — it is DOM over the canvas, and the job does not mount it. Clicking it opens the View popover on Performance.</p></div>
    <div class="note"><p class="cap"><b>The live block reads stores, not the DOM.</b> Today the light counts are stamped as <span class="mono">data-lights</span> and the haze tier as <span class="mono">data-haze-tier</span> for measurement; the Performance tab reads the same values from a small stats store those writers also feed, and the attributes stay.</p></div>
  </div>
</div>`
  writeFileSync(`${OUT}/Readout.dc.html`, doc('Frame-rate readout', W, H, body))
}

// ── Model.dc.html ──────────────────────────────────────────────────────────
{
  const W = 1440, H = 1180
  const row = (s, today, next, wire, rv, cls = '') => `<tr class="${cls}"><td><b>${s}</b></td><td>${today}</td><td>${next}</td><td>${wire}</td><td>${rv}</td></tr>`
  const body = `
<div style="padding:22px 24px 10px; display:flex; flex-direction:column; gap:6px">
  <span class="h1">The model · stored, wire, code, sessions</span>
</div>
<div class="cols" style="grid-template-columns:1fr; padding:0 24px">
  <div class="sect">
    <span class="h2">Every setting, where it lives today and where it goes</span>
    <table class="tbl">
      <tr><th>Setting</th><th>Today</th><th>Proposed</th><th>On the wire</th><th>A render_view capture</th></tr>
      ${row('Source', 'Window — <span class="mono">sessionStorage stage.source</span>', '<span class="tag kept">kept</span>', '<span class="mono">viewOptions.source</span>', "The request's")}
      ${row('Fixtures · Light <span style="font-weight:400;color:var(--mfg)">(was Beam cones)</span> · Rigging · Regions', '<b>Browser</b> — <span class="mono">localStorage stageViewFlags</span>', 'Window — <span class="mono">stage.viewFlags</span>, seeded once from the old key', 'No', 'The defaults')}
      ${row('Labels', '<b>Browser</b> — the same key', 'Window — the same new key', 'No', 'None (DOM over the canvas)')}
      ${row('Venue · Set · Seating · Haze', 'Window — <span class="mono">stage.sceneLayers</span>, not announced', '<span class="tag kept">kept</span>', 'No', 'The defaults; Haze on the Stage')}
      ${row('Work lights', '—', 'Window — <span class="mono">stage.workLights</span>, default off <span class="tag new">new</span>', '<span class="mono">viewOptions.workLights</span> <span class="tag new">new</span>', 'The request\'s <span class="mono">workLights</span>, default off <span class="tag new">new</span>')}
      ${row('Frame-rate readout', '—', 'Window — <span class="mono">stage.frameRate</span>, default off <span class="tag new">new</span>', 'No', 'Never drawn')}
      ${row('Light budget', 'Machine — <span class="mono">localStorage stage.lightBudget</span>', '<span class="tag kept">kept</span>', 'No', "The machine's, read")}
      ${row('Gobos on surfaces', 'Machine — <span class="mono">stage.goboSurfaces</span>', '<span class="tag kept">kept</span>', 'No', 'The default (every gobo light)')}
      ${row('Box shadows', 'Machine — <span class="mono">stage.boxShadows</span>', '<span class="tag kept">kept</span>', 'No', 'The default (64)')}
      ${row('Test recovery', 'An action', 'An action, on Performance', '—', '—')}
    </table>
  </div>
</div>
<div class="cols" style="grid-template-columns:1fr 1fr; padding:16px 24px 0">
  <div class="sect">
    <span class="h2">The starting proposal, tested</span>
    <div class="note key"><p class="cap"><b>Held:</b> split <i>what you see</i> from <i>what it costs</i>. Two tabs of one popover, so the header keeps one button and the rare settings stop pushing the frequent ones off screen.</p></div>
    <div class="note warn"><p class="cap"><b>Moved — the readout is per window, not per machine.</b> Each window draws its own canvas at its own rate, and the two desk screens are two windows of one profile: a machine setting would put the counter on the hall screen as well. Its group on Performance is labelled <span class="scope">this window</span>; the three below it <span class="scope mach">this machine</span>.</p></div>
    <div class="note warn"><p class="cap"><b>Moved — the Show flags and Labels are per browser today</b>, not per window: <span class="mono">useStageView</span> keeps them in <span class="mono">localStorage</span>. Hiding the rigging on the hall screen hides it on the desk too. They move to <span class="mono">sessionStorage</span>, a window with nothing stored reading the old key once, as the vis source did when it moved.</p></div>
    <div class="note warn" style="display:grid; grid-template-columns:200px 1fr; gap:12px; align-items:start"><div class="shot"><img src="plates/balcony-beamcones-off.jpg"></div><p class="cap"><b>Found while drawing — <i>Beam cones</i> is the light switch.</b> Off unmounts <span class="mono">StageEmitters</span>, which since light moved into the surface shader also packs the light table every surface reads: no beam, no pool, the rig dark (Balcony · desk with it off, left). Haze <i>Off</i> is what hides the cones alone. Renamed <b>Light</b>, hint <i>Beams and every pool</i>; the stored key stays <span class="mono">beamCones</span>.</p></div>
    <div class="note"><p class="cap"><b>Corrected:</b> Venue · Set · Seating · Haze are per window but <b>not</b> announced — only the viewpoint and the source ride <span class="mono">viewOptions</span>. That stays; Work lights join the source, because it changes what the whole picture means and a plotting screen is a thing another window would set. The rest are this window's business.</p></div>
  </div>
  <div class="sect">
    <span class="h2">Wire, storage, sync</span>
    <div class="note"><div class="kv">
      <span class="k">Desk tables</span><span>None. No schema, no sync, no <span class="mono">SyncCoverageTest</span> row.</span>
      <span class="k">Announce</span><span><span class="mono">viewOptions.workLights</span> under the Stage view — a free map, so <span class="mono">windowsApi.test.ts</span>'s pinned key set is unchanged.</span>
      <span class="k">windows.viewOptions</span><span><span class="mono">applyStageViewOptions</span> takes <span class="mono">workLights</span>; a value outside <span class="mono">off | on</span> is ignored, as a viewpoint's is.</span>
      <span class="k">Copy link</span><span><span class="mono">?workLights=</span>, applied and stripped on arrival with <span class="mono">viewpoint</span> and <span class="mono">source</span>.</span>
      <span class="k">render_view</span><span>Optional <span class="mono">workLights</span> input (<span class="mono">RenderViewTool</span>), through <span class="mono">StageRenderService.render</span> into the <span class="mono">stageRender.request</span> frame; the frontend's parse reads a missing one as off. Backend and frontend land in one PR.</span>
    </div></div>
    <span class="h2" style="margin-top:6px">Where the code goes</span>
    <div class="note"><div class="kv">
      <span class="k">StageViewMenu.tsx</span><span>Rewritten: <span class="mono">Popover</span>, a two-value tab, <span class="mono">ToggleGroup</span>s and toggles. Same props plus work lights and the readout.</span>
      <span class="k">useStageView.ts</span><span>On <span class="mono">createSyncStore</span> over <span class="mono">sessionStorageArea</span>, seeded from <span class="mono">stageViewFlags</span>.</span>
      <span class="k">scene/workLights.ts</span><span>New: the two rows, the store, the option key, <span class="mono">isWorkLights</span>.</span>
      <span class="k">scene/surfaceShader.ts</span><span><span class="mono">uLift</span> and the lift's albedo floor beside <span class="mono">uAmbient</span>; <span class="mono">litByFill</span> takes the work lights.</span>
      <span class="k">bodies/StageBodies.tsx</span><span>The housing fill and billboard colours follow the work lights.</span>
      <span class="k">scene/frameRate.ts</span><span>New, pure: trailing-second count, median gap, idle. Its probe and chip live in <span class="mono">Stage3D</span>.</span>
      <span class="k">lib/stageViewpoint.ts · windowViews.ts</span><span>The <span class="mono">workLights</span> key in apply, launch and announce; <span class="mono">STAGE_WORK_LIGHTS_OPTION</span>.</span>
      <span class="k">render/StageRenderJob.tsx · api/stageRenderApi.ts</span><span>The request's work lights.</span>
      <span class="k">ai/RenderViewTool.kt · state/StageRenderService.kt</span><span>The <span class="mono">workLights</span> input and the frame field.</span>
    </div></div>
  </div>
</div>
<div class="cols" style="grid-template-columns:1fr 1fr; padding:16px 24px 0">
  <div class="sect">
    <span class="h2">Two sessions, one PR each</span>
    <div class="note"><p class="cap"><b>1 · The popover, Performance and the readout</b> (frontend). The menu rebuilt; Show and Labels per window; the stats store and the readout; the trigger naming a non-Output source. Docs: stage-vis §"The 3D renderer", frontend CLAUDE.md §Stage views.</p></div>
    <div class="note"><p class="cap"><b>2 · Work lights, and render_view's</b> (frontend + backend). The work-lights table and store, the shader's lift and its floor tuned in the cyc harness, the housings and billboards, the Screens segment and link, the tool input. Docs: the colour bullet, §"Rendering for render_view", mcp-engineering, root CLAUDE.md's frame line; the stage-light plan's §10 question 1 marked answered.</p></div>
  </div>
  <div class="sect">
    <span class="h2">Considered and declined</span>
    <div class="note no"><p class="cap"><b>Keep the dropdown and add submenus</b> (Haze ▸, Labels ▸, Light budget ▸). A submenu hides its current value until hovered, and every change is two hovers — a poor trade on the iPad.</p></div>
    <div class="note no"><p class="cap"><b>Two header buttons</b>, View and a gauge. The header already wraps at narrow widths (Edit drops to a second row at 800 px), and a control touched once per machine does not earn a permanent place.</p></div>
    <div class="note no"><p class="cap"><b>One scroll with a Performance disclosure.</b> Shorter than today, still taller than a 768 px screen once opened, and the readout's click has no clean place to land.</p></div>
    <div class="note no"><p class="cap"><b>Work lights as a slider</b>, or per machine. Off and on are enough to plot by, a level is one more number to argue with, and machine-wide work lights would light the hall screen's room too.</p></div>
  </div>
</div>`
  writeFileSync(`${OUT}/Model.dc.html`, doc('Stage view menu model', W, H, body))
}

writeFileSync(`${OUT}/canvas.json`, JSON.stringify({
  v: 3,
  attachments: {},
  boards: {
    'Menu.dc.html': { h: 1300, title: 'The View popover · View and Performance, and the height to scale', w: 1440, x: 0, y: 0 },
    'WorkLights.dc.html': { h: 1500, title: 'Work lights off and on, from real frames', w: 1440, x: 1520, y: 0 },
    'Readout.dc.html': { h: 1240, title: 'The frame-rate readout · states, placement, rules', w: 1440, x: 0, y: 1380 },
    'Model.dc.html': { h: 1180, title: 'The model · stored, wire, code, sessions', w: 1440, x: 1520, y: 1580 },
  },
}, null, 2) + '\n')
console.log('ok')
