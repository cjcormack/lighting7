import type { FxPreviewFx } from '@/store/fxPreview'
import type { GraphViewChoice } from '@/lib/fxEditorView'
import type { GraphMark } from '@/components/fx/fxGraphLink'
import { mixSeries, type DecodedAttributeRow, type DecodedLane, type DecodedSeries } from './fxPreviewDecode'
import {
  SAMPLES_PER_CYCLE,
  colourAt,
  ladderBrackets,
  levelScale,
  planeAt,
  rowStarts,
  rungRow,
  sampleX,
  wingBreaks,
  type LaidRow,
  type LaidSection,
  type LaneLayout,
} from './fxGraphModel'

/**
 * **The graph's drawing** (FX editor plan §4.4): a lane, the path panel and its dots, drawn from the
 * desk's samples onto anything with a 2D context's drawing calls — a canvas in the editor, a pixel
 * stub in the tests. Pure in its inputs; nothing here computes an FX's output (P2).
 */

export type GraphCtx = Pick<
  CanvasRenderingContext2D,
  | 'fillStyle'
  | 'strokeStyle'
  | 'lineWidth'
  | 'font'
  | 'textBaseline'
  | 'textAlign'
  | 'fillRect'
  | 'clearRect'
  | 'beginPath'
  | 'moveTo'
  | 'lineTo'
  | 'closePath'
  | 'rect'
  | 'arc'
  | 'fill'
  | 'stroke'
  | 'clip'
  | 'save'
  | 'restore'
  | 'setLineDash'
  | 'fillText'
>

export interface GraphPalette {
  text: string
  dim: string
  grid: string
  stripe: string
  area: string
  resultLine: string
  picked: string
  pickedArea: string
  underneath: string
  start: string
  bracket: string
  holding: string
  heldBack: string
  pan: string
  tilt: string
  stepA: string
  stepB: string
  stepText: string
  /** A mark a hovered control changes, and a shade over what it covers (§4.5). */
  highlight: string
  highlightArea: string
}

export const LIGHT_PALETTE: GraphPalette = {
  text: '#3f3f46',
  dim: '#71717a',
  grid: '#e4e4e7',
  stripe: 'rgba(0,0,0,0.03)',
  area: 'rgba(113,113,122,0.35)',
  resultLine: '#52525b',
  picked: '#7c3aed',
  pickedArea: 'rgba(124,58,237,0.28)',
  underneath: '#a1a1aa',
  start: '#7c3aed',
  bracket: '#8b5cf6',
  holding: 'rgba(217,119,6,0.55)',
  heldBack: 'rgba(220,38,38,0.5)',
  pan: '#0284c7',
  tilt: '#d97706',
  stepA: 'rgba(124,58,237,0.16)',
  stepB: 'rgba(124,58,237,0.32)',
  stepText: '#4c1d95',
  highlight: '#c026d3',
  highlightArea: 'rgba(192,38,211,0.14)',
}

export const DARK_PALETTE: GraphPalette = {
  text: '#d4d4d8',
  dim: '#a1a1aa',
  grid: '#27272a',
  stripe: 'rgba(255,255,255,0.025)',
  area: 'rgba(228,228,231,0.30)',
  resultLine: '#d4d4d8',
  picked: '#a78bfa',
  pickedArea: 'rgba(167,139,250,0.30)',
  underneath: '#71717a',
  start: '#8b5cf6',
  bracket: '#8b5cf6',
  holding: 'rgba(245,158,11,0.5)',
  heldBack: 'rgba(248,113,113,0.5)',
  pan: '#38bdf8',
  tilt: '#fbbf24',
  stepA: 'rgba(167,139,250,0.18)',
  stepB: 'rgba(167,139,250,0.34)',
  stepText: '#ede9fe',
  highlight: '#f0abfc',
  highlightArea: 'rgba(240,171,252,0.16)',
}

export type GraphView = GraphViewChoice

export interface LaneDrawOptions {
  samples: number
  /** The window's length in its master's beats, for the beat grid. */
  beats: number
  /** The master's beat at sample 0 (`startBeatOf`), which places the grid on its beats; null draws none. */
  startBeat: number | null
  /** *Result* (the lane after every FX in it) or *This FX* (the picked FX's own output, before Amount). */
  view: GraphView
  /** The picked FX, as its index in the answer's `fx`, and the FX itself. */
  picked: number | null
  pickedFx: FxPreviewFx | null
  /** *Colour as seen*: each row's Intensity result, which the colour strip is multiplied by. */
  asSeen: ReadonlyMap<number, Uint8Array> | null
  /** The steps either side of 128 a position row spans. */
  positionScale: number
  palette: GraphPalette
  /** The mark a hovered control in the inspector changes, lit (§4.5). */
  highlight?: GraphMark | null
  /** A row a ladder dot links to, by its index in the answer's `rows`, marked in the gutter. */
  linkedRow?: number | null
}

/** Whether [section] holds rows the picked FX paints: where its marks, lights and handles go. */
export function paintsSection(section: LaidSection, byRow: ReadonlyMap<number, DecodedAttributeRow>, picked: number | null): boolean {
  return picked != null && section.rows.some((r) => (r.mix ?? [r.row]).some((i) => byRow.get(i)?.fx.some((o) => o.fx === picked)))
}

const rgb = ({ r, g, b }: { r: number; g: number; b: number }) => `rgb(${r},${g},${b})`

export function drawLane(ctx: GraphCtx, layout: LaneLayout, lane: DecodedLane, options: LaneDrawOptions): void {
  const { palette } = options
  ctx.clearRect(0, 0, layout.width, layout.height)
  drawBeatGrid(ctx, layout, options)
  for (const section of layout.sections) {
    const attribute = lane.attributes.find((a) => a.attribute === section.attribute)
    if (attribute == null) continue
    if (section.header != null) {
      ctx.fillStyle = palette.dim
      ctx.font = '9.5px ui-sans-serif, system-ui, sans-serif'
      ctx.textBaseline = 'bottom'
      ctx.textAlign = 'left'
      ctx.fillText(section.header, 4, section.top - 1)
    }
    section.rows.forEach((laid, index) => {
      const row = laid.mix == null ? attribute.byRow.get(laid.row) : attribute.mixed.get(laid.fixtureKey)
      if (!section.heat && index % 2 === 1) {
        ctx.fillStyle = palette.stripe
        ctx.fillRect(layout.plotLeft, laid.y, layout.plotWidth, section.rowH)
      }
      if (laid.kind === 'fixture' && !section.heat && index > 0) {
        ctx.fillStyle = palette.grid
        ctx.fillRect(layout.plotLeft, laid.y, layout.plotWidth, 1)
      }
      // A mixed row is drawn for a level or a colour; a position or a Steps FX has no mix to draw (D5).
      if (row != null && (laid.mix == null || !section.steps)) drawRow(ctx, layout, section, laid, row, attribute.kind, options)
      if (options.linkedRow === laid.row) {
        ctx.fillStyle = palette.highlight
        ctx.fillRect(0, laid.y, 3, section.rowH)
      }
    })
    // Cycle's light: the picked FX's first cycle, shaded over its rows, so an opaque colour strip or
    // heat row does not hide it.
    if (options.highlight === 'cycle' && options.samples > 0 && paintsSection(section, attribute.byRow, options.picked)) {
      const end = sampleX(layout, options.samples, Math.min(options.samples, SAMPLES_PER_CYCLE))
      ctx.fillStyle = palette.highlightArea
      ctx.fillRect(layout.plotLeft, section.top, end - layout.plotLeft, section.rows.length * section.rowH)
    }
    drawSectionMarks(ctx, layout, section, attribute.byRow, options)
  }
}

/** A line on each of the master's beats in the window, or each bar's first over 16 beats. */
function drawBeatGrid(ctx: GraphCtx, layout: LaneLayout, { beats, startBeat, palette }: LaneDrawOptions) {
  if (startBeat == null || !(beats > 0) || beats > 64) return
  const step = beats > 16 ? 4 : 1
  ctx.fillStyle = palette.grid
  for (let b = Math.ceil(startBeat / step) * step; b < startBeat + beats; b += step) {
    if (b <= startBeat) continue
    ctx.fillRect(Math.round(layout.plotLeft + ((b - startBeat) / beats) * layout.plotWidth), 0, 1, layout.height)
  }
}

/** The series a row draws under the view, and the picked FX's own output on it. */
function seriesFor(row: DecodedAttributeRow, options: LaneDrawOptions) {
  const pickedOutput = options.picked == null ? null : (row.fx.find((o) => o.fx === options.picked) ?? null)
  const own = options.view === 'this' && pickedOutput != null
  return { series: own ? pickedOutput.output : row.result, own, pickedOutput }
}

function drawRow(
  ctx: GraphCtx,
  layout: LaneLayout,
  section: LaidSection,
  laid: LaidRow,
  row: DecodedAttributeRow,
  kind: string,
  options: LaneDrawOptions,
) {
  const { palette, samples } = options
  const { series, own, pickedOutput } = seriesFor(row, options)
  const top = laid.y
  const bandH = section.stepsBand ? 10 : 0
  const y0 = top + bandH
  const h = section.rowH - bandH
  const x = (s: number) => sampleX(layout, samples, s)

  const seen = asSeenOf(options.asSeen, laid)
  if (section.heat) {
    drawHeatRow(ctx, layout, series, kind, top, section.rowH, options, seen)
  } else if (kind === 'COLOUR') {
    for (let s = 0; s < samples; s++) {
      const scale = seen == null ? 1 : (seen[Math.min(s, seen.length - 1)] ?? 0) / 255
      ctx.fillStyle = rgb(colourAt(series, s, scale))
      const left = x(s)
      ctx.fillRect(left, y0 + 1, x(s + 1) - left + 0.5, h - 2)
    }
  } else if (kind === 'POSITION') {
    const mid = y0 + h / 2
    const span = h / 2 - 1
    const yOf = (v: number) => mid - Math.max(-1, Math.min(1, (v - 128) / options.positionScale)) * span
    ctx.fillStyle = palette.grid
    ctx.fillRect(layout.plotLeft, Math.round(mid), layout.plotWidth, 1)
    for (const [plane, colour] of [
      ['pan', palette.pan],
      ['tilt', palette.tilt],
    ] as const) {
      const under = planeAt(row.underneath, plane, 0)
      if (under != null) dashed(ctx, layout.plotLeft, layout.plotLeft + layout.plotWidth, yOf(under), palette.underneath)
      const bytes = series.planes[plane]
      if (bytes == null) continue
      trace(ctx, bytes, samples, x, yOf, colour, 1.25)
    }
  } else {
    const level = series.planes.level
    const { yOf } = levelScale(section, top)
    if (level != null) {
      ctx.beginPath()
      ctx.moveTo(x(0), y0 + h - 1)
      for (let s = 0; s < samples; s++) {
        const v = level[Math.min(s, level.length - 1)]
        ctx.lineTo(x(s), yOf(v))
        ctx.lineTo(x(s + 1), yOf(v))
      }
      ctx.lineTo(x(samples), y0 + h - 1)
      ctx.closePath()
      ctx.fillStyle = own ? palette.pickedArea : palette.area
      ctx.fill()
      // Amount's light: the gap Amount leaves between the picked FX's own output and the lane's.
      const ownLevel = pickedOutput?.output.planes.level
      const resultLevel = row.result.planes.level
      if (options.highlight === 'amount' && ownLevel != null && resultLevel != null) {
        ctx.fillStyle = palette.highlightArea
        for (let s = 0; s < samples; s++) {
          const a = yOf(ownLevel[Math.min(s, ownLevel.length - 1)])
          const b = yOf(resultLevel[Math.min(s, resultLevel.length - 1)])
          if (Math.abs(a - b) >= 0.5) ctx.fillRect(x(s), Math.min(a, b), x(s + 1) - x(s), Math.abs(a - b))
        }
      }
      trace(ctx, level, samples, x, yOf, own ? palette.picked : palette.resultLine, 1, true)
    }
    const under = planeAt(row.underneath, 'level', 0)
    if (under != null) dashed(ctx, layout.plotLeft, layout.plotLeft + layout.plotWidth, yOf(under), palette.underneath)
    // Under Result, the picked FX's own output is drawn over the lane's.
    const pickedLevel = !own ? pickedOutput?.output.planes.level : undefined
    if (pickedLevel != null) trace(ctx, pickedLevel, samples, x, yOf, palette.picked, 1.25, true)
  }

  if (section.stepsBand && !section.heat) {
    // The picked FX's steps where it is a Steps FX, else the first Steps FX on the row.
    const stepped = [pickedOutput, ...row.fx].find((o) => o?.output.steps != null)?.output
    if (stepped != null) drawSteps(ctx, layout, stepped, top, bandH, options)
  }

  if (row.parked && !section.heat) {
    ctx.fillStyle = palette.dim
    ctx.font = '9px ui-sans-serif, system-ui, sans-serif'
    ctx.textAlign = 'right'
    ctx.textBaseline = 'middle'
    ctx.fillText('parked', layout.plotLeft + layout.plotWidth - 2, top + section.rowH / 2)
    ctx.textAlign = 'left'
  }

  if (pickedOutput != null && options.pickedFx != null) {
    const fx = options.pickedFx
    const rowH = section.rowH
    const holding = laid.mix == null ? fx.holding[laid.row] : null
    if (holding != null && holding.length > 0) {
      const colour = options.highlight === 'holding' ? palette.highlight : palette.holding
      for (const [from, to] of holding) hatch(ctx, x(from), top, x(to + 1) - x(from), rowH, colour)
    }
    if (own && pickedOutput.heldBackReason != null) hatch(ctx, layout.plotLeft, top, layout.plotWidth, rowH, palette.heldBack)
    const lit = options.highlight === 'starts'
    const half = lit ? 4 : 3
    ctx.fillStyle = lit ? palette.highlight : palette.start
    for (const s of rowStarts(fx, laid) ?? []) {
      const sx = x(s)
      if (section.heat) {
        ctx.fillRect(Math.round(sx), top, lit ? 2 : 1, rowH)
        continue
      }
      ctx.beginPath()
      ctx.moveTo(sx - half, top)
      ctx.lineTo(sx + half, top)
      ctx.lineTo(sx, top + half + 1)
      ctx.closePath()
      ctx.fill()
    }
  }
}

function drawHeatRow(
  ctx: GraphCtx,
  layout: LaneLayout,
  series: DecodedSeries,
  kind: string,
  top: number,
  rowH: number,
  options: LaneDrawOptions,
  seen: Uint8Array | null,
) {
  const { samples, palette } = options
  const x = (s: number) => sampleX(layout, samples, s)
  for (let s = 0; s < samples; s++) {
    const left = x(s)
    const w = x(s + 1) - left + 0.5
    if (kind === 'COLOUR') {
      const scale = seen == null ? 1 : (seen[Math.min(s, seen.length - 1)] ?? 0) / 255
      ctx.fillStyle = rgb(colourAt(series, s, scale))
      ctx.fillRect(left, top, w, rowH)
    } else if (kind === 'POSITION') {
      const half = rowH / 2
      for (const [plane, i, colour] of [
        ['pan', 0, palette.pan],
        ['tilt', 1, palette.tilt],
      ] as const) {
        const v = planeAt(series, plane, s)
        if (v == null) continue
        ctx.fillStyle = withAlpha(colour, Math.min(1, Math.abs(v - 128) / options.positionScale))
        ctx.fillRect(left, top + i * half, w, half)
      }
    } else {
      const v = planeAt(series, 'level', s) ?? 0
      ctx.fillStyle = withAlpha(palette.picked, v / 255)
      ctx.fillRect(left, top, w, rowH)
    }
  }
}

/** *As seen*'s Intensity for a laid row: its own, or a mixed row's elements' mean. */
function asSeenOf(asSeen: ReadonlyMap<number, Uint8Array> | null, laid: LaidRow): Uint8Array | null {
  if (asSeen == null) return null
  if (laid.mix == null) return asSeen.get(laid.row) ?? null
  const levels = laid.mix.flatMap((r) => {
    const level = asSeen.get(r)
    return level == null ? [] : [{ planes: { level }, steps: null, stepLabels: null }]
  })
  return levels.length === 0 ? null : (mixSeries(levels).planes.level ?? null)
}

/** A `#rrggbb` colour at [alpha], rounded to two places. */
export function withAlpha(hex: string, alpha: number): string {
  const n = Number.parseInt(hex.slice(1, 7), 16)
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${Math.round(alpha * 100) / 100})`
}

function drawSteps(ctx: GraphCtx, layout: LaneLayout, series: DecodedSeries, top: number, h: number, options: LaneDrawOptions) {
  const steps = series.steps
  if (steps == null || steps.length === 0) return
  const { samples, palette } = options
  const x = (s: number) => sampleX(layout, samples, s)
  let start = 0
  for (let s = 1; s <= Math.min(samples, steps.length); s++) {
    if (s < steps.length && s < samples && steps[s] === steps[start]) continue
    const step = steps[start]
    const left = x(start)
    const width = x(s) - left
    ctx.fillStyle = step % 2 === 0 ? palette.stepA : palette.stepB
    ctx.fillRect(left, top, width, h - 1)
    const label = series.stepLabels?.[step] ?? `${step + 1}`
    if (width > label.length * 5.5 + 4) {
      ctx.fillStyle = palette.stepText
      ctx.font = '8.5px ui-sans-serif, system-ui, sans-serif'
      ctx.textBaseline = 'middle'
      ctx.textAlign = 'left'
      ctx.fillText(label, left + 2, top + h / 2)
    }
    start = s
  }
}

function drawSectionMarks(
  ctx: GraphCtx,
  layout: LaneLayout,
  section: LaidSection,
  byRow: ReadonlyMap<number, DecodedAttributeRow>,
  options: LaneDrawOptions,
) {
  const fx = options.pickedFx
  if (fx == null || options.picked == null) return
  if (!paintsSection(section, byRow, options.picked)) return
  // The rungs are the answer's rows': a mixed row stands in for its elements only while they are folded.
  const ranked = section.rows.flatMap((laid, index) => (laid.mix == null || laid.folded ? [{ row: rungRow(laid), index }] : []))
  const rows = ranked.map((r) => r.row)
  const { palette } = options
  const { segments, parts } = ladderBrackets(fx, rows)
  const top = (at: number) => section.top + ranked[at].index * section.rowH
  const bottom = (at: number) => top(at) + section.rowH
  /** Where a wing opens at ranked row [at]: over its fixture's row where that row heads it. */
  const opens = (at: number) => {
    const index = ranked[at].index
    const above = section.rows[index - 1]
    return section.top + (above != null && above.mix != null && above.fixtureKey === section.rows[index].fixtureKey ? index - 1 : index) * section.rowH
  }
  const edge = layout.plotLeft - 3
  const bracketsLit = options.highlight === 'brackets'
  const bracketColour = bracketsLit ? palette.highlight : palette.bracket
  ctx.strokeStyle = bracketColour
  ctx.lineWidth = bracketsLit ? 2 : 1
  for (const b of segments) bracket(ctx, edge, top(b.from) + 2, bottom(b.to) - 2)
  for (const b of parts) {
    bracket(ctx, edge - 6, top(b.from) + 1, bottom(b.to) - 1)
    if (!section.heat) {
      ctx.fillStyle = bracketColour
      ctx.font = '8.5px ui-monospace, Menlo, monospace'
      ctx.textBaseline = 'middle'
      ctx.textAlign = 'right'
      ctx.fillText(`P${b.part}`, edge - 8, (top(b.from) + bottom(b.to)) / 2)
      ctx.textAlign = 'left'
    }
  }
  ctx.lineWidth = 1
  const mirrorLit = options.highlight === 'mirror'
  for (const at of wingBreaks(fx, rows)) {
    dashed(ctx, layout.plotLeft, layout.plotLeft + layout.plotWidth, opens(at), mirrorLit ? palette.highlight : palette.bracket, [2, 3], mirrorLit ? 2 : 1)
  }
}

function bracket(ctx: GraphCtx, x: number, from: number, to: number) {
  ctx.beginPath()
  ctx.moveTo(x, from)
  ctx.lineTo(x - 3, from)
  ctx.lineTo(x - 3, to)
  ctx.lineTo(x, to)
  ctx.stroke()
}

function dashed(ctx: GraphCtx, from: number, to: number, y: number, colour: string, dash: number[] = [3, 3], width = 1) {
  ctx.save()
  ctx.setLineDash(dash)
  ctx.strokeStyle = colour
  ctx.lineWidth = width
  ctx.beginPath()
  ctx.moveTo(from, Math.round(y) + 0.5)
  ctx.lineTo(to, Math.round(y) + 0.5)
  ctx.stroke()
  ctx.restore()
}

/** A plane as a line, a sample a step ([stepped]) or joining the samples' midpoints. */
function trace(
  ctx: GraphCtx,
  bytes: Uint8Array,
  samples: number,
  x: (s: number) => number,
  yOf: (v: number) => number,
  colour: string,
  width: number,
  stepped = false,
) {
  ctx.beginPath()
  for (let s = 0; s < samples; s++) {
    const y = yOf(bytes[Math.min(s, bytes.length - 1)])
    if (stepped) {
      if (s === 0) ctx.moveTo(x(s), y)
      else ctx.lineTo(x(s), y)
      ctx.lineTo(x(s + 1), y)
    } else {
      const mx = (x(s) + x(s + 1)) / 2
      if (s === 0) ctx.moveTo(mx, y)
      else ctx.lineTo(mx, y)
    }
  }
  ctx.strokeStyle = colour
  ctx.lineWidth = width
  ctx.stroke()
  ctx.lineWidth = 1
}

function hatch(ctx: GraphCtx, x: number, y: number, w: number, h: number, colour: string) {
  if (w <= 0) return
  ctx.save()
  ctx.beginPath()
  ctx.rect(x, y, w, h)
  ctx.clip()
  ctx.strokeStyle = colour
  ctx.lineWidth = 1
  ctx.beginPath()
  for (let d = -h; d < w; d += 5) {
    ctx.moveTo(x + d, y + h)
    ctx.lineTo(x + d + h, y)
  }
  ctx.stroke()
  ctx.restore()
}

export interface PathRow {
  pan: Uint8Array
  tilt: Uint8Array
  colour: string
}

/** The path panel: each element's pan across and tilt up over the window, at [scale] steps a side. */
export function drawPaths(ctx: GraphCtx, size: number, rows: readonly PathRow[], samples: number, scale: number, palette: GraphPalette): void {
  ctx.clearRect(0, 0, size, size)
  ctx.fillStyle = palette.grid
  ctx.fillRect(Math.round(size / 2), 4, 1, size - 8)
  ctx.fillRect(4, Math.round(size / 2), size - 8, 1)
  for (const row of rows) {
    ctx.beginPath()
    for (let s = 0; s < samples; s++) {
      const [px, py] = pathPoint(row, s, size, scale)
      if (s === 0) ctx.moveTo(px, py)
      else ctx.lineTo(px, py)
    }
    ctx.strokeStyle = row.colour
    ctx.lineWidth = 1
    ctx.stroke()
  }
}

/** The path panel's dots, where each element is at sample [s]. */
export function drawPathDots(ctx: GraphCtx, size: number, rows: readonly PathRow[], s: number, scale: number): void {
  ctx.clearRect(0, 0, size, size)
  for (const row of rows) {
    const [px, py] = pathPoint(row, Math.floor(s), size, scale)
    ctx.fillStyle = row.colour
    ctx.beginPath()
    ctx.arc(px, py, 3, 0, Math.PI * 2)
    ctx.fill()
  }
}

export function pathPoint(row: Pick<PathRow, 'pan' | 'tilt'>, s: number, size: number, scale: number): [number, number] {
  const half = size / 2 - 6
  const at = (bytes: Uint8Array) => bytes[Math.max(0, Math.min(s, bytes.length - 1))] ?? 128
  const clamp = (v: number) => Math.max(-1, Math.min(1, (v - 128) / scale))
  return [size / 2 + clamp(at(row.pan)) * half, size / 2 - clamp(at(row.tilt)) * half]
}

/** The largest swing from 128 any row's pan or tilt makes, at least 16 steps: what a position row spans. */
export function positionScaleOf(lane: DecodedLane | undefined): number {
  let most = 16
  for (const attribute of lane?.attributes ?? []) {
    for (const row of attribute.rows) {
      for (const series of [row.result, ...row.fx.map((o) => o.output)]) {
        for (const plane of [series.planes.pan, series.planes.tilt]) {
          if (plane == null) continue
          for (const v of plane) most = Math.max(most, Math.abs(v - 128))
        }
      }
    }
  }
  return most
}
