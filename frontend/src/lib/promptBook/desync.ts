// Desync detection for the prompt-book — ADVISORY ONLY. Nothing here reorders
// or blocks; we compute warnings, the operator decides.
//
// Two orderings that should agree in a clean prompt-book:
//   • cue-stack order  — authoritative sequence from the show (entries → stack cues)
//   • script position  — each anchor's (page, y) reading position
//
// Agreement means: advancing the stack moves you monotonically DOWN the script.
// Ported from docs/prototypes/model.ts (the design-session spec) with numeric cue ids.
//
// A cue with no anchor is NOT a fault: a pre-show state, house lights, or an
// auto-followed cue has no line to point at and never will. Unanchored cues are
// simply skipped here (the rail signals them quietly and borrows a neighbour's
// position for navigation — see geometry.nearestAnchoredCue).

import type { Rect, Region, CueAnchorDto, AnnotationDto } from '../../api/promptBooksApi'

/** One cue in the authoritative flattened show order (see geometry.flattenCueOrder). */
export interface FlatCue {
  cueId: number
  label: string
  /** Cue name (distinct from the "Q12" label) — shown in the rail's hero row. */
  name: string
  /** Fade-in duration in ms (null → snap), for the rail's transition meta. */
  fadeMs: number | null
  /** Fade curve name ("lin", "sin", …), for the rail's transition meta. */
  fadeCurve: string
  stackId: number
  stackName: string
}

export type WarningKind = 'out-of-order' | 'anchor-in-cut'

export interface DesyncWarning {
  kind: WarningKind
  /** The cue the warning is about — always an anchored cue, so it can be scrolled to. */
  cueId: number
  stackId: number
  message: string
}

/** Reading-order key for a region: earliest page, then topmost y. */
export function scriptPosition(region: Region): { page: number; y: number } {
  return region.reduce(
    (best, r) => (r.page < best.page || (r.page === best.page && r.y < best.y) ? { page: r.page, y: r.y } : best),
    { page: Infinity, y: Infinity },
  )
}

/** True if two rects overlap at all (same page, intersecting boxes). */
function rectsOverlap(a: Rect, b: Rect): boolean {
  if (a.page !== b.page) return false
  return a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y
}

export function regionsOverlap(a: Region, b: Region): boolean {
  return a.some((ra) => b.some((rb) => rectsOverlap(ra, rb)))
}

/**
 * Compute advisory warnings for a prompt-book against the authoritative cue order.
 * Recomputed reactively on every edit and on load — cheap enough to run in a memo.
 */
export function computeWarnings(
  anchors: CueAnchorDto[],
  annotations: AnnotationDto[],
  cueOrder: FlatCue[],
): DesyncWarning[] {
  const warnings: DesyncWarning[] = []
  const anchorByCue = new Map(anchors.map((a) => [a.cueId, a]))
  const cuts = annotations.filter((n) => n.kind === 'STRIKETHROUGH')

  // Walk cues in stack order, requiring monotonic script position. Unanchored cues
  // carry no reading position, so they neither warn nor break the comparison between
  // their anchored neighbours.
  let prev: { page: number; y: number } | null = null
  let prevLabel: string | null = null
  for (const cue of cueOrder) {
    const anchor = anchorByCue.get(cue.cueId)
    if (!anchor) continue

    const pos = scriptPosition(anchor.region)
    if (prev && (pos.page < prev.page || (pos.page === prev.page && pos.y < prev.y))) {
      warnings.push({
        kind: 'out-of-order',
        cueId: cue.cueId,
        stackId: cue.stackId,
        message: `${cue.label} sits earlier in the script than ${prevLabel ?? 'the cue before it'}. Check anchor placement or stack order.`,
      })
    }
    prev = pos
    prevLabel = cue.label

    if (cuts.some((cut) => regionsOverlap(anchor.region, cut.region))) {
      warnings.push({
        kind: 'anchor-in-cut',
        cueId: cue.cueId,
        stackId: cue.stackId,
        message: `${cue.label} is anchored inside a cut section.`,
      })
    }
  }

  return warnings
}
