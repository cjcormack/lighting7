import type { PointerEvent as ReactPointerEvent } from 'react'
import { cn } from '@/lib/utils'

/**
 * **The 5px grab strip on a docked panel's left edge**, straddling its border — the programmer
 * rail's, shared with the busk view's side sheet so the two cannot end up with different hit
 * areas, cursors or labels for one gesture.
 *
 * `touch-none` is what lets a finger drag it at all: without it the browser claims the gesture as
 * a scroll and the pointer events stop arriving. The drag itself is `useSidePanelResize`'s.
 *
 * It is drawn only where the panel's width is actually the stored one — so not in the rail's
 * narrow push arm, which is a fixed 300px overlay, and not on the phone, which has no panel. The
 * caller decides that with `className`, because on the rail it is a container query and in the
 * busk sheet it is the mode.
 *
 * The **label is the caller's**, as `AddDoorsMenu`'s is: a control announced as "resize the
 * panel" tells a screen reader nothing about which of two panels it has landed on, and each
 * surface already has a name for itself.
 */
export function SidePanelResizeHandle({
  label,
  onResizeStart,
  className,
}: {
  /** What this panel is, for the `aria-label` — "the rail", "the side sheet". */
  label: string
  onResizeStart: (e: ReactPointerEvent) => void
  className?: string
}) {
  return (
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label={`Resize ${label}`}
      title={`Drag to resize ${label}`}
      onPointerDown={onResizeStart}
      className={cn(
        'absolute inset-y-0 -left-[3px] z-10 w-[5px] cursor-col-resize touch-none hover:bg-primary/40',
        className,
      )}
    />
  )
}
