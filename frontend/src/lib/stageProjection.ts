// Orthographic projections of lighting space onto a 2D drawing plane: the
// top-down plan and the two elevations.
//
// This module is deliberately **three.js-free** — pure arithmetic, no Vector3,
// no MathUtils. `stageCoords` imports three at module scope, so anything that
// pulls it in drags the 3D chunk along; keeping the projection maths separate
// lets the cue-card MiniStage and the app-shell peek panel (mounted on *every*
// route) share it without that cost.
//
// Lighting coords are Z-up, FOH-relative, metres: X = stage right, Y = upstage,
// Z = up. The stage envelope is X ∈ [−w/2, w/2], Y ∈ [0, d], Z ∈ [0, h] — Y = 0
// is the downstage/FOH edge, not the centre.

export type ProjectionId = 'plan' | 'front' | 'side'
export type LightingAxis = 'x' | 'y' | 'z'

export interface AxisMap {
  axis: LightingAxis
  sign: 1 | -1
}

export interface StageProjection {
  id: ProjectionId
  label: string
  /** Screen-right. */
  h: AxisMap
  /** Screen-**down** — see the note on sign conventions below. */
  v: AxisMap
  /** The out-of-plane axis, which `unproject` preserves. */
  depth: LightingAxis
  hAxisLabel: string
  vAxisLabel: string
}

export interface ScreenPoint {
  h: number
  v: number
}

export interface LightingPoint {
  x: number
  y: number
  z: number
}

export interface StageDims {
  widthM: number
  depthM: number
  heightM: number
}

export interface Extent {
  hMin: number
  hMax: number
  vMin: number
  vMax: number
}

// `{h, v}` is screen-metre space with **v increasing downward**, so an SVG
// viewBox consumes it directly and no `transform="scale(1,-1)"` is ever needed
// (that would mirror every glyph and label, and you'd fight it forever). Every
// flip lives in these descriptors instead.
//
// `v = 0` is always the datum: the downstage edge in plan, the deck in the
// elevations.
//
// Front keeps `h = +x` rather than mirroring to theatre convention. The repo
// already puts +x on screen-right in the plan view and in the patch form's
// copy; mirroring only the front elevation would make two views disagree about
// which side of the screen a fixture is on, which is a worse trap than the
// notation quibble. (Worth knowing: the plan view is therefore already mirrored
// relative to a standard theatre plan. Changing that would move every existing
// fixture, so it stays as-is.)
//
// Side uses `h = +y`, putting FOH at screen-left and upstage at screen-right —
// the standard section layout, implying an eye on the +x side. The rendered axis
// legend states this, since "from stage right" is exactly the ambiguity the
// convention above creates.
export const STAGE_PROJECTIONS: Record<ProjectionId, StageProjection> = {
  plan: {
    id: 'plan',
    label: 'Plan',
    h: { axis: 'x', sign: 1 },
    v: { axis: 'y', sign: -1 },
    depth: 'z',
    hAxisLabel: 'Stage right (X)',
    vAxisLabel: 'Upstage (Y)',
  },
  front: {
    id: 'front',
    label: 'Front',
    h: { axis: 'x', sign: 1 },
    v: { axis: 'z', sign: -1 },
    depth: 'y',
    hAxisLabel: 'Stage right (X)',
    vAxisLabel: 'Height (Z)',
  },
  side: {
    id: 'side',
    label: 'Side',
    h: { axis: 'y', sign: 1 },
    v: { axis: 'z', sign: -1 },
    depth: 'x',
    hAxisLabel: 'Upstage (Y)',
    vAxisLabel: 'Height (Z)',
  },
}

export const PROJECTION_IDS: ProjectionId[] = ['plan', 'front', 'side']

export function isProjectionId(v: unknown): v is ProjectionId {
  return v === 'plan' || v === 'front' || v === 'side'
}

/**
 * Project a lighting point onto the drawing plane.
 *
 * Linear, so this doubles as a delta/size projector: `project({x: dx, y: dy,
 * z: dz}, proj)` gives the screen-space delta for a lighting-space vector.
 */
export function project(p: LightingPoint, proj: StageProjection): ScreenPoint {
  return { h: proj.h.sign * p[proj.h.axis], v: proj.v.sign * p[proj.v.axis] }
}

/**
 * Lift a screen point back into lighting space, taking the out-of-plane
 * coordinate from `existing`.
 *
 * `existing` must be the object's **world** lighting position, never its stored
 * rig-local `stage*` triple — the out-of-plane axis has to be preserved in world
 * space or a drag in one view silently shifts the object along the axis the user
 * can't see. Signs are ±1, so each is its own inverse.
 */
export function unproject(
  s: ScreenPoint,
  proj: StageProjection,
  existing: LightingPoint,
): LightingPoint {
  const out = { ...existing }
  out[proj.h.axis] = proj.h.sign * s.h
  out[proj.v.axis] = proj.v.sign * s.v
  return out
}

// Negating a zero bound yields -0, which stringifies harmlessly but fails
// identity comparisons and reads oddly in debug output. Collapse it.
function unsigned(v: number): number {
  return v === 0 ? 0 : v
}

/** The stage envelope's bounds in this projection's screen-metre space. */
export function projectionExtent(proj: StageProjection, dims: StageDims): Extent {
  const halfW = dims.widthM / 2
  const axisRange: Record<LightingAxis, [number, number]> = {
    x: [-halfW, halfW],
    y: [0, dims.depthM],
    z: [0, dims.heightM],
  }
  const [hLo, hHi] = axisRange[proj.h.axis]
  const [vLo, vHi] = axisRange[proj.v.axis]
  const hs = [proj.h.sign * hLo, proj.h.sign * hHi]
  const vs = [proj.v.sign * vLo, proj.v.sign * vHi]
  return {
    hMin: unsigned(Math.min(...hs)),
    hMax: unsigned(Math.max(...hs)),
    vMin: unsigned(Math.min(...vs)),
    vMax: unsigned(Math.max(...vs)),
  }
}

/**
 * The region actually visible in an `<svg preserveAspectRatio="xMidYMid meet">`
 * of pixel size `rectW × rectH` showing viewBox `vb`.
 *
 * `meet` scales to *fit*, so the visible region is **larger** than the viewBox on
 * whichever axis isn't constraining. Grid lines, the envelope backdrop and
 * marquee bounds must be drawn over this rather than over the viewBox, or the
 * grid mysteriously stops partway across the canvas at some window sizes.
 */
export function visibleExtent(vb: Extent, rectW: number, rectH: number): Extent {
  const vbW = vb.hMax - vb.hMin
  const vbH = vb.vMax - vb.vMin
  if (vbW <= 0 || vbH <= 0 || rectW <= 0 || rectH <= 0) return vb

  // Uniform scale that fits the viewBox inside the rect.
  const scale = Math.min(rectW / vbW, rectH / vbH)
  const visW = rectW / scale
  const visH = rectH / scale
  // xMidYMid centres the surplus on both axes.
  const padH = (visW - vbW) / 2
  const padV = (visH - vbH) / 2
  return {
    hMin: vb.hMin - padH,
    hMax: vb.hMax + padH,
    vMin: vb.vMin - padV,
    vMax: vb.vMax + padV,
  }
}

/**
 * A screen point as percentages of an extent, for the DOM-positioned thumbnail
 * views that place markers with `left`/`top` rather than an SVG viewBox.
 *
 * Not clamped: a fixture rigged outside the declared stage envelope should be
 * drawn outside the box rather than silently pinned to its edge.
 */
export function toPercent(s: ScreenPoint, e: Extent): { leftPct: number; topPct: number } {
  const w = e.hMax - e.hMin
  const h = e.vMax - e.vMin
  return {
    leftPct: w === 0 ? 50 : ((s.h - e.hMin) / w) * 100,
    topPct: h === 0 ? 50 : ((s.v - e.vMin) / h) * 100,
  }
}

/** Grow an extent by a uniform margin, e.g. to leave breathing room on Fit. */
export function padExtent(e: Extent, marginM: number): Extent {
  return {
    hMin: e.hMin - marginM,
    hMax: e.hMax + marginM,
    vMin: e.vMin - marginM,
    vMax: e.vMax + marginM,
  }
}
