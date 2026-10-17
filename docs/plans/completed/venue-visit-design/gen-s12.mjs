// Generates `Fan.dc.html`, venue-visit plan S12's one board: Spread's relative mode.
// `node gen-s12.mjs` in this directory rewrites it. Dark-only, in the editor-kit boards' vocabulary:
// the stylesheet and the icon paths are read out of `../../editor-kit-design/gen.mjs`, so the two never
// drift, and every measurement is the shipped busk Spread tab's where a board and the tab disagree.
import { readFileSync, writeFileSync } from 'node:fs'

const kit = readFileSync(new URL('../../editor-kit-design/gen.mjs', import.meta.url), 'utf8')
const T = Function(`return ${/const T = (\{[\s\S]*?\n\})/.exec(kit)[1]}`)()
const P = Function(`return ${/const P = (\{[\s\S]*?\n\})/.exec(kit)[1]}`)()
// The kit's stylesheet is a template literal over `T`; evaluate it with the same tokens.
const CSS = Function('T', `return \`${/const CSS = `([\s\S]*?)`\nconst HEAD/.exec(kit)[1]}\``)(T)

const ico = (name, size = 14, style = '') =>
  `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="flex:0 0 auto;${style}"><path d="${P[name]}"/></svg>`

// ---- primitives (the kit's, restated: gen.mjs is a script, not a module) ---------------------------
const seg = (items, on, extra = '') => `<div class="seg" style="${extra}">${items.map((t) => `<span class="${t === on ? 'on' : ''}${typeof t === 'object' ? ' off' : ''}">${typeof t === 'object' ? t.t : t}</span>`).join('')}</div>`
const lbl = (t, extra = '') => `<span class="lbl" style="${extra}">${t}</span>`
const btn = (label, kind = 'out', icon = null, extra = '') => `<span class="btn ${kind}" style="${extra}">${icon ? ico(icon, 12) : ''}${label ? `<span>${label}</span>` : ''}</span>`
const liveBtn = (on) => `<span class="btn ${on ? 'pri' : 'out'}"><span style="width:8px; height:8px; border-radius:50%; background:${on ? T.pfg : 'oklch(0.705 0.015 286.067 / 0.6)'}; display:inline-block;"></span>Live</span>`
const field = (t, extra = '', cls = '') => `<div class="field ${cls}" style="${extra}">${t}</div>`
const note = (html, kind = '') => `<div class="note ${kind}"><p>${html}</p></div>`
const notes = (list, kind = '') => `<div class="note ${kind}">${list.map((p) => `<p>${p}</p>`).join('')}</div>`
const title = (h1, cap) => `<div><div class="h1">${h1}</div><p class="cap" style="margin-top:4px; max-width:1180px;">${cap}</p></div>`
const sec = (t) => `<div class="row" style="gap:10px;"><span class="lbl" style="font-size:10px;">${t}</span><span style="flex:1; height:1px; background:var(--bd);"></span></div>`
const frameLabel = (t, sub = '') => `<div class="col" style="gap:2px; margin-bottom:2px;"><span class="lbl" style="font-size:10px;">${t}</span>${sub ? `<span class="cap9">${sub}</span>` : ''}</div>`
const tag = (t, k) => `<span class="tag ${k}">${t}</span>`

// The board's own: the centred amount strip, the fan curve pictures (a midline for each head's own
// position), and the encoder ring.
const EXTRA = `
    .amt { position:relative; height:28px; flex:1; min-width:0; border-radius:8px; border:1px solid var(--bd); background:oklch(0.274 0.006 286.033 / 0.3); overflow:hidden; }
    .amt .mid { position:absolute; left:50%; top:4px; bottom:4px; width:1px; background:var(--mfg); opacity:0.6; }
    .amt .fill { position:absolute; top:9px; height:8px; border-radius:4px; background:var(--pri); opacity:0.8; }
    .amt .knob { position:absolute; top:5px; width:6px; height:16px; margin-left:-3px; border-radius:3px; background:var(--fg); box-shadow:0 0 0 1px var(--bd); }
    .amt .tick { position:absolute; bottom:2px; font-size:8px; color:var(--mfg); }
    .amt.drag { border-color:var(--pri); box-shadow:0 0 0 3px oklch(0.623 0.214 259.815 / 0.3); }
    .cv svg.fan { width:44px; height:22px; }
    .ring { position:relative; width:96px; height:96px; border-radius:50%; border:1px solid var(--bd); background:var(--card); flex:0 0 auto; }
    .ring i { position:absolute; width:6px; height:6px; margin:-3px 0 0 -3px; border-radius:50%; background:var(--muted); }
    .ring i.on { background:var(--amber); box-shadow:0 0 6px var(--amber); }
    .ring b { position:absolute; inset:26px; border-radius:50%; background:var(--bg); border:1px solid var(--bd); display:flex; align-items:center; justify-content:center; font-size:11px; font-weight:600; }
    .heads { position:relative; border:1px solid var(--bd); border-radius:8px; background:oklch(0.274 0.006 286.033 / 0.15); }
    .wire { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size:10.5px; line-height:1.5; color:var(--fg); white-space:pre; margin:0; }
    .wire .c { color:var(--mfg); }
    .wire .n { color:#86efac; }
`

const W = 1440
const H = 3260

// ---- the amount strip: −128 … +127, 0 in the middle ------------------------------------------------
function amountStrip(value, { drag = false } = {}) {
  const at = (v) => ((v + 128) / 255) * 100
  const mid = at(0)
  const lo = Math.min(mid, at(value))
  const hi = Math.max(mid, at(value))
  return `<div class="amt ${drag ? 'drag' : ''}"><span class="mid" style="left:${mid}%;"></span><span class="fill" style="left:${lo}%; width:${hi - lo}%;"></span><span class="knob" style="left:${at(value)}%;"></span><span class="tick" style="left:4px;">−128</span><span class="tick" style="right:4px;">+127</span></div>`
}
const sign = (v) => (v > 0 ? `+${v}` : v < 0 ? `−${-v}` : '0')
function amountRow(axis, value, opts = {}) {
  return `<div class="row" style="gap:6px;">${lbl(axis, 'width:30px;')}${amountStrip(value, opts)}${field(`<span class="mono num">${sign(value)}</span>`, 'width:56px; flex:0 0 auto; justify-content:flex-end;', opts.focus ? 'focus' : '')}</div>`
}

// The four curve pictures, as a fan reads them: the dashed midline is each head's own position, the
// bottom −A and the top +A.
const fanPics = {
  Line: '<path d="M2 20 42 2"/>',
  Mirror: '<path d="M2 2 22 20 42 2"/>',
  Arrow: '<path d="M2 20 22 2 42 20"/>',
  Wings: '<path d="M2 2 19 20M25 20 42 2M22 16v5"/>',
}
const fanCurveRow = (on = 'Line') => `<div class="curve">${Object.entries(fanPics).map(([n, p]) => `<div class="cv ${n === on ? 'on' : ''}"><svg class="fan" viewBox="0 0 44 22" fill="none" stroke="currentColor" stroke-width="2"><path d="M2 11h40" stroke-dasharray="2 3" stroke-width="1" opacity="0.6"/>${p}</svg><span>${n}</span></div>`).join('')}</div>`

/**
 * The panel in Fan. `host`: 'popover' | 'docked'. `axes`: 'Pan' | 'Tilt' | 'Both'.
 */
function fanPanel({
  host = 'popover', width = 336, axes = 'Both', pan = 40, tilt = -12, drag = null, live = false, curve = 'Line', order = 'Rig',
  parts = '1', over = 'Heads', readout, headLine = null, save = null, footerNote = null, tabs = null, focus = false,
}) {
  const rows = [
    axes !== 'Tilt' ? amountRow('Pan', pan, { drag: drag === 'Pan', focus }) : '',
    axes !== 'Pan' ? amountRow('Tilt', tilt, { drag: drag === 'Tilt' }) : '',
  ].join('')
  const body = `${headLine ? `<div class="readout" style="margin-bottom:-2px;">${headLine}</div>` : ''}
    ${seg(['Intensity', 'Colour', 'Position', 'Beam'], 'Position')}
    <div class="row" style="gap:8px;">${seg(['Ends', 'Fan'], 'Fan', 'flex:0 0 112px;')}${seg(['Pan', 'Tilt', 'Both'], axes, 'flex:1;')}</div>
    <div class="col" style="gap:4px;">${lbl('Amount · the outer heads, centre steps')}${rows}</div>
    <div class="readout">${readout}</div>
    <div class="col" style="gap:4px;">${lbl('Curve')}${fanCurveRow(curve)}</div>
    <div class="col" style="gap:4px;">${lbl('Order')}${seg(['Rig', 'Reverse', 'Centre', 'Random'], order)}</div>
    <div class="row" style="gap:12px; align-items:flex-end;"><div class="col" style="gap:4px; flex:1;">${lbl('Parts')}<div class="row" style="gap:4px;">${seg(['1', '2', '3', '4'], parts, 'flex:1;')}${field(`<span class="mono num">${parts}</span>`, 'width:48px; height:28px;')}</div></div><div class="col" style="gap:4px;">${lbl('Over')}${seg(['Heads', 'Cells'], over)}</div></div>`
  const foot = `<div class="foot">${save ? btn(save, 'out', 'save') : ''}${footerNote ? `<span class="cap9" style="min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">${footerNote}</span>` : ''}<span style="flex:1;"></span>${liveBtn(live)}${btn(live ? 'Send again' : 'Apply', live ? 'out' : 'pri')}</div>`
  return `<div class="${host === 'docked' ? 'rail' : 'pop'}" style="width:${width}px;">${tabs ?? ''}<div class="body">${body}</div>${foot}</div>`
}
const reread = `<span style="margin-left:auto; color:var(--fg); text-decoration:underline; text-underline-offset:2px;">Re-read</span>`
const railTabs = `<div class="tabs"><span>${ico('panel', 12)}Stack</span><span>${ico('palette', 12)}Colour</span><span class="on">${ico('wave', 12)}Spread</span></div>`
const buskTabs = `<div class="tabs"><span>${ico('clock', 12)}Speed</span><span>${ico('palette', 12)}Colour</span><span class="on">${ico('wave', 12)}Spread</span><span>${ico('play', 12)}Show</span><span class="fold">${ico('chevR', 14)}</span></div>`

// ---- the order diagram: heads along the order, each one's own beam and its fanned one ------------
function headsDiagram({ n = 7, amount = 30, label, groupNames = null, parts = 1, w = 560 }) {
  const h = 150
  const pad = 36
  const step = (w - pad * 2) / Math.max(1, n - 1)
  const runs = []
  const base = Math.floor(n / parts)
  const extra = n % parts
  let at = 0
  for (let i = 0; i < parts; i++) {
    const size = base + (i < extra ? 1 : 0)
    runs.push([at, size])
    at += size
  }
  const offsets = new Array(n).fill(0)
  for (const [start, size] of runs) for (let j = 0; j < size; j++) offsets[start + j] = size > 1 ? amount * (2 * (j / (size - 1)) - 1) : -amount
  const x = (i) => pad + i * step
  const y0 = 108
  const svg = offsets.map((o, i) => {
    const ang = (o / 128) * 60
    const len = 78
    const rad = (ang * Math.PI) / 180
    const ox = x(i) + Math.sin(rad) * len
    const oy = y0 - Math.cos(rad) * len
    return `<line x1="${x(i)}" y1="${y0}" x2="${x(i)}" y2="${y0 - len}" stroke="${T.mfg}" stroke-width="1" stroke-dasharray="2 3" opacity="0.7"/>
      <line x1="${x(i)}" y1="${y0}" x2="${ox.toFixed(1)}" y2="${oy.toFixed(1)}" stroke="${T.amber}" stroke-width="2" stroke-linecap="round"/>
      <circle cx="${x(i)}" cy="${y0}" r="6" fill="${T.card}" stroke="${T.fg}" stroke-width="1.5"/>
      <text x="${x(i)}" y="${y0 + 20}" text-anchor="middle" font-size="10" fill="${T.mfg}">${groupNames ? groupNames[i] : i + 1}</text>
      <text x="${x(i)}" y="${y0 + 33}" text-anchor="middle" font-size="10" fill="${T.fg}" font-family="ui-monospace, Menlo, monospace">${sign(Math.round(o))}</text>`
  }).join('')
  const dividers = runs.slice(1).map(([start]) => `<line x1="${(x(start - 1) + x(start)) / 2}" y1="20" x2="${(x(start - 1) + x(start)) / 2}" y2="${h - 2}" stroke="${T.border}" stroke-width="1"/>`).join('')
  return `<div class="col" style="gap:4px;">${frameLabel(label)}<div class="heads" style="width:${w}px;"><svg width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">${dividers}${svg}</svg></div></div>`
}

// ---- the encoder ring: 11 LEDs, the centre one 0 --------------------------------------------------
function ring(lit, label) {
  const dots = Array.from({ length: 11 }, (_, i) => {
    const a = (-135 + i * 27) * (Math.PI / 180)
    const cx = 48 + Math.sin(a) * 40
    const cy = 48 - Math.cos(a) * 40
    return `<i class="${lit.includes(i) ? 'on' : ''}" style="left:${cx.toFixed(1)}px; top:${cy.toFixed(1)}px;"></i>`
  }).join('')
  return `<div class="col" style="gap:6px; align-items:center;"><div class="ring">${dots}<b>${label}</b></div></div>`
}

// ---- the position cell's popup, with its footer ---------------------------------------------------
function positionPopup({ scope = '6 heads · Local', aim = 'live', spread = 'live' }) {
  const pad = `<div class="xypad" style="width:120px; height:120px; flex:0 0 auto;"><i style="left:56%; top:38%;"></i></div>`
  const fields = `<div class="col" style="gap:8px; flex:1; min-width:0;"><div class="row" style="gap:6px;">${lbl('Pan', 'width:30px;')}${field('<span class="mono num">+14</span>', 'flex:1;')}</div><div class="row" style="gap:6px;">${lbl('Tilt', 'width:30px;')}${field('<span class="mono num">−30</span>', 'flex:1;')}</div></div>`
  const bands = `<div class="col" style="gap:6px;">${lbl('Palettes')}<div class="row" style="gap:6px; flex-wrap:wrap;">${['Downstage', 'Drum riser', 'Audience'].map((n) => `<span class="chip">${n}</span>`).join('')}</div></div>`
  // At 288px Aim… and Spread… fold to their icons and the save keeps its word: the colour footer's rule.
  const verb = (icon, state) => btn(null, `out icon${state === 'refused' ? ' dim' : ''}`, icon)
  return `<div class="pop" style="width:288px;"><div class="body"><div class="readout"><span style="color:var(--fg); font-weight:600;">${scope}</span><span style="margin-left:auto;">Position</span></div>${bands}<div class="row" style="align-items:flex-start; gap:12px;">${pad}${fields}</div><div class="readout"><span>DMX 142 · 98</span><span style="margin-left:auto;">heads differ</span></div></div><div class="foot">${btn('Save as palette…', 'out', 'save')}<span style="flex:1;"></span>${verb('crosshair', aim)}${verb('wave', spread)}</div></div>`
}

// =====================================================================================================
function board() {
  const popover = fanPanel({
    headLine: `<span style="color:var(--fg); font-weight:600;">6 heads · Local</span><span style="margin-left:auto;">Position</span>`,
    readout: `<span>From where they are · 1 head at the end of travel</span>${reread}`,
  })
  const rail = fanPanel({
    host: 'docked', width: 300, tabs: railTabs, axes: 'Pan', pan: -64, drag: 'Pan', live: true,
    headLine: `<span style="color:var(--fg); font-weight:600;">4 heads · Local</span><span style="margin-left:auto;">Spread</span>`,
    readout: `<span>From where they are</span>${reread}`,
  })
  const busk = fanPanel({
    host: 'docked', width: 320, tabs: buskTabs, axes: 'Tilt', tilt: 24, save: 'Save as Look…',
    readout: `<span>From where they are · Movers (group order)</span>${reread}`,
  })
  const layer = fanPanel({
    width: 336, axes: 'Both', pan: 20, tilt: 8, footerNote: 'Lands in <b style="color:var(--fg);">Warm front</b>’s draft',
    headLine: `<span style="color:var(--fg); font-weight:600;">6 heads · Warm front</span><span style="margin-left:auto;">Position</span>`,
    readout: `<span>From the layer’s rows · 2 heads from the stage</span>${reread}`,
  })
  const cue = fanPanel({
    width: 336, axes: 'Pan', pan: 32,
    headLine: `<span style="color:var(--fg); font-weight:600;">6 heads · Q12</span><span style="margin-left:auto;">Position</span>`,
    readout: `<span>From Q12’s values · 6 of 6</span>${reread}`,
  })

  const calls = [
    ['1', 'The name', '<b>Ends · Fan</b>, a segment on the Position family\'s first row. Spread stays the verb, the panel and the button. The editor kit retired "Fan" as a second name for first→last, which is what Spread already did. This mode is something else: an offset around each head\'s own position, and "fan" is what other desks call that gesture. <i>Ends</i> names the mode Spread has today, the two endpoints.', '"Absolute · Relative" (it says how, not what) · "Spread · Fan" (the mode is named like the panel)'],
    ['2', 'Pan, Tilt, Both', 'A second segment, <b>Pan · Tilt · Both</b>, beside the mode. Both draws two amount rows, each with its own amount, so a wide pan fan and a shallow tilt fan are one gesture. Position is one property (<span class="mono">pan,tilt</span>), so the axis not fanned is written at its own base. The head\'s literal still names both.', 'One amount for both axes (rarely what is wanted) · a 2-D pad (one finger cannot hold one axis still)'],
    ['3', 'The amount', 'The <b>outer heads\' offset</b>, on S11\'s scale: −128…+127 centre steps, one step a coarse DMX unit. 0 is no fan. Negative crosses the fan. <b>Drag</b>: the strip is centred at 0; press anywhere and drag, ⇧ for ¼ speed, double-click to 0. With Live on every move is sent, as Spread\'s Live already is. <b>Field</b>: typed, clamped. <b>Encoder</b>: below.', 'A percentage of travel (S11 made position steps) · degrees (a head without a range has none)'],
    ['4', 'The centre', 'Symmetric about the <b>middle of the order</b>: head <i>i</i> of <i>n</i> moves <span class="mono">A × (2t − 1)</span>, where <i>t</i> is the fraction Spread already computes. Under Line the middle head stays put; with an even count the two middle heads move ±A/(n−1). The order is <b>rig order</b>, or, when the selection is one group, <b>that group\'s member order</b> (S7), as a spread is today. Parts makes each part its own fan about its own middle. Order and Curve keep their meanings, drawn against the midline: Mirror is a V, Random a scatter within ±A. Over: Cells fans a bar\'s cells.', 'Centre on the stage\'s centre line (needs placements; Aim\'s job) · a separate fan maths (two shape vocabularies)'],
    ['5', 'Where a head starts', '<b>Local, Row C, the rail and the busk tab</b>: Local\'s own value for the head where it holds one, which is what Record would capture. Otherwise, what the desk is sending now, park included, read the way <i>Set locate from current</i> reads it. Local wins because a fade in flight, a position effect or Blind make the output a poor base. <b>A focused Look layer and the Values grid</b>: the row being edited. A head with no position in the row starts from the stage, and the read-out says how many did.', 'Always the stage (loses a Blind or mid-fade value) · a head with no row starts at 0 (a fan from centre surprises)'],
    ['6', 'The anchor', 'The starting positions are read on the first send and held while the gesture lasts, so Live does not fan a fan. They are dropped when the selection changes, the panel closes, the mode leaves Fan, or <b>Re-read</b> is pressed. The amount then returns to 0. The desk answers each head\'s start (<span class="mono">base</span>) and the panel sends it back with every later request. The client does not compute positions; it only returns the desk\'s literals.', 'The desk keeps the anchor by request id (state on the server for a stateless route) · an incremental fan (Live drops intermediate sends)'],
    ['7', 'The encoder', 'A binding target, <b><span class="mono">SelectionFan(axis)</span></b> with axis Pan or Tilt, on any absolute encoder. It acts on the desk selection: CC 0…127 is −128…+126, 64 is 0, and the ring shows the amount. The desk holds the encoder\'s anchor. It drops it, and returns the ring to centre, when the selection changes or when anything else has moved a selected head\'s position since the fan last wrote it. The fan writes owner <span class="mono">surface</span>. It is not an encoder-bank property: the bank drives one head per strip, and a fan is one gesture over the whole selection.', 'An encoder-bank entry (per strip, so eight fans) · a relative encoder mode (the surface code is absolute-only)'],
    ['8', '<i>Spread…</i> in the footer', 'After <i>Aim…</i>: <b>Save as palette… · · Aim… · Spread…</b>, the colour footer\'s order (Spread last). At the popup\'s 288px the two fold to their icons, their words on the title, and the save keeps its word: the colour footer\'s fold, since the three worded buttons need about 300px. It opens the rail\'s Spread tab on Position, in Fan, at 0, over the cell\'s heads. A colour\'s <i>Spread…</i> seeds From; a fan needs no seed, because it starts where the heads are. Drawn in the programmer only, like the footer. In Output it is refused with Output\'s words. In a focused Look layer it stays live and lands in the layer, where <i>Aim…</i> is refused.', 'Open the popover at row C (two Spreads at once) · seed Ends with From = the cell (the gesture the cell already is)'],
    ['9', 'Scope', 'Fan is on <b>Position only</b>, as the plan says. Level, Zoom and the rest keep Ends. No <span class="mono">formatVersion</span>: a fan writes literals and stores nothing.', 'A relative mode for every percent property (not asked for)'],
  ]

  const wire = `<pre class="wire"><span class="c">// POST /projects/{id}/programmer/spread — the same route, one mode more</span>
{
  "targets": [{ "type": "group", "key": "movers" }],
  "families": ["POSITION"],
  "property": "position",
  <span class="n">"mode": "FAN",</span>                 <span class="c">// absent: "ENDS", byte for byte today's request</span>
  <span class="n">"fan": { "pan": 40, "tilt": -12 },</span> <span class="c">// centre steps; an absent axis is not fanned</span>
  <span class="n">"base": [</span>                     <span class="c">// absent on the first send: the desk reads each head</span>
    <span class="n">{ "target": { "type": "fixture", "key": "mover-1" }, "value": "142,98" }, …</span>
  <span class="n">],</span>
  "curve": "LINE", "order": "LINEAR", "parts": 1, "over": "HEADS",
  "write": false                <span class="c">// the Look layer and the Values grid, as today</span>
}
<span class="c">// 200</span>
{
  "written": [{ "target": …, "propertyName": "position", "value": "102,110" }, …],
  "skipped": [{ "target": …, "reason": "does not pan and tilt" }],
  <span class="n">"base": [{ "target": …, "value": "142,98" }, …],</span>  <span class="c">// where each head started: sent back next time</span>
  <span class="n">"clamped": [{ "type": "fixture", "key": "mover-6" }]</span>  <span class="c">// at the end of travel</span>
}</pre>`

  const html = `<div class="app" style="width:${W}px; padding:32px; display:flex; flex-direction:column; gap:22px;">
  ${title('S12 · Fan: Spread’s relative mode', 'Spread gains a second mode on Position: <b>Fan</b>, an <b>amount</b> around each head’s own position, symmetric about the middle of the selection’s order. It is the same panel (<span class="mono">components/editor/SpreadPanel.tsx</span>) in the same four hosts, the same route (<span class="mono">POST /programmer/spread</span>, which grows a mode) and the same Curve · Order · Parts · Over rows. Only the endpoints change: <i>From · To</i> becomes one <i>Amount</i> per axis. The desk does the arithmetic, as it does for every intent, so the panel never computes a position. The board answers the plan’s six questions as nine calls, at the foot.')}

  ${sec('1 · The panel in Fan, in each host')}
  <div class="row" style="align-items:flex-start; gap:20px;">
    <div class="col">${frameLabel('Row C · the popover, Both', 'fixtures-list/SpreadPopover.tsx · 336px · Local')}${popover}</div>
    <div class="col">${frameLabel('Programmer rail · the Spread tab, Pan, dragging', 'programmer/RailSpreadTab.tsx · 300px · Live on')}${rail}</div>
    <div class="col">${frameLabel('Busk · the Spread tab, Tilt', 'busking/SpreadSheet.tsx · 320px · the desk selection, one group')}${busk}</div>
  </div>
  <div class="row" style="align-items:flex-start; gap:20px;">
    <div class="col">${frameLabel('A focused Look layer', 'row C or the rail · write: false · lands in the layer’s draft')}${layer}</div>
    <div class="col">${frameLabel('The cue editor’s Values grid', 'cues/editor/CueValuesGrid.tsx · write: false · lands in the cue')}${cue}</div>
    <div class="col" style="width:420px; gap:10px;">
      ${note('<b>One row replaces From · To.</b> The first row is the mode and the axes, <i>Ends · Fan</i> and <i>Pan · Tilt · Both</i>, which fits 288px on one line. Below it is one amount row per axis: a centred drag strip and a typed field. The read-out under them says where the heads started and offers <b>Re-read</b>. It also names the heads the fan pushed to the end of travel. Ends draws exactly as today.', 'key')}
      ${note('<b>The family segment and the label line are as today.</b> The marquee answers Position and the panel opens one row down, in the mode it was last in. A colour or level marquee never shows the mode row.')}
      ${note('<b>Live is the fan gesture.</b> Live on with a drag is fanning in and out by eye, the reason Spread has Live. With Live off, the strip and the field set the amount and Apply sends it. Send again resends against the same anchor, so the result does not change.')}
      ${note('<b>The skipped heads are Spread’s.</b> A par in the marquee is skipped by name and takes no place in the order, as in Ends since editor-kit session 3.')}
    </div>
  </div>

  ${sec('2 · Where the centre sits')}
  <div class="row" style="align-items:flex-start; gap:24px; flex-wrap:wrap;">
    ${headsDiagram({ n: 7, amount: 30, label: 'Seven heads in rig order, amount +30: the middle one stays' })}
    ${headsDiagram({ n: 6, amount: 30, label: 'Six: the two middle heads move ±6', groupNames: ['M1', 'M2', 'M3', 'M4', 'M5', 'M6'] })}
    ${headsDiagram({ n: 8, amount: 24, parts: 2, label: 'Parts 2: two fans, each about its own middle' })}
    ${headsDiagram({ n: 5, amount: 40, label: 'One group selected: its member order (S7), not rig order', groupNames: ['SR-3', 'SR-1', 'C', 'SL-1', 'SL-3'] })}
  </div>
  <p class="cap" style="max-width:1180px;">The grey dashed line is each head’s own position (its base) and the amber line is the head after the fan. Under the heads are the offsets in centre steps, <span class="mono">A × (2t − 1)</span>, with <i>t</i> from <span class="mono">SpreadPlan.fractions</span> unchanged. A head’s result is clamped to its travel and named in <span class="mono">clamped</span>.</p>

  ${sec('3 · The encoder and the position cell’s footer')}
  <div class="row" style="align-items:flex-start; gap:28px;">
    <div class="col" style="gap:10px;">
      ${frameLabel('X-Touch · an encoder bound to SelectionFan(Pan)', 'absolute CC, ring fed back like the grand master’s fader')}
      <div class="row" style="gap:18px;">${ring([5], '0')}${ring([5, 6, 7, 8], '+48')}${ring([2, 3, 4, 5], '−64')}</div>
      <p class="cap" style="max-width:360px;">At 0 only the centre LED is lit. Turning right fans out, left crosses the fan, and back to the centre fans in. A new selection, or anything else moving a selected head, returns the ring to the centre: the next turn starts from where the heads are.</p>
      <div class="note" style="max-width:360px;"><p>The binding inspector lists it under <b>Selection</b> as <i>Fan pan</i> and <i>Fan tilt</i>, beside <i>Selection property</i>. Health is always <span class="mono">ok</span>: an empty selection drops the turn, as <span class="mono">SelectionProperty</span> does.</p></div>
    </div>
    <div class="col" style="gap:10px;">
      ${frameLabel('The position cell’s popup · Local', 'PaletteBands’ footer tools: Aim… then Spread…')}
      ${positionPopup({})}
    </div>
    <div class="col" style="gap:10px;">
      ${frameLabel('The same, in a focused Look layer', 'Aim… writes Local, so it is refused · Spread… lands in the layer')}
      ${positionPopup({ scope: '6 heads · Warm front', aim: 'refused' })}
    </div>
  </div>

  ${sec('4 · The wire')}
  <div class="row" style="align-items:flex-start; gap:24px;">
    <div class="note" style="flex:0 0 640px;">${wire}</div>
    <div class="col" style="gap:10px; flex:1;">
      ${note('<b>The route grows the mode; nothing new is stored.</b> <span class="mono">mode: FAN</span> replaces <span class="mono">from</span> and <span class="mono">to</span> with <span class="mono">fan</span>. Every other field keeps its meaning: the mask, the targets, member order for one group, the two passes that skip a head with no position, Over, and <span class="mono">write: false</span>. The answer’s <span class="mono">written[].value</span> is still the literal, so the Look-layer and Values-grid arms land it as they do today.', 'key')}
      ${note('<b>Defaulted fields, sent only when used.</b> An Ends request is byte for byte today’s, and the panel omits <span class="mono">mode</span>, <span class="mono">fan</span> and <span class="mono">base</span> outside Fan. The reason is the one that keeps <span class="mono">write</span> absent when true: this Json refuses an unknown key, and a desk mid-upgrade would otherwise 400 every spread.')}
      ${note('<b>The encoder calls the same function.</b> The route body and <span class="mono">SelectionFan</span> both go through one desk resolve, the encoder holding its own anchor. It writes owner <span class="mono">surface</span>, the panel owner <span class="mono">WEB</span>, as every other door does.')}
    </div>
  </div>

  ${sec('5 · The calls')}
  <table><tr><th style="width:28px;">#</th><th style="width:130px;">Question</th><th>Drawn</th><th style="width:300px;">Declined</th></tr>${calls.map(([n, q, a, d]) => `<tr><td><b>${n}</b></td><td><b>${q}</b></td><td class="y">${a}</td><td>${d}</td></tr>`).join('')}</table>
</div>`
  return html
}

const HEAD = `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
  <style>${CSS}${EXTRA}</style>
</helmet>`
const TAIL = `</x-dc>
<script data-dc-script data-props='{"$preview":{"width":${W},"height":${H}}}'>
class Component extends DCLogic {
  renderVals() { return {}; }
}
</script>
</body>
</html>`

writeFileSync(new URL('./Fan.dc.html', import.meta.url), `${HEAD}\n${board()}\n${TAIL}\n`)
console.log(`Fan ${W}×${H}`)
