import type { StageElementDto } from '../../../api/stageElementApi'
import type { SeatPoint } from '../../../lib/stageSeats'

/**
 * What an element builder answers (stage-view plan session 3): the element as **parts** — boxes,
 * quads, cylinders and pleated cloth in the element's own frame — plus, for a seating element, its
 * seats. Plain numbers, no three.js, so each builder is a pure function a node test can pin, and one
 * renderer (`StageSceneElements.tsx`) turns parts into meshes for every kind.
 *
 * **The element's own frame is lighting metres about its origin**: x across (towards stage left),
 * y upstage, z up, before the element's yaw. The renderer places the group at the element's
 * origin — its base, or for a platform its top, or for a flown piece its trim ([elementBaseZ]) —
 * and turns it by the yaw, anticlockwise from above as everywhere on the desk.
 */

/** A face's outward normal, in the element's frame. `up` is +z, `upstage` +y, `left` +x. */
export type Facing = 'up' | 'down' | 'upstage' | 'downstage' | 'left' | 'right'

export type PartGeometry =
  /** A solid box: [w] along x, [d] along y, [h] along z. */
  | { shape: 'box'; w: number; d: number; h: number }
  /**
   * One quad, drawn from the [facing] side only — a room's walls face in, so from outside the near
   * walls vanish (the dollhouse). [w] × [h] are its two in-plane sizes: x × y for a floor or
   * ceiling, y × z for a side wall, x × z for a downstage or upstage wall.
   */
  | { shape: 'quad'; w: number; h: number; facing: Facing }
  /** A cylinder or cone along z, [h] tall; `rTop` < `rBottom` is a lamp shade. */
  | { shape: 'cylinder'; rTop: number; rBottom: number; h: number }
  /** A disc facing downstage (a moon, a clock face): [r] radius, [d] thick along y. */
  | { shape: 'disc'; r: number; d: number }
  /** Soft goods: cloth [w] wide along x and [h] tall along z, pleated along x, seen from both sides. */
  | { shape: 'pleat'; w: number; h: number }

export type FinishPattern = 'PLAIN' | 'PANELS' | 'TILES' | 'BOARDS'

export interface PartFinish {
  colour: string
  pattern: FinishPattern
  /** Glows by itself (an exit sign) — drawn at its colour, not lit. */
  emissive: boolean
}

export interface ScenePart {
  /** Stable within its element, for React keys and tests. */
  key: string
  geometry: PartGeometry
  /** The part's centre in the element's frame. */
  at: { x: number; y: number; z: number }
  finish: PartFinish
  /** Whether a beam stops at it ([`beamReach.ts`](./beamReach.ts)). A surround strip does not. */
  collides: boolean
}

export interface ElementBuild {
  parts: ScenePart[]
  /** A seating element's seats, from `lib/stageSeats.ts` — every other kind has none. */
  seats: SeatPoint[]
}

export const EMPTY_BUILD: ElementBuild = { parts: [], seats: [] }

// — reading an element ————————————————————————————————————————————————————————

function finite(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

/** The element's `states`, narrowed: each field only when it is the right type. */
export interface ElementStates {
  visible: boolean | null
  open: number | null
  trimM: number | null
}

export function elementStates(element: Pick<StageElementDto, 'params'>): ElementStates {
  const raw = element.params.states
  const s = raw != null && typeof raw === 'object' ? (raw as Record<string, unknown>) : {}
  return {
    visible: typeof s.visible === 'boolean' ? s.visible : null,
    open: finite(s.open) ? Math.min(1, Math.max(0, s.open)) : null,
    trimM: finite(s.trimM) ? s.trimM : null,
  }
}

/** Whether the element is drawn at all: not `hidden`, and not switched off by its `visible` state. */
export function isElementShown(element: Pick<StageElementDto, 'hidden' | 'params'>): boolean {
  return !element.hidden && elementStates(element).visible !== false
}

/** Whether a piece flies — an `OBJECT` with `flies`, or a `DRAPE` whose operation is `FLY`. */
export function elementFlies(element: Pick<StageElementDto, 'kind' | 'params'>): boolean {
  if (element.kind === 'OBJECT') return element.params.flies === true
  if (element.kind === 'DRAPE') return String(element.params.operation ?? '').toUpperCase() === 'FLY'
  return false
}

/**
 * Where the element's frame sits in Z: its base — except a platform's, which is its top surface
 * (the deck hangs below it, as a region's does), and a flown piece's, which is its `trimM` while one
 * is set.
 */
export function elementBaseZ(element: Pick<StageElementDto, 'kind' | 'params' | 'positionZ'>): number {
  if (elementFlies(element)) {
    const trim = elementStates(element).trimM
    if (trim != null) return trim
  }
  return element.positionZ
}

const DEFAULT_KIND_COLOUR: Record<string, string> = {
  ROOM: '#4a4540',
  PROSCENIUM: '#6a5d52',
  FLAT: '#c9b99a',
  DRAPE: '#3b1219',
  PLATFORM: '#4a443d',
  SEATING: '#6a2733',
  OBJECT: '#9aa5b1',
}

const HEX = /^#[0-9a-fA-F]{6}$/

export function isFinishPattern(value: unknown): value is FinishPattern {
  return value === 'PLAIN' || value === 'PANELS' || value === 'TILES' || value === 'BOARDS'
}

/** The element's own finish, with its kind's colour where it names none. */
export function elementFinish(
  element: Pick<StageElementDto, 'kind' | 'finishColour' | 'finishPattern' | 'emissive'>,
): PartFinish {
  const colour = element.finishColour != null && HEX.test(element.finishColour)
    ? element.finishColour
    : (DEFAULT_KIND_COLOUR[element.kind] ?? '#9aa5b1')
  const pattern = typeof element.finishPattern === 'string' ? element.finishPattern.toUpperCase() : null
  return { colour, pattern: isFinishPattern(pattern) ? pattern : 'PLAIN', emissive: element.emissive === true }
}

/**
 * A sub-finish from `params` (a room's `floor` and `ceiling`) over [fallback]: its own colour and
 * pattern where it states them, the element's where it does not.
 */
export function paramsFinish(value: unknown, fallback: PartFinish): PartFinish {
  if (value == null || typeof value !== 'object') return fallback
  const f = value as Record<string, unknown>
  const colour = typeof f.colour === 'string' && HEX.test(f.colour) ? f.colour : fallback.colour
  const pattern = typeof f.pattern === 'string' ? f.pattern.toUpperCase() : null
  return { colour, pattern: isFinishPattern(pattern) ? pattern : fallback.pattern, emissive: false }
}

/** A number from `params`, or [fallback] where it is missing or not a finite number. */
export function paramNumber(element: Pick<StageElementDto, 'params'>, key: string, fallback: number): number {
  const v = element.params[key]
  return finite(v) ? v : fallback
}

/** An upper-cased enumeration from `params`, or null. */
export function paramEnum(element: Pick<StageElementDto, 'params'>, key: string): string | null {
  const v = element.params[key]
  return typeof v === 'string' ? v.trim().toUpperCase() : null
}

/** A solid box part, bottom at [z0] — the builders stand things on their base more than they centre them. */
export function boxPart(
  key: string,
  x: number,
  y: number,
  z0: number,
  w: number,
  d: number,
  h: number,
  finish: PartFinish,
  collides = true,
): ScenePart | null {
  if (!(w > 1e-4 && d > 1e-4 && h > 1e-4)) return null
  return { key, geometry: { shape: 'box', w, d, h }, at: { x, y, z: z0 + h / 2 }, finish, collides }
}
