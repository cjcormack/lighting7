/**
 * Where a press in a PDF text layer's gaps should put the caret.
 *
 * pdf.js draws each run of text as an absolutely positioned span, so the space beside and between
 * lines belongs to no text. Chrome anchors a selection started there somewhere arbitrary in the
 * layer, and react-pdf's `selecting` cover then stops it extending, so a drag from the margin
 * selects nothing. The Prompt Book anchors such a drag at the text nearest the press instead.
 */

/** One text run as laid out on screen. */
export interface TextRun {
  left: number
  top: number
  right: number
  bottom: number
  length: number
}

/** A character position within the runs: run `index`, `offset` characters in. */
export interface RunPosition {
  index: number
  offset: number
}

/**
 * The position nearest a point: the closest run on the point's line, else the run nearest above
 * or below it; at its start when the point is left of it, its end when right, else in proportion.
 */
export function nearestRunPosition(runs: readonly TextRun[], x: number, y: number): RunPosition | null {
  let best: RunPosition | null = null
  let bestDy = Infinity
  let bestDx = Infinity
  runs.forEach((run, index) => {
    if (run.length === 0 || run.right <= run.left) return
    const dy = y < run.top ? run.top - y : y > run.bottom ? y - run.bottom : 0
    const dx = x < run.left ? run.left - x : x > run.right ? x - run.right : 0
    if (dy < bestDy || (dy === bestDy && dx < bestDx)) {
      bestDy = dy
      bestDx = dx
      const share = (x - run.left) / (run.right - run.left)
      best = { index, offset: Math.round(Math.min(Math.max(share, 0), 1) * run.length) }
    }
  })
  return best
}

/**
 * The upright text spans pdf.js drew in a layer, each with its text node. A rotated span (a
 * diagonal watermark) is left out: its bounding box covers most of the page.
 */
function layerSpans(layer: Element): { node: Text; rect: DOMRect }[] {
  const spans: { node: Text; rect: DOMRect }[] = []
  layer.querySelectorAll<HTMLElement>('span[role="presentation"]').forEach((span) => {
    const node = span.firstChild
    if (!(node instanceof Text) || !node.data) return
    const rotate = span.style.getPropertyValue('--rotate')
    if (rotate && parseFloat(rotate) !== 0) return
    spans.push({ node, rect: span.getBoundingClientRect() })
  })
  return spans
}

/** The DOM position in `layer` nearest a client point, or null for a layer with no text. */
export function textPositionNear(layer: Element, x: number, y: number): { node: Text; offset: number } | null {
  const spans = layerSpans(layer)
  const pos = nearestRunPosition(
    spans.map(({ node, rect }) => ({
      left: rect.left,
      top: rect.top,
      right: rect.right,
      bottom: rect.bottom,
      length: node.data.length,
    })),
    x,
    y,
  )
  if (!pos) return null
  const { node, rect } = spans[pos.index]
  // Inside the run, the browser knows the glyphs better than a proportion does.
  if (x > rect.left && x < rect.right && y >= rect.top && y <= rect.bottom) {
    const range = document.caretRangeFromPoint?.(x, y)
    if (range?.startContainer === node) return { node, offset: range.startOffset }
  }
  return { node, offset: pos.offset }
}
