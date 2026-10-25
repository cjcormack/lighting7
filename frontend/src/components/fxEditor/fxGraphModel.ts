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

/** Over this many rows a section draws as a heat map, [HEAT_ROW_PX] a row. */
export const HEAT_FROM_ROWS = 25
export const HEAT_ROW_PX = 6
/** Below the editor's 760 arm the graph draws the picked FX's lane alone, at most this many rows. */
export const NARROW_ROWS = 6

export const GUTTER_PX = 72
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

export interface LaidRow {
  /** An index into the answer's `rows`. */
  row: number
  y: number
  /** The fixture's name above an element run's first element; null otherwise. */
  fixtureLabel: string | null
  label: string
  element: boolean
}

export interface LaidSection {
  attribute: string
  /** The attribute's words over its rows, where the lane has several. */
  header: string | null
  top: number
  rowH: number
  heat: boolean
  stepsBand: boolean
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
  /** The answer's rows in draw order. */
  order: readonly number[]
  rows: FxPreviewAnswer['rows']
  /** Draws a Steps band over the rows of these attributes. */
  stepsAttributes: ReadonlySet<string>
  /** At most this many rows a section (the narrow arm's 6). */
  maxRows?: number
  fixtureName: (fixtureKey: string) => string | undefined
  attributeLabel: (attribute: string) => string
}

/**
 * Where each section and row of a lane sits: one section per attribute (headed where the lane has
 * several), its rows in draw order — only the heads that carry it — at the family's row height, or
 * [HEAT_ROW_PX] once there are [HEAT_FROM_ROWS] or more.
 */
export function layoutLane(lane: DecodedLane, options: LayoutOptions): LaneLayout {
  const { width, order, rows, stepsAttributes, maxRows, fixtureName, attributeLabel } = options
  const plotLeft = GUTTER_PX
  const plotWidth = Math.max(1, width - plotLeft - 4)
  const headed = lane.attributes.length > 1
  const sections: LaidSection[] = []
  let y = 0
  for (const attribute of lane.attributes) {
    let drawn = order.filter((r) => attribute.byRow.has(r))
    if (maxRows != null) drawn = drawn.slice(0, maxRows)
    if (drawn.length === 0) continue
    const header = headed ? attributeLabel(attribute.attribute) : null
    if (header != null) y += SECTION_HEADER_PX
    const heat = drawn.length >= HEAT_FROM_ROWS
    const stepsBand = !heat && stepsAttributes.has(attribute.attribute)
    const rowH = heat ? HEAT_ROW_PX : ROW_PX[lane.family ?? 'OTHER'] + (stepsBand ? STEPS_BAND_PX : 0)
    const top = y
    const laid: LaidRow[] = drawn.map((r, i) => {
      const row = rows[r]
      const previous = i > 0 ? rows[drawn[i - 1]] : null
      const opensFixture = row.element != null && previous?.fixtureKey !== row.fixtureKey
      const name = fixtureName(row.fixtureKey) ?? row.fixtureKey
      return {
        row: r,
        y: top + i * rowH,
        fixtureLabel: opensFixture ? name : null,
        label: row.element != null ? elementLabel(row.label, name, row.element) : row.label,
        element: row.element != null,
      }
    })
    sections.push({ attribute: attribute.attribute, header, top, rowH, heat, stepsBand, rows: laid })
    y = top + drawn.length * rowH
  }
  return { family: lane.family, width, plotLeft, plotWidth, height: Math.max(y, 1), sections }
}

/** An element's own words under its fixture's name: what follows the name, else its number. */
function elementLabel(label: string, fixture: string, element: number): string {
  if (label.startsWith(fixture)) {
    const rest = label.slice(fixture.length).replace(/^[\s·:,-]+/, '')
    if (rest !== '') return rest
  }
  return label === fixture ? `${element + 1}` : label
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
