/**
 * The seats of a seating element (stage-view plan session 2), in lighting metres. The backend's
 * `SeatingParams.seat` in `models/stageScene.kt` is the same maths; `stageSeats.test.ts` and its
 * `StageSceneTest` pin the same seat, so the two cannot drift apart unnoticed.
 *
 * Row [firstRow] is nearest the stage, at the element's origin; each further row is a row pitch
 * further from the stage (local −Y) and a rake higher. Seat 1 is at the stage-right end (local −X).
 * The element's yaw turns the whole block anticlockwise about its origin, seen from above — the
 * desk's yaw everywhere (`docs/fixtures-engineering.md`).
 *
 * Three.js-free, so the picker can read it without the 3D chunk.
 */

import type { StageElementDto } from '../api/stageElementApi'

export interface SeatingParams {
  rows: number
  seatsPerRow: number
  rowPitchM: number
  seatPitchM: number
  firstRow: string
  rakeM: number
}

export interface LightingPoint3 {
  x: number
  y: number
  z: number
}

/** A seated eye, above the seat's base: the backend's `SEATED_EYE_HEIGHT_M`. */
export const SEATED_EYE_HEIGHT_M = 1.15

/**
 * Where a seat view with no target of its own looks: the stage's centre line, 2.4 m upstage of the
 * edge, 0.9 m above the deck. The backend's `DEFAULT_SEAT_TARGET`.
 */
export const DEFAULT_SEAT_TARGET: LightingPoint3 = { x: 0, y: 2.4, z: 0.9 }
export const DEFAULT_SEAT_FOV_DEG = 52

function finite(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

/**
 * A seating element's params, or null when they are not a seating's — an element of another kind,
 * or a document a later build shaped differently. Narrowed, not cast.
 */
export function seatingParams(element: Pick<StageElementDto, 'kind' | 'params'>): SeatingParams | null {
  if (element.kind !== 'SEATING') return null
  const p = element.params
  if (!finite(p.rows) || !finite(p.seatsPerRow) || !finite(p.rowPitchM) || !finite(p.seatPitchM)) return null
  const firstRow = typeof p.firstRow === 'string' && /^[A-Z]$/.test(p.firstRow) ? p.firstRow : 'A'
  return {
    rows: p.rows,
    seatsPerRow: p.seatsPerRow,
    rowPitchM: p.rowPitchM,
    seatPitchM: p.seatPitchM,
    firstRow,
    rakeM: finite(p.rakeM) ? p.rakeM : 0,
  }
}

type Pose = Pick<StageElementDto, 'positionX' | 'positionY' | 'positionZ' | 'yawDeg'>

/** A seat id's row letter and number (`F6`, `a12`), or null. */
export function parseSeatId(id: string): { row: string; number: number } | null {
  const m = /^([A-Za-z])([1-9][0-9]{0,2})$/.exec(id.trim())
  if (m == null) return null
  return { row: m[1].toUpperCase(), number: Number(m[2]) }
}

/** The seat's base, or null when this seating has no such seat. */
export function seatBase(pose: Pose, params: SeatingParams, id: string): LightingPoint3 | null {
  const parsed = parseSeatId(id)
  if (parsed == null) return null
  const r = parsed.row.charCodeAt(0) - params.firstRow.charCodeAt(0)
  if (r < 0 || r >= params.rows || parsed.number < 1 || parsed.number > params.seatsPerRow) return null
  const lx = (parsed.number - 1 - (params.seatsPerRow - 1) / 2) * params.seatPitchM
  const ly = -r * params.rowPitchM
  const yaw = (pose.yawDeg * Math.PI) / 180
  return {
    x: pose.positionX + lx * Math.cos(yaw) - ly * Math.sin(yaw),
    y: pose.positionY + lx * Math.sin(yaw) + ly * Math.cos(yaw),
    z: pose.positionZ + r * params.rakeM,
  }
}

/** A seated eye: above the seat, 5 cm towards its back. */
export function seatEye(pose: Pose, base: LightingPoint3): LightingPoint3 {
  const yaw = (pose.yawDeg * Math.PI) / 180
  return { x: base.x + 0.05 * Math.sin(yaw), y: base.y - 0.05 * Math.cos(yaw), z: base.z + SEATED_EYE_HEIGHT_M }
}

/**
 * The seating block's footprint in its own frame — width across the seats, depth from row A back —
 * with a seat's half-pitch round it, for drawing it as a box before session 3 draws seats. [riseM]
 * is the last row's height over row A: negative for a bank that steps down (the rake runs −1…1).
 */
export function seatingExtent(params: SeatingParams): { widthM: number; depthM: number; riseM: number } {
  return {
    widthM: params.seatsPerRow * params.seatPitchM,
    depthM: params.rows * params.rowPitchM,
    riseM: (params.rows - 1) * params.rakeM,
  }
}
