import { useCallback, useEffect, useMemo, useRef, useState } from 'react'

/**
 * Returns a `[callbackRef, index]` tuple: which of `floors` an element's content width is in, as an
 * index into that array. `floors` is **widest first**, and the answer is the first one the element
 * is at least as wide as — `floors.length - 1` (the narrowest band) when it is narrower than all of
 * them.
 *
 * **The one measurement hook.** `useNarrowContainer` is a two-band case of it and is now a wrapper
 * (see below), so the ResizeObserver discipline below — the parts with load-bearing comments —
 * exists once. The DMX sheet's 16 / 8 / 4 addresses to a row is the case that needed more than two:
 * two `useNarrowContainer`s on one node is two observers on one element and a hand-written ternary
 * beside the array, which is two places encoding how many bands there are.
 *
 * The ref callback **measures synchronously** before observing, so the initial guess (the widest
 * band) is committed but never painted: a `setState` from a ref callback is flushed in the layout
 * phase. Deliberately **not** debounced or rAF-throttled — `setBand(sameIndex)` is a React bail-out,
 * so a resize drag re-renders on the tick that crosses a floor and on no other. A zero width means
 * the element is `display:none` (or inside a collapsed ancestor), not that it is narrow, and is
 * ignored rather than collapsing the page to its narrowest arm.
 */
export function useContainerBand(
  floors: readonly number[],
  /** What to assume for the single commit before the element exists to measure. Never painted. */
  initialBand = 0,
): [(el: HTMLElement | null) => void, number] {
  const [band, setBand] = useState(initialBand)
  const observerRef = useRef<ResizeObserver | null>(null)

  // Assigned during render, not in an effect: a caller may compute its floors, and an
  // effect-written ref would leave a changed set inert until the next resize.
  const floorsRef = useRef(floors)
  floorsRef.current = floors

  const lastWidthRef = useRef<number | null>(null)

  const applyRef = useRef((width: number) => {
    if (width <= 0) return
    lastWidthRef.current = width
    setBand(bandFor(floorsRef.current, width))
  })

  const setRef = useCallback((el: HTMLElement | null) => {
    if (observerRef.current) {
      observerRef.current.disconnect()
      observerRef.current = null
    }
    if (!el) return
    applyRef.current(el.getBoundingClientRect().width)
    const observer = new ResizeObserver((entries) => {
      applyRef.current(entries[0]?.contentRect.width ?? 0)
    })
    observer.observe(el)
    observerRef.current = observer
    // Provably empty: every value this reads lives in a ref, and a changing callback ref would
    // detach and reattach the observer on every render.
  }, [])

  // A changed floor set has to re-decide against the width already known — the element has not
  // resized, so the observer will not fire again.
  const key = floors.join(',')
  useEffect(() => {
    if (lastWidthRef.current != null) setBand(bandFor(floorsRef.current, lastWidthRef.current))
  }, [key])

  useEffect(() => {
    return () => {
      observerRef.current?.disconnect()
      observerRef.current = null
    }
  }, [])

  return [setRef, band]
}

/** The widest band `width` fits, or the narrowest when it fits none. Exported for its test. */
export function bandFor(floors: readonly number[], width: number): number {
  const index = floors.findIndex((floor) => width >= floor)
  return index >= 0 ? index : Math.max(0, floors.length - 1)
}

/**
 * Returns a `[callbackRef, isNarrow]` tuple: `isNarrow` is true while the element's content width is
 * below `threshold`.
 *
 * The two-band case of [useContainerBand] — `[threshold, 0]`, narrow being band 1 — kept as its own
 * name because "is this narrow" is what most callers actually ask and reads better than an index.
 * It was a second full copy of the observer logic until the DMX sheet needed three bands.
 */
export function useNarrowContainer(
  threshold: number,
  {
    /**
     * What to assume for the single commit before the element exists to measure.
     *
     * Defaults to `false` (wide). It used to be `true`, which meant every mount painted the phone
     * layout for one frame — and because `ShowPage` swaps *subtrees* rather than hiding with CSS,
     * that frame mounted the whole `RunMobile` tree and threw it away. The synchronous measure in
     * `useContainerBand` means this guess is never painted either way; it only decides which tree is
     * built and discarded, and wide is the common case on a desk.
     */
    initial = false,
  }: { initial?: boolean } = {},
): [(el: HTMLElement | null) => void, boolean] {
  // Memoised so the floors identity — which `useContainerBand` re-decides against — moves only when
  // the threshold does. A caller may pass its threshold as a prop.
  const floors = useMemo(() => [threshold, 0], [threshold])
  const [setRef, band] = useContainerBand(floors, initial ? 1 : 0)
  return [setRef, band === 1]
}
