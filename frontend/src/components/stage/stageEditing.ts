// Shared, view-agnostic types for stage editing.
//
// These live here rather than in Stage3D so the 2D editor can import them
// without pulling in three.js / R3F: Stage3D imports `three` at module scope,
// so `import type { Selection } from '../stage3d/Stage3D'` would drag the whole
// 3D chunk into any module that names a selection. Stage3D re-exports every
// type below, so existing import sites keep working unchanged.
//
// `GizmoMode` deliberately stays in Stage3D — it describes drei's
// TransformControls and has no 2D analogue.

export type Selection =
  | { kind: 'patch'; patchKey: string }
  | { kind: 'region'; uuid: string }
  | { kind: 'rigging'; uuid: string }
  | null

export interface PatchPlacementUpdate {
  riggingUuid: string | null
  stageX: number | null
  stageY: number | null
  stageZ: number | null
  /** Present only for rotate-mode drags; null for translate drags so the
   *  caller doesn't overwrite the existing base orientation on a move. */
  baseYawDeg?: number | null
  basePitchDeg?: number | null
}

export interface RegionPositionUpdate {
  centerX: number | null
  centerY: number | null
  centerZ: number | null
  yawDeg: number | null
  widthM?: number | null
  depthM?: number | null
  heightM?: number | null
}

export interface RiggingPositionUpdate {
  positionX: number | null
  positionY: number | null
  positionZ: number | null
  yawDeg: number | null
  pitchDeg: number | null
  rollDeg: number | null
  lengthM?: number | null
}

/**
 * Where a placement click landed, in lighting coords — always complete.
 *
 * Every view can only determine two of the three axes from a click: the 3D
 * ground-plane raycast and the plan view fix X and Y, the front elevation fixes X
 * and Z, the side elevation fixes Y and Z. Rather than push a partially-null
 * point onto the caller and make it work out which axis is missing, each view
 * fills the axis it can't see from the `placementDefault` it was given. That way
 * "which axis did this view actually learn?" stays inside the view, where the
 * projection is already known.
 */
export interface PlacementPoint {
  x: number
  y: number
  z: number
}
