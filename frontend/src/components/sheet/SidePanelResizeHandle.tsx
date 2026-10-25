import type { PointerEvent as ReactPointerEvent } from 'react'
import { cn } from '@/lib/utils'

/**
 * **The 5px grab strip on a docked panel's left edge**, straddling its border.
 *
 * `touch-none` is what lets a finger drag it at all: without it the browser claims the gesture as
 * a scroll and the pointer events stop arriving. The drag itself is `useSidePanelResize`'s.
 *
 * It is drawn only where the panel's width is actually the stored one, which the caller decides
 * with `className`.
 *
 * The **label is the caller's**, as `AddDoorsMenu`'s is: a control announced as "resize the
 * panel" tells a screen reader nothing about which panel it has landed on, and each surface
 * already has a name for itself.
 */
export function SidePanelResizeHandle({
  label,
  onResizeStart,
  className,
}: {
  /** What this panel is, for the `aria-label` — "the side sheet". */
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
