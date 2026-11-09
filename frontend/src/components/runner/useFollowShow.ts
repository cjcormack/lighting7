import { useEffect, useRef } from 'react'
import { useStableCallback } from '@/hooks/useStableCallback'
import { rowInView } from '@/components/sheet/revealCell'

/** How long a hand scroll holds following off. */
export const FOLLOW_PAUSE_MS = 10_000

/** Keys that scroll a focused list when nothing else takes them. */
const SCROLL_KEYS = new Set(['ArrowUp', 'ArrowDown', 'PageUp', 'PageDown', 'Home', 'End', ' '])

/** Controls that take Space themselves. */
const SPACE_TAKERS =
  'button, a[href], summary, [role=button], [role=menuitem], [role=option], [role=checkbox], [role=switch], [role=tab], [role=radio]'

export interface FollowShowOptions {
  stackId: number
  activeEntryId: number | null
  standbyEntryId?: number | null
  /** The cue the URL's `?cue=` names, which wins over the entry centring. */
  linkedEntryId?: number | null
  /** The list's scroller, read at the moment it is needed. */
  getScroller: () => HTMLElement | null
  /** An entry's row, where it is drawn; null where the virtualiser has not drawn it. */
  getRow: (entryId: number) => Element | null
  /** Centre an entry in the scroller. */
  scrollTo: (entryId: number) => void
  /** Off, the hook does nothing, and turning it on is entering the view. */
  enabled?: boolean
}

/**
 * The Show view's list follows the show: entering a stack centres its live entry, and a later GO or
 * arming centres the entry it moves to when that is out of view. A hand scroll holds following off
 * for {@link FOLLOW_PAUSE_MS}.
 */
export function useFollowShow({
  stackId,
  activeEntryId,
  standbyEntryId = null,
  linkedEntryId = null,
  getScroller,
  getRow,
  scrollTo,
  enabled = true,
}: FollowShowOptions): void {
  const scroller = useStableCallback(getScroller)
  const row = useStableCallback(getRow)
  const centre = useStableCallback(scrollTo)
  const pausedUntil = useRef(0)

  useEffect(() => {
    if (!enabled) return
    const pause = () => {
      pausedUntil.current = Date.now() + FOLLOW_PAUSE_MS
    }
    let cached: HTMLElement | null = null
    const current = () => {
      if (cached == null || !cached.isConnected) cached = scroller() ?? null
      return cached
    }
    const inScroller = (target: EventTarget | null) => {
      if (!(target instanceof Node)) return false
      const el = current()
      return el != null && el.contains(target)
    }
    const onWheelOrTouch = (e: Event) => {
      if (inScroller(e.target)) pause()
    }
    const onPointerDown = (e: PointerEvent) => {
      if (e.target != null && e.target === current()) pause()
    }
    const onKeyDown = (e: KeyboardEvent) => {
      if (!SCROLL_KEYS.has(e.key) || e.metaKey || e.ctrlKey || e.altKey) return
      if (!inScroller(document.activeElement) || isEditable(e.target)) return
      if (e.key === ' ' && e.target instanceof Element && e.target.closest(SPACE_TAKERS) != null) return
      // Asked once every handler has run: a key the sheet's cursor took is the sheet's own scroll.
      setTimeout(() => {
        if (!e.defaultPrevented) pause()
      }, 0)
    }
    document.addEventListener('wheel', onWheelOrTouch, { capture: true, passive: true })
    document.addEventListener('touchmove', onWheelOrTouch, { capture: true, passive: true })
    document.addEventListener('pointerdown', onPointerDown, true)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('wheel', onWheelOrTouch, { capture: true })
      document.removeEventListener('touchmove', onWheelOrTouch, { capture: true })
      document.removeEventListener('pointerdown', onPointerDown, true)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [enabled, scroller])

  const seen = useRef<{ stackId: number; active: number | null; standby: number | null; linked: number | null } | null>(null)
  useEffect(() => {
    if (!enabled) {
      seen.current = null
      return
    }
    const prev = seen.current
    seen.current = { stackId, active: activeEntryId, standby: standbyEntryId, linked: linkedEntryId }
    if (prev == null || prev.stackId !== stackId) {
      pausedUntil.current = 0
      if (linkedEntryId == null && activeEntryId != null) centre(activeEntryId)
      return
    }
    // A link arriving now is being honoured; following starts from the row it lands on.
    if (linkedEntryId != null && linkedEntryId !== prev.linked) return
    if (Date.now() < pausedUntil.current) return
    const target =
      activeEntryId != null && activeEntryId !== prev.active
        ? activeEntryId
        : standbyEntryId != null && standbyEntryId !== prev.standby
          ? standbyEntryId
          : null
    if (target == null) return
    const el = scroller()
    const rowEl = row(target)
    if (el == null || rowEl == null || !rowInView(rowEl, el)) centre(target)
  }, [enabled, stackId, activeEntryId, standbyEntryId, linkedEntryId, centre, row, scroller])
}

function isEditable(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  return target.isContentEditable || target.closest('input, textarea, select, [contenteditable="true"]') != null
}
