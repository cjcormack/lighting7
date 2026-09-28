// Geometry helpers for the prompt-book overlay layer.
//
// All persisted geometry is normalized [0..1] against page dimensions — never
// pixels — so overlays survive zoom, resize, and re-display. Rendering is pure
// CSS percentages; only pointer interactions need a pixel conversion, always
// relative to the page element's current box.

import type { CSSProperties } from 'react'
import { clamp } from '../utils'
import type { Rect, Region, CueAnchorDto } from '../../api/promptBooksApi'
import type { CueStack } from '../../api/cueStacksApi'
import { scriptPosition, type FlatCue } from './desync'

/** Position a normalized rect inside a `position: relative` page wrapper. */
export function rectToStyle(r: Rect): CSSProperties {
  return {
    position: 'absolute',
    left: `${r.x * 100}%`,
    top: `${r.y * 100}%`,
    width: `${r.w * 100}%`,
    height: `${r.h * 100}%`,
  }
}

/** Convert a client-space point to normalized page coords, clamped to [0,1]. */
export function clientPointToNormalized(
  clientX: number,
  clientY: number,
  pageEl: Element,
): { x: number; y: number } {
  const box = pageEl.getBoundingClientRect()
  const x = box.width > 0 ? (clientX - box.left) / box.width : 0
  const y = box.height > 0 ? (clientY - box.top) / box.height : 0
  return { x: clamp(x, 0, 1), y: clamp(y, 0, 1) }
}

/**
 * Group items (anchors/annotations) by the pages their region touches, carrying
 * only the rects that sit on each page. One implementation for both overlay
 * kinds so page-membership rules can't drift between them.
 */
export function groupByPage<T>(
  items: T[],
  regionOf: (item: T) => Region,
): Map<number, { item: T; rects: Rect[] }[]> {
  const map = new Map<number, { item: T; rects: Rect[] }[]>()
  for (const item of items) {
    const region = regionOf(item)
    const pages = new Set(region.map((r) => r.page))
    for (const page of pages) {
      const rects = region.filter((r) => r.page === page)
      map.set(page, [...(map.get(page) ?? []), { item, rects }])
    }
  }
  return map
}

/**
 * Move every rect of a region vertically by `dy` (normalized), clamping the
 * delta so no rect leaves its page. The region moves as one unit — a page-break
 * span keeps its shape.
 */
export function moveRegionVertically(region: Region, dy: number): Region {
  let clamped = dy
  for (const r of region) {
    clamped = Math.max(clamped, -r.y)
    clamped = Math.min(clamped, 1 - r.h - r.y)
  }
  return region.map((r) => ({ ...r, y: r.y + clamped }))
}

/**
 * Vertical extent of a set of rects (all assumed on one page): the topmost y and
 * the total height down to the lowest edge. Used to draw a single gutter band that
 * spans everything a region touches on a page.
 */
export function verticalBounds(rects: Rect[]): { top: number; height: number } {
  if (rects.length === 0) return { top: 0, height: 0 }
  const top = Math.min(...rects.map((r) => r.y))
  const bottom = Math.max(...rects.map((r) => r.y + r.h))
  return { top, height: Math.max(bottom - top, 0) }
}

/**
 * Fixed normalized x for the cue/cut marker lane. Every margin marker (chip, cut
 * tag, accent band) anchors its right edge here so they form a single vertical
 * rail in the left margin — aligned regardless of where each region's text starts.
 * Sits in the script's typical left margin; markers overflow left into the paper
 * gutter. The on-text wash / strikethrough is unaffected — only the margin rail.
 */
export const MARKER_LANE_X = 0.035

/**
 * Absolute-position style for a cue/cut margin marker: spans the region's vertical
 * bounds with its right edge in the shared lane (`laneX`), growing leftward into
 * the paper gutter. Shared by the cue and cut markers so their rail geometry can't
 * drift apart.
 */
export function marginRailStyle(rects: Rect[], laneX: number): CSSProperties {
  const { top, height } = verticalBounds(rects)
  return {
    position: 'absolute',
    top: `${top * 100}%`,
    height: `${Math.max(height * 100, 1.6)}%`,
    right: `${(1 - laneX) * 100}%`,
    marginRight: 2,
  }
}

/** Order two rect corners into a normalized rect, with a minimum size floor. */
export function cornersToRect(
  page: number,
  a: { x: number; y: number },
  b: { x: number; y: number },
  minSize = 0.005,
): Rect {
  const x = Math.min(a.x, b.x)
  const y = Math.min(a.y, b.y)
  const w = Math.max(Math.abs(a.x - b.x), minSize)
  const h = Math.max(Math.abs(a.y - b.y), minSize)
  return { page, x, y, w: Math.min(w, 1 - x), h: Math.min(h, 1 - y) }
}

// ── Text-selection → Region ─────────────────────────────────────────────
// A browser text selection over the PDF text layer yields one client rect per
// text run (Chrome/Safari) or per line (Firefox). We attribute each to the page
// it lands on, normalize it, then merge the slivers back into per-line bands so a
// highlight hugs the actual words instead of a full-width box.

/** Minimal client-rect shape (a DOMRect, or a plain object in tests). */
export interface ClientRectLike {
  left: number
  top: number
  right: number
  bottom: number
}

/** A page's on-screen box, in the same client space as the selection rects. */
export interface PageBox {
  page: number
  left: number
  top: number
  width: number
  height: number
}

/**
 * Horizontal gap (normalized to page WIDTH) above which a run break is treated as
 * a two-column gutter (character name | dialogue) and split, rather than a word
 * space to union across. Kept in width units because x is width-normalized.
 */
const COLUMN_GAP_X = 0.05

/**
 * Union same-line rects into per-line bands, per page. Text-layer selections
 * come back as many per-run slivers; without this a single highlighted line
 * persists as a dozen rects. Rects are bucketed into lines by vertical overlap,
 * then unioned along x — but a large horizontal gap (a two-column line: character
 * name | dialogue) splits the band so the gutter isn't swallowed.
 */
export function mergeRectsByLine(rects: Rect[]): Rect[] {
  const byPage = new Map<number, Rect[]>()
  for (const r of rects) byPage.set(r.page, [...(byPage.get(r.page) ?? []), r])

  const out: Rect[] = []
  for (const [page, prects] of byPage) {
    // 1) bucket into lines by vertical OVERLAP. A midpoint±tolerance test snowballs
    //    adjacent lines together on tightly-leaded scripts (the accepted band keeps
    //    growing as rects join); requiring real overlap keeps distinct lines apart
    //    while still tolerating per-run height jitter within a line.
    const lines: { top: number; bottom: number; rects: Rect[] }[] = []
    for (const r of [...prects].sort((a, b) => a.y - b.y)) {
      const line = lines.find((l) => {
        const overlap = Math.min(l.bottom, r.y + r.h) - Math.max(l.top, r.y)
        return overlap > 0.5 * Math.min(l.bottom - l.top, r.h)
      })
      if (line) {
        line.rects.push(r)
        line.top = Math.min(line.top, r.y)
        line.bottom = Math.max(line.bottom, r.y + r.h)
      } else {
        lines.push({ top: r.y, bottom: r.y + r.h, rects: [r] })
      }
    }
    // 2) union along x within each line, breaking on a large horizontal gap
    for (const line of lines) {
      const top = line.top
      const height = line.bottom - line.top
      const gap = COLUMN_GAP_X
      const xs = [...line.rects].sort((a, b) => a.x - b.x)
      let cur = { x: xs[0].x, right: xs[0].x + xs[0].w }
      for (let i = 1; i < xs.length; i++) {
        const r = xs[i]
        if (r.x - cur.right > gap) {
          out.push({ page, x: cur.x, y: top, w: cur.right - cur.x, h: height })
          cur = { x: r.x, right: r.x + r.w }
        } else {
          cur.right = Math.max(cur.right, r.x + r.w)
        }
      }
      out.push({ page, x: cur.x, y: top, w: cur.right - cur.x, h: height })
    }
  }
  return out
}

/**
 * Convert client-space selection rects into a normalized {@link Region}, pure so
 * it can be unit-tested without a live DOM Range. Each rect is attributed to the
 * page whose box contains its centre; rects in the inter-page gap (belonging to
 * no page) are dropped rather than force-clipped onto one page, and zero-width
 * slivers (trailing spaces / line breaks) are discarded. See {@link rangeToRegion}.
 */
export function clientRectsToRegion(rects: ClientRectLike[], pages: PageBox[]): Region {
  const collected: Rect[] = []
  for (const cr of rects) {
    const w = cr.right - cr.left
    const h = cr.bottom - cr.top
    if (w <= 0.5 || h <= 0.5) continue
    const cx = cr.left + w / 2
    const cy = cr.top + h / 2
    const p = pages.find(
      (pg) => cx >= pg.left && cx <= pg.left + pg.width && cy >= pg.top && cy <= pg.top + pg.height,
    )
    if (!p || p.width <= 0 || p.height <= 0) continue
    const x = clamp((cr.left - p.left) / p.width, 0, 1)
    const y = clamp((cr.top - p.top) / p.height, 0, 1)
    const x1 = clamp((cr.right - p.left) / p.width, 0, 1)
    const y1 = clamp((cr.bottom - p.top) / p.height, 0, 1)
    collected.push({ page: p.page, x, y, w: x1 - x, h: y1 - y })
  }
  return mergeRectsByLine(collected)
}

/**
 * Build a normalized {@link Region} from a live DOM selection Range against the
 * currently-mounted page elements. Thin wrapper over {@link clientRectsToRegion}.
 */
export function rangeToRegion(range: Range, pageEls: Map<number, HTMLElement>): Region {
  const pages: PageBox[] = []
  for (const [page, el] of pageEls) {
    const b = el.getBoundingClientRect()
    pages.push({ page, left: b.left, top: b.top, width: b.width, height: b.height })
  }
  return clientRectsToRegion(Array.from(range.getClientRects()), pages)
}

/**
 * Flatten the show into the authoritative cue order the desync check walks: the project's
 * runnable stacks in show order (`sortOrder`), each contributing its STANDARD cues in stack order.
 * SEPARATOR stacks and in-stack MARKER cues contribute nothing.
 */
export function flattenCueOrder(stacks: CueStack[] | undefined): FlatCue[] {
  if (!stacks) return []
  const out: FlatCue[] = []
  const ordered = [...stacks]
    .filter((s) => s.type === 'STACK')
    .sort((a, b) => a.sortOrder - b.sortOrder)
  for (const stack of ordered) {
    for (const cue of stack.cues) {
      if (cue.cueType !== 'STANDARD') continue
      out.push({
        cueId: cue.id,
        label: cue.cueNumber ? `Q${cue.cueNumber}` : cue.name,
        name: cue.name,
        fadeMs: cue.fadeDurationMs,
        fadeCurve: cue.fadeCurve,
        stackId: stack.id,
        stackName: stack.name,
      })
    }
  }
  return out
}

/**
 * Compute the stack's cue-id order after inserting a new cue at the reading position
 * of `region` — the ordering to POST to `reorderCueStackCues` when a cue is created
 * from a prompt-book selection. `stack.cues` is the CURRENT list (before the new cue
 * lands in cache); the new cue is spliced in immediately after the last anchored cue
 * whose reading position precedes-or-equals the selection.
 *
 * When there are no anchored cues to compare against, the new cue is appended — the
 * natural result of anchoring a stack top-to-bottom. When the stack has anchors but
 * none precede the selection, it goes to the front.
 *
 * Placement is advisory: unanchored neighbours have no reading position and keep their
 * slot, and any residual disorder is surfaced by the existing desync warnings — this
 * doesn't attempt a total re-sort of the stack.
 */
export function orderedCueIdsForInsert(
  stack: CueStack,
  anchorByCue: Map<number, CueAnchorDto>,
  region: Region,
  newCueId: number,
): number[] {
  const sel = scriptPosition(region)
  const ids = stack.cues.map((c) => c.id)
  const anchored = stack.cues
    .map((c, i) => ({ i, anchor: anchorByCue.get(c.id) }))
    .filter((e): e is { i: number; anchor: CueAnchorDto } => e.anchor != null)
  // Nothing to order against → append (natural when anchoring a stack top-to-bottom).
  if (anchored.length === 0) return [...ids, newCueId]
  let insertIndex = 0
  for (const { i, anchor } of anchored) {
    const pos = scriptPosition(anchor.region)
    if (pos.page < sel.page || (pos.page === sel.page && pos.y <= sel.y)) {
      insertIndex = i + 1
    }
  }
  return [...ids.slice(0, insertIndex), newCueId, ...ids.slice(insertIndex)]
}

/** A borrowed reading position for a cue that has no anchor of its own. */
export interface AnchorFallback {
  /** The anchored cue whose reading position we're borrowing. */
  cue: FlatCue
  region: Region
  /** Where the borrowed cue sits relative to the requested one, in show order. */
  direction: 'before' | 'after'
}

/**
 * Best-effort reading position for a cue with no anchor — a pre-show state or an
 * auto-followed cue reasonably has no line to point at, but the book should still
 * navigate somewhere sensible.
 *
 * Searches BACKWARDS from the cue first: you read down the script, so the previous
 * cue's anchor is the closest known "you are here". Only when nothing precedes it
 * (a cue ahead of every anchor in the show) does it borrow forwards.
 *
 * Deliberately crosses stack boundaries — `cueOrder` is the show's flattened reading
 * order, so a wholly unanchored act still borrows from the act before it. Returns null
 * when the cue isn't in the order or the book has no anchors at all.
 */
export function nearestAnchoredCue(
  cueId: number,
  cueOrder: FlatCue[],
  anchorByCue: Map<number, CueAnchorDto>,
): AnchorFallback | null {
  const index = cueOrder.findIndex((c) => c.cueId === cueId)
  if (index < 0) return null
  for (let i = index - 1; i >= 0; i--) {
    const anchor = anchorByCue.get(cueOrder[i].cueId)
    if (anchor) return { cue: cueOrder[i], region: anchor.region, direction: 'before' }
  }
  for (let i = index + 1; i < cueOrder.length; i++) {
    const anchor = anchorByCue.get(cueOrder[i].cueId)
    if (anchor) return { cue: cueOrder[i], region: anchor.region, direction: 'after' }
  }
  return null
}

/** A flattened cue list interleaved with per-stack header rows. */
export type CueRailRow =
  | { type: 'header'; stackId: number; stackName: string }
  | { type: 'cue'; cue: FlatCue }

/**
 * Interleave a flattened cue order with a header row at each stack boundary — the
 * shared row model for the cue rail and the anchor picker (a prompt-book spans the
 * whole show, so both group cues under their stack).
 */
export function groupCuesByStack(cueOrder: FlatCue[]): CueRailRow[] {
  const out: CueRailRow[] = []
  let lastStackId: number | null = null
  for (const cue of cueOrder) {
    if (cue.stackId !== lastStackId) {
      out.push({ type: 'header', stackId: cue.stackId, stackName: cue.stackName })
      lastStackId = cue.stackId
    }
    out.push({ type: 'cue', cue })
  }
  return out
}

/** Rail row model that carries both project-level separators and in-stack MARKER cues as separators. */
export type ShowRailRow =
  | { type: 'header'; stackId: number; stackName: string }
  | { type: 'cue'; cue: FlatCue }
  // `source` disambiguates the two origins: a SEPARATOR stack's id and a MARKER cue's id come from
  // different tables and can collide, so consumers must key on `source`+`id`, not `id` alone.
  | { type: 'separator'; source: 'stack' | 'cue'; id: number; name: string }

/**
 * Build the prompt-book rail's rows straight from the project's ordered stacks — like
 * `flattenCueOrder` but keeping dividers as `separator` rows so the rail mirrors the Run view.
 * Both project-level SEPARATOR stacks and in-stack MARKER cues become `separator` rows. Per-stack
 * headers are emitted only when the show spans more than one runnable stack; with a single stack
 * the panel header already names it (no duplicate).
 */
export function flattenShowRows(stacks: CueStack[] | undefined): ShowRailRow[] {
  if (!stacks) return []
  const ordered = [...stacks].sort((a, b) => a.sortOrder - b.sortOrder)
  const distinctStacks = ordered.filter((s) => s.type === 'STACK').length
  const out: ShowRailRow[] = []
  for (const stack of ordered) {
    if (stack.type === 'SEPARATOR') {
      out.push({ type: 'separator', source: 'stack', id: stack.id, name: stack.label ?? stack.name })
      continue
    }
    if (distinctStacks > 1) {
      out.push({ type: 'header', stackId: stack.id, stackName: stack.name })
    }
    for (const cue of stack.cues) {
      if (cue.cueType === 'MARKER') {
        out.push({ type: 'separator', source: 'cue', id: cue.id, name: cue.name })
      } else {
        out.push({
          type: 'cue',
          cue: {
            cueId: cue.id,
            label: cue.cueNumber ? `Q${cue.cueNumber}` : cue.name,
            name: cue.name,
            fadeMs: cue.fadeDurationMs,
            fadeCurve: cue.fadeCurve,
            stackId: stack.id,
            stackName: stack.name,
          },
        })
      }
    }
  }
  return out
}

/**
 * A human "roughly where on the page" phrase for a region — used as the live
 * cue's trigger-line stand-in in the rail, since the PDF has no text layer to
 * quote. Buckets the region's reading-position y into fifths of the page.
 * e.g. `{ page: 11, y: 0.5 }` → "middle of p. 12" (page index is 0-based).
 * `coverPages` offsets the number to the script's own page 1 (see positionLabelFor).
 */
export function positionLabel(region: Region, coverPages = 0): string {
  if (region.length === 0) return ''
  const { page, y } = scriptPosition(region)
  return positionLabelFor(page, y, coverPages)
}

/**
 * The band + page phrasing for a raw reading position — the single source of the
 * "top of p. 9" wording, shared by the rail (which reduces a Region) and the Run
 * view (which gets `{page, y}` from the cue-locations endpoint). `page` is 0-based.
 *
 * `coverPages` is the count of leading front-matter pages (cover/title) before the
 * script's printed page 1, so the shown number matches the script's own numbering:
 *   • content page (page >= coverPages) → "p. ${page - coverPages + 1}"
 *   • front matter (page <  coverPages) → a cover label, not a number
 * Default 0 leaves the original "p. ${page + 1}" behaviour untouched.
 */
export function positionLabelFor(page: number, y: number, coverPages = 0): string {
  const band =
    y < 0.2 ? 'top' : y < 0.4 ? 'upper' : y < 0.6 ? 'middle' : y < 0.8 ? 'lower' : 'bottom'
  if (page < coverPages) {
    // Anchored on a front-matter page — no script page number applies.
    return coverPages > 1 ? `${band} of cover p. ${page + 1}` : `${band} of the cover`
  }
  return `${band} of p. ${page - coverPages + 1}`
}
