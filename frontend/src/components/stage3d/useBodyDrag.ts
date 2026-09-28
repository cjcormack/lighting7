import { useCallback, useEffect, useRef } from 'react'
import { type ThreeEvent } from '@react-three/fiber'
import { Object3D } from 'three'
import { useHandleDrag, type StartDragOptions } from './useHandleDrag'

/** Pointer travel that promotes a press from a click into a drag. Shared with the
 *  2D editor's useBodyDrag2D so both views discriminate identically. */
export const DRAG_PX_THRESHOLD = 4

function isDescendantOf(node: Object3D | null, ancestor: Object3D): boolean {
  let cur: Object3D | null = node
  while (cur) {
    if (cur === ancestor) return true
    cur = cur.parent
  }
  return false
}

// Pending body-drag click-discriminators register a cancel callback here so
// the fixture-translate TransformControls can abort them when it claims the
// click for one of its gizmo handles. The gizmo meshes have no R3F event
// handlers, so R3F's raycast passes through them and dispatches pointerdown
// to whatever body sits behind — without this signal, the body would still
// promote to a drag (or fire its click) once the user moves the gizmo.
const pendingCancels = new Set<() => void>()

export function notifyTransformDragStart(): void {
  // Snapshot before iterating — each cancel removes itself from the Set.
  for (const cancel of [...pendingCancels]) cancel()
}

export interface UseBodyDragOpts {
  /** Fired on pointerup if the pointer never crossed the click/drag threshold. */
  onClick?: () => void
  /** Factory for the drag options forwarded to useHandleDrag on promotion.
   *  Returning undefined disables drag (caller can omit instead). */
  buildDrag?: () => StartDragOptions
  /** Eagerly fired on pointerdown when drag is possible. Use this to disable
   *  OrbitControls so a sub-threshold pointermove doesn't trigger camera
   *  rotation that has to snap back on promote. */
  onDragStart?: () => void
  /** Fires exactly once on completion — on the dragged onSettle OR on the
   *  pointerup-as-click branch. Use to re-enable OrbitControls. */
  onDragEnd?: () => void
}

/**
 * Click-vs-drag discriminator for "the body is the affordance" interactions.
 * Watches a 4 px movement threshold from pointerdown; below threshold on
 * pointerup is a click, at or above is a drag promoted to useHandleDrag.
 * Window listeners survive cursor leaving the canvas and are torn down on
 * cleanup, promotion, or component unmount mid-discrimination.
 */
export function useBodyDrag() {
  const startDrag = useHandleDrag()
  const activeCleanup = useRef<(() => void) | null>(null)

  useEffect(
    () => () => {
      activeCleanup.current?.()
      activeCleanup.current = null
    },
    [],
  )

  return useCallback(
    (e: ThreeEvent<PointerEvent>, opts: UseBodyDragOpts) => {
      if (e.button !== 0) return
      if (!opts.onClick && !opts.buildDrag) return

      // R3F dispatches pointerdown to the closest handler-bearing object in
      // the intersection chain — but a closer object without R3F handlers
      // (a TransformControls gizmo arrow, a different fixture body) still
      // visually occludes us. If the topmost hit isn't in our subtree the
      // user clicked something in front; don't steal that click as a drag.
      const firstHit = e.intersections[0]
      if (firstHit && !isDescendantOf(firstHit.object, e.eventObject)) return

      e.stopPropagation()

      const pointerId = e.pointerId
      const downX = e.nativeEvent.clientX
      const downY = e.nativeEvent.clientY
      const canDrag = !!opts.buildDrag
      let promoted = false

      if (canDrag) opts.onDragStart?.()

      // cleanup doubles as the transform-drag-start cancel callback —
      // notifyTransformDragStart() invokes it when TC claims the click for
      // a gizmo handle. We don't call onDragEnd in that path because TC
      // keeps OrbitControls disabled for the duration of its own drag and
      // re-enables it itself on dragging-changed:false.
      const cleanup = () => {
        window.removeEventListener('pointermove', onWindowMove)
        window.removeEventListener('pointerup', onWindowUp)
        window.removeEventListener('pointercancel', onWindowUp)
        pendingCancels.delete(cleanup)
        activeCleanup.current = null
      }

      const onWindowMove = (ev: PointerEvent) => {
        if (promoted || ev.pointerId !== pointerId) return
        const dx = ev.clientX - downX
        const dy = ev.clientY - downY
        if (Math.hypot(dx, dy) < DRAG_PX_THRESHOLD) return
        promoted = true
        cleanup()
        const dragOpts = opts.buildDrag?.()
        if (!dragOpts) return
        startDrag(
          {
            ...dragOpts,
            onSettle: (last) => {
              opts.onDragEnd?.()
              dragOpts.onSettle(last)
            },
          },
          e,
        )
      }

      const onWindowUp = (ev: PointerEvent) => {
        if (ev.pointerId !== pointerId) return
        cleanup()
        if (promoted) return
        if (canDrag) opts.onDragEnd?.()
        opts.onClick?.()
      }

      activeCleanup.current = cleanup
      pendingCancels.add(cleanup)
      window.addEventListener('pointermove', onWindowMove)
      window.addEventListener('pointerup', onWindowUp)
      window.addEventListener('pointercancel', onWindowUp)
    },
    [startDrag],
  )
}
