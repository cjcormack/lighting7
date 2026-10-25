import { useEffect, useMemo, useRef, type ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { EditorReadout } from '@/components/editor/EditorReadout'
import { useIsDarkMode } from '@/hooks/useIsDarkMode'
import { useReducedMotion } from '@/hooks/useReducedMotion'
import type { FxPreviewAnswer } from '@/store/fxPreview'
import { decodePreview, ownOutputOnly } from './fxPreviewDecode'
import {
  GUTTER_PX,
  SAMPLES_PER_CYCLE,
  graphRowOrder,
  layoutLane,
  pickedIndex,
  playheadClockOf,
  startBeatOf,
  truncatedWords,
  visibleHeight,
} from './fxGraphModel'
import { DARK_PALETTE, LIGHT_PALETTE, positionScaleOf, type LaneDrawOptions } from './fxGraphDraw'
import {
  GraphLane,
  MIN_PLOT_PX,
  PATH_PX,
  PathPanel,
  RowScroll,
  attributeLabel,
  hasPosition,
  pathRowsOf,
  scrollHeight,
  stepsAttributes,
  useGraphPlayhead,
  useWidth,
  type GraphContextOf,
} from './FxGraph'

const canvasContext: GraphContextOf = (canvas) => canvas.getContext('2d')
const noHover = () => {}

export interface FxGraphStripProps {
  answer: FxPreviewAnswer | null
  error: string | null
  /** Said before there is an answer: that the desk is being asked, or why nothing is. */
  waiting: string
  /**
   * `result`: the lane after every FX in it, over what is underneath now. `this`: the FX's own output
   * alone, before Amount, with nothing underneath drawn — what a cue's FX or a palette's can honestly say.
   */
  draw: 'result' | 'this'
  /** Over the strip: what it draws on, and what it leaves out. */
  caption?: ReactNode
  fxName: (fxType: string) => string
  fixtureName?: (fixtureKey: string) => string | undefined
  /** Rows shown before the rest are a scroll away. */
  rows?: number
  /** The FX runs under Bounce, so its shape repeats every two cycles: where the playhead wraps. */
  bounce?: boolean
  contextOf?: GraphContextOf
}

const noName = () => undefined

/**
 * **The graph strip** (FX editor plan D15, §4.6): the FX editor's graph cut to one FX's lane, for the
 * hosts that edit one FX without the editor around it — the FX palette editor, the cue editor's FX
 * tab and the busk FX tab. Each host owns its ask (`useFxPreview`); the strip draws the answer with
 * the graph's own lane drawing (`fxGraphDraw.ts`, `layoutLane`) and computes nothing (P2). It has no
 * toolbar, hover read-out, lights, ladder or handles: those are the FX editor's.
 */
export function FxGraphStrip({
  answer,
  error,
  waiting,
  draw,
  caption,
  fxName,
  fixtureName = noName,
  rows = 8,
  bounce = false,
  contextOf = canvasContext,
}: FxGraphStripProps) {
  return (
    <div data-fx-graph-strip={draw} className="flex min-w-0 flex-col gap-1 rounded-md border">
      {caption != null && <div className="px-2.5 pt-1.5 text-[11px] text-muted-foreground">{caption}</div>}
      {answer == null ? (
        <div className="px-2.5 pb-2">
          <EditorReadout error={error ?? undefined}>{waiting}</EditorReadout>
        </div>
      ) : (
        <StripBody
          answer={answer}
          error={error}
          draw={draw}
          fxName={fxName}
          fixtureName={fixtureName}
          rows={rows}
          bounce={bounce}
          contextOf={contextOf}
        />
      )}
    </div>
  )
}

function StripBody({
  answer,
  error,
  draw,
  fxName,
  fixtureName,
  rows,
  bounce,
  contextOf,
}: Required<Omit<FxGraphStripProps, 'waiting' | 'caption'>> & { answer: FxPreviewAnswer }) {
  const dark = useIsDarkMode()
  const palette = dark ? DARK_PALETTE : LIGHT_PALETTE
  const frozen = useReducedMotion()
  const picked = pickedIndex(answer)
  const pickedFx = picked == null ? null : answer.fx[picked]
  const decoded = useMemo(() => {
    const all = decodePreview(answer)
    return draw === 'this' && picked != null ? ownOutputOnly(all, picked, (row) => answer.fx[picked].holding[row]) : all
  }, [answer, draw, picked])
  const lane = useMemo(
    () => decoded.lanes.find((l) => l.family != null && l.family === pickedFx?.family) ?? decoded.lanes[0] ?? null,
    [decoded, pickedFx?.family],
  )
  // Only the strip's own lane: another lane's path is not the picked FX's.
  const own = useMemo(() => ({ ...decoded, lanes: lane == null ? [] : [lane] }), [decoded, lane])
  const [measure, width] = useWidth()
  const position = hasPosition(own)
  const pathBeside = width - PATH_PX - 12 >= GUTTER_PX + MIN_PLOT_PX
  const lanesWidth = Math.max(0, width - (position && pathBeside ? PATH_PX + 12 : 0))
  const order = useMemo(() => graphRowOrder(answer, false), [answer])
  const layout = useMemo(
    () =>
      lane == null
        ? null
        : layoutLane(lane, { width: lanesWidth, order, rows: answer.rows, stepsAttributes: stepsAttributes(lane), fixtureName, attributeLabel }),
    [answer.rows, fixtureName, lane, lanesWidth, order],
  )
  const clock = useMemo(() => playheadClockOf(answer), [answer])
  const positionScale = useMemo(() => positionScaleOf(own.lanes.find((l) => l.family === 'POSITION')), [own])
  const options = useMemo(
    (): LaneDrawOptions => ({
      samples: answer.window.samples,
      beats: answer.window.beats,
      startBeat: startBeatOf(clock),
      view: draw,
      picked,
      pickedFx,
      asSeen: null,
      positionScale,
      palette,
    }),
    [answer.window.beats, answer.window.samples, clock, draw, palette, picked, pickedFx, positionScale],
  )

  const playhead = useGraphPlayhead(answer, frozen, bounce ? 2 * SAMPLES_PER_CYCLE : SAMPLES_PER_CYCLE)
  const playheadRef = useRef<HTMLDivElement>(null)
  const dotsRef = useRef<((sample: number) => void) | null>(null)
  const plotWidth = layout?.plotWidth ?? 0
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

  const pathRows = useMemo(() => pathRowsOf(own, order, options), [own, options, order])
  const truncated = truncatedWords(answer.truncated)
  const scroll = layout != null && visibleHeight(layout, rows) != null ? scrollHeight([layout], rows) : null
  const beats = Math.round(answer.window.beats * 100) / 100
  const seconds = Math.round(answer.window.seconds * 10) / 10

  return (
    <>
      <div className="flex flex-wrap items-center gap-x-3 px-2.5 text-[11px] text-muted-foreground tabular-nums" data-fx-strip-window>
        {pickedFx != null && <b className="font-medium text-foreground">{fxName(pickedFx.fxType)}</b>}
        <span>{`${beats} ${beats === 1 ? 'beat' : 'beats'} · ${seconds} s`}</span>
        <span>{`${answer.rows.length} ${answer.rows.length === 1 ? 'head' : 'heads'}`}</span>
      </div>
      {(error != null || truncated != null || lane == null) && (
        <div className="flex flex-col gap-1 px-2.5">
          {error != null && <EditorReadout error={error} />}
          {truncated != null && <EditorReadout>{truncated}</EditorReadout>}
          {lane == null && <EditorReadout>The FX paints nothing on these heads.</EditorReadout>}
        </div>
      )}
      <div ref={measure} className={cn('flex min-w-0 gap-3 px-2.5 pb-2', !pathBeside && 'flex-col')}>
        {lane != null && layout != null && (
          <RowScroll height={scroll}>
            <div className="relative min-w-0 flex-1" data-fx-graph-lanes>
              <GraphLane
                lane={lane}
                layout={layout}
                options={options}
                answer={answer}
                pickedFx={pickedFx}
                fxName={fxName}
                asSeen={false}
                editor={null}
                contextOf={contextOf}
                onHover={noHover}
                underneath={draw === 'result'}
              />
              {plotWidth > 0 && (
                <div
                  ref={playheadRef}
                  data-fx-playhead
                  aria-hidden
                  className="pointer-events-none absolute top-0 bottom-0 w-[1.5px] bg-sky-500/90"
                  style={{ left: GUTTER_PX }}
                />
              )}
            </div>
          </RowScroll>
        )}
        {pathRows.length > 0 && (
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
    </>
  )
}
