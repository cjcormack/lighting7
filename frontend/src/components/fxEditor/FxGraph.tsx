import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type PointerEvent, type ReactNode } from 'react'
import { ChevronDown, ChevronRight, Pause, Play, X } from 'lucide-react'
import { cn } from '@/lib/utils'
import { EditorReadout } from '@/components/editor/EditorReadout'
import { FAMILY_LABELS } from '@/lib/attributeFamily'
import { paletteAttributeFor } from '@/lib/paletteIntent'
import { dmxToCentre, formatCentre } from '@/lib/positionScale'
import {
  GUTTER_DEFAULT_PX,
  GUTTER_MAX_PX,
  GUTTER_MIN_PX,
  clampGutter,
  setFxGraphOption,
  setFixturesFolded,
  toggleFixtureFold,
  useFoldedFixtures,
  useFxGraphOptions,
  type FxGraphOptions,
} from '@/lib/fxEditorView'
import { useIsDarkMode } from '@/hooks/useIsDarkMode'
import { useReducedMotion } from '@/hooks/useReducedMotion'
import { useCoarsePointer } from '@/hooks/useCoarsePointer'
import type { GraphMark, LinkedEditor } from '@/components/fx/fxGraphLink'
import type { FxPreviewAnswer, FxPreviewFx } from '@/store/fxPreview'
import {
  requestSpeedMasterBeat,
  subscribeToSpeedMasterBeat,
  tapSpeedMaster,
  useSpeedMasterLiveQuery,
} from '@/store/speedMasters'
import { decodePreview, type DecodedAttribute, type DecodedAttributeRow, type DecodedLane, type DecodedPreview } from './fxPreviewDecode'
import {
  GRAPH_WINDOWS,
  GraphPlayhead,
  MIN_PLOT_PX,
  fitGutter,
  NARROW_ROWS,
  SAMPLES_PER_CYCLE,
  barBeatAt,
  colourAt,
  graphRowOrder,
  isHolding,
  layoutLane,
  middleCut,
  mixFixtureOf,
  phaseAt,
  pickedIndex,
  planeAt,
  playheadClockOf,
  rowAt,
  rulerTicks,
  RULER_PX,
  sampleAt,
  startBeatOf,
  truncatedWords,
  visibleHeight,
  type LaidRow,
  type LaneLayout,
  type PlayheadClock,
} from './fxGraphModel'
import { CycleTab, GraphHandles, beginDrag, useDragCancel } from './GraphHandles'
import { phaseFromDrag } from './fxGraphHandles'
import {
  DARK_PALETTE,
  LIGHT_PALETTE,
  drawLane,
  drawPathDots,
  drawPaths,
  paintsSection,
  positionScaleOf,
  type GraphCtx,
  type GraphPalette,
  type LaneDrawOptions,
  type PathRow,
} from './fxGraphDraw'

/** Where a lane's canvas gets its drawing calls: the canvas's own 2D context, or a test's stub. */
export type GraphContextOf = (canvas: HTMLCanvasElement) => GraphCtx | null
const canvasContext: GraphContextOf = (canvas) => canvas.getContext('2d')

export const PATH_PX = 132

export interface FxGraphProps {
  answer: FxPreviewAnswer | null
  error: string | null
  /** No FX reaches the selection, so nothing is asked. */
  empty: boolean
  /** Below the editor's 760 arm: the picked FX's lane alone, at most six rows. */
  narrow: boolean
  /** With [narrow], every row laid out and six of them shown, the rest a scroll away (§4.2's phone arm). */
  scrollRows?: boolean
  fxName: (fxType: string) => string
  fixtureName: (fixtureKey: string) => string | undefined
  /** The FX Solo marks, while the ask carries it. */
  solo: { name: string } | null
  onUnsolo: () => void
  /** The picked FX runs under Bounce, so its shape repeats every two cycles: where the playhead wraps. */
  bounce?: boolean
  /** The mark a hovered control in the inspector changes, lit (FX editor plan §4.5). */
  highlight?: GraphMark | null
  /** A knob the pointer rests on lights what it changes, as the inspector's hover does (follow-ups D4). */
  onHighlight?: (mark: GraphMark | null) => void
  /** A row a ladder dot links to, by key, marked in the gutter. */
  linkedRow?: string | null
  /** The row the pointer rests on, by key, as it moves: the ladder lights its dots. */
  onHoverRow?: (key: string | null) => void
  /** The inspector's editor, while it edits the picked FX: the handles write through it (D12). */
  editor?: LinkedEditor | null
  contextOf?: GraphContextOf
}

/**
 * **The FX editor's graph** (FX editor plan D8, D9, D14, §4.4): what the desk's preview answered,
 * one canvas per lane — Intensity an area per row over a dashed *Underneath*, Colour a strip per row
 * (as the colour, or as seen), Position pan and tilt as two traces with a path panel beside, Beam an
 * area per attribute — its rows in the picked FX's Order, or held in rig order. The marks are the
 * picked FX's: where each row's cycle starts, Segments and Parts bracketed, the Wings' mirror, and
 * hatching where an element holds. The playhead runs on the client from the answer's clock and
 * resyncs on the master's beat. Drawn from the answer alone (P2); the toolbar's choices are the
 * window's (P6).
 */
export function FxGraph({
  answer,
  error,
  empty,
  narrow,
  scrollRows = false,
  fxName,
  fixtureName,
  solo,
  onUnsolo,
  bounce = false,
  highlight = null,
  onHighlight,
  linkedRow = null,
  onHoverRow,
  editor = null,
  contextOf = canvasContext,
}: FxGraphProps) {
  const options = useFxGraphOptions()
  const reducedMotion = useReducedMotion()
  // Handles are pointer-only: under a finger the inspector's fields are the way in.
  const coarse = useCoarsePointer()
  const dark = useIsDarkMode()
  const palette = dark ? DARK_PALETTE : LIGHT_PALETTE

  if (answer == null) {
    return (
      <div data-fx-graph className="flex min-h-[120px] min-w-0 flex-col gap-1 p-3">
        <EditorReadout error={error ?? undefined}>{empty ? 'No FX reaches the selection.' : 'Asking the desk…'}</EditorReadout>
      </div>
    )
  }
  return (
    <GraphBody
      answer={answer}
      error={error}
      narrow={narrow}
      scrollRows={narrow && scrollRows}
      fxName={fxName}
      fixtureName={fixtureName}
      solo={solo}
      onUnsolo={onUnsolo}
      bounce={bounce}
      highlight={highlight}
      onHighlight={onHighlight}
      linkedRow={linkedRow}
      onHoverRow={onHoverRow}
      editor={coarse || editor?.readOnly === true ? null : editor}
      contextOf={contextOf}
      options={options}
      coarse={coarse}
      frozen={options.paused || reducedMotion}
      palette={palette}
    />
  )
}

/** What the pointer rests on: a sample and a row by its key, which outlive a re-ask that reorders the rows. */
export interface Hover {
  sample: number
  key: string
  x: number
}

function GraphBody({
  answer,
  error,
  narrow,
  scrollRows,
  fxName,
  fixtureName,
  solo,
  onUnsolo,
  bounce,
  highlight,
  onHighlight,
  linkedRow,
  onHoverRow,
  editor,
  contextOf,
  options,
  coarse,
  frozen,
  palette,
}: Omit<FxGraphProps, 'empty' | 'contextOf'> & {
  answer: FxPreviewAnswer
  contextOf: GraphContextOf
  options: FxGraphOptions
  coarse: boolean
  frozen: boolean
  palette: GraphPalette
}) {
  const decoded = useMemo(() => decodePreview(answer), [answer])
  const order = useMemo(() => graphRowOrder(answer, options.rigOrder), [answer, options.rigOrder])
  const picked = pickedIndex(answer)
  const pickedFx = picked == null ? null : answer.fx[picked]
  const [measure, width] = useWidth()
  // The name column: the window's width, held at the default under a finger, where it has no grip.
  const [dragGutter, setDragGutter] = useState<number | null>(null)
  const chosenGutter = coarse ? GUTTER_DEFAULT_PX : (dragGutter ?? options.gutter)
  const foldedKeys = useFoldedFixtures()
  const folded = useMemo(() => new Set(foldedKeys), [foldedKeys])
  // The path panel sits beside the lanes where that leaves them a plot, else under them.
  const pathBeside = !narrow && width - PATH_PX - 12 >= chosenGutter + MIN_PLOT_PX
  const lanesWidth = Math.max(0, width - (hasPosition(decoded) && pathBeside ? PATH_PX + 12 : 0))
  // A narrow graph keeps its plot: the names give way first.
  const gutter = fitGutter(chosenGutter, lanesWidth)
  const clock = useMemo(() => playheadClockOf(answer), [answer])

  const lanes = useMemo(() => {
    if (!narrow) return decoded.lanes
    const own = decoded.lanes.find((l) => l.family != null && l.family === pickedFx?.family) ?? decoded.lanes[0]
    return own == null ? [] : [own]
  }, [decoded, narrow, pickedFx?.family])
  const asSeen = useMemo(() => (options.asSeen ? intensityResults(decoded, answer.rows) : null), [answer.rows, decoded, options.asSeen])
  const positionScale = useMemo(() => positionScaleOf(decoded.lanes.find((l) => l.family === 'POSITION')), [decoded])

  const layouts = useMemo(
    () =>
      lanes.map((lane) =>
        layoutLane(lane, {
          width: lanesWidth,
          gutter,
          order,
          rows: answer.rows,
          stepsAttributes: stepsAttributes(lane),
          folded,
          maxRows: narrow && !scrollRows ? NARROW_ROWS : undefined,
          fixtureName,
          attributeLabel,
        }),
      ),
    [answer.rows, fixtureName, folded, gutter, lanes, lanesWidth, narrow, order, scrollRows],
  )
  const linkedIndex = linkedRow == null ? -1 : answer.rows.findIndex((r) => r.key === linkedRow)
  const drawOptions = useMemo(
    (): LaneDrawOptions => ({
      samples: answer.window.samples,
      beats: answer.window.beats,
      startBeat: startBeatOf(clock),
      view: options.view,
      picked,
      pickedFx,
      asSeen,
      positionScale,
      palette,
      highlight,
      linkedRow: linkedIndex < 0 ? null : linkedIndex,
    }),
    [answer.window.beats, answer.window.samples, asSeen, clock, highlight, linkedIndex, options.view, palette, picked, pickedFx, positionScale],
  )
  // The handles are the picked FX's, and only while the inspector edits that FX.
  const handles = editor != null && pickedFx != null && pickedFx.fxId === editor.fxId ? editor : null

  const playhead = useGraphPlayhead(answer, frozen, bounce ? 2 * SAMPLES_PER_CYCLE : SAMPLES_PER_CYCLE)
  const playheadRef = useRef<HTMLDivElement>(null)
  const dotsRef = useRef<((sample: number) => void) | null>(null)
  const plotWidth = layouts[0]?.plotWidth ?? 0
  useEffect(() => {
    let frame = 0
    const paint = () => {
      const s = playhead.sample(performance.now())
      const el = playheadRef.current
      if (el != null) el.style.transform = `translateX(${(s / Math.max(1, answer.window.samples)) * plotWidth}px)`
      dotsRef.current?.(s)
      if (!frozen) frame = requestAnimationFrame(paint)
    }
    paint()
    return () => cancelAnimationFrame(frame)
  }, [answer, frozen, playhead, plotWidth])

  const [hover, setHover] = useState<Hover | null>(null)
  const onHover = useCallback((next: Hover | null) => setHover(next), [])
  const hoverRow = hover == null ? -1 : answer.rows.findIndex((r) => r.key === hover.key)
  const hovered = hover != null && hoverRow >= 0 && hover.sample < answer.window.samples ? { ...hover, row: hoverRow } : null
  const hoverMix = hover != null && hoverRow < 0 && hover.sample < answer.window.samples ? mixedRowOf(layouts, hover.key) : null
  const hoveredKey = hovered?.key ?? null
  const onHoverRowRef = useRef(onHoverRow)
  onHoverRowRef.current = onHoverRow
  useEffect(() => {
    onHoverRowRef.current?.(hoveredKey)
  }, [hoveredKey])
  // Gone under the pointer, the graph lets go of the row it was resting on.
  useEffect(() => () => onHoverRowRef.current?.(null), [])

  const truncated = truncatedWords(answer.truncated)
  const hiddenRows = narrow && !scrollRows ? Math.max(0, ...layouts.flatMap((l) => l.sections.map((s) => s.cut))) : 0
  const scrolled = scrollRows === true && layouts.some((l) => visibleHeight(l, NARROW_ROWS) != null)
  const pathRows = useMemo(() => pathRowsOf(decoded, order, drawOptions), [decoded, drawOptions, order])

  return (
    <div data-fx-graph className="flex min-h-[120px] min-w-0 flex-col">
      <GraphToolbar answer={answer} pickedFx={pickedFx} options={options} solo={solo} onUnsolo={onUnsolo} />
      <div data-fx-graph-readout className="flex min-h-6 flex-wrap items-center gap-x-3 border-b px-3 py-0.5 text-[11px] text-muted-foreground tabular-nums">
        {hovered != null || (hover != null && hoverMix != null) ? (
          <HoverReadout
            answer={answer}
            decoded={decoded}
            sample={(hovered ?? hover)!.sample}
            at={hovered != null ? { row: hovered.row } : { mix: hoverMix! }}
            clock={clock}
            view={options.view}
            pickedFx={pickedFx}
            picked={picked}
          />
        ) : (
          <WindowReadout pickedFx={pickedFx} fxName={fxName} rigOrder={options.rigOrder} />
        )}
      </div>
      {(error != null || truncated != null || narrow) && (
        <div className="flex flex-col gap-1 px-3 pt-2">
          {error != null && <EditorReadout error={error} />}
          {truncated != null && <EditorReadout>{truncated}</EditorReadout>}
          {narrow && (
            <EditorReadout>
              {`Narrow: the picked FX’s lane only${hiddenRows > 0 ? `, its first ${NARROW_ROWS} rows` : scrolled ? `, ${NARROW_ROWS} rows at a time — scroll for more` : ''}.`}
            </EditorReadout>
          )}
        </div>
      )}
      <div ref={measure} className={cn('flex min-w-0 gap-3 px-3 py-2', !pathBeside && 'flex-col')}>
        <div className="flex min-w-0 flex-1 flex-col">
        {plotWidth > 0 && (
          <GraphRuler
            width={lanesWidth}
            plotLeft={gutter}
            plotWidth={plotWidth}
            answer={answer}
            clock={clock}
            palette={palette}
            contextOf={contextOf}
          >
            {handles != null && (
              <CycleTab plotLeft={gutter} plotWidth={plotWidth} samples={answer.window.samples} editor={handles} lit={highlight === 'cycle'} onHighlight={onHighlight} />
            )}
          </GraphRuler>
        )}
        <RowScroll height={scrolled ? scrollHeight(layouts, NARROW_ROWS) : null}>
        <div className="relative min-w-0 flex-1" data-fx-graph-lanes>
          {lanes.map((lane, i) => (
            <GraphLane
              key={lane.family ?? `other-${i}`}
              lane={lane}
              layout={layouts[i]}
              options={drawOptions}
              answer={answer}
              pickedFx={pickedFx}
              fxName={fxName}
              asSeen={options.asSeen}
              editor={handles}
              contextOf={contextOf}
              onHover={onHover}
              onToggleFold={toggleFixtureFold}
              onFoldAll={setFixturesFolded}
              onHighlight={onHighlight}
            />
          ))}
          {plotWidth > 0 && (
            <div
              ref={playheadRef}
              data-fx-playhead
              aria-hidden
              className="pointer-events-none absolute top-0 bottom-0 w-[1.5px] bg-sky-500/90"
              style={{ left: gutter }}
            />
          )}
          {!coarse && plotWidth > 0 && (
            <GutterGrip
              gutter={gutter}
              chosen={chosenGutter}
              onDrag={setDragGutter}
              onSet={(px) => {
                setDragGutter(null)
                setFxGraphOption('gutter', clampGutter(px))
              }}
            />
          )}
          {hovered != null && (
            <div aria-hidden className="pointer-events-none absolute top-0 bottom-0 w-px bg-foreground/35" style={{ left: hovered.x }} />
          )}
        </div>
        </RowScroll>
        </div>
        {pathRows.length > 0 && (narrow ? lanes.some((l) => l.family === 'POSITION') : true) && (
          <PathPanel
            rows={pathRows}
            samples={answer.window.samples}
            scale={positionScale}
            palette={palette}
            contextOf={contextOf}
            dotsRef={dotsRef}
            playhead={playhead}
          />
        )}
      </div>
    </div>
  )
}

/** The lanes in a scroller [height] tall, or as they are with none. */
export function RowScroll({ height, children }: { height: number | null; children: ReactNode }) {
  if (height == null) return <>{children}</>
  return (
    <div data-fx-graph-scroll className="min-w-0 flex-1 overflow-y-auto" style={{ maxHeight: height }}>
      {children}
    </div>
  )
}

/** The lanes' height that shows [rows] rows of each, its header and foot included. */
export function scrollHeight(layouts: readonly LaneLayout[], rows: number): number {
  return layouts.reduce((h, l) => h + LANE_HEADER_PX + (visibleHeight(l, rows) ?? l.height) + LANE_FOOT_PX, 0)
}

/** A lane's word line over its canvas (`h-5`) and the space under it (`pb-2`). */
const LANE_HEADER_PX = 20
const LANE_FOOT_PX = 8

export function hasPosition(decoded: DecodedPreview): boolean {
  return decoded.lanes.some((l) => l.attributes.some((a) => a.kind === 'POSITION'))
}

export const attributeLabel = (attribute: string) => paletteAttributeFor(attribute)?.label ?? attribute

export function stepsAttributes(lane: DecodedLane): Set<string> {
  return new Set(lane.attributes.filter((a) => a.rows.some((r) => r.fx.some((o) => o.output.steps != null))).map((a) => a.attribute))
}

/**
 * *Colour as seen*'s multiplier for every row: its own Intensity result, else its fixture's — a
 * cell's colour under the bar's one dimmer.
 */
function intensityResults(decoded: DecodedPreview, rows: FxPreviewAnswer['rows']): Map<number, Uint8Array> {
  const lane = decoded.lanes.find((l) => l.family === 'INTENSITY')
  const attribute = lane?.attributes.find((a) => a.attribute === 'dimmer') ?? lane?.attributes.find((a) => a.kind === 'SLIDER')
  const byKey = new Map(rows.map((r, i) => [r.key, i]))
  const out = new Map<number, Uint8Array>()
  rows.forEach((row, i) => {
    const own = attribute?.byRow.get(i) ?? attribute?.byRow.get(byKey.get(row.fixtureKey) ?? -1)
    const level = own?.result.planes.level
    if (level != null) out.set(i, level)
  })
  return out
}

export function pathRowsOf(decoded: DecodedPreview, order: readonly number[], options: LaneDrawOptions): PathRow[] {
  const attribute = decoded.lanes.flatMap((l) => l.attributes).find((a) => a.kind === 'POSITION')
  if (attribute == null) return []
  const out: PathRow[] = []
  for (const r of order) {
    const row = attribute.byRow.get(r)
    if (row == null) continue
    const own = options.view === 'this' ? row.fx.find((o) => o.fx === options.picked)?.output : undefined
    const series = own ?? row.result
    const pan = series.planes.pan
    const tilt = series.planes.tilt
    if (pan == null || tilt == null) continue
    out.push({ pan, tilt, colour: `hsl(${(250 + out.length * 47) % 360},70%,58%)` })
  }
  return out
}

/** The lanes' column width, measured, and followed while a ResizeObserver can say. */
export function useWidth(): [(el: HTMLElement | null) => void, number] {
  const [width, setWidth] = useState(0)
  const observer = useRef<ResizeObserver | null>(null)
  const ref = useCallback((el: HTMLElement | null) => {
    observer.current?.disconnect()
    observer.current = null
    if (el == null) return
    const read = () => {
      const w = Math.floor(el.getBoundingClientRect().width) - 24
      if (w > 0) setWidth(w)
    }
    read()
    if (typeof ResizeObserver === 'function') {
      observer.current = new ResizeObserver(read)
      observer.current.observe(el)
    }
  }, [])
  return [ref, width]
}

/**
 * The playhead over an answer: from sample 0 when it lands, the master's tick carried across a
 * re-ask, resynced on the master's beat; held while [frozen] (the graph's pause, or reduced motion).
 */
export function useGraphPlayhead(answer: FxPreviewAnswer | null, frozen: boolean, periodSamples = SAMPLES_PER_CYCLE): GraphPlayhead {
  const [playhead] = useState(() => new GraphPlayhead())
  const clock = answer == null ? null : playheadClockOf(answer)
  const uuid = clock?.kind === 'master' ? clock.speedMasterUuid : null

  useLayoutEffect(() => {
    if (answer != null) playhead.setAnswer(answer.window, playheadClockOf(answer), performance.now(), periodSamples)
  }, [answer, periodSamples, playhead])
  // Let go, it runs on from where it was held, behind the master by the hold: a beat puts it back.
  const wasFrozen = useRef(frozen)
  useLayoutEffect(() => {
    playhead.setFrozen(frozen, performance.now())
    if (wasFrozen.current && !frozen && playhead.masterUuid != null) requestSpeedMasterBeat(playhead.masterUuid)
    wasFrozen.current = frozen
  }, [frozen, playhead])
  useEffect(() => {
    if (uuid == null) return
    const subscription = subscribeToSpeedMasterBeat(uuid, (beat) => playhead.resync(beat, performance.now()))
    return () => subscription.unsubscribe()
  }, [playhead, uuid])
  // A fresh answer on the master already followed: ask for a beat now rather than wait out the
  // desk's throttle. A new master's subscription asks for one itself.
  const followed = useRef<string | null>(null)
  useEffect(() => {
    if (uuid != null && followed.current === uuid) requestSpeedMasterBeat(uuid)
    followed.current = uuid
  }, [answer, uuid])
  return playhead
}

export const GraphLane = memo(function GraphLane({
  lane,
  layout,
  options,
  answer,
  pickedFx,
  fxName,
  asSeen,
  editor,
  contextOf,
  onHover,
  onToggleFold,
  onFoldAll,
  onHighlight,
  underneath = true,
}: {
  lane: DecodedLane
  layout: LaneLayout
  options: LaneDrawOptions
  answer: FxPreviewAnswer
  pickedFx: FxPreviewFx | null
  fxName: (fxType: string) => string
  asSeen: boolean
  editor: LinkedEditor | null
  contextOf: GraphContextOf
  onHover: (hover: Hover | null) => void
  /** Folds a fixture's elements under its row; without it, a fixture row has no chevron. */
  onToggleFold?: (fixtureKey: string) => void
  /** Folds or unfolds every listed fixture: the lane's way out of a heat map, which draws no chevrons. */
  onFoldAll?: (fixtureKeys: readonly string[], folded: boolean) => void
  onHighlight?: (mark: GraphMark | null) => void
  /** The key names *Underneath*: false where the lane draws none. */
  underneath?: boolean
}) {
  const ref = useRef<HTMLCanvasElement>(null)
  const cancel = useDragCancel()
  // The picked FX's own section of its own lane, where its handles go.
  const handleSection =
    editor == null || pickedFx == null || pickedFx.family !== lane.family
      ? null
      : (layout.sections.find((s) => {
          const attribute = lane.attributes.find((a) => a.attribute === s.attribute)
          return s.attribute === pickedFx.attribute && attribute != null && paintsSection(s, attribute.byRow, options.picked)
        }) ?? null)
  /** A row of the picked FX's section under the pointer, whose block a drag moves sideways: Phase. */
  const phaseRowAt = (x: number, y: number) => {
    if (handleSection == null || sampleAt(layout, options.samples, x) == null) return null
    const at = rowAt(layout, y)
    return at != null && at.section === handleSection && at.row.mix == null ? at : null
  }
  const onPointerDown = (e: PointerEvent<HTMLCanvasElement>) => {
    const box = e.currentTarget.getBoundingClientRect()
    if (editor == null || phaseRowAt(e.clientX - box.left, e.clientY - box.top) == null) return
    const start = editor.draft.phaseOffset
    let last = start
    const perSample = layout.plotWidth / Math.max(1, options.samples)
    beginDrag(
      e,
      cancel,
      (dx) => {
        last = phaseFromDrag(start, dx / perSample)
        editor.set({ phaseOffset: last }, false)
      },
      (moved) => {
        if (moved && last !== start) editor.set({ phaseOffset: last }, true)
      },
    )
  }
  useLayoutEffect(() => {
    const canvas = ref.current
    if (canvas == null || layout.width <= 0) return
    const ctx = prepare(canvas, layout.width, layout.height, contextOf)
    if (ctx != null) drawLane(ctx, layout, lane, options)
  }, [contextOf, lane, layout, options])

  const onPointerMove = (e: PointerEvent<HTMLCanvasElement>) => {
    const box = e.currentTarget.getBoundingClientRect()
    const x = e.clientX - box.left
    const s = sampleAt(layout, options.samples, x)
    const at = rowAt(layout, e.clientY - box.top)
    onHover(s == null || at == null ? null : { sample: s, key: at.row.key, x })
    e.currentTarget.style.cursor = phaseRowAt(x, e.clientY - box.top) != null ? 'ew-resize' : ''
  }

  const family = lane.family
  const word = family != null ? FAMILY_LABELS[family].singular : 'Other'
  const representative = answer.fx.filter((f) => f.representative === true && (f.family ?? null) === family)
  const slider = lane.attributes.some((a) => a.kind === 'SLIDER')
  const own = options.view === 'this' && pickedFx != null && pickedFx.family === family
  const fixtureRows = layout.sections.flatMap((s) => s.rows.filter((r) => r.kind === 'fixture'))
  const foldKeys = [...new Set(fixtureRows.map((r) => r.fixtureKey))]
  const allFolded = fixtureRows.length > 0 && fixtureRows.every((r) => r.folded)

  return (
    <div data-fx-graph-lane={family ?? 'OTHER'} className="pb-2">
      <div className="flex h-5 items-center gap-2 text-[10.5px] text-muted-foreground">
        <span className="text-[11px] font-semibold tracking-wide uppercase">{word}</span>
        {family === 'COLOUR' ? (
          <span>{asSeen ? 'as seen' : 'as the colour'}</span>
        ) : family === 'POSITION' ? (
          <>
            <Key colour="bg-sky-500">Pan</Key>
            <Key colour="bg-amber-500">Tilt</Key>
            <span>{`±${options.positionScale} steps about the centre`}</span>
          </>
        ) : slider ? (
          <>
            <Key colour={own ? 'bg-violet-500' : 'bg-zinc-400'}>{own ? 'This FX' : 'Result'}</Key>
            {!own && pickedFx?.family === family && <Key colour="bg-violet-500">This FX</Key>}
            {underneath && <Key colour="border-t border-dashed border-zinc-400 bg-transparent">Underneath</Key>}
          </>
        ) : null}
        {onFoldAll != null && foldKeys.length > 0 && (
          <button
            type="button"
            data-fx-fold-all
            onClick={() => onFoldAll(foldKeys, !allFolded)}
            className="ml-auto rounded px-1 text-[10.5px] text-muted-foreground hover:bg-accent hover:text-foreground"
          >
            {allFolded ? 'Show elements' : 'Fold elements'}
          </button>
        )}
        {representative.map((f) => (
          <span
            key={`${f.fxId ?? f.entry}`}
            data-fx-representative
            className="rounded border px-1 text-[9.5px]"
            title="A stateful FX: the graph draws its shape, not the stage’s frames"
          >
            {fxName(f.fxType)} · representative
          </span>
        ))}
      </div>
      <div className="relative" style={{ width: layout.width }}>
        <canvas
          ref={ref}
          aria-label={`${word} graph`}
          data-fx-graph-canvas={family ?? 'OTHER'}
          title={handleSection != null ? 'Drag a row sideways to set Phase' : undefined}
          style={{ width: layout.width, height: layout.height, display: 'block' }}
          onPointerMove={onPointerMove}
          onPointerDown={onPointerDown}
          onPointerLeave={() => onHover(null)}
        />
        <GutterLabels layout={layout} onToggleFold={onToggleFold} />
        {editor != null && handleSection != null && pickedFx != null && (
          <GraphHandles
            layout={layout}
            section={handleSection}
            samples={options.samples}
            fx={pickedFx}
            rows={answer.rows}
            editor={editor}
            highlight={options.highlight}
            onHighlight={onHighlight}
          />
        )}
      </div>
    </div>
  )
})

function Key({ colour, children }: { colour: string; children: ReactNode }) {
  return (
    <span className="inline-flex items-center gap-1 whitespace-nowrap">
      <i className={cn('inline-block h-0.5 w-3.5', colour)} />
      {children}
    </span>
  )
}

function prepare(canvas: HTMLCanvasElement, width: number, height: number, contextOf: GraphContextOf): GraphCtx | null {
  const dpr = Math.min(2, typeof window === 'undefined' ? 1 : window.devicePixelRatio || 1)
  canvas.width = Math.round(width * dpr)
  canvas.height = Math.round(height * dpr)
  const ctx = contextOf(canvas)
  if (ctx != null && 'setTransform' in ctx && typeof ctx.setTransform === 'function') ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  return ctx
}

/** The path panel: each element's pan and tilt over the window, a dot where the playhead is. */
export function PathPanel({
  rows,
  samples,
  scale,
  palette,
  contextOf,
  dotsRef,
  playhead,
}: {
  rows: readonly PathRow[]
  samples: number
  scale: number
  palette: GraphPalette
  contextOf: GraphContextOf
  dotsRef: { current: ((sample: number) => void) | null }
  playhead: GraphPlayhead
}) {
  const paths = useRef<HTMLCanvasElement>(null)
  const dots = useRef<HTMLCanvasElement>(null)
  useLayoutEffect(() => {
    const canvas = paths.current
    if (canvas == null) return
    const ctx = prepare(canvas, PATH_PX, PATH_PX, contextOf)
    if (ctx != null) drawPaths(ctx, PATH_PX, rows, samples, scale, palette)
  }, [contextOf, palette, rows, samples, scale])
  useLayoutEffect(() => {
    const canvas = dots.current
    if (canvas == null) return
    const ctx = prepare(canvas, PATH_PX, PATH_PX, contextOf)
    dotsRef.current = ctx == null ? null : (s) => drawPathDots(ctx, PATH_PX, rows, s, scale)
    // Preparing clears the canvas, and a held playhead asks for no frame that would draw them again.
    dotsRef.current?.(playhead.sample(performance.now()))
    return () => {
      dotsRef.current = null
    }
  }, [contextOf, dotsRef, playhead, rows, scale])
  return (
    <div data-fx-graph-path className="flex shrink-0 flex-col gap-1">
      <span className="text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">Path</span>
      <div className="relative rounded-md border bg-card" style={{ width: PATH_PX, height: PATH_PX }}>
        <canvas ref={paths} aria-label="Position paths" style={{ width: PATH_PX, height: PATH_PX, display: 'block' }} />
        <canvas ref={dots} aria-hidden className="absolute inset-0" style={{ width: PATH_PX, height: PATH_PX }} />
      </div>
      <span className="max-w-[132px] text-[10px] text-muted-foreground">Pan across, tilt up; a dot where the playhead is.</span>
    </div>
  )
}

function GraphToolbar({
  answer,
  pickedFx,
  options,
  solo,
  onUnsolo,
}: {
  answer: FxPreviewAnswer
  pickedFx: FxPreviewFx | null
  options: FxGraphOptions
  solo: { name: string } | null
  onUnsolo: () => void
}) {
  const clock = playheadClockOf(answer)
  const beats = Math.round(answer.window.beats * 100) / 100
  const seconds = Math.round(answer.window.seconds * 10) / 10
  return (
    <div data-fx-graph-toolbar className="@container flex min-h-11 flex-wrap items-center gap-x-2.5 gap-y-1.5 border-b px-3 py-1.5">
      <Segment
        label="Window"
        value={options.window}
        choices={GRAPH_WINDOWS.map((w) => ({ value: w.value, label: w.label }))}
        onChange={(v) => setFxGraphOption('window', v)}
      />
      <span data-fx-graph-length className="font-mono text-[11.5px] whitespace-nowrap text-muted-foreground tabular-nums">
        {`${beats} ${beats === 1 ? 'beat' : 'beats'} · ${seconds} s`}
      </span>
      <Segment
        label="Draw"
        value={options.view}
        choices={[
          { value: 'result', label: 'Result', title: 'Each lane after every FX in it, over what is underneath' },
          { value: 'this', label: 'This FX', title: 'The picked FX’s own output, before Amount' },
        ]}
        onChange={(v) => setFxGraphOption('view', v)}
      />
      <div role="group" aria-label="Rows and colour" className={SEGMENT_CLASS}>
        <Toggle on={options.rigOrder} title="Hold the rows in rig order while the Order changes" onClick={() => setFxGraphOption('rigOrder', !options.rigOrder)}>
          Rig order
        </Toggle>
        <Toggle on={options.asSeen} title="Colour as seen: the colour lane multiplied by the Intensity result" onClick={() => setFxGraphOption('asSeen', !options.asSeen)}>
          As seen
        </Toggle>
      </div>
      {solo != null && (
        <span data-fx-graph-solo className="inline-flex h-7 items-center gap-1 rounded-md border border-sky-500/60 pl-2 text-xs text-sky-700 dark:text-sky-300">
          Solo: {solo.name}
          <button type="button" aria-label="Unsolo" onClick={onUnsolo} className="grid size-6 place-items-center rounded hover:bg-accent">
            <X className="size-3" />
          </button>
        </span>
      )}
      <div className="ml-auto flex items-center gap-1.5">
        {clock.kind === 'master' ? (
          <MasterChip uuid={clock.speedMasterUuid} />
        ) : (
          pickedFx != null && <span className="inline-flex h-7 items-center rounded-md border px-2 text-xs text-muted-foreground">Seconds</span>
        )}
        <button
          type="button"
          aria-label={options.paused ? 'Run the playhead' : 'Pause the playhead'}
          title={options.paused ? 'Run the playhead' : 'Pause the playhead — the graph’s, not the stage’s'}
          onClick={() => setFxGraphOption('paused', !options.paused)}
          className="grid size-7 place-items-center rounded-md border text-muted-foreground hover:bg-accent hover:text-foreground"
        >
          {options.paused ? <Play className="size-3.5" /> : <Pause className="size-3.5" />}
        </button>
      </div>
    </div>
  )
}

/** The picked FX's master and its BPM, with Tap. */
function MasterChip({ uuid }: { uuid: string }) {
  const { master } = useSpeedMasterLiveQuery(undefined, {
    selectFromResult: ({ data }) => {
      const m = data?.find((x) => x.uuid === uuid)
      return { master: m == null ? null : { index: m.index, bpm: m.bpm } }
    },
  })
  return (
    <>
      <span data-fx-graph-bpm className="inline-flex h-7 items-center rounded-md border px-2 font-mono text-[11.5px] tabular-nums">
        {master == null ? '—' : `M${master.index} · ${Math.round(master.bpm)}`}
      </span>
      <button type="button" onClick={() => tapSpeedMaster(uuid)} className="h-7 rounded-md border px-2.5 text-xs hover:bg-accent">
        Tap
      </button>
    </>
  )
}

/**
 * The toolbar's 28px segment, its label inside it (follow-ups D4). Below 300px of toolbar — the side
 * form's graph beside an open inspector — the label folds (the group's name keeps it) and the
 * buttons may wrap inside the segment rather than past the graph's edge.
 */
const SEGMENT_CLASS =
  'flex h-7 items-center gap-0.5 rounded-md border bg-background p-0.5 @max-[300px]:h-auto @max-[300px]:min-h-7 @max-[300px]:flex-wrap'

function Segment<T extends string>({
  label,
  value,
  choices,
  onChange,
}: {
  label: string
  value: T
  choices: readonly { value: T; label: string; title?: string }[]
  onChange: (value: T) => void
}) {
  return (
    <div role="group" aria-label={label} className={SEGMENT_CLASS}>
      <span aria-hidden className="pr-1 pl-1.5 text-[10.5px] tracking-wide text-muted-foreground uppercase @max-[300px]:hidden">
        {label}
      </span>
      {choices.map((c) => (
        <button
          key={c.value}
          type="button"
          aria-pressed={value === c.value}
          title={c.title}
          onClick={() => onChange(c.value)}
          className={cn(
            'h-[22px] rounded px-2 text-xs whitespace-nowrap text-foreground/75 hover:text-foreground',
            value === c.value && 'bg-accent font-semibold text-foreground shadow-[inset_0_0_0_1px_var(--border)]',
          )}
        >
          {c.label}
        </button>
      ))}
    </div>
  )
}

function Toggle({ on, title, onClick, children }: { on: boolean; title: string; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      aria-pressed={on}
      title={title}
      onClick={onClick}
      className={cn(
        'h-[22px] rounded px-2 text-xs whitespace-nowrap text-foreground/75 hover:text-foreground',
        on && 'bg-accent font-semibold text-foreground shadow-[inset_0_0_0_1px_var(--border)]',
      )}
    >
      {children}
    </button>
  )
}

function WindowReadout({ pickedFx, fxName, rigOrder }: { pickedFx: FxPreviewFx | null; fxName: (fxType: string) => string; rigOrder: boolean }) {
  return (
    <>
      {rigOrder ? <span>Rows in rig order</span> : pickedFx != null && <span>{`Rows in ${fxName(pickedFx.fxType)}’s Order`}</span>}
      <span>Hover a row to read it</span>
    </>
  )
}

/**
 * **The beat ruler** over the lanes (follow-ups D4): bars and beats on the picked FX's master, from
 * the tick the window started on, or seconds on its own clock. Its children — the Cycle tab — sit on
 * it, at the plot's own x.
 */
function GraphRuler({
  width,
  plotLeft,
  plotWidth,
  answer,
  clock,
  palette,
  contextOf,
  children,
}: {
  width: number
  plotLeft: number
  plotWidth: number
  answer: FxPreviewAnswer
  clock: PlayheadClock
  palette: GraphPalette
  contextOf: GraphContextOf
  children?: ReactNode
}) {
  const ref = useRef<HTMLCanvasElement>(null)
  const ticks = useMemo(() => rulerTicks(clock, answer.window, plotLeft, plotWidth), [answer.window, clock, plotLeft, plotWidth])
  useLayoutEffect(() => {
    const canvas = ref.current
    if (canvas == null || width <= 0) return
    const ctx = prepare(canvas, width, RULER_PX, contextOf)
    if (ctx == null) return
    ctx.clearRect(0, 0, width, RULER_PX)
    ctx.fillStyle = palette.grid
    ctx.fillRect(plotLeft, RULER_PX - 1, plotWidth, 1)
    ctx.font = '10.5px ui-monospace, Menlo, monospace'
    ctx.textBaseline = 'alphabetic'
    ctx.textAlign = 'left'
    for (const tick of ticks) {
      const x = Math.round(tick.x)
      ctx.fillStyle = tick.major ? palette.text : palette.dim
      ctx.fillRect(x, tick.major ? 12 : 17, 1, tick.major ? 12 : 7)
      if (tick.label != null) ctx.fillText(tick.label, x + 3, 10)
    }
  }, [contextOf, palette, plotLeft, plotWidth, ticks, width])
  return (
    <div data-fx-graph-ruler className="relative shrink-0" style={{ width, height: RULER_PX }}>
      <canvas ref={ref} aria-label="Beat ruler" style={{ width, height: RULER_PX, display: 'block' }} />
      {children}
    </div>
  )
}

/**
 * The grip between the names and the plot (D6): dragged, or stepped 8px by the arrow keys, it sets
 * the window's width ([chosen]); it sits at the width drawn ([gutter]), which a narrow graph narrows.
 */
function GutterGrip({
  gutter,
  chosen,
  onDrag,
  onSet,
}: {
  gutter: number
  chosen: number
  onDrag: (px: number | null) => void
  onSet: (px: number) => void
}) {
  const cancel = useDragCancel()
  const [dragging, setDragging] = useState(false)
  // A grip gone mid-drag (the plot emptied, a finger arrived) lets go of the width it was showing.
  const dragRef = useRef<{ dragging: boolean; onDrag: typeof onDrag }>({ dragging, onDrag })
  dragRef.current = { dragging, onDrag }
  useEffect(() => () => {
    if (dragRef.current.dragging) dragRef.current.onDrag(null)
  }, [])
  const onPointerDown = (e: PointerEvent<HTMLDivElement>) => {
    const from = chosen
    let last = from
    const started = beginDrag(
      e,
      cancel,
      (dx) => {
        last = clampGutter(from + dx)
        onDrag(last)
      },
      (moved) => {
        setDragging(false)
        if (moved) onSet(last)
        else onDrag(null)
      },
    )
    if (started) setDragging(true)
  }
  return (
    <div
      data-fx-gutter-grip
      role="separator"
      aria-orientation="vertical"
      aria-label="Name column width"
      aria-valuenow={chosen}
      aria-valuemin={GUTTER_MIN_PX}
      aria-valuemax={GUTTER_MAX_PX}
      tabIndex={0}
      onPointerDown={onPointerDown}
      onKeyDown={(e) => {
        const step = e.key === 'ArrowLeft' ? -8 : e.key === 'ArrowRight' ? 8 : 0
        if (step === 0) return
        e.preventDefault()
        onSet(chosen + step)
      }}
      className="group absolute top-0 bottom-0 flex w-[9px] cursor-col-resize touch-none justify-end outline-none"
      style={{ left: gutter - 9 }}
    >
      <i
        className={cn(
          'block h-full w-px bg-border group-hover:w-[3px] group-hover:rounded-sm group-hover:bg-violet-500 group-focus-visible:w-[3px] group-focus-visible:bg-violet-500',
          dragging && 'w-[3px] rounded-sm bg-violet-500',
        )}
      />
    </div>
  )
}

/** Room the brackets at the gutter's right edge keep. */
const BRACKET_ROOM_PX = 16
const ELEMENT_INDENT_PX = 18
const CHEVRON_PX = 16

/**
 * The gutter's names (D5, D6): a fixture's own row in its name — cut in the middle to fit, whole on
 * hover — with a chevron that folds its elements; an element in its own words, else its number; any
 * other row its label. A heat map draws none.
 */
function GutterLabels({ layout, onToggleFold }: { layout: LaneLayout; onToggleFold?: (fixtureKey: string) => void }) {
  const room = layout.plotLeft - BRACKET_ROOM_PX
  return (
    <div data-fx-graph-gutter className="pointer-events-none absolute top-0 left-0" style={{ width: layout.plotLeft, height: layout.height }}>
      {layout.sections.flatMap((section) =>
        section.heat
          ? []
          : section.rows.map((laid) => (
              <GutterLabel key={`${section.attribute}-${laid.key}`} laid={laid} rowH={section.rowH} room={room} onToggleFold={onToggleFold} />
            )),
      )}
    </div>
  )
}

function GutterLabel({ laid, rowH, room, onToggleFold }: { laid: LaidRow; rowH: number; room: number; onToggleFold?: (fixtureKey: string) => void }) {
  const style = { top: laid.y, height: rowH }
  if (laid.kind === 'element') {
    return (
      <span
        data-fx-graph-label={laid.key}
        title={laid.label}
        className="pointer-events-auto absolute left-0 flex items-center text-[11px] whitespace-nowrap text-muted-foreground"
        style={{ ...style, paddingLeft: ELEMENT_INDENT_PX }}
      >
        {elementWords(laid, room - ELEMENT_INDENT_PX)}
      </span>
    )
  }
  const fixture = laid.kind === 'fixture'
  const foldable = fixture && onToggleFold != null
  const name = middleCut(laid.label, room - (fixture ? CHEVRON_PX : 4), labelWidth(fixture))
  const words = (
    <>
      {fixture && (
        <span aria-hidden className="grid w-3 shrink-0 place-items-center text-muted-foreground">
          {foldable ? laid.folded ? <ChevronRight className="size-3" /> : <ChevronDown className="size-3" /> : null}
        </span>
      )}
      <span className="truncate">{name}</span>
    </>
  )
  const title = laid.mix != null ? `${laid.label} · mix of ${laid.mix.length}` : laid.label
  if (foldable) {
    return (
      <button
        type="button"
        data-fx-graph-label={laid.key}
        aria-expanded={!laid.folded}
        aria-label={`${laid.label} — ${laid.folded ? 'show its elements' : 'fold its elements'}`}
        title={title}
        onClick={() => onToggleFold(laid.fixtureKey)}
        className="pointer-events-auto absolute left-0 flex items-center gap-1 rounded-sm pl-0.5 text-[11px] font-semibold whitespace-nowrap text-foreground hover:bg-accent/60"
        style={{ ...style, maxWidth: room + 4 }}
      >
        {words}
      </button>
    )
  }
  return (
    <span
      data-fx-graph-label={laid.key}
      title={title}
      className={cn('pointer-events-auto absolute left-0 flex items-center gap-1 pl-1 text-[11px] whitespace-nowrap text-foreground', fixture && 'font-semibold')}
      style={style}
    >
      {words}
    </span>
  )
}

/** An element's own words where they fit, else its number (D6). */
function elementWords(laid: LaidRow, room: number): string {
  const measure = labelWidth(false)
  if (measure(laid.label) <= room || laid.element == null) return middleCut(laid.label, room, measure)
  return `${laid.element + 1}`
}

const LABEL_FONT = '11px ui-sans-serif, system-ui, sans-serif'
const measureCache = new Map<string, number>()
let measureContext: { font: string; measureText: (text: string) => { width: number } } | null | undefined

/** A gutter label's width in px: measured on an offscreen canvas where there is one, else estimated. */
function labelWidth(bold: boolean): (text: string) => number {
  const font = `${bold ? '600 ' : ''}${LABEL_FONT}`
  return (text) => {
    const key = `${font}|${text}`
    const hit = measureCache.get(key)
    if (hit != null) return hit
    if (measureContext === undefined) {
      measureContext = typeof OffscreenCanvas === 'function' ? (new OffscreenCanvas(1, 1).getContext('2d') ?? null) : null
    }
    let width: number
    if (measureContext != null) {
      measureContext.font = font
      width = measureContext.measureText(text).width
    } else {
      width = text.length * (bold ? 6.6 : 6.2)
    }
    if (measureCache.size > 5000) measureCache.clear()
    measureCache.set(key, width)
    return width
  }
}

/** The mixed row a hovered key names, in any lane. */
function mixedRowOf(layouts: readonly LaneLayout[], key: string): LaidRow | null {
  if (mixFixtureOf(key) == null) return null
  for (const layout of layouts) {
    for (const section of layout.sections) {
      const laid = section.rows.find((r) => r.key === key)
      if (laid != null) return laid
    }
  }
  return null
}

/**
 * A row's value in every lane at the hovered moment, and the picked FX's phase there. A fixture's
 * mixed row (D5) reads each lane through the fixture's own row where it has one, else its elements'
 * mix, as *Fixture · mix of 3*, and has no phase: it has no cycle of its own.
 */
function HoverReadout({
  answer,
  decoded,
  sample: s,
  at,
  clock,
  view,
  pickedFx,
  picked,
}: {
  answer: FxPreviewAnswer
  decoded: DecodedPreview
  sample: number
  at: { row: number } | { mix: LaidRow }
  clock: PlayheadClock
  view: FxGraphOptions['view']
  pickedFx: FxPreviewFx | null
  picked: number | null
}) {
  const mix = 'mix' in at ? at.mix : null
  const rowOf = (attribute: DecodedAttribute): DecodedAttributeRow | undefined => {
    if (mix == null) return attribute.byRow.get((at as { row: number }).row)
    if (attribute.kind === 'POSITION') return undefined
    return attribute.rows.find((r) => answer.rows[r.row]?.key === mix.fixtureKey) ?? attribute.mixed.get(mix.fixtureKey)
  }
  const parts: string[] = []
  for (const lane of decoded.lanes) {
    for (const attribute of lane.attributes) {
      const r = rowOf(attribute)
      if (r == null) continue
      const own = view === 'this' && pickedFx != null && pickedFx.family === lane.family ? r.fx.find((o) => o.fx === picked)?.output : undefined
      const series = own ?? r.result
      const name = attributeLabel(attribute.attribute)
      if (attribute.kind === 'COLOUR') {
        const { r: red, g, b } = colourAt(series, s)
        parts.push(`${name} #${[red, g, b].map((v) => v.toString(16).padStart(2, '0')).join('')}`)
      } else if (attribute.kind === 'POSITION') {
        const pan = planeAt(series, 'pan', s)
        const tilt = planeAt(series, 'tilt', s)
        if (pan != null && tilt != null) parts.push(`Pan, tilt ${formatCentre(dmxToCentre(pan))}, ${formatCentre(dmxToCentre(tilt))}`)
      } else {
        const level = planeAt(series, 'level', s)
        const step = series.steps?.[s]
        if (step != null && series.stepLabels?.[step] != null) parts.push(`${name} ${series.stepLabels[step]}`)
        else if (level != null) parts.push(`${name} ${Math.round(level / 2.55)} %`)
      }
    }
  }
  let phase: string | null = null
  if (mix == null && pickedFx != null) {
    const row = (at as { row: number }).row
    if (pickedFx.starts[row] != null) {
      const p = phaseAt(pickedFx, row, s, pickedFx.entry === 0 ? SAMPLES_PER_CYCLE : null)
      if (isHolding(pickedFx.holding[row], s)) phase = 'Phase holding'
      else if (p != null) phase = `Phase ${Math.round(p * 360)}°`
    }
  }
  const beat = barBeatAt(clock, answer.window, s)
  return (
    <>
      {beat != null ? (
        <span>
          Beat <b className="font-medium text-foreground">{beat.toFixed(2)}</b>
        </span>
      ) : (
        <span>
          <b className="font-medium text-foreground">{((s * answer.window.sampleMs) / 1000).toFixed(2)} s</b>
        </span>
      )}
      <b className="font-medium text-foreground">{mix != null ? `${mix.label} · mix of ${mix.mix?.length ?? 0}` : answer.rows[(at as { row: number }).row]?.label}</b>
      {parts.map((p, i) => (
        <span key={i}>{p}</span>
      ))}
      {phase != null && <span>{phase}</span>}
    </>
  )
}

