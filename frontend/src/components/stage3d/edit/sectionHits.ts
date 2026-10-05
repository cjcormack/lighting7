import type { StageRegionDto } from '../../../api/stageRegionApi'
import type { RiggingDto } from '../../../api/riggingApi'
import type { StageElementDto } from '../../../api/stageElementApi'
import type { DrawnPoint } from '../../../hooks/useProjectedPatches'
import { rotateXY, worldCornersFor, worldEndpointsFor } from '../../../lib/stageGeometry'
import { seatBase, seatingParams } from '../../../lib/stageSeats'
import { project, type ScreenPoint, type StageProjection } from '../../../lib/stageProjection'
import { elementBaseZ, isElementShown, paramEnum } from '../scene/sceneParts'
import type { SelectionRef } from '../useStageSelection'

/**
 * What a pointer on an orthographic section is over, and what a marquee holds — pure, in the
 * section's screen metres, so the editing on the sections is pinned by node tests rather than on a
 * canvas (stage-view plan session 5). The shapes are the ones the SVG plot drew and hit-tested; the
 * scene itself is now drawn by the 3D renderer, and this is only where its objects are.
 */

/** Rendered bar thickness. Mirrors RIGGING_THICKNESS_M in RiggingMeshes. */
export const RIGGING_THICKNESS_M = 0.18
/** A rigging whose projected length is under this is edge-on to the viewer. */
export const DEGENERATE_LENGTH_M = 1e-6
/** How far from a fixture's point, in pixels, a press still takes it — the SVG plot's hit circle. */
export const FIXTURE_HIT_PX = 12
/** How far from a bar's line, in pixels, a press still takes it (at least half its thickness). */
export const RIGGING_HIT_PX = 7

export interface ProjectedRigging {
  rig: RiggingDto
  a: ScreenPoint
  b: ScreenPoint
  /** Projected length. Near zero means the bar points at the viewer. */
  lengthOnScreen: number
  degenerate: boolean
}

export function projectRigging(rig: RiggingDto, projection: StageProjection): ProjectedRigging {
  const [wa, wb] = worldEndpointsFor(rig)
  const a = project(wa, projection)
  const b = project(wb, projection)
  const lengthOnScreen = Math.hypot(b.h - a.h, b.v - a.v)
  return { rig, a, b, lengthOnScreen, degenerate: lengthOnScreen < DEGENERATE_LENGTH_M }
}

/**
 * A region's outline in the given projection. In plan the four floor corners are the visible
 * rectangle (yawed, so a polygon); in an elevation a yawed box has no axis-aligned outline, so it
 * is the bounding rectangle of all eight projected corners.
 */
export function regionOutline(region: StageRegionDto, projection: StageProjection): ScreenPoint[] {
  const corners = worldCornersFor(region).map(([x, y, z]) => project({ x, y, z }, projection))
  return projection.id === 'plan' ? corners.slice(0, 4) : boundingRect(corners)
}

/**
 * A scene element's outline on the section, or null where it has none to press: a `ROOM` — it is
 * the whole hall, so a press anywhere in it would take it and nothing would ever clear or pan; it is
 * picked from the list instead — and an element the view is not drawing. A seating block is the box
 * round its seats; every other kind the turned box its builder stands about its origin, its Z its
 * base (a flown piece's trim) or, for a platform, its top.
 */
export function elementOutline(element: StageElementDto, projection: StageProjection): ScreenPoint[] | null {
  if (element.kind === 'ROOM' || !isElementShown(element)) return null
  if (element.kind === 'SEATING') {
    const params = seatingParams(element)
    if (params == null) return null
    // The block's four corner seats hold every seat: an aisle only ever falls between two seats.
    const last = String.fromCharCode(params.firstRow.charCodeAt(0) + params.rows - 1)
    const corners = [`${params.firstRow}1`, `${params.firstRow}${params.seatsPerRow}`, `${last}1`, `${last}${params.seatsPerRow}`]
      .map((id) => seatBase(element, params, id))
      .filter((b) => b != null)
    if (corners.length === 0) return null
    const points = corners.flatMap((b) => [project(b, projection), project({ ...b, z: b.z + 1 }, projection)])
    return padRect(boundingRect(points), 0.3)
  }
  if (!(element.widthM > 0 && element.depthM > 0 && element.heightM > 0)) return null
  // The box the builder draws in (`scene/builders/object.ts`): a cylinder or a shade is `widthM`
  // across both ways, and a disc stands on its base `widthM` tall whatever its `heightM` says.
  const shape = element.kind === 'OBJECT' ? paramEnum(element, 'shape') ?? 'BOX' : 'BOX'
  const w = element.widthM
  const d = shape === 'CYLINDER' || shape === 'SHADE' ? element.widthM : element.depthM
  const h = shape === 'DISC' ? element.widthM : element.heightM
  const base = elementBaseZ(element)
  const z0 = element.kind === 'PLATFORM' ? base - h : base
  const yaw = (element.yawDeg * Math.PI) / 180
  const floor = [
    [-w / 2, -d / 2],
    [w / 2, -d / 2],
    [w / 2, d / 2],
    [-w / 2, d / 2],
  ].map(([lx, ly]) => {
    const [rx, ry] = rotateXY(lx, ly, yaw)
    return { x: element.positionX + rx, y: element.positionY + ry }
  })
  if (projection.id === 'plan') return floor.map((p) => project({ ...p, z: z0 }, projection))
  return boundingRect(floor.flatMap((p) => [project({ ...p, z: z0 }, projection), project({ ...p, z: z0 + h }, projection)]))
}

/** Where an element's origin is on the section — the anchor its drag moves. */
export function elementAnchor(element: StageElementDto, projection: StageProjection): ScreenPoint {
  return project({ x: element.positionX, y: element.positionY, z: element.positionZ }, projection)
}

function boundingRect(points: readonly ScreenPoint[]): ScreenPoint[] {
  let hMin = Infinity
  let hMax = -Infinity
  let vMin = Infinity
  let vMax = -Infinity
  for (const p of points) {
    hMin = Math.min(hMin, p.h)
    hMax = Math.max(hMax, p.h)
    vMin = Math.min(vMin, p.v)
    vMax = Math.max(vMax, p.v)
  }
  return [
    { h: hMin, v: vMin },
    { h: hMax, v: vMin },
    { h: hMax, v: vMax },
    { h: hMin, v: vMax },
  ]
}

function padRect(rect: ScreenPoint[], pad: number): ScreenPoint[] {
  const [a, , c] = rect
  return [
    { h: a.h - pad, v: a.v - pad },
    { h: c.h + pad, v: a.v - pad },
    { h: c.h + pad, v: c.v + pad },
    { h: a.h - pad, v: c.v + pad },
  ]
}

/** Whether [p] is inside the convex outline [poly] (either winding). */
export function insideOutline(p: ScreenPoint, poly: readonly ScreenPoint[]): boolean {
  let sign = 0
  for (let i = 0; i < poly.length; i++) {
    const a = poly[i]
    const b = poly[(i + 1) % poly.length]
    const cross = (b.h - a.h) * (p.v - a.v) - (b.v - a.v) * (p.h - a.h)
    if (Math.abs(cross) < 1e-12) continue
    const s = cross > 0 ? 1 : -1
    if (sign === 0) sign = s
    else if (s !== sign) return false
  }
  return true
}

/** The distance from [p] to the segment [a]–[b]. */
export function distanceToSegment(p: ScreenPoint, a: ScreenPoint, b: ScreenPoint): number {
  const dh = b.h - a.h
  const dv = b.v - a.v
  const lenSq = dh * dh + dv * dv
  const t = lenSq < 1e-12 ? 0 : Math.max(0, Math.min(1, ((p.h - a.h) * dh + (p.v - a.v) * dv) / lenSq))
  return Math.hypot(p.h - (a.h + dh * t), p.v - (a.v + dv * t))
}

/** What a press on a section lands on. A lantern (`placement`) presses as its fixture. */
export type SectionHit =
  | { kind: 'patch'; point: DrawnPoint }
  | { kind: 'rigging'; rig: RiggingDto }
  | { kind: 'region'; region: StageRegionDto }
  | { kind: 'element'; element: StageElementDto }

export interface SectionScene {
  projection: StageProjection
  /** Every fixture's own placement, then every lantern — what the plot drew. */
  points: readonly DrawnPoint[]
  riggings: readonly RiggingDto[]
  regions: readonly StageRegionDto[]
  elements: readonly StageElementDto[]
}

/**
 * The object under [p]: a fixture over the bar it hangs on, a bar over the areas — regions and
 * scenery — and among those the smallest. The nearest wins within a kind.
 * [mPerPx] turns the pixel radii into metres, so a press feels the same at every zoom.
 */
export function hitAt(scene: SectionScene, p: ScreenPoint, mPerPx: number): SectionHit | null {
  const fixtureR = FIXTURE_HIT_PX * mPerPx
  let bestPoint: { point: DrawnPoint; d: number } | null = null
  for (const point of scene.points) {
    let d = Math.hypot(p.h - point.screen.h, p.v - point.screen.v)
    if (point.span) d = Math.min(d, distanceToSegment(p, point.span[0], point.span[1]))
    if (d <= fixtureR && (bestPoint == null || d < bestPoint.d)) bestPoint = { point, d }
  }
  if (bestPoint) return { kind: 'patch', point: bestPoint.point }

  const rigR = Math.max(RIGGING_HIT_PX * mPerPx, RIGGING_THICKNESS_M / 2)
  let bestRig: { rig: RiggingDto; d: number } | null = null
  for (const rig of scene.riggings) {
    const pr = projectRigging(rig, scene.projection)
    const d = distanceToSegment(p, pr.a, pr.b)
    if (d <= rigR && (bestRig == null || d < bestRig.d)) bestRig = { rig, d }
  }
  if (bestRig) return { kind: 'rigging', rig: bestRig.rig }

  // Regions and scenery overlap by nature — a prop on a deck, a flat on a platform, a platform in
  // the stalls — so the smallest outline under the pointer wins: the thing the operator is pointing
  // at, not the floor it stands on. A tie goes to the region, listed first.
  let best: { hit: SectionHit; area: number } | null = null
  const consider = (hit: SectionHit, outline: readonly ScreenPoint[]) => {
    if (!insideOutline(p, outline)) return
    const area = outlineArea(outline)
    if (best == null || area < best.area) best = { hit, area }
  }
  for (const region of scene.regions) consider({ kind: 'region', region }, regionOutline(region, scene.projection))
  for (const element of scene.elements) {
    const outline = elementOutline(element, scene.projection)
    if (outline) consider({ kind: 'element', element }, outline)
  }
  return (best as { hit: SectionHit } | null)?.hit ?? null
}

/** The area of a simple outline (either winding). */
function outlineArea(poly: readonly ScreenPoint[]): number {
  let twice = 0
  for (let i = 0; i < poly.length; i++) {
    const a = poly[i]
    const b = poly[(i + 1) % poly.length]
    twice += a.h * b.v - b.h * a.v
  }
  return Math.abs(twice) / 2
}

/**
 * A marquee's catch: the fixtures inside the band, a lantern selecting its fixture and a fixture
 * caught twice counted once. Fixtures only — a marquee over a plot means "these lights", and taking
 * the regions under it would hand align and distribute a mixed set nobody asked for.
 */
export function marqueeHits(
  points: readonly DrawnPoint[],
  start: ScreenPoint,
  end: ScreenPoint,
): SelectionRef[] {
  const hMin = Math.min(start.h, end.h)
  const hMax = Math.max(start.h, end.h)
  const vMin = Math.min(start.v, end.v)
  const vMax = Math.max(start.v, end.v)
  const keys = new Set<string>()
  for (const { patch, screen } of points) {
    if (screen.h >= hMin && screen.h <= hMax && screen.v >= vMin && screen.v <= vMax) keys.add(patch.key)
  }
  return [...keys].map((patchKey) => ({ kind: 'patch', patchKey }))
}
