import type { StageElementDto } from '../../../../api/stageElementApi'
import {
  elementFinish,
  isFinishPattern,
  type ElementBuild,
  type PartFinish,
  type PartGeometry,
  type PartTexture,
  type PartTransform,
  type ScenePart,
} from '../sceneParts'

/**
 * A `MODEL` object — an `OBJECT` whose `shape` is `MODEL` — built from its `params.parts`
 * (lighting7 `models/sceneModel.kt`, which validates them): boxes, cylinders and cones, spheres,
 * extruded outlines and lathed profiles, each placed and turned in the model's own frame, finished
 * in its own colour (else the element's) and optionally textured with a scene image.
 *
 * **The model is fitted to the element's box**: its bounds ([modelBounds], the desk's sum, pinned
 * by `src/test/resources/stage/modelBounds.fixture.json`) are scaled to `widthM × depthM × heightM`,
 * the footprint centred on the origin and the lowest point on the base — as every other object
 * stands on its base about its origin. Each part carries the fit and its own turn as one
 * [PartTransform] about its origin, and `at` is where that origin lands.
 *
 * Read leniently: a part this build cannot read is left out rather than failing the model, since a
 * later desk may write shapes this one does not know.
 */

export type PartShape = 'BOX' | 'CYLINDER' | 'SPHERE' | 'EXTRUDE' | 'LATHE'

type Point = readonly [number, number]

/** One part as `params.parts` holds it, read ([readModelPart]). */
export interface ModelPart {
  key: string
  shape: PartShape
  x: number
  y: number
  z: number
  yawDeg: number
  pitchDeg: number
  rollDeg: number
  widthM: number
  depthM: number
  heightM: number
  topWidthM: number
  outline: readonly Point[]
  holes: readonly (readonly Point[])[]
  profile: readonly Point[]
  segments: number
  colour: string | null
  pattern: string | null
  emissive: boolean
  texture: PartTexture | null
  shadow: boolean
}

export interface ModelBounds {
  minX: number
  minY: number
  minZ: number
  maxX: number
  maxY: number
  maxZ: number
}

const HEX = /^#[0-9a-fA-F]{6}$/
const HASH = /^[0-9a-f]{64}$/
/** The desk's [MIN_MODEL_EXTENT]: a model flatter than this along an axis is not fitted. */
const MIN_EXTENT = 1e-4
/** A lathe's sides when its part names none, as the desk documents. */
export const DEFAULT_LATHE_SEGMENTS = 32

function num(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

function points(value: unknown, min: number): Point[] | null {
  if (!Array.isArray(value) || value.length < min) return null
  const out: Point[] = []
  for (const p of value) {
    if (!Array.isArray(p) || p.length !== 2 || typeof p[0] !== 'number' || typeof p[1] !== 'number') return null
    if (!Number.isFinite(p[0]) || !Number.isFinite(p[1])) return null
    out.push([p[0], p[1]])
  }
  return out
}

/** One stored part, or null where this build cannot draw it. */
export function readModelPart(raw: unknown, index: number): ModelPart | null {
  if (raw == null || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>
  const shape = typeof r.shape === 'string' ? r.shape.trim().toUpperCase() : ''
  if (shape !== 'BOX' && shape !== 'CYLINDER' && shape !== 'SPHERE' && shape !== 'EXTRUDE' && shape !== 'LATHE') return null
  const width = num(r.widthM, 0)
  const tex = r.texture != null && typeof r.texture === 'object' ? (r.texture as Record<string, unknown>) : null
  const image = typeof tex?.image === 'string' ? tex.image.trim().toLowerCase() : ''
  const texture: PartTexture | null =
    tex != null && HASH.test(image)
      ? { image, mode: String(tex.mode ?? '').toUpperCase() === 'TILE' ? 'TILE' : 'STRETCH', tileM: Math.max(0.01, num(tex.tileM, 1)) }
      : null
  const pattern = typeof r.pattern === 'string' ? r.pattern.trim().toUpperCase() : null
  const part: ModelPart = {
    key: `part${index}`,
    shape,
    x: num(r.x, 0),
    y: num(r.y, 0),
    z: num(r.z, 0),
    yawDeg: num(r.yawDeg, 0),
    pitchDeg: num(r.pitchDeg, 0),
    rollDeg: num(r.rollDeg, 0),
    widthM: width,
    depthM: num(r.depthM, shape === 'SPHERE' ? width : 0),
    heightM: num(r.heightM, shape === 'SPHERE' ? width : 0),
    topWidthM: num(r.topWidthM, width),
    outline: points(r.outline, 3) ?? [],
    holes: Array.isArray(r.holes) ? r.holes.map((h) => points(h, 3)).filter((h): h is Point[] => h != null) : [],
    profile: points(r.profile, 2) ?? [],
    segments: Math.round(Math.min(96, Math.max(3, num(r.segments, DEFAULT_LATHE_SEGMENTS)))),
    colour: typeof r.colour === 'string' && HEX.test(r.colour) ? r.colour.toLowerCase() : null,
    pattern: isFinishPattern(pattern) ? pattern : null,
    emissive: r.emissive === true,
    texture,
    shadow: r.shadow !== false,
  }
  switch (shape) {
    case 'BOX':
    case 'SPHERE':
      return part.widthM > 0 && part.depthM > 0 && part.heightM > 0 ? part : null
    case 'CYLINDER':
      return part.heightM > 0 && Math.max(part.widthM, part.topWidthM) > 0 && part.widthM >= 0 && part.topWidthM >= 0 ? part : null
    case 'EXTRUDE':
      return part.outline.length >= 3 && part.depthM > 0 ? part : null
    case 'LATHE':
      return part.profile.length >= 2 && part.profile.every((p) => p[0] >= 0) && part.profile.some((p) => p[0] > 0) ? part : null
  }
}

/** Every part [element] holds that this build can draw. */
export function modelParts(element: Pick<StageElementDto, 'params'>): ModelPart[] {
  const raw = element.params.parts
  if (!Array.isArray(raw)) return []
  return raw.map((p, i) => readModelPart(p, i)).filter((p): p is ModelPart => p != null)
}

/** The desk's `ModelPart.localBounds`: the part's own box before it is turned. */
export function partLocalBounds(p: ModelPart): ModelBounds {
  switch (p.shape) {
    case 'BOX':
    case 'SPHERE':
      return { minX: -p.widthM / 2, minY: -p.depthM / 2, minZ: -p.heightM / 2, maxX: p.widthM / 2, maxY: p.depthM / 2, maxZ: p.heightM / 2 }
    case 'CYLINDER': {
      const r = Math.max(p.widthM, p.topWidthM) / 2
      return { minX: -r, minY: -r, minZ: -p.heightM / 2, maxX: r, maxY: r, maxZ: p.heightM / 2 }
    }
    case 'EXTRUDE': {
      const xs = p.outline.map((q) => q[0])
      const zs = p.outline.map((q) => q[1])
      return { minX: Math.min(...xs), minY: -p.depthM / 2, minZ: Math.min(...zs), maxX: Math.max(...xs), maxY: p.depthM / 2, maxZ: Math.max(...zs) }
    }
    case 'LATHE': {
      const r = Math.max(...p.profile.map((q) => q[0]))
      const zs = p.profile.map((q) => q[1])
      return { minX: -r, minY: -r, minZ: Math.min(...zs), maxX: r, maxY: r, maxZ: Math.max(...zs) }
    }
  }
}

/**
 * The desk's `ModelPart.rotation`: `Rz(yaw) · Rx(pitch) · Ry(roll)`, row-major, in the model's frame
 * (x across, y upstage, z up) — rolled first, yawed last.
 */
export function partRotation(p: Pick<ModelPart, 'yawDeg' | 'pitchDeg' | 'rollDeg'>): PartTransform {
  const a = (p.yawDeg * Math.PI) / 180
  const b = (p.pitchDeg * Math.PI) / 180
  const c = (p.rollDeg * Math.PI) / 180
  const ca = Math.cos(a), sa = Math.sin(a), cb = Math.cos(b), sb = Math.sin(b), cc = Math.cos(c), sc = Math.sin(c)
  return [
    ca * cc - sa * sb * sc, -sa * cb, ca * sc + sa * sb * cc,
    sa * cc + ca * sb * sc, ca * cb, sa * sc - ca * sb * cc,
    -cb * sc, sb, cb * cc,
  ]
}

/** [m] applied to ([x], [y], [z]). */
export function applyTransform(m: PartTransform, x: number, y: number, z: number): [number, number, number] {
  return [m[0] * x + m[1] * y + m[2] * z, m[3] * x + m[4] * y + m[5] * z, m[6] * x + m[7] * y + m[8] * z]
}

/** The box round [box] mapped by [m] — its eight corners, each mapped, then offset by ([tx], [ty], [tz]). */
export function transformedBounds(box: ModelBounds, m: PartTransform, tx = 0, ty = 0, tz = 0): ModelBounds {
  const out = { minX: Infinity, minY: Infinity, minZ: Infinity, maxX: -Infinity, maxY: -Infinity, maxZ: -Infinity }
  for (const cx of [box.minX, box.maxX]) for (const cy of [box.minY, box.maxY]) for (const cz of [box.minZ, box.maxZ]) {
    const [x, y, z] = applyTransform(m, cx, cy, cz)
    out.minX = Math.min(out.minX, x + tx)
    out.minY = Math.min(out.minY, y + ty)
    out.minZ = Math.min(out.minZ, z + tz)
    out.maxX = Math.max(out.maxX, x + tx)
    out.maxY = Math.max(out.maxY, y + ty)
    out.maxZ = Math.max(out.maxZ, z + tz)
  }
  return out
}

/**
 * The desk's `modelBounds`: every part's own box, turned and moved to its place, unioned. Null for
 * a model with no part this build can draw.
 */
export function modelBounds(parts: readonly ModelPart[]): ModelBounds | null {
  if (parts.length === 0) return null
  const out = { minX: Infinity, minY: Infinity, minZ: Infinity, maxX: -Infinity, maxY: -Infinity, maxZ: -Infinity }
  for (const p of parts) {
    const b = transformedBounds(partLocalBounds(p), partRotation(p), p.x, p.y, p.z)
    out.minX = Math.min(out.minX, b.minX)
    out.minY = Math.min(out.minY, b.minY)
    out.minZ = Math.min(out.minZ, b.minZ)
    out.maxX = Math.max(out.maxX, b.maxX)
    out.maxY = Math.max(out.maxY, b.maxY)
    out.maxZ = Math.max(out.maxZ, b.maxZ)
  }
  return out
}

function partGeometryOf(p: ModelPart): PartGeometry {
  switch (p.shape) {
    case 'BOX':
      return { shape: 'box', w: p.widthM, d: p.depthM, h: p.heightM }
    case 'SPHERE':
      return { shape: 'sphere', w: p.widthM, d: p.depthM, h: p.heightM }
    case 'CYLINDER':
      return { shape: 'cylinder', rTop: p.topWidthM / 2, rBottom: p.widthM / 2, h: p.heightM }
    case 'EXTRUDE':
      return { shape: 'extrude', outline: p.outline, holes: p.holes, d: p.depthM }
    case 'LATHE':
      return { shape: 'lathe', profile: p.profile, segments: p.segments }
  }
}

function partFinish(p: ModelPart, body: PartFinish): PartFinish {
  return {
    colour: p.colour ?? body.colour,
    pattern: (p.pattern as PartFinish['pattern'] | null) ?? body.pattern,
    emissive: p.emissive || body.emissive,
    lobes: body.lobes,
    ...(p.texture != null && { texture: p.texture }),
  }
}

/**
 * [element]'s parts, fitted to its box. Nothing for a model with no drawable part, or one flat along
 * an axis — the desk refuses both, so neither is stored by it.
 */
export function buildModel(element: StageElementDto): ElementBuild {
  const w = element.widthM
  const d = element.depthM
  const h = element.heightM
  const parts = modelParts(element)
  const bounds = modelBounds(parts)
  if (bounds == null || !(w > 0 && d > 0 && h > 0)) return { parts: [], seats: [] }
  const bw = bounds.maxX - bounds.minX
  const bd = bounds.maxY - bounds.minY
  const bh = bounds.maxZ - bounds.minZ
  if (bw < MIN_EXTENT || bd < MIN_EXTENT || bh < MIN_EXTENT) return { parts: [], seats: [] }
  const sx = w / bw
  const sy = d / bd
  const sz = h / bh
  const body = elementFinish(element)
  const out: ScenePart[] = parts.map((p) => {
    const r = partRotation(p)
    // S · R: the part's own turn, then the fit's scale along the element's axes.
    const transform: PartTransform = [
      sx * r[0], sx * r[1], sx * r[2],
      sy * r[3], sy * r[4], sy * r[5],
      sz * r[6], sz * r[7], sz * r[8],
    ]
    return {
      key: p.key,
      geometry: partGeometryOf(p),
      at: { x: -w / 2 + sx * (p.x - bounds.minX), y: -d / 2 + sy * (p.y - bounds.minY), z: sz * (p.z - bounds.minZ) },
      finish: partFinish(p, body),
      light: p.shadow ? 'solid' : 'none',
      transform,
    }
  })
  return { parts: out, seats: [] }
}
