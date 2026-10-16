import { useEffect, useRef, useState, type RefObject } from 'react'

/** Two presses this close, neither of which moved, are the double press that restores a default. */
const DOUBLE_PRESS_MS = 400

/**
 * The drag behind a height grip — the busk rig's `RigHandle` and the cue sheet's pane share it.
 *
 * A press arms it; the rest of the drag lives on **window** listeners, as every drag on this desk
 * does, ended by `pointercancel` as by a release (a drag a touchscreen reclaims as a pan must not
 * leave a height the fact never learned). Each move writes the snapped height **imperatively** onto
 * [target] — so a pointer frame re-renders the grip and not what it sizes — and the release commits
 * it, only if the pointer moved. Two still presses within [DOUBLE_PRESS_MS] are the double press,
 * read off the presses rather than `dblclick` (the press cancels `pointerdown`, and whether a
 * browser still synthesises the click pair after that is its call). An unmount mid-drag cancels the
 * drag and writes nothing: what the target's height should be is its owner's to put right.
 */
export function useHeightGrip({
  target,
  direction,
  snap,
  onCommit,
  onReset,
  onDragging,
}: {
  /** The element whose height the grip sets. */
  target: RefObject<HTMLElement | null>
  /** 1 when dragging down grows the target (a grip under it), -1 when dragging up does. */
  direction: 1 | -1
  /** A raw dragged height to the one drawn — snapped and clamped. Read at move time. */
  snap: (raw: number) => number
  onCommit: (height: number) => void
  onReset: () => void
  /** A drag began or ended. */
  onDragging?: (dragging: boolean) => void
}) {
  const [dragging, setDragging] = useState(false)
  const drag = useRef<{ pointerId: number; startY: number; startHeight: number; height: number; moved: boolean } | null>(null)
  const stillPressAt = useRef<number | null>(null)
  // Through a ref: the host hands fresh callbacks each render, and a re-render mid-drag must not
  // tear the window listeners down.
  const latest = useRef({ snap, onCommit, onReset, onDragging })
  latest.current = { snap, onCommit, onReset, onDragging }

  useEffect(() => {
    if (!dragging) return
    const onMove = (e: PointerEvent) => {
      const state = drag.current
      if (state == null || e.pointerId !== state.pointerId) return
      const el = target.current
      if (el == null) return
      // Not until the pointer has moved: a still press snapping would commit a nudge and eat a
      // double press.
      if (e.clientY !== state.startY) state.moved = true
      if (!state.moved) return
      const next = latest.current.snap(state.startHeight + direction * (e.clientY - state.startY))
      if (next !== state.height) {
        state.height = next
        el.style.height = `${next}px`
      }
    }
    const onUp = (e: PointerEvent) => {
      const state = drag.current
      if (state == null || e.pointerId !== state.pointerId) return
      drag.current = null
      setDragging(false)
      latest.current.onDragging?.(false)
      if (state.moved) {
        // A drag is not half of a double press.
        stillPressAt.current = null
        latest.current.onCommit(state.height)
      } else if (stillPressAt.current != null && Date.now() - stillPressAt.current <= DOUBLE_PRESS_MS) {
        // `Date.now()` rather than the event's `timeStamp`, which a test cannot set.
        stillPressAt.current = null
        latest.current.onReset()
      } else stillPressAt.current = Date.now()
    }
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    window.addEventListener('pointercancel', onUp)
    return () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
      window.removeEventListener('pointercancel', onUp)
      if (drag.current != null) {
        drag.current = null
        latest.current.onDragging?.(false)
      }
    }
  }, [dragging, target, direction])

  const onPointerDown = (e: React.PointerEvent<HTMLElement>) => {
    if (e.button !== 0) return
    e.preventDefault()
    const el = target.current
    if (el == null) return
    const startHeight = el.getBoundingClientRect().height
    drag.current = { pointerId: e.pointerId, startY: e.clientY, startHeight, height: startHeight, moved: false }
    setDragging(true)
    latest.current.onDragging?.(true)
    // Captured, so a drag that runs off the window still delivers its release. After the drag is
    // armed: the window listeners carry it whether or not the capture takes, and a pointer the
    // browser does not hold (a synthetic one) throws.
    try {
      e.currentTarget.setPointerCapture(e.pointerId)
    } catch {
      // Uncaptured: the window listeners still see every move and the release.
    }
  }

  return { dragging, onPointerDown }
}
