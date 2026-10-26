import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import { cn } from '@/lib/utils'
import { getCycleLabel } from '@/components/fx/fxConstants'
import type { GraphMark, LinkedEditor } from '@/components/fx/fxGraphLink'
import type { FxPreviewFx, FxPreviewRow } from '@/store/fxPreview'
import { SAMPLES_PER_CYCLE, levelScale, rowStarts, rungRow, sampleX, type LaidSection, type LaneLayout } from './fxGraphModel'
import { cycleFromDrag, spreadFromDrag, spreadShareOf } from './fxGraphHandles'

/** How far the pointer goes before a press is a drag: a click with a jitter in it edits nothing. */
export const DRAG_SLOP_PX = 3

/**
 * A drag from a primary pointer-down: each move of that pointer told how far it has gone, once it
 * has gone [DRAG_SLOP_PX], and the release whether it moved at all. The window hears the moves, so
 * the drag survives leaving the handle; [cancel] holds what ends it early, for an unmount. False
 * when the press starts no drag.
 */
export function beginDrag(
  e: ReactPointerEvent,
  cancel: { current: (() => void) | null },
  move: (dx: number, dy: number) => void,
  end: (moved: boolean) => void,
): boolean {
  if (e.button !== 0) return false
  e.preventDefault()
  e.stopPropagation()
  cancel.current?.()
  const x0 = e.clientX
  const y0 = e.clientY
  const pointer = e.pointerId
  let moved = false
  const onMove = (ev: PointerEvent) => {
    if (ev.pointerId !== pointer) return
    const dx = ev.clientX - x0
    const dy = ev.clientY - y0
    if (!moved && Math.hypot(dx, dy) < DRAG_SLOP_PX) return
    moved = true
    move(dx, dy)
  }
  const stop = () => {
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
    window.removeEventListener('pointercancel', onUp)
    cancel.current = null
  }
  const onUp = (ev: PointerEvent) => {
    if (ev.pointerId !== pointer) return
    stop()
    end(moved)
  }
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
  window.addEventListener('pointercancel', onUp)
  cancel.current = stop
  return true
}

/** A drag that ends with its component: nothing left listening on the window. */
export function useDragCancel(): { current: (() => void) | null } {
  const cancel = useRef<(() => void) | null>(null)
  useEffect(() => () => cancel.current?.(), [])
  return cancel
}

/** A handle's knob: a violet disc that names itself on hover and lights what it changes. */
function Knob({
  name,
  row,
  x,
  y,
  tip,
  cursor,
  mark,
  onHighlight,
  onPointerDown,
}: {
  name: string
  row?: string
  x: number
  y: number
  tip: string
  cursor: string
  mark: GraphMark
  onHighlight?: (mark: GraphMark | null) => void
  onPointerDown: (e: ReactPointerEvent) => void
}) {
  const [hovered, setHovered] = useState(false)
  // Gone under the pointer, a knob puts its light out; leaving does so at once, so the next knob's survives.
  const lit = useRef(false)
  const highlight = useRef(onHighlight)
  highlight.current = onHighlight
  useEffect(() => () => {
    if (lit.current) highlight.current?.(null)
  }, [])
  return (
    <div
      data-fx-handle={name}
      data-row={row}
      title={tip}
      onPointerDown={onPointerDown}
      onPointerEnter={() => {
        setHovered(true)
        lit.current = true
        onHighlight?.(mark)
      }}
      onPointerLeave={() => {
        setHovered(false)
        lit.current = false
        onHighlight?.(null)
      }}
      className="pointer-events-auto absolute grid size-4 -translate-x-1/2 -translate-y-1/2 place-items-center"
      style={{ left: x, top: y, cursor }}
    >
      <i className="block size-2.5 rounded-full bg-violet-600 shadow-[0_0_0_2px_var(--background),0_0_0_3px_rgb(139_92_246)]" />
      {hovered && (
        <span
          data-fx-knob-tip
          className="pointer-events-none absolute top-1/2 left-4 -translate-y-1/2 rounded border border-violet-600 bg-background px-1 text-[10.5px] font-semibold whitespace-nowrap text-violet-700 dark:text-violet-300"
        >
          {tip}
        </span>
      )}
    </div>
  )
}

/**
 * **The Cycle tab** (FX editor follow-ups D4), on the beat ruler at the first cycle's end, labelled
 * with the cycle: dragged sideways it sets Cycle, snapping to the cycle steps (or to 0.05 s in
 * seconds), and writes through the inspector's editor as the graph's other handles do.
 */
export function CycleTab({
  plotLeft,
  plotWidth,
  samples,
  editor,
  lit,
  onHighlight,
}: {
  plotLeft: number
  plotWidth: number
  samples: number
  editor: LinkedEditor
  lit: boolean
  onHighlight?: (mark: GraphMark | null) => void
}) {
  const cancel = useDragCancel()
  const [drag, setDrag] = useState<{ x: number; cycle: number } | null>(null)
  const edgeX = sampleX({ plotLeft, plotWidth }, samples, SAMPLES_PER_CYCLE)
  const inSeconds = editor.draft.seconds || editor.wallClock
  const cycleWords = (cycle: number) => (inSeconds ? `${cycle} s` : getCycleLabel(cycle))
  const hovering = useRef(false)
  useEffect(() => () => {
    if (hovering.current) onHighlight?.(null)
  }, [onHighlight])
  if (SAMPLES_PER_CYCLE > samples) return null

  const start = (e: ReactPointerEvent) => {
    const from = editor.draft.cycle
    let last = from
    const started = beginDrag(
      e,
      cancel,
      (dx) => {
        const cycle = cycleFromDrag(from, plotLeft, edgeX, edgeX + dx, inSeconds)
        setDrag({ x: plotLeft + ((edgeX - plotLeft) * cycle) / from, cycle })
        if (cycle !== last) editor.set({ cycle }, false)
        last = cycle
      },
      (moved) => {
        setDrag(null)
        if (moved && last !== from) editor.set({ cycle: last }, true)
      },
    )
    if (started) setDrag({ x: edgeX, cycle: from })
  }
  const cycle = drag?.cycle ?? editor.draft.cycle
  return (
    <div
      data-fx-handle="cycle"
      title={`Cycle ${cycleWords(editor.draft.cycle)} — drag to set it`}
      onPointerDown={start}
      onPointerEnter={() => {
        hovering.current = true
        onHighlight?.('cycle')
      }}
      onPointerLeave={() => {
        hovering.current = false
        onHighlight?.(null)
      }}
      className={cn(
        'pointer-events-auto absolute top-[3px] inline-flex h-[18px] -translate-x-1/2 cursor-ew-resize items-center gap-1 rounded-full bg-violet-600 px-2 text-[10.5px] font-semibold whitespace-nowrap text-white select-none',
        lit && 'ring-2 ring-fuchsia-400',
      )}
      style={{ left: drag?.x ?? edgeX }}
    >
      <span data-fx-handle-label>{cycleWords(cycle)}</span>
      <span aria-hidden>⇔</span>
    </div>
  )
}

/**
 * **The graph's handles** (FX editor plan D12, §4.4; follow-ups D4), over the picked FX's section of
 * its lane: Spread's knob at the end of the line joining the rows' start ticks, and an Intensity
 * FX's max and min lines, each with a knob at its right end. A knob is a handle and a line a mark;
 * hovering a knob names it and lights what it changes. Cycle is the ruler's tab ([CycleTab]); a row
 * dragged sideways (Phase) is the canvas's own gesture. Each writes through the inspector's editor
 * ([editor]) — its draft, its floor, its Revert — and the editor tells the preview the unsent move,
 * so the graph follows.
 */
export function GraphHandles({
  layout,
  section,
  samples,
  fx,
  rows,
  editor,
  highlight = null,
  onHighlight,
}: {
  layout: LaneLayout
  section: LaidSection
  samples: number
  fx: FxPreviewFx
  rows: readonly FxPreviewRow[]
  editor: LinkedEditor
  highlight?: GraphMark | null
  onHighlight?: (mark: GraphMark | null) => void
}) {
  const cancel = useDragCancel()
  const top = section.top
  const height = section.rows.length * section.rowH
  const samplesToPx = layout.plotWidth / Math.max(1, samples)

  // The chase line: each painted row's first start, at the top of its row.
  const points = section.rows.flatMap((laid) => {
    const s = rowStarts(fx, laid)?.[0]
    return s == null ? [] : [{ laid, x: sampleX(layout, samples, s), y: laid.y + 2 - top }]
  })
  const shares = points.map((p) => spreadShareOf(fx, rungRow(p.laid)) ?? 0)
  const spreadHandle = editor.draft.order !== 'UNIFIED' && points.length >= 2 && shares.some((q) => q > 0)
  // The knob rides the row Spread moves furthest — the lowest of those — so it follows the finger.
  const most = Math.max(0, ...shares)
  const knobAt = shares.lastIndexOf(most)
  const knob = points[knobAt]

  const startSpread = (e: ReactPointerEvent) => {
    const share = most
    const start = editor.draft.spread
    let value = start
    beginDrag(
      e,
      cancel,
      (dx) => {
        value = spreadFromDrag(start, dx / samplesToPx, share)
        editor.set({ spread: value }, false)
      },
      (moved) => {
        if (moved && value !== start) editor.set({ spread: value }, true)
      },
    )
  }

  const level = (name: 'min' | 'max') => Number(editor.draft.parameters[name])
  const levelHandles = editor.levels && !section.heat && ['min', 'max'].every((p) => Number.isFinite(level(p as 'min' | 'max')))
  const startLevel = (e: ReactPointerEvent, name: 'min' | 'max', laid: LaidSection['rows'][number]) => {
    const scale = levelScale(section, laid.y)
    const from = scale.yOf(level(name))
    const start = String(level(name))
    let last = start
    beginDrag(
      e,
      cancel,
      (_, dy) => {
        last = String(scale.byteAt(from + dy))
        editor.setParam(name, last, false)
      },
      (moved) => {
        if (moved && last !== start) editor.setParam(name, last, true)
      },
    )
  }
  const levelsLit = highlight === 'levels'
  const knobX = layout.plotLeft + layout.plotWidth - 6
  // High and Low are the FX's, not a row's: one pair of knobs, on the first row the answer has.
  const knobRow = section.rows.find((laid) => laid.mix == null)

  return (
    <div data-fx-handles aria-hidden className="pointer-events-none absolute inset-x-0" style={{ top: 0, height: layout.height }}>
      {levelHandles &&
        section.rows.flatMap((laid) =>
          laid.mix != null
            ? []
            : (['max', 'min'] as const).map((name) => {
                const y = levelScale(section, laid.y).yOf(level(name))
                return (
                  <div key={`${laid.row}-${name}`}>
                    <i
                      data-fx-level-line={name}
                      className={cn('absolute block border-t border-dashed', levelsLit ? 'border-fuchsia-600 dark:border-fuchsia-300' : 'border-violet-500/70')}
                      style={{ left: layout.plotLeft, width: layout.plotWidth, top: Math.round(y) }}
                    />
                    {laid === knobRow && (
                    <Knob
                      name={name}
                      row={rows[laid.row]?.key}
                      x={knobX}
                      y={y}
                      tip={`${name === 'max' ? 'High' : 'Low'} ${Math.round(level(name) / 2.55)}%`}
                      cursor="ns-resize"
                      mark="levels"
                      onHighlight={onHighlight}
                      onPointerDown={(e) => startLevel(e, name, laid)}
                    />
                    )}
                  </div>
                )
              }),
        )}
      {spreadHandle && knob != null && (
        <>
          <svg className="absolute overflow-visible" style={{ left: 0, top, width: layout.width, height }}>
            <polyline
              data-fx-spread-line
              points={points.map((p) => `${p.x},${p.y}`).join(' ')}
              fill="none"
              stroke="currentColor"
              strokeWidth={1.25}
              strokeDasharray="4 3"
              className="text-violet-500/70"
            />
          </svg>
          <Knob
            name="spread"
            x={knob.x}
            y={top + knob.y}
            tip={`Spread ${Math.round(editor.draft.spread * 100)}%`}
            cursor="ew-resize"
            mark="starts"
            onHighlight={onHighlight}
            onPointerDown={startSpread}
          />
        </>
      )}
    </div>
  )
}
