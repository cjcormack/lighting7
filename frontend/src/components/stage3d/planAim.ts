import type { StageElementDto } from '../../api/stageElementApi'
import type { StageRegionDto } from '../../api/stageRegionApi'
import type { SpreadSkip } from '../../store/programmerOps'
import { rotateXY } from '../../lib/stageGeometry'
import { STAGE_PROJECTIONS, type ScreenPoint } from '../../lib/stageProjection'
import { insideOutline, regionOutline } from './edit/sectionHits'
import type { LightingBounds } from './stageCameras'

/**
 * Aim from the floor plan (venue-visit plan S13), the pure half: where a click on the Plan is in
 * stage metres, how high the point is, and how the desk's skips read. The desk solves pan and tilt;
 * nothing here does.
 */

/** Where a performer's face is, above whatever they stand on — what "aim at a region" means. */
export const HEAD_HEIGHT_M = 1.7

/** A point on the floor plan, in stage metres: x audience-right, y upstage. */
export interface PlanPoint {
  x: number
  y: number
}

/** A point to aim at, in stage metres. */
export interface AimPoint extends PlanPoint {
  z: number
}

/**
 * The plan's clicks while aiming is armed: the canvas emits, the aim controls listen — so the click
 * reaches whichever controls are mounted without the route threading a handler down to them.
 */
export interface PlanAimClicks {
  emit(point: PlanPoint): void
  subscribe(listener: (point: PlanPoint) => void): () => void
}

export function createPlanAimClicks(): PlanAimClicks {
  const listeners = new Set<(point: PlanPoint) => void>()
  return {
    emit(point) {
      for (const listener of [...listeners]) listener(point)
    },
    subscribe(listener) {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
  }
}

/**
 * Whether the aim layer takes the canvas's pointer: only while aiming is armed, on the Plan, with
 * Edit off, nothing being placed, no seat pick and not in a render. Everywhere else a click on a
 * fixture selects it and one on scenery opens its popover, as they always did.
 */
export function planAimTakesPointer(state: {
  armed: boolean
  camera: string
  editMode: boolean
  placing: boolean
  capturing: boolean
  seatPicking: boolean
}): boolean {
  return state.armed && state.camera === 'plan' && !state.editMode && !state.placing && !state.capturing && !state.seatPicking
}

/** A point on the Plan section (`lib/stageProjection.ts`'s `{h, v}`, v screen-down) in stage metres. */
export function planPointOf(section: ScreenPoint): PlanPoint {
  return { x: section.h, y: section.v === 0 ? 0 : -section.v }
}

/**
 * The placed region whose footprint holds the point — its top surface the highest where several
 * overlap (a rostrum on the deck), so the click lands on what a performer would stand on.
 */
export function regionUnder(regions: readonly StageRegionDto[], point: PlanPoint): StageRegionDto | null {
  const at = { h: point.x, v: -point.y }
  let best: StageRegionDto | null = null
  for (const region of regions) {
    if (region.centerX == null || region.centerY == null) continue
    if (!insideOutline(at, regionOutline(region, STAGE_PROJECTIONS.plan))) continue
    if (best == null || (region.centerZ ?? 0) > (best.centerZ ?? 0)) best = region
  }
  return best
}

/**
 * The floor under a point with no region on it: the deck across the stage's footprint, else the
 * house floor — a modelled room's, which is what Stage3D lands confetti on too.
 */
export function floorUnder(
  point: PlanPoint,
  stage: { width: number; depth: number },
  houseFloorZ: number,
): number {
  const onStage = Math.abs(point.x) <= stage.width / 2 && point.y >= 0 && point.y <= stage.depth
  return onStage ? 0 : houseFloorZ
}

/** The lowest drawn room's floor, or the deck where no room is modelled. */
export function houseFloorOf(elements: readonly StageElementDto[]): number {
  const rooms = elements.filter((e) => e.kind === 'ROOM' && !e.hidden)
  return rooms.length > 0 ? Math.min(...rooms.map((e) => e.positionZ)) : 0
}

/**
 * The drawn rooms' footprint on the plan — each a turned `widthM × depthM` about its origin, the
 * shell `scene/builders/room.ts` builds — what the Plan is fitted to beside the rig. Null where no
 * room is drawn (the Venue layer off, a canvas drawing a subset), so the Plan fits the rig alone.
 */
export function roomFootprint(elements: readonly StageElementDto[]): LightingBounds | null {
  let out: LightingBounds | null = null
  for (const room of elements) {
    if (room.kind !== 'ROOM' || !(room.widthM > 0) || !(room.depthM > 0)) continue
    const yaw = (room.yawDeg * Math.PI) / 180
    for (const [lx, ly] of [[-1, -1], [1, -1], [1, 1], [-1, 1]] as const) {
      const [rx, ry] = rotateXY((lx * room.widthM) / 2, (ly * room.depthM) / 2, yaw)
      const x = room.positionX + rx
      const y = room.positionY + ry
      const z = room.positionZ
      out =
        out == null
          ? { min: { x, y, z }, max: { x, y, z } }
          : {
              min: { x: Math.min(out.min.x, x), y: Math.min(out.min.y, y), z: Math.min(out.min.z, z) },
              max: { x: Math.max(out.max.x, x), y: Math.max(out.max.y, y), z: Math.max(out.max.z, z) },
            }
    }
  }
  return out
}

/** How a plan click's height is found: head height on what is under it, or the typed Z. */
export type AimHeight = 'surface' | 'z'

/**
 * A plan click as a point to aim at. On `surface`, head height above the top of the region under
 * it (`regionAimPoint`'s rule, at the click rather than the centre), else above [floorZ] — the deck,
 * or the house floor off the stage; on `z`, the typed height. Names the region it stood on, for the
 * read-out.
 */
export function planAimPoint(
  point: PlanPoint,
  height: AimHeight,
  typedZ: number | null,
  regions: readonly StageRegionDto[],
  floorZ = 0,
): { point: AimPoint; region: StageRegionDto | null } {
  if (height === 'z' && typedZ != null) return { point: { ...point, z: typedZ }, region: null }
  const region = regionUnder(regions, point)
  return { point: { ...point, z: round3((region?.centerZ ?? floorZ) + HEAD_HEIGHT_M) }, region }
}

/** The desk's skips, one line per reason, the heads in the order the desk answered them. */
export function skipsByReason(
  skipped: readonly SpreadSkip[],
  nameOf: (key: string) => string,
): { reason: string; names: string[] }[] {
  const groups = new Map<string, string[]>()
  for (const skip of skipped) {
    const names = groups.get(skip.reason)
    if (names) names.push(nameOf(skip.target.key))
    else groups.set(skip.reason, [nameOf(skip.target.key)])
  }
  return [...groups].map(([reason, names]) => ({ reason, names }))
}

/** Metres to the millimetre — what the fields show and the request sends. */
export function round3(n: number): number {
  return Math.round(n * 1000) / 1000
}
