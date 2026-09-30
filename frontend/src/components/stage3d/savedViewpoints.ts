import type { StageElementDto } from '../../api/stageElementApi'
import type {
  CreateStageViewpointRequest,
  StageViewpointDto,
} from '../../api/stageViewpointApi'
import type { EyePose, OrbitPose, Vec3 } from '../../lib/stageCameraPoses'
import {
  DEFAULT_SEAT_FOV_DEG,
  DEFAULT_SEAT_TARGET,
  seatBase,
  seatEye,
  seatingParams,
  type LightingPoint3,
} from '../../lib/stageSeats'
import {
  isSeatViewpointRef,
  isStageCamera,
  parseSeatViewpointRef,
  type SavedViewCamera,
  type SavedViewpointRef,
  type SeatViewpointRef,
  type StageCamera,
  type StageViewpoint,
} from '../../lib/stageViewpoint'
import type { StageCameraLanding } from './StageCameraRig'
import { EYE_DEFAULT_FOV_DEG, clampFov, eyeTarget, lookAngles } from './stageCameras'

/**
 * Saved viewpoints, turned into what the cameras take and back (stage-view plan session 2).
 * Pure and three.js-free: rows and elements in, poses in the desk's three.js space out
 * (`lib/stageCoords.ts`: x right, y up, z towards the house — lighting X, Z, −Y).
 *
 * - An `ORBIT` row lands the orbit camera at its eye, circling its target.
 * - An `EYE` row lands the eye at its eye, looking at its target, through its lens.
 * - A `SEAT` row lands the eye at the seat's seated eye (`lib/stageSeats.ts`), looking at the
 *   row's target or, with none, at the stage.
 */

function toThreeVec(p: LightingPoint3): Vec3 {
  return [p.x, p.z, -p.y]
}

function fromThreeVec(v: Vec3): LightingPoint3 {
  return { x: v[0], y: -v[2], z: v[1] }
}

function point(x: number | null, y: number | null, z: number | null): LightingPoint3 | null {
  return x != null && y != null && z != null ? { x, y, z } : null
}

/** The camera a saved view lands, from its kind alone. */
export function savedViewCamera(row: Pick<StageViewpointDto, 'kind'>): SavedViewCamera {
  return row.kind === 'ORBIT' ? 'orbit' : 'eye'
}

/** Every row's camera, by uuid — what `noteSavedViewpointCameras` is told. */
export function savedViewCameras(rows: readonly StageViewpointDto[]): Map<string, SavedViewCamera> {
  return new Map(rows.map((row) => [row.uuid, savedViewCamera(row)]))
}

/** A seat's seated eye and its element, or null when the seat cannot be found. */
function seatOf(seatElementUuid: string | null, seatId: string | null, elements: readonly StageElementDto[]) {
  if (seatElementUuid == null || seatId == null) return null
  const element = elements.find((e) => e.uuid === seatElementUuid)
  if (element == null) return null
  const params = seatingParams(element)
  if (params == null) return null
  const base = seatBase(element, params, seatId)
  if (base == null) return null
  return { element, eye: seatEye(element, base) }
}

/**
 * Landing in a picked seat (session 3's *Sit in a seat…*), or null when its seating is gone or no
 * longer has the seat: the eye at the seat's seated eye, facing the stage, through a seat view's
 * lens — exactly what a saved `SEAT` row with no target of its own lands.
 */
export function resolveSeatViewpoint(
  ref: SeatViewpointRef,
  elements: readonly StageElementDto[],
): StageCameraLanding | null {
  const parsed = parseSeatViewpointRef(ref)
  if (parsed == null) return null
  const seat = seatOf(parsed.elementUuid, parsed.seatId, elements)
  if (seat == null) return null
  const position = toThreeVec(seat.eye)
  const angles = lookAngles(position, toThreeVec(DEFAULT_SEAT_TARGET))
  return { ref, camera: 'eye', pose: { position, ...angles, fov: clampFov(DEFAULT_SEAT_FOV_DEG) } }
}

/** A picked seat's name, as the picker and the caption say it: `Row F, seat 6`. */
export function seatViewpointName(ref: SeatViewpointRef): string {
  const parsed = parseSeatViewpointRef(ref)
  if (parsed == null) return 'Seat'
  const m = /^([A-Z])(\d+)$/.exec(parsed.seatId)
  return m == null ? `Seat ${parsed.seatId}` : `Row ${m[1]}, seat ${m[2]}`
}

/** The canvas caption's note for a picked seat, after its name. */
export function seatViewpointCaption(ref: SeatViewpointRef): string {
  const parsed = parseSeatViewpointRef(ref)
  return `seat ${parsed?.seatId ?? '?'} · unsaved · drag to look around, scroll to zoom`
}

/**
 * What landing on [row] means, or null when it cannot be landed — a seat whose seating is gone or
 * no longer has that seat, or an eye view missing a point. The picker draws such a row disabled.
 */
export function resolveSavedViewpoint(
  row: StageViewpointDto,
  elements: readonly StageElementDto[],
): StageCameraLanding | null {
  const ref = row.uuid as SavedViewpointRef
  const target = point(row.targetX, row.targetY, row.targetZ)
  if (row.kind === 'SEAT') {
    const seat = seatOf(row.seatElementUuid, row.seatId, elements)
    if (seat == null) return null
    const position = toThreeVec(seat.eye)
    const angles = lookAngles(position, toThreeVec(target ?? DEFAULT_SEAT_TARGET))
    return { ref, camera: 'eye', pose: { position, ...angles, fov: clampFov(row.fovDeg ?? DEFAULT_SEAT_FOV_DEG) } }
  }
  const eye = point(row.eyeX, row.eyeY, row.eyeZ)
  if (eye == null || target == null) return null
  if (row.kind === 'ORBIT') {
    return { ref, camera: 'orbit', pose: { position: toThreeVec(eye), target: toThreeVec(target) } }
  }
  const position = toThreeVec(eye)
  return {
    ref,
    camera: 'eye',
    pose: { position, ...lookAngles(position, toThreeVec(target)), fov: clampFov(row.fovDeg ?? EYE_DEFAULT_FOV_DEG) },
  }
}

/**
 * Any viewpoint in the vocabulary as the camera it draws through and the landing it takes, or null
 * when it cannot be drawn here: a saved view this project does not have, or a seat that is gone.
 * `render_view`'s render (stage-view plan session 4), which has every row in hand before it draws —
 * so unlike the Stage view it never falls back to a camera it last landed with.
 */
export function resolveViewpoint(
  viewpoint: StageViewpoint,
  rows: readonly StageViewpointDto[],
  elements: readonly StageElementDto[],
): { camera: StageCamera; landing: StageCameraLanding | null } | null {
  if (isStageCamera(viewpoint)) return { camera: viewpoint, landing: null }
  const landing = isSeatViewpointRef(viewpoint)
    ? resolveSeatViewpoint(viewpoint, elements)
    : (() => {
        const row = rows.find((r) => r.uuid === viewpoint)
        return row == null ? null : resolveSavedViewpoint(row, elements)
      })()
  return landing == null ? null : { camera: landing.camera, landing }
}

/** The picker's note for a row, after its name: `standing · 50°`, `seat F6`, `turntable`. */
export function savedViewNote(row: StageViewpointDto): string {
  if (row.kind === 'SEAT') return `seat ${row.seatId ?? '?'}`
  if (row.kind === 'ORBIT') return 'turntable'
  return `standing · ${Math.round(row.fovDeg ?? EYE_DEFAULT_FOV_DEG)}°`
}

/** The canvas caption's note for a landed row, after its name. */
export function savedViewCaption(row: StageViewpointDto): string {
  if (row.kind === 'ORBIT') return 'saved view · drag to orbit, right-drag to pan'
  const where = row.kind === 'SEAT' ? `seat ${row.seatId ?? '?'} · seated eye height` : 'standing'
  return `${where} · drag to look around, scroll to zoom`
}

const round = (n: number) => Math.round(n * 1000) / 1000

/**
 * *Save this view…*: the row a create sends for where this window's camera is now. The orbit
 * camera saves an `ORBIT` view; the eye saves an `EYE` view — or, while it is sitting in a seat
 * view, a `SEAT` view of the same seat with the head turned where it now is, so "Row F, looking
 * stage left" keeps following the seat if the seating moves. A seat picked but not yet saved (session
 * 3's *Sit in a seat…*) is handed in the same shape, and saves the same row.
 */
export function viewpointFromCamera(
  name: string,
  camera:
    | { kind: 'orbit'; pose: OrbitPose }
    | { kind: 'eye'; pose: EyePose; seat?: Pick<StageViewpointDto, 'kind' | 'seatElementUuid' | 'seatId'> | null },
): CreateStageViewpointRequest {
  if (camera.kind === 'orbit') {
    const eye = fromThreeVec(camera.pose.position)
    const target = fromThreeVec(camera.pose.target)
    return {
      name,
      kind: 'ORBIT',
      eyeX: round(eye.x), eyeY: round(eye.y), eyeZ: round(eye.z),
      targetX: round(target.x), targetY: round(target.y), targetZ: round(target.z),
    }
  }
  const target = fromThreeVec(eyeTarget(camera.pose))
  const aim = { targetX: round(target.x), targetY: round(target.y), targetZ: round(target.z) }
  const fovDeg = Math.round(clampFov(camera.pose.fov))
  const seat = camera.seat
  if (seat?.kind === 'SEAT' && seat.seatElementUuid != null && seat.seatId != null) {
    return { name, kind: 'SEAT', seatElementUuid: seat.seatElementUuid, seatId: seat.seatId, ...aim, fovDeg }
  }
  const eye = fromThreeVec(camera.pose.position)
  return { name, kind: 'EYE', eyeX: round(eye.x), eyeY: round(eye.y), eyeZ: round(eye.z), ...aim, fovDeg }
}
