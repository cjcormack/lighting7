import { useCallback } from 'react'
import { type ThreeEvent, useThree } from '@react-three/fiber'
import { Plane, Raycaster, Vector2, Vector3 } from 'three'

export interface StartDragOptions {
  /** Plane to raycast pointer rays against during this drag. */
  plane: Plane
  /**
   * The handle's world position (in the same R3F space the plane lives in) at
   * the moment of click. The hook projects the click's screen position onto
   * the plane to find the "grab anchor" and stores the offset between that and
   * `handleWorld`. The offset is added to every subsequent pointermove
   * projection so the logical drag point stays put when the user clicks
   * off-centre on the handle — no jump on drag start.
   */
  handleWorld: Vector3
  /**
   * Constrain the drag output to a single axis. With `'vertical'`, the hook
   * raycasts a camera-facing vertical plane through the handle but then
   * clamps the hit's R3F X/Z back to `handleWorld.x/.z` so the drag point
   * moves only along world Y. Useful for "drag this face up/down" handles
   * where the user shouldn't be able to slide the handle sideways.
   */
  lockAxis?: 'vertical'
  /** Fires for each pointermove, with the new world point on the drag plane. */
  onDrag: (worldPoint: Vector3) => void
  /** Fires once on pointerup. Receives the last drag point (or null if none). */
  onSettle: (lastPoint: Vector3 | null) => void
}

/**
 * Build a vertical plane through `handleR3F` whose normal is the horizontal
 * component of the camera-to-handle direction. Used for vertical-drag handles
 * so the user has a stable "screen-aligned" plane to drag against (looking
 * straight down is the only degenerate case — we fall back to a +Z normal).
 */
export function verticalPlaneThroughR3F(handleR3F: Vector3, cameraR3F: Vector3): Plane {
  const n = new Vector3().subVectors(cameraR3F, handleR3F)
  n.y = 0
  if (n.lengthSq() < 1e-6) n.set(0, 0, 1)
  n.normalize()
  return new Plane(n, -n.dot(handleR3F))
}

/**
 * Returns a `startDrag` callback that takes per-drag options + the R3F
 * pointerdown event. Wires pointermove/up listeners on the canvas DOM with
 * pointer capture so the drag survives the cursor leaving the canvas, and
 * raycasts against the supplied plane to produce world-space drag points.
 *
 * The `worldPoint` passed to `onDrag` is a shared scratch buffer — callers
 * must read it synchronously (don't retain).
 */
export function useHandleDrag() {
  const { camera, gl } = useThree()
  return useCallback(
    (opts: StartDragOptions, e: ThreeEvent<PointerEvent>) => {
      e.stopPropagation()
      const el = gl.domElement
      const raycaster = new Raycaster()
      const ndc = new Vector2()
      const hit = new Vector3()
      let lastHit: Vector3 | null = null
      el.setPointerCapture(e.pointerId)

      const project = (clientX: number, clientY: number): Vector3 | null => {
        const rect = el.getBoundingClientRect()
        ndc.set(
          ((clientX - rect.left) / rect.width) * 2 - 1,
          -((clientY - rect.top) / rect.height) * 2 + 1,
        )
        raycaster.setFromCamera(ndc, camera)
        return raycaster.ray.intersectPlane(opts.plane, hit) ? hit : null
      }

      // Project the click's screen position onto the drag plane; the offset
      // from there to the handle's world position is what we apply to every
      // pointermove so the handle stays under the cursor.
      const offset = new Vector3()
      const anchor = project(e.nativeEvent.clientX, e.nativeEvent.clientY)
      if (anchor) offset.copy(opts.handleWorld).sub(anchor)

      const onMove = (ev: PointerEvent) => {
        const p = project(ev.clientX, ev.clientY)
        if (p) {
          p.add(offset)
          if (opts.lockAxis === 'vertical') {
            p.x = opts.handleWorld.x
            p.z = opts.handleWorld.z
          }
          lastHit = p
          opts.onDrag(p)
        }
      }
      const onUp = (ev: PointerEvent) => {
        el.releasePointerCapture(ev.pointerId)
        el.removeEventListener('pointermove', onMove)
        el.removeEventListener('pointerup', onUp)
        el.removeEventListener('pointercancel', onUp)
        opts.onSettle(lastHit)
      }
      el.addEventListener('pointermove', onMove)
      el.addEventListener('pointerup', onUp)
      el.addEventListener('pointercancel', onUp)
    },
    [camera, gl],
  )
}
