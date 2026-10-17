// Generates effects.html, the one-page copy of ../effects-plan.md for human readers.
// `node gen.mjs` rewrites it. The figures are computed here, so every phase dial and waveform is
// drawn to the same rule the plan states (§3), not placed by eye.
import { writeFileSync } from 'node:fs'

const OUT = new URL('effects.html', import.meta.url).pathname

// ─── figures ──────────────────────────────────────────────────────────────────

const f = (n) => Math.round(n * 10) / 10

/** One row of heads, each a dial whose hand points at its phase (0 = up, clockwise). */
function dialRow({ y, phases, label, note, x0 = 168, gap = 54, r = 17, mark }) {
  let s = `<text x="12" y="${y + 5}" class="rl">${label}</text>`
  if (note) s += `<text x="12" y="${y + 20}" class="rn">${note}</text>`
  phases.forEach((p, i) => {
    const cx = x0 + i * gap
    const a = p * 2 * Math.PI
    const hx = cx + Math.sin(a) * (r - 4)
    const hy = y - Math.cos(a) * (r - 4)
    const cls = mark ? mark(i) : ''
    s += `<circle cx="${cx}" cy="${y}" r="${r}" class="dial ${cls}"/>`
    s += `<line x1="${cx}" y1="${y}" x2="${f(hx)}" y2="${f(hy)}" class="hand"/>`
    s += `<circle cx="${cx}" cy="${y}" r="2.2" class="hub"/>`
    s += `<text x="${cx}" y="${y + r + 14}" class="deg">${Math.round(p * 360) % 360}°</text>`
  })
  return s
}

const N = 8
const linear = (spread = 1) => Array.from({ length: N }, (_, i) => (spread * i) / N)
const blocks = (b) => Array.from({ length: N }, (_, i) => Math.floor(i / b) / Math.ceil(N / b))
const groups = (g) => Array.from({ length: N }, (_, i) => (i % g) / g)
const wings = (w) => {
  const per = N / w
  return Array.from({ length: N }, (_, i) => {
    const wing = Math.floor(i / per)
    const j = i % per
    const k = wing % 2 === 0 ? j : per - 1 - j
    return k / per
  })
}

function fig({ id, title, rows, h, caption, aria }) {
  const w = 168 + N * 54
  return `<figure id="${id}"><div class="figbox"><svg viewBox="0 0 ${w} ${h}" role="img" aria-label="${aria}">
  <text x="12" y="20" class="ft">${title}</text>${rows}</svg></div><figcaption>${caption}</figcaption></figure>`
}

const spreadFig = fig({
  id: 'fig-spread',
  title: 'Spread — how far apart the heads sit in the cycle',
  h: 300,
  aria: 'Eight heads at spread 100, 50 and 0 percent: their phases cover the whole cycle, half of it, or none of it.',
  rows: [
    dialRow({ y: 66, phases: linear(1), label: 'Spread 100 %', note: 'today, always' }),
    dialRow({ y: 156, phases: linear(0.5), label: 'Spread 50 %', note: 'a ripple' }),
    dialRow({ y: 246, phases: linear(0), label: 'Spread 0 %', note: 'unison' }),
  ].join(''),
  caption: 'Each dial is one head, its hand the phase the head is at when the first one is at 0°. Today a distributed effect always spreads over the whole cycle (<code>index / size</code>); every desk surveyed lets the operator say how much.',
})

const shapeFig = fig({
  id: 'fig-shape',
  title: 'Blocks, groups and wings over eight heads',
  h: 300,
  aria: 'Blocks of two share a phase in pairs; groups of two repeat the spread every second head; two wings mirror the spread about the centre.',
  rows: [
    dialRow({ y: 66, phases: blocks(2), label: 'Blocks 2', note: 'neighbours as one', mark: (i) => (Math.floor(i / 2) % 2 ? 'alt' : '') }),
    dialRow({ y: 156, phases: groups(2), label: 'Groups 2', note: 'every 2nd matches', mark: (i) => (i % 2 ? 'alt' : '') }),
    dialRow({ y: 246, phases: wings(2), label: 'Wings 2', note: 'mirrored halves', mark: (i) => (i >= 4 ? 'alt' : '') }),
  ].join(''),
  caption: 'The three ways the desks reshape a spread before it is applied. Today only two-wing mirrors exist, as the fixed orders <code>CENTER_OUT</code>, <code>EDGES_IN</code> and <code>SPLIT</code> (the last two give the same offsets).',
})

/** Waveform plot helper: samples fn(t) over [0, cycles), y in 0..1. */
function wave({ x, y, w, h, fn, cycles = 2, cls = 'wv', steps = 240 }) {
  let d = ''
  for (let k = 0; k <= steps; k++) {
    const t = (k / steps) * cycles
    const px = x + (k / steps) * w
    const py = y + h - fn(t) * h
    d += (k ? 'L' : 'M') + f(px) + ' ' + f(py)
  }
  return `<path d="${d}" class="${cls}"/>`
}

const frac = (t) => t - Math.floor(t)
const RGB = ['#e5484d', '#30a46c', '#3e63dd']
function colourStrip({ x, y, w, h, dir, cycles = 2, n = 48 }) {
  let s = ''
  for (let k = 0; k < n; k++) {
    let p = frac(((k + 0.5) / n) * cycles)
    if (dir === 'back') p = 1 - p
    if (dir === 'bounce') { const t = ((k + 0.5) / n) * cycles; const q = frac(t / 2); p = q < 0.5 ? q * 2 : 2 - q * 2 }
    s += `<rect x="${f(x + (k / n) * w)}" y="${y}" width="${f(w / n + 0.6)}" height="${h}" fill="${RGB[Math.min(2, Math.floor(p * 3))]}"/>`
  }
  return s
}

const dirFig = (() => {
  const W = 620, rows = [['Forward', 'fwd', (t) => frac(t)], ['Backward', 'back', (t) => 1 - frac(t)], ['Bounce', 'bounce', (t) => { const q = frac(t / 2); return q < 0.5 ? q * 2 : 2 - q * 2 }]]
  let s = `<text x="12" y="20" class="ft">Direction — the same cycle, run three ways</text>`
  rows.forEach(([label, dir, fn], i) => {
    const y = 44 + i * 78
    s += `<text x="12" y="${y + 22}" class="rl">${label}</text>`
    s += `<line x1="130" y1="${y + 40}" x2="${130 + 300}" y2="${y + 40}" class="axis"/>`
    s += wave({ x: 130, y, w: 300, h: 40, fn })
    s += colourStrip({ x: 460, y: y + 8, w: 150, h: 24, dir })
  })
  s += `<text x="280" y="${44 + 3 * 78 - 4}" class="rn" text-anchor="middle">a ramp, two cycles</text>`
  s += `<text x="535" y="${44 + 3 * 78 - 4}" class="rn" text-anchor="middle">Colour Cycle, red · green · blue</text>`
  return `<figure id="fig-direction"><div class="figbox"><svg viewBox="0 0 ${W} 280" role="img" aria-label="A ramp and a red-green-blue colour cycle run forward, backward and bouncing."> ${s}</svg></div>
  <figcaption>Direction belongs to the running effect, so one switch reverses any of them. Today a direction exists only where an effect wrote one in (Ramp Up and Ramp Down are two effects; Sweep has <code>pingPong</code>), and Colour Cycle has none.</figcaption></figure>`
})()

const fadeFig = (() => {
  const W = 640, x0 = 70, w = 540
  const go = 0.25, fade = 0.4 // as fractions of the plot
  const env = (t, snap, out) => {
    const p = snap ? (t >= go ? 1 : 0) : Math.max(0, Math.min(1, (t - go) / fade))
    return out ? 1 - p : p
  }
  const sine = (t) => 0.5 + 0.5 * Math.sin(t * 2 * Math.PI * 9)
  let s = `<text x="12" y="20" class="ft">Effect fade — the effect's size follows the cue's fade</text>`
  const rows = [['Today', true], ['Fade by size', false]]
  rows.forEach(([label, snap], i) => {
    const y = 44 + i * 120
    s += `<text x="12" y="${y + 14}" class="rl">${label}</text>`
    const gx = x0 + go * w, fx = x0 + (go + fade) * w
    s += `<line x1="${gx}" y1="${y + 2}" x2="${gx}" y2="${y + 96}" class="go"/><text x="${gx + 4}" y="${y + 12}" class="rn">GO</text>`
    if (!snap) s += `<line x1="${fx}" y1="${y + 2}" x2="${fx}" y2="${y + 96}" class="axis dash"/><text x="${fx + 4}" y="${y + 12}" class="rn">fade ends</text>`
    s += wave({ x: x0, y: y + 22, w, h: 32, cycles: 1, steps: 500, fn: (t) => 0.5 + (sine(t) - 0.5) * env(t, snap, true), cls: 'wv out' })
    s += wave({ x: x0, y: y + 62, w, h: 32, cycles: 1, steps: 500, fn: (t) => 0.5 + (sine(t) - 0.5) * env(t, snap, false), cls: 'wv' })
    s += `<text x="${x0 - 6}" y="${y + 42}" class="rn" text-anchor="end">Q4 pulse</text><text x="${x0 - 6}" y="${y + 82}" class="rn" text-anchor="end">Q5 pulse</text>`
  })
  return `<figure id="fig-fade"><div class="figbox"><svg viewBox="0 0 ${W} 290" role="img" aria-label="Today the outgoing cue's effect stops at GO and the incoming one starts at full size. Fading by size, the outgoing effect shrinks to nothing over the cue's fade while the incoming one grows."> ${s}</svg></div>
  <figcaption>Eos (Entry and Exit modes), Hog 4 (effect fade, delay and path) and MagicQ (FX fade) all fade an effect with the cue. <code>CueCrossfadeDriver</code> says snapping "matches Eos / grandMA / Hog 4"; the manuals say otherwise.</figcaption></figure>`
})()

const sizeFig = (() => {
  const W = 620, x0 = 130, w = 440
  let s = `<text x="12" y="20" class="ft">Size — one scale on every effect, about its centre</text>`
  ;[[1, 'Size 100 %'], [0.5, 'Size 50 %'], [0, 'Size 0 %']].forEach(([k, label], i) => {
    const y = 40 + i * 62
    s += `<text x="12" y="${y + 26}" class="rl">${label}</text>`
    s += `<line x1="${x0}" y1="${y + 22}" x2="${x0 + w}" y2="${y + 22}" class="axis dash"/>`
    s += wave({ x: x0, y: y + 2, w, h: 40, cycles: 3, fn: (t) => 0.5 + 0.5 * k * Math.sin(t * 2 * Math.PI) })
  })
  return `<figure id="fig-size"><div class="figbox"><svg viewBox="0 0 ${W} 230" role="img" aria-label="A sine at size 100, 50 and 0 percent, shrinking about its centre line."> ${s}</svg></div>
  <figcaption>Every desk has one Size (MagicQ 0–800 %, Eos 0–2000 %, Hog in the parameter's own units). Ours has none: each effect carries literal <code>min</code>/<code>max</code>, a radius or a range, and <code>intensityMultiplier</code> exists on the instance with nothing setting it. One size is also what a cue fade and a fader would move.</figcaption></figure>`
})()

// ─── the survey table ─────────────────────────────────────────────────────────

const M = { m: '<span class="v m">gap · matters</span>', p: '<span class="v p">partly there</span>', h: '<span class="v h">we have it</span>', o: '<span class="v o">leave out</span>' }
const ROWS = [
  ['Attributes', 'Any attribute through <em>Any Attrib</em> waveforms; iris and focus built-ins', 'Any attribute per fixture (manual shows dimmer, colour, position)', 'Linear relative effects on any parameter; step/absolute via <em>Param</em>', 'Any fixture function; size in degrees, slots, Hz', 'Engine drives any slider (zoom, iris, focus, gobo rotation); the picker offers dimmer and UV only', M.m],
  ['Wheels and slots', '<em>Abs</em> add mode; Palette FX (2col, 2pos, 2iris…)', 'Absolute steps', 'Absolute effects step between palettes', 'Size in slots', 'Raw bytes on the channel; no idea of a slot', M.p],
  ['Size', 'Size 0–100 %, ×8 with SHIFT', 'Change Size tool (no layer)', 'Size 0–2000 % on the instance', 'Size in the parameter’s units', 'Literal min/max per effect; nothing scales it', M.m],
  ['Rate', 'Speed; 100 masters, by family', 'Speed, SpeedMaster, Measure', 'Cycle time, Rate 0–2000 %, BPM', 'Rate (cpm), Beats', 'Beats on a speed master (usage, follow); seconds from S15', M.h],
  ['Phase offset', 'Initial spread', 'Phase (°)', '—', 'Offset (°)', '<code>phaseOffset</code>', M.h],
  ['Spread', 'Spread %, over 100 % allowed', 'Phase range, “0 thru 360”', 'Trail 0–200 %', 'Effect Spread wheel', 'Always the whole cycle', M.m],
  ['Over a selection', 'Selection order', 'Selection order, MAtricks', 'Selection order', 'Selection order, fanning', 'Only over a group; loose fixtures run in unison (<code>FU-FX-SELECTION-DISTRIBUTION</code>)', M.m],
  ['Order across heads', 'Fwd, Back, Centre In/Out, Odd/Even, L/R, Rnd', 'MAtricks, Shuffle', 'Selection order, Random Group', 'Fan modes', 'Nine distribution strategies', M.h],
  ['Direction', 'Bounce variants (1.9.8)', 'Forward, Backward, Alternate', 'Forward, Reverse, Bounce', 'Direction, Bounce', 'Only inside some effects; Colour Cycle has none', M.m],
  ['Wings', 'Via Direction and Chunks', 'MAtricks Wings', 'Bounce Channels', 'Fan To Centre', 'Two, fixed (Centre out, Edges in)', M.p],
  ['Blocks / groups', 'Segments, Parts, Chunks', 'Blocks, Groups', 'Grouping', 'Segments, Buddying', 'None (element mode only)', M.m],
  ['Width, softness', 'Width, Crossfade, Attack/Decay', 'Width, Transition, Accel/Decel', 'Step in/dwell/decay', 'Length', 'Per effect, under different names (duty, on ratio, fade ratio)', M.p],
  ['Fade with the cue', 'FX fade and delay', 'Not stated', 'Entry/Exit modes and times', 'Effect fade, delay, path', 'Effects snap at GO', M.m],
  ['Size and speed on a fader', 'Fader controls size or speed', 'Speed fader', 'Sub entry mode, Global FX fader', 'Scale rate or size', 'Tempo on a fader; no size', M.m],
  ['N-shot', 'One–Four Shot', 'NShot', 'Num cycles', 'N Shot', 'None', M.m],
  ['Effect palettes', 'FX palettes, linked', 'Shapes, presets, recipes', 'Presets (copied)', 'Effect palettes', 'Effect templates, which track', M.h],
  ['Waveform editor', 'User waveforms, Make FX', 'Phaser editor, steps', 'Drawn shapes', '—', 'Scripts (<code>.fx.kts</code>)', M.o],
  ['Pixel and grid FX', 'Pix Map FX', 'MAtricks grid, adaptive', '—', '—', 'None', M.o],
  ['Per-head override inside one effect', 'Advanced view', 'Per-fixture layers', 'Effect Channels', 'Per head', 'R3 splits the instance instead', M.o],
]

const table = `<div class="tablebox"><table class="survey">
<thead><tr><th>Feature</th><th>MagicQ</th><th>grandMA3</th><th>Eos</th><th>Hog 4</th><th>Ours</th><th>Verdict</th></tr></thead>
<tbody>${ROWS.map(([a, ...r]) => `<tr><th scope="row">${a}</th>${r.map((c) => `<td>${c}</td>`).join('')}</tr>`).join('\n')}</tbody></table></div>`


const DECISIONS = [
  ['D1', 'Effects fade by size with the cue', 'Over a cue’s fade an effect leaving shrinks to nothing and one arriving grows from nothing. One in both cues runs on.'],
  ['D2', 'Size, 0–100 %', 'How much of the effect reaches the stage over what is underneath. Stored on every effect row; the fade and a fader move it.'],
  ['D3', 'One effect over the selection', 'An ordered list of fixtures and groups, spread in selection order as a group is.'],
  ['D4', 'Spread %, Wings, Blocks, Groups', 'The four ways the desks shape a spread.'],
  ['D5', 'Direction on every effect', 'Forward, Backward or Bounce, applied to each head’s phase.'],
  ['D6', 'Beam built-ins', 'Zoom Breathe, Iris Pulse, Frost Pulse, Focus Pull, each on its own property.'],
  ['D7', 'Beam joins usage', 'A speed master can be tagged Beam, and new beam effects land on it.'],
  ['D8', 'Steps between named things', 'Palette Steps through value palettes; Slot Steps through a wheel’s option names. Resolved per head.'],
  ['D9', 'N-shot', 'An effect can run N cycles and stop.'],
  ['D10', 'A cue stack’s effect size', 'Runtime, like the grand master: a MIDI fader, a slider in the Show view, REST and WS.'],
  ['D11', 'The editor', 'Size and Direction beside Speed; a Spread section with a dial preview; N-shot under Advanced.'],
  ['D12', 'Before R3’s session 2', 'One effect over a selection lands first.'],
  ['D13', 'The programmer fades effects', 'A press, a release and Clear grow or shrink effects over the programmer fade.'],
]
const SESSIONS = [
  ['Session 1 · backend + editor', 'Size, direction and N-shot', 'The weight after the blend, direction on the phase, N-shot; stored and synced; Size and Direction in the editor.'],
  ['Session 2 · backend + editor', 'Shaping the spread', 'Blocks, wings, groups and spread in the distribution plan; the Spread section and its dials; a shared vector.'],
  ['Session 3 · backend + programmer', 'One effect over a selection', 'A selection target for effects; + Effect and Apply FX send the marquee’s heads in order.'],
  ['Session 4 · backend', 'Fading by size', 'The envelope on GO, GO TO, GO BACK and stop; the programmer’s press, release and Clear; Blind.'],
  ['Session 5 · backend + frontend', 'A stack’s effect size', 'Runtime per stack; REST, WS, a MIDI fader; a slider in the Show view and the phone runner.'],
  ['Session 6 · backend + frontend', 'Beam effects', 'Four beam built-ins, Beam in usage, Beam effect palettes, the picker’s Beam family.'],
  ['Session 7 · backend + editor', 'Palette Steps and Slot Steps', 'A steps output each target resolves per head; the two built-ins; palette and slot list editors.'],
]
const QUESTIONS = [
  ['What should a cue’s fade do to effects?', 'Fade by size (D1). Per-effect entry/exit modes and the snap were declined.'],
  ['Should an effect carry a Size?', '0–100 % over what is underneath (D2). A 0–200 % amplitude and a runtime-only size were declined.'],
  ['An effect on loose fixtures?', 'One effect over the selection, in selection order (D3).'],
  ['Which shaping parameters?', 'All four: Spread %, Wings, Blocks, Groups (D4).'],
  ['Where does direction live?', 'On every running effect (D5).'],
  ['How do effects reach beam?', 'Beam built-ins of their own (D6). Level effects on any beam slider were declined.'],
  ['Wheels and slots?', 'A steps effect; since a palette cannot hold a slot, Slot Steps by option name (D8).'],
  ['Which smaller features?', 'Size on a fader and N-shot (D9, D10). One Width/Softness and a rate scale were not taken.'],
  ['The first beam set?', 'Zoom Breathe, Iris Pulse, Frost Pulse, Focus Pull.'],
  ['A Beam speed master?', 'Yes (D7).'],
  ['Where in the editor?', 'Size and Direction up front, a Spread section (D11).'],
  ['What does a Size fader scale?', 'One cue stack’s effects (D10). Per family and per speed master were declined.'],
  ['This plan and R3’s session 2?', 'This plan’s selection session first (D12).'],
  ['The stack’s size, stored?', 'Runtime, like the grand master (D10).'],
  ['Do programmer gestures fade effects?', 'Yes, at the programmer fade (D13).'],
]

// ─── page ─────────────────────────────────────────────────────────────────────

const STATUS = 'Agreed'

const html = `<title>Effects Plan</title>
<meta name="color-scheme" content="light dark">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Public+Sans:ital,wght@0,400;0,500;0,600;0,800;1,400&family=Martian+Mono:wght@400;500&display=swap">
<style>
  /* One reading column. Colour carries meaning: violet = an effect, coral = a gap that matters,
     amber = partly there, teal = we have it, grey = left out on purpose. */
  :root {
    --bg:#f3f3f6; --panel:#ffffff; --fg:#18171d; --muted:#5f5d6b; --line:#d9d8e0; --soft:#ebeaf0;
    --fx:#6e3fc8; --gap:#c2462e; --part:#a86a00; --have:#0f7a6e; --out:#7a7887;
    --fx-t:rgba(110,63,200,0.10); --alt:rgba(194,70,46,0.10);
    --sans:"Public Sans", ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif;
    --mono:"Martian Mono", ui-monospace, Menlo, Consolas, monospace;
  }
  @media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) {
    --bg:#111015; --panel:#18171e; --fg:#ecebf2; --muted:#9d9aab; --line:#2d2b36; --soft:#201f27;
    --fx:#b994ff; --gap:#ff8f78; --part:#f2bd4f; --have:#5cd1c1; --out:#8f8c9c;
    --fx-t:rgba(185,148,255,0.14); --alt:rgba(255,143,120,0.14); color-scheme:dark; } }
  :root[data-theme="dark"] {
    --bg:#111015; --panel:#18171e; --fg:#ecebf2; --muted:#9d9aab; --line:#2d2b36; --soft:#201f27;
    --fx:#b994ff; --gap:#ff8f78; --part:#f2bd4f; --have:#5cd1c1; --out:#8f8c9c;
    --fx-t:rgba(185,148,255,0.14); --alt:rgba(255,143,120,0.14); color-scheme:dark; }

  body { background:var(--bg); color:var(--fg); font-family:var(--sans); font-size:15px; line-height:1.55; }
  .wrap { max-width:1120px; margin:0 auto; padding-left:max(16px, env(safe-area-inset-left, 0px)); padding-right:max(16px, env(safe-area-inset-right, 0px)); padding-block:32px 72px; display:flex; flex-direction:column; gap:46px; }
  header { display:flex; flex-direction:column; gap:12px; }
  .eyebrow { font:500 11px var(--mono); letter-spacing:0.06em; text-transform:uppercase; color:var(--muted); display:flex; flex-wrap:wrap; gap:6px 16px; }
  .eyebrow .status { color:var(--fx); }
  h1 { margin:0; font-size:clamp(30px, 5.2vw, 48px); line-height:1.05; font-weight:800; letter-spacing:-0.025em; text-wrap:balance; max-width:22ch; }
  h1 em { font-style:normal; color:var(--fx); }
  .lede { margin:0; max-width:68ch; color:var(--muted); font-size:16.5px; }
  nav.toc { position:sticky; top:env(safe-area-inset-top, 0px); z-index:3; background:var(--bg); border-bottom:1px solid var(--line); padding-block:10px; display:flex; flex-wrap:wrap; gap:6px; margin-top:-22px; }
  nav.toc a { font-size:13px; font-weight:500; color:var(--fg); text-decoration:none; border:1px solid var(--line); border-radius:6px; padding:3px 10px; background:var(--panel); }
  nav.toc a:hover, nav.toc a:focus-visible { border-color:var(--fx); outline:none; }
  section { display:flex; flex-direction:column; gap:14px; scroll-margin-top:64px; }
  h2 { margin:0; font-size:24px; line-height:1.2; font-weight:800; letter-spacing:-0.01em; text-wrap:balance; }
  h3 { margin:8px 0 0; font-size:16px; font-weight:600; }
  p { margin:0; max-width:72ch; }
  .muted { color:var(--muted); }
  code { font-family:var(--mono); font-size:0.8em; background:var(--soft); border-radius:4px; padding:1px 4px; overflow-wrap:anywhere; }
  ul.plain { margin:0; padding-left:20px; max-width:74ch; display:flex; flex-direction:column; gap:6px; }
  a { color:var(--fx); }

  .tablebox { overflow-x:auto; border:1px solid var(--line); border-radius:8px; background:var(--panel); }
  table { border-collapse:collapse; width:100%; font-size:13.5px; }
  th, td { text-align:left; vertical-align:top; padding:9px 11px; border-bottom:1px solid var(--line); }
  tbody tr:last-child th, tbody tr:last-child td { border-bottom:none; }
  thead th { font:500 11px var(--mono); letter-spacing:0.05em; text-transform:uppercase; color:var(--muted); background:var(--soft); white-space:nowrap; }
  tbody th { font-weight:600; white-space:nowrap; }
  table.survey { min-width:1040px; }
  table.survey td:nth-child(6) { background:var(--fx-t); }
  .v { display:inline-block; font:500 10.5px var(--mono); border-radius:4px; padding:1px 6px; border:1px solid currentColor; white-space:nowrap; }
  .v.m { color:var(--gap); } .v.p { color:var(--part); } .v.h { color:var(--have); } .v.o { color:var(--out); }
  .legend { display:flex; flex-wrap:wrap; gap:8px 18px; font-size:13px; color:var(--muted); }

  figure { margin:0; display:flex; flex-direction:column; gap:8px; }
  .figbox { overflow-x:auto; background:var(--panel); border:1px solid var(--line); border-radius:8px; padding:8px; }
  .figbox svg { display:block; width:100%; min-width:560px; height:auto; }
  figcaption { font-size:13.5px; color:var(--muted); max-width:78ch; }
  .figs { display:grid; grid-template-columns:repeat(auto-fit, minmax(min(100%, 480px), 1fr)); gap:22px; }
  svg text { fill:currentColor; font-family:var(--sans); }
  svg .ft { font-size:14px; font-weight:600; }
  svg .rl { font-size:13px; font-weight:600; }
  svg .rn, svg .deg { font-size:11px; fill:var(--muted); font-family:var(--mono); }
  svg .deg { text-anchor:middle; }
  svg { color:var(--fg); }
  svg .dial { fill:var(--panel); stroke:var(--fx); stroke-width:1.5; }
  svg .dial.alt { fill:var(--alt); }
  svg .hand { stroke:var(--fx); stroke-width:2.4; stroke-linecap:round; }
  svg .hub { fill:var(--fx); }
  svg .wv { fill:none; stroke:var(--fx); stroke-width:2; }
  svg .wv.out { stroke:var(--muted); }
  svg .axis { stroke:var(--line); stroke-width:1; }
  svg .dash { stroke-dasharray:4 4; }
  svg .go { stroke:var(--gap); stroke-width:1.5; }

  .cards { display:grid; grid-template-columns:repeat(auto-fit, minmax(min(100%, 300px), 1fr)); gap:12px; }
  .card { background:var(--panel); border:1px solid var(--line); border-radius:8px; padding:12px 14px; min-width:0; font-size:14px; }
  .card b { display:block; margin-bottom:3px; font-size:15px; }
  .card .n { font:500 11px var(--mono); color:var(--muted); display:block; margin-bottom:4px; }
  .pending { color:var(--part); font:500 11px var(--mono); }
  footer { font-size:13px; color:var(--muted); border-top:1px solid var(--line); padding-top:16px; }
  @media (prefers-reduced-motion: reduce) { * { scroll-behavior:auto; } }
</style>
<div class="wrap">
<header>
  <div class="eyebrow"><span>Venue-visit R4</span><span>docs/plans/completed/effects-plan.md</span><span class="status">${STATUS}</span><span>2026-10-15</span></div>
  <h1>Effects a theatre desk can <em>lean on</em></h1>
  <p class="lede">What MagicQ, grandMA3, Eos and Hog 4 let an operator do with an effect, set against our 24 built-ins and the parameters they take. Which gaps matter for a theatre and busking desk, which are big-desk features to leave out, and the sessions that close the ones that matter.</p>
</header>
<nav class="toc" aria-label="Sections"><a href="#survey">Survey</a><a href="#new">New parameters</a><a href="#ours">What we found</a><a href="#decisions">Decisions</a><a href="#surfaces">On screen</a><a href="#sessions">Sessions</a><a href="#questions">Questions</a></nav>

<section id="survey">
  <h2>Four desks against ours</h2>
  <p>Read from each manufacturer's current manual; the citations are in <code>effects-design/survey.md</code>. A desk's own word is used where it has one. “Not stated” means the manual is silent, not that the desk cannot.</p>
  <div class="legend"><span>${M.m} a theatre or busking operator would miss it</span><span>${M.p} the engine half-has it</span><span>${M.h} parity or better</span><span>${M.o} a big-desk feature</span></div>
  ${table}
</section>

<section id="new">
  <h2>The parameters that are new to us</h2>
  <p>Spread, blocks, groups and wings shape where each head sits in the cycle; direction runs the cycle itself; size and fade scale what the cycle produces. The first four belong to the distribution, the last three to the running effect.</p>
  <div class="figs">${spreadFig}${shapeFig}</div>
  <div class="figs">${dirFig}${sizeFig}</div>
  ${fadeFig}
</section>

<section id="ours">
  <h2>What we found in our code</h2>
  <ul class="plain">
    <li><b>The engine can already drive zoom.</b> <code>FxTargetFactory</code> resolves any slider by name and checks only the output type, so Sine Wave on <code>zoom</code> works through REST, a cue or the AI. The picker never offers it: every slider built-in declares <code>[dimmer, uv]</code> and a new definition gets <code>[dimmer]</code>.</li>
    <li><b>A setting (colour wheel, gobo slot) takes raw bytes</b> through <code>SettingTarget</code>, always overriding. There is no slot-aware output.</li>
    <li><b>Spread is fixed at the whole cycle.</b> <code>LINEAR</code> is <code>index / size</code>; <code>EDGES_IN</code> and <code>SPLIT</code> give the same offsets.</li>
    <li><b>Direction is an order, not a run.</b> <code>REVERSE</code> and <code>PING_PONG</code> reorder heads; reversing the waveform itself is up to each script.</li>
    <li><b>Effects snap at GO</b> (<code>CueStackManager</code>, <code>CueCrossfadeDriver</code>). <code>intensityMultiplier</code> exists and nothing sets it.</li>
    <li><b>Effect rows store their fields as columns</b> (<code>look_effects</code>, cue effects, a template's effect), so a new field on the instance is a <code>formatVersion</code> bump (27 today, more after S15 and S16); a new parameter of one effect is a key in <code>parameters</code> and is not.</li>
    <li><b>S15 has not landed.</b> This plan builds on it as agreed: an effect's timing is a speed master or seconds, and a new effect is one bar on its family's usage master.</li>
  </ul>
</section>

<section id="decisions">
  <h2>Decisions</h2>
  <p>Agreed with Chris on 2026-10-15, in five rounds.</p>
  <div class="cards">${DECISIONS.map(([n, b, t]) => `<div class="card"><span class="n">${n}</span><b>${b}</b>${t}</div>`).join('')}</div>
  <h3>And what the plan adds</h3>
  <ul class="plain">
    <li><b>Size is a weight applied after the blend</b>: <code>out = before + (blended − before) × w</code>, with <code>w = size × envelope × stack size</code>. It replaces the raw-output scaling that once dropped an Override effect to 0.</li>
    <li><b>A continuing effect is matched by its Look effect uuid and target</b>, which survive a contents edit since <code>652128a</code>; anything else leaves and arrives.</li>
    <li><b>The desk owns every rule.</b> The editor's dial preview is the one client copy, of the spread's shape, pinned to the desk's by a shared vector.</li>
    <li><b>Three <code>formatVersion</code> bumps</b>, one per session that stores a field (1, 2, 3); the step lists ride <code>parameters</code>. No stored effect changes meaning.</li>
  </ul>
</section>

<section id="surfaces">
  <h2>What changes on screen</h2>
  <p><b>The effect editor changes once</b>, and that carries the new parameters to every host: the FX tray, the rail's popover, the add sheet, the effect-palette editor and the busk Effects tab, which all mount <code>FxEffectFields</code>. Size and Direction sit beside Speed; a Spread section holds the order, Spread %, Wings, Blocks and Groups over a row of phase dials; <em>Runs</em> (N-shot) joins Advanced.</p>
  <p><b>The busk view's chrome does not change</b> beyond a pad's detail line (<em>Colour Cycle · 1 bar · M2 · ← · 60 %</em>). The size fader is a cue stack's, so it lives on the stack in the Show view and on the busk Show tab's runner, and binds to a MIDI fader. The picker gains a Beam family.</p>
</section>

<section id="sessions">
  <h2>Seven sessions, one PR each</h2>
  <div class="cards">${SESSIONS.map(([n, b, t]) => `<div class="card"><span class="n">${n}</span><b>${b}</b>${t}</div>`).join('')}</div>
  <p class="muted">S15 lands first. Session 3 lands before R3's session 2, which builds its press and split on it.</p>
</section>

<section id="questions">
  <h2>Questions asked</h2>
  <div class="tablebox"><table><thead><tr><th>Question</th><th>Answer</th></tr></thead><tbody>
  ${QUESTIONS.map(([q, a]) => `<tr><td>${q}</td><td>${a}</td></tr>`).join('')}
  </tbody></table></div>
</section>

<footer>The checked-in <code>docs/plans/completed/effects-design/effects.html</code> is the authority; this page is a copy for reading. Generated by <code>gen.mjs</code> beside it.</footer>
</div>
`

writeFileSync(OUT, html)
console.log('wrote', OUT, html.length)
