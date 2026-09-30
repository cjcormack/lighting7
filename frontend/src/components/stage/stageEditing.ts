// Shared, view-agnostic types for stage editing.
//
// These live here rather than in Stage3D so a module that only names a
// selection — the route, the editor panels, the picker — can import them
// without pulling in three.js / R3F: Stage3D imports `three` at module scope,
// so `import type { Selection } from '../stage3d/Stage3D'` would drag the whole
// 3D chunk in. Stage3D re-exports every type below, so existing import sites
// keep working unchanged.
//
// `GizmoMode` deliberately stays in Stage3D — it describes drei's
// TransformControls, which the orbit camera's editing uses and a section's
// does not.

export type Selection =
  | { kind: 'patch'; patchKey: string }
  | { kind: 'region'; uuid: string }
  | { kind: 'rigging'; uuid: string }
  /** A scene element (`stage_elements`, stage-view plan session 5): scenery and the venue. */
  | { kind: 'element'; uuid: string }
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

/** A scene element's move on a section: its origin, as the element's own fields. */
export interface ElementPositionUpdate {
  positionX: number
  positionY: number
  positionZ: number
}

/**
 * Where a placement click landed, in lighting coords — always complete.
 *
 * Every view can only determine two of the three axes from a click: the orbit
 * camera's ground-plane raycast and the plan section fix X and Y, the front
 * section fixes X and Z, the side section fixes Y and Z. Rather than push a partially-null
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
