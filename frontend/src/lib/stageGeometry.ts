// Pure geometry for stage regions and riggings, in **lighting coords**
// (X = stage right, Y = upstage, Z = up; metres).
//
// Lifted out of the 3D edit handles so the 2D editor can share the same forward
// and inverse maths — the handles differ only in how they obtain a pointer
// position, not in what they derive from it. Everything here is a pure function
// of a DTO plus scalars, so it is directly unit-testable; the versions that
// lived inside RegionEditHandles and RiggingEndpointHandles were not.

import { MathUtils, Vector3 } from 'three'
import type { StageRegionDto } from '../api/stageRegionApi'
import type { RiggingDto } from '../api/riggingApi'
import { rigEuler, fromThree } from './stageCoords'
import type { ScreenPoint } from './stageProjection'

/** Fallback bar length for riggings with no `lengthM`. Re-exported by
 *  RiggingMeshes, which is where it used to live — this is the one definition. */
export const DEFAULT_RIGGING_LENGTH_M = 3

export interface LightingPoint {
  x: number
  y: number
  z: number
}

// — regions ————————————————————————————————————————————————————————

/** Rotate a point about the origin in the XY (floor) plane. */
export function rotateXY(x: number, y: number, yawRad: number): [number, number] {
  const c = Math.cos(yawRad)
  const s = Math.sin(yawRad)
  return [x * c - y * s, x * s + y * c]
}

/**
 * Corner index 0-3 around the floor of the rectangle (CCW from front-left):
 *   0 = (-w/2, -d/2)   1 = (+w/2, -d/2)
 *   3 = (-w/2, +d/2)   2 = (+w/2, +d/2)
 * 4-7 mirror that order at the top face.
 */
export function localCorners(w: number, d: number): Array<[number, number]> {
  return [
    [-w / 2, -d / 2],
    [+w / 2, -d / 2],
    [+w / 2, +d / 2],
    [-w / 2, +d / 2],
  ]
}

/**
 * Edge / side index 0-3, same orientation as corner index modulo 4:
 *   0 = front (–Y)   1 = right (+X)   2 = back (+Y)   3 = left (–X)
 */
export function localEdgeMidpoints(w: number, d: number): Array<[number, number]> {
  return [
    [0, -d / 2],
    [+w / 2, 0],
    [0, +d / 2],
    [-w / 2, 0],
  ]
}

export function localRotationOffsets(
  w: number,
  d: number,
  offset: number,
): Array<[number, number]> {
  return [
    [0, -d / 2 - offset],
    [+w / 2 + offset, 0],
    [0, +d / 2 + offset],
    [-w / 2 - offset, 0],
  ]
}

/**
 * The region's 8 world corners in lighting coords — indices 0-3 on the floor,
 * 4-7 directly above them. `centerZ` is the **floor** of the box, not its
 * midpoint (matching StageRegionMeshes), so the top face sits at
 * `centerZ + heightM`.
 */
export function worldCornersFor(region: StageRegionDto): Array<[number, number, number]> {
  const cx = region.centerX ?? 0
  const cy = region.centerY ?? 0
  const cz = region.centerZ ?? 0
  const h = region.heightM ?? 1
  const w = region.widthM ?? 1
  const d = region.depthM ?? 1
  const yaw = MathUtils.degToRad(region.yawDeg ?? 0)
  const xys = localCorners(w, d).map(([lx, ly]) => rotateXY(lx, ly, yaw))
  const floor: Array<[number, number, number]> = xys.map(([rx, ry]) => [cx + rx, cy + ry, cz])
  const top: Array<[number, number, number]> = xys.map(([rx, ry]) => [cx + rx, cy + ry, cz + h])
  return [...floor, ...top]
}

/** The corner diagonally opposite `idx`, at the same height level. */
export function pinnedIndexFor(idx: number): number {
  const base = idx < 4 ? 0 : 4
  return base + ((idx - base + 2) % 4)
}

/**
 * Derives new region pose from the dragged corner and the pinned (diagonally
 * opposite) corner. Yaw is preserved — width/depth are absolute projections of
 * the diagonal vector onto the local yawed axes.
 */
export function deriveFromDraggedCorner(
  draggedX: number,
  draggedY: number,
  pinnedX: number,
  pinnedY: number,
  yawDeg: number,
): { centerX: number; centerY: number; widthM: number; depthM: number } {
  const cx = (draggedX + pinnedX) / 2
  const cy = (draggedY + pinnedY) / 2
  const dx = draggedX - pinnedX
  const dy = draggedY - pinnedY
  const yaw = MathUtils.degToRad(yawDeg)
  const widthM = Math.abs(dx * Math.cos(yaw) + dy * Math.sin(yaw))
  const depthM = Math.abs(-dx * Math.sin(yaw) + dy * Math.cos(yaw))
  return { centerX: cx, centerY: cy, widthM, depthM }
}

// — riggings ————————————————————————————————————————————————————————

const SCRATCH_HALF = new Vector3()

/**
 * The rig's two endpoints in lighting coords. The bar spans the rig's local +X
 * axis with endpoints at (±L/2, 0, 0) — the mesh is centred on the rig origin
 * with no positional offset (see RiggingMeshes), which is why the local span is
 * symmetric rather than [0, L].
 *
 * The pitch/yaw/roll YXZ Euler is defined in R3F space, so `applyEuler`
 * produces an R3F vector that must come back through `fromThree` before being
 * offset from the rig's lighting-coord position. Endpoint 0 is the −X end,
 * endpoint 1 the +X end.
 */
export function worldEndpointsFor(rig: RiggingDto): [LightingPoint, LightingPoint] {
  const px = rig.positionX ?? 0
  const py = rig.positionY ?? 0
  const pz = rig.positionZ ?? 0
  const length = rig.lengthM ?? DEFAULT_RIGGING_LENGTH_M
  const half = fromThree(SCRATCH_HALF.set(length / 2, 0, 0).applyEuler(rigEuler(rig)))
  return [
    { x: px - half.x, y: py - half.y, z: pz - half.z },
    { x: px + half.x, y: py + half.y, z: pz + half.z },
  ]
}

/**
 * Where along a projected bar a pointer lands, as a bar-local X offset in
 * `[-lengthM/2, +lengthM/2]`.
 *
 * This is how a truss-mounted fixture is dragged. It is deliberately *not* an
 * in-plane free move that preserves the out-of-plane axis: on a yawed truss the
 * bar changes depth along its length, so holding world Y fixed while dragging in
 * the front elevation would walk the fixture off the bar it is bolted to. Being
 * on the truss is the stronger constraint, so the depth follows the bar.
 *
 * The clamp is the only thing preventing a fixture being persisted past the end
 * of its truss — the server's sole bound on a stage coordinate is ±500 m, so an
 * unclamped value is accepted and then drawn floating in mid-air with no error.
 *
 * `a` is the bar's −X end and `b` its +X end, both already projected. Callers
 * must reject the degenerate case (a bar pointing at the viewer projects to a
 * point) before calling: with `a == b` there is no direction to slide along.
 */
export function localXAlongBar(
  pointer: ScreenPoint,
  a: ScreenPoint,
  b: ScreenPoint,
  lengthM: number,
): number {
  const half = lengthM / 2
  const dh = b.h - a.h
  const dv = b.v - a.v
  const lenSq = dh * dh + dv * dv
  if (lenSq === 0) return 0
  const t = ((pointer.h - a.h) * dh + (pointer.v - a.v) * dv) / lenSq
  const clamped = Math.max(0, Math.min(1, t))
  return -half + clamped * lengthM
}

/**
 * Inverse of the forward kinematics R_y(yaw)·R_z(roll)·(L/2, 0, 0) with
 * pitch = 0, expressed in lighting space (three.js Euler 'YXZ' is intrinsic, so
 * the matrix is R_y · R_x · R_z applied to the column vector). Derivation:
 *   dx = L·cos(roll)·cos(yaw)
 *   dy = L·cos(roll)·sin(yaw)
 *   dz = L·sin(roll)
 * giving yaw = atan2(dy, dx) and roll = atan2(dz, hypot(dx, dy)). pitchDeg is
 * forced to 0 — it's a twist along the bar's own axis with no value derivable
 * from endpoint positions alone.
 *
 * Endpoints must be passed in canonical (A = index 0, B = index 1) order so the
 * (dx, dy, dz) vector points along the bar's +X (forward) direction.
 */
export function deriveFromEndpoints(
  a: LightingPoint,
  b: LightingPoint,
): {
  positionX: number
  positionY: number
  positionZ: number
  yawDeg: number
  pitchDeg: number
  rollDeg: number
  lengthM: number
} {
  const dx = b.x - a.x
  const dy = b.y - a.y
  const dz = b.z - a.z
  return {
    positionX: (a.x + b.x) / 2,
    positionY: (a.y + b.y) / 2,
    positionZ: (a.z + b.z) / 2,
    lengthM: Math.hypot(dx, dy, dz),
    yawDeg: MathUtils.radToDeg(Math.atan2(dy, dx)),
    pitchDeg: 0,
    rollDeg: MathUtils.radToDeg(Math.atan2(dz, Math.hypot(dx, dy))),
  }
}
