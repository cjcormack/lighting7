// Alignment guides: snapping a dragged object to the rows and columns other
// objects already occupy.
//
// This is what makes "line this fixture up with that one" feel right, and it's
// the thing a plain grid can't do — a rig's useful alignments are relative to the
// other fixtures, not to absolute multiples of 0.25 m.
//
// Pure arithmetic over projected screen-metre coordinates, so it's testable and
// projection-agnostic.

import type { ScreenPoint } from './stageProjection'

/** Candidate rows and columns to snap onto, in screen metres. */
export interface GuideCandidates {
  h: number[]
  v: number[]
}

export interface GuideSource {
  /** Stable identity, so the dragged object doesn't snap to itself. */
  id: string
  points: ScreenPoint[]
}

export interface GuideSnapResult {
  p: ScreenPoint
  /** The h value snapped onto, or null if the h axis wasn't claimed. */
  hitH: number | null
  /** The v value snapped onto, or null if the v axis wasn't claimed. */
  hitV: number | null
}

/**
 * Collects the distinct rows and columns occupied by everything except `excludeId`.
 *
 * Call this **once at drag start**, not per frame: with a few hundred fixtures,
 * rebuilding it on every pointermove is the obvious performance cliff, and the
 * candidates can't meaningfully change mid-gesture anyway (only the dragged
 * object is moving, and it's excluded).
 *
 * Values are de-duplicated to a tight tolerance so a hundred fixtures sharing a
 * bar contribute one candidate rather than a hundred coincident ones.
 */
export function buildGuideCandidates(
  sources: GuideSource[],
  excludeId: string | null,
): GuideCandidates {
  const h: number[] = []
  const v: number[] = []
  for (const source of sources) {
    if (source.id === excludeId) continue
    for (const p of source.points) {
      pushDistinct(h, p.h)
      pushDistinct(v, p.v)
    }
  }
  h.sort((a, b) => a - b)
  v.sort((a, b) => a - b)
  return { h, v }
}

const DEDUPE_EPSILON_M = 1e-6

function pushDistinct(into: number[], value: number) {
  for (const existing of into) {
    if (Math.abs(existing - value) < DEDUPE_EPSILON_M) return
  }
  into.push(value)
}

function nearest(values: number[], target: number, toleranceM: number): number | null {
  let best: number | null = null
  let bestDelta = toleranceM
  for (const value of values) {
    const delta = Math.abs(value - target)
    if (delta <= bestDelta) {
      bestDelta = delta
      best = value
    }
  }
  return best
}

/**
 * Snaps a point onto the nearest candidate row and column within tolerance.
 *
 * The two axes are decided independently, so a fixture can align horizontally
 * with one neighbour and vertically with a different one — which is usually
 * exactly what's wanted.
 *
 * `toleranceM` should be derived from the zoom (a pixel threshold times
 * `mPerPx`), not fixed in metres: a fixed-metre tolerance feels sticky when
 * zoomed out and unreachable when zoomed in.
 */
export function applyGuideSnap(
  p: ScreenPoint,
  candidates: GuideCandidates,
  toleranceM: number,
): GuideSnapResult {
  const hitH = nearest(candidates.h, p.h, toleranceM)
  const hitV = nearest(candidates.v, p.v, toleranceM)
  return {
    p: { h: hitH ?? p.h, v: hitV ?? p.v },
    hitH,
    hitV,
  }
}

/**
 * Guide snapping first, then grid snapping on whichever axes the guides didn't
 * claim.
 *
 * That precedence is the standard behaviour in drawing tools, and it matters: if
 * the grid ran first it would pull the point off the neighbour's row before the
 * guide ever got a chance to catch it, so exact alignment would be unreachable at
 * any position that isn't already a grid multiple.
 */
export function snapWithGuides(
  p: ScreenPoint,
  candidates: GuideCandidates | null,
  toleranceM: number,
  snapToGrid: (value: number) => number,
): GuideSnapResult {
  const guided = candidates
    ? applyGuideSnap(p, candidates, toleranceM)
    : { p, hitH: null, hitV: null }
  return {
    hitH: guided.hitH,
    hitV: guided.hitV,
    p: {
      h: guided.hitH != null ? guided.hitH : snapToGrid(guided.p.h),
      v: guided.hitV != null ? guided.hitV : snapToGrid(guided.p.v),
    },
  }
}
