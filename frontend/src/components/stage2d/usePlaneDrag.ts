import { useCallback, useEffect, useRef } from 'react'
import type { ScreenPoint } from '../../lib/stageProjection'
import { DRAG_PX_THRESHOLD } from '../stage3d/useBodyDrag'

export interface PlaneDragOptions {
  /**
   * The object's projected position at pointerdown. The hook stores the offset
   * from the pointer's metre position to this and adds it to every move, so
   * grabbing a shape off-centre doesn't make it jump. Mirrors `handleWorld` in
   * useHandleDrag.
   */
  anchor: ScreenPoint
  /** Restrict motion to one screen axis. The analogue of useHandleDrag's lockAxis. */
  lockAxis?: 'h' | 'v'
  /**
   * Project the free point onto an arbitrary line or curve — used to constrain a
   * truss-mounted fixture to its bar. Applied before snapping.
   */
  constrain?: (p: ScreenPoint) => ScreenPoint
  /** Applied after `constrain`, so snapping lands on the grid, not off the bar. */
  snap?: (p: ScreenPoint) => ScreenPoint
  onDrag: (p: ScreenPoint) => void
  /** Fires exactly once, with the last point (null if the pointer never moved). */
  onSettle: (last: ScreenPoint | null) => void
}

type ToMetres = (clientX: number, clientY: number) => ScreenPoint

/** The originating press. A plain object rather than a React event, so a
 *  body-drag can promote to a plane-drag by replaying the coordinates it
 *  recorded at pointerdown. */
export interface DragOrigin {
  clientX: number
  clientY: number
  pointerId: number
}

/**
 * The DOM analogue of `useHandleDrag`: a grab offset and a single settle
 * callback, but resolving positions through an SVG's CTM instead of a three.js
 * raycast.
 *
 * Listeners go on `window`, not on the SVG, matching the 3D `useBodyDrag` — the
 * gesture has to survive the cursor leaving the canvas, and an element listener
 * silently misses the `pointerup` when it does. That also removes any need for
 * `setPointerCapture`, whose paired `releasePointerCapture` throws NotFoundError
 * on a `pointercancel` (the pointer is no longer active by then) and would take
 * the settle callback down with it.
 *
 * Two deliberate differences from the 3D version, so nobody "fixes" them:
 *
 *  - **No scratch buffer.** useHandleDrag reuses one Vector3 because it runs
 *    inside useFrame; here one small object per pointermove (~60/s) is free, and
 *    a shared buffer would make `onDrag` consumers unsafe for no gain.
 *  - **No drag-cancel channel.** The 3D version needs
 *    `notifyTransformDragStart`/descendant checks because R3F passes a
 *    pointerdown through gizmo meshes to whatever body is behind. SVG hit-tests
 *    by paint order natively, so handles drawn last simply win.
 */
export function usePlaneDrag(
  svgRef: React.RefObject<SVGSVGElement | null>,
  toMetres: ToMetres,
) {
  // Tear down an in-flight drag if the view unmounts mid-gesture.
  const activeCleanup = useRef<(() => void) | null>(null)
  useEffect(
    () => () => {
      activeCleanup.current?.()
      activeCleanup.current = null
    },
    [],
  )

  return useCallback(
    (opts: PlaneDragOptions, origin: DragOrigin) => {
      const el = svgRef.current
      if (!el) return

      const start = toMetres(origin.clientX, origin.clientY)
      const offset = { h: opts.anchor.h - start.h, v: opts.anchor.v - start.v }
      let last: ScreenPoint | null = null

      const resolve = (clientX: number, clientY: number): ScreenPoint => {
        const raw = toMetres(clientX, clientY)
        let p: ScreenPoint = { h: raw.h + offset.h, v: raw.v + offset.v }
        if (opts.lockAxis === 'h') p = { h: p.h, v: opts.anchor.v }
        else if (opts.lockAxis === 'v') p = { h: opts.anchor.h, v: p.v }
        if (opts.constrain) p = opts.constrain(p)
        if (opts.snap) p = opts.snap(p)
        return p
      }

      const cleanup = () => {
        window.removeEventListener('pointermove', onMove)
        window.removeEventListener('pointerup', onUp)
        window.removeEventListener('pointercancel', onUp)
        activeCleanup.current = null
      }

      const onMove = (ev: PointerEvent) => {
        if (ev.pointerId !== origin.pointerId) return
        const p = resolve(ev.clientX, ev.clientY)
        last = p
        opts.onDrag(p)
      }
      const onUp = (ev: PointerEvent) => {
        if (ev.pointerId !== origin.pointerId) return
        cleanup()
        opts.onSettle(last)
      }

      activeCleanup.current = cleanup
      window.addEventListener('pointermove', onMove)
      window.addEventListener('pointerup', onUp)
      window.addEventListener('pointercancel', onUp)
    },
    [svgRef, toMetres],
  )
}

export interface BodyDragOptions {
  onClick?: () => void
  /**
   * Omit to make the shape click-only. Called once, on promotion to a drag, so
   * the caller can capture drag-start state (anchors, pinned endpoints) at the
   * right moment. May return undefined to decline the drag after inspecting
   * that state — e.g. a bar that turns out to be edge-on in this projection.
   */
  buildDrag?: () => PlaneDragOptions | undefined
}

/**
 * "The body is the affordance" for 2D shapes: a press that stays within
 * `DRAG_PX_THRESHOLD` is a click (select), and past it becomes a drag (move).
 *
 * Threshold imported from useBodyDrag rather than redefined, so the two views
 * discriminate click-from-drag identically. Listeners are on `window` for the
 * same reason as `usePlaneDrag`: on an element listener, a press that drifts off
 * the canvas before promotion never sees its `pointerup`, so the handlers stay
 * bound and a later unrelated `pointerup` fires this gesture's `onClick`.
 */
export function useBodyDrag2D(
  svgRef: React.RefObject<SVGSVGElement | null>,
  toMetres: ToMetres,
) {
  const startDrag = usePlaneDrag(svgRef, toMetres)
  const activeCleanup = useRef<(() => void) | null>(null)
  useEffect(
    () => () => {
      activeCleanup.current?.()
      activeCleanup.current = null
    },
    [],
  )

  return useCallback(
    (e: React.PointerEvent, opts: BodyDragOptions) => {
      // Primary button only — otherwise right-clicking a shape for the browser
      // context menu also selects it on pointerup.
      if (e.button !== 0) return
      e.stopPropagation()
      if (!svgRef.current) return
      const origin = { clientX: e.clientX, clientY: e.clientY, pointerId: e.pointerId }
      let promoted = false

      const cleanup = () => {
        window.removeEventListener('pointermove', onMove)
        window.removeEventListener('pointerup', onUp)
        window.removeEventListener('pointercancel', onUp)
        activeCleanup.current = null
      }

      const onMove = (ev: PointerEvent) => {
        if (ev.pointerId !== origin.pointerId || promoted) return
        const travel = Math.hypot(ev.clientX - origin.clientX, ev.clientY - origin.clientY)
        if (travel < DRAG_PX_THRESHOLD) return
        promoted = true
        cleanup()
        const dragOpts = opts.buildDrag?.()
        if (!dragOpts) return
        // Hand the gesture over, seeded from the ORIGINAL press so the grab offset
        // is measured from where the user actually took hold of the shape — not
        // from wherever the pointer had reached by the time it crossed the
        // threshold, which would make the shape jump by up to the threshold.
        startDrag(dragOpts, origin)
      }
      const onUp = (ev: PointerEvent) => {
        if (ev.pointerId !== origin.pointerId) return
        cleanup()
        if (!promoted) opts.onClick?.()
      }

      activeCleanup.current = cleanup
      window.addEventListener('pointermove', onMove)
      window.addEventListener('pointerup', onUp)
      window.addEventListener('pointercancel', onUp)
    },
    // toMetres is deliberately absent: this hook never calls it, it only reaches
    // it through `startDrag`, which already depends on it.
    [svgRef, startDrag],
  )
}
