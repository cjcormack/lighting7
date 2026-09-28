import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { RefCallback, RefObject } from 'react'

/** Which way a horizontal scroller has more content, and whether it has any. */
export interface ScrollEdges {
  /** Scrolled off its start — there is content to the **left** of what is showing. */
  left: boolean
  /** There is content to the **right** of what is showing. */
  right: boolean
  /** The content is wider than the box **at all**, wherever it happens to be scrolled. */
  overflows: boolean
}

const NONE: ScrollEdges = { left: false, right: false, overflows: false }

export interface ScrollEdgesOptions {
  /**
   * Re-measure after **every render**, in a layout effect.
   *
   * For a scroller whose *content* changes without the scroller resizing and where the answer has
   * to be right in the frame the new content lands in — a row of chips that gains one, say. The
   * content `ResizeObserver` below catches the same change, but it fires *after* paint, so the
   * fade would arrive a frame late. Off by default: it costs a forced layout per render, which on
   * a grid that re-renders at 30 Hz under a fader drag is not worth a mask.
   */
  measureOnRender?: boolean
  /**
   * This element scrolls **vertically as well** — skip the measure when `scrollLeft` has not moved.
   *
   * A virtualized list and its columns share one `overflow-auto` box, so most scroll events on it
   * say nothing about the horizontal axis. Without this, flinging a 200-row grid downwards runs a
   * full measure per scroll frame on the path this codebase treats as performance-critical. With
   * it, a vertical tick costs one `scrollLeft` read and returns.
   *
   * Off by default, because for a purely horizontal scroller the guard would swallow the first
   * measure after a *content* change that left `scrollLeft` where it was.
   */
  horizontalOnly?: boolean
}

/**
 * Whether a horizontal scroller has more content off either edge — the one fact an edge fade or a
 * pair of scroll chevrons is drawn from.
 *
 * **Measured, because nothing else answers it.** These scrollers are `flex-1`, so their own box
 * does not change when their contents do; and the answer moves with the scroll position as well as
 * with either width. So the measure runs on three triggers, and each is a way the answer changes on
 * its own: the element's own `scroll`, a `ResizeObserver` on the element (the window, the rail, a
 * sibling taking room) **and on its first element child** (the content growing or shrinking inside
 * an unchanged box — a column switched off, a chip list refilled), and optionally every render.
 * State is written only when one of the three flags actually flips, so a scroll listener at frame
 * rate costs no renders.
 *
 * It replaced three near-identical hand-rolled copies — `TemplateStrip`'s `useScrollerOverflows`,
 * `StackTabStrip`'s inline `{left, right}` and `FixturesTable`'s own — which shared all of that
 * mechanism and differed only in which of the three answers they wanted. Hence three fields rather
 * than a derivation: `overflows` is **not** `left || right` at the edges (a scroller overflowing by
 * two pixels and sitting one pixel in reports neither), and one of the three callers is drawing a
 * mask that must stay up at the end of the travel while another is drawing a chevron that must not.
 *
 * `ResizeObserver` is guarded rather than assumed — jsdom has none unless a suite stubs one, and
 * these components render in suites that do not. Without it the scroll trigger and the render
 * trigger still answer; only a width change nothing else notices is missed, which in a test is
 * every width change.
 */
export function useScrollEdges<T extends HTMLElement>(
  ref: RefObject<T | null>,
  { measureOnRender = false, horizontalOnly = false }: ScrollEdgesOptions = {},
): ScrollEdges & { attach: RefCallback<T> } {
  const [edges, setEdges] = useState<ScrollEdges>(NONE)

  const measure = useCallback((el: HTMLElement) => {
    // 1px of slack throughout: `scrollWidth` and `clientWidth` are rounded integers of fractional
    // layout, so a row that fits exactly routinely reports a pixel of overflow — which would fade
    // a chip nothing is hiding, or light a chevron with nowhere to go.
    const maxScroll = el.scrollWidth - el.clientWidth
    const next: ScrollEdges = {
      left: el.scrollLeft > 1,
      right: el.scrollLeft < maxScroll - 1,
      overflows: el.scrollWidth > el.clientWidth + 1,
    }
    setEdges((prev) =>
      prev.left === next.left && prev.right === next.right && prev.overflows === next.overflows
        ? prev
        : next,
    )
  }, [])

  /**
   * The node, as state — and `attach` is how it gets here, which is the whole reason the caller
   * puts `ref={attach}` on the scroller instead of its own ref.
   *
   * A `RefObject` gives no signal when it is filled, and two of these scrollers are rendered
   * **conditionally** — the template strip appears with the selection, the fixtures table with a
   * non-empty list — so the element mounts *after* an effect reading `ref.current` has already
   * run and returned early. Nothing in that effect's dependencies then changes, so it never runs
   * again and the observers are never attached at all: the value then moves only on the
   * `measureOnRender` pass, which is to say only when something else happens to re-render the
   * component, and a scroller without that option would never update after its first frame.
   * Both of the hand-rolled copies this replaced had it, and it is invisible in a test — jsdom
   * has no `ResizeObserver` — so it is stated here rather than pinned.
   *
   * `attach` fills the caller's own ref as well, because these components read `.current` all
   * over for things that are not this hook's business (a virtualizer's scroll element, a
   * `scrollIntoView`, a paging `scrollBy`).
   */
  const [el, setEl] = useState<T | null>(null)
  const attach = useCallback<RefCallback<T>>(
    (node) => {
      ref.current = node
      setEl(node)
    },
    [ref],
  )

  /** The last `scrollLeft` a scroll event was acted on at — see `horizontalOnly`. */
  const lastScrollLeft = useRef<number | null>(null)

  useEffect(() => {
    if (!el) return
    lastScrollLeft.current = el.scrollLeft
    measure(el)

    const onScroll = () => {
      if (horizontalOnly) {
        const left = el.scrollLeft
        if (left === lastScrollLeft.current) return
        lastScrollLeft.current = left
      }
      measure(el)
    }
    el.addEventListener('scroll', onScroll, { passive: true })

    const observer =
      typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => measure(el))
    observer?.observe(el)
    // The content, not only the box: a column switched off or a chip added changes the scrollable
    // width without touching the scroller's own size, and an observer on the element alone is
    // blind to it.
    if (el.firstElementChild) observer?.observe(el.firstElementChild)

    return () => {
      el.removeEventListener('scroll', onScroll)
      observer?.disconnect()
    }
  }, [el, measure, horizontalOnly])

  // No deps array, and the flag is read inside rather than gating the hook: a conditional
  // `useLayoutEffect` would be a conditional hook, and a deps array whose length depends on an
  // option is a React error waiting for the day an option is made dynamic.
  useLayoutEffect(() => {
    if (measureOnRender && ref.current) measure(ref.current)
  })

  return { ...edges, attach }
}
