import { useCallback, useEffect, useRef, useState } from 'react'
import { useLongPress } from './useLongPress'

/** How long a finger must hold still on a pip before a drag across the row is a run — the marquee's number. */
export const PIP_HOLD_MS = 500

/**
 * The busk pip's gesture (busk-further plan D11), for a row of pips that each toggle one thing: **a
 * tap toggles**, **a mouse drag runs** — the pip under the press toggles on `pointerdown`, the row
 * takes pointer capture and every pip the pointer crosses toggles once — and **a held finger runs**:
 * a touch or pen runs only after a 500ms hold (`useLongPress`), because on a touchscreen a finger
 * pans and a distance-armed run would pick on every scroll, and a non-passive `touchmove` guard
 * stops the pan for as long as the run lasts. The click a run's release generates is swallowed so
 * it is not a second toggle; a pip's own `onClick` is the keyboard's toggle.
 *
 * Two rows share it: the busk rig tile's cells (`RigTile`) and the fixture sheet's head strip
 * (`HeadStrip`). A pip is found by its [attribute], whose value is the key [onToggle] is handed.
 *
 * [hot] is the pip under a live touch run, which a row draws large so the finger can see it.
 */
export function usePipRun<E extends HTMLElement = HTMLElement>({
  attribute,
  inert,
  onToggle,
}: {
  /** The data attribute each pip carries, its value the pip's key — `data-rig-pip`. */
  attribute: string
  /** The row takes no press (a rig being edited). */
  inert: boolean
  onToggle: (key: string) => void
}) {
  const rowRef = useRef<E | null>(null)
  /** The live run: the pointer that owns it and the pips it has already toggled. */
  const run = useRef<{ pointerId: number; toggled: Set<string> } | null>(null)
  /** A touch press waiting for its hold, and the pip it landed on. */
  const pending = useRef<{ pointerId: number; key: string | null } | null>(null)
  /** Set by a run's release: the click the browser is about to deliver is not a second toggle. */
  const swallowClick = useRef(false)
  const [hot, setHot] = useState<string | null>(null)
  const touchGuard = useRef<((e: TouchEvent) => void) | null>(null)

  const toggleRef = useRef(onToggle)
  toggleRef.current = onToggle

  const releaseTouchGuard = useCallback(() => {
    if (touchGuard.current) {
      window.removeEventListener('touchmove', touchGuard.current)
      touchGuard.current = null
    }
  }, [])
  useEffect(() => releaseTouchGuard, [releaseTouchGuard])

  const toggle = useCallback((key: string | null) => {
    const live = run.current
    if (key == null || live == null || live.toggled.has(key)) return
    live.toggled.add(key)
    toggleRef.current(key)
  }, [])

  const start = useCallback(
    (pointerId: number, key: string | null, held: boolean) => {
      run.current = { pointerId, toggled: new Set() }
      // A finger's run is followed off the row and the browser's pan is refused for as long as
      // it lasts (`useCellMarquee`'s `touchmove` guard, for its reason).
      if (held) {
        const guard = (e: TouchEvent) => e.preventDefault()
        window.addEventListener('touchmove', guard, { passive: false })
        touchGuard.current = guard
        setHot(key)
      }
      try {
        rowRef.current?.setPointerCapture(pointerId)
      } catch {
        // jsdom has no pointer capture; the browser always does.
      }
      toggle(key)
    },
    [toggle],
  )

  const end = useCallback(() => {
    if (run.current != null) {
      run.current = null
      // The click this release generates lands on a pip (or the row, with capture) in the same
      // task or the next; a click that never comes must not eat the next keyboard activation.
      swallowClick.current = true
      setTimeout(() => {
        swallowClick.current = false
      }, 350)
    }
    pending.current = null
    setHot(null)
    releaseTouchGuard()
  }, [releaseTouchGuard])

  // The touch arm: armed by time, never by distance, so a finger that moves before the hold is the
  // browser's scroll and `useLongPress` cancels the hold on its travel.
  const { handlers: hold } = useLongPress({
    delayMs: PIP_HOLD_MS,
    onLongPress: () => {
      const press = pending.current
      if (press == null || run.current != null) return
      start(press.pointerId, press.key, true)
    },
  })

  const pipAt = (e: React.PointerEvent): string | null => {
    const selector = `[${attribute}]`
    const direct = (e.target as Element | null)?.closest?.(selector)
    const under = direct ?? document.elementFromPoint?.(e.clientX, e.clientY)?.closest(selector) ?? null
    return under?.getAttribute(attribute) ?? null
  }

  const onPointerDown = (e: React.PointerEvent) => {
    if (inert || e.button !== 0 || run.current != null || pending.current != null) return
    // Whatever the row overlays (a rig tile's own button) must not see this press.
    e.stopPropagation()
    const key = pipAt(e)
    if (e.pointerType === 'touch' || e.pointerType === 'pen') {
      pending.current = { pointerId: e.pointerId, key }
      hold.onPointerDown(e)
      return
    }
    start(e.pointerId, key, false)
  }

  const onPointerMove = (e: React.PointerEvent) => {
    const live = run.current
    if (live != null) {
      if (e.pointerId !== live.pointerId) return
      const key = pipAt(e)
      if (touchGuard.current) setHot(key)
      toggle(key)
      return
    }
    if (pending.current?.pointerId === e.pointerId) hold.onPointerMove(e)
  }

  const onPointerUp = (e: React.PointerEvent) => {
    if (run.current != null && e.pointerId !== run.current.pointerId) return
    if (pending.current != null && pending.current.pointerId !== e.pointerId) return
    hold.onPointerUp()
    end()
  }

  const onPointerCancel = (e: React.PointerEvent) => {
    if (run.current != null && e.pointerId !== run.current.pointerId) return
    hold.onPointerCancel()
    end()
  }

  const onClickCapture = (e: React.MouseEvent) => {
    if (!swallowClick.current) return
    swallowClick.current = false
    e.preventDefault()
    e.stopPropagation()
  }

  return {
    rowRef,
    hot,
    rowHandlers: { onPointerDown, onPointerMove, onPointerUp, onPointerCancel, onClickCapture },
  }
}
