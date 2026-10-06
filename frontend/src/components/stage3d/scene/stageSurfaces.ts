import type { StageElementDto } from '../../../api/stageElementApi'
import type { LightingBounds } from '../stageCameras'
import type { BeamClip, HazePlane, RegionGeometry, StageDims } from '../StageEmitters'
import { boxCollider, elementColliders, type Collider, type Face } from './beamReach'
import { buildElement } from './builders'
import { NO_SIDE_X } from '../beamShaders'
import { elementInLayers, type HazeExtent, type SceneLayers } from './sceneView'
import type { ElementBuild } from './sceneParts'
import type { SceneBuild } from './StageSceneElements'

/**
 * What the Stage view's canvas draws and where its beams land, derived from the scene document, the
 * regions and the stage box (stage-view plan session 3). Pure, so the rules below are pinned by a
 * node test rather than on screen:
 *
 * - **The modelled room replaces the stage's own shell.** While a `ROOM` is drawn, the stage box's
 *   back wall, the grid and the catch floor round the stage give way to it; the stage's own floor
 *   stays (a platform or a region top at 0 draws over it, a few millimetres proud).
 * - **Without a room, light still lands off the stage**: a *catch floor* the grid's size takes the
 *   pools the retired floor cookies drew anywhere on the plane.
 * - **Every drawn surface stops a beam**: the stage floor, its back wall while drawn, the catch
 *   floor, every drawn region, and every colliding part of every drawn element.
 */

/** How thick the stage floor's and back wall's slabs are, below and behind their faces. */
const SLAB_M = 0.02

/** Builds already made, by the element object they were made from. */
export type SceneBuildCache = WeakMap<StageElementDto, ElementBuild>

/**
 * The elements a view draws: in its layers, and built. With a [cache], an element the list still
 * holds as the same object — RTK Query's cache writes keep every row they do not touch — is not
 * built again: a drag on a section writes one element a frame, and rebuilding the whole hall,
 * seating and all, sixty times a second to move one flat is the cost this saves.
 */
export function sceneBuilds(
  elements: readonly StageElementDto[],
  layers: SceneLayers,
  cache?: SceneBuildCache,
): SceneBuild[] {
  const out: SceneBuild[] = []
  for (const element of elements) {
    if (!elementInLayers(element, layers)) continue
    const cached = cache?.get(element)
    const build: ElementBuild = cached ?? buildElement(element)
    if (cached !== build) cache?.set(element, build)
    if (build.parts.length === 0 && build.seats.length === 0) continue
    out.push({ element, build })
  }
  return out
}

/** Whether a modelled room is drawn — which retires the stage box's back wall, grid and catch floor. */
export function drawsRoom(builds: readonly SceneBuild[]): boolean {
  return builds.some((b) => b.element.kind === 'ROOM' && b.build.parts.length > 0)
}

/** The regions' OBBs as colliders — the same boxes the beam shaders shadow-test. */
export function regionColliders(regions: readonly RegionGeometry[]): Collider[] {
  return regions.map((r) =>
    boxCollider(r.obbCenter.x, r.obbCenter.y, r.obbCenter.z, r.obbHalfX, r.obbHalfY, r.obbHalfZ, r.yawRad),
  )
}

const UP: Face = { x: 0, y: 1, z: 0 }
const DOWNSTAGE: Face = { x: 0, y: 0, z: 1 }

/**
 * Everything a beam can stop at in this view: the stage's floor, its back wall and the catch floor
 * where they are drawn, the drawn regions and the drawn elements.
 */
export function sceneColliders({
  stage,
  regions,
  builds,
  catchSizeM,
}: {
  stage: StageDims
  regions: readonly RegionGeometry[]
  builds: readonly SceneBuild[]
  catchSizeM: number
}): Collider[] {
  const t = SLAB_M / 2
  // The floor and wall are planes drawn from their face only, so a sight line through the back of
  // one is not stopped.
  const out: Collider[] = [
    // The stage floor: the footprint, its top at 0. Three: y up, z = −lighting Y.
    boxCollider(0, -t, -stage.depth / 2, stage.width / 2, t, stage.depth / 2, 0, undefined, undefined, UP),
  ]
  if (!drawsRoom(builds)) {
    out.push(boxCollider(0, stage.height / 2, -stage.depth - t, stage.width / 2, stage.height / 2, t, 0, undefined, undefined, DOWNSTAGE))
    const catchFloor = boxCollider(0, -t, 0, catchSizeM / 2, t, catchSizeM / 2)
    catchFloor.sight = false
    out.push(catchFloor)
  }
  out.push(...regionColliders(regions))
  for (const { element, build } of builds) out.push(...elementColliders(element, build))
  return out
}

/**
 * The floor and walls the beams clip to in the air: the stage's own floor and back wall, or — while
 * a room is drawn — the lowest room floor, the furthest upstage room wall and the outermost side
 * walls, so a hung head's beam reaches the hall floor in front of a raised stage rather than
 * stopping at deck height, and a long throw stays inside the building.
 */
export function beamClipFor(stage: StageDims, builds: readonly SceneBuild[]): BeamClip {
  let floorZ = 0
  let wallY = stage.depth
  let minX = Infinity
  let maxX = -Infinity
  for (const { element, build } of builds) {
    if (element.kind !== 'ROOM' || build.parts.length === 0) continue
    floorZ = Math.min(floorZ, element.positionZ)
    const yaw = (element.yawDeg * Math.PI) / 180
    const hw = element.widthM / 2
    const hd = element.depthM / 2
    // The room's furthest corners upstage and to each side, whatever its turn.
    const reach = Math.abs(Math.sin(yaw)) * hw + Math.abs(Math.cos(yaw)) * hd
    wallY = Math.max(wallY, element.positionY + reach)
    const side = Math.abs(Math.cos(yaw)) * hw + Math.abs(Math.sin(yaw)) * hd
    minX = Math.min(minX, element.positionX - side)
    maxX = Math.max(maxX, element.positionX + side)
  }
  return minX <= maxX ? { floorZ, wallY, minX, maxX } : { floorZ, wallY, minX: -NO_SIDE_X, maxX: NO_SIDE_X }
}

/**
 * Where a window's haze stops. *Stage* is upstage of the proscenium — the most downstage one, by its
 * own turn — or of the stage's downstage edge where none is modelled. Read from the stored elements,
 * not the drawn ones, so hiding the Venue layer does not move the haze. Null for *everywhere* and
 * *off* (whose beams are not drawn at all).
 */
export function hazeClipFor(
  extent: HazeExtent,
  elements: readonly Pick<StageElementDto, 'kind' | 'hidden' | 'positionX' | 'positionY' | 'yawDeg'>[],
): HazePlane | null {
  if (extent !== 'stage') return null
  let pros: (typeof elements)[number] | null = null
  for (const e of elements) {
    if (e.kind !== 'PROSCENIUM' || e.hidden) continue
    if (pros == null || e.positionY < pros.positionY) pros = e
  }
  if (pros == null) return { nx: 0, ny: 1, d: 0 }
  // The wall's own +Y, turned by +yaw (anticlockwise from above, as `elementColliders` turns it),
  // and kept pointing upstage whichever way round the wall was placed.
  const yaw = (pros.yawDeg * Math.PI) / 180
  let nx = -Math.sin(yaw)
  let ny = Math.cos(yaw)
  if (ny < 0) {
    nx = -nx
    ny = -ny
  }
  return { nx, ny, d: nx * pros.positionX + ny * pros.positionY }
}

/** The drawn venue and set as one lighting-space box, for how deep a section sees; null when none. */
export function sceneElementBounds(builds: readonly SceneBuild[]): LightingBounds | null {
  let min: { x: number; y: number; z: number } | null = null
  let max: { x: number; y: number; z: number } | null = null
  const grow = (x: number, y: number, z: number) => {
    if (min == null || max == null) {
      min = { x, y, z }
      max = { x, y, z }
      return
    }
    min.x = Math.min(min.x, x)
    min.y = Math.min(min.y, y)
    min.z = Math.min(min.z, z)
    max.x = Math.max(max.x, x)
    max.y = Math.max(max.y, y)
    max.z = Math.max(max.z, z)
  }
  for (const { element, build } of builds) {
    for (const c of elementColliders(element, { parts: build.parts.map((p) => ({ ...p, collides: true })), seats: [] })) {
      // The collider is a turned box in three.js space; its bounding circle about y is enough here.
      const r = Math.hypot(c.hx, c.hz)
      grow(c.cx - r, -c.cz - r, c.cy - c.hy)
      grow(c.cx + r, -c.cz + r, c.cy + c.hy)
    }
    for (const seat of build.seats) grow(seat.base.x, seat.base.y, seat.base.z + 1.2)
  }
  return min == null || max == null ? null : { min, max }
}
