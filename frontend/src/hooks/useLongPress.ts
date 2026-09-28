import { useCallback, useEffect, useMemo, useRef } from 'react'

/**
 * A press-and-hold gesture, as the busk view's pads have always drawn it.
 *
 * The rule is the one three components hand-rolled independently: a timer armed on `pointerdown`,
 * cancelled if the pointer travels more than a few pixels (so a drag over a scrolling pad grid is a
 * scroll, not a hold) or leaves the element, and a short press that fires {@link onPress} on
 * `pointerup` only when the hold did not.
 *
 * **The move threshold is why this is pointer events rather than `click`.** A pad grid scrolls, and
 * on a touchscreen every scroll begins as a press on whatever is under the finger; without the
 * threshold, dragging the library would fire a pad.
 *
 * {@link consumeLongPress} exists for the one case a pad does not have: handlers spread on a
 * *container* whose children are themselves buttons. Call it from the container's `onClickCapture`
 * and `stopPropagation()` when it returns true — capture runs root-to-child, so the child's own
 * `onClick` never sees the click that ended the hold. It is one-shot: the flag clears on read, and
 * again on the next `pointerdown`.
 *
 * `onLongPress` is handed **where the press started**, which is what lets a hold turn into a drag:
 * a hold-to-slide control seeds its value from the point the finger landed on, so the value does
 * not jump the moment the gesture arms. The hold fires while the pointer is still down, so a
 * consumer wanting the rest of the drag installs its own window listeners from there — this hook
 * deliberately does not own the drag, only the moment it begins.
 */
export interface PressOrigin {
  x: number
  y: number
}

/**
 * How long a fired hold's flag outlives the release, waiting for the click that release may
 * generate. A click that is coming lands in the same task or the next; this is the bound for one
 * that never comes.
 */
const LONG_PRESS_FLAG_MS = 350

export interface LongPressOptions {
  /**
   * Fired once the hold survives {@link delayMs}, while the pointer is still down, with the
   * viewport point the press started at.
   */
  onLongPress: (origin: PressOrigin) => void
  /** Fired on `pointerup` when the press was neither a hold nor a drag. */
  onPress?: () => void
  /** How long the hold must last. The busk view's pads have always used 500ms. */
  delayMs?: number
  /** How far the pointer may travel before the gesture becomes a drag and is abandoned. */
  moveThresholdPx?: number
  /** Arms nothing while true — used where a control underneath owns the pointer instead. */
  disabled?: boolean
}

export interface LongPressHandlers {
  onPointerDown: (e: React.PointerEvent) => void
  onPointerMove: (e: React.PointerEvent) => void
  onPointerUp: () => void
  onPointerLeave: () => void
  onPointerCancel: () => void
}

export function useLongPress({
  onLongPress,
  onPress,
  delayMs = 500,
  moveThresholdPx = 10,
  disabled = false,
}: LongPressOptions): {
  handlers: LongPressHandlers
  consumeLongPress: () => boolean
} {
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const didLongPress = useRef(false)
  const didMove = useRef(false)
  const startPos = useRef<{ x: number; y: number } | null>(null)

  // Read through refs so the returned handlers keep one identity for the life of the component:
  // they are spread onto an element, and a fresh object every render would defeat any memo above.
  const longPressRef = useRef(onLongPress)
  longPressRef.current = onLongPress
  const pressRef = useRef(onPress)
  pressRef.current = onPress
  const disabledRef = useRef(disabled)
  disabledRef.current = disabled

  const cancel = useCallback(() => {
    if (timer.current) {
      clearTimeout(timer.current)
      timer.current = null
    }
  }, [])

  // The timer must not outlive the component: a hold that matures after unmount would call
  // `onLongPress` for an element that is gone — on a template chip, that is a live request.
  useEffect(() => cancel, [cancel])

  const handlers = useMemo<LongPressHandlers>(
    () => ({
      onPointerDown: (e: React.PointerEvent) => {
        didLongPress.current = false
        didMove.current = false
        // A second pointer while one is down is a pinch or a fumble, not a hold: the first
        // press's timer is dropped rather than left to fire early against the newcomer, and no
        // new one is armed. `didMove` marks the gesture spent so the release fires no press.
        if (startPos.current) {
          cancel()
          didMove.current = true
          return
        }
        if (disabledRef.current) return
        startPos.current = { x: e.clientX, y: e.clientY }
        const origin = { x: e.clientX, y: e.clientY }
        timer.current = setTimeout(() => {
          timer.current = null
          didLongPress.current = true
          if (!didMove.current) longPressRef.current(origin)
        }, delayMs)
      },
      onPointerMove: (e: React.PointerEvent) => {
        if (!startPos.current || didMove.current) return
        const dx = e.clientX - startPos.current.x
        const dy = e.clientY - startPos.current.y
        if (dx * dx + dy * dy > moveThresholdPx * moveThresholdPx) {
          didMove.current = true
          cancel()
        }
      },
      onPointerUp: () => {
        cancel()
        if (!didLongPress.current && !didMove.current) pressRef.current?.()
        // A fired hold's flag waits for the click this release generates, and the click may
        // never come — a finger that lifts off the element (implicit capture still delivers the
        // `pointerup` here) produces none. Left set, the flag would eat the next keyboard
        // activation of the same element. Cleared after a short window instead: the click, when
        // there is one, lands well inside it.
        if (didLongPress.current) {
          setTimeout(() => {
            didLongPress.current = false
          }, LONG_PRESS_FLAG_MS)
        }
        startPos.current = null
      },
      onPointerLeave: () => {
        cancel()
        startPos.current = null
      },
      // The browser taking the gesture over — a touch the scroller claims as a pan — ends it with
      // no `pointerup` at all. Without this the armed timer survives the touch and the hold fires
      // on a finger that is already scrolling something else.
      onPointerCancel: () => {
        cancel()
        startPos.current = null
      },
    }),
    [cancel, delayMs, moveThresholdPx],
  )

  const consumeLongPress = useCallback(() => {
    const fired = didLongPress.current
    didLongPress.current = false
    return fired
  }, [])

  return { handlers, consumeLongPress }
}

/**
 * Open a Radix context menu from a touch hold, by synthesising the event a right-click would send.
 *
 * Radix's `ContextMenuTrigger` has its own ~700ms touch long-press, but a surface that already owns
 * a hold ({@link useLongPress}) must not run two hold detectors against one finger — so the app's
 * hold fires first and *dispatches* the menu, which also clears Radix's timer through its own
 * `onContextMenu` handler. Both callers pass the origin `onLongPress` hands them, which is what puts
 * the menu under the finger rather than at the element's corner.
 *
 * Extracted because this was its second verbatim copy (`BuskPad` and `CueSlotCell`), and a third is
 * likely: if the synthetic event ever needs a fix — `pageX`/`pageY`, `composed` for a shadow host —
 * one copy would get it.
 */
export function dispatchSyntheticContextMenu(
  element: HTMLElement | null | undefined,
  { x, y }: PressOrigin,
): void {
  element?.dispatchEvent(new MouseEvent('contextmenu', { bubbles: true, clientX: x, clientY: y }))
}
