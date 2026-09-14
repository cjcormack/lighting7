// Generates the programmer-chrome design artboards.
// Every value is lifted from lighting-react: index.css tokens, button.tsx sizes, the row
// components under src/components/programmer/. The PROPOSED system is applied by the builders
// below; TODAY's numbers are quoted in Spec.dc.html.
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
function sourceBox({ mode, cw }) {
  const base = `display:flex; align-items:center; gap:8px; height:${SYS.control}px; min-width:0; overflow:hidden; border-radius:6px; padding:0 10px; border:1px solid ${T.border};`
  if (mode === 'empty') {
    return `<div style="${base} background:oklch(0.21 0.006 285.885 / 0.50); flex:0 0 auto;">${ico('circlePlus', 14, `color:${T.mfg}`)}${cw >= 410 ? `<span style="font-size:12px; color:${T.mfg}; white-space:nowrap;">No source</span>` : ''}</div>`
  }
  if (mode === 'busking') {
    return `<div style="${base} background:oklch(0.21 0.006 285.885 / 0.50); flex:0 0 auto;">${zlab('Busking')}${cw >= 450 ? `<span style="font-size:12px; color:${T.mfg}; white-space:nowrap;">12 values</span>` : ''}</div>`
  }
  // editing Q4 (cue), and layer-focused shares it
  const wide = cw >= 600
  if (!wide) {
    // The phone arm. The box gets 393 − 24 − 17 − the verbs on a portrait phone, and today's verbs
    // are 285, which leaves 67px for a box whose content needs ~200. So below 600 the box is the
    // number and its two verbs: the name and the count ride Update's title, and the dirty state is
    // an amber dot on Update itself. 24 + 28 + 28 + 16 + 20 = 116, and the verbs give up the fade
    // trigger's chevron (86 → 48) so the row is 361 of 369.
    return `<div style="${base} flex:1 1 0; border-color:oklch(0.282 0.091 267.935 / 0.90); background:oklch(0.282 0.091 267.935 / 0.30);">
    <span style="font-family:ui-monospace,Menlo,monospace; font-size:14px; font-weight:700; flex:0 0 auto;">Q4</span>
    <span style="flex:1"></span>
    <span style="position:relative; display:inline-flex; flex:0 0 auto;">${btn({ icon: ico('upload', 12), variant: 'primary', h: SYS.nested, word: false })}<span style="position:absolute; top:-3px; right:-3px; width:8px; height:8px; border-radius:999px; background:oklch(0.828 0.189 84.429); border:1.5px solid ${T.bg};"></span></span>
    ${btn({ icon: ico('refresh', 12), h: SYS.nested, word: false })}
  </div>`
  }
  return `<div style="${base} flex:1 1 0; border-color:oklch(0.282 0.091 267.935 / 0.90); background:oklch(0.282 0.091 267.935 / 0.30);">
    ${wide ? zlab('Editing', 'oklch(0.809 0.105 251.813)') : ''}${wide ? ico('download', 14, 'color:oklch(0.809 0.105 251.813)') : ''}
    <span style="font-family:ui-monospace,Menlo,monospace; font-size:14px; font-weight:700; flex:0 0 auto;">Q4</span>
    <span style="font-size:14px; font-weight:500; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; min-width:0;">Warm wash</span>
    ${cw >= 1100 ? `<span style="font-size:12px; color:oklch(0.809 0.105 251.813 / 0.80); white-space:nowrap; flex:0 0 auto;">Act 1 · cue 4 of 14</span>` : ''}
    <span style="flex:1"></span>
    ${pill(`3 changes${cw >= 1100 ? ' not written back' : ''}`, { fg: 'oklch(0.879 0.169 91.605)', bc: 'oklch(0.414 0.112 45.904)', bg: 'oklch(0.279 0.077 45.635 / 0.40)', dot: 'oklch(0.828 0.189 84.429)' })}
    ${btn({ icon: ico('upload', 12), label: cw >= 800 ? 'Update Q4' : 'Update', variant: 'primary', h: SYS.nested, word: cw >= 600 })}
    ${btn({ icon: ico('refresh', 12), label: 'Revert', h: SYS.nested, word: cw >= 1100 })}
  </div>`
}
function verbs({ mode, cw }) {
  const words = cw >= 800
  const empty = mode === 'empty'
  const clear = `<div style="display:inline-flex; align-items:stretch; height:${SYS.control}px; border-radius:6px; overflow:hidden; border:1px solid ${T.border}; flex:0 0 auto; ${empty ? 'opacity:0.5;' : ''}">
    <span style="display:inline-flex; align-items:center; gap:6px; padding:0 10px; font-size:12px; font-weight:500;">${ico('eraser', 14)}${words ? 'Clear' : ''}</span>
    ${cw >= 600
      ? `<span style="display:inline-flex; align-items:center; justify-content:space-between; gap:8px; width:86px; padding:0 10px; border-left:1px solid ${T.border}; background:oklch(0.274 0.006 286.033 / 0.40); font-family:ui-monospace,Menlo,monospace; font-size:12px;">Snap ${ico('chevD', 16, 'opacity:0.5')}</span>`
      : `<span style="display:inline-flex; align-items:center; width:48px; justify-content:center; border-left:1px solid ${T.border}; background:oklch(0.274 0.006 286.033 / 0.40); font-family:ui-monospace,Menlo,monospace; font-size:12px;">Snap</span>`}
  </div>`
  const blind = btn({ icon: ico('eyeOff', 14), label: 'Blind', word: words })
  const include = btn({ icon: ico('download', 14), label: 'Include…', word: words })
  const record = `<div style="display:inline-flex; align-items:stretch; height:${SYS.control}px; border-radius:6px; overflow:hidden; background:${T.primary}; color:${T.pfg}; flex:0 0 auto; ${empty ? 'opacity:0.5;' : ''}">
    <span style="display:inline-flex; align-items:center; gap:6px; padding:0 12px; font-size:12px; font-weight:600;">${dotFill(12)}${words ? 'Record' : ''}</span>
    <span style="display:inline-flex; align-items:center; padding:0 6px; border-left:1px solid oklch(0.21 0.006 285.885 / 0.25);">${ico('chevD', 14)}</span>
  </div>`
  return `<div style="display:flex; align-items:center; gap:${SYS.gap}px; flex:0 0 auto;">${clear}${blind}${include}${record}</div>`
}
function rowA({ mode, cw }) {
  const box = sourceBox({ mode, cw })
  const fills = mode === 'editing' || mode === 'layer'
  return `<div style="height:${SYS.row}px; flex:0 0 auto; display:flex; align-items:center; gap:${SYS.gap}px; padding:0 ${SYS.gutter}px; border-bottom:1px solid ${T.border}; background:oklch(0.21 0.006 285.885 / 0.50);">
    ${box}<span style="width:1px; height:22px; background:${T.border}; flex:0 0 auto; ${fills ? '' : 'margin-left:auto;'}"></span>${verbs({ mode, cw })}
  </div>`
}

// ---- Row B: what the grid shows ---------------------------------------------------------------
function scopeToggle({ mode, gw, compact }) {
  const words = !compact && gw >= 520
  const phoneArm = compact || gw < 600
  const item = (name, label, on, extra = '') =>
    `<span style="display:inline-flex; align-items:center; gap:6px; height:24px; padding:0 8px; border-radius:6px; font-size:14px; font-weight:500; white-space:nowrap; ${on ? `background:${T.bg}; color:${T.fg}; box-shadow:0 1px 2px rgba(0,0,0,0.3);` : `color:${T.mfg};`}${extra}">${ico(name, 14)}${words ? `<span>${label}</span>` : ''}</span>`
  const layerOn = mode === 'layer'
  const layerPill = layerOn
    ? `<span style="position:relative; display:inline-flex; align-items:center; gap:6px; height:24px; padding:0 8px; border-radius:6px; font-size:12px; font-weight:500; white-space:nowrap; max-width:${phoneArm ? 120 : 220}px; background:${T.bg}; color:${T.fg}; box-shadow:0 1px 2px rgba(0,0,0,0.3);">${ico('layers', 12)}<span style="overflow:hidden; text-overflow:ellipsis;">Warm Wash</span>${phoneArm ? `<span title="Unsaved" style="position:absolute; top:-3px; right:-3px; width:8px; height:8px; border-radius:999px; background:${T.mfg}; border:1.5px solid ${T.muted};"></span>` : ''}</span>`
    : ''
  return `<div style="display:inline-flex; align-items:center; height:${SYS.control}px; padding:4px; border-radius:8px; background:${T.muted}; color:${T.mfg}; flex:0 0 auto;">${item('eye', 'Output', false)}${item('hand', 'Local', mode !== 'layer' && true && !layerOn)}${layerPill}</div>`
}
function filterField({ gw, folded }) {
  // Proposed: the field at every width from 360 up (unfolded), with the short placeholder below 800.
  // Folded (short-height) arm keeps today's rule: the field only from 840, the search icon below.
  const showField = folded ? gw >= 840 : gw >= 360
  if (!showField) return btn({ icon: ico('search', 14) })
  const placeholder = gw >= 800 ? 'Filter fixtures…' : 'Filter…'
  // flex-grow 999 against the spacer's 1: the field takes the slack first and the spacer only what
  // the 340 cap leaves — two plain flex-1 siblings split it and shorted the field by half (the bug
  // ProgrammerGrid.tsx already names beside the @[800px] note).
  const width = folded ? 'flex:1 1 0; min-width:132px;' : 'flex:999 1 0; min-width:0; max-width:340px;'
  return `<div style="position:relative; ${width} height:${SYS.control}px; display:flex; align-items:center; border-radius:6px; border:1px solid ${T.border}; background:oklch(0.274 0.006 286.033 / 0.30); padding-left:36px; font-size:14px; color:${T.mfg}; overflow:hidden; white-space:nowrap;">${ico('search', 16, `position:absolute; left:12px; color:${T.mfg}`)}${placeholder}</div>`
}
function rowB({ mode, gw, vw, folded = false, leading = null, short = false }) {
  const compact = folded
  const sm = vw >= 640 && !compact
  const layerDetail = mode === 'layer' && gw >= 900 && !compact
    ? `<span style="font-size:12px; color:${T.mfg}; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; min-width:0;">asserts Colour · 4 targets</span>`
    : ''
  // Below 600 the save state is the dot on the layer pill (Unsaved and Saving… only — Save failed
  // keeps its word at every width, since that is the one that must never be a casualty of width).
  const saveState = mode === 'layer' && !(compact || gw < 600) ? `<span style="font-size:12px; color:${T.mfg}; white-space:nowrap; flex:0 0 auto;">Unsaved</span>` : ''
  const lit = btn({ icon: ico('bulb', 14), label: 'Lit', word: sm })
  const groups = btn({ icon: ico('layers', 14), label: 'Groups', word: !compact && gw >= 800 })
  const columns = btn({ icon: ico('columns', 14), label: 'Columns', word: sm })
  // The key explains the ownership tints, and ownership is switched off in layer scope — so the
  // button has nothing to explain there and is not drawn.
  const key = (gw < 600 || short) && mode !== 'layer' ? btn({ icon: ico('key', 14) }) : ''
  const tools = `${scopeToggle({ mode, gw, compact })}${layerDetail}${saveState}${filterField({ gw, folded })}${lit}${folded ? '' : spacer()}${groups}${columns}${key}`
  const lead = leading
    ? `<div style="display:flex; align-items:center; gap:${SYS.gap}px; min-width:min(410px,100%); flex:${mode === 'editing' || mode === 'layer' ? '1 1 0' : '0 1 auto'};">${leading}</div>${divider()}`
    : ''
  return `<div style="min-height:${SYS.row}px; flex:0 0 auto; display:flex; align-items:center; gap:${SYS.gap}px; padding:0 ${SYS.gutter}px; border-bottom:1px solid ${T.border};">${lead}${folded ? `<div style="display:flex; align-items:center; gap:${SYS.gap}px; flex:0 0 auto;">${tools}</div>` : tools}</div>`
}

// ---- Row C: the selection bar, carrying the templates ---------------------------------------
function chip({ swatch, name, pct }) {
  return `<span style="display:inline-flex; align-items:center; gap:6px; height:${SYS.nested}px; padding:0 8px; border-radius:6px; border:1px solid ${T.border}; background:${T.card}; font-size:11.5px; white-space:nowrap; flex:0 0 auto;">${swatch ? `<span style="width:12px; height:12px; border-radius:3px; background:${swatch}; border:1px solid oklch(0.274 0.006 286.033 / 0.60);"></span>` : ''}${pct ? `<span style="font-family:ui-monospace,Menlo,monospace; font-size:10px; color:${T.mfg};">${pct}</span>` : ''}<span>${name}</span></span>`
}
function rowC({ mode, gw, vw }) {
  if (mode === 'empty') {
    return `<div style="height:${SYS.row}px; flex:0 0 auto; display:flex; align-items:center; gap:${SYS.gap}px; padding:0 ${SYS.gutter}px; border-bottom:1px solid ${T.border}; color:${T.mfg};">${ico('marquee', 14, 'opacity:0.6')}<span style="font-size:12px;">Nothing selected</span></div>`
  }
  const phone = gw < 600
  const words = vw >= 640 && gw >= 1100
  const sel = mode === 'busking'
    ? { fixtures: 3, cells: 3, family: 'Intensity', chips: [chip({ pct: '50%', name: 'S5 Dim50' }), chip({ pct: '100%', name: 'Full' })] }
    : { fixtures: 4, cells: 4, family: 'Colour', chips: [chip({ swatch: 'oklch(0.75 0.16 60)', name: 'Warm wash' }), chip({ swatch: 'oklch(0.8 0.17 80)', name: 'Amber' }), chip({ swatch: 'oklch(0.55 0.2 300)', name: 'UV blue' })] }
  const counts = `${ico('marquee', 14)}${phone ? '' : `<span style="font-size:12px; font-weight:600; font-variant-numeric:tabular-nums; white-space:nowrap;">${sel.fixtures} fixtures</span><span style="color:oklch(0.705 0.015 286.067 / 0.50);">·</span>`}<span style="font-size:12px; font-weight:600; font-variant-numeric:tabular-nums; white-space:nowrap;">${sel.cells} cells</span>${phone ? '' : pill(sel.family)}${gw >= 1100 ? `<span style="display:inline-flex; align-items:center; gap:4px; font-size:10px; color:${T.mfg}; white-space:nowrap;"><kbd style="border-radius:4px; border:1px solid ${T.border}; background:oklch(0.274 0.006 286.033 / 0.50); padding:0 6px; font-size:9.5px; font-family:ui-monospace,Menlo,monospace;">⏎</kbd> edit <kbd style="border-radius:4px; border:1px solid ${T.border}; background:oklch(0.274 0.006 286.033 / 0.50); padding:0 6px; font-size:9.5px; font-family:ui-monospace,Menlo,monospace; margin-left:4px;">⌫</kbd> clear</span>` : ''}`
  const strip = `${divider()}<div style="display:flex; align-items:center; gap:6px; flex:1 1 0; min-width:0; overflow:hidden; -webkit-mask-image:linear-gradient(to right, black calc(100% - 24px), transparent); mask-image:linear-gradient(to right, black calc(100% - 24px), transparent);">${sel.chips.join('')}</div>${btn({ icon: ico('plus', 14), label: 'New', h: SYS.nested, extra: `border-style:dashed; background:transparent;` })}`
  const toolbar = `<div style="display:flex; align-items:center; gap:${vw >= 640 ? 8 : 6}px; flex:0 0 auto; margin-left:auto;">
    ${gw >= 1100 ? `<span style="font-size:12px; color:${T.mfg}; font-variant-numeric:tabular-nums;">${sel.fixtures}${vw >= 640 ? ' selected' : ''}</span>` : ''}
    ${btn({ icon: ico('pencil', 14), label: 'Set', word: words })}${btn({ icon: ico('backspace', 14), label: 'Clear', word: words })}
    ${phone ? '' : btn({ icon: ico('fan', 14), label: 'Fan', word: words })}
    ${phone ? '' : btn({ icon: ico('crosshair', 14), label: 'Locate', word: words })}${phone ? '' : btn({ icon: ico('flashlight', 14), label: 'Highlight', word: words })}
    ${btn({ icon: ico('x', 14), label: 'Deselect', variant: 'ghost', word: vw >= 640 })}
  </div>`
  return `<div style="height:${SYS.row}px; flex:0 0 auto; display:flex; align-items:center; gap:${SYS.gap}px; padding:0 ${SYS.gutter}px; border-bottom:1px solid ${T.border}; background:oklch(0.985 0 0 / 0.05);">${counts}${strip}${toolbar}</div>`
}

// ---- the grid: column header and two rows -----------------------------------------------------
const COLS = ['Dimmer', 'Colour', 'Position', 'Gobo', 'Zoom', 'Strobe', 'Focus', 'Iris', 'Prism', 'Frost']
function grid({ gw, phone, mode }) {
  const nameW = phone ? Math.min(Math.round(gw * 0.45), 260) : 260
  const avail = gw - nameW
  const n = phone ? Math.max(2, Math.ceil(avail / 130)) : Math.min(COLS.length, Math.floor(avail / 96))
  const colW = phone ? 130 : Math.floor(avail / n)
  const hdr = `<div style="height:30px; flex:0 0 auto; display:flex; border-bottom:1px solid ${T.border}; background:${T.bg}; overflow:hidden;">
    <span style="width:${nameW}px; flex:0 0 auto; padding:0 8px; display:flex; align-items:center; font-size:11px; font-weight:500; text-transform:uppercase; letter-spacing:0.05em; color:${T.mfg};">Fixture</span>
    ${COLS.slice(0, n).map((c) => `<span style="width:${colW}px; flex:0 0 auto; padding:0 6px; display:flex; align-items:center; font-size:11px; font-weight:500; text-transform:uppercase; letter-spacing:0.05em; color:${T.mfg};">${c}</span>`).join('')}
  </div>`
  const dash = `<span style="width:${colW}px; flex:0 0 auto; padding:0 8px; font-size:12px; color:oklch(0.705 0.015 286.067 / 0.60);">—</span>`
  const bar = (pct, own) => `<span style="width:${colW}px; flex:0 0 auto; padding:2px 1px; height:100%; display:flex;"><span style="flex:1; display:flex; align-items:center; gap:6px; padding:0 8px; border-radius:4px; ${own === 'you' ? `box-shadow:inset 0 0 0 1px ${T.primary}; background:oklch(0.623 0.214 259.815 / 0.10);` : own === 'cue' ? `box-shadow:inset 0 0 0 1px oklch(0.685 0.169 237.323 / 0.40);` : ''}"><span style="position:relative; flex:1; height:6px; border-radius:999px; background:${T.muted}; overflow:hidden;"><span style="position:absolute; inset:0 auto 0 0; width:${pct}%; background:${T.primary}; border-radius:999px;"></span></span><span style="width:32px; text-align:right; font-size:12px; color:${T.mfg};">${pct}</span></span></span>`
  const swatchCell = (colour, own, sel) => `<span style="width:${colW}px; flex:0 0 auto; padding:2px 1px; height:100%; display:flex;"><span style="flex:1; display:flex; align-items:center; gap:6px; padding:0 8px; border-radius:4px; ${sel ? `box-shadow:inset 0 0 0 1px ${T.fg}; background:oklch(0.985 0 0 / 0.06);` : ''}${own === 'you' && !sel ? `box-shadow:inset 0 0 0 1px ${T.primary}; background:oklch(0.623 0.214 259.815 / 0.10);` : ''}"><span style="width:16px; height:16px; border-radius:4px; background:${colour}; border:1px solid ${T.border};"></span><span style="font-size:12px; color:${T.mfg};">${own === 'mixed' ? 'Mixed' : ''}</span></span></span>`
  const busk = mode === 'busking'
  const edit = mode === 'editing' || mode === 'layer'
  const row = (name, cells, sel) => `<div style="height:36px; flex:0 0 auto; display:flex; align-items:center; border-bottom:1px solid ${T.border}; font-size:14px; ${sel ? `background:oklch(0.985 0 0 / 0.06); box-shadow:inset 3px 0 0 ${T.fg};` : ''}">
    <span style="width:${nameW}px; flex:0 0 auto; padding:0 8px; display:flex; align-items:center; gap:6px; height:100%; overflow:hidden; white-space:nowrap; text-overflow:ellipsis; ${sel ? 'font-weight:600;' : ''}">${name}</span>${cells}
  </div>`
  const r1 = row('Freedom Par Hex', (busk ? bar(50, 'you') : edit ? bar(80, 'cue') : dash) + (edit ? swatchCell('oklch(0.75 0.16 60)', 'you', edit) : dash) + dash.repeat(Math.max(0, n - 2)), busk || edit)
  const r2 = row('LED Lightbar 12 Pixel', (busk ? bar(50, 'you') : dash) + (edit ? swatchCell('oklch(0.75 0.16 60)', 'you', edit) : swatchCell('oklch(0.55 0.25 320)', 'mixed', false)) + dash.repeat(Math.max(0, n - 2)), busk || edit)
  return hdr + r1 + r2
}
function footer({ gw, mode }) {
  const sel = mode === 'empty' ? '' : ` · ${mode === 'busking' ? 3 : 4} selected`
  const k = (color, t, hollow = false) => `<span style="display:inline-flex; align-items:center; gap:6px; flex:0 0 auto;"><span style="width:10px; height:10px; border-radius:999px; ${hollow ? `border:1px solid ${color};` : `border:1px solid ${color}; background:${color}22;`}"></span>${t}</span>`
  return `<div style="height:22px; flex:0 0 auto; display:flex; align-items:center; gap:12px; padding:0 ${SYS.gutter}px; border-top:1px solid ${T.border}; font-size:10.5px; color:${T.mfg}; white-space:nowrap; overflow:hidden;">
    <span style="font-variant-numeric:tabular-nums;">7 fixtures${sel}</span>${gw >= 420 ? `<span style="font-weight:500;">Owned by</span>` : ''}${k(T.primary, 'You')}${k(T.sky, 'Cue')}${k(T.violet, 'Effect')}${k(T.amber, 'Parked')}${k('oklch(0.45 0.01 286)', 'Nothing asserts it', true)}
    ${gw >= 520 ? `<span style="margin-left:auto; display:inline-flex; align-items:center; gap:6px;">${ico('layers', 12)}from a layer</span>` : ''}
  </div>`
}

// ---- the rail: docked (300), strip (40), phone handle (44) ------------------------------------
function railHeader({ layers, fx, docked }) {
  return `<div style="height:${SYS.row}px; flex:0 0 auto; display:flex; align-items:center; gap:10px; padding:0 ${SYS.gutter}px; border-bottom:1px solid ${T.border};">
    <span style="display:inline-flex; align-items:center; gap:6px;">${ico('layers', 12)}${zlab('Layers', T.fg)}${countBadge(layers)}</span>
    <span style="display:inline-flex; align-items:center; gap:6px; color:oklch(0.709 0.164 293.541);">${ico('wave', 12)}${zlab('FX', 'oklch(0.709 0.164 293.541)')}${countBadge(fx)}</span>
    <span style="flex:1"></span>
    <span style="width:24px; height:24px; display:flex; align-items:center; justify-content:center; color:${T.mfg};">${ico(docked ? 'chevR' : 'chevL', 14)}</span>
  </div>`
}
function railDocked({ mode, height }) {
  const has = mode === 'editing' || mode === 'layer'
  const layerRow = (name, on, badge) => `<div style="display:flex; align-items:center; gap:7px; padding:6px 8px; border-radius:6px; border:1px solid ${on ? 'oklch(0.45 0.16 300)' : T.border}; background:${on ? 'oklch(0.27 0.09 302 / 0.55)' : T.card}; font-size:12px;">${countBadge(badge)}<span style="display:inline-flex; align-items:center; gap:5px; font-weight:600; min-width:0; overflow:hidden; white-space:nowrap;">${ico('layers', 12)}<span style="overflow:hidden; text-overflow:ellipsis;">${name}</span></span><span style="margin-left:auto; font-size:9.5px; color:${T.mfg};">Colour · 4</span></div>`
  return `<aside style="width:300px; flex:0 0 auto; height:${height}px; display:flex; flex-direction:column; border-left:1px solid ${T.border}; background:color-mix(in oklab, ${T.card} 40%, ${T.bg}); overflow:hidden;">
    ${railHeader({ layers: has ? 2 : 0, fx: has ? 1 : 0, docked: true })}
    <div style="display:flex; flex-direction:column; gap:6px; padding:8px 10px; overflow:hidden;">
      <div style="display:flex; align-items:center; gap:6px; padding:0 2px;">${zlab('Values', T.primary)}<span style="font-size:9.5px; color:${T.mfg};">· top wins</span></div>
      <div style="display:flex; align-items:center; gap:7px; padding:6px 8px; border-radius:6px; border:1px solid ${T.primary}; background:oklch(0.623 0.214 259.815 / 0.12); font-size:12px;">${ico('hand', 14, `color:${T.primary}`)}<span style="display:flex; flex-direction:column; min-width:0; flex:1;"><span style="font-weight:600;">Local values</span><span style="font-size:10px; color:oklch(0.623 0.214 259.815 / 0.90);">${mode === 'empty' ? 'Nothing yet' : mode === 'busking' ? '12 values · 3 heads' : '3 values · 4 heads'}</span></span>${mode === 'empty' ? '' : btn({ icon: ico('layers', 12), label: 'Make layer', h: 22, extra: 'font-size:10px; padding:0 6px;' })}</div>
      ${has ? layerRow('Warm Wash', mode === 'layer', 2) + layerRow('Front Focus', false, 1) : `<span style="font-size:11px; color:${T.mfg}; padding:2px;">No layers</span>`}
    </div>
  </aside>`
}
function railStrip({ mode, height }) {
  const has = mode === 'editing' || mode === 'layer'
  const door = (name, n, color = T.mfg) => `<span style="display:flex; flex-direction:column; align-items:center; gap:2px; width:40px; padding:8px 0; color:${color};">${ico(name, 14)}${countBadge(n)}</span>`
  return `<aside style="width:40px; flex:0 0 auto; height:${height}px; display:flex; flex-direction:column; align-items:center; border-left:1px solid ${T.border}; background:oklch(0.21 0.006 285.885 / 0.40);">
    <span style="width:40px; height:${SYS.row}px; display:flex; align-items:center; justify-content:center; border-bottom:1px solid ${T.border}; color:${T.mfg};">${ico('chevL', 14)}</span>
    ${door('layers', has ? 2 : 0)}${door('wave', has ? 1 : 0, 'oklch(0.709 0.164 293.541)')}
    <span style="flex:1"></span>
    <span style="width:40px; height:36px; display:flex; align-items:center; justify-content:center; color:${T.mfg};">${ico('plus', 14)}</span>
  </aside>`
}
function railHandle({ mode }) {
  const has = mode === 'editing' || mode === 'layer'
  return `<div style="height:44px; flex:0 0 auto; display:flex; align-items:center; gap:10px; padding:0 ${SYS.gutter}px; border-top:1px solid ${T.border}; background:oklch(0.21 0.006 285.885 / 0.40);">
    <span style="display:inline-flex; align-items:center; gap:6px; padding:4px;">${ico('layers', 12)}${zlab('Layers', T.fg)}${countBadge(has ? 2 : 0)}</span>
    <span style="display:inline-flex; align-items:center; gap:6px; padding:4px; color:oklch(0.709 0.164 293.541);">${ico('wave', 12)}${zlab('FX', 'oklch(0.709 0.164 293.541)')}${countBadge(has ? 1 : 0)}</span>
    <span style="flex:1; min-width:0; font-size:11px; color:${T.mfg}; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">${has ? 'Warm Wash · Front Focus' : 'No layers'}</span>
    <span style="width:32px; height:32px; display:flex; align-items:center; justify-content:center; color:${T.fg};">${ico('plus', 16)}</span>
    <span style="width:32px; height:32px; display:flex; align-items:center; justify-content:center; color:${T.mfg};">${ico('chevU', 16)}</span>
  </div>`
}

// ---- sidebar rail (collapsed drawer, 64px, at ≥768 viewport) --------------------------------
function sidebar(height) {
  const items = ['menu', 'grid', 'layers', 'palette', 'sparkles', 'slidersV', 'theater', 'book', 'wave']
  return `<div style="width:64px; flex:0 0 auto; height:${height}px; border-right:1px solid ${T.border}; background:${T.bg}; display:flex; flex-direction:column; align-items:center; padding-top:8px; gap:8px; overflow:hidden;">${items.map((n, i) => `<span style="width:40px; height:36px; border-radius:6px; display:flex; align-items:center; justify-content:center; color:${n === 'slidersV' ? T.fg : T.mfg}; ${n === 'slidersV' ? `background:${T.muted};` : ''}">${ico(n, 20)}</span>`).join('')}</div>`
}

// ---- a chrome strip for one device × one mode --------------------------------------------------
const MODES = {
  empty: { title: 'Nothing loaded', sub: 'No source · nothing selected · Clear and Record disabled' },
  busking: { title: 'Busking', sub: '12 values in Local · three dimmer cells selected · intensity templates offered' },
  editing: { title: 'Editing a cue', sub: 'Q4 included with 3 changes · four colour cells selected' },
  layer: { title: 'A layer focused', sub: 'Scope on the Warm Wash layer · a live write, said out loud' },
}
function strip({ device, mode }) {
  const { vw, cw, gw, rail, short = false } = device
  const phone = vw < 640
  let column = ''
  const workspaceRows = (h) => (rail === 'docked' ? railDocked({ mode, height: h }) : rail === 'strip' ? railStrip({ mode, height: h }) : '')
  const gridCol = (extra = '') => `<div style="display:flex; flex-direction:column; flex:1 1 0; min-width:0;">${extra}${rowC({ mode, gw, vw })}${grid({ gw, phone: vw < 640 || gw < 600, mode })}${gw >= 600 && !short ? footer({ gw, mode }) : ''}</div>`
  if (short) {
    // folded arm: row A's two halves lead row B, on one 40px line
    const leading = sourceBox({ mode, cw: 420 }) + `<span style="width:1px; height:22px; background:${T.border}; flex:0 0 auto;"></span>` + verbs({ mode, cw: 420 })
    const wsH = SYS.row + SYS.row + 30 + 72
    column = `${appHeader({ vw, cw })}${showHeader({ cw })}<div style="display:flex; flex:0 0 auto;">${gridCol(rowB({ mode, gw, vw, folded: true, leading, short: true }))}${workspaceRows(wsH)}</div>`
  } else {
    const wsH = SYS.row + SYS.row + 30 + 72 + (gw >= 600 ? 22 : 0)
    column = `${appHeader({ vw, cw })}${showHeader({ cw })}${rowA({ mode, cw })}<div style="display:flex; flex:0 0 auto;">${gridCol(rowB({ mode, gw, vw }))}${workspaceRows(wsH)}</div>${rail === 'handle' ? railHandle({ mode }) : ''}`
  }
  const body = `<div style="display:flex; flex-direction:column; flex:1 1 0; min-width:0; background:${T.bg};">${column}</div>`
  const h = short ? 52 + SYS.header + SYS.row + SYS.row + 30 + 72 : 52 + SYS.header + SYS.row + SYS.row + SYS.row + 30 + 72 + (gw >= 600 ? 22 : 0) + (rail === 'handle' ? 44 : 0)
  return { html: `<div style="display:flex; width:${vw}px; height:${h}px; overflow:hidden; border:1px solid ${T.border};">${vw >= 768 ? sidebar(h) : ''}${body}</div>`, h }
}

// ---- artboard wrappers ---------------------------------------------------------------------------
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
function modeLabel(mode) {
  const m = MODES[mode]
  return `<div style="display:flex; align-items:baseline; gap:10px; padding:0 2px 8px;"><span style="font-size:12px; font-weight:700; text-transform:uppercase; letter-spacing:0.08em; color:${T.fg};">${m.title}</span><span style="font-size:11px; color:${T.mfg};">${m.sub}</span></div>`
}
function deviceBoard(device) {
  const modes = ['empty', 'busking', 'editing', 'layer']
  const parts = []
  let total = 24
  for (const mode of modes) {
    const s = strip({ device, mode })
    parts.push(`<div style="display:flex; flex-direction:column;">${modeLabel(mode)}${s.html}</div>`)
    total += 26 + s.h + 40
  }
  const w = device.vw + 48
  const html = `${HEAD}
<div class="app" style="width:${w}px; height:${total}px; padding:24px; display:flex; flex-direction:column; gap:40px;">
${parts.join('\n')}
</div>
${TAIL}`
  return { html, w, h: total }
}

// ---- devices -------------------------------------------------------------------------------
// vw = viewport; cw = content column (viewport less the 64px sidebar rail at ≥768); gw = grid column
// (content less the rail: 300 docked at ≥1200 of workspace, 40 strip below, nothing on a phone).
const DEVICES = {
  Main: { name: 'Desktop · 1440 wide', vw: 1440, cw: 1376, gw: 1076, rail: 'docked' },
  TabletLandscape: { name: 'iPad landscape · 1180 wide', vw: 1180, cw: 1116, gw: 1076, rail: 'strip' },
  TabletPortrait: { name: 'iPad portrait · 820 wide', vw: 820, cw: 756, gw: 716, rail: 'strip' },
  Phone: { name: 'iPhone · 393 wide', vw: 393, cw: 393, gw: 393, rail: 'handle' },
  PhoneLandscape: { name: 'iPhone landscape · 852×393 · short-height fold', vw: 852, cw: 788, gw: 748, rail: 'strip', short: true },
}

const boards = {}
for (const [file, device] of Object.entries(DEVICES)) {
  const b = deviceBoard(device)
  writeFileSync(`${file}.dc.html`, b.html)
  boards[file] = { w: b.w, h: b.h, title: device.name }
}

// ---- Spec artboard: the system, and today → proposed -----------------------------------------------
function spec() {
  const rows = [
    ['Gutter (left and right inset of a row)', 'ShowHeader 16 · rows A/B/C 12 · rail header 10', '12 everywhere, header included'],
    ['ShowHeader height', '64 (32px controls + 16 above and below); 48 only under 500px of height', '48 at every height (py-2)'],
    ['Row A', '40, controls 32 → 4px inset', '40, controls 32 — unchanged'],
    ['Row B', '36; toggle group, filter, Lit, Columns 32 · Groups, search, key 28 → 2px and 4px insets on one line', '40; every control 32 → one 4px inset'],
    ['Row C (selection bar)', '34; verbs 32 · chips and New 26 → 1px inset', '40; verbs 32 · chips and New 28 (nested tier)'],
    ['Rail header / strip chevron', '36, level with row B', '40, level with row B'],
    ['Gap between controls', '8 (rows) · 6 or 8 (toolbar, by viewport) · 10 (rail)', '8 on every row; 6 inside a control'],
    ['Control tiers', '32 / 28 / 26 / 24, mixed per row', '32 control · 28 nested (Update, Revert, a chip) · 24 toggle item · 20 pill'],
    ['Row B filter', 'Field at ≥800 of row B, a search icon below → an empty middle on iPad portrait and phones; and the field splits the slack with the spacer, so it is half the width it could be', 'Field at ≥360 with "Filter…" below 800; the field takes the slack before the spacer does (grow 999 vs 1), capped at 340'],
    ['Right edge', 'Header 16 · row A 12 · rows B/C 12 before the rail — four edges', 'Header and row A end at the page\'s 12; rows B/C at the grid column\'s 12; the strip is its own column'],
    ['Row A on a phone, with a cue included', 'The verbs are 285px iconic, so the source box gets 67px of a 393 row (117 on a landscape phone folded) for content that needs ~200 — Update is clipped to a sliver', 'Below 600: the box is Q4 · Update · Revert (116px); name and count ride Update\'s title, the dirty state is an amber dot on Update; the fade trigger keeps its value and drops its chevron (86 → 48). 116 + 17 + 228 = 361 of 369'],
    ['Row B in layer scope on a phone', 'Two scope pills, the layer pill (max 220), "Unsaved", search, Lit, Groups, Columns and the key: ~470px on a 369px row, so the right end is clipped', 'Below 600: the layer pill is capped at 120 and carries Unsaved / Saving… as a dot (Save failed keeps its word); the key is not drawn in layer scope, since ownership tints are off there. 336 of 369'],
    ['Chrome above the first fixture row (desktop)', '52 + 64 + 40 + 36 + 34 + 30 = 256', '52 + 48 + 40 + 40 + 40 + 30 = 250'],
  ]
  const table = `<table style="border-collapse:collapse; width:100%; font-size:12px; line-height:1.4;">
    <thead><tr>${['', 'Today', 'Proposed'].map((h, i) => `<th style="text-align:left; padding:6px 10px; border-bottom:1px solid ${T.border}; font-size:10px; font-weight:700; text-transform:uppercase; letter-spacing:0.1em; color:${T.mfg}; ${i === 0 ? 'width:170px;' : ''}">${h}</th>`).join('')}</tr></thead>
    <tbody>${rows.map(([k, a, b]) => `<tr><td style="padding:7px 10px; border-bottom:1px solid ${T.border}; font-weight:600; vertical-align:top;">${k}</td><td style="padding:7px 10px; border-bottom:1px solid ${T.border}; color:${T.mfg}; vertical-align:top;">${a}</td><td style="padding:7px 10px; border-bottom:1px solid ${T.border}; vertical-align:top;">${b}</td></tr>`).join('')}</tbody>
  </table>`
  const rules = [
    ['One gutter', '12px on every row of the programmer, the ShowHeader included. The header is the one row that was 16, and it is the row whose left edge every other row is read against.'],
    ['One row height', 'Every chrome row is 40px and holds 32px controls, so the inset is 4px above and below on every line. Row B was 36 with 28px and 32px controls side by side; row C was 34 with 32px buttons beside 26px chips.'],
    ['Three tiers, by nesting', 'A control on a row is 32. A control inside a control is 28: Update and Revert in the source box, a template chip in the scroller, New beside it. A toggle item is 24 inside its 32 group. A pill is 20. Nothing else.'],
    ['The header loses 16px', 'py-2 (48px) at every height, not only under 500px. The short-height arm already proved the row reads fine at that size, and on a phone it was the fattest row on the screen with the least in it.'],
    ['The field takes the room', 'Row B\'s filter is a field from 360px of row up, with "Filter…" as its placeholder below 800 and the full one above. The field is the only elastic thing on the row, so the middle can never be a hole — the reasoning the folded arm already uses.'],
    ['Two right edges, not four', 'The header and row A are the page\'s and end 12px from its edge; rows B and C are the grid column\'s and end 12px before the rail. The rail strip is its own 40px column, chevron level with row B.'],
    ['The phone arm sheds the right things', 'With a cue included, row A cannot hold a name, a count, two verbs and 285px of bar in 369px — today Update is clipped to a sliver. Below 600 the box keeps the number and its two verbs and puts the count on Update as a dot; the fade trigger keeps its value (it has to be read before Clear) and loses only its chevron.'],
    ['Words fold on one ladder per row', 'Unchanged thresholds, restated: header trail 640 / switcher 820 / Stop 420; row A words 800, phone arm 600; row B scope words 520, Groups 800, Lit and Columns at sm; row C phone arm 600, words 1100.'],
  ]
  const rulesHtml = rules.map(([h, p]) => `<div style="display:flex; flex-direction:column; gap:3px;"><span style="font-size:12px; font-weight:700;">${h}</span><p style="margin:0; font-size:11.5px; line-height:1.5; color:${T.mfg};">${p}</p></div>`).join('')

  // an annotated strip: busking at a 1000px content column with rulers
  const dev = { vw: 1000, cw: 936, gw: 896, rail: 'strip' }
  const s = strip({ device: dev, mode: 'busking' })
  const heights = [['App header', 52], ['Show header', 48], ['Row A', 40], ['Row B', 40], ['Row C', 40], ['Columns', 30], ['Rows', 72], ['Footer', 22]]
  let y = 0
  const ruler = heights.map(([n, h]) => { const top = y; y += h; return `<div style="position:absolute; top:${top}px; height:${h}px; left:0; width:120px; border-top:1px dashed oklch(0.45 0.01 286); display:flex; align-items:center; gap:6px; padding-left:8px; font-size:10px; color:${T.mfg}; white-space:nowrap;"><span style="font-family:ui-monospace,Menlo,monospace; font-weight:700; color:${T.fg}; width:22px;">${h}</span>${n}</div>` }).join('')
  const html = `${HEAD}
<div class="app" style="width:1180px; height:1400px; padding:28px 32px; display:flex; flex-direction:column; gap:22px;">
  <div style="display:flex; flex-direction:column; gap:4px;">
    <span style="font-size:20px; font-weight:700;">Programmer chrome — the system</span>
    <span style="font-size:12px; color:${T.mfg};">One gutter, one row height, three control tiers by nesting, and the field takes the slack. Every number below is measured against the components under <span style="font-family:ui-monospace,Menlo,monospace;">src/components/programmer/</span>.</span>
  </div>
  <div style="display:grid; grid-template-columns:repeat(2, minmax(0, 1fr)); gap:28px;">
    <div style="display:flex; flex-direction:column; gap:14px;">${rulesHtml}</div>
    <div>${table}</div>
  </div>
  <div style="display:flex; flex-direction:column; gap:8px;">
    <span style="font-size:12px; font-weight:700; text-transform:uppercase; letter-spacing:0.08em;">The rhythm, busking, at a 1000px viewport</span>
    <div style="display:flex; gap:12px;">
      <div style="position:relative; width:120px; height:${s.h}px; flex:0 0 auto;">${ruler}</div>
      <div style="flex:0 0 auto;">${s.html}</div>
    </div>
    <div style="display:flex; gap:24px; padding-left:132px; font-size:11px; color:${T.mfg};"><span><b style="color:${T.fg}; font-family:ui-monospace,Menlo,monospace;">12</b> gutter, every row</span><span><b style="color:${T.fg}; font-family:ui-monospace,Menlo,monospace;">8</b> between controls</span><span><b style="color:${T.fg}; font-family:ui-monospace,Menlo,monospace;">4</b> above and below a 32px control in a 40px row</span><span><b style="color:${T.fg}; font-family:ui-monospace,Menlo,monospace;">22</b> hairline divider, self-centred</span></div>
  </div>
</div>
${TAIL}`
  writeFileSync('Spec.dc.html', html)
  return { w: 1180, h: 1400 }
}
boards.Spec = { ...spec(), title: 'The system · today → proposed' }

// ---- canvas.json ----------------------------------------------------------------------------------
const GAPX = 100, GAPY = 140
const layout = [
  ['Spec', 0, 0],
  ['Main', boards.Spec.w + GAPX, 0],
]
let y2 = Math.max(boards.Spec.h, boards.Main.h) + GAPY
layout.push(['TabletLandscape', 0, y2], ['TabletPortrait', boards.TabletLandscape.w + GAPX, y2])
let y3 = y2 + Math.max(boards.TabletLandscape.h, boards.TabletPortrait.h) + GAPY
layout.push(['Phone', 0, y3], ['PhoneLandscape', boards.Phone.w + GAPX, y3])
const canvas = {
  artboards: layout.map(([file, x, y]) => ({ file: `${file}.dc.html`, title: boards[file].title, x, y, w: boards[file].w, h: boards[file].h })),
  annotations: [
    {
      id: 'brief',
      x: 0,
      y: -300,
      w: 720,
      text:
        'Programmer chrome — a tidy-up, not a redesign.\n\nEvery row keeps its job and its controls; what changes is the spacing system: a 12px gutter on every row (the header included), 40px rows holding 32px controls so the inset is 4px everywhere, three control tiers by nesting (32 · 28 · 24, pills 20), the header at 48px at every height, and row B\'s filter as a field wherever the row is 360px or wider so the middle is never a hole.\n\nEach device artboard stacks the four operating modes: nothing loaded, busking, editing a cue, a layer focused. The Spec sheet has the measured today → proposed table.\n\nAssumed: static mockups; dark only (read at a desk); the app header is drawn as it is and left alone; Show, Prompt Book and Busk inherit the 48px header, since ShowHeader is shared.',
    },
  ],
  launch: { view: 'canvas' },
}
writeFileSync('canvas.json', JSON.stringify(canvas, null, 2) + '\n')
console.log(Object.entries(boards).map(([k, b]) => `${k} ${b.w}×${b.h}`).join('\n'))
