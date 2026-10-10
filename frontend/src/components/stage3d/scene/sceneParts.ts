import { paintOf, type ScenePaint } from '../../../api/sceneImageApi'
import type { StageElementDto } from '../../../api/stageElementApi'
import type { SeatPoint } from '../../../lib/stageSeats'
import { LAMBERT_LOBES, type FinishLobes } from './lobes'
import type { PleatAnchor, PleatShape } from './pleat'

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
  /**
   * Soft goods: cloth [w] wide along x and [h] tall along z, pleated along x by [pleat] — measured
   * from its centre, or from its [anchor] edge — and seen from both sides.
   */
  | { shape: 'pleat'; w: number; h: number; pleat: PleatShape; anchor?: PleatAnchor }
  /**
   * Cloth that hangs flat (scrim plan D2): [w] wide along x and [h] tall along z in the plane
   * y = 0, seen from both sides — every fabric but velour, hung dead or flown.
   */
  | { shape: 'sheet'; w: number; h: number }

export type FinishPattern = 'PLAIN' | 'PANELS' | 'TILES' | 'BOARDS'

export interface PartFinish {
  colour: string
  pattern: FinishPattern
  /** Glows by itself (an exit sign) — drawn at its colour, not lit. */
  emissive: boolean
  /** How light leaves it ([finishLobes]): one of [FINISH_LOBES], so a finish compares by identity. */
  lobes: FinishLobes
  /**
   * The images painted on it (scrim plan D4), each a stored image's SHA-256: `front` on the
   * downstage face, `back` on the upstage one. A drape's and a flat's only (D14), and only with a
   * side set. Where on the image each part's faces land is the part's [ScenePart.uv].
   */
  paint?: ScenePaint
  /**
   * τ for a translucent cloth (scrim plan D6, `seeThrough.ts`): the share of light from behind that
   * lights its front, through both its paints. A muslin drape's only; absent, light behind a face
   * lights nothing on it.
   */
  translucent?: number
}

/**
 * Where a part's own x and z land on its element's images (scrim plan §3.5): x from −w/2 to w/2
 * runs [u0] to [u1] and z from −h/2 to h/2 runs [v0] to [v1], as the image is seen from downstage
 * (u = 0 its left edge, at stage right; v = 0 its bottom). The upstage face sees the image the
 * other way round, `1 − u`, so a back painting reads the right way from behind. A flat's pieces
 * each carry their share of the face, so an opening cuts the picture rather than squeezing it; a
 * drawn cloth's halves each carry their half of it, compressed as they gather.
 */
export interface PartUv {
  u0: number
  u1: number
  v0: number
  v1: number
}

/** The whole image over the whole part. */
export const FULL_UV: PartUv = { u0: 0, u1: 1, v0: 0, v1: 1 }

/**
 * A part that passes a share of the light that reaches it (scrim plan D7):
 *
 * - `angle` — a net (sharkstooth, bobbinet): `open(θ)` of thread share [r], θ from the cloth's
 *   normal, raised to [gather], the layers a drawn half is gathered into (its fullness, 1 for a
 *   cloth hanging open) — `scrimOpen.ts`.
 * - `mask` — a painted cloth or flat: the [image]'s alpha at the point crossed, through its 256 px
 *   mask (D5) — a hole below half opacity passes everything, the cloth passes nothing. [uv] is where
 *   the part's own x and z land on that image, as [ScenePart.uv] says for the paint, so a drawn
 *   half's mask is its own half of the image, compressed as it gathers.
 *
 * The mask's pixels are never in the part: they are looked up when a beam is cast or the colliders
 * are packed (`sceneMasks.ts`), so the builders stay pure. One not yet loaded, missing, over the
 * atlas's 32, or with no hole in it counts as solid.
 */
export type Transmit =
  | { kind: 'angle'; r: number; gather: number }
  | { kind: 'mask'; image: string; uv: PartUv }

/**
 * How a part meets light: `solid` stops a beam and casts a shadow, `none` is drawn and nothing
 * more (a proscenium's surround strip, proud of its wall), or a [Transmit] share.
 */
export type PartLight = 'solid' | 'none' | Transmit

export interface ScenePart {
  /** Stable within its element, for React keys and tests. */
  key: string
  geometry: PartGeometry
  /** The part's centre in the element's frame. */
  at: { x: number; y: number; z: number }
  finish: PartFinish
  /** How light meets it ([`beamReach.ts`](./beamReach.ts), [`occlusion.ts`](./occlusion.ts)). */
  light: PartLight
  /** Where its faces land on the element's paint, for a part whose finish carries [PartFinish.paint]. */
  uv?: PartUv
}

/** Whether a part is a collider at all — solid or transmitting; a `none` part is drawn only. */
export function partCollides(part: Pick<ScenePart, 'light'>): boolean {
  return part.light !== 'none'
}

/** The part's [Transmit], or null for a solid or a `none` one. */
export function partTransmit(part: Pick<ScenePart, 'light'>): Transmit | null {
  return typeof part.light === 'object' ? part.light : null
}

/**
 * The [Transmit] a painted part's alpha gives it (D5, D7): the front image's mask over [uv], or —
 * painted on its back only — the back's, seen from downstage the other way round (`1 − u`, as the
 * surface shader samples it). Null for an unpainted part.
 *
 * A cloth painted on **both** faces cuts light by its front's alpha alone: the surface discards
 * where either image is a hole (session 2), but one part has one mask, and a hole only the back
 * has is drawn and not lit through. A cut cloth painted on both sides cuts the same holes in both.
 */
export function paintTransmit(paint: ScenePaint | null | undefined, uv: PartUv): Transmit | null {
  if (paint?.front != null) return { kind: 'mask', image: paint.front, uv }
  if (paint?.back != null) return { kind: 'mask', image: paint.back, uv: { u0: 1 - uv.u0, u1: 1 - uv.u1, v0: uv.v0, v1: uv.v1 } }
  return null
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

/**
 * The finishes a stage is made of, as lobes (stage-light plan session 4, `lobes.ts`). Estimates,
 * judged in `profileHarness.ts`'s material scenes rather than measured:
 *
 * - `MATTE` — plaster, a ceiling, a cyc's cloth, a pros's black surround: rough matte, no shine.
 * - `PAINT` — a flat, a wall, a prop: eggshell paint, a little rough, a broad highlight.
 * - `VELOUR` — legs, borders, tabs, a backcloth, a seat's upholstery: rough, with a grazing sheen.
 * - `FLOOR` — the stage floor: a satin dance floor, the sharpest highlight here, so a lamp upstage
 *   reads on it from the house as a soft streak.
 * - `DECK` — a platform, a rostrum, a room's floor: a sealed or painted deck, a broader one.
 * - `NET` — a scrim's net (sharkstooth, bobbinet): matte, no sheen. Its own preset, so the
 *   see-through draw (scrim plan session 4) can give the threads their wrap without touching
 *   `MATTE`.
 * - `LAMBERT` — no lobes: the housings, which a fill lights, and a catch surface.
 */
// Estimate: judged by eye in `?profileHarness=rake`, `=cyc`, `=floor` and `=gloss`, not measured.
export const FINISH_LOBES = {
  LAMBERT: LAMBERT_LOBES,
  MATTE: { diffuseRoughness: 0.5, sheen: 0, sheenRoughness: 1, specular: 0, roughness: 1 },
  PAINT: { diffuseRoughness: 0.3, sheen: 0, sheenRoughness: 1, specular: 0.04, roughness: 0.7 },
  VELOUR: { diffuseRoughness: 0.5, sheen: 0.1, sheenRoughness: 0.3, specular: 0, roughness: 1 },
  FLOOR: { diffuseRoughness: 0, sheen: 0, sheenRoughness: 1, specular: 0.04, roughness: 0.35 },
  DECK: { diffuseRoughness: 0, sheen: 0, sheenRoughness: 1, specular: 0.04, roughness: 0.55 },
  NET: { diffuseRoughness: 0.5, sheen: 0, sheenRoughness: 1, specular: 0, roughness: 1 },
} as const satisfies Record<string, FinishLobes>

/** Which part of an element a finish is for: its body, or one the builder names. */
export type FinishPart = 'body' | 'floor' | 'ceiling' | 'surround' | 'rail' | 'seat' | 'frame'

/**
 * A finish's lobes by the element's [kind], a drape's [role] and [fabric], and the [part]. No
 * element carries its own (stage-light plan P2: an override would be a portable field), so this
 * table is the whole of it. A drape's fabric (scrim plan D1) outranks its role: canvas and muslin
 * are `MATTE`, the two nets `NET`, and only velour — no fabric — keeps the role's.
 */
export function finishLobes(
  kind: string,
  role: string | null,
  part: FinishPart = 'body',
  fabric: string | null = null,
): FinishLobes {
  switch (part) {
    case 'floor':
      return FINISH_LOBES.DECK
    case 'ceiling':
    case 'surround':
      return FINISH_LOBES.MATTE
    case 'rail':
    case 'frame':
      return FINISH_LOBES.PAINT
    case 'seat':
      return FINISH_LOBES.VELOUR
    case 'body':
      break
  }
  switch (kind) {
    case 'ROOM':
      return FINISH_LOBES.MATTE
    case 'DRAPE':
      switch (fabric) {
        case 'CANVAS':
        case 'MUSLIN':
          return FINISH_LOBES.MATTE
        case 'SHARKSTOOTH':
        case 'BOBBINET':
          return FINISH_LOBES.NET
      }
      return role === 'CYC' ? FINISH_LOBES.MATTE : FINISH_LOBES.VELOUR
    case 'PLATFORM':
      return FINISH_LOBES.DECK
    case 'SEATING':
      return FINISH_LOBES.VELOUR
    default:
      return FINISH_LOBES.PAINT
  }
}

export function isFinishPattern(value: unknown): value is FinishPattern {
  return value === 'PLAIN' || value === 'PANELS' || value === 'TILES' || value === 'BOARDS'
}

/**
 * The element's own finish for [part], with its kind's colour where it names none and its kind's,
 * role's and part's lobes ([finishLobes]).
 */
export function elementFinish(
  element: Pick<StageElementDto, 'kind' | 'finishColour' | 'finishPattern' | 'emissive' | 'params'>,
  part: FinishPart = 'body',
): PartFinish {
  const colour = element.finishColour != null && HEX.test(element.finishColour)
    ? element.finishColour
    : (DEFAULT_KIND_COLOUR[element.kind] ?? '#9aa5b1')
  const pattern = typeof element.finishPattern === 'string' ? element.finishPattern.toUpperCase() : null
  return {
    colour,
    pattern: isFinishPattern(pattern) ? pattern : 'PLAIN',
    emissive: element.emissive === true,
    lobes: finishLobes(element.kind, paramEnum(element, 'role'), part, element.kind === 'DRAPE' ? paramEnum(element, 'fabric') : null),
  }
}

/**
 * A sub-finish from `params` (a room's `floor` and `ceiling`) over [fallback]: its own colour and
 * pattern where it states them, the element's where it does not, and the fallback's lobes.
 */
export function paramsFinish(value: unknown, fallback: PartFinish): PartFinish {
  if (value == null || typeof value !== 'object') return fallback
  const f = value as Record<string, unknown>
  const colour = typeof f.colour === 'string' && HEX.test(f.colour) ? f.colour : fallback.colour
  const pattern = typeof f.pattern === 'string' ? f.pattern.toUpperCase() : null
  return { colour, pattern: isFinishPattern(pattern) ? pattern : fallback.pattern, emissive: false, lobes: fallback.lobes }
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

/** A drape's fabrics (scrim plan D1); absent is velour. */
export type DrapeFabric = 'VELOUR' | 'CANVAS' | 'MUSLIN' | 'SHARKSTOOTH' | 'BOBBINET'

/** A drape's `fabric`, velour where it names none or one this build does not know. */
export function paramFabric(element: Pick<StageElementDto, 'params'>): DrapeFabric {
  const v = paramEnum(element, 'fabric')
  return v === 'CANVAS' || v === 'MUSLIN' || v === 'SHARKSTOOTH' || v === 'BOBBINET' ? v : 'VELOUR'
}

/** An element's `paint` (scrim plan D4), well-formed hashes only; null when neither side is set. */
export function paramPaint(element: Pick<StageElementDto, 'params'>): ScenePaint | null {
  const paint = paintOf(element.params)
  return paint.front != null || paint.back != null ? paint : null
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
  light: PartLight = 'solid',
): ScenePart | null {
  if (!(w > 1e-4 && d > 1e-4 && h > 1e-4)) return null
  return { key, geometry: { shape: 'box', w, d, h }, at: { x, y, z: z0 + h / 2 }, finish, light }
}
