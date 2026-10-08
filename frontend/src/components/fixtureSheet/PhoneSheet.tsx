import { useRef, useState, type CSSProperties, type KeyboardEvent, type PointerEvent, type ReactNode } from 'react'
import { Sheet, SheetContent, SheetDescription } from '@/components/ui/sheet'
import { useKeyboardInset, type EditorForm } from '@/components/editor/EditorSurface'
import { cn } from '@/lib/utils'

/**
 * The bottom sheet's three heights (fixture-fx-sheets plan §4, D14): **peek** is the header and the
 * tray — the properties fold away, so the stage stays in view and another fixture can be tapped —
 * then **half**, where it opens, then **full**.
 */
export type PhoneSheetHeight = 'peek' | 'half' | 'full'

export const PHONE_SHEET_HEIGHTS: readonly PhoneSheetHeight[] = ['peek', 'half', 'full']

/** Half of the small viewport — `svh`, the unit that stays put while Safari's bar comes and goes. */
const HALF_SVH = 50
/** Full is `EditorSurface`'s bottom sheet's own cap, so the two read as one shape. */
const FULL_SVH = 88
/** The side form's width: the Stage panel's 380px, never more than most of a landscape phone. */
const SIDE_WIDTH = 'min(380px, 85vw)'
/** The device's bottom strip — the home indicator — padded under the tray. */
export const SAFE_BOTTOM_CLASS = 'pb-[env(safe-area-inset-bottom)]'
/** A move shorter than this is a tap on the grabber, not a drag of it. */
const DRAG_SLOP_PX = 6
/** Dragged this far below the peek height, the sheet lets go — the phone's own dismiss. */
const DISMISS_PX = 64

/** What one drag measured when it began, in CSS pixels. */
interface DragFrame {
  pointerId: number
  startY: number
  startPx: number
  /** The header and tray alone: the sheet's height less its properties'. */
  peekPx: number
  halfPx: number
  fullPx: number
  moved: boolean
  /**
   * The height the last move set. A ref, not the `dragPx` state: a quick flick can deliver its
   * last move and the release before React renders, and the release must settle where the finger
   * let go, not one move behind.
   */
  lastPx: number | null
}

/** The height a released drag settles on: the nearest of the three, or `null` to let the sheet go. */
export function settleHeight(px: number, snaps: { peek: number; half: number; full: number }): PhoneSheetHeight | null {
  if (px < snaps.peek - DISMISS_PX) return null
  let best: PhoneSheetHeight = 'peek'
  for (const h of PHONE_SHEET_HEIGHTS) if (Math.abs(snaps[h] - px) < Math.abs(snaps[best] - px)) best = h
  return best
}

/**
 * **The fixture sheet's phone form** (fixture-fx-sheets plan D14, Hosts board): `useEditorForm`'s
 * two touch forms, sized for a whole sheet rather than one cell's editor. Mount `FixtureSheet` in it
 * with `host="phone"`, which is what gives the rows their finger sizes.
 *
 * - **On an upright phone, a bottom sheet with three heights** (`PhoneSheetHeight`) and a grabber. It
 *   opens at half. A drag on the grabber follows the finger and settles on the nearest height, and a
 *   drag well below the peek lets the sheet go; a tap — or Enter — steps it taller, round to the
 *   peek from full; the arrow keys step it either way. At the peek the properties fold away
 *   (`[data-sheet-body]`), leaving the header, the scope line and the tray.
 * - **On a short viewport (a landscape phone), the right-hand sheet**, the Stage panel's 380px,
 *   full height — no grabber, since there is no height to choose.
 *
 * Both keep the tray above the home indicator (the safe-area inset is the content's bottom padding,
 * under the tray, which is always the column's foot) and give back **the keyboard's bite** as
 * `EditorSurface` does, from `visualViewport` — the bottom sheet rises by it, the side sheet
 * shortens — since a `position: fixed` sheet would otherwise put the field being typed in behind
 * the keyboard.
 *
 * **Modal or not is the host's call.** The Stage view's is **not** modal: the canvas behind stays
 * live, so a tap on another fixture moves the sheet onto it and a tap on empty stage closes it,
 * exactly as the docked panel behaves — a modal sheet would make every other fixture one close
 * away. The busk view's, opened from a tile's menu, is modal.
 */
export function PhoneSheet({
  open,
  onClose,
  form,
  modal,
  description,
  className,
  children,
  ...data
}: {
  open: boolean
  onClose: () => void
  form: Exclude<EditorForm, 'popover'>
  modal: boolean
  /** The dialog's description, for a screen reader; the sheet's own header is its title. */
  description: string
  className?: string
  children: ReactNode
} & { [key: `data-${string}`]: string | undefined }) {
  const bottom = form === 'bottom-sheet'
  const keyboardInset = useKeyboardInset(open)
  const contentRef = useRef<HTMLDivElement>(null)
  const [height, setHeight] = useState<PhoneSheetHeight>('half')
  // Each open starts at half, the board's resting height — not wherever the last one was left.
  const [wasOpen, setWasOpen] = useState(open)
  if (open !== wasOpen) {
    setWasOpen(open)
    if (open) setHeight('half')
  }
  const [dragPx, setDragPx] = useState<number | null>(null)
  const drag = useRef<DragFrame | null>(null)
  // A drag ends in a click on the grabber too; that click is the drag's, not a tap's.
  const swallowClick = useRef(false)

  const step = (by: 1 | -1, wrap: boolean) =>
    setHeight((h) => {
      const i = PHONE_SHEET_HEIGHTS.indexOf(h) + by
      if (i >= PHONE_SHEET_HEIGHTS.length) return wrap ? PHONE_SHEET_HEIGHTS[0] : h
      return PHONE_SHEET_HEIGHTS[Math.max(0, i)]
    })

  const onPointerDown = (e: PointerEvent<HTMLButtonElement>) => {
    const content = contentRef.current
    if (content == null || (e.pointerType === 'mouse' && e.button !== 0)) return
    // A drag the browser cancelled is followed by no click, so its swallow must not outlive it.
    swallowClick.current = false
    const rect = content.getBoundingClientRect()
    const body = content.querySelector<HTMLElement>('[data-sheet-body]')
    const bodyPx = height === 'peek' || body == null ? 0 : body.getBoundingClientRect().height
    const viewport = window.innerHeight
    const fullPx = (viewport * FULL_SVH) / 100 - keyboardInset
    drag.current = {
      pointerId: e.pointerId,
      startY: e.clientY,
      startPx: rect.height,
      peekPx: Math.max(0, rect.height - bodyPx),
      halfPx: Math.min((viewport * HALF_SVH) / 100, fullPx),
      fullPx,
      moved: false,
      lastPx: null,
    }
    e.currentTarget.setPointerCapture?.(e.pointerId)
  }

  const onPointerMove = (e: PointerEvent<HTMLButtonElement>) => {
    const d = drag.current
    if (d == null || d.pointerId !== e.pointerId) return
    const dy = d.startY - e.clientY
    if (!d.moved && Math.abs(dy) < DRAG_SLOP_PX) return
    d.moved = true
    // Up to full and no further; down past the peek, so the dismiss can be felt coming.
    d.lastPx = Math.max(0, Math.min(d.fullPx, d.startPx + dy))
    setDragPx(d.lastPx)
  }

  const endDrag = (e: PointerEvent<HTMLButtonElement>, cancelled: boolean) => {
    const d = drag.current
    if (d == null || d.pointerId !== e.pointerId) return
    drag.current = null
    e.currentTarget.releasePointerCapture?.(e.pointerId)
    const px = d.lastPx
    setDragPx(null)
    if (!d.moved || px == null || cancelled) return
    // The click a released drag ends in is the drag's, not a tap's.
    swallowClick.current = true
    const settled = settleHeight(px, { peek: d.peekPx, half: d.halfPx, full: d.fullPx })
    if (settled == null) onClose()
    else setHeight(settled)
  }

  const onGrabberKey = (e: KeyboardEvent<HTMLButtonElement>) => {
    if (e.key !== 'ArrowUp' && e.key !== 'ArrowDown') return
    e.preventDefault()
    step(e.key === 'ArrowUp' ? 1 : -1, false)
  }

  const full = `calc(${FULL_SVH}svh - ${keyboardInset}px)`
  const shown: PhoneSheetHeight | 'drag' = dragPx != null ? 'drag' : height
  const style: CSSProperties = {
    ...(bottom
      ? {
          bottom: keyboardInset,
          maxHeight: full,
          height:
            shown === 'drag'
              ? `${dragPx}px`
              : shown === 'full'
                ? full
                : shown === 'half'
                  ? `min(${HALF_SVH}svh, ${full})`
                  : undefined,
        }
      : {
          // As `EditorSurface`'s side form: the inset added to the width rather than padded out of
          // it, `SheetContent`'s `sm:max-w-sm` lifted, and the height shortened by the keyboard.
          width: `calc(${SIDE_WIDTH} + env(safe-area-inset-right))`,
          maxWidth: 'none',
          height: `calc(100% - ${keyboardInset}px)`,
          paddingRight: 'env(safe-area-inset-right)',
        }),
  }

  return (
    <Sheet open={open} onOpenChange={(next) => !next && onClose()} modal={modal}>
      <SheetContent
        ref={contentRef}
        side={bottom ? 'bottom' : 'right'}
        data-phone-sheet={form}
        data-height={bottom ? shown : undefined}
        {...data}
        className={cn(
          'gap-0 p-0',
          // The home indicator's strip, under the tray; the keyboard covers it while it is up. A
          // class rather than the inline style, so it is still there to read where `env()` is not.
          keyboardInset === 0 && SAFE_BOTTOM_CLASS,
          bottom && 'rounded-t-xl',
          // The sheet's own × sits in the name's row, below the grabber rather than on it.
          bottom && '[&>[data-slot=sheet-close-x]]:top-7',
          // At the peek the properties fold away: the header, the scope line and the tray stay. The
          // bottom sheet's alone — a sheet turned to landscape at the peek has no grabber to bring
          // them back, and the side form has no heights.
          bottom && shown === 'peek' && '[&_[data-sheet-body]]:hidden',
          // A drag moves the height directly; a settle to half or full animates. The peek is the
          // content's own height (auto), which CSS cannot animate to or from, so it snaps.
          bottom && shown !== 'drag' && 'transition-[height]',
          className,
        )}
        style={style}
        // The canvas behind a non-modal sheet stays live: a tap there is the stage's to answer (the
        // next fixture, or empty stage closing the sheet), not an outside press that shuts it.
        onInteractOutside={modal ? undefined : (e) => e.preventDefault()}
        // Nor does a non-modal sheet take focus from the stage it opened over.
        onOpenAutoFocus={modal ? undefined : (e) => e.preventDefault()}
      >
        <SheetDescription className="sr-only">{description}</SheetDescription>
        {bottom && (
          <button
            type="button"
            data-sheet-grabber
            aria-label={`Sheet height: ${height === 'peek' ? 'header and effects' : height}. Drag, or tap to change`}
            onPointerDown={onPointerDown}
            onPointerMove={onPointerMove}
            onPointerUp={(e) => endDrag(e, false)}
            onPointerCancel={(e) => endDrag(e, true)}
            onClick={() => {
              if (swallowClick.current) {
                swallowClick.current = false
                return
              }
              step(1, true)
            }}
            onKeyDown={onGrabberKey}
            className="grid h-5 w-full flex-none cursor-grab touch-none place-items-center active:cursor-grabbing"
          >
            <span className="h-1 w-9 rounded-full bg-muted-foreground/40" />
          </button>
        )}
        <div className="flex min-h-0 flex-1 flex-col">{children}</div>
      </SheetContent>
    </Sheet>
  )
}
