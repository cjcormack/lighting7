import { useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import { getCycleLabel } from '@/components/fx/fxConstants'
import type { LinkedEditor } from '@/components/fx/fxGraphLink'
import type { FxPreviewFx, FxPreviewRow } from '@/store/fxPreview'
import { SAMPLES_PER_CYCLE, levelScale, sampleX, type LaidSection, type LaneLayout } from './fxGraphModel'
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

/**
 * **The graph's handles** (FX editor plan D12, §4.4), over the picked FX's section of its lane: the
 * first cycle's right edge (Cycle, snapping to the cycle steps), the line joining the rows' start
 * ticks (Spread), and an Intensity FX's max and min lines. A row dragged sideways (Phase) is the
 * canvas's own gesture. Each writes through the inspector's editor ([editor]) — its draft, its
 * floor, its Revert — and the editor tells the preview the unsent move, so the graph follows.
 */
export function GraphHandles({
  layout,
  section,
  samples,
  fx,
  rows,
  editor,
}: {
  layout: LaneLayout
  section: LaidSection
  samples: number
  fx: FxPreviewFx
  rows: readonly FxPreviewRow[]
  editor: LinkedEditor
}) {
  const box = useRef<HTMLDivElement>(null)
  const cancel = useDragCancel()
  const [cycleDrag, setCycleDrag] = useState<{
    x: number
    cycle: number
  } | null>(null)
  const top = section.top
  const height = section.rows.length * section.rowH
  const samplesToPx = layout.plotWidth / Math.max(1, samples)

  const edgeX = sampleX(layout, samples, SAMPLES_PER_CYCLE)
  const cycleHandle = SAMPLES_PER_CYCLE <= samples
  const inSeconds = editor.draft.seconds || editor.wallClock
  const cycleWords = (cycle: number) => (inSeconds ? `${cycle} s` : getCycleLabel(cycle))

  const startCycle = (e: ReactPointerEvent) => {
    const start = editor.draft.cycle
    let last = start
    const started = beginDrag(
      e,
      cancel,
      (dx) => {
        const cycle = cycleFromDrag(start, layout.plotLeft, edgeX, edgeX + dx, inSeconds)
        setCycleDrag({
          x: layout.plotLeft + ((edgeX - layout.plotLeft) * cycle) / start,
          cycle,
        })
        if (cycle !== last) editor.set({ cycle }, false)
        last = cycle
      },
      (moved) => {
        setCycleDrag(null)
        if (moved && last !== start) editor.set({ cycle: last }, true)
      },
    )
    if (started) setCycleDrag({ x: edgeX, cycle: start })
  }

  // The chase line: each painted row's first start, at the top of its row.
  const points = section.rows.flatMap((laid) => {
    const s = fx.starts[laid.row]?.[0]
    return s == null ? [] : [{ laid, x: sampleX(layout, samples, s), y: laid.y + 2 - top }]
  })
  const shares = points.map((p) => spreadShareOf(fx, p.laid.row) ?? 0)
  const spreadHandle = editor.draft.order !== 'UNIFIED' && points.length >= 2 && shares.some((q) => q > 0)

  const startSpread = (e: ReactPointerEvent) => {
    const rect = box.current?.getBoundingClientRect()
    const y = rect == null ? 0 : e.clientY - rect.top - top
    let nearest = 0
    points.forEach((p, i) => {
      if (Math.abs(p.y - y) < Math.abs(points[nearest].y - y)) nearest = i
    })
    const share = shares[nearest] > 0 ? shares[nearest] : Math.max(...shares)
    const start = editor.draft.spread
    let last = start
    beginDrag(
      e,
      cancel,
      (dx) => {
        last = spreadFromDrag(start, dx / samplesToPx, share)
        editor.set({ spread: last }, false)
      },
      (moved) => {
        if (moved && last !== start) editor.set({ spread: last }, true)
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

  return (
    <div ref={box} data-fx-handles aria-hidden className="pointer-events-none absolute inset-x-0" style={{ top: 0, height: layout.height }}>
      {levelHandles &&
        section.rows.flatMap((laid) =>
          (['max', 'min'] as const).map((name) => {
            const y = levelScale(section, laid.y).yOf(level(name))
            return (
              <div
                key={`${laid.row}-${name}`}
                data-fx-handle={name}
                data-row={rows[laid.row]?.key}
                title={`Drag to set ${name === 'max' ? 'High' : 'Low'}`}
                onPointerDown={(e) => startLevel(e, name, laid)}
                className="pointer-events-auto absolute cursor-ns-resize"
                style={{
                  left: layout.plotLeft,
                  width: layout.plotWidth,
                  top: y - 2.5,
                  height: 5,
                }}
              >
                <i className="absolute inset-x-0 top-[2px] block border-t border-dashed border-violet-500/70" />
              </div>
            )
          }),
        )}
      {spreadHandle && (
        <svg className="absolute overflow-visible" style={{ left: 0, top, width: layout.width, height }}>
          <polyline
            points={points.map((p) => `${p.x},${p.y}`).join(' ')}
            fill="none"
            stroke="currentColor"
            strokeWidth={1.25}
            className="text-violet-500/70"
          />
          <polyline
            data-fx-handle="spread"
            points={points.map((p) => `${p.x},${p.y}`).join(' ')}
            fill="none"
            stroke="transparent"
            strokeWidth={8}
            onPointerDown={startSpread}
            className="cursor-ew-resize"
            style={{ pointerEvents: 'stroke' }}
          >
            <title>Drag to set Spread</title>
          </polyline>
        </svg>
      )}
      {cycleHandle && (
        <div
          data-fx-handle="cycle"
          title="Drag to set Cycle"
          onPointerDown={startCycle}
          className="pointer-events-auto absolute flex cursor-ew-resize justify-center"
          style={{ left: (cycleDrag?.x ?? edgeX) - 4, top, width: 8, height }}
        >
          <i className="block h-full w-0.5 bg-violet-500" />
          <i className="absolute top-1/2 block h-4 w-1.5 -translate-y-1/2 rounded-sm bg-violet-300" />
          {cycleDrag != null && (
            <span
              data-fx-handle-label
              className="absolute -top-0.5 left-2 rounded bg-violet-600 px-1 text-[10px] whitespace-nowrap text-white"
            >
              {cycleWords(cycleDrag.cycle)}
            </span>
          )}
        </div>
      )}
    </div>
  )
}
