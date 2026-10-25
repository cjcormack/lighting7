import { memo, useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type PointerEvent, type ReactNode } from 'react'
import { Pause, Play, X } from 'lucide-react'
import { cn } from '@/lib/utils'
import { EditorReadout } from '@/components/editor/EditorReadout'
import { FAMILY_LABELS } from '@/lib/attributeFamily'
import { paletteAttributeFor } from '@/lib/paletteIntent'
import { dmxToCentre, formatCentre } from '@/lib/positionScale'
import { setFxGraphOption, useFxGraphOptions, type FxGraphOptions } from '@/lib/fxEditorView'
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
import { decodePreview, type DecodedLane, type DecodedPreview } from './fxPreviewDecode'
import {
  GRAPH_WINDOWS,
  GUTTER_PX,
  GraphPlayhead,
  NARROW_ROWS,
  SAMPLES_PER_CYCLE,
  barBeatAt,
  colourAt,
  graphRowOrder,
  isHolding,
  layoutLane,
  phaseAt,
  pickedIndex,
  planeAt,
  playheadClockOf,
  rowAt,
  sampleAt,
  startBeatOf,
  truncatedWords,
  visibleHeight,
  type LaneLayout,
  type PlayheadClock,
} from './fxGraphModel'
import { GraphHandles, beginDrag, useDragCancel } from './GraphHandles'
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
/** The least plot the lanes keep beside the path panel before it moves under them. */
export const MIN_PLOT_PX = 160

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
      linkedRow={linkedRow}
      onHoverRow={onHoverRow}
      editor={coarse || editor?.readOnly === true ? null : editor}
      contextOf={contextOf}
      options={options}
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
  linkedRow,
  onHoverRow,
  editor,
  contextOf,
  options,
  frozen,
  palette,
}: Omit<FxGraphProps, 'empty' | 'contextOf'> & {
  answer: FxPreviewAnswer
  contextOf: GraphContextOf
  options: FxGraphOptions
  frozen: boolean
  palette: GraphPalette
}) {
  const decoded = useMemo(() => decodePreview(answer), [answer])
  const order = useMemo(() => graphRowOrder(answer, options.rigOrder), [answer, options.rigOrder])
  const picked = pickedIndex(answer)
  const pickedFx = picked == null ? null : answer.fx[picked]
  const [measure, width] = useWidth()
  // The path panel sits beside the lanes where that leaves them a plot, else under them.
  const pathBeside = !narrow && width - PATH_PX - 12 >= GUTTER_PX + MIN_PLOT_PX
  const lanesWidth = Math.max(0, width - (hasPosition(decoded) && pathBeside ? PATH_PX + 12 : 0))
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
          order,
          rows: answer.rows,
          stepsAttributes: stepsAttributes(lane),
          maxRows: narrow && !scrollRows ? NARROW_ROWS : undefined,
          fixtureName,
          attributeLabel,
        }),
      ),
    [answer.rows, fixtureName, lanes, lanesWidth, narrow, order, scrollRows],
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
  const hoveredKey = hovered?.key ?? null
  const onHoverRowRef = useRef(onHoverRow)
  onHoverRowRef.current = onHoverRow
  useEffect(() => {
    onHoverRowRef.current?.(hoveredKey)
  }, [hoveredKey])
  // Gone under the pointer, the graph lets go of the row it was resting on.
  useEffect(() => () => onHoverRowRef.current?.(null), [])

  const truncated = truncatedWords(answer.truncated)
  const hiddenRows = narrow && !scrollRows ? Math.max(0, ...layouts.flatMap((l) => l.sections.map((s) => countRows(lanes, s.attribute) - s.rows.length))) : 0
  const scrolled = scrollRows === true && layouts.some((l) => visibleHeight(l, NARROW_ROWS) != null)
  const pathRows = useMemo(() => pathRowsOf(decoded, order, drawOptions), [decoded, drawOptions, order])

  return (
    <div data-fx-graph className="flex min-h-[120px] min-w-0 flex-col">
      <GraphToolbar answer={answer} pickedFx={pickedFx} options={options} solo={solo} onUnsolo={onUnsolo} />
      <div data-fx-graph-readout className="flex min-h-6 flex-wrap items-center gap-x-3 border-b px-3 py-0.5 text-[11px] text-muted-foreground tabular-nums">
        {hovered != null ? (
          <HoverReadout answer={answer} decoded={decoded} hover={hovered} clock={clock} view={options.view} pickedFx={pickedFx} picked={picked} />
        ) : (
          <WindowReadout answer={answer} pickedFx={pickedFx} fxName={fxName} rigOrder={options.rigOrder} />
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
            />
          ))}
          {plotWidth > 0 && (
            <div
              ref={playheadRef}
              data-fx-playhead
              aria-hidden
              className="pointer-events-none absolute top-0 bottom-0 w-[1.5px] bg-sky-500/90"
              style={{ left: GUTTER_PX }}
            />
          )}
          {hovered != null && (
            <div aria-hidden className="pointer-events-none absolute top-0 bottom-0 w-px bg-foreground/35" style={{ left: hovered.x }} />
          )}
        </div>
        </RowScroll>
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

function countRows(lanes: readonly DecodedLane[], attribute: string): number {
  return lanes.flatMap((l) => l.attributes).find((a) => a.attribute === attribute)?.rows.length ?? 0
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
    return at != null && at.section === handleSection ? at : null
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
    const key = at == null ? undefined : answer.rows[at.row.row]?.key
    onHover(s == null || key == null ? null : { sample: s, key, x })
    e.currentTarget.style.cursor = phaseRowAt(x, e.clientY - box.top) != null ? 'ew-resize' : ''
  }

  const family = lane.family
  const word = family != null ? FAMILY_LABELS[family].singular : 'Other'
  const representative = answer.fx.filter((f) => f.representative === true && (f.family ?? null) === family)
  const slider = lane.attributes.some((a) => a.kind === 'SLIDER')
  const own = options.view === 'this' && pickedFx != null && pickedFx.family === family

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
        {editor != null && handleSection != null && pickedFx != null && (
          <GraphHandles layout={layout} section={handleSection} samples={options.samples} fx={pickedFx} rows={answer.rows} editor={editor} />
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
  return (
    <div data-fx-graph-toolbar className="flex min-h-9 flex-wrap items-center gap-2 border-b px-3 py-1">
      <Segment
        label="Window"
        value={options.window}
        choices={GRAPH_WINDOWS.map((w) => ({ value: w.value, label: w.label }))}
        onChange={(v) => setFxGraphOption('window', v)}
      />
      <Segment
        label="Draw"
        value={options.view}
        choices={[
          { value: 'result', label: 'Result', title: 'Each lane after every FX in it, over what is underneath' },
          { value: 'this', label: 'This FX', title: 'The picked FX’s own output, before Amount' },
        ]}
        onChange={(v) => setFxGraphOption('view', v)}
      />
      <Toggle on={options.rigOrder} title="Hold the rows in rig order while the Order changes" onClick={() => setFxGraphOption('rigOrder', !options.rigOrder)}>
        Rig order
      </Toggle>
      <Toggle on={options.asSeen} title="Colour as seen: the colour lane multiplied by the Intensity result" onClick={() => setFxGraphOption('asSeen', !options.asSeen)}>
        As seen
      </Toggle>
      {solo != null && (
        <span data-fx-graph-solo className="inline-flex h-6 items-center gap-1 rounded border border-sky-500/60 pl-1.5 text-[11px] text-sky-700 dark:text-sky-300">
          Solo: {solo.name}
          <button type="button" aria-label="Unsolo" onClick={onUnsolo} className="grid size-5 place-items-center rounded hover:bg-accent">
            <X className="size-3" />
          </button>
        </span>
      )}
      <div className="ml-auto flex items-center gap-1.5">
        {clock.kind === 'master' ? <MasterChip uuid={clock.speedMasterUuid} /> : pickedFx != null && <span className="rounded border px-1.5 text-[11px] text-muted-foreground">Seconds</span>}
        <button
          type="button"
          aria-label={options.paused ? 'Run the playhead' : 'Pause the playhead'}
          title={options.paused ? 'Run the playhead' : 'Pause the playhead — the graph’s, not the stage’s'}
          onClick={() => setFxGraphOption('paused', !options.paused)}
          className="grid size-6 place-items-center rounded-md border text-muted-foreground hover:bg-accent hover:text-foreground"
        >
          {options.paused ? <Play className="size-3" /> : <Pause className="size-3" />}
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
      <span data-fx-graph-bpm className="rounded border px-1.5 text-[11px] tabular-nums text-muted-foreground">
        {master == null ? '—' : `M${master.index} · ${Math.round(master.bpm)}`}
      </span>
      <button type="button" onClick={() => tapSpeedMaster(uuid)} className="h-6 rounded-md border px-2 text-[11px] hover:bg-accent">
        Tap
      </button>
    </>
  )
}

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
    <div role="group" aria-label={label} className="flex h-6 items-center rounded-md border p-0.5">
      {choices.map((c) => (
        <button
          key={c.value}
          type="button"
          aria-pressed={value === c.value}
          title={c.title}
          onClick={() => onChange(c.value)}
          className={cn('h-full rounded px-1.5 text-[11px] text-muted-foreground hover:text-foreground', value === c.value && 'bg-accent font-medium text-foreground')}
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
      className={cn('h-6 rounded-md border px-1.5 text-[11px] text-muted-foreground hover:text-foreground', on && 'bg-accent font-medium text-foreground')}
    >
      {children}
    </button>
  )
}

function WindowReadout({
  answer,
  pickedFx,
  fxName,
  rigOrder,
}: {
  answer: FxPreviewAnswer
  pickedFx: FxPreviewFx | null
  fxName: (fxType: string) => string
  rigOrder: boolean
}) {
  const beats = Math.round(answer.window.beats * 100) / 100
  const seconds = Math.round(answer.window.seconds * 10) / 10
  return (
    <>
      <span>
        Window <b className="font-medium text-foreground">{`${beats} ${beats === 1 ? 'beat' : 'beats'}`}</b> · {seconds} s
      </span>
      {rigOrder ? <span>Rows in rig order</span> : pickedFx != null && <span>{`Rows in ${fxName(pickedFx.fxType)}’s Order`}</span>}
      <span>Hover a row to read it</span>
    </>
  )
}

/** A row's value in every lane at the hovered moment, and the picked FX's phase there. */
function HoverReadout({
  answer,
  decoded,
  hover,
  clock,
  view,
  pickedFx,
  picked,
}: {
  answer: FxPreviewAnswer
  decoded: DecodedPreview
  hover: Hover & { row: number }
  clock: PlayheadClock
  view: FxGraphOptions['view']
  pickedFx: FxPreviewFx | null
  picked: number | null
}) {
  const { sample: s, row } = hover
  const parts: string[] = []
  for (const lane of decoded.lanes) {
    for (const attribute of lane.attributes) {
      const r = attribute.byRow.get(row)
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
  if (pickedFx != null && pickedFx.starts[row] != null) {
    const p = phaseAt(pickedFx, row, s, pickedFx.entry === 0 ? SAMPLES_PER_CYCLE : null)
    if (isHolding(pickedFx.holding[row], s)) phase = 'Phase holding'
    else if (p != null) phase = `Phase ${Math.round(p * 360)}°`
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
      <b className="font-medium text-foreground">{answer.rows[row]?.label}</b>
      {parts.map((p, i) => (
        <span key={i}>{p}</span>
      ))}
      {phase != null && <span>{phase}</span>}
    </>
  )
}

