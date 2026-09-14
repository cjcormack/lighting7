// Generates the sheet-views design artboards (patch list, channels, show as sheets).
// The tokens, icons, primitives, app header and sidebar below are copied verbatim from
// programmer-chrome-design/gen.mjs so the two records draw the same chrome; the SYS comments about
// "today" are that record's and describe the programmer as it now is.
import { writeFileSync } from 'node:fs'

// ---- tokens (dark) -------------------------------------------------------------------------
const T = {
  bg: 'oklch(0.141 0.005 285.823)',
  card: 'oklch(0.21 0.006 285.885)',
  muted: 'oklch(0.274 0.006 286.033)',
  border: 'oklch(0.274 0.006 286.033)',
  fg: 'oklch(0.985 0 0)',
  mfg: 'oklch(0.705 0.015 286.067)',
  primary: 'oklch(0.623 0.214 259.815)',
  pfg: 'oklch(0.21 0.006 285.885)',
  destructive: 'oklch(0.704 0.191 22.216)',
  green: 'oklch(0.723 0.219 149.579)',
  amber: 'oklch(0.828 0.189 84.429)',
  violet: 'oklch(0.606 0.25 292.717)',
  sky: 'oklch(0.685 0.169 237.323)',
}

// ---- proposed system --------------------------------------------------------------------------
const SYS = {
  gutter: 12, // every row, header included (today: header 16, rows 12, rail header 10)
  gap: 8, // between controls on a row
  row: 40, // rows A, B, C, the folded row, the rail header and the strip's chevron (today 40/36/34/36)
  header: 48, // ShowHeader (today 64; 48 only under 500px of height)
  control: 32, // every control on a row (today 32 and 28 mixed on row B; 32/26 on row C)
  nested: 28, // a control inside a control: Update/Revert in the source box, a template chip
  chip: 20, // a badge or pill
}

// ---- icons (lucide, 24-grid) -----------------------------------------------------------------
const P = {
  menu: 'M4 6h16M4 12h16M4 18h16',
  check: 'M9 12l2 2 4-4',
  circle: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Z',
  theater: 'M2 10h20M4 10v9h16v-9M8 5h8l1 5H7Z',
  grid: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z',
  gauge: 'M12 14l3.5-3.5M3.34 19a10 10 0 1 1 17.32 0',
  table: 'M3 3h18v18H3zM3 9h18M3 15h18M9 3v18',
  sparkles: 'm12 3 1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9ZM19 17l.8 2.2L22 20l-2.2.8L19 23l-.8-2.2L16 20l2.2-.8Z',
  slidersV: 'M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6',
  book: 'M4 5.5A2.5 2.5 0 0 1 6.5 3H20v18H6.5A2.5 2.5 0 0 0 4 18.5ZM12 7v10',
  wave: 'M2 12h3l2-6 3 12 3-9 2 5 2-3h5',
  chevR: 'm9 18 6-6-6-6',
  chevD: 'm6 9 6 6 6-6',
  chevL: 'm15 18-6-6 6-6',
  chevU: 'm18 15-6-6-6 6',
  circlePlus: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20ZM8 12h8M12 8v8',
  download: 'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3',
  upload: 'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12',
  refresh: 'M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8M21 3v5h-5M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16M8 16H3v5',
  layers: 'm12 2 9 4.5-9 4.5-9-4.5ZM3 12l9 4.5 9-4.5M3 17l9 4.5 9-4.5',
  eraser: 'm7 21-4.3-4.3c-1-1-1-2.5 0-3.4l9.6-9.6c1-1 2.5-1 3.4 0l5.6 5.6c1 1 1 2.5 0 3.4L13 21M22 21H7m5-11 9 9',
  eyeOff: 'M9.88 9.88a3 3 0 1 0 4.24 4.24M10.73 5.08A10.43 10.43 0 0 1 12 5c7 0 10 7 10 7a13.16 13.16 0 0 1-1.67 2.68M6.61 6.61A13.5 13.5 0 0 0 2 12s3 7 10 7a9.74 9.74 0 0 0 5.39-1.61M2 2l20 20',
  eye: 'M2 12s3-7 10-7 10 7 10 7-3 7-10 7-10-7-10-7ZM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6Z',
  hand: 'M18 11V6a2 2 0 0 0-4 0v1M14 10V4a2 2 0 0 0-4 0v2M10 10.5V6a2 2 0 0 0-4 0v8M18 8a2 2 0 1 1 4 0v6a8 8 0 0 1-8 8h-2c-2.8 0-4.5-.86-5.99-2.34l-3.6-3.6a2 2 0 0 1 2.83-2.82L7 15',
  search: 'M11 3a8 8 0 1 0 0 16 8 8 0 0 0 0-16Zm10 18-4.3-4.3',
  bulb: 'M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5M9 18h6M10 22h4',
  columns: 'M3 3h18v18H3zM9 3v18M15 3v18',
  key: 'M2.6 17.4A2 2 0 0 0 2 18.8V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.2a2 2 0 0 0 1.4-.6l.8-.8a6.5 6.5 0 1 0-4-4zM16.5 7.5h.01',
  marquee: 'M5 3a2 2 0 0 0-2 2M19 3a2 2 0 0 1 2 2M21 19a2 2 0 0 1-2 2M5 21a2 2 0 0 1-2-2M9 3h1M9 21h2M14 3h1M3 9v1M21 9v2M3 14v1m9-3 4 10 1.7-4.3L22 16Z',
  plus: 'M5 12h14M12 5v14',
  pencil: 'M21.17 6.83 17.17 2.83a2 2 0 0 0-2.83 0L3 14v5h5L21.17 9.66a2 2 0 0 0 0-2.83zM15 5l4 4',
  backspace: 'M10 5a2 2 0 0 0-1.34.51L2 12l6.66 6.49A2 2 0 0 0 10 19h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2zm2 4 6 6m0-6-6 6',
  crosshair: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20ZM22 12h-4M6 12H2M12 6V2M12 22v-4',
  flashlight: 'M18 6c0 2-2 2-2 4v10a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2V10c0-2-2-2-2-4V2h12zM6 6h12M12 13v1',
  fan: 'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6ZM3 12h6M15 12h6',
  x: 'M18 6 6 18M6 6l12 12',
  palette: 'M12 22a10 10 0 1 1 0-20c5.5 0 10 3.6 10 8a4 4 0 0 1-4 4h-1.5a2 2 0 0 0-1.5 3.3c.4.5.6 1 .6 1.7a2 2 0 0 1-2 2ZM7.5 10.5h.01M12 7h.01M16.5 10.5h.01',
  slidersH: 'M21 4h-6M9 4H3M21 12h-8M7 12H3M21 20h-4M11 20H3M15 2v4M7 10v4M17 18v4',
  arrows: 'm21 16-4 4-4-4M17 20V4M7 4 3 8m4-4 4 4M7 4v16',
  circleDot: 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm0 7a3 3 0 1 0 0 6 3 3 0 0 0 0-6Z',
}
const ico = (name, size = 14, style = '', fill = false) =>
  `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="${fill ? 'currentColor' : 'none'}" stroke="${fill ? 'none' : 'currentColor'}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="flex:0 0 auto;${style}"><path d="${P[name]}"/></svg>`
const dotFill = (size) =>
  `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="currentColor" style="flex:0 0 auto"><circle cx="12" cy="12" r="10"/></svg>`
const squareFill = (size) =>
  `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="currentColor" style="flex:0 0 auto"><rect x="4" y="4" width="16" height="16" rx="2"/></svg>`

// ---- primitives -------------------------------------------------------------------------------
const flex = (style, inner) => `<div style="display:flex; align-items:center; ${style}">${inner}</div>`
// shadcn Button size="sm": h-8 rounded-md text-xs font-medium gap-1.5 px-3 has-[>svg]:px-2.5
function btn({ icon, label, variant = 'outline', h = SYS.control, disabled = false, on = false, extra = '', word = true, mono = false }) {
  const iconOnly = !label || !word
  const pad = iconOnly ? 0 : icon ? 10 : 12
  let bg = 'transparent', bc = T.border, fg = T.fg
  if (variant === 'outline') bg = 'oklch(0.274 0.006 286.033 / 0.30)'
  if (variant === 'primary') { bg = T.primary; bc = T.primary; fg = T.pfg }
  if (variant === 'destructive') { bg = 'oklch(0.704 0.191 22.216 / 0.60)'; bc = 'transparent' }
  if (variant === 'ghost') { bg = 'transparent'; bc = 'transparent' }
  if (on) { bg = 'oklch(0.828 0.189 84.429 / 0.15)'; bc = 'oklch(0.769 0.188 70.08 / 0.60)'; fg = 'oklch(0.879 0.169 91.605)' }
  return `<div style="display:inline-flex; align-items:center; justify-content:center; gap:6px; height:${h}px; ${iconOnly ? `width:${h}px;` : ''} padding:0 ${pad}px; border-radius:6px; border:1px solid ${bc}; background:${bg}; color:${fg}; font-size:12px; font-weight:${variant === 'primary' ? 600 : 500}; white-space:nowrap; flex:0 0 auto; ${disabled ? 'opacity:0.5;' : ''}${mono ? 'font-family:ui-monospace,Menlo,monospace;' : ''}${extra}">${icon ?? ''}${iconOnly ? '' : `<span>${label}</span>`}</div>`
}
const divider = () => `<span style="width:1px; height:22px; background:${T.border}; flex:0 0 auto; align-self:center;"></span>`
const spacer = () => `<span style="flex:1 1 0; min-width:0;"></span>`
const zlab = (t, color = T.mfg, extra = '') =>
  `<span style="font-size:9px; font-weight:700; text-transform:uppercase; letter-spacing:0.1em; color:${color}; white-space:nowrap; flex:0 0 auto; ${extra}">${t}</span>`
const pill = (t, { fg = T.fg, bc = T.border, bg = 'transparent', dot = null } = {}) =>
  `<span style="display:inline-flex; align-items:center; gap:6px; height:${SYS.chip}px; padding:0 8px; border-radius:999px; border:1px solid ${bc}; background:${bg}; color:${fg}; font-size:10px; font-weight:500; white-space:nowrap; flex:0 0 auto;">${dot ? `<span style="width:6px;height:6px;border-radius:999px;background:${dot};"></span>` : ''}${t}</span>`
const countBadge = (n, color = T.fg) =>
  `<span style="display:inline-flex; align-items:center; justify-content:center; min-width:16px; height:14px; padding:0 4px; border-radius:999px; background:${T.muted}; color:${color}; font-size:9px; font-weight:600; font-variant-numeric:tabular-nums;">${n}</span>`

// ---- app header (Layout.tsx: px-2 py-2 sm:px-4, size-9 buttons; not in scope, drawn as is) ----
function appHeader({ vw, cw }) {
  const pad = vw >= 640 ? 16 : 8
  const gap = vw >= 640 ? 16 : 8
  const hamburger = vw < 768
  const title = cw >= 620
  const iconBtn = (name) =>
    `<span style="width:36px; height:36px; display:flex; align-items:center; justify-content:center; border-radius:6px; flex:0 0 auto;">${ico(name, 20)}</span>`
  const connected = cw >= 620
    ? `<span style="display:inline-flex; align-items:center; gap:8px; height:36px; padding:0 12px; border-radius:6px; border:1px solid ${T.green}; background:oklch(0.141 0.005 285.823 / 0.85); color:${T.green}; font-size:14px; font-weight:500;">${ico('check', 18)}Connected</span>`
    : `<span style="width:36px; height:36px; display:flex; align-items:center; justify-content:center; border-radius:6px; border:1px solid ${T.green}; background:oklch(0.141 0.005 285.823 / 0.85); color:${T.green};">${ico('check', 18)}</span>`
  return `<header style="height:52px; flex:0 0 auto; display:flex; align-items:center; gap:${gap}px; padding:0 ${pad}px; background:${T.primary}; color:${T.pfg}; border-bottom:1px solid ${T.border};">
    ${hamburger ? `<span style="width:36px; height:36px; margin-left:-4px; display:flex; align-items:center; justify-content:center; flex:0 0 auto;">${ico('menu', 20)}</span>` : ''}
    ${title ? `<span style="flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:${cw >= 760 ? 18 : 16}px; font-weight:600;">Chris' DMX Controller v7</span>` : '<span style="flex:1"></span>'}
    <div style="display:flex; align-items:center; gap:${vw >= 640 ? 8 : 4}px; flex:0 0 auto;">
      ${connected}${iconBtn('theater')}${iconBtn('grid')}${iconBtn('gauge')}${iconBtn('table')}${iconBtn('sparkles')}
      <span style="width:36px; height:36px; border-radius:999px; background:oklch(0.21 0.006 285.885 / 0.20); display:flex; align-items:center; justify-content:center; font-size:12px; font-weight:600; flex:0 0 auto;">CC</span>
    </div>
  </header>`
}

// ---- ShowHeader: proposed px-3 py-2 (48px) at every height ------------------------------------
function showHeader({ cw, running = true }) {
  const seg = (name, label, on) =>
    `<span style="display:inline-flex; align-items:center; gap:6px; border-radius:6px; padding:4px 10px; font-size:12px; font-weight:600; white-space:nowrap; ${on ? `background:${T.muted}; color:${T.fg};` : `color:${T.mfg};`}">${ico(name, 14)}${cw >= 820 ? `<span>${label}</span>` : ''}</span>`
  const crumbs = cw >= 640
    ? `<nav style="display:flex; align-items:center; gap:4px; font-size:14px; white-space:nowrap;"><span style="color:${T.mfg};">Projects</span>${ico('chevR', 16, `color:${T.mfg}`)}<span style="display:inline-flex; align-items:center; gap:8px; color:${T.mfg};">Experiment <span style="display:inline-flex; align-items:center; height:${SYS.chip}px; padding:0 8px; border-radius:999px; background:${T.primary}; color:${T.pfg}; font-size:12px; font-weight:500;">active</span></span>${ico('chevR', 16, `color:${T.mfg}`)}<span style="font-weight:500; color:${T.fg};">Programmer</span></nav>`
    : `<span style="font-size:14px; font-weight:500; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">Programmer</span>`
  return `<div style="height:${SYS.header}px; flex:0 0 auto; display:flex; align-items:center; gap:12px; padding:0 ${SYS.gutter}px; border-bottom:1px solid transparent;">
    <div style="flex:1; min-width:0; display:flex;">${crumbs}</div>
    <div style="display:flex; align-items:center; gap:8px; flex:0 0 auto;">
      <nav style="display:inline-flex; align-items:center; gap:2px; border-radius:8px; border:1px solid ${T.border}; background:${T.card}; padding:2px;">${seg('slidersV', 'Programmer', true)}${seg('theater', 'Show', false)}${seg('book', 'Prompt Book', false)}${seg('wave', 'Busk', false)}</nav>
      ${btn({ icon: running ? squareFill(14) : ico('play', 14), label: running ? 'Stop' : 'Start', variant: running ? 'destructive' : 'primary', word: cw >= 420 })}
      <span style="width:12px; height:12px; border-radius:999px; margin-left:4px; flex:0 0 auto; ${running ? `background:${T.green}; box-shadow:0 0 6px ${T.green};` : `background:oklch(0.705 0.015 286.067 / 0.40);`}"></span>
    </div>
  </div>`
}

// ---- Row A: the source box and the verbs -------------------------------------------------------

function sidebar(height, active = null) {
  const items = ['menu', 'grid', 'layers', 'palette', 'sparkles', 'slidersV', 'theater', 'book', 'wave']
  return `<div style="width:64px; flex:0 0 auto; height:${height}px; border-right:1px solid ${T.border}; background:${T.bg}; display:flex; flex-direction:column; align-items:center; padding-top:8px; gap:8px; overflow:hidden;">${items.map((n, i) => `<span style="width:40px; height:36px; border-radius:6px; display:flex; align-items:center; justify-content:center; color:${n === active ? T.fg : T.mfg}; ${n === active ? `background:${T.muted};` : ''}">${ico(n, 20)}</span>`).join('')}</div>`
}


const HEAD = `<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <script src="./support.js"></script>
</head>
<body>
<x-dc>
<helmet>
  <style>
    body { margin: 0; }
    a { color: oklch(0.707 0.165 254.624); } a:hover { color: oklch(0.809 0.105 251.813); }
    .app { font-family: ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; -webkit-font-smoothing: antialiased; color: ${T.fg}; background: oklch(0.11 0.005 286); box-sizing: border-box; overflow: hidden; position: relative; }
    .app *, .app *::before, .app *::after { box-sizing: border-box; }
  </style>
</helmet>`
const TAIL = `</x-dc>
</body>
</html>
`

// =====================================================================================================
// Sheet views — the programmer's grid gestures rolled out to the patch list, channels and show.
// Everything below draws with the builders above (tokens from index.css, button.tsx sizes, the row
// system from programmer-chrome-design) and the FixturesTable anatomy: 30px uppercase header, 36px
// rows, a sticky name column, cell triggers with an 18px marks gutter, ownership rings, the marquee
// rubber band and the `after:` selection overlay.
// =====================================================================================================
const mono = 'font-family:ui-monospace,Menlo,monospace;'
const C = {
  mainBg: `color-mix(in oklab, ${T.muted} 40%, ${T.bg})`, // main: bg-muted/40 over background
  selBg: 'oklch(0.623 0.214 259.815 / 0.25)', // after:bg-primary/25
  selRing: 'oklch(0.985 0 0 / 0.9)', // after:ring-foreground/90
  rowSel: 'oklch(0.985 0 0 / 0.06)',
  ringYou: T.primary,
  ringCue: 'oklch(0.685 0.169 237.323 / 0.4)',
  ringFx: 'oklch(0.606 0.25 292.717 / 0.5)',
  ringParked: 'oklch(0.769 0.188 70.08)',
  parkedBg: 'oklch(0.769 0.188 70.08 / 0.15)',
  clash: T.destructive,
  clashBg: 'oklch(0.704 0.191 22.216 / 0.12)',
  green: T.green,
  blue: 'oklch(0.707 0.165 254.624)',
  amberWash: 'oklch(0.828 0.189 84.429 / 0.15)',
  amberWashBorder: 'oklch(0.769 0.188 70.08 / 0.5)',
}
P.lock = 'M5 11V7a7 7 0 0 1 14 0v4M5 11h14v10H5z'
P.lockOpen = 'M7 11V7a5 5 0 0 1 9.9-1M5 11h14v10H5z'
P.play = 'm6 3 14 9-14 9z'
P.trash = 'M3 6h18M8 6V4h8v2M19 6l-1 14H6L5 6M10 11v6M14 11v6'
P.zap = 'M13 2 3 14h9l-1 8 10-12h-9z'
P.grip = 'M9 5h.01M9 12h.01M9 19h.01M15 5h.01M15 12h.01M15 19h.01'
P.cards = 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z'
P.tableProps = 'M3 3h18v18H3zM3 9h18M3 15h18M9 3v18'
P.arrowR = 'M5 12h14m-7-7 7 7-7 7'
P.arrowL = 'M19 12H5m7 7-7-7 7-7'
P.info = 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20ZM12 16v-4M12 8h.01'
P.settings = 'M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2zM12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z'
P.separator = 'M3 12h18M8 8l4-4 4 4M8 16l4 4 4-4'
P.rotate = 'M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5'
P.clock = 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20ZM12 6v6l4 2'
P.square = 'M4 4h16v16H4z'

// ---- text and kbd ------------------------------------------------------------------------------
const kbd = (t) => `<kbd style="border-radius:4px; border:1px solid ${T.border}; background:oklch(0.274 0.006 286.033 / 0.50); padding:0 6px; font-size:9.5px; ${mono}">${t}</kbd>`
const hint = (pairs) => `<span style="display:inline-flex; align-items:center; gap:6px; font-size:10px; color:${T.mfg}; white-space:nowrap; flex:0 0 auto;">${pairs.map(([k, w]) => `${kbd(k)} ${w}`).join('<span style="width:2px"></span>')}</span>`
const muted = (t, size = 12) => `<span style="font-size:${size}px; color:${T.mfg};">${t}</span>`
const title2 = (t, sub) => `<div style="display:flex; flex-direction:column; gap:4px;"><span style="font-size:20px; font-weight:700;">${t}</span>${sub ? `<span style="font-size:12px; color:${T.mfg}; max-width:900px; line-height:1.5;">${sub}</span>` : ''}</div>`
const section = (t) => `<span style="font-size:12px; font-weight:700; text-transform:uppercase; letter-spacing:0.08em;">${t}</span>`
const codify = (t) => t.replace(/`([^`]+)`/g, (_, c) => `<code style="${mono} font-size:10.5px; color:${T.fg}; background:oklch(0.274 0.006 286.033 / 0.5); padding:0 4px; border-radius:3px;">${c}</code>`)
const note = (t) => `<p style="margin:0; font-size:11.5px; line-height:1.5; color:${T.mfg};">${codify(t)}</p>`
const label = (t, sub) => `<div style="display:flex; align-items:baseline; gap:10px; padding:0 2px 8px;"><span style="font-size:12px; font-weight:700; text-transform:uppercase; letter-spacing:0.08em; color:${T.fg}; flex:0 0 auto;">${t}</span>${sub ? `<span style="font-size:11px; color:${T.mfg};">${codify(sub)}</span>` : ''}</div>`

// ---- the Cards · Table switcher (ViewSwitcher.tsx: nav gap-0.5 rounded-lg border bg-card p-0.5) --
function viewSwitcher(current, { words = true, a = ['cards', 'Cards'], b = ['tableProps', 'Table'] } = {}) {
  const seg = ([icon, text], on) => `<span style="display:inline-flex; align-items:center; gap:6px; border-radius:6px; padding:4px 10px; font-size:12px; font-weight:600; white-space:nowrap; ${on ? `background:${T.muted}; color:${T.fg};` : `color:${T.mfg};`}">${ico(icon, 14)}${words ? `<span>${text}</span>` : ''}</span>`
  return `<nav style="display:inline-flex; align-items:center; gap:2px; border-radius:8px; border:1px solid ${T.border}; background:${T.card}; padding:2px; flex:0 0 auto;">${seg(a, current === 'cards')}${seg(b, current === 'table')}</nav>`
}

// ---- the sheet: header (30), rows (36), cells --------------------------------------------------
// A column is { key, label, w } — w in px; the first column is sticky in the real thing.
function sheetHeader(cols, { inert = [] } = {}) {
  return `<div style="height:30px; flex:0 0 auto; display:flex; border-bottom:1px solid ${T.border}; background:${T.bg}; overflow:hidden;">${cols.map((c) => `<span style="width:${c.w}px; flex:${c.flex ? '1 1 0' : '0 0 auto'}; min-width:0; padding:0 ${c.key === 'name' ? 8 : 6}px; display:flex; align-items:center; gap:4px; font-size:11px; font-weight:500; text-transform:uppercase; letter-spacing:0.05em; color:${T.mfg}; white-space:nowrap; ${inert.includes(c.key) ? 'opacity:0.4;' : ''}${c.align === 'right' ? 'justify-content:flex-end;' : ''}">${c.label}</span>`).join('')}</div>`
}
// A cell wrapper: `relative h-full min-w-0 py-0.5 pr-[18px]` + ownership ring + selection overlay.
function cellWrap(c, inner, { sel = false, own = null, clash = false, inert = false, dashed = false, mark = '', noGutter = false } = {}) {
  let ring = ''
  if (own === 'you') ring = `box-shadow:inset 0 0 0 1px ${C.ringYou}; background:oklch(0.623 0.214 259.815 / 0.10);`
  if (own === 'cue') ring = `box-shadow:inset 0 0 0 1px ${C.ringCue};`
  if (own === 'fx') ring = `box-shadow:inset 0 0 0 1px ${C.ringFx};`
  if (own === 'parked') ring = `box-shadow:inset 0 0 0 1px ${C.ringParked}; background:${C.parkedBg};`
  if (clash) ring = `box-shadow:inset 0 0 0 1px ${C.clash}; background:${C.clashBg};`
  if (dashed) ring = `border:1px dashed ${T.border}; opacity:0.55;`
  const overlay = sel ? `<span style="position:absolute; inset:0; border-radius:4px; background:${C.selBg}; box-shadow:inset 0 0 0 2px ${C.selRing}; pointer-events:none;"></span>` : ''
  return `<span style="position:relative; width:${c.w}px; flex:${c.flex ? '1 1 0' : '0 0 auto'}; min-width:0; height:100%; padding:2px ${noGutter ? 2 : 18}px 2px 0; ${inert ? 'opacity:0.6;' : ''}"><span style="display:flex; align-items:center; gap:6px; height:100%; width:100%; border-radius:4px; ${ring}">${inner}</span>${mark}${overlay}</span>`
}
const cellText = (t, { color = T.mfg, weight = 400, mono: m = false, size = 12, align = 'left' } = {}) => `<span style="flex:1; min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; padding:0 6px; font-size:${size}px; font-weight:${weight}; color:${color}; text-align:${align}; ${m ? mono + 'font-variant-numeric:tabular-nums;' : ''}">${t}</span>`
const dash = `<span style="flex:1; padding:0 6px; font-size:12px; color:oklch(0.705 0.015 286.067 / 0.60);">—</span>`
const bar = (pct, readout = `${pct}%`) => `<span style="position:relative; flex:1; min-width:24px; height:6px; margin-left:6px; border-radius:999px; background:${T.muted}; overflow:hidden;"><span style="position:absolute; inset:0 auto 0 0; width:${pct}%; background:${T.primary}; border-radius:999px;"></span></span><span style="width:48px; flex:0 0 auto; margin-right:6px; text-align:right; font-size:12px; color:${T.mfg}; font-variant-numeric:tabular-nums;">${readout}</span>`
const swatch = (colour, txt) => `<span style="width:16px; height:16px; margin-left:6px; border-radius:4px; background:${colour}; border:1px solid ${T.border}; flex:0 0 auto;"></span><span style="margin-right:6px; font-size:12px; color:${T.mfg}; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-variant-numeric:tabular-nums;">${txt}</span>`
const chipCell = (chips) => `<span style="display:flex; gap:4px; padding:0 6px; overflow:hidden;">${chips.map((c) => `<span style="display:inline-flex; align-items:center; height:18px; padding:0 6px; border-radius:999px; background:${T.muted}; font-size:10px; font-weight:500; white-space:nowrap;">${c}</span>`).join('')}</span>`
const check = (on) => `<span style="display:inline-flex; align-items:center; justify-content:center; width:16px; height:16px; margin-left:6px; border-radius:4px; border:1px solid ${on ? T.primary : T.border}; background:${on ? T.primary : 'transparent'}; color:${T.pfg};">${on ? ico('check', 12) : ''}</span>`

// The name column: sticky, 8px padding, chevron for expandable rows, selected rows carry the 3px bar.
function nameCell(c, name, { sel = false, indent = 0, chev = null, badge = null, sub = null, muted: mu = false, weight = null } = {}) {
  return `<span style="position:relative; width:${c.w}px; flex:0 0 auto; height:100%; display:flex; align-items:center; gap:6px; padding:0 8px; background:${sel ? C.rowSel : 'transparent'}; box-shadow:${sel ? `inset 3px 0 0 ${T.fg}` : 'none'}; overflow:hidden; white-space:nowrap;">
    ${indent ? `<span style="width:${indent * 20}px; flex:0 0 auto;"></span>` : ''}${chev ? ico(chev === 'open' ? 'chevD' : 'chevR', 14, `color:${T.mfg}`) : ''}
    <span style="min-width:0; flex:1; overflow:hidden; text-overflow:ellipsis; font-size:14px; font-weight:${weight ?? (sel ? 600 : 400)}; color:${mu ? T.mfg : T.fg};">${name}${sub ? `<span style="font-size:11px; color:${T.mfg}; margin-left:6px;">${sub}</span>` : ''}</span>${badge != null ? countBadge(badge) : ''}
  </span>`
}
function sheetRow(cells, { sel = false, h = 36, extra = '' } = {}) {
  return `<div style="height:${h}px; flex:0 0 auto; display:flex; align-items:center; border-bottom:1px solid ${T.border}; font-size:14px; ${sel ? `background:${C.rowSel};` : ''}${extra}">${cells}</div>`
}
// The rubber band (data-testid="cell-marquee"): border-2 border-foreground bg-foreground/5, two 7px corners.
function rubberBand({ x, y, w, h }) {
  return `<div style="position:absolute; left:${x}px; top:${y}px; width:${w}px; height:${h}px; border:2px solid ${T.fg}; background:oklch(0.985 0 0 / 0.05); border-radius:2px; box-shadow:0 0 0 1px ${T.bg}; pointer-events:none; z-index:30;"><span style="position:absolute; left:-1px; top:-1px; width:7px; height:7px; border-radius:1px; background:${T.fg};"></span><span style="position:absolute; right:-1px; bottom:-1px; width:7px; height:7px; border-radius:1px; background:${T.fg};"></span></div>`
}
// The drag-scope chip that rides the pointer: rounded-full bg-primary px-2.5 py-1 text-[11px].
const scopeChip = (t, x, y) => `<span style="position:absolute; left:${x}px; top:${y}px; z-index:50; display:inline-flex; align-items:center; gap:6px; border-radius:999px; background:${T.primary}; color:${T.pfg}; padding:4px 10px; font-size:11px; font-weight:500; box-shadow:0 10px 15px -3px rgba(0,0,0,0.5); white-space:nowrap;">${t}</span>`

// ---- row C: the selection bar, per view -----------------------------------------------------------
// counts · family pill · kbd hints · (strip) · verbs. The verbs differ per view; the shape does not.
function selectionBar({ counts, family, verbs, strip = '', hints = true, empty = false, gw = 1300 }) {
  if (empty) return `<div style="height:${SYS.row}px; flex:0 0 auto; display:flex; align-items:center; gap:${SYS.gap}px; padding:0 ${SYS.gutter}px; border-bottom:1px solid ${T.border}; color:${T.mfg};">${ico('marquee', 14, 'opacity:0.6')}<span style="font-size:12px;">Nothing selected</span></div>`
  const c = counts.map((t, i) => `${i ? `<span style="color:oklch(0.705 0.015 286.067 / 0.50);">·</span>` : ''}<span style="font-size:12px; font-weight:600; font-variant-numeric:tabular-nums; white-space:nowrap;">${t}</span>`).join('')
  const h = hints && gw >= 1100 ? hint([['⏎', 'edit'], ['⌫', 'clear']]) : ''
  return `<div style="height:${SYS.row}px; flex:0 0 auto; display:flex; align-items:center; gap:${SYS.gap}px; padding:0 ${SYS.gutter}px; border-bottom:1px solid ${T.border}; background:oklch(0.985 0 0 / 0.05);">${ico('marquee', 14)}${c}${family ? pill(family) : ''}${h}${strip}<div style="display:flex; align-items:center; gap:8px; flex:0 0 auto; margin-left:auto;">${verbs}</div></div>`
}
const verb = (icon, word, o = {}) => btn({ icon: ico(icon, 14), label: word, ...o })

// ---- a cell editor: the popover form (w-64, bg-popover, rounded-md border shadow) ---------------
function popover({ x, y, w = 256, inner }) {
  return `<div style="position:absolute; left:${x}px; top:${y}px; width:${w}px; z-index:40; border-radius:8px; border:1px solid ${T.border}; background:${T.card}; box-shadow:0 10px 15px -3px rgba(0,0,0,0.5), 0 4px 6px -4px rgba(0,0,0,0.5); padding:16px; display:flex; flex-direction:column; gap:12px;">${inner}</div>`
}
const field = (v, { w = 80, focus = false, ph = false, right = false, h = 32 } = {}) => `<span style="display:inline-flex; align-items:center; height:${h}px; width:${w}px; padding:0 10px; border-radius:6px; border:1px solid ${focus ? T.ring ?? T.primary : T.border}; background:oklch(0.274 0.006 286.033 / 0.30); font-size:14px; ${mono} font-variant-numeric:tabular-nums; color:${ph ? T.mfg : T.fg}; ${focus ? `box-shadow:0 0 0 3px oklch(0.623 0.214 259.815 / 0.5);` : ''}${right ? 'justify-content:flex-end;' : ''}">${focus ? `<span style="background:oklch(0.623 0.214 259.815 / 0.45); border-radius:2px;">${v}</span>` : v}</span>`
const fieldLabel = (t) => `<span style="font-size:12px; font-weight:500; color:${T.fg};">${t}</span>`
const slider = (pct, w = 150) => `<span style="position:relative; display:inline-block; width:${w}px; height:16px; flex:1;"><span style="position:absolute; top:6px; left:0; right:0; height:4px; border-radius:999px; background:${T.muted};"></span><span style="position:absolute; top:6px; left:0; width:${pct}%; height:4px; border-radius:999px; background:${T.primary};"></span><span style="position:absolute; top:0; left:calc(${pct}% - 8px); width:16px; height:16px; border-radius:999px; background:${T.bg}; border:1px solid ${T.primary}; box-shadow:0 1px 2px rgba(0,0,0,0.4);"></span></span>`
const batchLine = (t) => `<span style="font-size:12px; color:${T.mfg};">${t}</span>`

// =====================================================================================================
// PATCH LIST — /projects/:id/settings/patches, the Patch List tab
// =====================================================================================================
const settingsHeader = () => {
  const tab = (t, on) => `<span style="display:inline-flex; align-items:center; height:28px; padding:0 8px; border-radius:6px; font-size:14px; font-weight:500; white-space:nowrap; ${on ? `background:${T.bg}; color:${T.fg}; box-shadow:0 1px 2px rgba(0,0,0,0.3);` : `color:${T.mfg};`}">${t}</span>`
  return `<div style="padding:16px; display:flex; flex-direction:column; gap:12px; border-bottom:1px solid ${T.border}; flex:0 0 auto;">
    <nav style="display:flex; align-items:center; gap:4px; font-size:14px;"><span style="color:${T.mfg};">Projects</span>${ico('chevR', 16, `color:${T.mfg}`)}<span style="display:inline-flex; align-items:center; gap:8px; color:${T.mfg};">Experiment <span style="display:inline-flex; align-items:center; height:${SYS.chip}px; padding:0 8px; border-radius:999px; background:${T.primary}; color:${T.pfg}; font-size:12px; font-weight:500;">active</span></span>${ico('chevR', 16, `color:${T.mfg}`)}<span style="font-weight:500;">Settings</span></nav>
    <div style="display:flex; flex-direction:column; gap:2px;"><span style="font-size:18px; font-weight:600;">Project Settings</span><span style="font-size:14px; color:${T.mfg};">Configure this project's metadata, fixture patches, and control surfaces.</span></div>
    <div style="display:inline-flex; align-items:center; height:36px; padding:4px; border-radius:8px; background:${T.muted}; align-self:flex-start;">${tab('General')}${tab('Patch List', true)}${tab('Surfaces')}${tab('Stage')}${tab('Rigging')}${tab('Sync')}</div>
  </div>`
}
// universes and groups as they are today (chips), on one row rather than two
const configChip = (inner) => `<span style="display:inline-flex; align-items:center; gap:6px; height:26px; padding:0 10px; border-radius:6px; border:1px solid ${T.border}; font-size:12px; white-space:nowrap; flex:0 0 auto;">${inner}</span>`
function patchChipsRow() {
  const lab = (t) => `<span style="font-size:10px; font-weight:500; text-transform:uppercase; letter-spacing:0.05em; color:oklch(0.705 0.015 286.067 / 0.60); flex:0 0 auto;">${t}</span>`
  const uni = (n, addr, fill) => configChip(`<span style="${mono} font-weight:500;">U${n}</span>${addr ? `<span style="color:${T.mfg};">${addr}</span>` : `<span style="color:oklch(0.705 0.015 286.067 / 0.5); font-style:italic;">no address</span>`}<span title="${fill} of 512 addresses used" style="position:relative; width:64px; height:6px; border-radius:999px; background:${T.muted}; overflow:hidden;"><span style="position:absolute; inset:0 auto 0 0; width:${Math.round((fill / 512) * 100)}%; background:${T.mfg}; opacity:0.7;"></span></span>${ico('pencil', 10, 'color:oklch(0.705 0.015 286.067 / 0.5)')}`)
  const grp = (n, c) => configChip(`<span style="font-weight:500;">${n}</span><span style="color:${T.mfg};">${c}</span>`)
  return `<div style="display:flex; align-items:center; gap:8px; padding:12px ${SYS.gutter}px 4px; flex-wrap:wrap;">${lab('Universes')}${uni(1, '2.0.0.11', 118)}${uni(2, null, 2)}<span style="width:12px"></span>${lab('Groups')}${grp('Front wash', 4)}${grp('Bars', 2)}${grp('Movers', 4)}${grp('Dimmers', 4)}</div>`
}
const PCOLS = [
  { key: 'name', label: 'Fixture', w: 240 },
  { key: 'addr', label: 'Address', w: 104 },
  { key: 'type', label: 'Type', w: 180, flex: true },
  { key: 'mode', label: 'Mode', w: 118 },
  { key: 'ch', label: 'Ch', w: 56, align: 'right' },
  { key: 'key', label: 'Key', w: 132 },
  { key: 'mount', label: 'Mount', w: 118 },
  { key: 'angle', label: 'Angle', w: 72 },
  { key: 'gel', label: 'Gel', w: 96 },
  { key: 'groups', label: 'Groups', w: 150 },
  { key: 'stage', label: 'Stage', w: 64 },
]
const PC = Object.fromEntries(PCOLS.map((c) => [c.key, c]))
// The rig: id, name, type, mode, ch, addr, key, mount, angle, gel, groups, hidden, clash?
const RIG = [
  ['Front PAR 1', 'Chauvet Freedom Par Hex', '6ch', 6, '1-001', 'front-par-1', 'FOH truss', '25°', ['L201', 'oklch(0.75 0.15 240)'], ['Front wash']],
  ['Front PAR 2', 'Chauvet Freedom Par Hex', '6ch', 6, '1-007', 'front-par-2', 'FOH truss', '25°', ['L201', 'oklch(0.75 0.15 240)'], ['Front wash']],
  ['Front PAR 3', 'Chauvet Freedom Par Hex', '6ch', 6, '1-013', 'front-par-3', 'FOH truss', '25°', ['L201', 'oklch(0.75 0.15 240)'], ['Front wash']],
  ['Front PAR 4', 'Chauvet Freedom Par Hex', '6ch', 6, '1-019', 'front-par-4', 'FOH truss', '25°', null, ['Front wash']],
  ['Bar SL', 'Chauvet Hex Bar 6', '18ch', 18, '1-025', 'bar-sl', 'Free', null, null, ['Bars']],
  ['Bar SR', 'Chauvet Hex Bar 6', '18ch', 18, '1-043', 'bar-sr', 'Free', null, null, ['Bars']],
  ['Spot 1', 'Martin MAC 250 Entour', '16-bit', 16, '1-101', 'spot-1', 'Mid truss', null, null, ['Movers']],
  ['Spot 2', 'Martin MAC 250 Entour', '16-bit', 16, '1-117', 'spot-2', 'Mid truss', null, null, ['Movers']],
  ['Fusion 1', 'Elation Fusion 100 Spot MKII', '13ch', 13, '1-201', 'fusion-1', 'Mid truss', null, null, ['Movers']],
  ['Fusion 2', 'Elation Fusion 100 Spot MKII', '13ch', 13, '1-205', 'fusion-2', 'Mid truss', null, null, ['Movers'], true],
  ['Dimmer 1', 'Generic Single-channel dimmer', '1ch', 1, '2-001', 'dimmer-1', 'Free', null, ['R119', 'oklch(0.8 0.1 200)'], ['Dimmers']],
  ['Dimmer 2', 'Generic Single-channel dimmer', '1ch', 1, '2-002', 'dimmer-2', 'Free', null, null, ['Dimmers']],
]
function patchRow(r, i, { selRows = [], selCells = [], addrOverride = null } = {}) {
  const [name, type, modeName, ch, addr, key, mount, angle, gel, groups, clash] = r
  const sel = selRows.includes(i)
  const cs = (k) => selCells.some(([ri, ck]) => ri === i && ck === k)
  const cells = [
    nameCell(PC.name, name, { sel }),
    cellWrap(PC.addr, cellText(addrOverride?.[i] ?? addr, { color: clash ? T.destructive : T.fg, mono: true, weight: 500 }), { sel: cs('addr'), clash, mark: clash ? `<span title="Overlaps Fusion 1 (1-201 to 1-213)" style="position:absolute; right:4px; top:2px; color:${T.destructive};">${ico('info', 10)}</span>` : '' }),
    cellWrap(PC.type, cellText(type)),
    cellWrap(PC.mode, cellText(modeName), { sel: cs('mode') }),
    cellWrap(PC.ch, cellText(String(ch), { mono: true, align: 'right' })),
    cellWrap(PC.key, cellText(key, { mono: true, size: 11 })),
    cellWrap(PC.mount, mount === 'Free' ? cellText('Free', { color: 'oklch(0.705 0.015 286.067 / 0.6)' }) : cellText(mount)),
    cellWrap(PC.angle, angle ? cellText(angle, { mono: true }) : dash),
    cellWrap(PC.gel, gel ? swatch(gel[1], gel[0]) : dash),
    cellWrap(PC.groups, chipCell(groups)),
    cellWrap(PC.stage, check(!(i === 10))),
  ].join('')
  return sheetRow(cells, { sel })
}
function patchFooter(gw, sel) {
  return `<div style="height:22px; flex:0 0 auto; display:flex; align-items:center; gap:12px; padding:0 ${SYS.gutter}px; border-top:1px solid ${T.border}; font-size:10.5px; color:${T.mfg}; white-space:nowrap; overflow:hidden;"><span style="font-variant-numeric:tabular-nums;">12 fixtures patched${sel ? ` · ${sel} selected` : ''} · 4 groups</span><span style="display:inline-flex; align-items:center; gap:6px;"><span style="width:10px; height:10px; border-radius:999px; border:1px solid ${T.destructive}; background:${C.clashBg};"></span>Address overlaps another fixture</span><span style="margin-left:auto;">2 universes · 120 of 1024 addresses</span></div>`
}
// Row B for the patch list: universe toggle · filter · spacer · Groups · Columns · + Patch (the primary verb moves onto the row).
function patchRowB({ gw }) {
  const filter = `<div style="position:relative; flex:999 1 0; min-width:0; max-width:340px; height:${SYS.control}px; display:flex; align-items:center; border-radius:6px; border:1px solid ${T.border}; background:oklch(0.274 0.006 286.033 / 0.30); padding-left:36px; font-size:14px; color:${T.mfg}; overflow:hidden; white-space:nowrap;">${ico('search', 16, `position:absolute; left:12px; color:${T.mfg}`)}Filter…</div>`
  const universe = `<div style="display:inline-flex; align-items:center; height:${SYS.control}px; padding:4px; border-radius:8px; background:${T.muted}; color:${T.mfg}; flex:0 0 auto;">${['All', 'U1', 'U2'].map((t, i) => `<span style="display:inline-flex; align-items:center; height:24px; padding:0 8px; border-radius:6px; font-size:14px; font-weight:500; ${i === 0 ? `background:${T.bg}; color:${T.fg}; box-shadow:0 1px 2px rgba(0,0,0,0.3);` : ''}">${t}</span>`).join('')}</div>`
  return `<div style="height:${SYS.row}px; flex:0 0 auto; display:flex; align-items:center; gap:${SYS.gap}px; padding:0 ${SYS.gutter}px; border-bottom:1px solid ${T.border};">${universe}${filter}${spacer()}${btn({ icon: ico('layers', 14), label: 'Groups' })}${btn({ icon: ico('columns', 14), label: 'Columns' })}${btn({ icon: ico('plus', 14), label: 'Patch', variant: 'primary' })}</div>`
}
function patchStrip({ vw, mode }) {
  const cw = vw - 64
  const gw = cw
  // the selection: four Front PAR address cells (mode 'set'), or eight heads for a fan (mode 'fan')
  const selRows = mode === 'set' ? [0, 1, 2, 3] : mode === 'fan' ? [0, 1, 2, 3, 4, 5, 6, 7] : []
  const selCells = mode === 'set' ? selRows.map((i) => [i, 'addr']) : mode === 'fan' ? selRows.map((i) => [i, 'addr']) : []
  const rows = RIG.map((r, i) => patchRow(r, i, { selRows, selCells })).join('')
  const verbs = mode === 'empty' ? '' : `${verb('pencil', 'Set')}${verb('backspace', 'Clear', { disabled: true })}${verb('fan', 'Fan')}${verb('crosshair', 'Locate')}${verb('flashlight', 'Highlight')}${verb('trash', 'Unpatch', { extra: `color:${T.destructive};` })}${btn({ icon: ico('x', 14), label: 'Deselect', variant: 'ghost' })}`
  const bar = selectionBar({ empty: mode === 'empty', counts: [`${selRows.length} fixtures`, `${selCells.length} cells`], family: 'Address', verbs, gw })
  const headerH = 16 + 20 + 12 + 50 + 12 + 36 + 16
  const chipsH = 42
  const bodyH = SYS.row + SYS.row + 30 + 36 * RIG.length + 22
  const h = 52 + headerH + chipsH + bodyH
  // overlays: the marquee band over the four address cells, the popover beside them
  let overlay = ''
  const gridTop = 52 + headerH + chipsH + SYS.row + SYS.row + 30
  const addrX = 64 + PC.name.w
  if (mode === 'set') {
    overlay += rubberBand({ x: addrX + 2, y: gridTop + 2, w: PC.addr.w - 18 - 4, h: 36 * 4 - 4 })
    overlay += popover({ x: addrX + PC.addr.w + 4, y: gridTop - 4, w: 300, inner: `
      <div style="display:flex; align-items:flex-end; gap:6px;">
        <div style="display:flex; flex-direction:column; gap:6px; flex:1;">${fieldLabel('Universe')}${field('1', { w: 70 })}</div>
        <span style="padding-bottom:9px; ${mono} color:${T.mfg};">-</span>
        <div style="display:flex; flex-direction:column; gap:6px; flex:2;">${fieldLabel('Start channel')}${field('007', { w: 96, focus: true })}</div>
      </div>
      ${batchLine(`Channels 7–12 on universe 1 · ${kbd('-')} steps to the channel`)}
      <div style="display:flex; align-items:center; gap:8px; padding-top:4px; border-top:1px solid ${T.border};">${check(true)}<span style="font-size:12px;">Consecutive across the selection</span></div>
      ${batchLine('Front PAR 1 → 1-007 · PAR 2 → 1-013 · PAR 3 → 1-019 · PAR 4 → 1-025 <span style="color:' + T.destructive + '">· 1-025 overlaps Bar SL</span>')}
    ` })
  }
  if (mode === 'fan') {
    overlay += rubberBand({ x: addrX + 2, y: gridTop + 2, w: PC.addr.w - 18 - 4, h: 36 * 8 - 4 })
    const fanX = 64 + cw - 12 - 8 - 32 - 8 - 32 - 8 - 32 - 8 - 84 - 8 - 96 - 60
    overlay += popover({ x: fanX, y: 52 + headerH + chipsH + SYS.row + SYS.row + 4, w: 320, inner: `
      <div style="display:flex; align-items:center; gap:8px;">${zlab('Fan · Address', T.fg)}${pill('8 fixtures')}</div>
      <div style="display:flex; align-items:flex-end; gap:8px;">
        <div style="display:flex; flex-direction:column; gap:6px;">${fieldLabel('From')}${field('1-001', { w: 96, focus: true })}</div>
        <div style="display:flex; flex-direction:column; gap:6px;">${fieldLabel('Step')}${field('20', { w: 72 })}</div>
        <div style="display:flex; flex-direction:column; gap:6px; flex:1;">${fieldLabel('Order')}<span style="display:inline-flex; align-items:center; justify-content:space-between; height:32px; padding:0 10px; border-radius:6px; border:1px solid ${T.border}; font-size:14px;">Visible rows ${ico('chevD', 14, 'opacity:0.5')}</span></div>
      </div>
      ${batchLine('Step is a fixed gap; blank uses each fixture\'s footprint. 1-001, 1-021, 1-041 … 1-141 — nothing overlaps.')}
      <div style="display:flex; justify-content:flex-end; gap:8px;">${btn({ label: 'Cancel', h: 28 })}${btn({ label: 'Apply', variant: 'primary', h: 28 })}</div>
    ` })
  }
  const column = `${appHeader({ vw, cw })}${settingsHeader()}${patchChipsRow()}${patchRowB({ gw })}${bar}${sheetHeader(PCOLS)}<div style="position:relative;">${rows}</div>${patchFooter(gw, selRows.length)}`
  return { html: `<div style="position:relative; display:flex; width:${vw}px; height:${h}px; overflow:hidden; border:1px solid ${T.border};">${sidebar(h)}<div style="display:flex; flex-direction:column; flex:1 1 0; min-width:0; background:${T.bg};">${column}</div>${overlay}</div>`, h }
}
const PMODES = {
  empty: ['The list as a sheet', 'The same table, on the programmer\'s grid: 36px rows, a sticky name column, a filter on row B, a universe toggle, and the overlap drawn on the cell rather than found in a sheet'],
  set: ['Set four addresses', 'Drag down the Address column, double-click or ⏎, type 007. Four heads land consecutively from 1-007, each by its own footprint — and the editor says which one would collide before Apply'],
  fan: ['Fan eight heads across a universe', 'Fan on the Address column re-spaces the selection from a start with a fixed step, in visible-row order — a Thru-style patch with the arithmetic on screen'],
}
function patchBoard() {
  const vw = 1440
  const parts = []
  let total = 24
  for (const mode of ['empty', 'set', 'fan']) {
    const s = patchStrip({ vw, mode })
    parts.push(`<div style="display:flex; flex-direction:column;">${label(...PMODES[mode])}${s.html}</div>`)
    total += 26 + s.h + 40
  }
  const w = vw + 48
  const html = `${HEAD}\n<div class="app" style="width:${w}px; height:${total}px; padding:24px; display:flex; flex-direction:column; gap:40px;">\n${parts.join('\n')}\n</div>\n${TAIL}`
  writeFileSync('Main.dc.html', html)
  return { w, h: total, title: 'Patch list · 1440 wide' }
}
const boards = {}
boards.Main = patchBoard()
console.log('Main', boards.Main.w, boards.Main.h)

// =====================================================================================================
// CHANNELS — /projects/:id/channels/:universe · Cards (today) · Table (the DMX sheet)
// =====================================================================================================
const crumbs = (page) => `<nav style="display:flex; align-items:center; gap:4px; font-size:14px; white-space:nowrap;"><span style="color:${T.mfg};">Projects</span>${ico('chevR', 16, `color:${T.mfg}`)}<span style="display:inline-flex; align-items:center; gap:8px; color:${T.mfg};">Experiment <span style="display:inline-flex; align-items:center; height:${SYS.chip}px; padding:0 8px; border-radius:999px; background:${T.primary}; color:${T.pfg}; font-size:12px; font-weight:500;">active</span></span>${ico('chevR', 16, `color:${T.mfg}`)}<span style="font-weight:500; color:${T.fg};">${page}</span></nav>`
// The rig on universe 1, as a footprint list: [name, start, [attrs]]
const U1 = [
  ['Front PAR 1', 1, ['Dim', 'Red', 'Green', 'Blue', 'Amber', 'White']],
  ['Front PAR 2', 7, ['Dim', 'Red', 'Green', 'Blue', 'Amber', 'White']],
  ['Front PAR 3', 13, ['Dim', 'Red', 'Green', 'Blue', 'Amber', 'White']],
  ['Front PAR 4', 19, ['Dim', 'Red', 'Green', 'Blue', 'Amber', 'White']],
  ['Bar SL', 25, ['Dim', 'Strobe', 'R1', 'G1', 'B1', 'R2', 'G2', 'B2', 'R3', 'G3', 'B3', 'R4', 'G4', 'B4', 'R5', 'G5', 'B5', 'Macro']],
  ['Bar SR', 43, ['Dim', 'Strobe', 'R1', 'G1', 'B1', 'R2', 'G2', 'B2', 'R3', 'G3', 'B3', 'R4', 'G4', 'B4', 'R5', 'G5', 'B5', 'Macro']],
]
const VALS = { 1: 255, 2: 191, 3: 157, 4: 74, 5: 0, 6: 0, 7: 255, 8: 191, 9: 157, 10: 74, 13: 255, 14: 191, 15: 157, 16: 74, 19: 128, 25: 255, 26: 0, 27: 200, 28: 60, 29: 60, 30: 200, 31: 60, 32: 60, 43: 255, 45: 200, 46: 60, 47: 60 }
const OWN = { 1: 'cue', 2: 'cue', 3: 'cue', 4: 'cue', 7: 'cue', 8: 'cue', 9: 'cue', 10: 'cue', 13: 'cue', 14: 'cue', 15: 'cue', 16: 'cue', 19: 'you', 30: 'parked', 45: 'fx', 46: 'fx', 47: 'fx' }
function chanInfo(n) {
  for (const [name, start, attrs] of U1) if (n >= start && n < start + attrs.length) return { name, attr: attrs[n - start], first: n === start, idx: U1.findIndex((f) => f[0] === name) }
  return null
}
// ---- Cards (today): 64 cards of 8 rows; drawn as the first row of four -------------------------
function channelCardsRow({ cw, editing = false }) {
  const inner = cw - 32 - 32
  const colW = Math.floor((inner - 3 * 16) / 4)
  const row = (n) => {
    const info = chanInfo(n)
    const v = VALS[n] ?? 0
    const parked = OWN[n] === 'parked'
    const pct = Math.round((v / 255) * 100)
    return `<div style="display:flex; flex-direction:column; padding:2px 4px; border-radius:4px; ${parked ? 'background:oklch(0.769 0.188 70.08 / 0.12);' : ''}">
      <div style="display:flex; align-items:center; gap:8px; height:20px;"><span style="width:32px; flex:0 0 auto; font-size:12px; font-weight:500; color:${T.mfg}; position:relative;">${n}${parked ? `<span style="position:absolute; top:-4px; right:-4px; width:12px; height:12px; border-radius:999px; background:oklch(0.769 0.188 70.08); color:white; font-size:7px; font-weight:700; display:flex; align-items:center; justify-content:center;">P</span>` : ''}</span>${editing && !parked ? `${slider(pct)}${field(String(v), { w: 56, h: 28 })}` : `<span style="flex:1; min-width:48px; height:8px; border-radius:999px; background:${T.muted}; overflow:hidden;"><span style="display:block; height:100%; width:${pct}%; background:${parked ? 'oklch(0.769 0.188 70.08)' : T.primary};"></span></span><span style="width:40px; text-align:right; font-size:12px; ${parked ? 'color:oklch(0.79 0.16 70); font-weight:500;' : ''}">${v}</span>`}<span style="width:16px; color:${parked ? 'oklch(0.79 0.16 70)' : T.mfg}; opacity:${parked ? 1 : 0.35};">${ico(parked ? 'lock' : 'lockOpen', 12)}</span></div>
      <div style="display:flex; align-items:center; gap:4px; margin-left:32px; font-size:10px; overflow:hidden; white-space:nowrap;">${info ? `<span style="color:${T.mfg};">${info.name}</span><span style="color:oklch(0.705 0.015 286.067 / 0.5);">·</span><span style="color:oklch(0.705 0.015 286.067 / 0.5);">${info.attr}</span>` : `<span style="color:oklch(0.705 0.015 286.067 / 0.3);">Unmapped</span>`}</div>
    </div>`
  }
  const card = (g) => `<div style="width:${colW}px; flex:0 0 auto; border-radius:10px; border:1px solid ${T.border}; background:${T.card}; padding:16px; display:flex; flex-direction:column; gap:2px;">${Array.from({ length: 8 }, (_, i) => row(g * 8 + i + 1)).join('')}</div>`
  return `<div style="display:flex; gap:16px;">${card(0)}${card(1)}${card(2)}${card(3)}</div>`
}
function channelsToolbar({ view, cw, editing = false }) {
  const parked = btn({ icon: ico('lock', 14), label: `Parked${countBadge(1)}` })
  const tools = view === 'cards'
    ? `${parked}${btn({ icon: ico('slidersH', 14), label: 'Set Value' })}${btn({ icon: ico('lock', 14), label: 'Park at Value' })}${btn({ icon: editing ? ico('check', 14) : ico('pencil', 14), label: editing ? 'Done' : 'Edit', variant: editing ? 'primary' : 'outline' })}`
    : `${parked}${btn({ icon: ico('lockOpen', 14), label: 'Unpark All' })}${btn({ icon: ico('columns', 14), label: 'Columns' })}`
  return `<div style="display:flex; align-items:flex-start; justify-content:space-between; gap:8px; margin-bottom:16px;">${crumbs('Channels')}<div style="display:flex; align-items:center; gap:8px; flex:0 0 auto;">${viewSwitcher(view)}${divider()}${tools}</div></div>`
}
const universeTabs = () => `<div style="display:inline-flex; align-items:center; height:36px; padding:4px; border-radius:8px; background:${T.muted}; margin-bottom:16px;">${['Universe 1', 'Universe 2'].map((t, i) => `<span style="display:inline-flex; align-items:center; height:28px; padding:0 8px; border-radius:6px; font-size:14px; font-weight:500; ${i === 0 ? `background:${T.bg}; color:${T.fg}; box-shadow:0 1px 2px rgba(0,0,0,0.3);` : `color:${T.mfg};`}">${t}</span>`).join('')}</div>`
// ---- Table: the DMX sheet, 16 addresses per row, 44px rows --------------------------------------
const DMX_ROW_H = 44
function dmxCell(n, w, { sel = false } = {}) {
  const info = chanInfo(n)
  const v = VALS[n]
  const own = OWN[n] ?? null
  const tint = info ? (info.idx % 2 === 0 ? `background:oklch(0.21 0.006 285.885 / 0.55);` : '') : ''
  const inner = `<span style="display:flex; flex-direction:column; justify-content:center; gap:2px; width:100%; height:100%; padding:0 6px; ${tint} border-radius:4px; overflow:hidden;">
    <span style="display:flex; align-items:center; gap:4px; font-size:9.5px; line-height:1; color:${T.mfg}; white-space:nowrap; overflow:hidden;"><span style="${mono} font-variant-numeric:tabular-nums; flex:0 0 auto; ${info?.first ? `color:${T.fg}; font-weight:600;` : ''}">${String(n).padStart(3, '0')}</span><span style="overflow:hidden; text-overflow:ellipsis; ${info?.first ? `color:${T.fg};` : ''}">${info ? (info.first ? info.name : info.attr) : ''}</span></span>
    <span style="font-size:14px; line-height:1; ${mono} font-variant-numeric:tabular-nums; font-weight:${v ? 600 : 400}; color:${info ? (v ? T.fg : T.mfg) : 'oklch(0.705 0.015 286.067 / 0.35)'};">${info ? (v ?? 0) : '0'}</span>
  </span>`
  return cellWrap({ w }, inner, { sel, own, noGutter: true, inert: !info })
}
function dmxSheet({ cw, rows = 4, selFrom = 0, selTo = -1 }) {
  const inner = cw - 32 - 32
  const rowHeadW = 48
  const cellW = Math.floor((inner - rowHeadW) / 16)
  const head = `<div style="height:30px; display:flex; border-bottom:1px solid ${T.border}; background:${T.bg};"><span style="width:${rowHeadW}px; flex:0 0 auto;"></span>${Array.from({ length: 16 }, (_, i) => `<span style="width:${cellW}px; flex:0 0 auto; padding:0 6px; display:flex; align-items:center; font-size:11px; font-weight:500; text-transform:uppercase; letter-spacing:0.05em; color:${T.mfg}; ${mono}">+${i}</span>`).join('')}</div>`
  const body = Array.from({ length: rows }, (_, r) => {
    const base = r * 16
    return `<div style="height:${DMX_ROW_H}px; display:flex; align-items:center; border-bottom:1px solid ${T.border};"><span style="width:${rowHeadW}px; flex:0 0 auto; padding:0 8px; font-size:11px; ${mono} color:${T.mfg}; font-variant-numeric:tabular-nums;">${String(base + 1).padStart(3, '0')}</span>${Array.from({ length: 16 }, (_, i) => { const n = base + i + 1; return dmxCell(n, cellW, { sel: n >= selFrom && n <= selTo }) }).join('')}</div>`
  }).join('')
  return { html: `<div style="border-radius:8px; border:1px solid ${T.border}; overflow:hidden; position:relative;">${head}${body}<div style="height:22px; display:flex; align-items:center; gap:12px; padding:0 12px; font-size:10.5px; color:${T.mfg}; border-top:1px solid ${T.border};"><span>Universe 1 · 118 of 512 patched · 1 parked</span><span style="font-weight:500;">Owned by</span>${[[T.primary, 'You'], [C.blue, 'Cue'], [T.violet, 'Effect'], [T.amber, 'Parked']].map(([c, t]) => `<span style="display:inline-flex; align-items:center; gap:6px;"><span style="width:10px; height:10px; border-radius:999px; border:1px solid ${c}; background:${c}22;"></span>${t}</span>`).join('')}<span style="margin-left:auto;">Scroll for 065 – 512 · ${kbd('⏎')} edit · ${kbd('⌫')} to 0 · type a value to set</span></div></div>`, cellW, rowHeadW }
}
function channelsStrip({ vw, mode }) {
  const cw = vw - 64
  const cardTop = 52 + 16
  let content = ''
  let h = 0
  let overlay = ''
  if (mode === 'cards') {
    content = `${channelsToolbar({ view: 'cards', cw })}${universeTabs()}${channelCardsRow({ cw })}`
    h = cardTop + 16 + 32 + 16 + 36 + 16 + 8 * 40 + 32 + 16 + 16 + 4
  } else {
    const sel = mode === 'select'
    const bar = selectionBar({ empty: !sel, counts: ['8 channels'], family: 'Value', verbs: `${verb('pencil', 'Set')}${verb('backspace', 'Clear')}${verb('fan', 'Fan')}${verb('lock', 'Park')}${verb('lockOpen', 'Unpark', { disabled: true })}${btn({ icon: ico('x', 14), label: 'Deselect', variant: 'ghost' })}`, gw: cw })
    const sheet = dmxSheet({ cw, rows: 4, selFrom: sel ? 7 : 0, selTo: sel ? 14 : -1 })
    content = `${channelsToolbar({ view: 'table', cw })}${universeTabs()}<div style="margin:0 -16px 12px; border-top:1px solid ${T.border};">${bar}</div>${sheet.html}`
    h = cardTop + 16 + 32 + 16 + 36 + 16 + 40 + 12 + 31 + DMX_ROW_H * 4 + 22 + 2 + 16 + 16 + 4
    if (sel) {
      const sheetX = 64 + 16 + 16 + 1
      const sheetY = cardTop + 16 + 32 + 16 + 36 + 16 + 40 + 12 + 31
      const x = sheetX + sheet.rowHeadW + sheet.cellW * 6 + 2
      overlay += rubberBand({ x, y: sheetY + 2, w: sheet.cellW * 8 - 4, h: DMX_ROW_H - 4 })
      overlay += popover({ x: sheetX + sheet.rowHeadW + sheet.cellW * 6, y: sheetY + DMX_ROW_H + 6, w: 272, inner: `
        <div style="display:flex; align-items:center; gap:12px;">${slider(70)}${field('178', { w: 72, focus: true })}</div>
        <div style="display:flex; align-items:center; gap:8px; font-size:12px; color:${T.mfg};"><span>0 – 255</span><span style="margin-left:auto;">70%</span></div>
        ${batchLine('Applying to 8 channels · 1-007 to 1-014')}
      ` })
    }
  }
  const column = `${appHeader({ vw, cw })}<div style="flex:1; background:${C.mainBg}; padding:16px;"><div style="border-radius:10px; border:1px solid ${T.border}; background:${T.card}; padding:16px;">${content}</div></div>`
  return { html: `<div style="position:relative; display:flex; width:${vw}px; height:${h}px; overflow:hidden; border:1px solid ${T.border};">${sidebar(h)}<div style="display:flex; flex-direction:column; flex:1 1 0; min-width:0; background:${T.bg};">${column}</div>${overlay}</div>`, h }
}
const CMODES = {
  cards: ['Cards — today, plus the switcher', 'The 64 cards of eight sliders stay as they are; a Cards · Table pill joins the toolbar, mirroring Fixtures and Groups. The route is `/channels/:u/table`, the preference sticky in localStorage'],
  table: ['Table — the DMX sheet', 'Sixteen addresses to a row, every cell the same 44px trigger: address and attribute on the first line, the raw value on the second, a fixture\'s footprint tinted as a run with its name on its first cell, ownership rings as on the programmer'],
  select: ['Drag eight addresses and set them', 'A marquee across 007–014 selects eight cells; ⏎, a double click, or typing opens one slider for all of them. Row C is the programmer\'s bar with the verbs a raw address has: Set · Clear · Fan · Park · Unpark'],
}
function channelsBoard() {
  const vw = 1440
  const parts = []
  let total = 24
  for (const mode of ['cards', 'table', 'select']) {
    const s = channelsStrip({ vw, mode })
    parts.push(`<div style="display:flex; flex-direction:column;">${label(...CMODES[mode])}${s.html}</div>`)
    total += 26 + s.h + 40
  }
  const w = vw + 48
  const html = `${HEAD}\n<div class="app" style="width:${w}px; height:${total}px; padding:24px; display:flex; flex-direction:column; gap:40px;">\n${parts.join('\n')}\n</div>\n${TAIL}`
  writeFileSync('Channels.dc.html', html)
  return { w, h: total, title: 'Channels · Cards and Table · 1440 wide' }
}
boards.Channels = channelsBoard()
console.log('Channels', boards.Channels.w, boards.Channels.h)

// =====================================================================================================
// SHOW — /projects/:id/show/stacks/:stackId · Cards (today) · Table (the cue sheet)
// =====================================================================================================
const WASH = `border-color:${C.amberWashBorder}; background:${C.amberWash};`
function showHeaderRow({ cw, unlocked }) {
  const seg = (name, l, on) => `<span style="display:inline-flex; align-items:center; gap:6px; border-radius:6px; padding:4px 10px; font-size:12px; font-weight:600; white-space:nowrap; ${on ? `background:${T.muted}; color:${T.fg};` : `color:${T.mfg};`}">${ico(name, 14)}<span>${l}</span></span>`
  const lock = unlocked
    ? `<span style="display:inline-flex; align-items:center; gap:6px; height:28px; padding:0 10px; border-radius:6px; background:oklch(0.769 0.188 70.08); color:oklch(0.25 0.05 70); font-size:12px; font-weight:700;">${ico('lockOpen', 14)}Editing</span>`
    : `<span style="display:inline-flex; align-items:center; gap:6px; height:28px; padding:0 10px; border-radius:6px; border:1px solid ${T.border}; background:oklch(0.274 0.006 286.033 / 0.30); font-size:12px; font-weight:500;">${ico('lock', 14)}Locked</span>`
  return `<div style="height:${SYS.header}px; flex:0 0 auto; display:flex; align-items:center; gap:12px; padding:0 ${SYS.gutter}px; border-bottom:1px solid transparent; ${unlocked ? WASH : ''}">
    <div style="flex:1; min-width:0; display:flex;">${crumbs('Show')}</div>
    <div style="display:flex; align-items:center; gap:8px; flex:0 0 auto;">${lock}<nav style="display:inline-flex; align-items:center; gap:2px; border-radius:8px; border:1px solid ${T.border}; background:${T.card}; padding:2px;">${seg('slidersV', 'Programmer', false)}${seg('theater', 'Show', true)}${seg('book', 'Prompt Book', false)}${seg('wave', 'Busk', false)}</nav>${btn({ icon: squareFill(14), label: 'Stop', variant: 'destructive' })}<span style="width:12px; height:12px; border-radius:999px; margin-left:4px; background:${T.green}; box-shadow:0 0 6px ${T.green};"></span></div>
  </div>`
}
function showBar({ unlocked }) {
  const tileLab = (t) => `<span style="font-size:9px; font-weight:700; text-transform:uppercase; letter-spacing:0.08em; color:${T.mfg};">${t}</span>`
  const tile = (inner, extra = '') => `<div style="display:flex; flex-direction:column; justify-content:flex-start; gap:1px; padding:6px 12px; border-radius:6px; border:1px solid ${T.border}; background:${T.card}; flex:0 0 auto; ${extra}">${inner}</div>`
  const master = (n, bpm, on) => `<div style="display:flex; align-items:stretch; border-radius:6px; border:1px solid ${T.border}; background:${T.card}; flex:0 0 auto;"><div style="display:flex; flex-direction:column; gap:1px; padding:6px 12px;"><span style="display:flex; align-items:center; gap:6px;">${tileLab(n)}<span style="width:6px; height:6px; border-radius:999px; background:${on ? T.green : T.muted};"></span></span><span style="${mono} font-size:18px; font-weight:700; line-height:1; font-variant-numeric:tabular-nums;">${bpm}</span></div><div style="display:flex; align-items:center; padding:0 12px; border-left:1px solid ${T.border}; font-size:12px; font-weight:700; text-transform:uppercase; letter-spacing:0.08em;">Tap</div></div>`
  return `<div style="display:flex; align-items:stretch; gap:8px; padding:8px 16px; border-bottom:1px solid ${T.border}; ${unlocked ? WASH : ''}">
    ${tile(`${tileLab('Blackout')}<span style="${mono} font-size:18px; font-weight:700; line-height:1; letter-spacing:0.05em;">DBO</span>`)}
    ${master('Master 1', '120', true)}${master('Colour', '60', false)}
    <div style="flex:1; min-width:0; display:flex; align-items:center; gap:14px; border-radius:6px; border:1px solid ${T.border}; background:${T.card}; padding:6px 12px; overflow:hidden;">
      <span style="font-size:14px; font-weight:500; flex:0 0 auto;">Act 1</span><span style="color:${T.mfg};">·</span>
      <span style="width:22px; height:22px; border-radius:999px; border:1px solid oklch(0.3 0.08 150); background:oklch(0.22 0.05 150); color:${T.green}; display:grid; place-items:center; flex:0 0 auto;">${ico('play', 10, '', true)}</span>
      <span style="${mono} font-size:14px; font-weight:700; color:${T.green}; flex:0 0 auto;">Q4</span><span style="font-size:14px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">Warm wash</span>
      ${ico('arrowR', 14, `color:${T.mfg}`)}${tileLab('Next')}<span style="${mono} font-size:12px; font-weight:700; color:${C.blue};">Q5</span><span style="font-size:12px; color:${T.mfg};">Spots up</span>
      ${unlocked ? '' : `<span style="margin-left:auto;">${hint([['space', 'go'], ['⌫', 'back']])}</span>`}
    </div>
    <div style="display:flex; align-items:stretch; gap:8px; flex:0 0 auto;">${btn({ label: '◀ BACK', extra: 'height:auto; font-weight:600; letter-spacing:0.05em; padding:0 20px; font-size:14px;' })}${btn({ label: 'GO', variant: 'primary', extra: 'height:auto; min-width:120px; font-size:16px; font-weight:700; letter-spacing:0.16em; box-shadow:0 6px 14px rgba(59,130,246,0.35);' })}</div>
  </div>`
}
function stackTabs({ unlocked }) {
  const tab = (name, n, on, live) => `<span style="display:inline-flex; align-items:center; gap:8px; padding:0 20px; height:100%; border-right:1px solid ${T.border}; font-size:12px; font-weight:500; color:${on ? T.fg : T.mfg}; position:relative; ${on ? `background:oklch(0.274 0.006 286.033 / 0.2);` : ''}">${live ? `<span style="width:6px; height:6px; border-radius:999px; background:${T.green}; box-shadow:0 0 6px ${T.green};"></span>` : ''}${name}<span style="${mono} font-size:9.5px; border-radius:999px; border:1px solid ${T.border}; background:oklch(0.274 0.006 286.033 / 0.4); padding:0 6px; color:oklch(0.705 0.015 286.067 / 0.8);">${n}</span>${on ? `<span style="position:absolute; left:0; right:0; bottom:0; height:2px; background:${T.primary};"></span>` : ''}</span>`
  const sep = (t) => `<span style="display:inline-flex; align-items:center; gap:6px; padding:0 8px; font-size:12px; font-weight:500; text-transform:uppercase; color:${T.mfg};"><span style="width:1px; height:16px; background:${T.border};"></span>${t}<span style="width:1px; height:16px; background:${T.border};"></span></span>`
  return `<div style="height:48px; flex:0 0 auto; display:flex; align-items:stretch; border-bottom:1px solid ${T.border}; ${unlocked ? WASH : ''}">${tab('Pre-show', 3, false, false)}${sep('Show')}${tab('Act 1', 10, true, true)}${tab('Act 2', 14, false, false)}${tab('Encores', 4, false, false)}</div>`
}
function stackDetailHeader({ unlocked, view }) {
  return `<div style="height:48px; flex:0 0 auto; display:flex; align-items:center; gap:12px; padding:0 16px; border-bottom:1px solid ${T.border}; ${unlocked ? WASH : ''}">${btn({ icon: ico('arrowL', 14), label: 'Stacks', extra: 'font-weight:700; letter-spacing:0.05em;' })}<span style="font-size:14px; font-weight:600;">Act 1</span><span style="font-size:12px; color:${T.mfg};">10 cues</span><span style="flex:1"></span>${viewSwitcher(view)}${unlocked ? `${divider()}${btn({ icon: ico('zap', 14), label: 'Record into Act 1' })}${btn({ icon: ico('separator', 14), label: 'Separator' })}` : ''}</div>`
}
const SCOLS = [
  { key: 'name', label: 'Cue', w: 100 },
  { key: 'label', label: 'Name', w: 200, flex: true },
  { key: 'fade', label: 'Fade', w: 88 },
  { key: 'curve', label: 'Curve', w: 118 },
  { key: 'follow', label: 'Follow', w: 96 },
  { key: 'loc', label: 'Book', w: 76 },
  { key: 'layers', label: 'Layers', w: 76 },
  { key: 'fx', label: 'FX', w: 56 },
  { key: 'hooks', label: 'Hooks', w: 64 },
  { key: 'notes', label: 'Notes', w: 240, flex: true },
]
const SC = Object.fromEntries(SCOLS.map((c) => [c.key, c]))
// [number, auto?, name, fade, curve, follow, book, layers, fx, hooks, notes, state]
const CUES = [
  ['1', false, 'Pre-show', '3s', 'Linear', null, 'p1', ['House warm'], 0, 1, 'House to half at clearance', 'done'],
  ['2', false, 'House out', '5s', 'Ease in-out', '2s', 'p1', ['House warm'], 0, 0, '', 'done'],
  ['3', false, 'Blackout', 'SNAP', null, null, 'p2', [], 0, 0, 'Hold for the scream', 'done'],
  ['4', false, 'Warm wash', '2s', 'Ease in-out', null, 'p2', ['Warm Wash', 'Front Focus'], 1, 0, '', 'active'],
  ['5', false, 'Spots up', '1s', 'Linear', null, 'p2', ['Warm Wash', 'Front Focus', 'Spot pair'], 0, 0, 'On "…and then?"', 'standby'],
  ['MARKER', false, 'Interval'],
  ['6', true, 'Chase in', '2s', 'Linear', '4s', 'p3', ['Chase base'], 2, 1, ''],
  ['7', true, 'Chase build', '2s', 'Linear', '4s', 'p3', ['Chase base'], 2, 0, ''],
  ['8', true, 'Chase peak', '2s', 'Linear', '4s', 'p3', ['Chase base'], 3, 0, 'Strobe warning given at doors'],
  ['9', true, 'Chase out', '2s', 'Ease out', null, 'p3', ['Chase base'], 0, 1, ''],
  ['10', false, 'Curtain', '8s', 'Ease in-out', null, 'p4', ['House warm'], 0, 0, 'Slow — wait for the bow'],
]
function cueRow(c, i, { locked, selCells = [], fadeOverride = null }) {
  if (c[0] === 'MARKER') {
    return `<div style="height:36px; flex:0 0 auto; display:flex; align-items:center; gap:10px; padding:0 16px; border-bottom:1px solid ${T.border};"><span style="flex:1; height:1px; background:${T.border};"></span><span style="display:inline-flex; align-items:center; height:${SYS.chip}px; padding:0 8px; border-radius:4px; border:1px solid ${T.border}; background:${T.card}; font-size:12px; font-weight:500; color:${T.mfg};">${c[2]}</span><span style="flex:1; height:1px; background:${T.border};"></span></div>`
  }
  const [num, auto, name, fade, curve, follow, book, layers, fx, hooks, notes, state] = c
  const cs = (k) => selCells.some(([ri, ck]) => ri === i && ck === k)
  const active = state === 'active', standby = state === 'standby', done = state === 'done'
  const pip = active
    ? `<span style="width:22px; height:22px; border-radius:999px; border:1px solid oklch(0.3 0.08 150); background:oklch(0.22 0.05 150); color:${T.green}; display:grid; place-items:center; flex:0 0 auto; box-shadow:0 0 8px rgba(74,222,128,0.4);">${ico('play', 10, '', true)}</span>`
    : standby ? `<span style="width:22px; height:22px; border-radius:999px; border:1px solid oklch(0.3 0.08 260); background:oklch(0.22 0.05 260); color:${C.blue}; display:grid; place-items:center; flex:0 0 auto;"><span style="width:6px; height:6px; border-radius:999px; background:currentColor;"></span></span>`
      : `<span style="width:22px; height:22px; border-radius:999px; border:1px solid ${T.border}; background:${T.muted}; display:grid; place-items:center; flex:0 0 auto;">${done ? ico('check', 12, `color:oklch(0.705 0.015 286.067 / 0.6)`) : `<span style="width:8px; height:8px; border-radius:999px; background:oklch(0.705 0.015 286.067 / 0.3);"></span>`}</span>`
  const numStyle = `${mono} font-size:14px; font-weight:${auto ? 400 : 600}; color:${auto ? 'oklch(0.705 0.015 286.067 / 0.7)' : T.fg};`
  const nameCol = `<span style="position:relative; width:${SC.name.w}px; flex:0 0 auto; height:100%; display:flex; align-items:center; gap:10px; padding:0 8px 0 12px; overflow:hidden; white-space:nowrap;">${locked ? '' : `<span style="position:absolute; left:0; top:0; bottom:0; width:12px; display:grid; place-items:center; color:${T.mfg}; opacity:0.5;">${ico('grip', 12)}</span>`}${pip}<span style="${numStyle}">Q${num}</span></span>`
  const nameColour = active ? 'oklch(0.85 0.15 150)' : standby ? 'oklch(0.8 0.1 250)' : T.fg
  const rowBg = active ? `background:oklch(0.723 0.219 149.579 / 0.08); box-shadow:inset 3px 0 0 ${T.green};` : standby ? `background:oklch(0.623 0.214 259.815 / 0.06); box-shadow:inset 3px 0 0 ${C.blue};` : ''
  const cells = [
    nameCol,
    cellWrap(SC.label, cellText(name, { color: nameColour, weight: active || standby ? 600 : 500, size: 14 }), { sel: cs('label'), inert: locked }),
    cellWrap(SC.fade, cellText(fadeOverride?.[i] ?? fade, { mono: true, color: T.fg, weight: 500 }), { sel: cs('fade'), inert: locked }),
    cellWrap(SC.curve, curve ? cellText(curve) : dash, { sel: cs('curve'), inert: locked }),
    cellWrap(SC.follow, follow ? `<span style="display:inline-flex; align-items:center; gap:4px; padding:0 6px; font-size:12px;"><span style="font-size:9px; text-transform:uppercase; letter-spacing:0.05em; color:${C.blue};">auto</span><span style="${mono} color:${T.fg};">${follow}</span></span>` : dash, { sel: cs('follow'), inert: locked }),
    cellWrap(SC.loc, cellText(book, { mono: true, size: 11 }), { inert: locked }),
    cellWrap(SC.layers, layers.length ? `<span style="display:inline-flex; align-items:center; gap:5px; padding:0 6px; font-size:12px; color:${T.mfg}; overflow:hidden; white-space:nowrap;">${countBadge(layers.length)}${ico('layers', 12)}<span style="overflow:hidden; text-overflow:ellipsis;">${layers[0]}</span></span>` : dash),
    cellWrap(SC.fx, fx ? `<span style="display:inline-flex; align-items:center; gap:5px; padding:0 6px; font-size:12px; color:oklch(0.709 0.164 293.541);">${countBadge(fx, 'oklch(0.709 0.164 293.541)')}${ico('wave', 12)}</span>` : dash),
    cellWrap(SC.hooks, hooks ? `<span style="display:inline-flex; align-items:center; gap:5px; padding:0 6px; font-size:12px; color:${T.mfg};">${countBadge(hooks)}${ico('zap', 12)}</span>` : dash),
    cellWrap(SC.notes, notes ? cellText(notes, { color: T.mfg }) : dash, { sel: cs('notes'), inert: locked }),
  ].join('')
  return sheetRow(cells, { extra: rowBg })
}
function showStrip({ vw, mode }) {
  const cw = vw - 64
  const unlocked = mode !== 'locked'
  const selCells = mode === 'fan' ? [6, 7, 8, 9].map((i) => [i, 'fade']) : mode === 'locked' ? [[9, 'notes']] : []
  const rows = CUES.map((c, i) => cueRow(c, i, { locked: !unlocked, selCells })).join('')
  const verbs = mode === 'fan'
    ? `${verb('pencil', 'Set')}${verb('backspace', 'Clear')}${verb('fan', 'Fan', { on: true })}${btn({ icon: ico('x', 14), label: 'Deselect', variant: 'ghost' })}`
    : `${verb('pencil', 'Set', { disabled: true })}${verb('backspace', 'Clear', { disabled: true })}${verb('fan', 'Fan', { disabled: true })}${verb('play', 'Arm as next')}${btn({ icon: ico('x', 14), label: 'Deselect', variant: 'ghost' })}`
  const bar = selectionBar({ empty: mode === 'table', counts: mode === 'fan' ? ['4 cues', '4 cells'] : ['1 cue', '1 cell'], family: mode === 'fan' ? 'Fade' : 'Notes', verbs, gw: cw, hints: unlocked, strip: unlocked ? '' : `${divider()}<span style="display:inline-flex; align-items:center; gap:6px; font-size:12px; color:${T.mfg};">${ico('lock', 12)}Locked — cells are read-only · ${kbd('L')} to edit</span>` })
  const top = 52 + SYS.header + 60 + 48 + 48 + SYS.row + 30
  const h = top + 36 * CUES.length + 22
  let overlay = ''
  if (mode === 'fan') {
    const x = 64 + SC.name.w + SC.label.w + 2 // label is flex; approximate: the flex share is drawn below with fixed widths
    // Because `label` and `notes` flex, compute the real x from the fixed widths: total fixed = sum(w of non-flex); flex share splits the rest 200:240.
    const fixed = SCOLS.filter((c) => !c.flex).reduce((a, c) => a + c.w, 0)
    const free = cw - fixed
    const labelW = Math.round(free / 2) // both flex columns are `flex:1 1 0`, so they split the slack equally
    const fadeX = 64 + SC.name.w + labelW
    overlay += rubberBand({ x: fadeX + 2, y: top + 36 * 6 + 2, w: SC.fade.w - 18 - 4, h: 36 * 4 - 4 })
    overlay += popover({ x: fadeX + SC.fade.w + 6, y: top + 36 * 5 + 6, w: 336, inner: `
      <div style="display:flex; align-items:center; gap:8px;">${zlab('Fan · Fade', T.fg)}${pill('4 cues')}${pill('visible order')}</div>
      <div style="display:flex; align-items:flex-end; gap:8px;">
        <div style="display:flex; flex-direction:column; gap:6px;">${fieldLabel('From')}${field('1s', { w: 84, focus: true })}</div>
        <div style="display:flex; flex-direction:column; gap:6px;">${fieldLabel('To')}${field('4s', { w: 84 })}</div>
        <div style="display:flex; flex-direction:column; gap:6px; flex:1;">${fieldLabel('Spread')}<span style="display:inline-flex; align-items:center; justify-content:space-between; height:32px; padding:0 10px; border-radius:6px; border:1px solid ${T.border}; font-size:14px;">Linear ${ico('chevD', 14, 'opacity:0.5')}</span></div>
      </div>
      <div style="display:flex; align-items:center; gap:6px; ${mono} font-size:11px; color:${T.mfg};">Q6 <b style="color:${T.fg}">1s</b> · Q7 <b style="color:${T.fg}">2s</b> · Q8 <b style="color:${T.fg}">3s</b> · Q9 <b style="color:${T.fg}">4s</b></div>
      <div style="display:flex; justify-content:flex-end; gap:8px;">${btn({ label: 'Cancel', h: 28 })}${btn({ label: 'Apply', variant: 'primary', h: 28 })}</div>
    ` })
  }
  const footer = `<div style="height:22px; flex:0 0 auto; display:flex; align-items:center; gap:12px; padding:0 ${SYS.gutter}px; border-top:1px solid ${T.border}; font-size:10.5px; color:${T.mfg}; white-space:nowrap;"><span>10 cues · 1 marker</span><span style="display:inline-flex; align-items:center; gap:6px;"><span style="width:10px; height:10px; border-radius:999px; border:1px solid ${T.green}; background:${T.green}22;"></span>Live</span><span style="display:inline-flex; align-items:center; gap:6px;"><span style="width:10px; height:10px; border-radius:999px; border:1px solid ${C.blue}; background:${C.blue}22;"></span>Next</span><span style="display:inline-flex; align-items:center; gap:6px; color:oklch(0.705 0.015 286.067 / 0.7);"><span style="${mono}">Q6</span> auto-numbered from position</span><span style="margin-left:auto;">Layers, FX and Hooks open the cue card · ${kbd('⏎')} on a name, fade, curve, follow or note edits it</span></div>`
  const column = `${appHeader({ vw, cw })}${showHeaderRow({ cw, unlocked })}${showBar({ unlocked })}${stackTabs({ unlocked })}${stackDetailHeader({ unlocked, view: 'table' })}${bar}${sheetHeader(SCOLS)}${rows}${footer}`
  return { html: `<div style="position:relative; display:flex; width:${vw}px; height:${h}px; overflow:hidden; border:1px solid ${T.border};">${sidebar(h, 'theater')}<div style="display:flex; flex-direction:column; flex:1 1 0; min-width:0; background:${T.bg};">${column}</div>${overlay}</div>`, h }
}
const SMODES = {
  table: ['Table — the cue sheet, unlocked', 'Cards · Table on the stack header; one row per cue, a marker as a divider row. Name, Fade, Curve, Follow and Notes are cells and the cue number keeps its inline edit; Book, Layers, FX and Hooks are read-outs that open the card. The header\'s amber wash runs down the show chrome while unlocked (today only ShowHeader washes)'],
  fan: ['Fan four fade times', 'Drag down Fade over Q6–Q9 and Fan spreads 1s → 4s in visible order — the Thru gesture every desk has, without a command line. Set on the same selection types one value into all four'],
  locked: ['Locked — the running state', 'Locked keeps the sheet readable and the marquee usable, but every value cell is inert and the verbs say why. A click on the Cue column arms a cue as next, exactly as a card click does; Space and ⌫ stay the transport'],
}
function showBoard() {
  const vw = 1440
  const parts = []
  let total = 24
  for (const mode of ['table', 'fan', 'locked']) {
    const s = showStrip({ vw, mode })
    parts.push(`<div style="display:flex; flex-direction:column;">${label(...SMODES[mode])}${s.html}</div>`)
    total += 26 + s.h + 40
  }
  const w = vw + 48
  const html = `${HEAD}\n<div class="app" style="width:${w}px; height:${total}px; padding:24px; display:flex; flex-direction:column; gap:40px;">\n${parts.join('\n')}\n</div>\n${TAIL}`
  writeFileSync('Show.dc.html', html)
  return { w, h: total, title: 'Show · the cue sheet · 1440 wide' }
}
boards.Show = showBoard()
console.log('Show', boards.Show.w, boards.Show.h)

// =====================================================================================================
// CHANNELS — the alternate: one row per address (low-fi, for the decision)
// =====================================================================================================
function channelsRowsBoard() {
  const w = 900
  const cols = [{ key: 'name', label: 'Address', w: 110 }, { key: 'val', label: 'Value', w: 220 }, { key: 'fix', label: 'Fixture', w: 180 }, { key: 'attr', label: 'Attribute', w: 120 }, { key: 'park', label: 'Parked', w: 90 }, { key: 'src', label: 'Owned by', w: 110 }]
  const cc = Object.fromEntries(cols.map((c) => [c.key, c]))
  const rows = Array.from({ length: 14 }, (_, i) => {
    const n = i + 1
    const info = chanInfo(n)
    const v = VALS[n] ?? 0
    const own = OWN[n]
    const sel = n >= 7 && n <= 10
    return sheetRow([
      nameCell(cc.name, `1-${String(n).padStart(3, '0')}`, { sel, weight: 500 }),
      cellWrap(cc.val, bar(Math.round((v / 255) * 100), String(v)), { sel, own: own ?? null }),
      cellWrap(cc.fix, info ? cellText(info.name, { color: T.fg }) : dash),
      cellWrap(cc.attr, info ? cellText(info.attr) : dash),
      cellWrap(cc.park, own === 'parked' ? `<span style="display:inline-flex; align-items:center; gap:4px; padding:0 6px; font-size:11px; color:oklch(0.79 0.16 70);">${ico('lock', 11)} 200</span>` : dash),
      cellWrap(cc.src, cellText(own === 'cue' ? 'Q4' : own === 'you' ? 'You' : own === 'parked' ? 'Parked' : own === 'fx' ? 'Effect' : '—')),
    ].join(''), { sel })
  }).join('')
  const barC = selectionBar({ counts: ['4 channels'], family: 'Value', verbs: `${verb('pencil', 'Set')}${verb('backspace', 'Clear')}${verb('fan', 'Fan')}${verb('lock', 'Park')}${verb('lockOpen', 'Unpark', { disabled: true })}${btn({ icon: ico('x', 14), label: 'Deselect', variant: 'ghost' })}`, gw: 800 })
  const pros = [
    ['For', 'It is literally the programmer\'s table — same row height, same sticky first column, same cell components (a SliderCell with a 0–255 readout). Nothing new to build, and Fixture and Attribute become sortable, filterable columns.'],
    ['Against', '512 rows for one universe, 32 screens tall at 36px. Every desk surveyed draws the raw output as a 2-D grid because the address space is the thing being read; a run of RGB cells across a fixture reads at a glance in a grid and not in a list.'],
    ['Call', 'The grid (Channels artboard) is the recommendation. Keep this shape in mind as the fallback if the 16-wide cell cannot hold what a cell needs — it shares every gesture and the whole selection bar.'],
  ]
  const html = `${HEAD}
<div class="app" style="width:${w}px; height:1040px; padding:24px; display:flex; flex-direction:column; gap:18px;">
  ${title2('Channels — the alternate: one row per address', 'Drawn to decide between a 2-D DMX sheet and a row list. Same kit, same verbs; only the shape differs.')}
  <div style="border:1px solid ${T.border}; background:${T.bg};">${barC}${sheetHeader(cols)}${rows}</div>
  <div style="display:grid; grid-template-columns:repeat(3, minmax(0, 1fr)); gap:18px;">${pros.map(([h, p]) => `<div style="display:flex; flex-direction:column; gap:4px;"><span style="font-size:12px; font-weight:700;">${h}</span>${note(p)}</div>`).join('')}</div>
</div>
${TAIL}`
  writeFileSync('ChannelsRows.dc.html', html)
  return { w, h: 1040, title: 'Channels · alternate · one row per address' }
}
boards.ChannelsRows = channelsRowsBoard()

// =====================================================================================================
// KIT — one sheet, four surfaces: the shared components and the gesture matrix
// =====================================================================================================
function kitBoard() {
  const w = 1240
  // 1. anatomy: a three-row fragment with rulers
  const acols = [{ key: 'name', label: 'Row', w: 200 }, { key: 'a', label: 'Cell', w: 140 }, { key: 'b', label: 'Cell', w: 140 }, { key: 'c', label: 'Cell', w: 140 }]
  const ac = Object.fromEntries(acols.map((c) => [c.key, c]))
  const frag = `<div style="position:relative; width:${200 + 140 * 3}px; border:1px solid ${T.border}; background:${T.bg};">${sheetHeader(acols)}
    ${sheetRow(nameCell(ac.name, 'Selected row', { sel: true }) + cellWrap(ac.a, bar(64), { sel: true, own: 'you' }) + cellWrap(ac.b, swatch('oklch(0.75 0.16 60)', '255,157,74'), { sel: true }) + cellWrap(ac.c, dash), { sel: true })}
    ${sheetRow(nameCell(ac.name, 'Group', { chev: 'open', badge: 4, weight: 500 }) + cellWrap(ac.a, bar(80), { own: 'cue' }) + cellWrap(ac.b, cellText('Mixed'), { own: 'fx', mark: `<span style="position:absolute; right:4px; top:2px; width:14px; height:14px; border-radius:3px; background:oklch(0.606 0.25 292.717 / 0.9); color:white; display:grid; place-items:center;">${ico('wave', 10)}</span>` }) + cellWrap(ac.c, cellText('Open', { color: T.mfg }), { own: 'parked' }))}
    ${sheetRow(nameCell(ac.name, 'Member', { indent: 1 }) + cellWrap(ac.a, dash) + cellWrap(ac.b, dash, { dashed: true }) + cellWrap(ac.c, dash, { inert: true }))}
    ${rubberBand({ x: 202, y: 32, w: 140 * 2 - 18 - 4, h: 32 })}
  </div>`
  const anatomyNotes = [
    ['30 / 36', 'Header 30px, uppercase 11px tracked; every row 36px; the first column sticky with the 3px selection bar in it.'],
    ['Cell', 'A full-height trigger with an 18px marks gutter on the right for the corner glyphs (a Look layer, an effect, a clash). Rounded 4px. `pointer-events:none` when a scope makes it read-only; `disabled` on the trigger as well, so Tab cannot reach it.'],
    ['Rings', 'Ownership is a 1px inset ring: primary = you, sky = cue, violet = effect, amber = parked; a dashed ring = mixed ownership across a group row; a dashed box at 55% = a row a layer does not target. A selection is the `after:` overlay — primary at 25% under a 2px foreground ring — and never a border, so it can sit over any ring.'],
    ['Marquee', 'The rubber band is 2px foreground with two 7px corners, drawn over the rows wrapper; the drag-scope chip rides the pointer. Row marquees start in the name column and cell marquees to its right, decided at the press.'],
  ]
  // 2. the bars per surface
  const bars = [
    ['Programmer', selectionBar({ counts: ['4 fixtures', '4 cells'], family: 'Colour', verbs: `${verb('pencil', 'Set')}${verb('backspace', 'Clear')}${verb('fan', 'Fan')}${verb('crosshair', 'Locate')}${verb('flashlight', 'Highlight')}${btn({ icon: ico('x', 14), label: 'Deselect', variant: 'ghost' })}`, strip: `${divider()}<div style="display:flex; gap:6px; flex:1 1 0; min-width:0; overflow:hidden;">${RECENT_CHIPS()}</div>${btn({ icon: ico('plus', 14), label: 'New', h: SYS.nested, extra: 'border-style:dashed; background:transparent;' })}` })],
    ['Patch list', selectionBar({ counts: ['4 fixtures', '4 cells'], family: 'Address', verbs: `${verb('pencil', 'Set')}${verb('backspace', 'Clear', { disabled: true })}${verb('fan', 'Fan')}${verb('crosshair', 'Locate')}${verb('flashlight', 'Highlight')}${verb('trash', 'Unpatch', { extra: `color:${T.destructive};` })}${btn({ icon: ico('x', 14), label: 'Deselect', variant: 'ghost' })}` })],
    ['Channels', selectionBar({ counts: ['8 channels'], family: 'Value', verbs: `${verb('pencil', 'Set')}${verb('backspace', 'Clear')}${verb('fan', 'Fan')}${verb('lock', 'Park')}${verb('lockOpen', 'Unpark', { disabled: true })}${btn({ icon: ico('x', 14), label: 'Deselect', variant: 'ghost' })}` })],
    ['Show', selectionBar({ counts: ['4 cues', '4 cells'], family: 'Fade', verbs: `${verb('pencil', 'Set')}${verb('backspace', 'Clear')}${verb('fan', 'Fan')}${btn({ icon: ico('x', 14), label: 'Deselect', variant: 'ghost' })}` })],
  ]
  // 3. gesture matrix
  const M = [
    ['Drag from the first column selects rows; from a value column selects cells', '●', '●', '● (no row axis — the grid is all cells)', '●'],
    ['Single click selects one cell; double click opens its editor', '●', '●', '●', '● unlocked · locked: arms the cue'],
    ['⏎ opens the editor for the first selected cell, focused; ⌫ clears', '●', '● ⌫ disabled on Address', '● ⌫ = 0', '● unlocked only'],
    ['Type to set: digits and letters seed the first field', '●', '●', '●', '●'],
    ['One editor per column, fanned over every selected column', '●', '●', 'one column', '●'],
    ['Row C: counts · family pill · ⏎/⌫ hints · verbs', '●', '●', '●', '●'],
    ['Set · Clear · Fan', '●', 'Set · Fan (Clear is disabled — an address cannot be empty)', '●', '●'],
    ['Surface verbs', 'Locate · Highlight · templates', 'Locate · Highlight · Unpatch', 'Park · Unpark', 'Arm as next'],
    ['Editor forms: popover / bottom sheet / side sheet by viewport', '●', '●', '●', '●'],
    ['Read-only scope', 'Output · a template layer', '—', 'desk offline', 'Locked'],
    ['View switcher on the header row', 'Cards · List (fixtures, groups)', '— (a settings tab)', 'Cards · Table', 'Cards · Table'],
    ['Route + sticky preference', '/fixtures · /fixtures/list', '—', '/channels/:u · /channels/:u/table', '/show/stacks/:id · …/table'],
  ]
  const matrix = `<table style="border-collapse:collapse; width:100%; font-size:11.5px; line-height:1.4;"><thead><tr>${['Gesture', 'Programmer (today)', 'Patch list', 'Channels', 'Show'].map((h, i) => `<th style="text-align:left; padding:6px 10px; border-bottom:1px solid ${T.border}; font-size:10px; font-weight:700; text-transform:uppercase; letter-spacing:0.1em; color:${T.mfg}; ${i === 0 ? 'width:34%;' : ''}">${h}</th>`).join('')}</tr></thead><tbody>${M.map((r) => `<tr>${r.map((c, i) => `<td style="padding:6px 10px; border-bottom:1px solid ${T.border}; vertical-align:top; ${i === 0 ? 'font-weight:500;' : c === '●' ? `color:${T.primary}; font-weight:700;` : `color:${T.mfg};`}">${codify(c)}</td>`).join('')}</tr>`).join('')}</tbody></table>`
  // 4. the module map
  const mods = [
    ['Move, not copy', '`components/fixtures-list/` holds the generic half under fixture-specific names. Lift `cellSelectionModel`, `cellSelection.ts`, `cellEntry.ts` (`orderedSelectedCells`, `cellKeyboardPermission`), `useCellSelection`, `cells/CellEditorSurface`, `cells/useCellEditorOpen`, `cells/useCellEditorKeyboard`, `useCellEditorForm`, `SelectionToolbar` (the `WORD_CLASS` / fold constants), `CellSelectionActions`, `FanPopover` and `fanMath` into `components/sheet/`. Three things are extractions rather than moves: `useCellMarquee` is a local of `FixturesTable` today, and `commitToCells` / `columnTargets` are closures inside the container. The fixtures list keeps its columns, row model, ownership and scope.'],
    ['One SheetColumn&lt;Row&gt; per surface', 'A column says how to read a row (`value(row)`), which editor it takes (`SliderCell` · `ColourCell` · `PositionCell` · `SettingCell` · a new `TextCell` and `AddressCell`), whether it fans (`fanKind`), and what a commit does (`write(rows, value)`). `orderedSelectedCells` already works over that shape; `commitToCells` and `columnTargets` do once they are pulled out of the container, where today they close over `COLUMN_DEFS`. Fan grows two controls it does not have: a Step for addresses (a stride) and a Spread for fades; today\'s `FanPopover` is From · To only.'],
    ['The bar is a shell', '`SelectionBar` becomes counts · family · hints · a `strip` slot · a `verbs` slot. Set/Clear/Fan stay `CellSelectionActions` and take a `permission` (`cellKeyboardPermission`\'s shape) so a disabled button and a refused key always agree. Each surface adds its own verbs after them.'],
    ['Two new cells', '`TextCell` (name, key, notes: an input, commit on ⏎, escape reverts) and `AddressCell` (universe · channel, `-` steps, consecutive by footprint over a batch, the overlap check inline). Both go through `CellEditorSurface`, so they get the three forms and the double click for free.'],
    ['Writers per surface', 'Patch → `useUpdatePatchMutation` per row (a batch is N PUTs; the server has no bulk patch route today). Channels → `lightingApi.channels.update` per address, the fire-and-forget gesture the cards already use. Show → `buildCueInput` field by field, one PATCH per cue; Fan over fade is N of them.'],
    ['ViewSwitcher is already generic', 'It takes `storageKey` + two routes. Channels needs `CHANNELS_VIEW_KEY = \'channels.view\'` and the `/table` sibling; Show needs `SHOW_VIEW_KEY` and `/show/stacks/:id/table`. Both follow the cards/list wiring exactly: cards route redirects when the sticky says table, table route writes the sticky on mount.'],
  ]
  const html = `${HEAD}
<div class="app" style="width:${w}px; height:1480px; padding:28px 32px; display:flex; flex-direction:column; gap:26px;">
  ${title2('One sheet, four surfaces', 'The programmer\'s grid gestures as a kit the patch list, channels and show can mount. Nothing here is new to the programmer — this sheet names which parts are generic, where they move, and what each surface adds.')}
  <div style="display:flex; flex-direction:column; gap:10px;">${section('1 · The sheet anatomy every surface shares')}<div style="display:flex; gap:28px; align-items:flex-start;">${frag}<div style="display:grid; grid-template-columns:repeat(2, minmax(0, 1fr)); gap:12px 24px; flex:1;">${anatomyNotes.map(([h, p]) => `<div style="display:flex; flex-direction:column; gap:3px;"><span style="font-size:12px; font-weight:700; ${mono}">${h}</span>${note(p)}</div>`).join('')}</div></div></div>
  <div style="display:flex; flex-direction:column; gap:10px;">${section('2 · Row C — the left half never changes, the verbs do')}<div style="display:flex; flex-direction:column; gap:8px;">${bars.map(([n, b]) => `<div style="display:flex; align-items:center; gap:12px;"><span style="width:90px; flex:0 0 auto; font-size:11px; font-weight:600; color:${T.mfg};">${n}</span><div style="flex:1; border:1px solid ${T.border};">${b}</div></div>`).join('')}</div>${note('Counts, the family pill and the ⏎ / ⌫ hints are the kit\'s. The verbs after Fan are the surface\'s: Locate and Highlight belong to fixtures, Park to raw addresses, Arm to a cue. Deselect is always last and always ghost.')}</div>
  <div style="display:flex; flex-direction:column; gap:10px;">${section('3 · Which gesture lands where')}${matrix}</div>
  <div style="display:flex; flex-direction:column; gap:10px;">${section('4 · Where the code goes')}<div style="display:grid; grid-template-columns:repeat(2, minmax(0, 1fr)); gap:14px 28px;">${mods.map(([h, p]) => `<div style="display:flex; flex-direction:column; gap:3px;"><span style="font-size:12px; font-weight:700;">${h}</span>${note(p)}</div>`).join('')}</div></div>
</div>
${TAIL}`
  writeFileSync('Kit.dc.html', html)
  return { w, h: 1480, title: 'The kit · one sheet, four surfaces' }
}
function RECENT_CHIPS() {
  const c = ({ swatch: s, name }) => `<span style="display:inline-flex; align-items:center; gap:6px; height:${SYS.nested}px; padding:0 8px; border-radius:6px; border:1px solid ${T.border}; background:${T.card}; font-size:11.5px; white-space:nowrap; flex:0 0 auto;"><span style="width:12px; height:12px; border-radius:3px; background:${s}; border:1px solid oklch(0.274 0.006 286.033 / 0.60);"></span><span>${name}</span></span>`
  return [{ swatch: 'oklch(0.75 0.16 60)', name: 'Warm wash' }, { swatch: 'oklch(0.8 0.17 80)', name: 'Amber' }, ].map(c).join('')
}
boards.Kit = kitBoard()

// =====================================================================================================
// SPEC — what the desks do, what we take, what is open
// =====================================================================================================
function specBoard() {
  const w = 1240
  const survey = [
    ['Patch', 'grandMA3', 'FID · CID · Name · FixtureType · Mode · Patch (typed as 2.1) · Gel · Note …; right-click selected Patch cells → Edit Patch with a universe map, Patch To Next Free, PatchOffset as the stride', 'The address typed into the cell; consecutive by footprint; next-free as a first-class verb'],
    ['Patch', 'ETC Eos', 'Channel · Address · Type · Label · Output; Format flips channel ↔ address; 1 Thru 10 @ 1 auto-offsets by fixture type, {Offset} widens; a clash unpatches the other after a confirmation', 'A batch Set lands consecutively; a clash is confirmed, never silent'],
    ['Patch', 'Avolites Titan', 'A coloured address bar per line showing every fixture\'s block; a blue bargraph per line for fill; click the gap to patch there; Park Conflicting Fixtures', 'The fill bar on each universe chip; the overlap drawn on the cell'],
    ['Patch', 'MagicQ · Hog 4', 'Yellow cells are editable, type and ENTER; 4/40 = four at stride 40; Hog: Fixture 1 Thru 5 @ 1 follows on, @ @ @ adds an offset', 'Fan on Address = From + Step, footprint when blank'],
    ['DMX', 'grandMA3', 'DMX Sheet: one square per address, value · attribute · ID togglable, a fixture\'s run has rounded ends and alternating grey; parked blue; readout %/dec/hex', 'The 2-D grid; the footprint run; the first cell carrying the name'],
    ['DMX', 'ETC Eos', 'sACN Output Viewer: 512 cells, address + value, red brightens with level, outlined in the colour of the source; Address 5 Full drives it; Park tab', 'Ownership rings on the address cell; Park as a verb on the selection'],
    ['DMX', 'MagicQ · Hog 4', 'MagicQ colours by attribute family, View Names overlays fixtures, TEST CHANS follows the cursor; Hog: select cells, Set, type 0–255, blue = manual override', 'Set on a range of cells with one editor'],
    ['Cue', 'grandMA3', 'Sequence Sheet: 41 columns, grey = editable, black = derived; green frame on the active cue; drag through cells then Edit to multi-set; the calculator fans 0 thru 10 in selection order', 'Editability by cell tone; the marquee then one editor for many; Fan over fade'],
    ['Cue', 'ETC Eos', 'Cue List Index is a blind list: attributes edit here, contents edit in the blind display; Blind turns the whole surface blue', 'The lock as the safety: locked is quiet, unlocked is the amber wash'],
    ['Cue', 'Titan · MagicQ · Hog 4 · ONYX', 'A grid of cues; draw a box across items to bulk-edit; live row light-blue / next dark-blue (Titan), red + % (MagicQ), >> and a red next box (ONYX); Hog: Set 2s Thru 10s fans a range; edits behind an Edit arm', 'Live green / next blue as the cards already do; arm the next from the sheet while locked'],
  ]
  const table = `<table style="border-collapse:collapse; width:100%; font-size:11.5px; line-height:1.4;"><thead><tr>${['View', 'Desk', 'What it does', 'What we take'].map((h, i) => `<th style="text-align:left; padding:6px 10px; border-bottom:1px solid ${T.border}; font-size:10px; font-weight:700; text-transform:uppercase; letter-spacing:0.1em; color:${T.mfg}; ${i < 2 ? 'width:90px;' : i === 3 ? 'width:30%;' : ''}">${h}</th>`).join('')}</tr></thead><tbody>${survey.map(([v, d, a, b]) => `<tr><td style="padding:6px 10px; border-bottom:1px solid ${T.border}; font-weight:600; vertical-align:top;">${v}</td><td style="padding:6px 10px; border-bottom:1px solid ${T.border}; font-weight:500; vertical-align:top; white-space:nowrap;">${d}</td><td style="padding:6px 10px; border-bottom:1px solid ${T.border}; color:${T.mfg}; vertical-align:top;">${a}</td><td style="padding:6px 10px; border-bottom:1px solid ${T.border}; vertical-align:top;">${b}</td></tr>`).join('')}</tbody></table>`
  const rules = [
    ['A cell is a cell everywhere', 'Patch address, raw level, cue fade: each is a trigger in a 30px-headed sheet — 36px tall, or 44 on the DMX sheet where a cell carries two lines — selected by a marquee, opened by ⏎ or a double click, committed to every selected cell. The surfaces differ in their columns and their verbs, never in the gesture.'],
    ['Consecutive is the batch default for an address', 'Every desk patches a range by footprint (Eos, Hog, MA3 Edit Patch) and offers a stride as the exception. Set over N addresses lands them consecutively from the typed one; Fan is the stride. Setting four fixtures to one address is never what was meant.'],
    ['The overlap is on the cell', 'A clash is a destructive ring on the Address cell with the other fixture on its title, and a legend line in the footer — not a warning found later in a sheet. The editor says which head would collide before Apply; the server still refuses.'],
    ['Raw output is a grid', 'The DMX sheet is 16 to a row because the address space is the thing being read: a fixture\'s RGB run reads across, not down. Cells keep the programmer\'s cell contract and the ownership rings. The row-per-address list is on the canvas as the fallback.'],
    ['The lock is the cue sheet\'s scope', 'Output scope made the programmer\'s cells inert without changing the grid; the show lock does the same to the cue sheet. Locked: read, marquee, arm-as-next. Unlocked: every cell edits, under the amber wash the cards already wear. GO, BACK, Space and ⌫ are untouched.'],
    ['Cards · Table is a switcher, not a mode', 'A sibling route plus a sticky preference, exactly the Fixtures / Groups wiring: the URL says which, localStorage remembers, the cards route redirects when the sticky says table. On Channels the Edit / Done toggle is not drawn in Table — a deliberate double click or ⏎ is the guard the sliders needed — and Unpark All keeps its confirm dialog rather than the Edit gate.'],
  ]
  const open = [
    ['One word for the second view', 'Fixtures and Groups say Cards · List; Channels and Show would say Cards · Table, because their second view is a table and their first genuinely is cards. Either rename the two existing switchers to Table or accept two words. The mockups say Table.'],
    ['Where the patch sheet lives', 'It stays the Patch List tab of Project Settings in these boards. If it grows a universe map and a rapid-patch flow, it may want to be a route again (`routes/Patches.tsx` still exists for exactly that).'],
    ['A bulk patch route', 'A Set over N addresses is N PUTs today; the clash check is per PUT, so a consecutive batch could half-apply. A `PUT /patches/addresses` that takes the whole batch and refuses atomically is the backend half of this.'],
    ['Cue sheet columns', 'Name · Fade · Curve · Follow · Notes are the editable five, and the cue number keeps its inline edit on the Cue column; Book, Layers, FX and Hooks read out and open the card. Whether the sheet should also hold the layer stack inline (a Layers column that expands) is a separate design.'],
    ['Channels cell contents', 'Address + attribute on line one, value on line two, the fixture name on the first cell of its run. Percent vs 0–255 readout, and a level bar behind the value, are toggles the desks all have and the mockup does not draw.'],
  ]
  const html = `${HEAD}
<div class="app" style="width:${w}px; height:1080px; padding:28px 32px; display:flex; flex-direction:column; gap:24px;">
  ${title2('Sheet views — the survey, the rules, and what is open', 'The programmer gained drag selection, double-click Set, keyboard entry and a verbs bar. This canvas rolls them out to the patch list, channels and show. The survey below is of grandMA3, Eos, Titan, MagicQ, Hog 4 and ONYX manuals; the rules are what the three mockups apply.')}
  <div style="display:flex; flex-direction:column; gap:10px;">${section('What the desks do in these three views')}${table}</div>
  <div style="display:grid; grid-template-columns:repeat(2, minmax(0, 1fr)); gap:14px 28px;">${rules.map(([h, p]) => `<div style="display:flex; flex-direction:column; gap:3px;"><span style="font-size:12px; font-weight:700;">${h}</span>${note(p)}</div>`).join('')}</div>
  <div style="display:flex; flex-direction:column; gap:10px;">${section('Open — for Chris to call')}<div style="display:grid; grid-template-columns:repeat(2, minmax(0, 1fr)); gap:14px 28px;">${open.map(([h, p]) => `<div style="display:flex; flex-direction:column; gap:3px;"><span style="font-size:12px; font-weight:700;">${h}</span>${note(p)}</div>`).join('')}</div></div>
</div>
${TAIL}`
  writeFileSync('Spec.dc.html', html)
  return { w, h: 1080, title: 'The survey, the rules, the open calls' }
}
boards.Spec = specBoard()

// ---- canvas.json ----------------------------------------------------------------------------------
const GAPX = 100, GAPY = 160
const layout = [
  ['Spec', 0, 0],
  ['Kit', boards.Spec.w + GAPX, 0],
]
const y2 = Math.max(boards.Spec.h, boards.Kit.h) + GAPY
layout.push(['Main', 0, y2], ['Channels', boards.Main.w + GAPX, y2])
const y3 = y2 + Math.max(boards.Main.h, boards.Channels.h) + GAPY
layout.push(['Show', 0, y3], ['ChannelsRows', boards.Show.w + GAPX, y3])
const canvas = {
  artboards: layout.map(([file, x, y]) => ({ file: `${file}.dc.html`, title: boards[file].title, x, y, w: boards[file].w, h: boards[file].h })),
  annotations: [
    { id: 'brief', x: 0, y: -300, w: 760, text: 'Sheet views — the programmer\'s grid gestures on the patch list, channels and show.\n\nDrag selection (rows from the first column, cells from the rest), single click selects, double click or ⏎ opens one editor for every selected cell, typing seeds it, ⌫ clears, and row C carries the counts, the family pill and the verbs. Each surface keeps its own columns and adds its own verbs after Set · Clear · Fan.\n\nChannels and Show gain a Cards · Table switcher on their header row: a sibling route plus a sticky localStorage preference, the Fixtures / Groups wiring. The patch list is already a table and simply becomes this one.\n\nAssumed: static mockups, dark only (read at a desk), 1440 wide; the app header and sidebar drawn as they are. Spec has the desk survey and the open calls; Kit says which code moves where.' },
    { id: 'channels-call', x: boards.Show.w + GAPX, y: y3 - 150, w: 520, text: 'Two shapes for the channels table. The 16-wide DMX sheet (above) is the recommendation — every desk draws raw output as a grid. This row list is the alternate: identical kit, worse at reading an address space.' },
  ],
  launch: { view: 'canvas' },
}
writeFileSync('canvas.json', JSON.stringify(canvas, null, 2) + '\n')
console.log(Object.entries(boards).map(([k, b]) => `${k} ${b.w}×${b.h}`).join('\n'))
