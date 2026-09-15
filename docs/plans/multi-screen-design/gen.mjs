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
// TWO SCREENS AND AN iPAD — the desk across several browser windows.
// Everything above this banner is the chrome vocabulary copied from list-shell-design/gen.mjs (tokens
// from index.css, button.tsx sizes, the row system, the sheet anatomy). Below: the busk view drawn
// from BuskPad / padFace.ts / TargetBand.tsx, and the new pieces this record proposes — the desk
// chip on the selection bar, the Screens sheet, the hand chip, the Follow toggle.
// =====================================================================================================
P.monitor = 'M2 3h20v14H2zM8 21h8M12 17v4'
P.tablet = 'M4 2h16v20H4zM12 18h.01'
P.maximize = 'M8 3H5a2 2 0 0 0-2 2v3M21 8V5a2 2 0 0 0-2-2h-3M3 16v3a2 2 0 0 0 2 2h3M16 21h3a2 2 0 0 0 2-2v-3'
P.minimize = 'M8 3v3a2 2 0 0 1-2 2H3M21 8h-3a2 2 0 0 1-2-2V3M3 16h3a2 2 0 0 1 2 2v3M16 21v-3a2 2 0 0 1 2-2h3'
P.link = 'M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71'
P.unlink = 'M9 17H7A5 5 0 0 1 7 7M15 7h2a5 5 0 0 1 4 8M8 12h4M2 2l20 20'
P.grab = 'M18 11.5V9a2 2 0 0 0-4 0v2M14 10V8a2 2 0 0 0-4 0v2M10 9.9V9a2 2 0 0 0-4 0v5M6 14a2 2 0 0 0-4 0 8 8 0 0 0 8 8h4a8 8 0 0 0 8-8v-2a2 2 0 0 0-4 0'
P.user = 'M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2M12 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z'
P.moon = 'M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z'
P.logout = 'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9'
P.command = 'M15 6v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3'
P.arrowUR = 'M7 17 17 7M7 7h10v10'
P.smartphone = 'M6 2h12v20H6zM12 18h.01'
P.dot = 'M12 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4Z'
P.pin = 'M12 17v5M9 10.8V6a3 3 0 0 1 6 0v4.8l2 2.2H7z'
P.copy = 'M8 8h12v12H8zM16 8V4H4v12h4'

const { w: FW, h: FH } = F
const BLUE = C.blue
const PRI10 = 'oklch(0.623 0.214 259.815 / 0.10)'
const PRI20 = 'oklch(0.623 0.214 259.815 / 0.20)'
const PRI40 = 'oklch(0.623 0.214 259.815 / 0.40)'
const PRI50 = 'oklch(0.623 0.214 259.815 / 0.50)'

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

// ---- the desk chip: the one new thing on the selection bar ----------------------------------------------
// Linked: this window follows the desk selection and publishes to it (today's programmer, in words).
// The trailing name is who last moved it — this window says nothing; another window names itself.
function deskChip({ from = null, local = false } = {}) {
  if (local) return `<span style="display:inline-flex; align-items:center; gap:6px; height:${SYS.chip}px; padding:0 8px; border-radius:999px; border:1px dashed ${T.border}; color:${T.mfg}; font-size:10px; font-weight:500; white-space:nowrap; flex:0 0 auto;">${ico('unlink', 11)}This window</span>`
  return `<span style="display:inline-flex; align-items:center; gap:6px; height:${SYS.chip}px; padding:0 8px; border-radius:999px; border:1px solid ${PRI40}; background:${PRI10}; color:${BLUE}; font-size:10px; font-weight:500; white-space:nowrap; flex:0 0 auto;">${ico('link', 11)}Desk${from ? `<span style="color:${T.mfg};">· from ${from}</span>` : ''}</span>`
}

// A template chip on the strip (TemplateStrip: h-7, rounded-md, border, gap-1.5, px-2, text-xs).
const tchip = (name, colour = null, { on = false, effect = false } = {}) =>
  `<span style="display:inline-flex; align-items:center; gap:6px; height:${SYS.nested}px; padding:0 8px; border-radius:6px; border:1px solid ${on ? T.primary : T.border}; background:${on ? PRI20 : T.card}; font-size:12px; font-weight:500; white-space:nowrap; flex:0 0 auto;">${colour ? `<span style="width:12px; height:12px; border-radius:3px; background:${colour}; flex:0 0 auto;"></span>` : effect ? ico('wave', 12, `color:${T.mfg}`) : ''}${name}</span>`
const strip = (chips) => `<div style="display:flex; align-items:center; gap:6px; min-width:0; overflow:hidden; flex:1 1 0;">${chips.join('')}${btn({ label: 'All · 23', h: SYS.nested, extra: 'color:' + T.mfg })}</div>`

// ---- the hand: a desk-owned held item, drawn on every window ---------------------------------------------
function handChip({ x, y, name, kind, detail, where }) {
  return `<div style="position:absolute; left:${x}px; top:${y}px; z-index:60; display:flex; align-items:center; gap:10px; height:44px; padding:0 6px 0 12px; border-radius:999px; border:1px solid ${T.primary}; background:${T.card}; box-shadow:0 12px 24px -6px rgba(0,0,0,0.6), 0 0 0 4px ${PRI20}; white-space:nowrap;">
    ${ico('grab', 16, `color:${BLUE}`)}
    <span style="display:flex; flex-direction:column; gap:1px; line-height:1.1;"><span style="font-size:12px; font-weight:600;">${name}<span style="font-size:10px; font-weight:500; color:${T.mfg}; margin-left:6px;">${kind}</span></span><span style="font-size:10px; color:${T.mfg};">${detail}</span></span>
    ${where ? `<span style="font-size:10px; color:${T.mfg}; padding-left:10px; border-left:1px solid ${T.border};">${where}</span>` : ''}
    <span style="width:32px; height:32px; display:grid; place-items:center; border-radius:999px; color:${T.mfg};">${ico('x', 14)}</span>
  </div>`
}

// ---- the busk view, drawn from BuskPad.tsx / padFace.ts / TargetBand.tsx -------------------------------------
const buskLabel = (t) => `<span style="font-size:9px; font-weight:700; text-transform:uppercase; letter-spacing:0.1em; color:${T.mfg}; white-space:nowrap;">${t}</span>`
// TargetBand: a 30px pad, gap-2, two rows grid-flow-col. Selected: border-primary bg-primary/20 ring.
function tpad(name, { icon = 'grid', on = false, n = null, half = false } = {}) {
  const ring = on ? `border-color:${T.primary}; background:${PRI20}; box-shadow:0 0 0 1px ${PRI50};` : half ? `border-color:${PRI40}; background:${PRI10};` : ''
  return `<span style="display:inline-flex; align-items:center; gap:8px; height:36px; min-width:132px; padding:0 12px; border-radius:8px; border:1px solid ${T.border}; background:${T.card}; font-size:12px; font-weight:500; white-space:nowrap; ${ring}">${ico(icon, 14, `color:${on ? BLUE : T.mfg}`)}<span style="flex:1; text-align:left;">${name}</span>${n != null ? countBadge(n) : ''}</span>`
}
function targetBand({ groups, fixtures, summary, dim = false }) {
  return `<div style="flex:0 0 auto; border-bottom:1px solid ${T.border}; padding:10px 16px 12px; ${dim ? 'opacity:0.5;' : ''}">
    <div style="display:flex; align-items:baseline; gap:10px; margin-bottom:8px;">${buskLabel('Targets')}<span style="min-width:0; flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:11px; color:${T.mfg};">${summary}</span>${btn({ label: 'Deselect', variant: 'ghost', h: 24, extra: 'font-size:12px; padding:0 8px;' })}</div>
    <div style="display:grid; grid-auto-flow:column; grid-template-rows:repeat(2, minmax(0, 1fr)); justify-content:start; gap:8px; overflow:hidden;">${[...groups, ...fixtures].join('')}</div>
  </div>`
}
// PAD_SHELL: min-h-[56px] rounded-lg border p-2; face centred; presence ladder from padPresenceClass.
function pad(name, { detail = '', swatch = null, effect = false, presence = 'none', cue = null, drop = false, ghost = false, w = null } = {}) {
  let ring = `border:1px solid ${T.border}; background:${T.card};`
  if (presence === 'some') ring = `border:1px solid ${PRI40}; background:${PRI10};`
  if (presence === 'all') ring = `border:1px solid ${T.primary}; background:${PRI20}; box-shadow:0 0 0 1px ${PRI50};`
  if (cue === 'live') ring = `border:1px solid oklch(0.723 0.219 149.579 / 0.7); background:oklch(0.723 0.219 149.579 / 0.08); box-shadow:0 0 0 1px oklch(0.723 0.219 149.579 / 0.35);`
  if (drop) ring = `border:2px dashed ${T.primary}; background:${PRI10};`
  const name_ = `<span style="display:inline-flex; align-items:center; gap:6px; font-size:12px; font-weight:600; color:${presence === 'none' ? T.fg : BLUE}; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; max-width:100%;">${swatch ? `<span style="width:12px; height:12px; border-radius:3px; background:${swatch}; flex:0 0 auto;"></span>` : ''}${effect ? ico('wave', 12, `color:${presence === 'none' ? T.mfg : BLUE}`) : ''}${name}</span>`
  const inner = cue
    ? `<span style="display:flex; flex-direction:column; align-items:flex-start; gap:2px;"><span style="display:flex; align-items:center; gap:6px; font-size:12px; font-weight:600;">${cue === 'live' ? `<span style="width:6px; height:6px; border-radius:999px; background:${T.green}; box-shadow:0 0 6px ${T.green};"></span>` : ''}<span style="${mono} font-size:10px; color:${T.mfg};">${detail}</span>${name}</span><span style="font-size:10px; color:${T.mfg};">Test Stack</span></span>`
    : `<span style="display:flex; flex-direction:column; align-items:center; justify-content:center; text-align:center; gap:2px; width:100%;">${name_}${detail ? `<span style="font-size:10px; color:${T.mfg}; white-space:nowrap;">${detail}</span>` : ''}</span>`
  return `<span style="position:relative; display:flex; align-items:center; justify-content:${cue ? 'flex-start' : 'center'}; min-height:56px; ${w ? `width:${w}px;` : 'min-width:110px;'} padding:8px; border-radius:8px; ${ring} ${ghost ? 'opacity:0.35;' : ''}">${inner}${presence !== 'none' && !cue ? `<span style="position:absolute; top:6px; right:6px; width:8px; height:8px; border-radius:999px; background:${presence === 'all' ? T.primary : PRI50};"></span>` : ''}</span>`
}
function bank(name, pads, { solo = false, column = false, drop = false, slot = null } = {}) {
  const items = pads.slice()
  if (slot != null) items.splice(slot, 0, `<span style="min-height:56px; min-width:110px; border-radius:8px; border:2px dashed ${T.primary}; background:${PRI10};"></span>`)
  return `<div style="display:flex; flex-direction:column; gap:8px; padding:10px; border-radius:10px; border:1px ${drop ? 'dashed' : 'solid'} ${drop ? T.primary : T.border}; background:${drop ? PRI10 : 'oklch(0.21 0.006 285.885 / 0.4)'};">
    <div style="display:flex; align-items:center; gap:8px;">${buskLabel(name)}${solo ? pill('solo', { fg: T.amber, bc: 'oklch(0.828 0.189 84.429 / 0.5)' }) : ''}</div>
    <div style="display:${column ? 'flex' : 'flex'}; flex-direction:${column ? 'column' : 'row'}; flex-wrap:wrap; gap:8px;">${items.join('')}</div>
  </div>`
}
const pageStrip = (pages, on) => `<div style="height:40px; flex:0 0 auto; display:flex; align-items:stretch; padding:0 12px; border-bottom:1px solid ${T.border}; gap:2px;">${pages.map((p) => `<span style="display:inline-flex; align-items:center; padding:0 12px; font-size:12px; font-weight:500; color:${p === on ? T.fg : T.mfg}; position:relative;">${p}${p === on ? `<span style="position:absolute; left:0; right:0; bottom:0; height:2px; background:${T.primary};"></span>` : ''}</span>`).join('')}<span style="flex:1;"></span><span style="display:inline-flex; align-items:center;">${btn({ icon: ico('pencil', 14), label: 'Edit layout', variant: 'ghost' })}</span></div>`
function speedRail() {
  const card = (name, bpm, { usage = null, follow = null, on = false } = {}) => `<div style="display:flex; flex-direction:column; gap:6px; padding:10px 12px; border-radius:8px; border:1px solid ${on ? T.primary : T.border}; background:${T.card};">
    <div style="display:flex; align-items:center; gap:8px;"><span style="width:6px; height:6px; border-radius:999px; background:${on ? T.primary : T.mfg};"></span><span style="font-size:11px; font-weight:600;">${name}</span>${usage ? pill(usage) : ''}<span style="margin-left:auto; color:${T.mfg};">${ico('slidersH', 12)}</span></div>
    <div style="display:flex; align-items:baseline; gap:8px;"><span style="${mono} font-size:26px; font-weight:700; line-height:1; font-variant-numeric:tabular-nums;">${bpm}</span><span style="font-size:10px; color:${T.mfg};">BPM</span><span style="margin-left:auto;">${follow ? `<span style="font-size:11px; font-weight:600; color:${T.mfg};">${follow}</span>` : btn({ label: 'TAP', h: 24, extra: 'font-size:11px; letter-spacing:0.08em; padding:0 10px;' })}</span></div>
  </div>`
  return `<div style="width:264px; flex:0 0 auto; border-left:1px solid ${T.border}; display:flex; flex-direction:column; gap:8px; padding:12px; overflow:hidden;">${buskLabel('Speed')}${card('Master 1', '120.0', { on: true })}${card('Colour', '61.2', { usage: 'colour' })}${card('Movers', '30.0', { follow: '½ · follows M1' })}<span style="flex:1;"></span><span style="font-size:10px; color:${T.mfg}; line-height:1.4;">Hold a card to trim its tempo. Nothing here stamps a master.</span></div>`
}
const viewNav = (on) => `<nav style="display:inline-flex; align-items:center; gap:2px; border-radius:8px; border:1px solid ${T.border}; background:${T.card}; padding:2px;">${[['slidersV', 'Programmer'], ['theater', 'Show'], ['book', 'Prompt Book'], ['wave', 'Busk']].map(([i, l]) => `<span style="display:inline-flex; align-items:center; gap:6px; border-radius:6px; padding:4px 10px; font-size:12px; font-weight:600; white-space:nowrap; ${l === on ? `background:${T.muted}; color:${T.fg};` : `color:${T.mfg};`}">${ico(i, 14)}<span>${l}</span></span>`).join('')}</nav>`
const liveHeaderRight = (on) => `${viewNav(on)}${btn({ icon: squareFill(14), label: 'Stop', variant: 'destructive' })}<span style="width:12px; height:12px; border-radius:999px; margin-left:4px; background:${T.green}; box-shadow:0 0 6px ${T.green};"></span>`

// ---- the screen frame: an app frame with a name strip above it, in place of the browser chrome ---------
// A fullscreen window has no browser chrome, so the frame is the app alone; the strip names which
// screen it is, and is not part of the design.
function screenFrame(active, body, { w = FW, h = FH, chrome = null } = {}) {
  return `${HEAD}
<div class="app" style="width:${w}px; height:${h}px; display:flex; flex-direction:column;">
  ${chrome ?? ''}
  ${appHeader({ vw: w, cw: w })}
  <div style="flex:1; min-height:0; display:flex;">
    ${sidebar(h - APP_HEADER - (chrome ? 28 : 0), active)}
    <main style="flex:1; min-width:0; display:flex; flex-direction:column; background:${C.mainBg}; overflow:hidden; position:relative;">${body}</main>
  </div>
</div>
${TAIL}`
}

// =====================================================================================================
// MAIN — Screen 1: the programmer, a Colour marquee over three heads. The bar carries the desk chip.
// =====================================================================================================
const SEL_ROWS = [0, 1, 2] // the three Freedom Pars / Lightbar
function programmerRows() {
  return RIG.map((f, i) => {
    const sel = SEL_ROWS.includes(i)
    const cells = VCOLS.map((l) => {
      const key = l.toLowerCase()
      const c = FC[key]
      if (!f.has.includes(key)) return blank(c)
      const local = (f.local ?? []).includes(key)
      const inner = local ? valueCell(key, f.live[key]) : dash
      return cellWrap(c, inner, { own: local ? 'you' : null, sel: sel && key === 'colour' })
    }).join('')
    return sheetRow(nameCell(FC.name, f.name, { sel, chev: f.chev ?? null, badge: f.badge ?? null }) + cells, { sel })
  }).join('')
}
function mainBoard() {
  const sourceBox = `<span style="display:inline-flex; align-items:center; gap:8px; height:32px; padding:0 10px; border-radius:6px; border:1px solid ${T.border}; flex:0 0 auto;">${zlab('Busking')}<span style="font-size:12px; color:${T.mfg};">18 values</span></span>`
  const clearFade = `<span style="display:inline-flex; align-items:stretch; height:32px; border-radius:6px; border:1px solid ${T.border}; overflow:hidden; flex:0 0 auto;"><span style="display:inline-flex; align-items:center; gap:6px; padding:0 10px; font-size:12px; font-weight:500;">${ico('eraser', 14)}Clear</span><span style="display:inline-flex; align-items:center; gap:6px; width:86px; padding:0 10px; border-left:1px solid ${T.border}; background:oklch(0.274 0.006 286.033 / 0.40); font-size:12px; ${mono}">Snap${ico('chevD', 12, `margin-left:auto; color:${T.mfg}`)}</span></span>`
  const record = `<span style="display:inline-flex; align-items:stretch; height:32px; border-radius:6px; overflow:hidden; background:${T.primary}; color:${T.pfg}; flex:0 0 auto;"><span style="display:inline-flex; align-items:center; gap:6px; padding:0 12px; font-size:12px; font-weight:600;">${dotFill(12)}Record</span><span style="display:inline-flex; align-items:center; padding:0 6px; border-left:1px solid oklch(0.21 0.006 285.885 / 0.25);">${ico('chevD', 14)}</span></span>`
  const rowA = chromeRow(`${sourceBox}${spacer()}${divider()}${clearFade}${btn({ icon: ico('eyeOff', 14), label: 'Blind' })}${btn({ icon: ico('download', 14), label: 'Include…' })}${record}`, `background:oklch(0.21 0.006 285.885 / 0.50);`)
  const scope = `<span style="display:inline-flex; align-items:center; height:32px; padding:4px; border-radius:8px; background:${T.muted}; gap:2px; flex:0 0 auto;">${[['eye', 'Output', false], ['hand', 'Local', true]].map(([i, l, on]) => `<span style="display:inline-flex; align-items:center; gap:6px; height:24px; padding:0 8px; border-radius:6px; font-size:12px; font-weight:500; ${on ? `background:${T.bg}; color:${T.fg}; box-shadow:0 1px 2px rgba(0,0,0,0.3);` : `color:${T.mfg};`}">${ico(i, 14)}${l}</span>`).join('')}</span>`
  const rowB = chromeRow(`${scope}${filterField()}${litBtn()}${spacer()}${groupsBtn()}${columnsBtn()}`)
  const chips = strip([tchip('Warm Amber', 'oklch(0.75 0.16 70)'), tchip('Cold Blue', 'oklch(0.6 0.2 260)'), tchip('Lavender', 'oklch(0.7 0.15 300)', { on: true }), tchip('Colour Pulse', null, { effect: true }), tchip('Deep Red', 'oklch(0.5 0.22 25)')])
  const bar = selectionBar({ counts: ['3 cells', '3 fixtures'], family: 'Colour', strip: `${deskChip()}${chips}`, verbs: `${verb('pencil', 'Set')}${verb('backspace', 'Clear')}${verb('fan', 'Fan')}${verb('crosshair', 'Locate')}${verb('flashlight', 'Highlight')}${btn({ label: 'Deselect', variant: 'ghost' })}`, hints: false })
  const grid = `<div style="flex:1; min-width:0; display:flex; flex-direction:column; position:relative;">${rowA}${rowB}${bar}${sheetBody(FCOLS, programmerRows(), { fade: true })}${footer(`<span style="font-variant-numeric:tabular-nums;">7 fixtures · 3 selected</span>${ownershipLegend()}`, layerLegend())}</div>`
  const railBtn = (icon, n, color = T.mfg) => `<span style="display:flex; flex-direction:column; align-items:center; gap:2px; color:${color};">${ico(icon, 14)}${countBadge(n)}</span>`
  const rail = `<div style="width:40px; flex:0 0 auto; border-left:1px solid ${T.border}; display:flex; flex-direction:column; align-items:center; gap:14px; padding:12px 0;">${ico('chevL', 14, `color:${T.mfg}`)}${railBtn('layers', 1)}${railBtn('wave', 0, T.violet)}<span style="flex:1;"></span>${ico('plus', 14, `color:${T.mfg}`)}</div>`
  const header = pageHeader(crumbs('Programmer'), liveHeaderRight('Programmer'), 'border-bottom-color:transparent;')
  // The marquee just released over the Colour column, rows 1–3 — the rubber band is gone, the overlay stays.
  const body = `${header}<div style="flex:1; min-height:0; display:flex;">${grid}${rail}</div>`
  writeFileSync('Main.dc.html', screenFrame('slidersV', body))
  return { w: FW, h: FH, title: 'Screen 1 · Programmer — a Colour marquee over three heads' }
}
boards.Main = mainBoard()

// =====================================================================================================
// SCREEN 2 — the busk view on the second screen. The same three heads are lit in the target band, the
// Colour family the marquee named rides along, and the bar says where the selection came from.
// =====================================================================================================
function buskBody({ from, hand = false, drop = false, dimBand = false, w = FW }) {
  const groups = [tpad('Front Wash', { icon: 'layers', n: 4 }), tpad('Movers', { icon: 'layers', n: 2 }), tpad('Bars', { icon: 'layers', n: 2, half: true })]
  const fixtures = [tpad('Hex 1', { on: true }), tpad('Hex 2', { on: true }), tpad('Bar 1', { on: true }), tpad('Bar 2'), tpad('Dimmer 1'), tpad('Dimmer 2'), tpad('Spot')]
  const summary = `<span style="display:inline-flex; align-items:center; gap:8px;"><span>3 fixtures</span>${pill('Colour', { fg: BLUE, bc: PRI40, bg: PRI10 })}${deskChip({ from })}</span>`
  const band = targetBand({ groups, fixtures, summary, dim: dimBand })
  const colours = bank('Colours', [
    pad('Warm Amber', { swatch: 'oklch(0.75 0.16 70)', detail: 'generic' }),
    pad('Lavender', { swatch: 'oklch(0.7 0.15 300)', detail: 'generic', presence: 'all' }),
    pad('Cold Blue', { swatch: 'oklch(0.6 0.2 260)', detail: 'generic' }),
    pad('Deep Red', { swatch: 'oklch(0.5 0.22 25)', detail: 'generic' }),
    pad('Colour Pulse', { effect: true, detail: 'Colour Pulse · ½ · M2' }),
  ], { solo: true })
  const looks = bank('Looks', [
    pad('Warm Wash', { detail: '2 values · 4 heads', presence: 'some' }),
    pad('Ballyhoo', { detail: '1 effect · deferred' }),
    pad('Pre-set', { detail: '6 values · 6 heads' }),
  ], { drop, slot: drop ? 1 : null })
  const cues = bank('Cues', [
    pad('House to half', { cue: 'idle', detail: '0.5' }),
    pad('Opening', { cue: 'live', detail: '1' }),
    pad('Blackout', { cue: 'idle', detail: '9' }),
  ], { column: true })
  const positions = bank('Positions', [pad('Centre', { detail: 'per fixture' }), pad('Wide', { detail: 'per fixture' }), pad('Circle', { effect: true, detail: 'Pan Tilt Circle · 1 · M3' })], { solo: true })
  const page = `<div style="flex:1; min-height:0; overflow:hidden; padding:12px; display:flex; flex-direction:column; gap:12px;">
    <div style="display:grid; grid-template-columns:repeat(12, minmax(0, 1fr)); gap:12px;"><div style="grid-column:span 7; display:flex; flex-direction:column; gap:12px;">${colours}${positions}</div><div style="grid-column:span 3; display:flex; flex-direction:column; gap:12px;">${looks}</div><div style="grid-column:span 2;">${cues}</div></div>
  </div>`
  const left = `<div style="flex:1; min-width:0; display:flex; flex-direction:column;">${band}${pageStrip(['Act One', 'Interval', 'Act Two'], 'Act One')}${page}</div>`
  return `<div style="flex:1; min-height:0; display:flex;">${left}${speedRail()}</div>`
}
function screen2Board() {
  const header = pageHeader(crumbs('Busk'), liveHeaderRight('Busk'), 'border-bottom-color:transparent;')
  const body = `${header}${showBar()}${buskBody({ from: 'Screen 1' })}`
  writeFileSync('Screen2.dc.html', screenFrame('wave', body))
  return { w: FW, h: FH, title: 'Screen 2 · Busk — the same selection, one press away' }
}
boards.Screen2 = screen2Board()

// =====================================================================================================
// TABLET — the iPad, in the middle of a cross-window move: Warm Wash is in the hand, picked up on
// Screen 2, and the Looks bank here is showing where it would land.
// =====================================================================================================
function tabletBoard() {
  const header = pageHeader(crumbs('Busk'), liveHeaderRight('Busk'), 'border-bottom-color:transparent;')
  const body = `${header}${showBar()}${buskBody({ from: 'Screen 1', drop: true })}${handChip({ x: 380, y: FH - 52 - 64, name: 'Warm Wash', kind: 'Look', detail: 'Picked up on Screen 2 · tap a bank, a slot or the stack to place it', where: null })}`
  writeFileSync('Tablet.dc.html', screenFrame('wave', body))
  return { w: FW, h: FH, title: 'iPad · Busk — a Look in the hand, picked up on Screen 2' }
}
boards.Tablet = tabletBoard()

// =====================================================================================================
// SCREENS — going full screen, and what each window shows. Drawn as the pieces, not a whole page.
// =====================================================================================================
const H1 = (t) => `<span style="font-size:22px; font-weight:700; letter-spacing:-0.01em;">${t}</span>`
const para = (t, size = 12) => `<p style="margin:0; font-size:${size}px; line-height:1.55; color:${T.mfg}; max-width:720px;">${codify(t)}</p>`
const card = (inner, extra = '') => `<div style="display:flex; flex-direction:column; gap:12px; padding:16px; border-radius:10px; border:1px solid ${T.border}; background:${T.card}; ${extra}">${inner}</div>`
const menuItem = (icon, label, { right = '', on = false, sep = false, sub = null } = {}) => `<div style="display:flex; align-items:center; gap:10px; height:${sub ? 40 : 32}px; padding:0 8px; border-radius:6px; font-size:13px; ${on ? `background:${T.muted};` : ''} ${sep ? `border-top:1px solid ${T.border}; border-radius:0; margin-top:4px; padding-top:4px; height:${sub ? 44 : 36}px;` : ''}">${ico(icon, 14, `color:${T.mfg}`)}<span style="display:flex; flex-direction:column; gap:1px; flex:1; min-width:0;"><span>${label}</span>${sub ? `<span style="font-size:10.5px; color:${T.mfg};">${sub}</span>` : ''}</span>${right}</div>`
const menu = (items, w = 260) => `<div style="width:${w}px; padding:4px; border-radius:8px; border:1px solid ${T.border}; background:${T.card}; box-shadow:0 10px 15px -3px rgba(0,0,0,0.5), 0 4px 6px -4px rgba(0,0,0,0.5); display:flex; flex-direction:column;">${items.join('')}</div>`
const docHead = (h, sub) => `<div style="display:flex; flex-direction:column; gap:6px;">${H1(h)}${para(sub, 12.5)}</div>`
const artboard = (w, h, inner) => `${HEAD}<div class="app" style="width:${w}px; height:${h}px; padding:32px; display:flex; flex-direction:column; gap:24px; overflow:hidden;">${inner}</div>${TAIL}`

function screensBoard() {
  const W = 1240, Hh = 1260
  // 1 · the user menu gains Full screen and Screens…
  const userMenu = menu([
    `<div style="display:flex; flex-direction:column; gap:2px; padding:6px 8px 8px;"><span style="font-size:13px; font-weight:600;">Chris Cormack</span><span style="font-size:11px; color:${T.mfg};">admin · this window is <b style="color:${T.fg}; font-weight:600;">Screen 1</b></span></div>`,
    menuItem('user', 'Profile…'),
    menuItem('moon', 'Dark theme', { right: `<span style="width:28px; height:16px; border-radius:999px; background:${T.primary}; position:relative;"><span style="position:absolute; top:2px; right:2px; width:12px; height:12px; border-radius:999px; background:${T.pfg};"></span></span>` }),
    menuItem('maximize', 'Full screen', { right: kbd('⇧F'), sep: true }),
    menuItem('monitor', 'Screens…', { right: `<span style="font-size:10.5px; color:${T.mfg};">3 windows</span>` }),
    menuItem('logout', 'Log out', { sep: true }),
  ])
  // 2 · the Screens sheet: every window on the desk, what it shows, and what it could show
  const winRow = (icon, name, view, { me = false, fs = true, follow = true } = {}) => `<div style="display:flex; align-items:center; gap:12px; height:52px; padding:0 12px; border-radius:8px; border:1px solid ${me ? PRI40 : T.border}; background:${me ? PRI10 : 'transparent'};">
    ${ico(icon, 18, `color:${me ? BLUE : T.mfg}`)}
    <span style="display:flex; flex-direction:column; gap:2px; width:150px;"><span style="font-size:13px; font-weight:600;">${name}${me ? `<span style="font-size:10px; font-weight:500; color:${BLUE}; margin-left:6px;">this window</span>` : ''}</span><span style="font-size:10.5px; color:${T.mfg};">${fs ? 'full screen' : 'in a browser tab'} · ${follow ? 'follows the desk' : 'own selection'}</span></span>
    <span style="display:inline-flex; align-items:center; gap:6px; height:32px; padding:0 10px; border-radius:6px; border:1px solid ${T.border}; background:oklch(0.274 0.006 286.033 / 0.30); font-size:12px; font-weight:500; flex:1; max-width:220px;">${ico(view === 'Programmer' ? 'slidersV' : view === 'Busk' ? 'wave' : view === 'Show' ? 'theater' : 'book', 14)}<span style="flex:1;">${view}</span>${ico('chevD', 12, `color:${T.mfg}`)}</span>
    ${btn({ icon: ico(fs ? 'minimize' : 'maximize', 14), label: fs ? 'Exit full screen' : 'Full screen', disabled: !me })}
    ${btn({ icon: ico('pin', 14), variant: 'ghost' })}
  </div>`
  const screensSheet = `<div style="width:640px; border-radius:10px; border:1px solid ${T.border}; background:${T.bg}; box-shadow:0 20px 40px -10px rgba(0,0,0,0.7); display:flex; flex-direction:column; overflow:hidden;">
    <div style="padding:16px 16px 8px; display:flex; flex-direction:column; gap:4px;"><span style="font-size:16px; font-weight:600;">Screens</span><span style="font-size:12px; color:${T.mfg};">Every window signed in to this desk. Change what one shows from any of the others.</span></div>
    <div style="padding:8px 16px 16px; display:flex; flex-direction:column; gap:8px;">
      ${winRow('monitor', 'Screen 1', 'Programmer', { me: true })}
      ${winRow('monitor', 'Screen 2', 'Busk')}
      ${winRow('tablet', "Chris's iPad", 'Busk', { fs: false })}
      <div style="display:flex; align-items:center; gap:8px; height:44px; padding:0 12px; border-radius:8px; border:1px dashed ${T.border}; color:${T.mfg}; font-size:12px;">${ico('plus', 14)}<span style="flex:1;">Open a window on…</span>${btn({ icon: ico('monitor', 14), label: 'Display 2 · 1920×1080', h: 28 })}${btn({ icon: ico('copy', 14), label: 'Copy link for another device', h: 28 })}</div>
      <div style="display:flex; align-items:center; gap:8px; margin-top:4px;">${zlab('Layouts')}${btn({ label: 'Desk · Programmer + Busk', h: 28, on: true })}${btn({ label: 'Show night · Show + Prompt Book', h: 28 })}${btn({ icon: ico('plus', 14), label: 'Save current', h: 28, variant: 'ghost' })}</div>
    </div>
    <div style="padding:12px 16px; border-top:1px solid ${T.border}; display:flex; justify-content:flex-end; gap:8px;">${btn({ label: 'Close' })}</div>
  </div>`
  // 3 · Cmd+K rows
  const kRow = (icon, t, right = '') => `<div style="display:flex; align-items:center; gap:10px; height:36px; padding:0 10px; font-size:13px;">${ico(icon, 14, `color:${T.mfg}`)}<span style="flex:1;">${t}</span><span style="font-size:10.5px; color:${T.mfg};">${right}</span></div>`
  const palette = `<div style="width:420px; border-radius:10px; border:1px solid ${T.border}; background:${T.card}; box-shadow:0 20px 40px -10px rgba(0,0,0,0.7); overflow:hidden;">
    <div style="display:flex; align-items:center; gap:8px; height:44px; padding:0 12px; border-bottom:1px solid ${T.border}; font-size:14px;">${ico('search', 16, `color:${T.mfg}`)}<span>scr</span><span style="width:1px; height:16px; background:${T.fg};"></span></div>
    <div style="padding:6px 4px; display:flex; flex-direction:column;">${zlab('Screens', T.mfg, 'padding:6px 10px 4px;')}${kRow('maximize', 'Go full screen', '⇧F')}${kRow('monitor', 'Show Busk on Screen 2', 'switches that window')}${kRow('monitor', 'Show Programmer on Screen 2')}${kRow('arrowUR', 'Open Busk on Display 2, full screen')}${kRow('link', 'Follow the desk selection in this window', 'on')}</div>
  </div>`
  // 4 · the exit affordance and the four routes
  const routes = `<div style="display:grid; grid-template-columns:repeat(4, minmax(0, 1fr)); gap:12px;">
    ${card(`${section('In the app')}${para('A <b>Full screen</b> item in the user menu and in ⌘K, on <code>document.documentElement.requestFullscreen()</code>. Needs a gesture, so it cannot happen on load — but it can be <i>remembered</i>: a window that was full screen last time shows a one-tap <b>Return to full screen</b> banner on reload.')}`)}
    ${card(`${section('Installed (the desk)')}${para('Install the app once per browser profile: the manifest says <code>display: fullscreen</code> (with <code>standalone</code> as the fallback), and every launch is chromeless with <b>no gesture and no Esc trap</b>. This is what the two desk screens should run. The Windows install already ships a browser; add a <code>--app=</code> shortcut per screen.')}`)}
    ${card(`${section('Kiosk (unattended)')}${para('Chrome <code>--kiosk</code> or the OS kiosk mode for a screen nobody should be able to leave — a front-of-house tablet. Nothing to build; a note in the docs.')}`)}
    ${card(`${section('iPad')}${para('Safari on iPadOS honours <code>requestFullscreen</code> on the document, so the same item works. <b>Add to Home Screen</b> is the durable route: <code>standalone</code> hides the address bar and keeps the session cookie.')}`)}
  </div>`
  const escNote = card(`${section('Esc, and what it does now')}${para('A browser leaves element full screen on Esc, and Esc is also the sheet\'s clear-selection key and every dialog\'s close. In a plain tab the browser takes it first and the app never sees it, which is why the installed route is the recommendation for the desk. Chrome\'s Keyboard Lock (<code>navigator.keyboard.lock([\'Escape\'])</code>) keeps Esc in a fullscreen window and is worth a feature-detected call on the Full screen path; there is no equivalent in Safari. In full screen the app draws a small <b>exit</b> glyph in the user menu only — never a floating button on a live view.')}`)
  const inner = `${docHead('Full screen, and what each screen shows', 'Three surfaces already exist for this — the user menu (where per-viewer things live), the app header\'s panel toggles, and ⌘K. Nothing new goes in the header row: it is nine controls wide and already off the edge of a phone. A window gets a <b>name</b> (Screen 1, Screen 2, Chris\'s iPad — set once, kept in that browser) and the desk keeps a list of the windows signed in, which is what lets one screen change what another shows: the console gesture of moving a window to the external monitor, done from either side.')}
  <div style="display:flex; gap:32px; align-items:flex-start;">
    <div style="display:flex; flex-direction:column; gap:12px;">${label('1 · The user menu', 'two items, under the theme toggle')}${userMenu}</div>
    <div style="display:flex; flex-direction:column; gap:12px;">${label('2 · Screens', 'a sheet on a desk, full screen on a phone')}${screensSheet}</div>
  </div>
  <div style="display:flex; gap:32px; align-items:flex-start;">
    <div style="display:flex; flex-direction:column; gap:12px;">${label('3 · ⌘K', 'every row above, as a command')}${palette}</div>
    <div style="display:flex; flex-direction:column; gap:12px; flex:1;">${label('4 · Esc', 'why the desk should run installed')}${escNote}</div>
  </div>
  <div style="display:flex; flex-direction:column; gap:12px;">${label('5 · Four ways out of the browser chrome', 'and which one each surface should use')}${routes}</div>`
  writeFileSync('Screens.dc.html', artboard(W, Hh, inner))
  return { w: W, h: Hh, title: 'Full screen · Screens' }
}
boards.Screens = screensBoard()

// =====================================================================================================
// HAND — moving a thing from one window to another. Three frames at mini-window size.
// =====================================================================================================
function miniWindow(name, icon, inner, { w = 360, h = 250, edge = null } = {}) {
  return `<div style="display:flex; flex-direction:column; gap:6px; width:${w}px;">
    <span style="display:inline-flex; align-items:center; gap:6px; font-size:11px; font-weight:600; color:${T.mfg};">${ico(icon, 12)}${name}</span>
    <div style="position:relative; width:${w}px; height:${h}px; border-radius:8px; border:1px solid ${T.border}; background:${C.mainBg}; overflow:hidden; ${edge === 'right' ? `box-shadow:inset -3px 0 0 ${T.primary};` : edge === 'left' ? `box-shadow:inset 3px 0 0 ${T.primary};` : ''}">${inner}</div>
  </div>`
}
const miniBank = (name, pads, o = {}) => `<div style="padding:10px; display:flex; flex-direction:column; gap:8px;">${bank(name, pads, o)}</div>`
const miniHand = (name, kind, x, y) => `<div style="position:absolute; left:${x}px; top:${y}px; display:flex; align-items:center; gap:8px; height:34px; padding:0 10px; border-radius:999px; border:1px solid ${T.primary}; background:${T.card}; box-shadow:0 8px 16px -4px rgba(0,0,0,0.6), 0 0 0 3px ${PRI20}; font-size:11px; font-weight:600; white-space:nowrap;">${ico('grab', 13, `color:${BLUE}`)}${name}<span style="font-weight:500; color:${T.mfg};">${kind}</span>${ico('x', 11, `color:${T.mfg}`)}</div>`
function handBoard() {
  const W = 1240, Hh = 1600
  const p = (n, o = {}) => pad(n, { w: 96, ...o })
  // Frame 1: the pick-up, on Screen 2
  const f1a = miniWindow('Screen 2 · Busk', 'monitor', `${miniBank('Looks', [p('Warm Wash', { detail: '2 values' }), p('Ballyhoo', { detail: '1 effect' }), p('Pre-set', { detail: '6 values' })])}
    <div style="position:absolute; left:120px; top:48px; width:150px; padding:4px; border-radius:8px; border:1px solid ${T.border}; background:${T.card}; box-shadow:0 10px 15px -3px rgba(0,0,0,0.5); display:flex; flex-direction:column;">${menuItem('grab', 'Pick up', { on: true })}${menuItem('arrowUR', 'Send to…', { right: ico('chevR', 12, `color:${T.mfg}`) })}${menuItem('eye', 'Inspect')}${menuItem('x', 'Remove from bank')}</div>`)
  const f1b = miniWindow('iPad · Busk', 'tablet', miniBank('Looks', [p('Pre-set', { detail: '6 values' })]))
  // Frame 2: in the hand, on every window
  const f2a = miniWindow('Screen 2 · Busk', 'monitor', `${miniBank('Looks', [p('Warm Wash', { detail: '2 values', ghost: true }), p('Ballyhoo', { detail: '1 effect' }), p('Pre-set', { detail: '6 values' })])}${miniHand('Warm Wash', 'Look', 12, 200)}`)
  const f2b = miniWindow('iPad · Busk', 'tablet', `${miniBank('Looks', [p('Pre-set', { detail: '6 values' })], { drop: true, slot: 0 })}${miniHand('Warm Wash', 'Look', 12, 200)}<span style="position:absolute; right:10px; top:120px; font-size:10px; color:${T.mfg};">every bank, slot and layer stack lights</span>`)
  // Frame 3: placed
  const f3a = miniWindow('Screen 2 · Busk', 'monitor', miniBank('Looks', [p('Warm Wash', { detail: '2 values' }), p('Ballyhoo', { detail: '1 effect' }), p('Pre-set', { detail: '6 values' })]))
  const f3b = miniWindow('iPad · Busk', 'tablet', `${miniBank('Looks', [p('Warm Wash', { detail: '2 values', presence: 'some' }), p('Pre-set', { detail: '6 values' })])}<span style="position:absolute; left:10px; bottom:10px; display:inline-flex; align-items:center; gap:6px; font-size:10.5px; color:${T.mfg};">${ico('check', 12, `color:${T.green}`)}Placed in Looks on Chris\'s iPad · <span style="color:${T.fg};">Undo</span></span>`)
  const frame = (n, t, sub, a, b) => `<div style="display:flex; flex-direction:column; gap:10px;">${label(`${n} · ${t}`, sub)}<div style="display:flex; gap:16px;">${a}${b}</div></div>`
  // The edge drag — the same-machine shortcut
  const edgeA = miniWindow('Screen 1 · Programmer', 'monitor', `<div style="padding:10px; display:flex; flex-direction:column; gap:8px;">${chromeRow(`${deskChip()}${strip([tchip('Warm Amber', 'oklch(0.75 0.16 70)'), tchip('Lavender', 'oklch(0.7 0.15 300)')])}`)}<div style="height:120px;"></div></div><div style="position:absolute; right:8px; top:118px;">${tchip('Lavender', 'oklch(0.7 0.15 300)', { on: true })}</div><div style="position:absolute; right:10px; bottom:10px; font-size:10px; color:${T.mfg}; text-align:right;">a chip dragged off the right edge…</div>`, { edge: 'right' })
  const edgeB = miniWindow('Screen 2 · Busk', 'monitor', `${miniBank('Colours', [p('Warm Amber', { swatch: 'oklch(0.75 0.16 70)' }), p('Cold Blue', { swatch: 'oklch(0.6 0.2 260)' })], { drop: true, slot: 2 })}<div style="position:absolute; left:8px; top:118px;">${tchip('Lavender', 'oklch(0.7 0.15 300)', { on: true })}</div><div style="position:absolute; left:10px; bottom:10px; font-size:10px; color:${T.mfg};">…arrives on the screen to its right, still under the pointer</div>`, { edge: 'left' })

  const inner = `${docHead('The hand: moving a thing between windows', 'No browser can drag a dnd-kit element out of one window and into another, and no browser at all can drag from a desk screen to an iPad. So the move is a <b>desk fact</b> rather than a pointer gesture: a record is <i>picked up</i> into the hand, the desk broadcasts it, every window draws it and lights its drop targets, and a tap in any window places it. The same mechanism serves touch on one window — it is a cut-and-paste with a visible clipboard, which is what a desk\'s <b>Move</b> key is.')}
  ${frame('1', 'Pick up', 'a hold on any pad, chip, Look row, cue or layer — the same hold that opens the inspector today, with <b>Pick up</b> at the top of the menu', f1a, f1b)}
  ${frame('2', 'In the hand', 'the chip rides every window signed in to the desk; targets that can take this kind light; a second pick-up replaces, × or Esc drops it', f2a, f2b)}
  ${frame('3', 'Place', 'a tap on a bank, a slot, the layer stack or a cue\'s stack; the placing window undoes; the hand empties for everyone', f3a, f3b)}
  <div style="display:flex; flex-direction:column; gap:10px;">${label('Same machine, two screens', 'the shortcut: an ordinary drag that leaves a window\'s edge goes into the hand, and the neighbouring window shows it entering on its facing edge, under the pointer')}<div style="display:flex; gap:56px;">${edgeA}${edgeB}</div>${para('Chrome\'s Window Management API says where each window sits on which display, so a drag that crosses the boundary can be handed over as a pointer position on the other screen rather than as a dropped chip. The hand is what makes it safe: if the other window is not there, or is an iPad, the drag ends as a pick-up and nothing is lost. Same-origin windows in one browser can carry the ghost over <code>BroadcastChannel</code> without a round trip to the desk; the desk frame is the source of truth either way.', 11.5)}</div>`
  writeFileSync('Hand.dc.html', artboard(W, Hh, inner))
  return { w: W, h: Hh, title: 'The hand · a move across windows' }
}
boards.Hand = handBoard()

// =====================================================================================================
// MODEL — what the desk owns, what a window owns, and the frames that carry it.
// =====================================================================================================
function modelBoard() {
  const W = 1240, Hh = 960
  const th = (t) => `<span style="font-size:10.5px; font-weight:600; text-transform:uppercase; letter-spacing:0.06em; color:${T.mfg}; padding:8px 10px;">${t}</span>`
  const td = (t, extra = '') => `<span style="font-size:12px; line-height:1.45; padding:8px 10px; border-top:1px solid ${T.border}; ${extra}">${codify(t)}</span>`
  const tag = (t, kind) => `<span style="display:inline-flex; align-items:center; height:18px; padding:0 6px; border-radius:4px; font-size:10px; font-weight:600; ${kind === 'desk' ? `background:${PRI20}; color:${BLUE};` : kind === 'new' ? `background:oklch(0.723 0.219 149.579 / 0.15); color:${T.green};` : `background:${T.muted}; color:${T.mfg};`}">${t}</span>`
  const rows = [
    ['Fixture / group selection', tag('desk', 'desk') + ' today', '`selection.state`, one per project, cleared on project switch', 'unchanged — the busk band, the programmer rows and a MIDI select button already share it'],
    ['Attribute mask (which cells)', tag('window', 'win') + ' today', '`selection.state` gains `families: [COLOUR]` and, per target, the cell set', tag('new', 'new') + ' a marquee publishes its columns; the band shows the family pill; a template pressed elsewhere lands on those cells'],
    ['Who moved it', '—', '`source: {window: "Screen 1"}` on the same frame', tag('new', 'new') + ' the bar says <i>from Screen 1</i>; a MIDI surface says <i>from the desk</i>'],
    ['Follow / local', tag('window', 'win'), 'a flag in that tab\'s `localStorage`, never sent', tag('new', 'new') + ' Local = today\'s `/fixtures/list` behaviour, chosen per window; the chip flips it'],
    ['Programmer scope · fade · blind', tag('desk', 'desk') + ' / ' + tag('window', 'win'), 'blind and fade are desk facts already; scope stays a window fact', 'unchanged — two windows may look at Output and Local of one programmer at once, which is the point'],
    ['Template recents', tag('desk', 'desk') + ' today', '`templatePressed`', 'unchanged'],
    ['Busk page showing', tag('desk', 'desk') + ' today', '`busk.pageState`', 'unchanged — and it is the precedent: a window can already move another window\'s page'],
    ['The hand', '—', '`hand.state {item, pickedUpOn, pickedUpAt}` · `hand.pickUp` · `hand.place` · `hand.drop`', tag('new', 'new') + ' one held item per desk; a second pick-up replaces; auto-drops after 5 min'],
    ['Windows', '—', '`windows.state [{id, name, view, fullscreen, follows}]` · `windows.show {id, view}`', tag('new', 'new') + ' a socket announces its name and route; `windows.show` navigates that window'],
    ['Cell editor open · marquee in flight · filter text', tag('window', 'win'), 'never sent', 'a drag publishes at row boundaries as it does now; the rubber band itself is this window\'s'],
  ]
  const table = `<div style="display:grid; grid-template-columns:200px 120px 1fr 1fr; border:1px solid ${T.border}; border-radius:8px; overflow:hidden; background:${T.card};">${th('Fact')}${th('Owner')}${th('Wire')}${th('What changes')}${rows.map((r) => r.map((c) => td(c)).join('')).join('')}</div>`
  // the two chip states, side by side
  const states = `<div style="display:grid; grid-template-columns:repeat(2, minmax(0, 1fr)); gap:16px;">
    ${card(`${section('Following the desk')}<div style="display:flex; align-items:center; gap:8px;">${deskChip({ from: 'Chris\'s iPad' })}<span style="font-size:11px; color:${T.mfg};">the default; the name is who moved it last, and is never this window</span></div>${para('A marquee here publishes targets and columns; a pad pressed on the iPad lands on them. A press on any window clears the name — from then on it is simply <i>Desk</i> until another window moves it.')}`)}
    ${card(`${section('This window only')}<div style="display:flex; align-items:center; gap:8px;">${deskChip({ local: true })}<span style="font-size:11px; color:${T.mfg};">a click on the chip; kept per browser tab; shown dashed so it cannot be mistaken for linked</span></div>${para('The selection is a browsing scope, as it is on <code>/fixtures/list</code> today. A template press on this window lands on this window\'s rows; the busk band on the other screen does not move. Record still scopes on what this window shows. Unlinking while three heads are shared keeps them here and leaves the desk\'s copy alone.')}`)}
  </div>`
  const seq = card(`${section('A press on Screen 2 — the sequence')}<div style="display:flex; flex-direction:column; gap:6px; font-size:12px; line-height:1.5; color:${T.mfg};">
    <span><b style="color:${T.fg};">1</b> Screen 1: marquee released over Colour × 3 rows → <code>selection.set {targets:[hex-1, hex-2, bar-1], families:[COLOUR]}</code> — the bridge already does the first half at row boundaries.</span>
    <span><b style="color:${T.fg};">2</b> Desk: stores it with <code>source: Screen 1</code>, broadcasts <code>selection.state</code> to every socket. Screen 2\'s band lights three pads and a Colour pill; the iPad\'s too.</span>
    <span><b style="color:${T.fg};">3</b> Screen 2: Lavender pressed → <code>POST /busk/pads/{id}/press</code> with no targets — the pad press already reads the desk selection server-side. The mask narrows it to Colour, so a Look pad holding position rows would skip them, and the bar on Screen 1 says so.</span>
    <span><b style="color:${T.fg};">4</b> Screen 1: the three cells ring <i>You</i> and the value lands — no window-to-window message was ever sent; both only ever spoke to the desk.</span>
  </div>`)
  const ipad = card(`${section('Why the iPad is not a special case')}${para('An iPad is a window with a name and no display of its own. It follows the desk by default like any other, so a selection made with a finger on its busk band is the selection Screen 1\'s programmer shows. What it cannot do is host an edge drag (it has no neighbour), which is why the hand is the universal route and the edge drag only the shortcut.')}`)
  const opt = card(`${section('Optional within a session — the two questions')}${para('<b>Per window, not per session:</b> the follow flag belongs to the browser tab, because the whole point is that two tabs on one machine can differ. <b>Default on:</b> a new window follows. That is what the programmer does today and what every console does; a window that starts unlinked would look broken when the band on the other screen stayed dark. The only surface that stays unlinked by default is a plain list on a route with no scope — Fixtures › List and Groups › List — which is today\'s rule kept.')}`)
  const inner = `${docHead('The model: desk facts and window facts', 'Most of the architecture already exists. The selection, the busk page, blind, the template recents and the programmer stack are <b>desk facts</b>: server-owned, broadcast as a keyed frame, mirrored per window. What this work adds is three more of the same shape — the attribute mask on the selection, a registry of windows, and the hand — and one window fact, the follow flag. Nothing is sent between windows; every window talks to the desk and the desk answers everyone.')}
  ${table}${states}<div style="display:grid; grid-template-columns:repeat(2, minmax(0, 1fr)); gap:16px;">${seq}<div style="display:flex; flex-direction:column; gap:16px;">${ipad}${opt}</div></div>`
  writeFileSync('Model.dc.html', artboard(W, Hh, inner))
  return { w: W, h: Hh, title: 'The model · desk facts, window facts, frames' }
}
boards.Model = modelBoard()

// =====================================================================================================
// SURVEY — what the consoles do.
// =====================================================================================================
function surveyBoard() {
  const W = 1240, Hh = 780
  const th = (t) => `<span style="font-size:10.5px; font-weight:600; text-transform:uppercase; letter-spacing:0.06em; color:${T.mfg}; padding:8px 10px;">${t}</span>`
  const td = (t) => `<span style="font-size:12px; line-height:1.45; padding:8px 10px; border-top:1px solid ${T.border};">${codify(t)}</span>`
  const rows = [
    ['grandMA3', 'Multiple screens per station, each assigned a view; screen configuration is per user profile', 'onPC, wings, a station per device', '<b>Per user.</b> Every user in a session has their own programmer and screen layout; the output is shared, the programmer is not', 'The per-user programmer is the strongest model for multiple <i>people</i>; ours is one desk, one programmer — right for a two-screen desk, and a second user would be a new desk fact, not a window fact'],
    ['ChamSYS MagicQ', 'Up to four monitors; a window is moved to the external monitor with EXT / View › External; MultiWindow app shows windows from a remote MagicQ on another PC', 'MagicQ Remote on iPad: the same window set, mirrored or extended', '<b>One programmer.</b> Every window and the remote act on it', 'The closest to what Chris described. <i>Move this window to that screen</i> is the Screens sheet\'s view picker; MultiWindow is what a second browser window already is'],
    ['ETC Eos', 'Monitor arrangement in setup; any tab on any monitor; Direct Selects and Magic Sheets are the touch surfaces', 'iRFR / aRFR share the console\'s command line; a client on the network is a second station', '<b>One command line, one programmer</b> across every display and remote of a console', 'Direct Selects are pads that act on the console\'s selection — our busk band + pads is that. Eos\'s remote is not a separate programmer, which is the follow-by-default call'],
    ['Hog 4', 'Up to three external touchscreens; windows opened per screen', 'Consoles on HogNet track page, master and playback, each keeps its own programmer', '<b>Per console.</b> Tracking shares playback state, not the programmer', 'Tracking is what our desk facts already are (page, playhead); Hog keeps programming separate per surface, which we only want for a second person'],
    ['Avolites Titan', 'Two screens on the console, Titan Go / Mobile on a PC', 'Titan Remote runs as a separate user with a <b>separate programmer</b> so a second person can update palettes mid-show', 'Per user (multi-user); one console is one programmer', 'Same reading as MA: a remote with its own programmer is a multi-person feature. For one operator across two screens and an iPad it is the wrong default'],
  ]
  const table = `<div style="display:grid; grid-template-columns:120px 1fr 1fr 1fr 1.2fr; border:1px solid ${T.border}; border-radius:8px; overflow:hidden; background:${T.card};">${th('Console')}${th('Screens')}${th('Remote / second device')}${th('Programmer model')}${th('What we take')}${rows.map((r) => r.map(td).join('')).join('')}</div>`
  const takeaways = `<div style="display:grid; grid-template-columns:repeat(3, minmax(0, 1fr)); gap:16px;">
    ${card(`${section('1 · Screens are views on one programmer')}${para('Every console with a physical second screen treats it as another view on the same state, and every one lets you choose what each screen shows. None of them makes the second screen a second desk. Our windows already are views on one desk; what is missing is the naming and the <i>show X on screen 2</i> gesture.')}`)}
    ${card(`${section('2 · A remote is either the same programmer or a second user')}${para('Eos and MagicQ: the same. MA and Titan: a second user with a programmer of their own, because their remotes exist for a second person. Chris\'s ask is one operator reaching the desk from an iPad, so <b>follow by default, unlink per window</b> covers it, and a second-user mode stays a later, separate desk fact.')}`)}
    ${card(`${section('3 · Nobody drags across screens')}${para('A console moves a window to a screen; it does not drag a palette from one screen onto another. The hand has no precedent among the desks — its precedents are the Move key (pick up, then place) and the mobile clipboard. The edge drag is the one thing here that is genuinely new, and it is a shortcut over the hand, never the only route.')}`)}
  </div>`
  const inner = `${docHead('What other desks do', 'Five consoles, read for three things: how a second screen is treated, what a remote device is, and whether the programmer is shared. The sources are the vendors\' manuals; the readings in the last column are this record\'s.')}${table}${takeaways}`
  writeFileSync('Survey.dc.html', artboard(W, Hh, inner))
  return { w: W, h: Hh, title: 'Survey · what the consoles do' }
}
boards.Survey = surveyBoard()

// =====================================================================================================
// OPTIONS — three ways to move a thing across windows, so the hand is a chosen thing.
// =====================================================================================================
function optionsBoard() {
  const W = 1240, Hh = 440
  const opt = (name, t, reach, sub, tradeoff, rec = false) => `<div style="display:flex; flex-direction:column; gap:10px; padding:16px; border-radius:10px; border:1px solid ${rec ? T.primary : T.border}; background:${rec ? PRI10 : T.card};">
    <div style="display:flex; align-items:center; gap:8px;"><span style="font-size:14px; font-weight:700;">${name}</span>${rec ? pill('recommended', { fg: BLUE, bc: PRI40 }) : ''}</div>
    <span style="font-size:12px; font-weight:600;">${t}</span>${para(sub, 11.5)}
    <div style="display:flex; flex-direction:column; gap:4px; margin-top:auto;"><span style="font-size:10.5px; font-weight:600; text-transform:uppercase; letter-spacing:0.06em; color:${T.mfg};">Reaches</span><span style="font-size:11.5px;">${reach}</span><span style="font-size:10.5px; font-weight:600; text-transform:uppercase; letter-spacing:0.06em; color:${T.mfg}; margin-top:6px;">Costs</span><span style="font-size:11.5px; color:${T.mfg}; line-height:1.45;">${tradeoff}</span></div>
  </div>`
  const inner = `${docHead('Three ways to move a thing between windows', 'The ask was drag and drop; the honest answer is that a pointer drag cannot leave a browser window in general, so each option is a different way of making the move not depend on one.')}
  <div style="display:grid; grid-template-columns:repeat(3, minmax(0, 1fr)); gap:16px; flex:1;">
    ${opt('A · The hand', 'Pick up here, place there — a desk-owned held item', 'Desk screens, iPad, phone, and a MIDI button (Pick up / Place as bindings)', 'A hold on any record opens its menu with <b>Pick up</b>; every window draws the chip and lights its targets; a tap places. One mechanism for every pair of windows, and for touch on one.', 'Two gestures where a drag is one. A new frame family and a new desk fact. The chip is one more thing on a live view — it must sit where the ShowBar\'s GO cannot be hidden by it.', true)}
    ${opt('B · Native drag, same browser', 'HTML5 drag-and-drop between two windows of one browser on one machine', 'The two desk screens only', 'The browser carries a <code>dataTransfer</code> across its own windows for free. A pad would have to be a native draggable as well as a dnd-kit one, and the drop resolved from the payload.', 'Nothing reaches the iPad. dnd-kit and native DnD on one element fight over the pointer; the ghost is the browser\'s, not ours. Feels great when it works and silently does nothing otherwise.')}
    ${opt('C · Send to…', 'A menu: send this record to a bank / slot / stack on a named window', 'Everything, with no gesture at all', 'The hold menu grows a <b>Send to</b> submenu listing every bank on every page, and the layer stack. No held state, no second window involved.', 'Choosing a destination from a list is the thing a drag exists to avoid, and a page with twelve banks makes a long menu. Right as the accessible fallback under A, wrong as the primary.')}
  </div>`
  writeFileSync('Options.dc.html', artboard(W, Hh, inner))
  return { w: W, h: Hh, title: 'Options · moving a thing across windows' }
}
boards.Options = optionsBoard()

// ---- canvas.json ----------------------------------------------------------------------------------
const GAPX = 100, GAPY = 200
const layout = [
  ['Survey', 0, 0],
  ['Model', boards.Survey.w + GAPX, 0],
]
const y2 = Math.max(boards.Survey.h, boards.Model.h) + GAPY
const col = (i) => i * (FW + GAPX)
layout.push(['Main', col(0), y2], ['Screen2', col(1), y2], ['Tablet', col(2), y2])
const y3 = y2 + FH + GAPY
layout.push(['Screens', 0, y3], ['Hand', boards.Screens.w + GAPX, y3], ['Options', boards.Screens.w + GAPX + boards.Hand.w + GAPX, y3])
const noteAbove = (id, x, y, text, w = FW) => ({ id, x, y: y - 150, w, text })
const canvas = {
  artboards: layout.map(([file, x, y]) => ({ file: `${file}.dc.html`, title: boards[file].title, x, y, w: boards[file].w, h: boards[file].h })),
  annotations: [
    { id: 'brief', x: 0, y: -440, w: 900, text: 'The desk on two screens and an iPad.\n\nThe theatre desk runs two touch screens, and with MagicQ both are the app full screen. The equivalent here is a full-screen browser window per screen, plus an iPad. Three asks: (1) controls to go full screen; (2) select on one window, act on another; (3) drag and drop between windows. Plus what the consoles do, and the architecture — selection server-side, optional per session.\n\nWhat this record found: the architecture is mostly there. The fixture selection is already a desk fact (selection.state), and so are the busk page, blind and the template recents. The work is three more facts of the same shape (the attribute mask, a windows registry, the hand), one window fact (follow / local), and one nameable gesture per ask. Static mockups, dark only, 1180×820 for the screens.' },
    noteAbove('n-survey', 0, 0, 'Read first: five consoles, three questions. The finding that shapes everything below — every console makes a second screen a view on one programmer, and a remote is either that same programmer (Eos, MagicQ) or a second user (MA, Titan). One operator, two screens, an iPad → follow by default.', boards.Survey.w),
    noteAbove('n-model', boards.Survey.w + GAPX, 0, 'The model. Desk facts vs window facts, what is new on the wire, the two states of the desk chip, and the four-step sequence of a press on Screen 2 landing on Screen 1\'s cells.', boards.Model.w),
    noteAbove('n-main', col(0), y2, 'Screen 1 — the programmer, full screen (no browser chrome, so the frame is the app alone). A marquee over Colour on three heads. The one new thing on the bar is the desk chip: Desk, linked, with no name because this window moved it.'),
    noteAbove('n-screen2', col(1), y2, 'Screen 2 — Busk, at the same moment. The band lights the three heads, carries the Colour pill the marquee named, and says "from Screen 1". Lavender was just pressed here and rings all — the value is already in Screen 1\'s cells.'),
    noteAbove('n-tablet', col(2), y2, 'The iPad, in Safari full screen. Warm Wash is in the hand — picked up on Screen 2 — and the Looks bank shows the dashed slot where a tap would place it. The chip is drawn on every window; this is the one where the operator is.'),
    noteAbove('n-screens', 0, y3, 'Ask 1. Full screen and Screens as user-menu items and ⌘K commands, the Screens sheet with the windows registry, saved layouts, and the four ways out of the chrome — installed (PWA) is the recommendation for the desk, because Esc.', boards.Screens.w),
    noteAbove('n-hand', boards.Screens.w + GAPX, y3, 'Ask 3. The hand in three frames, then the edge drag: the same-machine shortcut that makes two screens feel like one.', boards.Hand.w),
    noteAbove('n-options', boards.Screens.w + GAPX + boards.Hand.w + GAPX, y3, 'The three ways it could be done, so A is a choice. B is worth having as the fast path on the desk machine once A exists; C is the accessible fallback under A.', boards.Options.w),
  ],
  launch: { view: 'canvas' },
}
writeFileSync('canvas.json', JSON.stringify(canvas, null, 2) + '\n')
console.log(Object.entries(boards).map(([k, b]) => `${k} ${b.w}×${b.h}`).join('\n'))
