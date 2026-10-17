import { useCallback, useEffect, useRef, useSyncExternalStore } from 'react'
import {
  PAN_THRESHOLD_PX,
  ZOOM_PER_WHEEL_NOTCH,
  offsetToSection,
  type SectionControls,
  type SectionViewStore,
} from './edit/sectionView'
import { planPointOf, type PlanPoint } from './planAim'

interface PlanAimLayerProps {
  viewStore: SectionViewStore
  controls: () => SectionControls | null
  /** A click on the plan, in stage metres. */
  onPoint: (point: PlanPoint) => void
  /** Where the last aim went, drawn as a ring. */
  marker: PlanPoint | null
}

/**
 * Aiming on the Plan (venue-visit plan S13): DOM over the canvas, as the section edit layer is, that
 * owns the pointer while aiming is armed — so neither the fixture click, the scenery popover nor the
 * camera's own controls see a press. A click is a point in the Plan's metres (`edit/sectionView.ts`,
 * resolved from the canvas's live centre); a drag pans and the wheel zooms, as on the editor's
 * sections. It mounts only while armed, and never in a render.
 */
export function PlanAimLayer({ viewStore, controls, onPoint, marker }: PlanAimLayerProps) {
  const view = useSyncExternalStore(viewStore.subscribe, viewStore.get, viewStore.get)
  const ref = useRef<HTMLDivElement | null>(null)
  const press = useRef<{ pointerId: number; startX: number; startY: number; lastX: number; lastY: number; panned: boolean } | null>(null)
  const viewRef = useRef(view)
  viewRef.current = view

  const toPlan = useCallback((clientX: number, clientY: number): PlanPoint | null => {
    const el = ref.current
    const v = viewRef.current
    if (!el || !v) return null
    const rect = el.getBoundingClientRect()
    return planPointOf(offsetToSection(v, clientX - (rect.left + rect.width / 2), clientY - (rect.top + rect.height / 2)))
  }, [])

  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    // A second finger makes the gesture a pinch or a two-finger tap: neither is a point to aim at.
    if (press.current != null) {
      if (press.current.pointerId !== e.pointerId) press.current.panned = true
      return
    }
    if (e.button !== 0 && e.button !== 1) return
    press.current = { pointerId: e.pointerId, startX: e.clientX, startY: e.clientY, lastX: e.clientX, lastY: e.clientY, panned: e.button !== 0 }
    e.currentTarget.setPointerCapture?.(e.pointerId)
  }

  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const p = press.current
    if (p == null || p.pointerId !== e.pointerId) return
    if (!p.panned) {
      if (Math.hypot(e.clientX - p.startX, e.clientY - p.startY) < PAN_THRESHOLD_PX) return
      p.panned = true
    }
    controls()?.panBy(e.clientX - p.lastX, e.clientY - p.lastY)
    p.lastX = e.clientX
    p.lastY = e.clientY
  }

  const onPointerUp = (e: React.PointerEvent<HTMLDivElement>) => {
    const p = press.current
    if (p == null || p.pointerId !== e.pointerId) return
    press.current = null
    if (e.currentTarget.hasPointerCapture?.(e.pointerId)) e.currentTarget.releasePointerCapture(e.pointerId)
    if (p.panned) return
    const at = toPlan(e.clientX, e.clientY)
    if (at != null) onPoint(at)
  }

  const onPointerCancel = (e: React.PointerEvent<HTMLDivElement>) => {
    if (press.current?.pointerId === e.pointerId) press.current = null
  }

  // The wheel zooms about the pointer — a native listener, since React's is passive and could not
  // stop a trackpad pinch zooming the page.
  useEffect(() => {
    const el = ref.current
    if (!el) return
    const onWheel = (e: WheelEvent) => {
      e.preventDefault()
      const rect = el.getBoundingClientRect()
      controls()?.zoomAt(e.deltaY < 0 ? ZOOM_PER_WHEEL_NOTCH : 1 / ZOOM_PER_WHEEL_NOTCH, e.clientX - rect.left, e.clientY - rect.top)
    }
    el.addEventListener('wheel', onWheel, { passive: false })
    return () => el.removeEventListener('wheel', onWheel)
  }, [controls])

  // The marker sits at its offset from the canvas's centre, which the section keeps through a resize.
  const markerOffset =
    marker != null && view != null ? { dx: (marker.x - view.h) * view.zoom, dy: (-marker.y - view.v) * view.zoom } : null

  return (
    <div
      ref={ref}
      data-plan-aim
      className="absolute inset-0 cursor-crosshair touch-none select-none"
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerCancel}
    >
      {markerOffset != null && (
        <span
          data-plan-aim-marker
          aria-hidden
          className="pointer-events-none absolute size-5 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-primary shadow-[0_0_0_2px_var(--background)]"
          style={{ left: `calc(50% + ${markerOffset.dx}px)`, top: `calc(50% + ${markerOffset.dy}px)` }}
        />
      )}
      <div
        role="status"
        // Top left: the camera's caption holds the top right, and the selection's the foot.
        className="pointer-events-none absolute left-3 top-3 rounded-md bg-background/85 px-3 py-1.5 text-xs shadow-md backdrop-blur"
      >
        Click the plan to aim · drag to pan · Esc to stop
      </div>
    </div>
  )
}
