import type { AttributeFamily } from '@/lib/attributeFamily'
import type { GraphWindowChoice } from '@/lib/fxEditorView'
import type { FxPreviewAnswer, FxPreviewFx, FxPreviewWindowRequest } from '@/store/fxPreview'
import type { SpeedMasterBeat } from '@/api/speedMastersWsApi'
import type { DecodedLane, DecodedSeries } from './fxPreviewDecode'

/**
 * **The FX editor's graph, as a model** (FX editor plan §4.4): which rows a lane draws and where,
 * the spread's marks read off the desk's ladder, a row's phase from its cycle starts, and where the
 * playhead is. Pure; `FxGraph` draws it. Every value drawn is the desk's (P2) — nothing here
 * computes an FX's output.
 */

/** The toolbar's window, sent as the preview's `window`. */
export type GraphWindow = GraphWindowChoice

export const GRAPH_WINDOWS: readonly { value: GraphWindow; label: string; request: FxPreviewWindowRequest }[] = [
  { value: '1c', label: '1 cycle', request: { cycles: 1 } },
  { value: '2c', label: '2 cycles', request: { cycles: 2 } },
  { value: '2b', label: '2 bars', request: { beats: 8 } },
  { value: '8b', label: '8 bars', request: { beats: 32 } },
]

export const windowRequest = (value: GraphWindow): FxPreviewWindowRequest =>
  (GRAPH_WINDOWS.find((w) => w.value === value) ?? GRAPH_WINDOWS[1]).request

/** Over this many rows a section draws as a heat map, [HEAT_ROW_PX] a row; a folded fixture counts as one. */
export const HEAT_FROM_ROWS = 25
export const HEAT_ROW_PX = 6
/** Below the editor's 760 arm the graph draws the picked FX's lane alone, at most this many rows. */
export const NARROW_ROWS = 6

/** The beat ruler over the lanes. */
export const RULER_PX = 24
/** The least plot the lanes keep beside the names (and the path panel) before either gives way. */
export const MIN_PLOT_PX = 160
/** The narrowest the name column gets when the window's width would leave the plot under [MIN_PLOT_PX]; the strip's fixed width. */
export const NARROW_GUTTER_PX = 72

/** The name column drawn in [lanesWidth]: the window's [chosen] width, narrowed as far as [NARROW_GUTTER_PX] to keep [MIN_PLOT_PX] of plot. */
export const fitGutter = (chosen: number, lanesWidth: number): number => Math.max(NARROW_GUTTER_PX, Math.min(chosen, lanesWidth - 4 - MIN_PLOT_PX))
const SECTION_HEADER_PX = 12
const STEPS_BAND_PX = 10

const ROW_PX: Readonly<Record<AttributeFamily | 'OTHER', number>> = {
  INTENSITY: 28,
  COLOUR: 14,
  POSITION: 24,
  BEAM: 22,
  OTHER: 22,
}

/**
 * The answer's rows in draw order, as indices into `rows`: the answer's own order — the picked
 * FX's Order, grouped by fixture — or, with *Rig order* on, by `rigIndex`, so the rows hold still
 * while the Order changes.
 */
export function graphRowOrder(answer: Pick<FxPreviewAnswer, 'rows'>, rigOrder: boolean): number[] {
  const order = answer.rows.map((_, i) => i)
  if (!rigOrder) return order
  return order.sort((a, b) => answer.rows[a].rigIndex - answer.rows[b].rigIndex || a - b)
}

const MIX_KEY_PREFIX = 'mix\u0000'

/** The key a fixture's mixed row is hovered by, which no answer row has. */
export const mixKeyOf = (fixtureKey: string): string => `${MIX_KEY_PREFIX}${fixtureKey}`
export const mixFixtureOf = (key: string): string | null => (key.startsWith(MIX_KEY_PREFIX) ? key.slice(MIX_KEY_PREFIX.length) : null)

export interface LaidRow {
  /** An index into the answer's `rows`; -1 for a mixed row, which the answer has no row for. */
  row: number
  /** The answer row's key, or a mixed row's [mixKeyOf]. */
  key: string
  y: number
  /**
   * `fixture` heads a multi-element fixture's elements — its own row where the answer has one, else
   * the mix of them ([mix]); `element` is one of them; `head` is a fixture drawn with no elements.
   */
  kind: 'fixture' | 'element' | 'head'
  /** The element rows a mixed row averages; null for a row the answer has. */
  mix: readonly number[] | null
  fixtureKey: string
  /** A fixture row's name, an element's own words or number, a head's label. */
  label: string
  /** An element row's index in its fixture, which it reads where its words do not fit. */
  element: number | null
  /** A fixture row whose elements are folded under it. */
  folded: boolean
}

export interface LaidSection {
  attribute: string
  /** The attribute's words over its rows, where the lane has several. */
  header: string | null
  top: number
  rowH: number
  heat: boolean
  stepsBand: boolean
  /** A Steps FX steps this attribute: a mixed row draws its name and no mix. */
  steps: boolean
  /** The rows the narrow arm's [LayoutOptions.maxRows] left out. */
  cut: number
  rows: LaidRow[]
}

export interface LaneLayout {
  family: AttributeFamily | null
  width: number
  plotLeft: number
  plotWidth: number
  height: number
  sections: LaidSection[]
}

export interface LayoutOptions {
  width: number
  /** The name column's width: where the plot starts. */
  gutter: number
  /** The answer's rows in draw order. */
  order: readonly number[]
  rows: FxPreviewAnswer['rows']
  /** Draws a Steps band over the rows of these attributes. */
  stepsAttributes: ReadonlySet<string>
  /** The fixtures whose elements fold under their own row. */
  folded?: ReadonlySet<string>
  /** At most this many rows a section (the narrow arm's 6). */
  maxRows?: number
  fixtureName: (fixtureKey: string) => string | undefined
  attributeLabel: (attribute: string) => string
}

/**
 * Where each section and row of a lane sits: one section per attribute (headed where the lane has
 * several), its rows in draw order — only the heads that carry it — at the family's row height, or
 * [HEAT_ROW_PX] once there are [HEAT_FROM_ROWS] or more. A multi-element fixture's elements sit
 * under a row of its own (D5): the answer's row for the fixture where it carries the attribute, else
 * a mixed row of its elements, which the drawing averages; folded, that row is drawn alone.
 */
export function layoutLane(lane: DecodedLane, options: LayoutOptions): LaneLayout {
  const { width, gutter, order, rows, stepsAttributes, folded, maxRows, fixtureName, attributeLabel } = options
  const plotLeft = gutter
  const plotWidth = Math.max(1, width - plotLeft - 4)
  const headed = lane.attributes.length > 1
  const sections: LaidSection[] = []
  let y = 0
  for (const attribute of lane.attributes) {
    const all = fixtureRuns(
      order.filter((r) => attribute.byRow.has(r)),
      rows,
      folded,
      fixtureName,
    )
    const drawn = maxRows != null ? all.slice(0, maxRows) : all
    if (drawn.length === 0) continue
    const header = headed ? attributeLabel(attribute.attribute) : null
    if (header != null) y += SECTION_HEADER_PX
    // The answer's rows decide the heat map, a folded fixture counting as one: a mixed row over
    // elements still drawn adds none.
    const heat = drawn.filter((r) => r.mix == null || r.folded).length >= HEAT_FROM_ROWS
    const steps = stepsAttributes.has(attribute.attribute)
    const stepsBand = !heat && steps
    const rowH = heat ? HEAT_ROW_PX : ROW_PX[lane.family ?? 'OTHER'] + (stepsBand ? STEPS_BAND_PX : 0)
    const top = y
    const laid: LaidRow[] = drawn.map((r, i) => ({ ...r, y: top + i * rowH }))
    sections.push({ attribute: attribute.attribute, header, top, rowH, heat, stepsBand, steps, cut: all.length - drawn.length, rows: laid })
    y = top + drawn.length * rowH
  }
  return { family: lane.family, width, plotLeft, plotWidth, height: Math.max(y, 1), sections }
}

/**
 * A section's rows grouped by fixture, in the order each fixture first appears: a fixture with
 * elements is its own row (the answer's, else mixed) then its elements, unless folded.
 */
function fixtureRuns(
  drawn: readonly number[],
  rows: FxPreviewAnswer['rows'],
  folded: ReadonlySet<string> | undefined,
  fixtureName: (fixtureKey: string) => string | undefined,
): Omit<LaidRow, 'y'>[] {
  const units: { fixtureKey: string; own: number | null; elements: number[] }[] = []
  const byFixture = new Map<string, (typeof units)[number]>()
  for (const r of drawn) {
    const row = rows[r]
    let unit = byFixture.get(row.fixtureKey)
    if (unit == null) {
      unit = { fixtureKey: row.fixtureKey, own: null, elements: [] }
      units.push(unit)
      byFixture.set(row.fixtureKey, unit)
    }
    if (row.element == null) unit.own = r
    else unit.elements.push(r)
  }
  const out: Omit<LaidRow, 'y'>[] = []
  for (const { fixtureKey, own, elements } of units) {
    if (elements.length === 0) {
      if (own != null) out.push({ row: own, key: rows[own].key, kind: 'head', mix: null, fixtureKey, label: rows[own].label, element: null, folded: false })
      continue
    }
    const name = fixtureName(fixtureKey) ?? (own != null ? rows[own].label : fixtureKey)
    const isFolded = folded?.has(fixtureKey) === true
    out.push(
      own != null
        ? { row: own, key: rows[own].key, kind: 'fixture', mix: null, fixtureKey, label: name, element: null, folded: isFolded }
        : { row: -1, key: mixKeyOf(fixtureKey), kind: 'fixture', mix: elements, fixtureKey, label: name, element: null, folded: isFolded },
    )
    if (isFolded) continue
    for (const r of elements) {
      const row = rows[r]
      const element = row.element ?? 0
      out.push({ row: r, key: row.key, kind: 'element', mix: null, fixtureKey, label: elementLabel(row.label, name, element), element, folded: false })
    }
  }
  return out
}

/** An element's own words under its fixture's name: what follows the name, else its number. */
export function elementLabel(label: string, fixture: string, element: number): string {
  if (label.startsWith(fixture)) {
    const rest = label.slice(fixture.length).replace(/^[\s·:,-]+/, '')
    if (rest !== '') return rest
    return `${element + 1}`
  }
  return label === '' ? `${element + 1}` : label
}

/**
 * [text] cut in its middle to fit [maxPx] as [measure] reads it — *LX2 Shehds…15 SR* — keeping more
 * of its start than its end; whole where it fits.
 */
export function middleCut(text: string, maxPx: number, measure: (text: string) => number): string {
  if (measure(text) <= maxPx) return text
  const cutAt = (keep: number) => {
    const head = Math.ceil(keep / 2)
    return `${text.slice(0, head).trimEnd()}…${text.slice(text.length - (keep - head)).trimStart()}`
  }
  // The most characters kept that still fit, by halving.
  let fits = 2
  let over = text.length
  while (over - fits > 1) {
    const keep = Math.floor((fits + over) / 2)
    if (measure(cutAt(keep)) <= maxPx) fits = keep
    else over = keep
  }
  return fits > 2 ? cutAt(fits) : `${text.slice(0, 2)}…`
}

/** The answer row a laid row's ladder rungs are read from: its own, or a mixed row's first element's. */
export const rungRow = (laid: Pick<LaidRow, 'row' | 'mix'>): number => laid.mix?.[0] ?? laid.row

/** The picked FX's cycle starts on a laid row: the answer's, or a mixed row's where its elements share them. */
export function rowStarts(fx: Pick<FxPreviewFx, 'starts'>, laid: Pick<LaidRow, 'row' | 'mix'>): readonly number[] | null {
  if (laid.mix == null) return fx.starts[laid.row] ?? null
  const first = fx.starts[laid.mix[0]]
  if (first == null) return null
  const same = (other: readonly number[] | null | undefined) => other != null && other.length === first.length && other.every((s, i) => s === first[i])
  return laid.mix.every((r) => same(fx.starts[r])) ? first : null
}

/**
 * A slider row's level scale, from the row's top: byte [v]'s y, and the byte at a y — what the
 * drawing places a level by and what the max and min handles read back.
 */
export function levelScale(section: Pick<LaidSection, 'rowH' | 'stepsBand'>, top: number): { yOf: (v: number) => number; byteAt: (y: number) => number } {
  const y0 = top + (section.stepsBand ? STEPS_BAND_PX : 0)
  const h = section.rowH - (section.stepsBand ? STEPS_BAND_PX : 0)
  return {
    yOf: (v) => y0 + h - 1 - (v / 255) * (h - 3),
    byteAt: (y) => Math.max(0, Math.min(255, Math.round(((y0 + h - 1 - y) / (h - 3)) * 255))),
  }
}

/** The x of sample [s] (its left edge) in a plot [plotWidth] wide of [samples] samples. */
export const sampleX = (layout: Pick<LaneLayout, 'plotLeft' | 'plotWidth'>, samples: number, s: number): number =>
  layout.plotLeft + (s / Math.max(1, samples)) * layout.plotWidth

/** The sample under [x], or null off the plot. */
export function sampleAt(layout: Pick<LaneLayout, 'plotLeft' | 'plotWidth'>, samples: number, x: number): number | null {
  const at = (x - layout.plotLeft) / layout.plotWidth
  if (at < 0 || at >= 1 || samples <= 0) return null
  return Math.min(samples - 1, Math.floor(at * samples))
}

/**
 * The height that shows a lane's first [rows] rows, across its sections, or null when every row
 * fits: where a strip's scroller stops.
 */
export function visibleHeight(layout: Pick<LaneLayout, 'sections'>, rows: number): number | null {
  let seen = 0
  for (const section of layout.sections) {
    if (seen + section.rows.length > rows) return section.top + (rows - seen) * section.rowH
    seen += section.rows.length
  }
  return null
}

/** The row under [y], with its section. */
export function rowAt(layout: LaneLayout, y: number): { section: LaidSection; row: LaidRow } | null {
  for (const section of layout.sections) {
    const index = Math.floor((y - section.top) / section.rowH)
    if (index >= 0 && index < section.rows.length) return { section, row: section.rows[index] }
  }
  return null
}

/** The FX the graph is about — the request's first, the picked FX — as its index in the answer's `fx`. */
export function pickedIndex(answer: Pick<FxPreviewAnswer, 'fx'>): number | null {
  const index = answer.fx.findIndex((fx) => fx.entry === 0)
  return index < 0 ? null : index
}

export interface Bracket {
  /** Indices into the section's rows, inclusive. */
  from: number
  to: number
  /** A Part's number from 1; null for a Segment. */
  part: number | null
}

/**
 * Segments and Parts as the desk's ladder says them, over a section's drawn rows: a Segment is a
 * run of neighbouring rows the Segments rung gave one rank, where Segments acted; a Part is a run of
 * rows in one repeat of the spread, where Parts acted (the Wings rank over the Parts count). A
 * split-off copy's rungs are null, and bring no bracket.
 */
export function ladderBrackets(fx: FxPreviewFx, rows: readonly number[]): { segments: Bracket[]; parts: Bracket[] } {
  const { order, segments, wings, parts } = fx.ladder
  const acted = (after: (number | null)[], before: (number | null)[]) =>
    rows.some((r) => after[r] != null && before[r] != null && (after[r] as number) < (before[r] as number))
  const runs = (key: (r: number) => number | null, part: boolean): Bracket[] => {
    const out: Bracket[] = []
    let start = 0
    for (let i = 1; i <= rows.length; i++) {
      const same = i < rows.length && key(rows[i]) != null && key(rows[i]) === key(rows[start])
      if (same) continue
      const k = key(rows[start])
      if (k != null && i - 1 > start) out.push({ from: start, to: i - 1, part: part ? k + 1 : null })
      if (part && k != null && i - 1 === start) out.push({ from: start, to: start, part: k + 1 })
      start = i
    }
    return out
  }
  const segmentRuns = acted(segments.counts, order.counts) ? runs((r) => segments.ranks[r], false) : []
  const partRuns = acted(parts.counts, wings.counts)
    ? runs((r) => {
        const rank = wings.ranks[r]
        const count = parts.counts[r]
        return rank == null || count == null || count <= 0 ? null : Math.floor(rank / count)
      }, true)
    : []
  return { segments: segmentRuns, parts: partRuns }
}

/**
 * Where Wings mirror, between drawn rows: the index of each row that opens a wing other than the
 * first (the Segments rank over the Wings count), where Wings acted.
 */
export function wingBreaks(fx: FxPreviewFx, rows: readonly number[]): number[] {
  const { segments, wings } = fx.ladder
  const wingOf = (r: number) => {
    const rank = segments.ranks[r]
    const count = wings.counts[r]
    return rank == null || count == null || count <= 0 ? null : Math.floor(rank / count)
  }
  const acted = rows.some((r) => wings.counts[r] != null && segments.counts[r] != null && (wings.counts[r] as number) < (segments.counts[r] as number))
  if (!acted) return []
  const out: number[] = []
  for (let i = 1; i < rows.length; i++) {
    const a = wingOf(rows[i - 1])
    const b = wingOf(rows[i])
    if (a != null && b != null && a !== b) out.push(i)
  }
  return out
}

/** Whether sample [s] falls in one of the row's inclusive `holding` ranges. */
export function isHolding(holding: readonly (readonly number[])[] | null | undefined, s: number): boolean {
  return (holding ?? []).some(([from, to]) => s >= from && s <= to)
}

/**
 * A row's phase at sample [s], 0 to 1, from where its cycles start: between two starts, the share
 * of the way; before the first or after the last, by the distance between starts — or by [cycle]
 * samples where the window holds fewer than two. Null where the row holds, or the cycle is unknown.
 */
export function phaseAt(fx: FxPreviewFx, row: number, s: number, cycle: number | null): number | null {
  if (isHolding(fx.holding[row], s)) return null
  const starts = fx.starts[row]
  if (starts == null || starts.length === 0) return null
  const period = starts.length >= 2 ? starts[1] - starts[0] : cycle
  let before = -1
  for (let i = 0; i < starts.length; i++) if (starts[i] <= s) before = i
  if (before >= 0 && before + 1 < starts.length) return (s - starts[before]) / (starts[before + 1] - starts[before])
  if (period == null || period <= 0) return null
  const from = before >= 0 ? starts[before] : starts[0] - period * Math.ceil((starts[0] - s) / period)
  return (((s - from) / period) % 1 + 1) % 1
}

/** Samples a cycle of the picked FX: the desk's rate, which a cut at its sample cap leaves alone. */
export const SAMPLES_PER_CYCLE = 48

/** What the desk's caps cut, in words. */
export function truncatedWords(truncated: readonly string[] | undefined): string | null {
  const words = (truncated ?? []).flatMap((t) =>
    t === 'SAMPLES' ? ['the samples, at 1024 a row'] : t === 'ROWS' ? ['the rows, at 96'] : t === 'FX' ? ['the FX, at 8'] : [],
  )
  return words.length === 0 ? null : `The desk cut ${words.join(', ')}.`
}

export interface PreviewColour {
  r: number
  g: number
  b: number
}

/**
 * A colour series' sample as light: RGB, with the bundled white, amber and UV added as the eye
 * would take them — a strip *as the colour*. [scale] is *Colour as seen*'s Intensity, 0 to 1.
 */
export function colourAt(series: DecodedSeries, s: number, scale = 1): PreviewColour {
  const p = series.planes
  const at = (plane: Uint8Array | undefined) => (plane == null ? 0 : (plane[Math.min(s, plane.length - 1)] ?? 0))
  const white = at(p.white)
  const amber = at(p.amber)
  const uv = at(p.uv)
  const clamp = (v: number) => Math.max(0, Math.min(255, Math.round(v * scale)))
  return {
    r: clamp(at(p.red) + white + amber + uv * 0.4),
    g: clamp(at(p.green) + white + amber * 0.6),
    b: clamp(at(p.blue) + white + uv * 0.8),
  }
}

/** A plane's byte at [s], the last sample for a one-sample series (what is underneath). */
export function planeAt(series: DecodedSeries | null | undefined, plane: keyof DecodedSeries['planes'], s: number): number | null {
  const bytes = series?.planes[plane]
  if (bytes == null || bytes.length === 0) return null
  return bytes[Math.min(s, bytes.length - 1)]
}

/** The clock the playhead runs on: the picked FX's master, or its own seconds. */
export type PlayheadClock =
  | { kind: 'master'; speedMasterUuid: string; tickAtStart: number; msPerBeat: number }
  | { kind: 'seconds' }

export const TICKS_PER_BEAT = 24

/**
 * The master's beat at sample 0, counted from its tick 0 — a cycle's phase is 0 where the tick is a
 * multiple of 24 (`MasterClock.phaseForDivision`), and the desk starts the window at whatever tick
 * the master is on. Null for a `seconds` clock, whose beats have no place in the window.
 */
export function startBeatOf(clock: PlayheadClock): number | null {
  return clock.kind === 'master' ? clock.tickAtStart / TICKS_PER_BEAT : null
}

export interface RulerTick {
  x: number
  /** A bar's first beat, or a second on a wall clock: drawn taller. */
  major: boolean
  label: string | null
}

const SECOND_STEPS = [0.1, 0.25, 0.5, 1, 2, 5, 10, 15, 30, 60, 120, 300]

/**
 * **The beat ruler's ticks** (D4): on a master, one on each of its beats, placed from the tick the
 * window started on as the beat grid is — a bar's first taller and numbered from the bar the window
 * starts in, the beats between `bar.beat` where there is room — or each bar alone over 16 beats. On
 * its own clock, seconds from the window's start.
 */
export function rulerTicks(clock: PlayheadClock, window: Pick<FxPreviewAnswer['window'], 'beats' | 'seconds'>, plotLeft: number, plotWidth: number): RulerTick[] {
  const end = plotLeft + plotWidth
  const labelled = (x: number) => x <= end - 10
  const start = startBeatOf(clock)
  if (start == null) {
    const seconds = window.seconds
    if (!(seconds > 0) || plotWidth <= 0) return []
    const step = SECOND_STEPS.find((s) => (s / seconds) * plotWidth >= 48) ?? SECOND_STEPS[SECOND_STEPS.length - 1]
    const out: RulerTick[] = []
    for (let i = 0; i * step <= seconds + 1e-9; i++) {
      const x = plotLeft + ((i * step) / seconds) * plotWidth
      out.push({ x, major: true, label: labelled(x) ? `${Number((i * step).toFixed(2))} s` : null })
    }
    return out
  }
  const beats = window.beats
  if (!(beats > 0) || plotWidth <= 0) return []
  const pxPerBeat = plotWidth / beats
  const step = beats > 16 ? 4 : 1
  const barEvery = Math.max(1, Math.ceil(20 / (pxPerBeat * 4)))
  const firstBar = Math.floor(start / 4) * 4
  const out: RulerTick[] = []
  for (let b = Math.ceil((start - 1e-9) / step) * step; b <= start + beats + 1e-9; b += step) {
    const x = plotLeft + (b - start) * pxPerBeat
    const inBar = (((b % 4) + 4) % 4) + 1
    const bar = Math.floor((b - firstBar) / 4) + 1
    const major = inBar === 1
    const label = !labelled(x) ? null : major ? ((bar - 1) % barEvery === 0 ? `${bar}` : null) : pxPerBeat >= 28 ? `${bar}.${inBar}` : null
    out.push({ x, major, label })
  }
  return out
}

/** The master's beat within its bar at sample [s], 1 to 4.99: what the hover reads. */
export function barBeatAt(clock: PlayheadClock, window: Pick<FxPreviewAnswer['window'], 'beats' | 'samples'>, s: number): number | null {
  const start = startBeatOf(clock)
  if (start == null || window.samples <= 0) return null
  const beat = start + (s * window.beats) / window.samples
  return (((beat % 4) + 4) % 4) + 1
}

export function playheadClockOf(answer: Pick<FxPreviewAnswer, 'clock' | 'fx'>): PlayheadClock {
  const index = pickedIndex(answer)
  const uuid = index == null ? null : (answer.fx[index].speedMasterUuid ?? null)
  const clock = uuid == null ? null : answer.clock.find((c) => c.speedMasterUuid === uuid)
  if (clock == null || clock.tickAtStart == null || clock.msPerBeat == null || uuid == null) return { kind: 'seconds' }
  return { kind: 'master', speedMasterUuid: uuid, tickAtStart: clock.tickAtStart, msPerBeat: clock.msPerBeat }
}

/**
 * **The graph's playhead** (FX editor plan §3.3), advanced on the client as a cue's fade is: from
 * sample 0 when the answer lands, at the wall clock's rate, wrapping at the window's last whole cycle. A master's
 * beat resyncs it — beat `n` is the master's tick `24n + 1`, so the playhead is that tick's distance
 * from the window's `tickAtStart` — and a later answer on the same master carries the master's tick
 * across, so a re-ask does not send the playhead back to the start. A `seconds` clock has no start
 * time to resync against, and runs from sample 0. [now] is a monotonic clock in milliseconds.
 */
export class GraphPlayhead {
  private clock: PlayheadClock = { kind: 'seconds' }
  private lengthMs = 0
  private sampleMs = 1
  private samples = 0
  /** The playhead's place, in ms from the window's start, at [anchoredAt]. */
  private anchorMs = 0
  private anchoredAt = 0
  private frozen = false

  /**
   * A new answer: start from sample 0, or carry the master's tick across from the last one. The
   * playhead wraps at the last whole [periodSamples] in the window — a cycle of the picked FX, two
   * under Bounce — so it comes round where the stage does; a window shorter than that wraps at its end.
   */
  setAnswer(window: FxPreviewAnswer['window'], clock: PlayheadClock, now: number, periodSamples = SAMPLES_PER_CYCLE): void {
    const previous = this.clock
    const tickNow = previous.kind === 'master' && clock.kind === 'master' && previous.speedMasterUuid === clock.speedMasterUuid ? this.masterTickAt(now) : null
    this.clock = clock
    this.samples = window.samples
    this.sampleMs = window.sampleMs
    const whole = periodSamples > 0 ? Math.floor(window.samples / periodSamples) * periodSamples : 0
    this.lengthMs = (whole > 0 ? whole : window.samples) * window.sampleMs
    this.anchoredAt = now
    this.anchorMs = tickNow != null && clock.kind === 'master' ? ((tickNow - clock.tickAtStart) * clock.msPerBeat) / TICKS_PER_BEAT : 0
  }

  /**
   * A beat frame from the master the playhead follows; any other is ignored, and so is one while
   * held. Ticks become window time at the answer's tempo, which the samples are spaced at: after a
   * retune, the answer to the re-ask brings the new one.
   */
  resync(beat: Pick<SpeedMasterBeat, 'masterUuid' | 'beatNumber'>, now: number): void {
    const clock = this.clock
    if (clock.kind !== 'master' || beat.masterUuid !== clock.speedMasterUuid || this.frozen) return
    this.anchorMs = ((beat.beatNumber * TICKS_PER_BEAT + 1 - clock.tickAtStart) * clock.msPerBeat) / TICKS_PER_BEAT
    this.anchoredAt = now
  }

  /** Hold the playhead where it is (the graph's pause, or reduced motion), or let it run on from there. */
  setFrozen(frozen: boolean, now: number): void {
    if (frozen === this.frozen) return
    this.anchorMs = this.positionMs(now)
    this.anchoredAt = now
    this.frozen = frozen
  }

  /** Where the playhead is, in ms from the window's start, wrapped into the window. */
  positionMs(now: number): number {
    const raw = this.anchorMs + (this.frozen ? 0 : now - this.anchoredAt)
    if (this.lengthMs <= 0) return 0
    return ((raw % this.lengthMs) + this.lengthMs) % this.lengthMs
  }

  /** The playhead as a fractional sample, 0 to `samples`. */
  sample(now: number): number {
    return this.samples === 0 ? 0 : Math.min(this.samples, this.positionMs(now) / this.sampleMs)
  }

  /** The master the playhead follows, for its beat subscription. */
  get masterUuid(): string | null {
    return this.clock.kind === 'master' ? this.clock.speedMasterUuid : null
  }

  private masterTickAt(now: number): number | null {
    const clock = this.clock
    if (clock.kind !== 'master') return null
    const raw = this.anchorMs + (this.frozen ? 0 : now - this.anchoredAt)
    return clock.tickAtStart + (raw * TICKS_PER_BEAT) / clock.msPerBeat
  }
}
