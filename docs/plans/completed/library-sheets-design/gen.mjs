// Generates the library-sheets design artboards (Scripts, FX Library, Looks, Templates, Speed Masters on the sheet kit).
// The chrome builders up to the LIBRARY SHEETS banner are copied from list-shell-design/gen.mjs.
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
  const items = ['menu', 'grid', 'layers', 'palette', 'sparkles', 'slidersV', 'theater', 'book', 'wave', 'slidersH', 'table']
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
const title2 = (t, sub) => `<div style="display:flex; flex-direction:column; gap:4px;"><span style="font-size:20px; font-weight:700;">${t}</span>${sub ? `<span style="font-size:12px; color:${T.mfg}; max-width:900px; line-height:1.5;">${codify(sub)}</span>` : ''}</div>`
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

const crumbs = (page) => `<nav style="display:flex; align-items:center; gap:4px; font-size:14px; white-space:nowrap;"><span style="color:${T.mfg};">Projects</span>${ico('chevR', 16, `color:${T.mfg}`)}<span style="display:inline-flex; align-items:center; gap:8px; color:${T.mfg};">Experiment <span style="display:inline-flex; align-items:center; height:${SYS.chip}px; padding:0 8px; border-radius:999px; background:${T.primary}; color:${T.pfg}; font-size:12px; font-weight:500;">active</span></span>${ico('chevR', 16, `color:${T.mfg}`)}<span style="font-weight:500; color:${T.fg};">${page}</span></nav>`
const DMX_ROW_H = 44
const WASH = `border-color:${C.amberWashBorder}; background:${C.amberWash};`

// =====================================================================================================
// LIST SHELL — one shell for every list view. Six surfaces drawn at the iPad 11 frame the screenshots
// were taken at (1180×820), each on the same shell: a 48px header row, 40px chrome rows, the
// selection bar, the sheet, a 22px footer, all on a 12px gutter. Everything above this line is the
// chrome vocabulary shared with programmer-chrome-design and sheet-views-design.
// =====================================================================================================
const boards = {}
const F = { w: 1180, h: 820 }
const APP_HEADER = 52
const SIDEBAR = 64
const MAINW = F.w - SIDEBAR
const FOOTER = 22
const SHEET_HEADER = 30

// ---- the shell pieces (proposed) -------------------------------------------------------------------
// SheetPage.Header: 48px, 12px gutter, breadcrumbs left, the switcher and actions right.
const pageHeader = (left, right, extra = '') =>
  `<div style="height:${SYS.header}px; flex:0 0 auto; display:flex; align-items:center; gap:12px; padding:0 ${SYS.gutter}px; border-bottom:1px solid ${T.border}; ${extra}"><div style="flex:1; min-width:0; display:flex; align-items:center;">${left}</div><div style="display:flex; align-items:center; gap:8px; flex:0 0 auto;">${right}</div></div>`
// SheetPage.Row: 40px, 12px gutter, 8px between 32px controls, owns its bottom border.
const chromeRow = (inner, extra = '') =>
  `<div style="height:${SYS.row}px; flex:0 0 auto; display:flex; align-items:center; gap:${SYS.gap}px; padding:0 ${SYS.gutter}px; border-bottom:1px solid ${T.border}; ${extra}">${inner}</div>`
// SheetPage.Footer: 22px, 10.5px muted, owns its top border.
const footer = (left, right = '') =>
  `<div style="height:${FOOTER}px; flex:0 0 auto; display:flex; align-items:center; gap:12px; padding:0 ${SYS.gutter}px; border-top:1px solid ${T.border}; font-size:10.5px; color:${T.mfg}; white-space:nowrap; overflow:hidden;">${left}${right ? `<span style="margin-left:auto;">${right}</span>` : ''}</div>`
// The sheet: bg-background on header, sticky column and body alike; no top border of its own.
const sheetBody = (cols, rows, { fade = false, inert = [] } = {}) =>
  `<div style="flex:1; min-height:0; display:flex; flex-direction:column; background:${T.bg}; overflow:hidden; position:relative;">${sheetHeader(cols, { inert })}${rows}${fade ? `<div style="position:absolute; top:0; right:0; bottom:0; width:24px; background:linear-gradient(to left, ${T.bg}, transparent); pointer-events:none;"></div>` : ''}</div>`
// The filter field: Input h-8 pl-9, max-w-[340px] flex-[999_1_0%].
const filterField = (ph = 'Filter…', w = 340) =>
  `<span style="display:inline-flex; align-items:center; gap:8px; height:32px; max-width:${w}px; flex:999 1 0%; min-width:0; padding:0 12px; border-radius:6px; border:1px solid ${T.border}; background:oklch(0.274 0.006 286.033 / 0.30); font-size:14px; color:${T.mfg}; white-space:nowrap; overflow:hidden;">${ico('search', 16, `color:${T.mfg}`)}${ph}</span>`
// The one legend swatch: size-3 rounded-sm, ringed by the real ownership class.
const legendSwatch = (ring, t, { fill = false, wave = false, dim = false } = {}) =>
  `<span style="display:inline-flex; align-items:center; gap:6px; flex:0 0 auto; ${dim ? 'opacity:0.62;' : ''}"><span style="display:grid; place-items:center; width:12px; height:12px; border-radius:3px; ${ring ? `box-shadow:inset 0 0 0 1px ${ring};` : `background:${T.muted};`} ${fill ? `background:${fill};` : ''} color:${T.violet};">${wave ? ico('wave', 8) : ''}</span>${t}</span>`
const ownershipLegend = () =>
  `<span style="font-weight:500;">Owned by</span>${legendSwatch(C.ringYou, 'You', { fill: 'oklch(0.623 0.214 259.815 / 0.10)' })}${legendSwatch(C.ringCue, 'Cue')}${legendSwatch(C.ringFx, 'Effect', { wave: true })}${legendSwatch(C.ringParked, 'Parked', { fill: C.parkedBg })}${legendSwatch(null, 'Nothing asserts it', { dim: true })}`
const layerLegend = () => `<span style="display:inline-flex; align-items:center; gap:6px;">${ico('layers', 12)}from a layer</span>`

// ---- the frame: app header, sidebar, main with the page ground ---------------------------------------
function frame(active, body) {
  return `${HEAD}
<div class="app" style="width:${F.w}px; height:${F.h}px; display:flex; flex-direction:column;">
  ${appHeader({ vw: F.w, cw: F.w })}
  <div style="flex:1; min-height:0; display:flex;">
    ${sidebar(F.h - APP_HEADER, active)}
    <main style="flex:1; min-width:0; display:flex; flex-direction:column; background:${C.mainBg}; overflow:hidden;">${body}</main>
  </div>
</div>
${TAIL}`
}

const cell = (t, { head = false, w = null, amber = false, mono: m = false, color = null } = {}) =>
  `<td style="padding:6px 10px; border-bottom:1px solid ${T.border}; vertical-align:top; font-size:11.5px; line-height:1.45; ${head ? `font-weight:600; white-space:nowrap; color:${T.fg};` : `color:${color ?? T.mfg};`} ${w ? `width:${w}px;` : ''} ${amber ? `color:${T.amber};` : ''} ${m ? mono + 'font-size:10.5px;' : ''}">${codify(t)}</td>`
const th = (t, w = null) => `<th style="text-align:left; padding:6px 10px; border-bottom:1px solid ${T.border}; font-size:10px; font-weight:700; text-transform:uppercase; letter-spacing:0.1em; color:${T.mfg}; white-space:nowrap; ${w ? `width:${w}px;` : ''}">${t}</th>`
const tbl = (heads, rows) => `<table style="border-collapse:collapse; width:100%;"><thead><tr>${heads.map((h) => (Array.isArray(h) ? th(h[0], h[1]) : th(h))).join('')}</tr></thead><tbody>${rows.map((r) => `<tr>${r}</tr>`).join('')}</tbody></table>`
const h3 = (t) => `<span style="font-size:12px; font-weight:700; color:${T.fg};">${t}</span>`
const block = (h, p) => `<div style="display:flex; flex-direction:column; gap:3px;">${h3(h)}${note(p)}</div>`

// =====================================================================================================
// LIBRARY SHEETS — Scripts, FX Library, Looks, Templates and Speed Masters on the sheet kit.
// Everything above this banner is list-shell-design/gen.mjs's chrome vocabulary, copied verbatim so
// the records draw one app. `node gen.mjs` writes project/*.dc.html and project/canvas.json.
// =====================================================================================================
const OUT = ''
P.braces = 'M8 3H7a2 2 0 0 0-2 2v5a2 2 0 0 1-2 2 2 2 0 0 1 2 2v5a2 2 0 0 0 2 2h1M16 21h1a2 2 0 0 0 2-2v-5a2 2 0 0 1 2-2 2 2 0 0 1-2-2V5a2 2 0 0 0-2-2h-1'
P.swatch = 'M11 17a4 4 0 0 1-8 0V5a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2ZM16.7 13H19a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H7M7 17h.01M11 8l2.3-2.3a2.4 2.4 0 0 1 3.4.1L18.1 7a2.4 2.4 0 0 1 .1 3.4L10 18.8'
P.copy = 'M8 8h12v12H8zM4 16V4h12'
P.hand = P.hand
P.clapper = 'M20.2 6 3 11l-.9-2.4c-.3-1.1.3-2.2 1.3-2.5l13.5-4c1.1-.3 2.2.3 2.5 1.3ZM6.2 5.3l3.1 3.9M12.4 3.4l3.1 4M3 11h18v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z'
P.fork = 'M6 3v6a3 3 0 0 0 3 3h6a3 3 0 0 1 3 3v6M6 21v-3M18 3v3'
P.record = 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm0 6a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z'
P.hammer = 'm15 12-8.4 8.4a2.1 2.1 0 1 1-3-3L12 9M17.6 15 22 10.6M20.9 11.7l-1.3-1.3c-.6-.6-.9-1.4-.9-2.2V6.9L16.3 4.5a5.6 5.6 0 0 0-4-1.6H9l.9.8A6.2 6.2 0 0 1 12 8.4V10l2 2h1.6c.8 0 1.6.3 2.2.9l1.2 1.3'
P.alert = 'M12 9v4M12 17h.01M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z'
P.gauge = 'M12 14l3.5-3.5M3.34 19a10 10 0 1 1 17.32 0'

const LIB = { gutter: 12 }
P.fan = 'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6ZM3 12h6M15 12h6'


// ---- the sidebar, with the five views' own glyphs ------------------------------------------------
function libSidebar(height, active) {
  const items = ['grid', 'layers', 'braces', 'sparkles', 'swatch', 'palette', 'gauge', 'slidersV', 'theater', 'book', 'wave', 'table']
  return `<div style="width:64px; flex:0 0 auto; height:${height}px; border-right:1px solid ${T.border}; background:${T.bg}; display:flex; flex-direction:column; align-items:center; padding-top:8px; gap:8px; overflow:hidden;">${items.map((n) => `<span style="width:40px; height:36px; border-radius:6px; display:flex; align-items:center; justify-content:center; color:${n === active ? T.fg : T.mfg}; ${n === active ? `background:${T.muted};` : ''}">${ico(n, 20)}</span>`).join('')}</div>`
}
function libFrame(active, body, overlay = '') {
  return `${HEAD}
<div class="app" style="width:${F.w}px; height:${F.h}px; display:flex; flex-direction:column;">
  ${appHeader({ vw: F.w, cw: F.w })}
  <div style="flex:1; min-height:0; display:flex;">
    ${libSidebar(F.h - APP_HEADER, active)}
    <main style="flex:1; min-width:0; display:flex; flex-direction:column; background:${C.mainBg}; overflow:hidden; position:relative;">${body}${overlay}</main>
  </div>
</div>
${TAIL}`
}

// ---- row B of a library: filter · partition chips · spacer · the create verb ---------------------
function partition(items, on) {
  return `<nav style="display:inline-flex; align-items:center; gap:2px; border-radius:8px; border:1px solid ${T.border}; background:${T.card}; padding:2px; flex:0 1 auto; min-width:0; overflow:hidden;">${items
    .map(([t, n]) => `<span style="display:inline-flex; align-items:center; gap:6px; height:26px; border-radius:6px; padding:0 9px; font-size:12px; font-weight:600; white-space:nowrap; ${t === on ? `background:${T.muted}; color:${T.fg};` : `color:${T.mfg};`}">${t}${n != null ? `<span style="font-size:10px; font-weight:500; color:${T.mfg}; font-variant-numeric:tabular-nums;">${n}</span>` : ''}</span>`)
    .join('')}</nav>`
}
const libRowB = ({ chips = '', create, extra = '', filter = 'Filter…' }) =>
  chromeRow(`${filterField(filter, 260)}${chips}${spacer()}${extra}${create}`)
const createBtn = (label, icon = 'plus') => btn({ icon: ico(icon, 15), label, variant: 'primary' })

// ---- the divider that groups a partition under All (SheetTable's divider arm, bg-muted/30) --------
const groupRow = (t, n) =>
  `<div style="height:36px; flex:0 0 auto; display:flex; align-items:center; gap:12px; padding:0 12px; border-bottom:1px solid ${T.border}; background:oklch(0.274 0.006 286.033 / 0.30);"><span style="height:1px; flex:1; background:${T.border};"></span><span style="border-radius:4px; border:1px solid ${T.border}; background:${T.card}; padding:1px 8px; font-size:12px; font-weight:500; color:${T.mfg}; white-space:nowrap;">${t}${n != null ? `<span style="margin-left:6px; font-variant-numeric:tabular-nums; opacity:0.7;">${n}</span>` : ''}</span><span style="height:1px; flex:1; background:${T.border};"></span></div>`

// ---- the name column: sticky, the rename editor on a double click, the open pencil ---------------
function libName(c, name, { sel = false, sub = null, pencil = false, lock = false, mu = false, lead = '' } = {}) {
  return `<span style="position:relative; width:${c.w}px; flex:0 0 auto; height:100%; display:flex; align-items:center; gap:6px; padding:0 8px; background:${sel ? C.rowSel : 'transparent'}; box-shadow:${sel ? `inset 3px 0 0 ${T.fg}` : 'none'}; overflow:hidden; white-space:nowrap;">
    ${lead}<span style="min-width:0; flex:1; overflow:hidden; text-overflow:ellipsis; font-size:14px; font-weight:${sel ? 600 : 400}; color:${mu ? T.mfg : T.fg};">${name}${sub ? `<span style="font-size:11px; color:${T.mfg}; margin-left:6px;">${sub}</span>` : ''}</span>
    ${lock ? ico('lock', 12, `color:${T.mfg}; opacity:0.7`) : ''}
    ${pencil ? `<span style="width:24px; height:24px; border-radius:6px; display:grid; place-items:center; color:${T.fg}; background:oklch(0.274 0.006 286.033 / 0.6); flex:0 0 auto;">${ico('pencil', 12)}</span>` : ''}
  </span>`
}
// A read-out: no cell trigger, no gutter, never in the marquee.
const readOut = (c, inner, { dim = false } = {}) =>
  `<span style="width:${c.w}px; flex:${c.flex ? '1 1 0' : '0 0 auto'}; min-width:0; height:100%; display:flex; align-items:center; ${dim ? 'opacity:0.55;' : ''}">${inner}</span>`
const miniChip = (t, { fg = T.fg, bg = T.muted, bc = 'transparent' } = {}) =>
  `<span style="display:inline-flex; align-items:center; gap:4px; height:18px; padding:0 6px; border-radius:999px; background:${bg}; border:1px solid ${bc}; color:${fg}; font-size:10px; font-weight:500; white-space:nowrap; flex:0 0 auto;">${t}</span>`
const chips = (list) => `<span style="display:flex; gap:4px; padding:0 6px; overflow:hidden;">${list.map((t) => miniChip(t)).join('')}</span>`
const count = (n, icon = null, { zero = '—' } = {}) =>
  n === 0 ? `<span style="padding:0 8px; font-size:12px; color:oklch(0.705 0.015 286.067 / 0.45);">${zero}</span>` : `<span style="display:inline-flex; align-items:center; gap:5px; padding:0 8px; font-size:12px; color:${T.mfg}; font-variant-numeric:tabular-nums;">${icon ? ico(icon, 12) : ''}${n}</span>`
const sw = (colour) => `<span style="width:14px; height:14px; border-radius:3px; background:${colour}; border:1px solid ${T.border}; flex:0 0 auto;"></span>`
const swatches = (list) => `<span style="display:flex; gap:3px; padding:0 8px;">${list.map(sw).join('')}</span>`
// A cell with nothing to set (master 1's Follows, a follower's Start, a manual master's Ratio, an effect
// template's Fade, a value template's Master) is **blank**, as the programmer draws a column a row resolves
// nothing for (lighting-react `FixturesTable`) — no dot, no dash. The em-dash is the mark an empty but
// *settable* cell wears; a second glyph for "nothing to set" read as a control that was merely unset.
const inertDash = ''
// The ReadOut button (lifted from CueSheet): a read-out whose display is a press.
const readOutBtn = (label, { icon = null, dim = false } = {}) =>
  `<span style="display:inline-flex; align-items:center; justify-content:center; gap:5px; height:24px; min-width:44px; margin:0 6px; padding:0 8px; border-radius:6px; border:1px solid ${T.border}; background:oklch(0.274 0.006 286.033 / 0.30); font-size:11px; font-weight:600; letter-spacing:0.04em; ${dim ? 'opacity:0.35;' : ''}">${icon ? ico(icon, 12) : ''}${label}</span>`

// The label line an editor's popover form draws first (editor kit D8).
const labelLine = (left, right) =>
  `<div style="display:flex; align-items:center; justify-content:space-between; gap:8px; font-size:9px; font-weight:700; text-transform:uppercase; letter-spacing:0.08em; color:${T.mfg};"><span>${left}</span><span>${right}</span></div>`
const readoutLine = (t) => `<span style="font-size:10px; line-height:1.45; color:${T.mfg};">${t}</span>`
const unitField = (v, unit, { w = 120, focus = true } = {}) =>
  `<span style="display:inline-flex; align-items:center; gap:6px; height:28px; width:${w}px; padding:0 8px; border-radius:8px; border:1px solid ${focus ? T.primary : T.border}; background:oklch(0.274 0.006 286.033 / 0.3); font-size:13px; ${mono} ${focus ? 'box-shadow:0 0 0 3px oklch(0.623 0.214 259.815 / 0.3);' : ''}"><span style="${focus ? 'background:oklch(0.623 0.214 259.815 / 0.45); border-radius:2px;' : ''}">${v}</span><span style="margin-left:auto; font-size:10px; color:${T.mfg}; font-family:inherit;">${unit}</span></span>`
const editorFoot = (inner) => `<div style="display:flex; align-items:center; gap:6px; border-top:1px solid ${T.border}; margin:0 -16px -16px; padding:8px 12px;">${inner}</div>`

// ---- a library surface: header · row B · the bar · the sheet · footer ----------------------------
function surface({ file, active, page, rowB, bar, cols, rows, foot, overlay = '', title }) {
  const body = `${pageHeader(crumbs(page), '')}${rowB}${bar}${sheetBody(cols, rows)}${foot}`
  writeFileSync(OUT + file, libFrame(active, body, overlay))
  return { w: F.w, h: F.h, title }
}
const colsOf = (list) => Object.fromEntries(list.map((c) => [c.key, c]))
const HEADER_TOP = SYS.header + SYS.row * 2 + SHEET_HEADER // header, row B, the bar, the sheet header

// =====================================================================================================
// SPEED MASTERS — the one library with a live value in every row.
// =====================================================================================================
const SMC = [
  { key: 'name', label: 'Master', w: 210 },
  { key: 'beat', label: '', w: 34 },
  { key: 'bpm', label: 'BPM', w: 124 },
  { key: 'tap', label: 'Tap', w: 70 },
  { key: 'start', label: 'Start', w: 92 },
  { key: 'leader', label: 'Follows', w: 104 },
  { key: 'ratio', label: 'Ratio', w: 80 },
  { key: 'usage', label: 'Usage', w: 104 },
  { key: 'refs', label: 'Used by', w: 76 },
  { key: 'notes', label: 'Notes', w: 222, flex: true },
]
const SM = colsOf(SMC)
const MASTERS = [
  { i: 1, name: 'Global', bpm: '128.0', start: '120', leader: null, ratio: null, usage: null, refs: 14, notes: 'The desk tempo', glob: true },
  { i: 2, name: 'Colour chase', bpm: '64.0', follow: '½ of M1', start: null, leader: 'M1', ratio: '½', usage: 'Colour', refs: 6, notes: '' },
  { i: 3, name: 'Movement', bpm: '96.0', start: '96', leader: 'Manual', ratio: null, usage: 'Position', refs: 4, notes: 'Mover chases', sel: true },
  { i: 4, name: 'Strobe hits', bpm: '128.0', follow: '1× of M1', start: null, leader: 'M1', ratio: '1×', usage: 'Dimmer', refs: 3, notes: '' },
  { i: 5, name: 'Slow wash', bpm: '42.0', tapped: true, start: '60', leader: 'Manual', ratio: null, usage: null, refs: 0, notes: 'Ballad set', sel: true },
]
const beatDot = (on) => `<span style="width:10px; height:10px; margin-left:12px; border-radius:999px; ${on ? `background:${T.green}; box-shadow:0 0 6px ${T.green};` : `background:oklch(0.723 0.219 149.579 / 0.25);`}"></span>`
function masterRow(m) {
  const lead = `<span style="${mono} font-size:11px; color:${T.mfg}; width:22px; flex:0 0 auto;">M${m.i}</span>`
  const bpmCell = m.follow
    ? readOut(SM.bpm, `<span style="padding:0 8px; font-size:13px; ${mono} color:${T.fg};">${m.bpm}</span><span style="font-size:10px; color:${T.mfg};">${m.follow}</span>`)
    : cellWrap(SM.bpm, `${cellText(m.bpm, { color: T.fg, mono: true, size: 13 })}${m.tapped ? miniChip('tapped', { bg: 'transparent', bc: T.border, fg: T.mfg }) : ''}`, { sel: m.sel })
  const start = m.start ? cellWrap(SM.start, cellText(m.start, { mono: true })) : readOut(SM.start, inertDash)
  const leader = m.glob ? readOut(SM.leader, inertDash) : cellWrap(SM.leader, cellText(m.leader, { color: m.leader === 'Manual' ? T.mfg : T.fg }))
  const ratio = m.ratio ? cellWrap(SM.ratio, cellText(m.ratio, { color: T.fg })) : readOut(SM.ratio, inertDash)
  const usage = cellWrap(SM.usage, m.usage ? cellText(m.usage, { color: T.fg }) : dash)
  const notes = cellWrap(SM.notes, m.notes ? cellText(m.notes) : dash)
  return sheetRow(
    libName(SM.name, m.name, { lead, sub: m.glob ? 'Global' : null, pencil: false }) +
      readOut(SM.beat, beatDot(m.i !== 5)) +
      bpmCell +
      readOut(SM.tap, readOutBtn('TAP', { dim: !!m.follow })) +
      start + leader + ratio + usage +
      readOut(SM.refs, count(m.refs)) +
      notes,
  )
}
function speedBoard() {
  const rowB = libRowB({ create: createBtn('New master'), filter: 'Filter…' })
  const bar = selectionBar({ counts: ['2 cells'], family: 'BPM', verbs: `${verb('pencil', 'Set')}${verb('eraser', 'Clear', { disabled: true })}${divider()}${verb('trash', 'Delete')}${btn({ label: 'Deselect', variant: 'ghost' })}` })
  const rows = MASTERS.map(masterRow).join('')
  const rowY = (i) => HEADER_TOP + 36 * i
  const pop = popover({
    x: SM.name.w + SM.beat.w + SM.bpm.w + SM.tap.w + 8,
    y: rowY(2),
    w: 288,
    inner: `${labelLine('2 masters · live tempo', 'BPM')}
      <div style="display:flex; align-items:center; gap:8px;">${unitField('90', 'bpm', { w: 132 })}${btn({ icon: ico('hand', 13), label: 'Tap', h: 28 })}</div>
      ${readoutLine('Written to the desk now, like the tile’s click-to-type. Start stays 96 and 60 — that is the tempo each boots at, a column of its own.')}
      ${readoutLine('M2 and M4 follow M1 · skipped')}`,
  })
  const foot = footer('<span style="font-variant-numeric:tabular-nums;">5 masters · 2 follow</span>', 'M1 is the global tempo · BPM is live, Start is stored')
  return surface({ file: 'SpeedMasters.dc.html', active: 'gauge', page: 'Speed Masters', rowB, bar, cols: SMC, rows, foot, overlay: pop, title: 'Speed Masters — every column a desk fact' })
}
boards.SpeedMasters = speedBoard()

// =====================================================================================================
// LOOKS — named, recorded, edited by Include; the sheet edits what a Look *is called* and holds.
// =====================================================================================================
const LKC = [
  { key: 'name', label: 'Look', w: 220 },
  { key: 'fam', label: 'Families', w: 170 },
  { key: 'prev', label: 'Preview', w: 130 },
  { key: 'cont', label: 'Contents', w: 190 },
  { key: 'notes', label: 'Notes', w: 206, flex: true },
  { key: 'layers', label: 'Cue layers', w: 96 },
  { key: 'pages', label: 'Busk pages', w: 104 },
]
const LK = colsOf(LKC)
const LOOKS = [
  { n: 'Ballyhoo', fam: ['Position'], prev: [], cont: 'effects follow the layer', notes: '', l: 0, p: 3, sel: true },
  { n: 'Band Spots', fam: ['Intensity', 'Position'], prev: ['#fff4e0', '#fff4e0'], cont: '4 fixtures · 8 rows', notes: 'Front line', l: 2, p: 0, sel: true },
  { n: 'Blackout Front', fam: ['Intensity'], prev: ['#111'], cont: '6 fixtures · 6 rows', notes: '', l: 0, p: 0, sel: true },
  { n: 'Cool Fill', fam: ['Colour'], prev: ['#6fa8ff', '#4d7fff'], cont: '8 fixtures · 8 rows', notes: '', l: 3, p: 1 },
  { n: 'Strobe Chase', fam: ['Intensity'], prev: ['#fff'], cont: '12 fixtures · 12 rows · 1 fx', notes: '', l: 1, p: 1 },
  { n: 'Sunset', fam: ['Colour', 'Beam'], prev: ['#ff7a2f', '#ff4d6d', '#b04dff'], cont: '16 fixtures · 32 rows', notes: 'Finale', l: 4, p: 2 },
  { n: 'Warm Wash', fam: ['Colour', 'Intensity'], prev: ['#ff9d4a', '#ffb56b'], cont: '12 fixtures · 24 rows', notes: 'Act 1 base', l: 5, p: 2 },
]
function lookRow(k) {
  return sheetRow(
    libName(LK.name, k.n, { sel: k.sel, pencil: k.n === 'Band Spots' }) +
      readOut(LK.fam, chips(k.fam)) +
      readOut(LK.prev, k.prev.length ? swatches(k.prev) : `<span style="padding:0 8px; color:${T.violet};">${ico('wave', 14)}</span>`) +
      readOut(LK.cont, `<span style="padding:0 8px; font-size:12px; color:${T.mfg}; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">${k.cont}</span>`) +
      cellWrap(LK.notes, k.notes ? cellText(k.notes) : dash) +
      readOut(LK.layers, count(k.l, 'clapper')) +
      readOut(LK.pages, count(k.p, 'grid')),
    { sel: k.sel },
  )
}
function looksBoard() {
  const rowB = libRowB({ create: createBtn('Record from programmer', 'record'), extra: `<span style="font-size:12px; color:${T.mfg}; white-space:nowrap;">Templates ${ico('arrowR', 12)}</span>` })
  const bar = selectionBar({
    counts: ['3 looks'],
    family: null,
    hints: false,
    verbs: `${verb('slidersV', 'Include', { disabled: true })}${verb('hand', 'Pick up', { disabled: true })}${divider()}${verb('copy', 'Duplicate')}${verb('arrowR', 'Copy to…')}${verb('trash', 'Delete')}${btn({ label: 'Deselect', variant: 'ghost' })}`,
  })
  const rows = LOOKS.map(lookRow).join('')
  const foot = footer('<span style="font-variant-numeric:tabular-nums;">7 looks · 15 cue layers</span>', 'Values are recorded — Include a Look to change what it holds')
  return surface({ file: 'Looks.dc.html', active: 'swatch', page: 'Looks', rowB, bar, cols: LKC, rows, foot, title: 'Looks — three rows, the row verbs' })
}
boards.Looks = looksBoard()

// =====================================================================================================
// TEMPLATES — family chips filter, dividers group; Fade spreads; Value is the one real value cell.
// =====================================================================================================
const TPC = [
  { key: 'name', label: 'Template', w: 190 },
  { key: 'kind', label: 'Holds', w: 70 },
  { key: 'value', label: 'Value', w: 200 },
  { key: 'fade', label: 'Fade', w: 84 },
  { key: 'master', label: 'Master', w: 110 },
  { key: 'notes', label: 'Notes', w: 162, flex: true },
  { key: 'layers', label: 'Layers', w: 70 },
  { key: 'pages', label: 'Pages', w: 66 },
  { key: 'pressed', label: 'Pressed', w: 90 },
]
const TP = colsOf(TPC)
const valSw = (hex, t) => `${swatch(hex, t)}`
const valLevel = (pct) => bar(pct)
const valText = (t, color = T.mfg) => cellText(t, { color, mono: true })
const TGROUPS = [
  ['Intensity', [
    { n: 'Full', kind: 'Value', v: valLevel(100), fade: null, pr: '4m ago' },
    { n: 'Half', kind: 'Value', v: valLevel(50), fade: '2s', pr: '1h ago', mq: true },
    { n: 'Pulse', kind: 'Effect', fx: 'Dimmer Pulse · ¼', master: 'M4 Strobe', pr: '—' },
  ]],
  ['Colour', [
    { n: 'Amber', kind: 'Value', v: valSw('#ff9d4a', '#FF9D4A · extract'), fade: '1s', l: 2, p: 1, pr: '12s ago', mq: true },
    { n: 'Band Specials', kind: 'Value', per: '4 heads · per fixture', pr: '—' },
    { n: 'Deep Blue', kind: 'Value', v: valSw('#1030ff', '#1030FF · rgb only'), fade: null, l: 1, pr: '3m ago', mq: true },
    { n: 'Rainbow', kind: 'Effect', fx: 'Rainbow Cycle · 1', master: 'M2 Colour', p: 2, pr: '20m ago' },
  ]],
  ['Position', [
    { n: 'Home', kind: 'Value', v: valText('270°, 135°'), fade: '3s', pr: '—' },
    { n: 'Figure 8', kind: 'Effect', fx: 'Figure 8 · 2', master: 'M3 Movement', pr: '—' },
  ]],
]
function templateRow(t) {
  const kind = readOut(TP.kind, `<span style="padding:0 8px; font-size:12px; color:${t.kind === 'Effect' ? T.violet : T.mfg};">${t.kind}</span>`)
  let value
  if (t.fx) value = readOut(TP.value, `<span style="display:flex; align-items:center; gap:6px; padding:0 8px; font-size:12px; color:${T.mfg}; white-space:nowrap;">${ico('wave', 13, `color:${T.violet}`)}${t.fx}</span>`)
  else if (t.per) value = readOut(TP.value, `<span style="padding:0 8px; font-size:12px; color:${T.mfg};">${t.per}</span>`)
  else value = cellWrap(TP.value, t.v)
  const fade = t.fx ? readOut(TP.fade, inertDash) : cellWrap(TP.fade, t.fade ? cellText(t.fade, { mono: true, color: T.fg }) : dash, { sel: t.mq })
  const master = t.fx ? cellWrap(TP.master, cellText(t.master, { color: T.fg })) : readOut(TP.master, inertDash)
  return sheetRow(
    libName(TP.name, t.n) + kind + value + fade + master +
      cellWrap(TP.notes, dash) +
      readOut(TP.layers, count(t.l ?? 0)) +
      readOut(TP.pages, count(t.p ?? 0)) +
      readOut(TP.pressed, `<span style="padding:0 8px; font-size:11.5px; color:${T.mfg}; white-space:nowrap;">${t.pr}</span>`),
  )
}
function templatesBoard() {
  const chipsNav = partition([['All', 13], ['Intensity', 3], ['Colour', 4], ['Position', 2], ['Beam', 4]], 'All')
  const rowB = libRowB({ chips: chipsNav, create: createBtn('New template') })
  const bar = selectionBar({ counts: ['3 cells'], family: 'Fade', verbs: `${verb('pencil', 'Set')}${verb('eraser', 'Clear')}${verb('fan', 'Spread')}${divider()}${verb('hand', 'Pick up', { disabled: true })}${verb('copy', 'Duplicate')}${verb('arrowR', 'Copy to…')}${verb('trash', 'Delete')}${btn({ label: 'Deselect', variant: 'ghost' })}` })
  const rows = TGROUPS.map(([g, list]) => groupRow(g, list.length) + list.map(templateRow).join('')).join('')
  // the Fade editor, opened by ⏎ over the three-cell marquee: beside the first selected cell
  const fadeX = TP.name.w + TP.kind.w + TP.value.w
  const firstY = HEADER_TOP + 36 + 36 // divider, Full → Half
  const pop = popover({
    x: fadeX + TP.fade.w + 8,
    y: firstY,
    w: 288,
    inner: `${labelLine('3 templates', 'Fade')}
      <div style="display:flex; align-items:center; gap:8px;">${unitField('1.5', 's', { w: 120 })}</div>
      ${readoutLine('Half, Amber and Deep Blue · the press fades in over this')}
      ${editorFoot(`${spacer()}${btn({ label: 'Cancel', variant: 'ghost', h: 28 })}${btn({ label: 'Apply', variant: 'primary', h: 28 })}`)}`,
  })
  const foot = footer('<span style="font-variant-numeric:tabular-nums;">13 templates · showing all</span>', 'Value edits the intent — the desk resolves it per head')
  return surface({ file: 'Templates.dc.html', active: 'palette', page: 'Templates', rowB, bar, cols: TPC, rows, foot, overlay: pop, title: 'Templates — family chips, dividers, a Fade marquee' })
}
boards.Templates = templatesBoard()

// =====================================================================================================
// FX LIBRARY — categories as dividers; built-ins are read-only rows; Fork makes a custom copy.
// =====================================================================================================
const FXC = [
  { key: 'name', label: 'Effect', w: 200 },
  { key: 'out', label: 'Output', w: 100 },
  { key: 'mode', label: 'Mode', w: 96 },
  { key: 'timing', label: 'Timing', w: 86 },
  { key: 'params', label: 'Params', w: 70 },
  { key: 'props', label: 'Drives', w: 236, flex: true },
  { key: 'src', label: 'Source', w: 110 },
]
const FX = colsOf(FXC)
const srcBadge = (s) => {
  if (s === 'Built-in') return miniChip(`${ico('lock', 9)}Built-in`, { bg: 'transparent', bc: T.border, fg: T.mfg })
  if (s === 'Script') return miniChip(`${ico('braces', 9)}Script`, { bg: 'oklch(0.685 0.169 237.323 / 0.18)', fg: 'oklch(0.85 0.09 237)' })
  return miniChip('Custom', { bg: 'oklch(0.623 0.214 259.815 / 0.22)', fg: 'oklch(0.86 0.08 259)' })
}
const FXGROUPS = [
  ['Dimmer', [
    { n: 'Pulse', out: 'Dimmer', mode: 'Per fixture', t: 'Beat', p: 3, props: ['dimmer'], s: 'Built-in' },
    { n: 'Random Flicker', out: 'Dimmer', mode: 'Per fixture', t: 'Clock', p: 2, props: ['dimmer'], s: 'Custom', sel: true },
    { n: 'Sine Wave', out: 'Dimmer', mode: 'Distributed', t: 'Beat', p: 4, props: ['dimmer'], s: 'Built-in' },
    { n: 'Strobe', out: 'Dimmer', mode: 'Per fixture', t: 'Beat', p: 2, props: ['dimmer', 'strobe'], s: 'Built-in' },
  ]],
  ['Colour', [
    { n: 'Colour Chase', out: 'Colour', mode: 'Distributed', t: 'Beat', p: 3, props: ['rgbColour'], s: 'Built-in' },
    { n: 'Rainbow Cycle', out: 'Colour', mode: 'Distributed', t: 'Beat', p: 2, props: ['rgbColour'], s: 'Built-in' },
    { n: 'Warm Flicker', out: 'Colour', mode: 'Per fixture', t: 'Clock', p: 1, props: ['rgbColour'], s: 'Script', sel: true },
  ]],
  ['Position', [
    { n: 'Circle', out: 'Position', mode: 'Distributed', t: 'Beat', p: 4, props: ['position'], s: 'Built-in' },
    { n: 'Figure 8', out: 'Position', mode: 'Distributed', t: 'Beat', p: 4, props: ['position'], s: 'Built-in' },
  ]],
]
function fxRow(e) {
  const ro = e.s !== 'Custom'
  const txt = (c, t) => readOut(c, `<span style="padding:0 8px; font-size:12px; color:${T.mfg}; white-space:nowrap;">${t}</span>`)
  return sheetRow(
    libName(FX.name, e.n, { sel: e.sel, lock: e.s === 'Built-in', pencil: e.n === 'Random Flicker' }) +
      txt(FX.out, e.out) + txt(FX.mode, e.mode) +
      readOut(FX.timing, `<span style="display:inline-flex; align-items:center; gap:5px; padding:0 8px; font-size:12px; color:${T.mfg};">${ico(e.t === 'Beat' ? 'wave' : 'clock', 12)}${e.t}</span>`) +
      readOut(FX.params, count(e.p)) +
      readOut(FX.props, chips(e.props)) +
      readOut(FX.src, `<span style="padding:0 8px; display:flex;">${srcBadge(e.s)}</span>`),
    { sel: e.sel },
  )
}
function fxBoard() {
  const chipsNav = partition([['All', 9], ['Dimmer', 4], ['Colour', 3], ['Position', 2], ['Controls', 0], ['Composite', 0]], 'All')
  const rowB = libRowB({ chips: chipsNav, create: createBtn('New effect') })
  const bar = selectionBar({ counts: ['2 effects'], family: null, hints: false, verbs: `${verb('fork', 'Fork')}${verb('trash', 'Delete')}${btn({ label: 'Deselect', variant: 'ghost' })}` })
  const rows = FXGROUPS.map(([g, list]) => groupRow(g, list.length) + list.map(fxRow).join('')).join('')
  const foot = footer('<span style="font-variant-numeric:tabular-nums;">9 effects · 2 yours</span>', `Built-ins are read-only — Fork one to change it`)
  return surface({ file: 'FxLibrary.dc.html', active: 'sparkles', page: 'FX Library', rowB, bar, cols: FXC, rows, foot, title: 'FX Library — categories as dividers, built-ins read-only' })
}
boards.FxLibrary = fxBoard()

// =====================================================================================================
// SCRIPTS — the type sidebar becomes chips; Compile over a selection fills the Check column.
// =====================================================================================================
const SCC = [
  { key: 'name', label: 'Script', w: 240 },
  { key: 'type', label: 'Type', w: 150 },
  { key: 'lines', label: 'Lines', w: 70 },
  { key: 'check', label: 'Check', w: 220 },
  { key: 'used', label: 'Used by', w: 170, flex: true },
]
const SC = colsOf(SCC)
const checkOk = `<span style="display:inline-flex; align-items:center; gap:6px; padding:0 8px; font-size:12px; color:${T.green};">${ico('check', 13)}Compiles</span>`
const checkErr = (n) => `<span style="display:inline-flex; align-items:center; gap:6px; padding:0 8px; font-size:12px; color:${T.destructive};">${ico('alert', 13)}${n} errors · line 14</span>`
const checkBusy = `<span style="display:inline-flex; align-items:center; gap:6px; padding:0 8px; font-size:12px; color:${T.mfg};">${ico('rotate', 13)}Compiling…</span>`
const SCGROUPS = [
  ['General', [
    { n: 'Startup', type: 'General', lines: 12, check: null, used: '' },
    { n: 'Test Pattern', type: 'General', lines: 40, check: null, used: '' },
  ]],
  ['FX definition', [
    { n: 'Warm Flicker', type: 'FX definition', lines: 38, check: 'ok', used: 'registers Warm Flicker', sel: true },
  ]],
  ['FX application', [
    { n: 'Blackout Sweep', type: 'FX application', lines: 22, check: 'ok', used: 'cue hook · Q12', sel: true },
    { n: 'Chorus Hit', type: 'FX application', lines: 31, check: 'err', used: 'cue hooks · Q4, Q18', sel: true },
  ]],
  ['FX calc', [
    { n: 'Gauss Falloff', type: 'FX calc', lines: 18, check: 'busy', used: '', sel: true },
    { n: 'Stagger', type: 'FX calc (stateful)', lines: 27, check: null, used: '' },
  ]],
]
function scriptRow(s) {
  const chk = s.check === 'ok' ? checkOk : s.check === 'err' ? checkErr(2) : s.check === 'busy' ? checkBusy : `<span style="padding:0 8px; font-size:12px; color:oklch(0.705 0.015 286.067 / 0.45);">not checked</span>`
  return sheetRow(
    libName(SC.name, s.n, { sel: s.sel, pencil: s.n === 'Chorus Hit' }) +
      readOut(SC.type, `<span style="padding:0 8px; display:flex;">${miniChip(s.type, { bg: 'transparent', bc: T.border, fg: T.mfg })}</span>`) +
      readOut(SC.lines, count(s.lines)) +
      readOut(SC.check, chk) +
      readOut(SC.used, s.used ? `<span style="padding:0 8px; font-size:12px; color:${T.mfg}; white-space:nowrap;">${s.used}</span>` : `<span style="padding:0 8px; font-size:12px; color:oklch(0.705 0.015 286.067 / 0.45);">—</span>`),
    { sel: s.sel },
  )
}
function scriptsBoard() {
  const chipsNav = partition([['All', 7], ['General', 2], ['FX def', 1], ['FX app', 2], ['FX calc', 2]], 'All')
  const rowB = libRowB({ chips: chipsNav, create: createBtn('New script') })
  const bar = selectionBar({ counts: ['4 scripts'], family: null, hints: false, verbs: `${verb('hammer', 'Compile')}${verb('play', 'Run', { disabled: true })}${divider()}${verb('arrowR', 'Copy to…')}${verb('trash', 'Delete')}${btn({ label: 'Deselect', variant: 'ghost' })}` })
  const rows = SCGROUPS.map(([g, list]) => groupRow(g, list.length) + list.map(scriptRow).join('')).join('')
  const foot = footer('<span style="font-variant-numeric:tabular-nums;">7 scripts · 3 of 4 checked · 1 failed</span>', 'Check is this tab’s last compile — not stored')
  return surface({ file: 'Scripts.dc.html', active: 'braces', page: 'Scripts', rowB, bar, cols: SCC, rows, foot, title: 'Scripts — type chips, Compile over a selection' })
}
boards.Scripts = scriptsBoard()

// =====================================================================================================
// DOCUMENT BOARDS — Main, Kit, Model.
// =====================================================================================================
const docBoard = (file, w, h, inner) => {
  writeFileSync(OUT + file, `${HEAD}
<div class="app" style="width:${w}px; height:${h}px; padding:28px 32px; display:flex; flex-direction:column; gap:26px;">${inner.replace(/\*([^*\n<]+)\*/g, '<i>$1</i>')}</div>
${TAIL}`)
  return { w, h }
}
const grid2 = (items) => `<div style="display:grid; grid-template-columns:repeat(2, minmax(0, 1fr)); gap:14px 28px;">${items.map(([h, p]) => block(h, p)).join('')}</div>`
const grid3 = (items) => `<div style="display:grid; grid-template-columns:repeat(3, minmax(0, 1fr)); gap:14px 24px;">${items.map(([h, p]) => block(h, p)).join('')}</div>`
const sect = (t, inner) => `<div style="display:flex; flex-direction:column; gap:12px;">${section(t)}${inner}</div>`
const tag = (t, kind) => {
  const s = { go: `background:oklch(0.5 0.15 150 / 0.3); color:#86efac;`, ask: `background:oklch(0.5 0.12 80 / 0.3); color:oklch(0.9 0.15 85);`, be: `background:oklch(0.45 0.16 300 / 0.35); color:oklch(0.85 0.1 300);`, no: `background:oklch(0.5 0.15 25 / 0.25); color:oklch(0.85 0.12 25);` }[kind]
  return `<span style="display:inline-flex; align-items:center; height:16px; padding:0 6px; border-radius:999px; font-size:9px; font-weight:700; letter-spacing:0.06em; text-transform:uppercase; white-space:nowrap; ${s}">${t}</span>`
}

// ---- MAIN ----------------------------------------------------------------------------------------
function mainBoard() {
  const A = (t) => cell(t, { amber: true })
  const today = [
    [cell('Scripts', { head: true }), A('A type sidebar (a bottom sheet on a phone) beside a `space-y-1` list of `&lt;button&gt;` rows'), cell('name · type badge · a usage glyph that only ever reads the type'), A('none'), cell('`ScriptForm` sheet — name, the Kotlin editor, Compile, Run, Delete, Copy to project'), A('none')],
    [cell('FX Library', { head: true }), A('a shadcn `Table` with collapsible category header rows, in a 930-line route file'), cell('name · compatible properties · Built-in / Custom'), A('none'), cell('three sheets: detail (read-only), edit (name + script), new'), A('none')],
    [cell('Looks', { head: true }), A('`rounded-lg border divide-y` of `LookListRow`s, a hover `…` menu'), cell('name · notes or contents · swatches · families · fx · layers · pages'), A('none'), cell('`LookDetailSheet` — name and notes; contents by Include'), A('none')],
    [cell('Templates', { head: true }), A('a `Card divide-y` of `TemplateListRow`s under `LookFamilyFilterBar`'), cell('name · shape · value preview · family · layers · pages'), A('none'), cell('`TemplateEditor` (1,095 lines) — every field'), A('none')],
    [cell('Speed Masters', { head: true }), A('a `Card` holding a column of bordered row cards'), cell('beat · M# · name · badges · live BPM · TAP'), cell('live BPM (click-to-type) · TAP'), cell('`SpeedMasterDetailSheet` — name, usage, follow, start BPM, notes'), A('none')],
  ]
  const todayTbl = tbl([['View', 110], 'Layout today', 'A row shows', ['Inline edit', 140], 'Edited in', ['Multi-select', 90]], today.map((r) => r.join('')))
  const rules = [
    ['L1 · The sheet replaces the list, on the same route', 'No Cards · Table switcher: each view has one list today and would have one sheet. A second view has to earn its switcher; none of the five has one to offer. The record editors stay — they are what a row *opens*.'],
    ['L2 · The list shell, whole', 'Header 48 · row B · the selection bar · the sheet · footer 22, on the 12px gutter (`SheetPage`). Row B is the library row: filter · partition chips · spacer · the create verb, which leaves the header.'],
    ['L3 · Chips filter, dividers group', 'Where a library partitions exactly — script type, effect category, template family — the partition is a chip set on row B with counts, and under *All* the sheet is grouped by `divider` rows. Looks and Speed Masters do not partition and draw no chips (a Look spans families, by design).'],
    ['L4 · The name column', 'Sticky; a double click renames it in the kit’s `TextCell` popover (`firstColumnCellProps`, as on the patch list and the cue sheet); a pencil — and ⏎ on one selected row with no cells — opens the record’s editor. A rename is one row at a time.'],
    ['L5 · What a cell edits', 'Metadata everywhere (name, notes), plus the few values a library actually holds: a template’s Value, Fade and effect Master; a master’s live BPM, Start, Follows, Ratio and Usage. What a Look *holds* stays recorded — the sheet never grows a value grid for it (`LookDetailSheet`’s rule).'],
    ['L6 · Row verbs on the bar', 'After Set · Clear · Spread come the row verbs — Include, Pick up, Duplicate, Fork, Compile, Copy to…, Delete — then Deselect. The per-row `…` menus go: a verb over one row is a verb over a selection of one.'],
    ['L7 · Read-only by row and by scope', 'A built-in effect, master 1’s Follows, a follower’s BPM: `value: undefined`, skipped and named on the editor’s read-out. Another project’s library is the sheet’s read-only scope — the cue lock’s shape — with Copy to… the one live verb.'],
    ['L8 · One delete for a batch', 'Delete sends each, gathers the in-use refusals (`LOOK_IN_USE`, `TEMPLATE_IN_USE`, `SPEED_MASTER_IN_USE`) and asks once, listing what each is used by; *Delete anyway* forces only those. Master 1 is skipped by name.'],
  ]
  const calls = [
    ['1 · No Cards · Table switcher', 'The sheet is the view, on the route the list has today. The record editors stay as what a row opens.'],
    ['2 · Categories stop collapsing', 'The FX Library’s collapsible groups become dividers; the chips are how you narrow. One grouping rule for three libraries.'],
    ['3 · ⏎ on one row opens its editor', 'In the kit, so the patch list gets it too. The pencil is the pointer door; a double click on the name stays rename.'],
    ['4 · Template Value is editable in the cell — its own session', 'Generic value templates only: the family’s editor from the editor kit in intent mode (colour + policy, % of range, degrees, `dmx:` emitters). Per-fixture and effect templates read out and open the editor.'],
    ['5 · A master’s BPM cell is the live tempo', 'Set over two masters writes both clocks now (`speedMasters.setBpm`). The stored boot tempo is its own Start column.'],
    ['6 · Scripts gain a Check column', 'Compile over a selection writes each result into its row as it arrives — this tab only, never stored. The editor keeps its own compile dialog.'],
    ['7 · Fork a built-in effect', 'A custom definition from the built-in’s script and parameters (`POST /fx/definitions`), named “(Custom)”, opening in its editor.'],
    ['8 · Templates get a copy route in lighting7', '`POST …/templates/{id}/copy {targetProjectId, newName?}`, the Looks route’s shape. It gives Templates both Duplicate and Copy to…, and needs a desk restart.'],
  ]
  const html = `${title2('Library sheets — Scripts, FX Library, Looks, Templates and Speed Masters on the sheet kit', 'The brief (2026-09-23): move the five library views to the table layout — drag select, editing and the rest of what the Programmer, Show and Channels sheets do. Read from `lighting-react` at `ea56d99b`: none of the five imports `SheetPage`, `SheetTable` or `useSheet`, none can select more than one thing, and between them they have four layouts. The eight calls were answered on 2026-09-23; the plan follows. Nothing is built.')}
  ${sect('Today, measured from the code', todayTbl)}
  ${sect('Proposed — eight rules for every library sheet', grid2(rules))}
  ${sect('The calls — answered 2026-09-23 (all as drawn but 8)', grid2(calls))}
  ${sect('Not proposed', grid3([
    ['No bulk routes', 'Every batch is N requests, as the patch list’s address batch is. None of these libraries is big enough for a round-trip each to matter.'],
    ['No Look value grid', 'A Look is recorded and edited by Include. The sheet edits its name and notes and reports the rest.'],
    ['No stored order', 'Every library stays name-ordered (masters by index). The templates plan removed stored order on purpose; a sheet does not bring it back.'],
    ['No filter on Looks', 'A Look spans families, so a family chip would hide most of the library from most filters — the reason it moved to `/templates`.'],
    ['No new ideas on colour', 'A spread over colour *templates* (build a palette of eight in one drag) would need the desk to resolve intents without heads. On the ideas list, not in this plan.'],
    ['Pads stay pads', 'Pressing a template or Look lives on the programmer and the busk view. The library sheets edit the library; they do not press it.'],
  ]))}`
  return { ...docBoard('Main.dc.html', 1240, 1960, html), title: 'Main — today, the rules, the calls' }
}
boards.Main = mainBoard()

// ---- KIT -----------------------------------------------------------------------------------------
function kitBoard() {
  // a) row B anatomy, drawn at 1116
  const rowBDemo = `<div style="width:1116px; background:${C.mainBg}; border:1px solid ${T.border}; border-radius:8px; overflow:hidden;">${libRowB({ chips: partition([['All', 13], ['Intensity', 3], ['Colour', 4], ['Position', 2], ['Beam', 4]], 'Colour'), create: createBtn('New template') })}</div>`
  // b) the name column: rename vs open
  const nc = { key: 'name', label: 'Template', w: 260 }
  const nameDemo = `<div style="width:560px; background:${T.bg}; border:1px solid ${T.border}; border-radius:8px; overflow:hidden; position:relative; height:190px;">
    ${sheetHeader([nc, { key: 'x', label: 'Value', w: 300 }])}
    ${sheetRow(libName(nc, 'Amber', { sel: true, pencil: true }) + cellWrap({ w: 300 }, swatch('#ff9d4a', '#FF9D4A · extract')), { sel: true })}
    ${sheetRow(libName(nc, 'Deep Blue') + cellWrap({ w: 300 }, swatch('#1030ff', '#1030FF · rgb only')))}
    ${popover({ x: 12, y: 104, w: 288, inner: `${labelLine('1 template', 'Name')}${unitField('Amber Warm', '', { w: 256 })}` })}
  </div>`
  // c) the batch delete dialog
  const dlg = `<div style="width:460px; border-radius:10px; border:1px solid ${T.border}; background:${T.card}; box-shadow:0 20px 40px rgba(0,0,0,0.6); padding:20px; display:flex; flex-direction:column; gap:12px;">
    <span style="font-size:16px; font-weight:600;">Delete 3 looks?</span>
    <span style="font-size:12.5px; color:${T.mfg}; line-height:1.5;">Blackout Front was deleted. Two are in use — deleting them anyway removes what uses them:</span>
    <div style="display:flex; flex-direction:column; border:1px solid ${T.border}; border-radius:8px;">
      ${[['Ballyhoo', 'on 3 busk pages'], ['Band Spots', '2 cue layers — Q4, Q9 · removed with it']].map(([n, u], i) => `<div style="display:flex; align-items:center; gap:8px; padding:8px 10px; ${i ? `border-top:1px solid ${T.border};` : ''} font-size:12.5px;"><span style="font-weight:600;">${n}</span><span style="color:${T.mfg};">${u}</span></div>`).join('')}
    </div>
    <div style="display:flex; justify-content:flex-end; gap:8px;">${btn({ label: 'Keep them', variant: 'outline', h: 36 })}${btn({ label: 'Delete anyway', variant: 'destructive', h: 36 })}</div>
  </div>`
  // d) the read-only scope bar
  const roBar = `<div style="width:1116px; border:1px solid ${T.border}; border-radius:8px; overflow:hidden; background:${C.mainBg};">${selectionBar({ counts: ['2 templates'], family: null, hints: false, strip: `<span style="display:inline-flex; align-items:center; gap:6px; font-size:12px; color:${T.mfg};">${ico('lock', 12)}Rehearsal Room’s library — copy it here to edit</span>`, verbs: `${verb('pencil', 'Set', { disabled: true })}${verb('eraser', 'Clear', { disabled: true })}${divider()}${verb('arrowR', 'Copy to Experiment')}${btn({ label: 'Deselect', variant: 'ghost' })}` })}</div>`
  const where = [
    [cell('`sheet/SheetTable.tsx`', { head: true }), cell('`firstColumn.onOpen(row)` draws the pencil the patch list hand-rolls today (`PatchSheet.tsx:706`) and is what ⏎ on one row calls; the patch list moves onto it.')],
    [cell('`sheet/useSheetKeyboard.ts`', { head: true }), cell('⏎ with one selected row and no cells → `onOpenRow`. Refused (no-op) where a sheet passes none.')],
    [cell('`sheet/LibraryRow.tsx` (new)', { head: true }), cell('Row B for a library: filter · `PartitionChips` · spacer · create. `PartitionChips` is `LookFamilyFilterBar` generalised (counts, a sticky key, a `?param=`); `/templates` keeps `?family=`, Scripts takes `?type=`, the FX Library `?category=`.')],
    [cell('`sheet/groupRows.ts` (new)', { head: true }), cell('Interleaves `divider` rows by partition under All, in the partition’s declared order. Pure; the divider arm already exists in `SheetTable`.')],
    [cell('`sheet/cells/NumberCell.tsx` (new)', { head: true }), cell('`TextCell`’s shape with `EditorField` inside: a unit, a range, a step. Speed-master BPM and Start (20–300), template Fade (seconds).')],
    [cell('`sheet/ReadOutButton.tsx`', { head: true }), cell('Lifted from `CueSheet`’s private `ReadOut`: a read-out whose display is a press — TAP; later a usage count that opens what uses it.')],
    [cell('`sheet/useBatchDelete.ts` + `BatchDeleteDialog.tsx` (new)', { head: true }), cell('`{ remove(id, force) → ok | inUse(summary) | refused(reason) }` per entity; the dialog lists the in-use ones once. Replaces three hand-written delete + in-use dialog pairs (Looks, Templates, the master sheet).')],
    [cell('`sheet/libraryScope.ts` (new)', { head: true }), cell('`libraryPermission(isCurrentProject)` → `permission` + copy, as `LOCKED_REASON` is for the cue sheet. Speed Masters is exempt: its routes are `withProject`, editable from any project today.')],
    [cell('`sheet/sheetModel.ts`', { head: true }), cell('`nameColumn({ rename, noun })` — the rename column every library uses, refusing a batch (`write` answers false for more than one row, “Names are one at a time”).')],
  ]
  const html = `${title2('Kit — what the sheet gains so five libraries can mount it', 'Everything else is already there: `SheetColumn` read-outs (no `cell` → never in the marquee), `value: undefined` for a row with nothing to set, `divider` rows, `firstColumnCellProps`, `CellSelectionActions`, `SelectionBar`, `SheetPage`. These are the additions, each one generic.')}
  ${sect('Row B — the library row', `${rowBDemo}${note('Filter · partition chips (with counts) · spacer · the create verb. The partition is a view, not a route: one route per library, the chip in a `?param=` and remembered, as `/templates` already does with `?family=`. Below 600px of row the chips fold into a select.')}`)}
  <div style="display:flex; gap:32px; align-items:flex-start;">
    <div style="display:flex; flex-direction:column; gap:12px; flex:0 0 auto;">${section('The name column — rename and open')}${nameDemo}${note('A double click on the name opens the rename (`TextCell`); the pencil opens the record’s editor, and so does ⏎ with one row selected. A single click selects the row, as on every sheet.')}</div>
    <div style="display:flex; flex-direction:column; gap:12px; flex:0 0 auto;">${section('One delete for a batch')}${dlg}${note('Plain deletes first; the refusals are gathered and asked about once. *Keep them* leaves the in-use ones selected.')}</div>
  </div>
  ${sect('Another project’s library — the read-only scope', `${roBar}${note('The cue lock’s shape: marquee and selection still work, every value verb is disabled with the reason, and the one live verb is the one that makes it yours. Looks, Templates (the new copy route, call 8) and Scripts have Copy to…; the FX Library has no copy route and shows the scope with nothing live.')}`)}
  ${sect('Where the code goes', tbl([['File', 300], 'What'], where.map((r) => r.join(''))))}`
  return { ...docBoard('Kit.dc.html', 1240, 1720, html), title: 'Kit — the additions' }
}
boards.Kit = kitBoard()

// ---- MODEL ---------------------------------------------------------------------------------------
function modelBoard() {
  const r = (...c) => c.map((t, i) => cell(t, { head: i === 0 })).join('')
  const colTbl = (rows) => tbl([['Column', 120], ['Cell', 150], ['Kind', 80], 'Writes', ['Clear', 150], ['Spread', 110]], rows)
  const sm = colTbl([
    r('Master', '`TextCell` · rename', '—', '`PUT {name}`', 'refused', '—'),
    r('BPM', '`NumberCell` 20–300', '`bpm`', '`speedMasters.setBpm` per master (WS, live) · followers skipped', 'refused', '—'),
    r('Tap', '`ReadOutButton`', '—', '`speedMasters.tap` — one master, a read-out', '—', '—'),
    r('Start', '`NumberCell` 20–300', '`start`', '`PUT {bpm}` · followers skipped', 'refused', '—'),
    r('Follows', '`OptionCell` Manual · M1…', '`leader`', '`PUT {followTargetUuid, followNum, followDen}` — a new link starts ½, Manual sends all three null · M1 skipped · a cycle refused before the PUT', 'Manual', '—'),
    r('Ratio', '`OptionCell` 2× 1× ½ ⅓ ¼', '`ratio`', '`PUT {followNum, followDen}` — both halves, no target, no bpm · manual masters skipped', 'refused', '—'),
    r('Usage', '`OptionCell` None · Dimmer · Colour · Position', '`usage`', '`PUT {usage}` · refused over more than one row unless None (unique per project)', 'None', '—'),
    r('Used by', 'read-out', '—', '`referenceCount`', '—', '—'),
    r('Notes', '`TextCell`', '`notes`', '`PUT {notes}`', 'empty', '—'),
  ])
  const tp = colTbl([
    r('Template', '`TextCell` · rename', '—', '`PUT {name}`', 'refused', '—'),
    r('Holds', 'read-out', '—', 'fixed at creation', '—', '—'),
    r('Value', 'the family’s editor, intent mode (call 4)', '`value:<family>`', '`PUT {rows}` — generic value templates only; per-fixture and effect read out', 'refused', '—'),
    r('Fade', '`NumberCell` seconds', '`fade`', '`PUT {fadeDurationMs, fadeDurationMsPresent}` · value templates', 'no fade', '`duration`'),
    r('Master', '`OptionCell` M1…', '`master`', '`PUT {effect}` with the master changed · effect templates', 'M1', '—'),
    r('Notes', '`TextCell`', '`notes`', '`PUT {notes, notesPresent}`', 'empty', '—'),
    r('Layers · Pages · Pressed', 'read-outs', '—', '`layerCount` · `buskPageCount` · `lastPressedAt` (live, via `templatePressed`)', '—', '—'),
  ])
  const lk = colTbl([
    r('Look', '`TextCell` · rename', '—', '`PUT {name}` — metadata only, never rows', 'refused', '—'),
    r('Families · Preview · Contents', 'read-outs', '—', 'derived server-side', '—', '—'),
    r('Notes', '`TextCell`', '`notes`', '`PUT {notes}`', 'empty', '—'),
    r('Cue layers · Busk pages', 'read-outs', '—', '`layerCount` · `buskPageCount`', '—', '—'),
  ])
  const fx = colTbl([
    r('Effect', '`TextCell` · rename, custom only', '—', '`PUT fx/definitions/{id} {name}` · built-ins and script-registered skipped', 'refused', '—'),
    r('Output · Mode · Timing · Params · Drives · Source', 'read-outs', '—', 'the registry entry', '—', '—'),
  ])
  const scr = colTbl([
    r('Script', '`TextCell` · rename', '—', '`PUT {name, script, scriptType}` — the route replaces the whole row, so the rename sends all three', 'refused', '—'),
    r('Type · Lines · Used by', 'read-outs', '—', 'Used by: the FX registry for a definition, cue hooks for an application — client-derived (the server’s `usedByProperties` is always empty)', '—', '—'),
    r('Check', 'read-out', '—', 'the last Compile in this tab (call 6)', '—', '—'),
  ])
  const verbs = tbl([['Sheet', 130], 'Cell verbs', 'Row verbs (then Deselect)'], [
    r('Speed Masters', 'Set · Clear', 'Delete (M1 skipped)'),
    r('Looks', 'Set · Clear', 'Include (one) · Pick up (one) · Duplicate · Copy to… · Delete'),
    r('Templates', 'Set · Clear · Spread (Fade)', 'Pick up (one) · Duplicate · Copy to… (call 8) · Delete'),
    r('FX Library', 'Set · Clear (names)', 'Fork (call 7) · Delete (custom only)'),
    r('Scripts', 'Set · Clear (names)', 'Compile · Run (one) · Copy to… · Delete'),
  ].map((x) => x))
  const sessions = [
    ['0 · lighting7: the template copy route', '`POST /projects/{id}/templates/{templateId}/copy {targetProjectId, newName?}` on `copyLook`’s model (name clash → 409, a `(Copy n)` name client-side as Looks does), and the script copy keeping `scriptType`. Desk restart.'],
    ['1 · The kit’s library half, on Speed Masters', 'Row B + `PartitionChips`, `groupRows`, `NumberCell`, `ReadOutButton`, `firstColumn.onOpen` + ⏎-opens-row (the patch list moves onto it), `nameColumn`, `useBatchDelete` + the dialog, `libraryScope`. Speed Masters is the proving surface: the most columns, a live value, the follow rules, no scope. Add `followerNames` to `SpeedMasterInUseResponse`.'],
    ['2 · Looks and Templates', 'Both sheets on the kit — metadata, Fade, Master, family chips and dividers, Include / Pick up / Duplicate / Copy to… / Delete, the batch delete over both in-use shapes, Duplicate and Copy to… over both copy routes. `LookListRow`, `TemplateListRow` and both delete + in-use dialog pairs are deleted. Value read-only this session.'],
    ['3 · Template values in the cell', 'Call 4. The editor kit’s colour, level, position and beam editors in intent mode — no targets, no Pick, a `templateIntent.ts` round trip — writing `rows` on a generic value template. Skippable.'],
    ['4 · Scripts and the FX Library', 'Type and category chips + dividers; the Check column and batch Compile; Fork; built-in and script-registered rows read-only. `FxLibrary.tsx` shrinks from 930 lines to its sheets. Fixes on the way: a script-registered effect opens its *script*, not `fx/definitions/<scriptId>` (today’s latent bug).'],
  ]
  const backend = [
    ['Needed', 'The template copy route (call 8) — session 0. Fixed alongside it: `POST …/scripts/{id}/copy` drops `scriptType`, so a copy lands as GENERAL.'],
    ['Follow-ups, not in the plan', 'A server-side “used by” for scripts (`usedByProperties` is always empty); a bulk route for any of the five.'],
  ]
  const html = `${title2('Model — every column, every verb, the sessions', 'What each sheet’s `SheetColumn`s are, what a commit sends, and what Clear and Spread mean there. A dash is “not offered”; *skipped* means the row has `value: undefined` and the editor’s read-out names it.')}
  ${sect('Speed Masters', sm)}
  ${sect('Templates', tp)}
  <div style="display:grid; grid-template-columns:repeat(2, minmax(0, 1fr)); gap:26px;">${sect('Looks', lk)}${sect('FX Library', fx)}</div>
  ${sect('Scripts', scr)}
  ${sect('Verbs on the bar', verbs)}
  ${sect('Sessions — the plan to write', grid2(sessions))}
  ${sect('lighting7', grid2(backend))}`
  return { ...docBoard('Model.dc.html', 1240, 2720, html), title: 'Model — columns, verbs, sessions' }
}
boards.Model = modelBoard()

// ---- canvas.json (the Design type's v3 index) ------------------------------------------------------
const GX = 80, GY = 320
const order = ['Main', 'Kit', 'Model', 'SpeedMasters', 'Looks', 'Templates', 'FxLibrary', 'Scripts']
const pos = {}
pos.Main = [0, 0]
pos.Kit = [1240 + GX, 0]
pos.Model = [2 * (1240 + GX), 0]
const yS = Math.max(boards.Main.h, boards.Kit.h, boards.Model.h) + GY
const colX = (i) => i * (F.w + GX)
pos.SpeedMasters = [colX(0), yS]
pos.Looks = [colX(1), yS]
pos.Templates = [colX(2), yS]
const yS2 = yS + F.h + GY
pos.FxLibrary = [colX(0), yS2]
pos.Scripts = [colX(1), yS2]
const bEntries = Object.fromEntries(order.map((k) => [`${k}.dc.html`, { x: pos[k][0], y: pos[k][1], w: boards[k].w, h: boards[k].h, title: boards[k].title }]))
const sticky = (id, k, text, w = F.w) => [id, { x: pos[k][0], y: pos[k][1] - 190, w, text, size: 's', maxH: 160 }]
const notes = Object.fromEntries([
  ['t-doc', { x: 0, y: -300, text: 'Library sheets — the proposal', kind: 'title1', maxW: 3800 }],
  ['t-surf', { x: 0, y: yS - 300 - 250 + 250 - 0 - 60, text: 'The five sheets, at the 1180×820 frame', kind: 'title1', maxW: 3600 }],
  sticky('n-sm', 'SpeedMasters', 'Speed Masters. ⏎ over the BPM of M3 and M5 opens one editor for both: 90 lands on both clocks now. M2 and M4 follow M1, so their BPM is a read-out (the ratio beside it) and Set skips them by name. Start is the stored boot tempo — a column of its own, so the two BPMs never share a cell. TAP stays in the row as a read-out button.'),
  sticky('n-lk', 'Looks', 'Looks. Three rows selected by dragging down the name column. Include and Pick up act on one Look, so they are disabled over three; Duplicate, Copy to… and Delete take the batch. Notes is the one editable value column — what a Look holds is recorded, and changed by Include.'),
  sticky('n-tp', 'Templates', 'Templates. The family filter bar becomes row B’s chips; under All the sheet is grouped by family dividers. A marquee down Fade over Half, Amber and Deep Blue and ⏎: one editor, three fades. Effect templates have no fade and an editable Master instead. Value is editable on generic value templates (call 4).'),
  sticky('n-fx', 'FxLibrary', 'FX Library. Categories are dividers rather than collapsible headers; the chips narrow. Built-ins carry a lock and are read-only rows — Fork makes a custom copy (call 7). Warm Flicker is registered by a script, so it opens the script, not a definition sheet.'),
  sticky('n-sc', 'Scripts', 'Scripts. The type sidebar becomes chips; the sheet is grouped by type. Four scripts selected, Compile: each result lands in its row’s Check column as it comes back (call 6). The Kotlin editor stays in its sheet — the pencil or ⏎ opens it.'),
])
notes['t-surf'].y = yS - 300
const canvas = {
  v: 3,
  createdOnFiles: { v: 1, at: new Date().toISOString().replace(/\.\d+Z$/, 'Z') },
  title: 'Library sheets',
  launch: { view: 'canvas' },
  pages: [],
  boards: bEntries,
  order: order.map((k) => `${k}.dc.html`),
  notes,
  designSystems: [],
}
writeFileSync(OUT + 'canvas.json', JSON.stringify(canvas, null, 2) + '\n')
console.log(Object.entries(boards).map(([k, b]) => `${k} ${b.w}×${b.h}`).join('\n'))
