// A fixture's drawn length, and where its long axis points — for the types whose length is set
// per install rather than by the model (a lightstrip, `FixtureTypeInfo.acceptsLength`).
//
// The backend owns which types take a length: it refuses a stored `lengthM` on any other type.
// This side still gates on the type rather than trusting the field alone, because an imported
// project is written as stored — so a length that reached a fixed-length patch some other way is
// ignored here rather than drawn as a lie about the rig.

import { Euler, MathUtils, Vector3 } from 'three'
import type { FixturePatch, PatchPlacement } from '../api/patchApi'
import type { FixtureTypeInfo } from '../store/fixtures'
import { fromThree } from './stageCoords'
import type { LightingPoint } from './stageProjection'

/**
 * The length to draw a fixture at, in metres: for a variable-length type, the placement's own
 * (a segment of a run laid in several sides), else the patch's, else the type's default; for any
 * other type, the type's own length. Null when the type is unknown or declares none.
 *
 * `placement` is for a caller holding the placement apart from its patch (`useProjectedPatches`). The 3D
 * view needs none: `patchAtPlacement` already lays the placement's length over the patch's.
 */
export function drawnLengthM(
  type: Pick<FixtureTypeInfo, 'acceptsLength' | 'lengthM'> | undefined,
  patch: Pick<FixturePatch, 'lengthM'>,
  placement?: Pick<PatchPlacement, 'lengthM'>,
): number | null {
  const typeLength = type?.lengthM ?? null
  if (!type?.acceptsLength) return typeLength
  return placement?.lengthM ?? patch.lengthM ?? typeLength
}

/**
 * Whether a type's length is the operator's to set. The same test `drawnLengthM` makes, for the
 * editors that decide whether to offer the field at all.
 */
export function acceptsLength(type: Pick<FixtureTypeInfo, 'acceptsLength'> | undefined): boolean {
  return type?.acceptsLength === true
}

const SCRATCH_EULER = new Euler()
const SCRATCH_AXIS = new Vector3()

/**
 * The unit vector of a fixture body's long axis (its local +X) in world lighting coords.
 *
 * Mirrors `FixtureModel` exactly, so a projected span (`useProjectedPatches`) and the 3D body agree: the body is drawn at its
 * world position under a YXZ Euler of `(basePitchDeg, baseYawDeg, baseRollDeg)` alone — a rigging's
 * pose places the body but does not turn it. Pitch turns about the body's own X, so it never moves
 * the long axis: yaw swings it round the stage, and roll is the only thing that tilts it off level
 * (roll 90 stands a strip on end).
 */
export function longAxisLighting(
  baseYawDeg: number | null | undefined,
  basePitchDeg: number | null | undefined,
  baseRollDeg?: number | null,
): LightingPoint {
  SCRATCH_EULER.set(
    MathUtils.degToRad(basePitchDeg ?? 0),
    MathUtils.degToRad(baseYawDeg ?? 0),
    MathUtils.degToRad(baseRollDeg ?? 0),
    'YXZ',
  )
  return fromThree(SCRATCH_AXIS.set(1, 0, 0).applyEuler(SCRATCH_EULER))
}

/**
 * The two ends of a body `lengthM` long, centred on `world` along its long axis — the span a
 * section's edit layer presses a variable-length fixture along (`useProjectedPatches`).
 */
export function bodyEndsLighting(
  world: LightingPoint,
  lengthM: number,
  baseYawDeg: number | null | undefined,
  basePitchDeg: number | null | undefined,
  baseRollDeg?: number | null,
): [LightingPoint, LightingPoint] {
  const axis = longAxisLighting(baseYawDeg, basePitchDeg, baseRollDeg)
  const half = lengthM / 2
  return [
    { x: world.x - axis.x * half, y: world.y - axis.y * half, z: world.z - axis.z * half },
    { x: world.x + axis.x * half, y: world.y + axis.y * half, z: world.z + axis.z * half },
  ]
}
