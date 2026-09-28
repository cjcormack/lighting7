import { useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Highlighter, Scissors, StickyNote } from 'lucide-react'
import { clamp } from '@/lib/utils'

/** Gap kept between the bar and the viewport edge when the anchor is near it. */
const EDGE_MARGIN = 8

interface FloatingSelectionToolbarProps {
  /** Client-space top-centre of the current selection; the bar floats just above it. */
  anchor: { x: number; y: number }
  onAnchor: () => void
  onCut: () => void
  onNote: () => void
}

/**
 * A small action bar that floats above a live text selection in edit mode. It is
 * the annotation-creation surface: select the script text, then choose what it
 * becomes. "Anchor cue" opens a picker to choose which cue the selection belongs
 * to (anchoring a new cue or re-anchoring an existing one).
 *
 * Rendered through a portal with fixed positioning so page transforms / the scroll
 * container can't clip it. `onMouseDown` is prevented on the bar so clicking a
 * button doesn't collapse the selection before its handler runs.
 *
 * Width is `max-content` and the labels never wrap, so the bar is always as wide as
 * its three actions need — a fixed-position box sized by the space to the right of
 * `left` would otherwise squeeze "Anchor cue" onto two lines near the right edge.
 * The centring is then clamped by the bar's *measured* half-width so it stays fully
 * on screen instead of hanging off the side.
 */
export function FloatingSelectionToolbar({ anchor, onAnchor, onCut, onNote }: FloatingSelectionToolbarProps) {
  const barRef = useRef<HTMLDivElement>(null)
  const [box, setBox] = useState<{ half: number; viewport: number } | null>(null)

  // Measured before paint, so the bar is never seen at the unclamped position. Kept
  // live because a rotate/resize with a selection open changes both the bar's own
  // width (it may wrap) and the edge it has to stay inside of. The root element is
  // observed rather than listening for `resize`, so a viewport change that doesn't
  // raise the event (device emulation, some mobile chrome) still re-clamps.
  useLayoutEffect(() => {
    const el = barRef.current
    if (!el) return
    // The root box is content-sized, so notifications also arrive for changes that
    // move nothing here — keep the previous state when the numbers match.
    const measure = () =>
      setBox((prev) => {
        const next = { half: el.offsetWidth / 2, viewport: window.innerWidth }
        return prev && prev.half === next.half && prev.viewport === next.viewport ? prev : next
      })
    measure()
    const observer = new ResizeObserver(measure)
    observer.observe(el)
    observer.observe(document.documentElement)
    return () => observer.disconnect()
  }, [])

  const viewport = box?.viewport ?? window.innerWidth
  const half = box?.half ?? 0
  const maxLeft = viewport - half - EDGE_MARGIN
  const minLeft = half + EDGE_MARGIN
  // A bar wider than the viewport can't satisfy both margins — centre it and let the
  // (wrapped) content sit inside max-w instead.
  const left = minLeft > maxLeft ? viewport / 2 : clamp(anchor.x, minLeft, maxLeft)
  const top = Math.max(anchor.y - 8, 44)
  const btn =
    'inline-flex items-center gap-1.5 rounded-md px-2.5 py-1 text-xs font-semibold whitespace-nowrap hover:bg-muted/70'
  return createPortal(
    <div
      ref={barRef}
      role="toolbar"
      // Prevent the selection from collapsing before the button's click fires.
      // pointerdown covers touch (mousedown does not, so taps would otherwise lose
      // the selection and the action would silently no-op on tablets/phones).
      onPointerDown={(e) => e.preventDefault()}
      onMouseDown={(e) => e.preventDefault()}
      style={{ position: 'fixed', top, left, transform: 'translate(-50%, -100%)', zIndex: 60 }}
      className="flex w-max max-w-[calc(100vw-1rem)] flex-wrap items-center justify-center gap-0.5 rounded-lg border bg-popover p-1 text-popover-foreground shadow-lg"
    >
      <button type="button" onClick={onAnchor} className={`${btn} text-amber-600`}>
        <Highlighter className="size-3.5" /> Anchor cue
      </button>
      <span className="mx-0.5 h-5 w-px bg-border" />
      <button type="button" onClick={onCut} className={`${btn} text-red-500`}>
        <Scissors className="size-3.5" /> Mark cut
      </button>
      <button type="button" onClick={onNote} className={`${btn} text-sky-500`}>
        <StickyNote className="size-3.5" /> Add note
      </button>
    </div>,
    document.body,
  )
}
