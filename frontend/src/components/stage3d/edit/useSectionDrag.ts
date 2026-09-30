import { useCallback, useEffect, useRef } from 'react'
import type { ScreenPoint } from '../../../lib/stageProjection'
import { DRAG_PX_THRESHOLD } from '../useBodyDrag'

export interface SectionDragOptions {
  /**
   * The object's projected position at pointerdown. The hook stores the offset from the pointer's
   * metre position to this and adds it to every move, so grabbing a shape off-centre doesn't make
   * it jump. Mirrors `handleWorld` in useHandleDrag.
   */
  anchor: ScreenPoint
  /** Restrict motion to one screen axis. The analogue of useHandleDrag's lockAxis. */
  lockAxis?: 'h' | 'v'
  /** Applied after the lock, so snapping lands on the grid. */
  snap?: (p: ScreenPoint) => ScreenPoint
  onDrag: (p: ScreenPoint) => void
  /** Fires exactly once, with the last point (null if the pointer never moved). */
  onSettle: (last: ScreenPoint | null) => void
}

/** Turns a pointer's client position into a point on the section. */
export type ToSection = (clientX: number, clientY: number) => ScreenPoint

/** The originating press. A plain object rather than a React event, so a press can promote to a
 *  drag by replaying the coordinates it recorded at pointerdown. */
export interface DragOrigin {
  clientX: number
  clientY: number
  pointerId: number
}

/**
 * A drag on a section: a grab offset and a single settle callback, resolving each pointer
 * position through the section camera ([toSection]) rather than a three.js raycast — the section's
 * screen axes are the projection's, so the edit layer works in metres on the section throughout.
 *
 * Listeners go on `window`, so the gesture survives the pointer leaving the canvas, and an element
 * listener cannot miss the `pointerup` when it does. That also removes any need for
 * `setPointerCapture`, whose paired `releasePointerCapture` throws on a `pointercancel`.
 *
 * **A drag settles exactly once, however it ends.** Its moves write the RTK cache as they go, and
 * only the settle writes the desk (or rolls the cache back), so a drag that ended without one would
 * leave an object drawn where the desk does not have it. So the layer unmounting mid-drag — a
 * section left, Edit turned off, the context lost — settles it where it had got to, and so does a
 * second pointer starting a drag of its own (a second finger on a tablet) before the first lifts.
 */
export function useSectionDrag(toSection: ToSection) {
  // Finish an in-flight drag if the layer unmounts mid-gesture, or another drag starts.
  const activeFinish = useRef<(() => void) | null>(null)
  useEffect(
    () => () => {
      activeFinish.current?.()
      activeFinish.current = null
    },
    [],
  )

  return useCallback(
    (opts: SectionDragOptions, origin: DragOrigin) => {
      activeFinish.current?.()
      const start = toSection(origin.clientX, origin.clientY)
      const offset = { h: opts.anchor.h - start.h, v: opts.anchor.v - start.v }
      let last: ScreenPoint | null = null

      const resolve = (clientX: number, clientY: number): ScreenPoint => {
        const raw = toSection(clientX, clientY)
        let p: ScreenPoint = { h: raw.h + offset.h, v: raw.v + offset.v }
        if (opts.lockAxis === 'h') p = { h: p.h, v: opts.anchor.v }
        else if (opts.lockAxis === 'v') p = { h: opts.anchor.h, v: p.v }
        if (opts.snap) p = opts.snap(p)
        return p
      }

      const finish = () => {
        window.removeEventListener('pointermove', onMove)
        window.removeEventListener('pointerup', onUp)
        window.removeEventListener('pointercancel', onUp)
        if (activeFinish.current === finish) activeFinish.current = null
        opts.onSettle(last)
      }

      const onMove = (ev: PointerEvent) => {
        if (ev.pointerId !== origin.pointerId) return
        const p = resolve(ev.clientX, ev.clientY)
        last = p
        opts.onDrag(p)
      }
      const onUp = (ev: PointerEvent) => {
        if (ev.pointerId !== origin.pointerId) return
        finish()
      }

      activeFinish.current = finish
      window.addEventListener('pointermove', onMove)
      window.addEventListener('pointerup', onUp)
      window.addEventListener('pointercancel', onUp)
    },
    [toSection],
  )
}

export interface SectionPressOptions {
  onClick?: () => void
  /**
   * Omit to make the press click-only. Called once, on promotion to a drag, so the caller can
   * capture drag-start state (anchors, pinned endpoints) at the right moment. May return undefined
   * to decline the drag — a bar that turns out to be edge-on in this section.
   */
  buildDrag?: () => SectionDragOptions | undefined
}

/**
 * "The body is the affordance" on a section: a press that stays within `DRAG_PX_THRESHOLD` is a
 * click (select), and past it becomes a drag (move) — the threshold the 3D bodies use, so every
 * view tells a click from a drag the same way.
 */
export function useSectionPress(toSection: ToSection) {
  const startDrag = useSectionDrag(toSection)
  const activeCleanup = useRef<(() => void) | null>(null)
  useEffect(
    () => () => {
      activeCleanup.current?.()
      activeCleanup.current = null
    },
    [],
  )

  return useCallback(
    (origin: DragOrigin, opts: SectionPressOptions) => {
      activeCleanup.current?.()
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
        // Seeded from the ORIGINAL press, so the grab offset is measured from where the operator
        // took hold of the shape, not from wherever the pointer had got to by the threshold.
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
    [startDrag],
  )
}
