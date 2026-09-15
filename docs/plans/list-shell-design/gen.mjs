// Generates the list-shell design artboards. Lines up to the '// ====' banner are the tokens, icons and
// chrome builders copied from sheet-views-design/gen.mjs so the three records draw the same chrome.
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

// ---- the rig, as the screenshots show it -------------------------------------------------------------
const VCOLS = ['Dimmer', 'Colour', 'Position', 'Gobo', 'Zoom', 'Strobe', 'Focus', 'Iris', 'Prism']
const FCOLS = [{ key: 'name', label: 'Fixture', w: 260 }, ...VCOLS.map((l) => ({ key: l.toLowerCase(), label: l, w: 104 }))]
const FC = Object.fromEntries(FCOLS.map((c) => [c.key, c]))
// A fixture: name, the properties it has (a subset of VCOLS) with the live value, and the Local entries.
const RIG = [
  { name: 'Freedom Par Hex', has: ['dimmer', 'colour', 'strobe'], live: { dimmer: 0, colour: ['oklch(0.22 0.07 25)', '5,0,0'], strobe: 0 }, local: ['colour'] },
  { name: 'Freedom Par Hex', has: ['dimmer', 'colour', 'strobe'], live: { dimmer: 0, colour: ['oklch(0.14 0 0)', '0,0,0'], strobe: 0 } },
  { name: 'LED Lightbar 12 Pixel', has: ['dimmer', 'colour', 'strobe'], live: { dimmer: 0, colour: ['oklch(0.14 0 0)', '0,0,0'], strobe: 0 } },
  { name: 'Single-channel dimmer', has: ['dimmer'], live: { dimmer: 0 } },
  { name: 'Single-channel dimmer 2', has: ['dimmer'], live: { dimmer: 0 } },
  { name: 'LED Lightbar 12 Pixel 2', badge: 12, chev: 'closed', has: ['colour'], live: { colour: ['oklch(0.5 0.25 330)', 'Mixed'] }, local: ['colour'] },
  { name: 'Fusion 100 Spot MKII', has: ['dimmer', 'colour', 'position', 'gobo', 'strobe', 'focus', 'iris', 'prism'], live: { dimmer: 0, colour: ['oklch(0.985 0 0)', 'Open…'], position: '127,1…', gobo: 'Gobo 1', strobe: 0, focus: 0, iris: 0, prism: 'Op…' }, local: ['position', 'gobo'] },
]
const posCell = (t) => `<span style="width:16px; height:16px; margin-left:6px; border-radius:4px; border:1px solid ${T.border}; display:grid; place-items:center; flex:0 0 auto;"><span style="width:4px; height:4px; border-radius:999px; background:${C.blue};"></span></span><span style="margin-right:6px; font-size:12px; color:${T.mfg}; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-variant-numeric:tabular-nums;">${t}</span>`
const blank = (c) => `<span style="width:${c.w}px; flex:0 0 auto; height:100%;"></span>`
function valueCell(key, v) {
  if (key === 'colour') return swatch(v[0], v[1])
  if (key === 'position') return posCell(v)
  if (typeof v === 'number') return bar(v, `${v}%`)
  return cellText(v, { color: T.fg })
}
// One fixture row. `scope`: 'live' draws every value; 'local' draws the Local entries ringed and an
// em-dash where the fixture has the property but Local holds nothing.
function fixtureRow(f, { scope = 'live', indent = 0, sel = false } = {}) {
  const cells = VCOLS.map((l) => {
    const key = l.toLowerCase()
    const c = FC[key]
    if (!f.has.includes(key)) return blank(c)
    if (scope === 'local' && !(f.local ?? []).includes(key)) return cellWrap(c, dash)
    return cellWrap(c, valueCell(key, f.live[key]), { own: scope === 'local' ? 'you' : null })
  }).join('')
  return sheetRow(nameCell(FC.name, f.name, { sel, indent, chev: f.chev ?? null, badge: f.badge ?? null }) + cells, { sel })
}
const dividerRow = (t) => `<div style="height:36px; flex:0 0 auto; display:flex; align-items:center; padding:0 8px; border-bottom:1px solid ${T.border}; background:oklch(0.274 0.006 286.033 / 0.30); font-size:11px; font-weight:500; text-transform:uppercase; letter-spacing:0.05em; color:${T.mfg};">${t}</div>`

// ---- the toolbar controls the lists share ----------------------------------------------------------------
const litBtn = () => btn({ icon: ico('bulb', 14), label: 'Lit' })
const columnsBtn = () => btn({ icon: ico('columns', 14), label: 'Columns' })
const groupsBtn = (on = false) => btn({ icon: ico('layers', 14), label: 'Groups', variant: on ? 'primary' : 'outline' })
const listSwitcher = () => viewSwitcher('table', { b: ['tableProps', 'List'] })

// =====================================================================================================
// FIXTURES › LIST — Main. The lead complaint: a Card in a scrolling page, its own margins and ground.
// =====================================================================================================
function fixturesBoard() {
  const rows = RIG.map((f) => fixtureRow(f)).join('')
  const body = `${pageHeader(crumbs('Fixtures'), listSwitcher())}
    ${chromeRow(`${filterField('Filter fixtures…')}${spacer()}${litBtn()}${columnsBtn()}`)}
    ${selectionBar({ empty: true })}
    ${sheetBody(FCOLS, rows, { fade: true })}
    ${footer('<span style="font-variant-numeric:tabular-nums;">7 fixtures</span>')}`
  writeFileSync('Main.dc.html', frame('grid', body))
  return { w: F.w, h: F.h, title: 'Fixtures › List' }
}
boards.Main = fixturesBoard()

// =====================================================================================================
// GROUPS › LIST — the same shell, the same table, grouped rows.
// =====================================================================================================
function groupsBoard() {
  const [par1, ...rest] = RIG
  const group = sheetRow(nameCell(FC.name, 'test', { chev: 'open', badge: 1, weight: 500 }) + VCOLS.map((l) => { const key = l.toLowerCase(); return key === 'dimmer' || key === 'colour' || key === 'strobe' ? cellWrap(FC[key], valueCell(key, par1.live[key])) : blank(FC[key]) }).join(''))
  const rows = group + fixtureRow(par1, { indent: 1 }) + dividerRow('Ungrouped') + rest.map((f) => fixtureRow(f)).join('')
  const body = `${pageHeader(crumbs('Groups'), listSwitcher())}
    ${chromeRow(`${filterField('Filter groups…')}${spacer()}${litBtn()}${columnsBtn()}`)}
    ${selectionBar({ empty: true })}
    ${sheetBody(FCOLS, rows, { fade: true })}
    ${footer('<span style="font-variant-numeric:tabular-nums;">1 group · 7 fixtures</span>')}`
  writeFileSync('Groups.dc.html', frame('layers', body))
  return { w: F.w, h: F.h, title: 'Groups › List' }
}
boards.Groups = groupsBoard()

// =====================================================================================================
// PROGRAMMER — already on the system; drawn so the six agree. Two changes here: the grid's body
// takes the sheet ground (the name column is no longer darker than the cells), and the bar's line
// and the grid's line are one line.
// =====================================================================================================
function programmerBoard() {
  const RAIL = 40
  const sourceBox = `<span style="display:inline-flex; align-items:center; gap:8px; height:32px; padding:0 10px; border-radius:6px; border:1px solid ${T.border}; flex:0 0 auto;">${zlab('Busking')}<span style="font-size:12px; color:${T.mfg};">18 values</span></span>`
  const clearFade = `<span style="display:inline-flex; align-items:stretch; height:32px; border-radius:6px; border:1px solid ${T.border}; overflow:hidden; flex:0 0 auto;"><span style="display:inline-flex; align-items:center; gap:6px; padding:0 10px; font-size:12px; font-weight:500;">${ico('eraser', 14)}Clear</span><span style="display:inline-flex; align-items:center; gap:6px; width:86px; padding:0 10px; border-left:1px solid ${T.border}; background:oklch(0.274 0.006 286.033 / 0.40); font-size:12px; ${mono}">Snap${ico('chevD', 12, `margin-left:auto; color:${T.mfg}`)}</span></span>`
  const record = `<span style="display:inline-flex; align-items:stretch; height:32px; border-radius:6px; overflow:hidden; background:${T.primary}; color:${T.pfg}; flex:0 0 auto;"><span style="display:inline-flex; align-items:center; gap:6px; padding:0 12px; font-size:12px; font-weight:600;">${dotFill(12)}Record</span><span style="display:inline-flex; align-items:center; padding:0 6px; border-left:1px solid oklch(0.21 0.006 285.885 / 0.25);">${ico('chevD', 14)}</span></span>`
  const rowA = chromeRow(`${sourceBox}${spacer()}${divider()}${clearFade}${btn({ icon: ico('eyeOff', 14), label: 'Blind' })}${btn({ icon: ico('download', 14), label: 'Include…' })}${record}`, `background:oklch(0.21 0.006 285.885 / 0.50);`)
  const scope = `<span style="display:inline-flex; align-items:center; height:32px; padding:4px; border-radius:8px; background:${T.muted}; gap:2px; flex:0 0 auto;">${[['eye', 'Output', false], ['hand', 'Local', true]].map(([i, l, on]) => `<span style="display:inline-flex; align-items:center; gap:6px; height:24px; padding:0 8px; border-radius:6px; font-size:12px; font-weight:500; ${on ? `background:${T.bg}; color:${T.fg}; box-shadow:0 1px 2px rgba(0,0,0,0.3);` : `color:${T.mfg};`}">${ico(i, 14)}${l}</span>`).join('')}</span>`
  const rowB = chromeRow(`${scope}${filterField()}${litBtn()}${spacer()}${groupsBtn()}${columnsBtn()}`)
  const rows = RIG.map((f) => fixtureRow(f, { scope: 'local' })).join('')
  const grid = `<div style="flex:1; min-width:0; display:flex; flex-direction:column;">${rowA}${rowB}${selectionBar({ empty: true })}${sheetBody(FCOLS, rows, { fade: true })}${footer(`<span style="font-variant-numeric:tabular-nums;">7 fixtures</span>${ownershipLegend()}`, layerLegend())}</div>`
  const railBtn = (icon, n, color = T.mfg) => `<span style="display:flex; flex-direction:column; align-items:center; gap:2px; color:${color};">${ico(icon, 14)}${countBadge(n)}</span>`
  const rail = `<div style="width:${RAIL}px; flex:0 0 auto; border-left:1px solid ${T.border}; display:flex; flex-direction:column; align-items:center; gap:14px; padding:12px 0;">${ico('chevL', 14, `color:${T.mfg}`)}${railBtn('layers', 0)}${railBtn('wave', 0, T.violet)}<span style="flex:1;"></span>${ico('plus', 14, `color:${T.mfg}`)}</div>`
  const header = pageHeader(crumbs('Programmer'), `<nav style="display:inline-flex; align-items:center; gap:2px; border-radius:8px; border:1px solid ${T.border}; background:${T.card}; padding:2px;">${[['slidersV', 'Programmer', true], ['theater', 'Show', false], ['book', 'Prompt Book', false], ['wave', 'Busk', false]].map(([i, l, on]) => `<span style="display:inline-flex; align-items:center; gap:6px; border-radius:6px; padding:4px 10px; font-size:12px; font-weight:600; white-space:nowrap; ${on ? `background:${T.muted}; color:${T.fg};` : `color:${T.mfg};`}">${ico(i, 14)}<span>${l}</span></span>`).join('')}</nav>${btn({ icon: squareFill(14), label: 'Stop', variant: 'destructive' })}<span style="width:12px; height:12px; border-radius:999px; margin-left:4px; background:${T.green}; box-shadow:0 0 6px ${T.green};"></span>`, 'border-bottom-color:transparent;')
  const body = `${header}<div style="flex:1; min-height:0; display:flex;">${grid}${rail}</div>`
  writeFileSync('Programmer.dc.html', frame('slidersV', body))
  return { w: F.w, h: F.h, title: 'Programmer' }
}
boards.Programmer = programmerBoard()

// =====================================================================================================
// SHOW › TABLE — the cue sheet. One change: the stack header's gutter is 16 and becomes 12.
// =====================================================================================================
const SCOLS = [
  { key: 'name', label: 'Cue', w: 100 },
  { key: 'label', label: 'Name', w: 200, flex: true },
  { key: 'fade', label: 'Fade', w: 88 },
  { key: 'curve', label: 'Curve', w: 118 },
  { key: 'follow', label: 'Follow', w: 96 },
  { key: 'book', label: 'Book', w: 120 },
  { key: 'layers', label: 'Layers', w: 76 },
  { key: 'fx', label: 'FX', w: 56 },
  { key: 'notes', label: 'Notes', w: 200, flex: true },
]
const SC = Object.fromEntries(SCOLS.map((c) => [c.key, c]))
// [number, auto?, name, fade, curve, book, state]
const CUES = [
  ['QLX1', false, 'New Cue 2', '3.0s', 'Linear', 'top of p. 8', 'standby'],
  ['MARKER', false, 'New Separator'],
  ['QLX2', false, 'New Cue 3', '3.0s', 'Linear', 'middle of p. 8'],
  ['QLX3', true, 'New Cue 4', '3.0s', 'Linear', 'bottom of p. 8'],
  ['QLX4', true, 'Cue 5', 'SNAP', null, null],
]
function cueRow(c) {
  if (c[0] === 'MARKER') {
    return `<div style="height:36px; flex:0 0 auto; display:flex; align-items:center; gap:12px; padding:0 12px; border-bottom:1px solid ${T.border}; background:oklch(0.274 0.006 286.033 / 0.30);"><span style="flex:1; height:1px; background:${T.border};"></span><span style="display:inline-flex; align-items:center; height:${SYS.chip}px; padding:0 8px; border-radius:4px; border:1px solid ${T.border}; background:${T.card}; font-size:12px; font-weight:500; color:${T.mfg};">${c[2]}</span><span style="flex:1; height:1px; background:${T.border};"></span></div>`
  }
  const [num, auto, name, fade, curve, book, state] = c
  const standby = state === 'standby'
  const pip = standby
    ? `<span style="width:22px; height:22px; border-radius:999px; border:1px solid oklch(0.3 0.08 260); background:oklch(0.22 0.05 260); color:${C.blue}; display:grid; place-items:center; flex:0 0 auto;"><span style="width:6px; height:6px; border-radius:999px; background:currentColor;"></span></span>`
    : `<span style="width:22px; height:22px; border-radius:999px; border:1px solid ${T.border}; background:${T.muted}; display:grid; place-items:center; flex:0 0 auto;"><span style="width:8px; height:8px; border-radius:999px; background:oklch(0.705 0.015 286.067 / 0.3);"></span></span>`
  const nameCol = `<span style="position:relative; width:${SC.name.w}px; flex:0 0 auto; height:100%; display:flex; align-items:center; gap:10px; padding:0 8px; overflow:hidden; white-space:nowrap;">${pip}<span style="${mono} font-size:14px; font-weight:${auto ? 400 : 600}; color:${auto ? 'oklch(0.705 0.015 286.067 / 0.7)' : T.fg};">${num}</span></span>`
  const cells = [
    nameCol,
    cellWrap(SC.label, cellText(name, { color: standby ? 'oklch(0.8 0.1 250)' : T.fg, weight: standby ? 600 : 500, size: 14 })),
    cellWrap(SC.fade, cellText(fade, { mono: true, color: T.fg, weight: 500 })),
    cellWrap(SC.curve, curve ? cellText(curve, { color: T.fg }) : ''),
    cellWrap(SC.follow, dash),
    cellWrap(SC.book, book ? cellText(book, { size: 11 }) : ''),
    cellWrap(SC.layers, ''),
    cellWrap(SC.fx, ''),
    cellWrap(SC.notes, dash),
  ].join('')
  return sheetRow(cells, { extra: standby ? `background:oklch(0.623 0.214 259.815 / 0.06); box-shadow:inset 3px 0 0 ${C.blue};` : '' })
}
function showBar() {
  const tileLab = (t) => `<span style="font-size:9px; font-weight:700; text-transform:uppercase; letter-spacing:0.08em; color:${T.mfg};">${t}</span>`
  const tile = (inner, extra = '') => `<div style="display:flex; flex-direction:column; justify-content:flex-start; gap:1px; padding:6px 12px; border-radius:6px; border:1px solid ${T.border}; background:${T.card}; flex:0 0 auto; ${extra}">${inner}</div>`
  const mini = (t, on) => `<span style="display:inline-flex; align-items:center; justify-content:center; width:22px; height:18px; border-radius:3px; font-size:9px; font-weight:700; ${on ? `background:${T.primary}; color:${T.pfg};` : `color:${T.mfg};`}">${t}</span>`
  const masters = `<div style="display:flex; align-items:stretch; border-radius:6px; border:1px solid ${T.border}; background:${T.card}; flex:0 0 auto;"><div style="display:grid; grid-template-columns:repeat(2, minmax(0, 1fr)); gap:2px; padding:6px;">${mini('M1')}${mini('M3')}${mini('M2', true)}${mini('M4')}</div><div style="display:flex; flex-direction:column; gap:1px; padding:6px 12px; border-left:1px solid ${T.border};"><span style="display:flex; align-items:center; gap:6px;"><span style="width:6px; height:6px; border-radius:999px; background:${T.mfg};"></span>${tileLab('M2 · Master 2')}</span><span style="${mono} font-size:18px; font-weight:700; line-height:1; font-variant-numeric:tabular-nums;">61.2</span></div><div style="display:flex; align-items:center; padding:0 12px; border-left:1px solid ${T.border}; font-size:12px; font-weight:600;">½</div></div>`
  const prog = `<span style="display:inline-flex; align-items:center; gap:6px; padding:0 10px; border-radius:6px; border:1px solid ${C.blue}; background:oklch(0.623 0.214 259.815 / 0.10); color:${C.blue}; font-size:14px; font-weight:600; flex:0 0 auto;">${ico('slidersH', 16)}18</span>`
  return `<div style="display:flex; align-items:stretch; gap:8px; padding:8px 16px; border-bottom:1px solid ${T.border}; flex:0 0 auto;">
    ${tile(`${tileLab('Blackout')}<span style="${mono} font-size:18px; font-weight:700; line-height:1; letter-spacing:0.05em;">DBO</span>`)}
    ${masters}${prog}
    <div style="flex:1; min-width:0; display:flex; align-items:center; gap:14px; border-radius:6px; border:1px solid ${T.border}; background:${T.card}; padding:6px 12px; overflow:hidden;"><span style="font-size:14px; font-weight:500; flex:0 0 auto;">Test Stack</span><span style="color:${T.mfg};">·</span><span style="font-size:14px; color:${T.mfg}; white-space:nowrap;">No cue running</span></div>
    <div style="display:flex; align-items:stretch; gap:8px; flex:0 0 auto;">${btn({ label: '◀ BACK', extra: 'height:auto; font-weight:600; letter-spacing:0.05em; padding:0 20px; font-size:14px;' })}${btn({ label: 'GO', variant: 'primary', extra: 'height:auto; min-width:120px; font-size:16px; font-weight:700; letter-spacing:0.16em; box-shadow:0 6px 14px rgba(59,130,246,0.35);' })}</div>
  </div>`
}
function stackTabs() {
  const tab = (name, n, on, live) => `<span style="display:inline-flex; align-items:center; gap:8px; padding:0 20px; height:100%; border-right:1px solid ${T.border}; font-size:12px; font-weight:500; color:${on ? T.fg : T.mfg}; position:relative; ${on ? `background:oklch(0.274 0.006 286.033 / 0.2);` : ''}">${live ? `<span style="width:6px; height:6px; border-radius:999px; background:${T.green}; box-shadow:0 0 6px ${T.green};"></span>` : ''}${name}<span style="${mono} font-size:9.5px; border-radius:999px; border:1px solid ${T.border}; background:oklch(0.274 0.006 286.033 / 0.4); padding:0 6px; color:oklch(0.705 0.015 286.067 / 0.8);">${n}</span>${on ? `<span style="position:absolute; left:0; right:0; bottom:0; height:2px; background:${T.primary};"></span>` : ''}</span>`
  return `<div style="height:48px; flex:0 0 auto; display:flex; align-items:stretch; border-bottom:1px solid ${T.border};">${tab('Test Stack', 4, true, true)}${tab('Unsorted', 1, false, false)}</div>`
}
function showBoard() {
  const header = pageHeader(crumbs('Show'), `${btn({ icon: ico('lock', 14), label: 'Locked' })}<nav style="display:inline-flex; align-items:center; gap:2px; border-radius:8px; border:1px solid ${T.border}; background:${T.card}; padding:2px;">${[['slidersV', 'Programmer', false], ['theater', 'Show', true], ['book', 'Prompt Book', false], ['wave', 'Busk', false]].map(([i, l, on]) => `<span style="display:inline-flex; align-items:center; gap:6px; border-radius:6px; padding:4px 10px; font-size:12px; font-weight:600; white-space:nowrap; ${on ? `background:${T.muted}; color:${T.fg};` : `color:${T.mfg};`}">${ico(i, 14)}<span>${l}</span></span>`).join('')}</nav>${btn({ icon: squareFill(14), label: 'Stop', variant: 'destructive' })}<span style="width:12px; height:12px; border-radius:999px; margin-left:4px; background:${T.green}; box-shadow:0 0 6px ${T.green};"></span>`, 'border-bottom-color:transparent;')
  // StackDetail's header on the 12px gutter (it is px-4 today).
  const stackHeader = pageHeader(`<div style="display:flex; align-items:center; gap:12px;">${btn({ icon: ico('arrowL', 14), label: 'Stacks', extra: 'font-weight:700; letter-spacing:0.05em;' })}<span style="font-size:14px; font-weight:600;">Test Stack</span><span style="font-size:12px; color:${T.mfg};">4 cues</span></div>`, viewSwitcher('table'))
  const rows = CUES.map(cueRow).join('')
  const body = `${header}${showBar()}${stackTabs()}${stackHeader}${selectionBar({ empty: true })}${sheetBody(SCOLS, rows)}${footer(`<span>4 cues · 1 marker</span>${legendSwatch(T.green, 'Live', { fill: 'oklch(0.723 0.219 149.579 / 0.2)' })}${legendSwatch(C.blue, 'Next', { fill: 'oklch(0.623 0.214 259.815 / 0.2)' })}`)}`
  writeFileSync('Show.dc.html', frame('theater', body))
  return { w: F.w, h: F.h, title: 'Show › Table' }
}
boards.Show = showBoard()

// =====================================================================================================
// CHANNELS › TABLE — the DMX sheet. Already on the shell; drawn so the six agree.
// =====================================================================================================
const U0 = [
  ['Freedom Par Hex', 1, ['Dim', 'rgb', 'rgb', 'rgb', 'amber', 'white', 'uv', 'strobe', '', 'mode', 'program', 'dim curve']],
  ['Freedom Par Hex', 13, ['Dim', 'rgb', 'rgb', 'rgb', 'amber', 'white', 'uv', 'strobe', '', 'mode', 'program', 'dim curve']],
  ['LED Lightbar 12 Pixel', 25, ['Dim', 'Strobe', 'Random', 'Colour', 'Pixel', 'Colour 2', 'Speed', 'Sound', 'rgb', 'rgb', 'rgb', 'White']],
  ['Single-channel dimmer', 37, ['Dim']],
  ['Single-channel dimmer 2', 38, ['Dim']],
  ['LED Lightbar 12 Pixel 2', 39, Array.from({ length: 48 }, (_, i) => `Head ${Math.floor(i / 4) + 1} ${['R', 'G', 'B', 'W'][i % 4]}`)],
  ['Fusion 100 Spot MKII', 87, ['Dim', 'Pan', 'Tilt', 'Tilt fine', 'Pan/Tilt speed', 'Dimmer', 'Strobe', 'Colour', 'Gobo', 'Gobo rot', 'Focus', 'Prism', 'LED macro', 'Pan/Tilt macro', 'Motor']],
]
const VALS0 = { 2: 5, 39: 255, 43: 232, 45: 23, 47: 209, 49: 46, 51: 185, 53: 70, 55: 162, 57: 93, 59: 139, 61: 116, 63: 116, 65: 139, 67: 93, 69: 162, 71: 70, 73: 185, 75: 46, 77: 209, 79: 23, 81: 232, 85: 255, 87: 127, 89: 120, 95: 9 }
const OWN0 = new Set([2, 3, 4, 5, 6, 7, 39, 40, 41, 43, 44, 45, 47, 48, 49, 51, 52, 53, 55, 56, 57, 59, 60, 61, 63, 64, 65, 67, 68, 69, 71, 72, 73, 75, 76, 77, 79, 80, 81, 83, 84, 85, 87, 89, 95])
function chanInfo0(n) {
  for (let i = 0; i < U0.length; i++) { const [name, start, attrs] = U0[i]; if (n >= start && n < start + attrs.length) return { name, attr: attrs[n - start], first: n === start, idx: i } }
  return null
}
function dmxCell(n, w) {
  const info = chanInfo0(n)
  const v = VALS0[n] ?? 0
  const owned = OWN0.has(n)
  const ring = owned ? (v ? `box-shadow:inset 0 0 0 1px ${C.ringYou}; background:oklch(0.623 0.214 259.815 / 0.10);` : `box-shadow:inset 0 0 0 1px oklch(0.623 0.214 259.815 / 0.40);`) : ''
  const tint = info && info.idx % 2 === 0 ? `background:oklch(0.21 0.006 285.885 / 0.55);` : ''
  return `<span style="position:relative; width:${w}px; flex:0 0 auto; height:100%; padding:2px;"><span style="display:flex; height:100%; width:100%; border-radius:4px; ${ring}"><span style="display:flex; flex-direction:column; justify-content:center; gap:2px; width:100%; height:100%; padding:0 6px; ${tint} border-radius:4px; overflow:hidden;">
    <span style="display:flex; align-items:center; gap:4px; font-size:9.5px; line-height:1; color:${T.mfg}; white-space:nowrap; overflow:hidden;"><span style="${mono} font-variant-numeric:tabular-nums; flex:0 0 auto; ${info?.first ? `color:${T.fg}; font-weight:600;` : ''}">${String(n).padStart(3, '0')}</span><span style="overflow:hidden; text-overflow:ellipsis; ${info?.first ? `color:${T.fg};` : ''}">${info ? (info.first ? info.name : info.attr) : ''}</span></span>
    <span style="font-size:14px; line-height:1; ${mono} font-variant-numeric:tabular-nums; font-weight:${v ? 600 : 400}; color:${info ? (v ? T.fg : T.mfg) : 'oklch(0.705 0.015 286.067 / 0.35)'};">${v}</span>
  </span></span></span>`
}
function channelsBoard() {
  const rowHeadW = 48
  const cellW = Math.floor((MAINW - rowHeadW) / 16)
  const cols = [{ key: 'name', label: '', w: rowHeadW }, ...Array.from({ length: 16 }, (_, i) => ({ key: `c${i}`, label: `+${i}`, w: cellW }))]
  const rowsN = Math.floor((F.h - APP_HEADER - SYS.header - SYS.row - SHEET_HEADER - FOOTER) / DMX_ROW_H) + 1
  const rows = Array.from({ length: rowsN }, (_, r) => {
    const base = r * 16
    return `<div style="height:${DMX_ROW_H}px; flex:0 0 auto; display:flex; align-items:center; border-bottom:1px solid ${T.border};"><span style="width:${rowHeadW}px; flex:0 0 auto; padding:0 8px; font-size:11px; ${mono} color:${T.mfg}; font-variant-numeric:tabular-nums;">${String(base + 1).padStart(3, '0')}</span>${Array.from({ length: 16 }, (_, i) => dmxCell(base + i + 1, cellW)).join('')}</div>`
  }).join('')
  const body = `${pageHeader(crumbs('Channels'), viewSwitcher('table'))}${selectionBar({ empty: true })}${sheetBody(cols, rows)}${footer(`<span>Universe 0 · 101 of 512 patched · 0 parked</span>${ownershipLegend()}`)}`
  writeFileSync('Channels.dc.html', frame('slidersH', body))
  return { w: F.w, h: F.h, title: 'Channels › Table' }
}
boards.Channels = channelsBoard()

// =====================================================================================================
// PATCH LIST — its own route again (`/projects/:id/patches`, called 2026-09-15), so it takes the
// full shell: a 48px header row with breadcrumbs, the chips row as a 40px chrome row, row B, the bar,
// the sheet, the footer. It was the Patch List tab of Project Settings, under that page's heading.
// =====================================================================================================
function settingsHeader() {
  const tab = (t, on) => `<span style="display:inline-flex; align-items:center; height:28px; padding:0 8px; border-radius:6px; font-size:14px; font-weight:500; white-space:nowrap; ${on ? `background:${T.bg}; color:${T.fg}; box-shadow:0 1px 2px rgba(0,0,0,0.3);` : `color:${T.mfg};`}">${t}</span>`
  return `<div style="padding:16px; display:flex; flex-direction:column; gap:12px; border-bottom:1px solid ${T.border}; flex:0 0 auto;">
    ${crumbs('Settings')}
    <div style="display:flex; flex-direction:column; gap:2px;"><span style="font-size:18px; font-weight:600;">Project Settings</span><span style="font-size:14px; color:${T.mfg};">Configure this project's metadata, fixture patches, and control surfaces.</span></div>
    <div style="display:inline-flex; align-items:center; height:36px; padding:4px; border-radius:8px; background:${T.muted}; align-self:flex-start;">${tab('General')}${tab('Patch List', true)}${tab('Surfaces')}${tab('Stage')}${tab('Rigging')}${tab('Sync')}</div>
  </div>`
}
const configChip = (inner) => `<span style="display:inline-flex; align-items:center; gap:6px; height:${SYS.nested}px; padding:0 10px; border-radius:6px; border:1px solid ${T.border}; font-size:12px; white-space:nowrap; flex:0 0 auto;">${inner}</span>`
const PCOLS = [
  { key: 'name', label: 'Fixture', w: 240 },
  { key: 'addr', label: 'Address', w: 104 },
  { key: 'type', label: 'Type', w: 180, flex: true },
  { key: 'mode', label: 'Mode', w: 118, flex: true },
  { key: 'ch', label: 'Ch', w: 56, align: 'right' },
  { key: 'key', label: 'Key', w: 132 },
  { key: 'mount', label: 'Mount', w: 118 },
  { key: 'angle', label: 'Angle', w: 72 },
  { key: 'gel', label: 'Gel', w: 96 },
]
const PC = Object.fromEntries(PCOLS.map((c) => [c.key, c]))
// [name, addr, type, mode, ch, key, angle, gel]
const PATCH = [
  ['Freedom Par Hex', '0-001', 'Chauvet Freedom Par Hex', '', 12, 'freedom-par-…', '—', null],
  ['Freedom Par Hex', '0-013', 'Chauvet Freedom Par Hex', '', 12, 'freedom-par-…', '—', null],
  ['LED Lightbar 12 Pixel', '0-025', 'Showtec LED Lightbar 12 Pi…', '12-Channel (Full …', 12, 'led-lightbar…', '', null],
  ['Single-channel dimmer', '0-037', 'Generic Single-channel dim…', '', 1, 'single-chann…', '14°', ['R90', 'oklch(0.75 0.18 140)']],
  ['Single-channel dimmer 2', '0-038', 'Generic Single-channel dim…', '', 1, 'single-chann…', '—', '—'],
  ['LED Lightbar 12 Pixel 2', '0-039', 'Showtec LED Lightbar 12 Pi…', '48-Channel (Pix…', 48, 'led-lightbar…', '', null],
  ['Fusion 100 Spot MKII', '0-087', 'Equinox Fusion 100 Spot MKII', '15-Channel (Full …', 15, 'fusion-100-s…', '—', null],
]
function patchRow(r) {
  const [name, addr, type, modeName, ch, key, angle, gel] = r
  const cells = [
    nameCell(PC.name, name, { weight: 500 }),
    cellWrap(PC.addr, cellText(addr, { mono: true, color: T.fg, weight: 500 })),
    cellWrap(PC.type, cellText(type)),
    cellWrap(PC.mode, modeName ? cellText(modeName, { color: T.fg }) : ''),
    cellWrap(PC.ch, cellText(String(ch), { mono: true, color: T.fg, align: 'right' })),
    cellWrap(PC.key, cellText(key, { mono: true, color: T.fg })),
    cellWrap(PC.mount, cellText('Free', { color: 'oklch(0.705 0.015 286.067 / 0.6)' })),
    cellWrap(PC.angle, angle === '—' ? dash : angle ? cellText(angle, { mono: true, color: T.fg }) : ''),
    cellWrap(PC.gel, gel == null ? '' : gel === '—' ? dash : `<span style="display:inline-flex; align-items:center; gap:6px; padding:0 6px;"><span style="width:12px; height:12px; border-radius:3px; background:${gel[1]}; border:1px solid oklch(0.274 0.006 286.033 / 0.6);"></span><span style="${mono} font-size:12px;">${gel[0]}</span></span>`),
  ].join('')
  return sheetRow(cells)
}
function patchBoard() {
  const lab = (t) => `<span style="font-size:10px; font-weight:500; text-transform:uppercase; letter-spacing:0.05em; color:oklch(0.705 0.015 286.067 / 0.60); flex:0 0 auto;">${t}</span>`
  const uni = configChip(`<span style="${mono} font-weight:500;">U0</span><span style="color:oklch(0.705 0.015 286.067 / 0.5); font-style:italic;">no address</span><span style="position:relative; width:64px; height:6px; border-radius:999px; background:${T.muted}; overflow:hidden;"><span style="position:absolute; inset:0 auto 0 0; width:20%; background:${T.mfg}; opacity:0.7;"></span></span>${ico('pencil', 10, 'color:oklch(0.705 0.015 286.067 / 0.5)')}`)
  const grp = configChip(`<span style="font-weight:500;">test</span><span style="color:${T.mfg};">1</span>`)
  const chips = chromeRow(`${lab('Universes')}${uni}<span style="width:12px"></span>${lab('Groups')}${grp}`)
  const rowB = chromeRow(`${filterField()}${spacer()}${groupsBtn(true)}${columnsBtn()}${btn({ icon: ico('plus', 16), label: 'Patch', variant: 'primary' })}`)
  const rows = PATCH.map(patchRow).join('')
  const body = `${pageHeader(crumbs('Patch List'), '')}${chips}${rowB}${selectionBar({ empty: true })}${sheetBody(PCOLS, rows)}${footer('<span style="font-variant-numeric:tabular-nums;">7 fixtures patched · 1 group</span>', '1 universe · 101 of 512 addresses')}`
  writeFileSync('Patch.dc.html', frame('table', body))
  return { w: F.w, h: F.h, title: 'Patch List' }
}
boards.Patch = patchBoard()

// =====================================================================================================
// KIT — the shell, the three grounds, where the code goes, and the surface × shell matrix.
// =====================================================================================================
const cell = (t, { head = false, w = null, amber = false, mono: m = false, color = null } = {}) =>
  `<td style="padding:6px 10px; border-bottom:1px solid ${T.border}; vertical-align:top; font-size:11.5px; line-height:1.45; ${head ? `font-weight:600; white-space:nowrap; color:${T.fg};` : `color:${color ?? T.mfg};`} ${w ? `width:${w}px;` : ''} ${amber ? `color:${T.amber};` : ''} ${m ? mono + 'font-size:10.5px;' : ''}">${codify(t)}</td>`
const th = (t, w = null) => `<th style="text-align:left; padding:6px 10px; border-bottom:1px solid ${T.border}; font-size:10px; font-weight:700; text-transform:uppercase; letter-spacing:0.1em; color:${T.mfg}; white-space:nowrap; ${w ? `width:${w}px;` : ''}">${t}</th>`
const tbl = (heads, rows) => `<table style="border-collapse:collapse; width:100%;"><thead><tr>${heads.map((h) => (Array.isArray(h) ? th(h[0], h[1]) : th(h))).join('')}</tr></thead><tbody>${rows.map((r) => `<tr>${r}</tr>`).join('')}</tbody></table>`
const h3 = (t) => `<span style="font-size:12px; font-weight:700; color:${T.fg};">${t}</span>`
const block = (h, p) => `<div style="display:flex; flex-direction:column; gap:3px;">${h3(h)}${note(p)}</div>`

function kitBoard() {
  const w = 1240
  // 1. the shell, as a 620-wide fragment with a ruler beside it
  const kc = [{ key: 'name', label: 'Fixture', w: 180 }, { key: 'a', label: 'Dimmer', w: 110 }, { key: 'b', label: 'Colour', w: 110 }, { key: 'c', label: 'Position', w: 110 }]
  const k = Object.fromEntries(kc.map((c) => [c.key, c]))
  const rowsK = sheetRow(nameCell(k.name, 'Freedom Par Hex', { sel: true }) + cellWrap(k.a, bar(64), { sel: true, own: 'you' }) + cellWrap(k.b, swatch('oklch(0.75 0.16 60)', '255,157,74'), { own: 'you' }) + blank(k.c), { sel: true })
    + sheetRow(nameCell(k.name, 'Freedom Par Hex', { sel: true }) + cellWrap(k.a, bar(64), { sel: true, own: 'cue' }) + cellWrap(k.b, dash) + blank(k.c), { sel: true })
    + sheetRow(nameCell(k.name, 'LED Lightbar 12 Pixel 2', { chev: 'closed', badge: 12 }) + blank(k.a) + cellWrap(k.b, swatch('oklch(0.5 0.25 330)', 'Mixed')) + blank(k.c))
    + sheetRow(nameCell(k.name, 'Fusion 100 Spot MKII') + cellWrap(k.a, dash) + cellWrap(k.b, dash) + cellWrap(k.c, posCell('127,1…')))
  const fragW = 640
  const frag = `<div style="width:${fragW}px; flex:0 0 auto; display:flex; flex-direction:column; border:1px solid ${T.border}; background:${C.mainBg}; overflow:hidden;">
    ${pageHeader(crumbs('Fixtures'), listSwitcher())}
    ${chromeRow(`${filterField()}${spacer()}${litBtn()}${columnsBtn()}`)}
    ${selectionBar({ counts: ['2 fixtures', '2 cells'], family: 'Dimmer', verbs: `${verb('pencil', 'Set')}${verb('backspace', 'Clear')}${verb('fan', 'Fan')}${btn({ icon: ico('x', 14), label: 'Deselect', variant: 'ghost' })}`, hints: false })}
    <div style="display:flex; flex-direction:column; background:${T.bg};">${sheetHeader(kc)}${rowsK}</div>
    ${footer('<span>4 fixtures · 2 selected</span>')}
  </div>`
  const ruler = [
    ['48', 'SheetPage.Header', 'Breadcrumbs · the view switcher and the page\'s actions. `px-3`, a `border-b`. ShowHeader, `StackDetail`\'s header and the channels header are this row. One named exception: ShowHeader\'s line is `border-transparent` until the unlocked wash colours it — the Programmer and Show boards draw it that way, as today.'],
    ['40', 'SheetPage.Row', 'Any number, in order: the programmer\'s row A and row B, the patch list\'s chips row and row B, the plain lists\' one toolbar row. `h-10 px-3 gap-2 border-b`, 32px controls. Row A keeps its `bg-card/50` — the one row with a ground, by decision.'],
    ['40', 'SelectionBar', 'Already the kit\'s. Drawn on every list, reserved (“Nothing selected”) when empty — it is the 40px the verbs appear in.'],
    ['30 + 36 × n', 'SheetTable · FixturesTable', 'The sheet: `bg-background` on the header row, the sticky column and the body — one surface. **No `border-t`**: the bar above owns the line. 44px rows on the DMX sheet.'],
    ['22', 'SheetPage.Footer', 'The count on the left, the legend after it, the right slot for what the surface has (`from a layer`, the address total). `border-t`, 10.5px muted.'],
    ['fills', 'SheetPage.Empty', 'The body while loading or when the project is not found: the spinner or the sentence centred on the page ground, under the same header row. No `Card`, so nothing changes shape when the sheet arrives.'],
  ]
  const rulerHtml = `<div style="display:flex; flex-direction:column; gap:10px; flex:1; min-width:0;">${ruler.map(([px, name, p]) => `<div style="display:grid; grid-template-columns:90px 190px minmax(0, 1fr); gap:12px; align-items:start;"><span style="${mono} font-size:12px; font-weight:700; color:${T.fg}; text-align:right; font-variant-numeric:tabular-nums;">${px}</span><span style="${mono} font-size:11px; color:${T.fg};">${name}</span>${note(p)}</div>`).join('')}
    <div style="display:grid; grid-template-columns:90px minmax(0, 1fr); gap:12px; margin-top:6px; padding-top:10px; border-top:1px solid ${T.border};"><span style="${mono} font-size:12px; font-weight:700; text-align:right;">12</span>${note('The gutter, on every row of every list — the settings heading excepted, which is the settings page\'s `p-4` and not a list row. Today the stack header is 16 and the plain lists are 16 + 16 (a Card\'s margin and padding).')}</div>
    <div style="display:grid; grid-template-columns:90px minmax(0, 1fr); gap:12px;"><span style="${mono} font-size:12px; font-weight:700; text-align:right;">1</span>${note('One line between any two neighbours. A chrome row owns its `border-b`; the sheet owns no top line; the footer owns its `border-t`. Today the bar\'s `border-b` and the sheet\'s `border-t` stack to 2px on four of the six.')}</div>
  </div>`
  // 2. grounds
  const sw = (bg, name, cls, p) => `<div style="display:flex; gap:10px; align-items:flex-start;"><span style="width:44px; height:44px; flex:0 0 auto; border-radius:6px; border:1px solid ${T.border}; background:${bg};"></span><div style="display:flex; flex-direction:column; gap:2px; min-width:0;">${h3(name)}<span style="${mono} font-size:10.5px; color:${T.fg};">${cls}</span>${note(p)}</div></div>`
  const grounds = `<div style="display:grid; grid-template-columns:repeat(3, minmax(0, 1fr)); gap:14px 24px;">
    ${sw(C.mainBg, 'The page', '&lt;main&gt; bg-muted/40', 'What every chrome row sits on. A row has no ground of its own — the one exception today is row A\'s `bg-card/50`, and it is an open call.')}
    ${sw(T.bg, 'The sheet', 'bg-background', 'Header row, sticky first column and body: one colour, so a name column can never read darker than its cells. Recessed against the page, which is what Show, Channels and the patch list already do; the other three are transparent over their page or card.')}
    ${sw('oklch(0.274 0.006 286.033 / 0.30)', 'A divider row', 'bg-muted/30', 'Ungrouped, a separator. The one tinted row in a sheet.')}
    ${sw('oklch(0.985 0 0 / 0.06)', 'A selected row', 'bg-foreground/[0.06] + a 3px edge', 'Neutral by decision (space plan D4) — the ownership rings keep every hue.')}
    ${sw('oklch(0.985 0 0 / 0.05)', 'The bar with a selection', 'bg-foreground/5', 'The wash *is* the selection; the reserved bar wears none.')}
    ${sw(T.card, 'Not a ground', 'bg-card · rounded-xl · shadow-sm', 'The `Card` the plain lists sit in today — the one lighter box on the desk, with its own margins. It goes.')}
  </div>`
  // 3. where the code goes
  const code = [
    ['`components/sheet/sheetFrame.ts`', 'new', 'The class strings, stated once: `PAGE_HEADER_CLASS`, `CHROME_ROW_CLASS`, `SHEET_SCROLLER_CLASS`, `SHEET_HEADER_ROW_CLASS`, `SHEET_HEADER_CELL_CLASS`, `SHEET_STICKY_CELL_CLASS`, `SHEET_ROW_CLASS`, `SHEET_DIVIDER_CLASS`, `SHEET_FOOTER_CLASS`. `SheetTable` and `FixturesTable` import them; the inline copies of the 11px uppercase header cell go.'],
    ['`components/sheet/SheetPage.tsx`', 'new', '`SheetPage` (the full-height flex column), `SheetPage.Header`, `SheetPage.Row`, `SheetPage.Footer` — thin components over the constants, so a surface writes the slots and never the classes. `LegendSwatch` beside them, replacing the three swatch vocabularies (rounded-full border · rounded-sm ring · the legend\'s real class).'],
    ['`routes/FixturesList.tsx` · `GroupsList.tsx`', 'change', 'Drop the `Card` and `LIST_PAGE_CARD_CLASS`; mount `SheetPage` with the breadcrumb header, one row, the bar, the container with `fill`, the footer. The `?select=` and sticky-view wiring is untouched.'],
    ['`routes/Patches.tsx`', 'change', 'A routed page again at `/projects/:id/patches` — the header row with breadcrumbs above the chips row, row B, the bar, the sheet, the footer. The Patch List tab and `PatchListContent`\'s mount in `ProjectSettings.tsx` go; `PatchesRedirect` already answers the bare path, and `navigation.ts` gets its entry back.'],
    ['Loading and not found', 'change', 'Every list route\'s `Card m-4 p-4` spinner and “Project not found” render inside the same `SheetPage` — the header row with what is known, the body centred on the spinner or the sentence — so the page keeps its shape from loading to loaded. `SheetPage.Empty` for the body, one component for the two states.'],
    ['`fixtures-list/FixturesListContainer.tsx`', 'change', '`fill` is the only arm: the `space-y-3` wrapper, the wrapping default toolbar and `SelectionToolbar`\'s inline count go; row C is `SelectionBar` on all three mounts; a `renderFooter` on all three.'],
    ['`fixtures-list/FixturesTable.tsx`', 'change', '`bg-background` on the scroller (the fix `SheetTable` already carries); the non-`fill` `rounded-md border` + `calc(100vh − 14rem)` arm deleted with its last caller; header and row classes from `sheetFrame.ts`.'],
    ['`sheet/SheetTable.tsx`', 'change', 'Same import; the `fill` arm loses its `border-t`; the non-`fill` arm goes (nothing embeds a sheet in a scrolling page any more).'],
    ['`runner/StackDetail.tsx` · `routes/ChannelsTable.tsx` · `routes/Patches.tsx` · `programmer/ProgrammerGrid.tsx` · `routes/ProgrammerPage.tsx`', 'change', 'Each header and chrome row becomes `SheetPage.Header` / `SheetPage.Row`; the four hand-written 22px footers become `SheetPage.Footer`. `StackDetail`\'s `px-4` → the gutter; the patch chips row gains the row\'s height and line.'],
    ['Tests', 'change', 'Nothing new to pin — the import is the guarantee. `FixturesTable.test.tsx`\'s `null`-scope assertion and `ProgrammerPage.test.tsx`\'s `gridMounts` still hold, and both are the ones that matter across this change.'],
  ]
  const codeTbl = tbl([['Where', 300], ['', 60], 'What'], code.map(([f, k, p]) => `${cell(f, { head: true, mono: true })}${cell(k, { color: k === 'new' ? T.green : T.amber })}${cell(p)}`))
  // 4. surface × shell
  const matrix = [
    ['Fixtures › List', 'Breadcrumbs · Cards · List', 'filter · Lit · Columns', 'reserved / rows · cells', '260px name · 9 value columns', '7 fixtures'],
    ['Groups › List', 'Breadcrumbs · Cards · List', 'filter · Lit · Columns', 'same', 'group rows expand; Ungrouped divider', '1 group · 7 fixtures'],
    ['Programmer', 'ShowHeader (view switcher · Start / Stop)', 'row A: source · verbs — row B: scope · filter · Lit · Groups · Columns', 'same, plus the template strip', 'same table, scoped; the rail beside it', '7 fixtures · Owned by … · from a layer'],
    ['Show › Table', 'ShowHeader; then the stack header (Stacks · name · count · Cards · Table)', 'ShowBar · StackTabStrip keep their own shapes', 'same, plus the lock notice', 'Cue · Name · Fade · Curve · Follow · Book · Layers · FX · Notes', '4 cues · 1 marker · Live · Next'],
    ['Channels › Table', 'Breadcrumbs · Cards · Table (Unpark All while anything is parked)', 'none (universe tabs when there are several)', 'same', '16 × 44px cells', 'Universe 0 · 101 of 512 · Owned by …'],
    ['Patch List', 'Breadcrumbs', 'chips row · row B: filter · Groups · Columns · + Patch', 'same, plus Unpatch', 'Fixture · Address · Type · Mode · Ch · Key · Mount · Angle · Gel', '7 patched · 1 group · addresses used'],
    ['Loading · not found', 'Breadcrumbs (the project name once known)', 'none', 'none', 'the body: a centred spinner, or the sentence', 'none'],
  ]
  const matrixTbl = tbl([['Surface', 130], 'Header 48', 'Rows 40', 'Row C 40', 'Sheet', 'Footer 22'], matrix.map((r) => r.map((t, i) => cell(t, { head: i === 0 })).join('')))
  const html = `${HEAD}
<div class="app" style="width:${w}px; height:3220px; padding:28px 32px; display:flex; flex-direction:column; gap:26px;">
  ${title2('One shell for every list', 'Six list views, one anatomy. The shell is stated once in `components/sheet/` and every surface mounts it, so the gutter, the row heights, the grounds and the lines agree by construction rather than by review. The values are the programmer chrome system\'s (`programmer-chrome-design/`) and the sheet kit\'s (`sheet-views-design/`); nothing here is a new number.')}
  <div style="display:flex; flex-direction:column; gap:12px;">${section('The shell, top to bottom')}<div style="display:flex; gap:28px; align-items:flex-start;">${frag}${rulerHtml}</div></div>
  <div style="display:flex; flex-direction:column; gap:12px;">${section('Three grounds')}${grounds}</div>
  <div style="display:flex; flex-direction:column; gap:12px;">${section('Where the code goes')}${codeTbl}</div>
  <div style="display:flex; flex-direction:column; gap:12px;">${section('Surface × shell — what each puts in each slot')}${matrixTbl}</div>
</div>
${TAIL}`
  writeFileSync('Kit.dc.html', html)
  return { w, h: 3220, title: 'The kit · one shell, six lists' }
}
boards.Kit = kitBoard()

// =====================================================================================================
// SPEC — today, measured from the code; the rules; the open calls.
// =====================================================================================================
function specBoard() {
  const w = 1240
  const A = (t) => cell(t, { amber: true })
  const today = [
    [cell('Fixtures › List', { head: true }), A('`Card m-2 p-2 sm:m-4 sm:p-4` — rounded-xl, shadow, `bg-card`, in a scrolling page'), A('16 + 16'), A('the breadcrumb row inside the card, `mb-4`, no height'), A('`flex-wrap gap-2`; the filter `flex-1`; the count and Locate · Highlight inline'), A('none'), A('`rounded-md border`, `max-height: calc(100vh − 14rem)` — the page scrolls and the table scrolls'), A('body transparent over `bg-card`; header and name column `bg-background`'), A('none')],
    [cell('Groups › List', { head: true }), A('same'), A('same'), A('same'), A('same'), A('none'), A('same'), A('same'), A('none')],
    [cell('Programmer', { head: true }), cell('full-height column'), cell('12'), cell('ShowHeader 48, `px-3 py-2`'), cell('row A 40 (`bg-card/50`) · row B 40'), cell('40'), A('`fill`: `border-t` under the bar\'s `border-b` — 2px'), A('body transparent over `bg-muted/40`; header and name column `bg-background` (the darker name column in the screenshot)'), cell('22 · legend')],
    [cell('Show › Table', { head: true }), cell('full-height column'), A('12 — the stack header 16'), cell('ShowHeader 48; stack header 48 `px-4`'), cell('ShowBar · StackTabStrip 48'), cell('40'), A('2px'), cell('`bg-background`'), cell('22 · Live · Next')],
    [cell('Channels › Table', { head: true }), cell('full-height column'), cell('12'), cell('48, `px-3`'), cell('none'), cell('40'), A('2px'), cell('`bg-background`'), cell('22 · legend')],
    [cell('Settings › Patch List', { head: true }), A('a tab of the settings page: heading `p-4` + tabs, then the tab body'), A('16 heading · 12 rows'), A('none — the settings heading'), A('chips row `px-3 pt-3 pb-1`, no line, 26px chips · row B 40'), cell('40'), A('2px'), cell('`bg-background`'), cell('22')],
  ]
  const todayTbl = tbl([['Surface', 120], 'Shell', ['Gutter', 70], 'Header row', 'Toolbar rows', ['Row C', 50], 'Sheet frame', 'Sheet ground', ['Footer', 80]], today.map((r) => r.join('')))
  const strays = [
    ['Three legend swatches', 'The cue sheet\'s footer draws `size-2.5 rounded-full border`, the DMX sheet\'s `size-2.5 rounded-sm ring-1 ring-inset`, the programmer\'s `size-3 rounded-sm` through the real `ownershipCellClass`. One `LegendSwatch`.'],
    ['Two header cells', '`SheetTable` has `HEADER_CLASS`; `FixturesTable` carries the same eleven utilities inline, twice. One constant.'],
    ['Four footers', 'The programmer\'s `LegendFooter`, the cue sheet\'s, the DMX sheet\'s and the patch list\'s each write `flex h-[22px] shrink-0 items-center gap-3 border-t px-3 text-[10.5px] text-muted-foreground` by hand.'],
    ['Two lists with no bar and no footer', 'Fixtures and Groups report their selection in the toolbar and their count nowhere; the other four have row C and a footer.'],
  ]
  const rules = [
    ['A list is a full-height column', 'Header · rows · the bar · the sheet · the footer, filling `&lt;main&gt;`. No `Card`, no page scroll: the sheet is the only scroller. Fixtures and Groups become what Channels › Table already is — and so do the loading and not-found states, under the same header row.'],
    ['One gutter', '12px on every row of every list — the stack header and the patch chips row included.'],
    ['The chrome system\'s heights', 'Header 48; every other row 40 with 32px controls; the bar 40; the footer 22; the sheet header 30 and rows 36 (44 on the DMX sheet). Nothing new — `programmer-chrome-design/` and `sheet-views-design/` set every number.'],
    ['Three grounds', 'The page (`bg-muted/40`), the sheet (`bg-background` — header, sticky column, body), the divider row (`bg-muted/30`). A chrome row has no ground of its own.'],
    ['One line between neighbours', 'A row owns its `border-b`; the sheet owns no top line; the footer owns its `border-t`.'],
    ['Row C and the footer on every list', 'The bar reserved when nothing is selected; the footer carrying the count, then the legend, then the surface\'s own right-hand slot.'],
    ['One swatch', '`LegendSwatch`, ringed by the real ownership class, on every footer that draws a key.'],
    ['Stated once', '`components/sheet/sheetFrame.ts` holds the classes and `SheetPage.tsx` the shell; every surface imports. A surface that wants to differ says so at the import, in one file.'],
  ]
  const open = [
    ['Row A keeps its wash', 'The programmer\'s action row stays the one chrome row with a ground (`bg-card/50`) — the verbs\' own band. Rule 4 gains that one named exception.'],
    ['The plain lists gain the bar and the footer', 'Fixtures and Groups take row C and the 22px footer like every other list — 62px of chrome, accepted; the inline count in the toolbar goes.'],
    ['The patch list is its own route again', '`/projects/:id/patches`, with the 48px header row and breadcrumbs, leaving Project Settings. `routes/Patches.tsx` is routed again, `PatchesRedirect` already answers the bare path, and the tab goes.'],
    ['The chips row is a 40px chrome row', 'Universes and groups on a row of the shell with 28px chips and its own line, above row B — as drawn.'],
    ['Loading and not-found go on the shell too', 'The spinner and “Project not found” render inside `SheetPage` under the header row, so a list never changes shape between loading and loaded. `SheetPage.Empty` is the one body for both.'],
  ]
  const html = `${HEAD}
<div class="app" style="width:${w}px; height:1400px; padding:28px 32px; display:flex; flex-direction:column; gap:24px;">
  ${title2('List views — today, measured', 'Six list views, read from the code rather than the screen: `routes/FixturesList.tsx` and `GroupsList.tsx`, `ProgrammerPage.tsx` with `ProgrammerGrid`, `StackDetail` with `CueSheet`, `ChannelsTable.tsx` with `DmxSheet`, and `Patches.tsx` with `PatchSheet`. Amber is what differs from the others or from the chrome system.')}
  <div style="display:flex; flex-direction:column; gap:10px;">${section('Today')}${todayTbl}</div>
  <div style="display:grid; grid-template-columns:repeat(2, minmax(0, 1fr)); gap:12px 28px;">${strays.map(([h, p]) => block(h, p)).join('')}</div>
  <div style="display:flex; flex-direction:column; gap:10px;">${section('Proposed — one line each')}<div style="display:grid; grid-template-columns:repeat(2, minmax(0, 1fr)); gap:12px 28px;">${rules.map(([h, p]) => block(h, p)).join('')}</div></div>
  <div style="display:flex; flex-direction:column; gap:10px;">${section('Called — 2026-09-15')}<div style="display:grid; grid-template-columns:repeat(2, minmax(0, 1fr)); gap:12px 28px;">${open.map(([h, p]) => block(h, p)).join('')}</div></div>
</div>
${TAIL}`
  writeFileSync('Spec.dc.html', html)
  return { w, h: 1400, title: 'Today measured · the rules · the open calls' }
}
boards.Spec = specBoard()

// ---- canvas.json ----------------------------------------------------------------------------------
const GAPX = 100, GAPY = 200
const layout = [
  ['Spec', 0, 0],
  ['Kit', boards.Spec.w + GAPX, 0],
]
const y2 = Math.max(boards.Spec.h, boards.Kit.h) + GAPY
const col = (i) => i * (F.w + GAPX)
layout.push(['Main', col(0), y2], ['Groups', col(1), y2], ['Programmer', col(2), y2])
const y3 = y2 + F.h + GAPY
layout.push(['Show', col(0), y3], ['Channels', col(1), y3], ['Patch', col(2), y3])
const noteAbove = (id, i, y, text) => ({ id, x: col(i), y: y - 150, w: F.w, text })
const canvas = {
  artboards: layout.map(([file, x, y]) => ({ file: `${file}.dc.html`, title: boards[file].title, x, y, w: boards[file].w, h: boards[file].h })),
  annotations: [
    { id: 'brief', x: 0, y: -420, w: 820, text: 'List views on one shell.\n\nFixtures and Groups sit in a Card with their own margins and ground; the other four are full-height columns on the programmer\'s chrome system, and even those differ in a gutter, a doubled line and three swatch shapes. Every surface is redrawn below on one shell — header 48 · rows 40 · the selection bar · the sheet · footer 22, on a 12px gutter, three grounds — and the Kit says where that shell lives in code so the agreement is automatic. The five open calls were made on 2026-09-15 and are on the Spec board.\n\nAssumed: static mockups at the 1180×820 frame the screenshots were taken at, dark only, the app header and sidebar as they are. Spec has today\'s values measured from the code and the open calls.' },
    noteAbove('n-main', 0, y2, 'Fixtures › List — the biggest change. Today: a Card (m-2 p-2 sm:m-4 sm:p-4, rounded-xl, bg-card) holding the breadcrumb row, a wrapping toolbar, and a rounded table capped at calc(100vh − 14rem) — two scrollers, no selection bar, no footer. Here: the same full-height shell as Channels › Table.'),
    noteAbove('n-groups', 1, y2, 'Groups › List — identical to Fixtures, grouped rows. The group row expands over its members; Ungrouped is the one tinted divider.'),
    noteAbove('n-programmer', 2, y2, 'Programmer — already on the system. Two things move: the grid\'s body takes the sheet ground (today the name column is bg-background over a transparent body — visibly darker), and the bar\'s line and the grid\'s line become one.'),
    noteAbove('n-show', 0, y3, 'Show › Table — the stack header goes from px-4 to the 12px gutter. ShowBar keeps its own @[440px]:px-4 by decision (CLAUDE.md), so bar and rows still differ by 4px there. The doubled line under the bar goes.'),
    noteAbove('n-channels', 1, y3, 'Channels › Table — the reference shape; only the doubled line and the swatch change.'),
    noteAbove('n-patch', 2, y3, 'Patch List — its own route again (called 2026-09-15), so it takes the whole shell: the 48px header row with breadcrumbs, the chips as a 40px chrome row with its line, row B, the bar, the sheet, the footer. It leaves Project Settings.'),
  ],
  launch: { view: 'canvas' },
}
writeFileSync('canvas.json', JSON.stringify(canvas, null, 2) + '\n')
console.log(Object.entries(boards).map(([k, b]) => `${k} ${b.w}×${b.h}`).join('\n'))
