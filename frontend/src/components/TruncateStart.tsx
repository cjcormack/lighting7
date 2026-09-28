import { useLayoutEffect, useRef, useState, type CSSProperties } from 'react'
import { cn } from '@/lib/utils'

/**
 * Single line clipped at its START — "…3.2.10", keeping the end that distinguishes one
 * cue number from the next.
 *
 * CSS can't do this. `text-overflow: ellipsis` only ever ellipsises the end of a line, and
 * the usual `direction: rtl` flip does move the ellipsis to the left but leaves the clipped
 * glyph's width behind as a gap ("… 3.2.10") — the remaining text is never re-flowed. So
 * the string is shortened here instead, and what renders is exactly what fits.
 *
 * Measurement is done on a canvas rather than by probing the DOM, so narrowing the text
 * costs no layout passes.
 *
 * The element may shrink-wrap its text — hosts rely on that to keep a click-to-edit target no
 * bigger than the value it edits. That needs care, because the width is both an input to the
 * clipping and an output of it: sized off the *displayed* string, every re-fit would shrink the
 * box, which would clip further, and the text would ratchet away to nothing. So an invisible
 * sizer carrying the FULL string sets the intrinsic width. The box therefore measures
 * `min(full text, whatever the parent allows)` — a function of the input text only — and the
 * clipped string is free to be shorter without feeding back.
 */

const ELLIPSIS = '…'

let measureCtx: CanvasRenderingContext2D | null | undefined

function context(): CanvasRenderingContext2D | null {
  // undefined = not tried yet, null = unavailable (jsdom has no canvas).
  if (measureCtx === undefined) {
    measureCtx = document.createElement('canvas').getContext('2d')
  }
  return measureCtx
}

export function TruncateStart({
  text,
  className,
  style,
  title,
}: {
  text: string
  /**
   * Bounds the element. Give it a width (`w-full`, a fixed track) to fill a cell, or leave it
   * unbounded to shrink-wrap the text — either way the box never sizes itself off the *clipped*
   * string, so the fit is stable.
   */
  className?: string
  /** As `className` — for widths that have to be computed (see `cueNumberCellWidth`). */
  style?: CSSProperties
  title?: string
}) {
  const ref = useRef<HTMLSpanElement>(null)
  const [display, setDisplay] = useState(text)

  useLayoutEffect(() => {
    const el = ref.current
    const ctx = context()
    // Nothing to measure with (jsdom, or a browser refusing a 2d context): show the string
    // whole. Returning without this would freeze the element on the text it first mounted
    // with, so a renamed or renumbered cue would keep rendering its old label.
    if (!el || !ctx) {
      setDisplay(text)
      return
    }

    const fit = () => {
      const style = getComputedStyle(el)
      // Sub-pixel width, deliberately not `clientWidth`. `clientWidth` rounds to an integer, and
      // when the element shrink-wraps its text the two are the same measurement — so a box of
      // 42.15px reports 42, the text "doesn't fit" its own width, and a character is clipped off
      // a label that was never too long. `getBoundingClientRect` keeps the fraction; the padding
      // and border it includes come back off here.
      const avail =
        el.getBoundingClientRect().width -
        parseFloat(style.paddingLeft) -
        parseFloat(style.paddingRight) -
        parseFloat(style.borderLeftWidth) -
        parseFloat(style.borderRightWidth)
      // Not laid out yet (or in a collapsed pane): leave the last good text alone rather
      // than clipping everything down to a bare ellipsis.
      if (!(avail > 0)) return

      ctx.font = `${style.fontStyle} ${style.fontWeight} ${style.fontSize} ${style.fontFamily}`
      const fits = (s: string) => ctx.measureText(s).width <= avail

      if (fits(text)) {
        setDisplay(text)
        return
      }
      // Longest tail that still fits with the ellipsis in front of it.
      let lo = 0
      let hi = text.length
      while (lo < hi) {
        const mid = Math.ceil((lo + hi) / 2)
        if (fits(ELLIPSIS + text.slice(text.length - mid))) lo = mid
        else hi = mid - 1
      }
      setDisplay(ELLIPSIS + text.slice(text.length - lo))
    }

    fit()
    // Re-fit when the cell resizes. Absent in some test environments — the one-off fit
    // above still stands, so degrade instead of throwing.
    if (typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(fit)
    ro.observe(el)
    return () => ro.disconnect()
  }, [text])

  return (
    <span ref={ref} title={title} style={style} className={cn('block overflow-hidden', className)}>
      {/* The clipping is a visual accommodation, so assistive tech gets the whole string:
          announcing "…3.2.10" would make QS1-3.2.10 and QS2-3.2.10 indistinguishable. */}
      <span className="sr-only">{text}</span>
      {/* Intrinsic sizer — see the note above. `sr-only` can't do this job: it is positioned
          absolutely and so contributes no width at all. Zero-height and hidden, it costs a line
          box and nothing else. */}
      <span aria-hidden="true" className="invisible block h-0 overflow-hidden whitespace-nowrap">
        {text}
      </span>
      <span aria-hidden="true" className="block overflow-hidden whitespace-nowrap">
        {display}
      </span>
    </span>
  )
}
