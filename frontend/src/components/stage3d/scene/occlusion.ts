import { coneReachesSphere } from '../beamLobes'
import { coneReachesBox } from './coneBox'
import { MIN_REACH_M, type Collider } from './beamReach'
import { LIGHT_TEXELS } from './lightTable'
import { MASK_HOLE_BELOW, MASK_SIZE, sceneMasks, maskHole } from './sceneMasks'
import { scrimShare, SCRIM_OPEN_GLSL } from './scrimOpen'

/**
 * **Box occlusion**: how much of a light reaches a surface fragment — the product of the shares of
 * the colliders in that light's cone that the segment from the fragment to the lamp crosses, 0 at
 * the first solid one. Analytic, against the oriented boxes beam reach casts at (`beamReach.ts`), so
 * a flat shadows the wall behind it with no shadow map, a gauze lets a flat front light through
 * (scrim plan D3, D8) and a cut cloth's shadow follows its holes (D5).
 *
 * Two textures beside the light table, which stays at six texels, and the mask atlas:
 *
 * - **The colliders**, one row each:
 *
 *   | texel | x | y | z | w |
 *   |---|---|---|---|---|
 *   | 0 | centre x | centre y | centre z | the yaw's cosine, folded so its sine is ≥ 0 |
 *   | 1 | half-extent x | half-extent y | half-extent z | skin ([colliderSkin]) |
 *   | 2 | kind | r, or the atlas layer | gather, or the mask's u0..u1 | 0, or its v0..v1 |
 *
 *   A box turned by π is the same box, so the shader takes the sine as `√(1 − cos²)` rather than
 *   paying a `cos` and a `sin` per fragment. Texel 2 is what the box does to light ([COLLIDER_SOLID],
 *   [COLLIDER_ANGLE], [COLLIDER_MASK]): a net's thread share and gathered layers; a painted cloth's
 *   atlas layer and where its local x and y land on the image, each pair packed in one float
 *   ([packUnitPair]). The plan's texel was `(kind, r or layer, gather, 0)`; a mask also needs its uv
 *   rect, a drawn half's being half the image, and has no gather (its uv is already compressed), so
 *   it takes the last two. A mask with no layer (`sceneMasks.ts`: loading, missing, no hole, or over
 *   the atlas's 32) is packed solid.
 * - **Each light's list**, one row per packed light row: the count, then one texel per collider
 *   ([packEntry]) — its bounding sphere's direction and angular size from the apex, its index and
 *   the distance to the sphere's near side. The sphere lets a fragment that cannot be behind the box
 *   skip the box test on the fetch it has already paid for. [cullLightColliders] fills it; a light
 *   whose cone reaches more than [MAX_LIGHT_COLLIDERS] overflows ([LIST_OVERFLOW]) and falls back to
 *   its landing planes (`landing.ts`) — **multiplied likewise** by the transmitting colliders in its
 *   cone, which its row still lists, counted as `LIST_OVERFLOW − n`: the planes stand for the solid
 *   boxes (beam reach skips a net, so they lie at the next solid surface past it), the list for what
 *   passes a share.
 * - **The mask atlas** (`maskAtlas.ts`), 256 × 256 × 32 bytes, sampled with `texelFetch` at the
 *   middle of a segment's crossing of a mask's box: a byte below half is a hole.
 *
 * The test ([segmentTransmit], [OCCLUSION_GLSL]) casts from the fragment towards the lamp, stopping
 * short of the aperture. A box the fragment sits inside — a pleated cloth in its own box, a wall's
 * face on its slab — passes it while the fragment lies within the box's skin of the face the segment
 * leaves by: a drape's troughs stay lit, a cloth does not shadow itself, the floor under a deck
 * stays dark.
 */

/** RGBA texels a collider takes in [OCCLUSION_GLSL]'s `uColliders`. */
export const COLLIDER_TEXELS = 3
/** Texel 2's kinds: a box that stops light, a net that passes `open(θ)^gather`, a cloth cut by its mask. */
export const COLLIDER_SOLID = 0
export const COLLIDER_ANGLE = 1
export const COLLIDER_MASK = 2
/** A unit pair's quantisation: 12 bits each, 24 together, exact in a float32. */
const UNIT_STEPS = 4095
const UNIT_BASE = 4096
/** The collider texture's rows: past any venue the view draws. A collider past it shadows nothing. */
export const MAX_COLLIDERS = 1024
/**
 * The most colliders one light's list holds — the list texture's width. The View menu's *Box
 * shadows* sets each browser's cap at or below it (`sceneView.ts`); a light that reaches more falls
 * back to its landing planes.
 */
export const MAX_LIGHT_COLLIDERS = 64
/** Each light list row: the count, then one texel an entry. */
export const LIST_TEXELS = 1 + MAX_LIGHT_COLLIDERS
/** An entry's index and near distance share a float: the index below this, centimetres above. */
export const ENTRY_INDEX_BASE = MAX_COLLIDERS
/** The furthest near distance an entry holds, in centimetres: 24 bits with the index, exact in a float32. */
const ENTRY_NEAR_MAX_CM = Math.floor((2 ** 24 - 1) / ENTRY_INDEX_BASE) - 1
/**
 * Taken off an entry's cone cosine and its near distance, so float32 rounding in the shader never
 * skips a box the fragment is behind.
 */
const ENTRY_COS_MARGIN = 1e-4
const ENTRY_NEAR_MARGIN_M = 0.01
/** An entry's cone cosine when the apex is inside the sphere: every direction is in it. */
export const ENTRY_ALL_DIRECTIONS = -2
/**
 * A light whose cone reached more colliders than [MAX_LIGHT_COLLIDERS]: its landing planes decide
 * the solid ones. A row reading `LIST_OVERFLOW − n` lists the n transmitting ones after its count.
 */
export const LIST_OVERFLOW = -1
/**
 * How near the segment's start a box may begin and still count as one the fragment sits on: a
 * millimetre, float noise on a coincident face. Further out, a box the segment enters blocks it.
 */
export const OCCLUSION_START_EPS_M = 0.001

/**
 * How far behind the face the segment leaves a box by a fragment inside it may lie and still be lit:
 * the larger of [Collider.skin] and [Collider.capSkin]. One number for all six faces — a cylinder's
 * point near its top, lit steeply, leaves its square box by the top, and is on the cylinder all the
 * same.
 */
export function colliderSkin(c: Collider): number {
  return Math.max(c.skin, c.capSkin)
}

/**
 * The colliders, packed: [data] is the texture's backing array, [spheres] each one's bounding sphere
 * (centre and radius, for the cull), [count] how many were packed — at most [MAX_COLLIDERS] — and
 * [dropped] how many were not.
 */
export interface ColliderSet {
  data: Float32Array
  spheres: Float64Array
  count: number
  dropped: number
}

export function makeColliderSet(capacity = MAX_COLLIDERS): ColliderSet {
  return { data: new Float32Array(capacity * COLLIDER_TEXELS * 4), spheres: new Float64Array(capacity * 4), count: 0, dropped: 0 }
}

/** Two numbers in 0..1 as one float: 12 bits each, [a] high, exact in a float32. */
export function packUnitPair(a: number, b: number): number {
  const q = (x: number) => Math.round(Math.min(1, Math.max(0, x)) * UNIT_STEPS)
  return q(a) * UNIT_BASE + q(b)
}

/** [packUnitPair]'s two numbers back, as the shader decodes them. */
export function unpackUnitPair(p: number): [number, number] {
  const hi = Math.floor(p / UNIT_BASE)
  return [hi / UNIT_STEPS, (p - hi * UNIT_BASE) / UNIT_STEPS]
}

/** Where a collider's mask lives in the atlas: −1 for none, which packs the collider solid. */
export interface MaskLayers {
  layerOf(hash: string): number
}

/**
 * Pack [colliders] into [set] (its rows past the count are left as they were: nothing reads them),
 * a mask's layer read from [masks] as it packs — so a mask that lands asks for a pack.
 */
export function packColliders(colliders: readonly Collider[], set: ColliderSet, masks: MaskLayers = sceneMasks): void {
  const capacity = set.data.length / (COLLIDER_TEXELS * 4)
  const n = Math.min(colliders.length, capacity)
  const d = set.data
  const s = set.spheres
  for (let i = 0; i < n; i++) {
    const c = colliders[i]
    const o = i * COLLIDER_TEXELS * 4
    // Fold the turn into [0, π]: the same box, a non-negative sine — and its local x the other way
    // round, so a mask's u runs the other way along it.
    const flip = c.sin < 0 || (c.sin === 0 && c.cos < 0)
    d[o] = c.cx
    d[o + 1] = c.cy
    d[o + 2] = c.cz
    d[o + 3] = flip ? -c.cos : c.cos
    d[o + 4] = c.hx
    d[o + 5] = c.hy
    d[o + 6] = c.hz
    d[o + 7] = colliderSkin(c)
    d[o + 8] = COLLIDER_SOLID
    d[o + 9] = 0
    d[o + 10] = 0
    d[o + 11] = 0
    const t = c.transmit
    if (t?.kind === 'angle') {
      d[o + 8] = COLLIDER_ANGLE
      d[o + 9] = t.r
      d[o + 10] = Math.max(1, t.gather)
    } else if (t?.kind === 'mask') {
      const layer = masks.layerOf(t.image)
      if (layer >= 0) {
        d[o + 8] = COLLIDER_MASK
        d[o + 9] = layer
        d[o + 10] = flip ? packUnitPair(t.uv.u1, t.uv.u0) : packUnitPair(t.uv.u0, t.uv.u1)
        d[o + 11] = packUnitPair(t.uv.v0, t.uv.v1)
      }
    }
    s[i * 4] = c.cx
    s[i * 4 + 1] = c.cy
    s[i * 4 + 2] = c.cz
    s[i * 4 + 3] = Math.hypot(c.hx, c.hy, c.hz)
  }
  set.count = n
  set.dropped = colliders.length - n
}

/** One packed collider, as the shader reads it back. */
export interface PackedCollider {
  cx: number
  cy: number
  cz: number
  hx: number
  hy: number
  hz: number
  cos: number
  sin: number
  skin: number
  /** Texel 2: [COLLIDER_SOLID], [COLLIDER_ANGLE] or [COLLIDER_MASK], then its three numbers. */
  kind: number
  k1: number
  k2: number
  k3: number
}

/** A packed collider with every field zero: what a caller hands [unpackCollider] to fill. */
export function emptyPackedCollider(): PackedCollider {
  return { cx: 0, cy: 0, cz: 0, hx: 0, hy: 0, hz: 0, cos: 1, sin: 0, skin: 0, kind: COLLIDER_SOLID, k1: 0, k2: 0, k3: 0 }
}

/** Collider [i] of [data] as [OCCLUSION_GLSL] unpacks it: the sine from the folded cosine. */
export function unpackCollider(data: Float32Array, i: number, out: PackedCollider): PackedCollider {
  const o = i * COLLIDER_TEXELS * 4
  out.cx = data[o]
  out.cy = data[o + 1]
  out.cz = data[o + 2]
  out.cos = data[o + 3]
  out.sin = Math.fround(Math.sqrt(Math.max(0, Math.fround(1 - Math.fround(out.cos * out.cos)))))
  out.hx = data[o + 4]
  out.hy = data[o + 5]
  out.hz = data[o + 6]
  out.skin = data[o + 7]
  out.kind = data[o + 8]
  out.k1 = data[o + 9]
  out.k2 = data[o + 10]
  out.k3 = data[o + 11]
  return out
}

/** Whether packed collider [i] of [data] passes a share of light rather than stopping it. */
function transmits(data: Float32Array, i: number): boolean {
  return data[i * COLLIDER_TEXELS * 4 + 8] !== COLLIDER_SOLID
}

const LIGHT_FLOATS = LIGHT_TEXELS * 4

/**
 * Each of the [lightCount] packed light rows of [lights] (`LightTable.pack`'s layout), its colliders:
 * every collider of [set] whose bounding sphere the light's cone reaches within [throwM] of its
 * aperture, written as row `k` of [out] — the count, then the entries — or [LIST_OVERFLOW] past
 * [cap] (itself at most [MAX_LIGHT_COLLIDERS]; 0 sends every light to its planes). A collider the aperture sits inside is left out, as
 * beam reach skips a box the ray starts inside: a head mounted in a deck's box is not blocked by it.
 * While the scene holds more colliders than [set] could pack, every light overflows: a list of the
 * packed ones alone would leave out shadows the planes still give.
 *
 * Conservative, as [coneReachesSphere] (`beamLobes.ts`) is for the haze's regions: a box the cone
 * merely might reach is kept, and the shader's test decides. A big box is tried in pieces
 * ([boxReachesCone]), or every wall of a room would be in every list.
 */
export function cullLightColliders(
  lights: Float32Array,
  lightCount: number,
  set: ColliderSet,
  throwM: number,
  out: Float32Array,
  cap = MAX_LIGHT_COLLIDERS,
): void {
  const limit = Math.min(cap, MAX_LIGHT_COLLIDERS)
  if (limit <= 0) {
    // Box shadows off: every light on its planes, and nothing to cull.
    for (let k = 0; k < lightCount; k++) out[k * LIST_TEXELS * 4] = LIST_OVERFLOW
    return
  }
  const s = set.spheres
  for (let k = 0; k < lightCount; k++) {
    const o = k * LIGHT_FLOATS
    const ax = lights[o]
    const ay = lights[o + 1]
    const az = lights[o + 2]
    const dx = lights[o + 4]
    const dy = lights[o + 5]
    const dz = lights[o + 6]
    const cosCone = Math.min(1, Math.max(-1, lights[o + 7]))
    const sinCone = Math.sqrt(Math.max(0, 1 - cosCone * cosCone))
    const near = lights[o + 20]
    const px = ax + dx * near
    const py = ay + dy * near
    const pz = az + dz * near
    const length = near + throwM
    const row = k * LIST_TEXELS * 4
    let n = 0
    let overflow = false
    for (let i = 0; i < set.count; i++) {
      if (!boxReachesCone(set, i, ax, ay, az, dx, dy, dz, length, cosCone, sinCone)) continue
      if (containsPoint(set.data, i, px, py, pz)) continue
      if (n >= limit) {
        overflow = true
        break
      }
      writeEntry(out, row + 4 + n * 4, i, ax, ay, az, s)
      n++
    }
    if (!overflow && set.dropped > 0) overflow = true
    if (!overflow) {
      out[row] = n
      continue
    }
    // Over: the planes stand for the solid boxes, and the transmitting ones in the cone are listed
    // again on their own, as many as fit, so the light still passes them by their shares.
    let t = 0
    for (let i = 0; i < set.count && t < limit; i++) {
      if (!transmits(set.data, i)) continue
      if (!boxReachesCone(set, i, ax, ay, az, dx, dy, dz, length, cosCone, sinCone)) continue
      if (containsPoint(set.data, i, px, py, pz)) continue
      writeEntry(out, row + 4 + t * 4, i, ax, ay, az, s)
      t++
    }
    out[row] = LIST_OVERFLOW - t
  }
}

/** How many entries a list row's count says follow it: the transmitting ones of an overflowed row. */
export function listEntryCount(count: number): number {
  return count >= 0 ? count : LIST_OVERFLOW - count
}

/**
 * How many floats of light row [k]'s list are in use — the count's texel and its entries — so the
 * upload can stop there: a row is 516 floats long and a light's list is usually a few entries.
 */
export function listRowFloats(lists: Float32Array, k: number): number {
  return 4 * (1 + listEntryCount(lists[k * LIST_TEXELS * 4]))
}

/** List entry [i]'s texel at [o]: its sphere's cone from the apex, its index and its near side. */
function writeEntry(out: Float32Array, o: number, i: number, ax: number, ay: number, az: number, spheres: Float64Array): void {
  const vx = spheres[i * 4] - ax
  const vy = spheres[i * 4 + 1] - ay
  const vz = spheres[i * 4 + 2] - az
  const r = spheres[i * 4 + 3]
  const d = Math.hypot(vx, vy, vz)
  if (d <= r) {
    packEntry(0, 0, 1, ENTRY_ALL_DIRECTIONS, i, 0, out, o)
  } else {
    const sin = r / d
    packEntry(vx / d, vy / d, vz / d, Math.sqrt(1 - sin * sin) - ENTRY_COS_MARGIN, i, d - r - ENTRY_NEAR_MARGIN_M, out, o)
  }
}

/**
 * One entry as its texel: the unit [dx, dy, dz] octahedrally encoded (`x, y`), [cos] (`z`), and
 * [index] with [nearM] floored to the centimetre (`w`).
 */
export function packEntry(
  dx: number, dy: number, dz: number,
  cos: number, index: number, nearM: number,
  out: { [i: number]: number }, o: number,
): void {
  const l1 = Math.abs(dx) + Math.abs(dy) + Math.abs(dz)
  let ex = dx / l1
  let ey = dy / l1
  if (dz < 0) {
    const fx = (1 - Math.abs(ey)) * (ex >= 0 ? 1 : -1)
    const fy = (1 - Math.abs(ex)) * (ey >= 0 ? 1 : -1)
    ex = fx
    ey = fy
  }
  const nearCm = Math.min(ENTRY_NEAR_MAX_CM, Math.max(0, Math.floor(nearM * 100)))
  out[o] = ex
  out[o + 1] = ey
  out[o + 2] = cos
  out[o + 3] = index + ENTRY_INDEX_BASE * nearCm
}

/** An entry's texel back, as the shader decodes it: the unit direction, the cosine, the index, the near distance. */
export function unpackEntry(list: ArrayLike<number>, o: number): { dx: number; dy: number; dz: number; cos: number; index: number; nearM: number } {
  const ex = list[o]
  const ey = list[o + 1]
  let x = ex
  let y = ey
  const z = 1 - Math.abs(ex) - Math.abs(ey)
  if (z < 0) {
    x = (1 - Math.abs(ey)) * (ex >= 0 ? 1 : -1)
    y = (1 - Math.abs(ex)) * (ey >= 0 ? 1 : -1)
  }
  const len = Math.hypot(x, y, z)
  const q = Math.floor(list[o + 3] / ENTRY_INDEX_BASE)
  return { dx: x / len, dy: y / len, dz: z / len, cos: list[o + 2], index: list[o + 3] - q * ENTRY_INDEX_BASE, nearM: q / 100 }
}

/**
 * Whether a fragment [dist] from the apex along the unit [lx, ly, lz] may be behind the entry at
 * [o] of a list row (the twin of the shader's skip): inside its sphere's cone, and no nearer than it.
 */
export function entryMayShadow(list: ArrayLike<number>, o: number, lx: number, ly: number, lz: number, dist: number): boolean {
  const e = unpackEntry(list, o)
  if (lx * e.dx + ly * e.dy + lz * e.dz < e.cos) return false
  return dist >= e.nearM
}

/**
 * Whether the cone from the apex ([ax], [ay], [az]) along ([dx], [dy], [dz]) reaches collider [i]
 * of [set] — conservatively, as `coneReachesSphere` is: its bounding sphere first, on the cull's
 * own copy, then the box itself in pieces (`coneBox.ts`).
 */
function boxReachesCone(
  set: ColliderSet, i: number,
  ax: number, ay: number, az: number,
  dx: number, dy: number, dz: number,
  length: number, cosCone: number, sinCone: number,
): boolean {
  const s = set.spheres
  APEX.x = ax
  APEX.y = ay
  APEX.z = az
  AXIS.x = dx
  AXIS.y = dy
  AXIS.z = dz
  CENTRE.x = s[i * 4]
  CENTRE.y = s[i * 4 + 1]
  CENTRE.z = s[i * 4 + 2]
  if (!coneReachesSphere(APEX, AXIS, length, cosCone, sinCone, CENTRE, s[i * 4 + 3])) return false
  const b = unpackCollider(set.data, i, SCRATCH)
  return coneReachesBox(ax, ay, az, dx, dy, dz, length, cosCone, sinCone, b.cx, b.cy, b.cz, b.cos, b.sin, b.hx, b.hy, b.hz)
}

const APEX = { x: 0, y: 0, z: 0 }
const AXIS = { x: 0, y: 0, z: 0 }
const CENTRE = { x: 0, y: 0, z: 0 }

const SCRATCH: PackedCollider = emptyPackedCollider()

function containsPoint(data: Float32Array, i: number, x: number, y: number, z: number): boolean {
  const b = unpackCollider(data, i, SCRATCH)
  const rx = x - b.cx
  const rz = z - b.cz
  return (
    Math.abs(b.cos * rx - b.sin * rz) <= b.hx &&
    Math.abs(y - b.cy) <= b.hy &&
    Math.abs(b.sin * rx + b.cos * rz) <= b.hz
  )
}

/** A direction component this small is taken as this, keeping its sign: no infinities in the slab test. */
const MIN_DIR = 1e-8

/** Where a segment lies in a box's own frame: its start and its direction, turned by −yaw ([segmentCrossing]). */
export interface BoxFrame {
  lox: number
  loy: number
  loz: number
  ldx: number
  ldy: number
  ldz: number
}

/** A [BoxFrame] with every field zero: what a caller hands [segmentCrossing] to fill. */
export function emptyBoxFrame(): BoxFrame {
  return { lox: 0, loy: 0, loz: 0, ldx: 0, ldy: 0, ldz: 0 }
}

/**
 * How the segment from [px, py, pz] along the unit [dx, dy, dz] for [tMax] metres meets packed
 * collider [b]: the twin of [TRANSMIT_GLSL]'s `segmentCrossing`, step for step. −1 where it passes
 * the box — misses it, ends inside it, or starts inside it within its skin of the face it leaves by
 * (a cloth does not shadow itself); otherwise how far along it the middle of its crossing lies, with
 * the segment in the box's frame written into [frame].
 */
export function segmentCrossing(
  px: number, py: number, pz: number,
  dx: number, dy: number, dz: number,
  tMax: number,
  b: PackedCollider,
  frame: BoxFrame,
): number {
  const rx = px - b.cx
  const ry = py - b.cy
  const rz = pz - b.cz
  const lox = b.cos * rx - b.sin * rz
  const loy = ry
  const loz = b.sin * rx + b.cos * rz
  const ldx = nonZero(b.cos * dx - b.sin * dz)
  const ldy = nonZero(dy)
  const ldz = nonZero(b.sin * dx + b.cos * dz)
  const t1x = (-b.hx - lox) / ldx
  const t2x = (b.hx - lox) / ldx
  const t1y = (-b.hy - loy) / ldy
  const t2y = (b.hy - loy) / ldy
  const t1z = (-b.hz - loz) / ldz
  const t2z = (b.hz - loz) / ldz
  const farX = Math.max(t1x, t2x)
  const farY = Math.max(t1y, t2y)
  const farZ = Math.max(t1z, t2z)
  const tNear = Math.max(Math.min(t1x, t2x), Math.min(t1y, t2y), Math.min(t1z, t2z))
  const tFar = Math.min(farX, farY, farZ)
  if (tNear > tFar || tFar <= 0 || tNear >= tMax) return -1
  if (tNear <= OCCLUSION_START_EPS_M) {
    // Inside, or on a face. A segment that never leaves the box ends inside it with the lamp: kept.
    if (tFar >= tMax) return -1
    // How far behind the face it leaves by is the distance to that face's plane.
    const depth =
      farX <= farY && farX <= farZ ? farX * Math.abs(ldx) : farY <= farZ ? farY * Math.abs(ldy) : farZ * Math.abs(ldz)
    if (depth <= b.skin) return -1
  }
  frame.lox = lox
  frame.loy = loy
  frame.loz = loz
  frame.ldx = ldx
  frame.ldy = ldy
  frame.ldz = ldz
  return (Math.max(tNear, 0) + Math.min(tFar, tMax)) / 2
}

/**
 * What a crossing of packed collider [b] keeps, the segment in its [frame] and [t] the middle of the
 * crossing ([segmentCrossing]): the twin of [TRANSMIT_GLSL]'s `crossingShare`. 0 for a solid box,
 * `open(θ)^gather` for a net (θ from the cloth's normal, its local z), and for a painted cloth 1 or 0
 * as its mask in [atlas] is a hole or cloth at [t] — the one copy of each, which the surfaces' shadows
 * and the haze (`hazePlanes.ts`) both read.
 */
export function crossingShare(b: PackedCollider, frame: BoxFrame, t: number, atlas: Uint8Array = sceneMasks.atlas): number {
  if (b.kind < 0.5) return 0
  // A net: its share at the angle the segment crosses it, through as many layers as it is gathered.
  if (b.kind < 1.5) return scrimShare(Math.abs(frame.ldz), b.k1, b.k2)
  // A cut cloth: its mask at the middle of the crossing.
  const [u0, u1] = unpackUnitPair(b.k2)
  const [v0, v1] = unpackUnitPair(b.k3)
  const u = u0 + ((frame.lox + frame.ldx * t) / b.hx + 1) * 0.5 * (u1 - u0)
  const v = v0 + ((frame.loy + frame.ldy * t) / b.hy + 1) * 0.5 * (v1 - v0)
  return maskHole(atlas, Math.round(b.k1), u, v) ? 1 : 0
}

const SCRATCH_FRAME: BoxFrame = emptyBoxFrame()

/**
 * How much of the light the segment from [px, py, pz] along the unit [dx, dy, dz] — towards the lamp
 * — for [tMax] metres keeps through packed collider [b]: the twin of [OCCLUSION_GLSL]'s
 * `boxTransmit`, step for step. 1 where the segment passes the box ([segmentCrossing]); otherwise the
 * box's share of the crossing ([crossingShare]).
 */
export function segmentTransmit(
  px: number, py: number, pz: number,
  dx: number, dy: number, dz: number,
  tMax: number,
  b: PackedCollider,
  atlas: Uint8Array = sceneMasks.atlas,
): number {
  const t = segmentCrossing(px, py, pz, dx, dy, dz, tMax, b, SCRATCH_FRAME)
  return t < 0 ? 1 : crossingShare(b, SCRATCH_FRAME, t, atlas)
}

function nonZero(v: number): number {
  return Math.abs(v) < MIN_DIR ? (v < 0 ? -MIN_DIR : MIN_DIR) : v
}

/**
 * How far from the fragment the segment towards the apex is tested: to the aperture's plane (an
 * axial [near] from the apex, crossed at `near / cosAxis` along the ray), less [MIN_REACH_M], the
 * stretch beam reach ignores in front of the lens — a hit there is the fixture's own mount.
 */
export function occlusionReach(dist: number, cosAxis: number, near: number): number {
  return dist - near / Math.max(cosAxis, 1e-6) - MIN_REACH_M
}

/**
 * The GLSL of [segmentCrossing] and [crossingShare], with the scrim share it reads (`scrimOpen.ts`'s
 * [SCRIM_OPEN_GLSL]) and the mask atlas it samples: the one copy of the crossing, of `open(θ)` and of
 * the mask lookup, which [OCCLUSION_GLSL] holds for the surfaces and the haze's march holds for the
 * air (`hazePlanes.ts`'s `HAZE_PLANES_GLSL`). Neither texture of colliders is declared here, so the
 * beam program, which reads its planes from uniforms, takes it without them.
 */
export const TRANSMIT_GLSL = /* glsl */ `
  #define OCCLUSION_START_EPS ${OCCLUSION_START_EPS_M.toFixed(4)}
  #define OCCLUSION_MIN_DIR ${MIN_DIR.toExponential(1)}
  uniform sampler2DArray uMaskAtlas;
  ${SCRIM_OPEN_GLSL}

  float occlusionNonZero(float v) {
    return abs(v) < OCCLUSION_MIN_DIR ? (v < 0.0 ? -OCCLUSION_MIN_DIR : OCCLUSION_MIN_DIR) : v;
  }

  vec2 unpackUnitPair(float p) {
    float hi = floor(p / ${UNIT_BASE}.0);
    return vec2(hi, p - hi * ${UNIT_BASE}.0) / ${UNIT_STEPS}.0;
  }

  float segmentCrossing(vec3 p, vec3 d, float tMax, vec4 a, vec4 b, out vec3 lo, out vec3 ld) {
    float s = sqrt(max(0.0, 1.0 - a.w * a.w));
    vec3 rel = p - a.xyz;
    lo = vec3(a.w * rel.x - s * rel.z, rel.y, s * rel.x + a.w * rel.z);
    ld = vec3(
      occlusionNonZero(a.w * d.x - s * d.z),
      occlusionNonZero(d.y),
      occlusionNonZero(s * d.x + a.w * d.z)
    );
    vec3 t1 = (-b.xyz - lo) / ld;
    vec3 t2 = (b.xyz - lo) / ld;
    vec3 tFarV = max(t1, t2);
    vec3 tNearV = min(t1, t2);
    float tNear = max(max(tNearV.x, tNearV.y), tNearV.z);
    float tFar = min(min(tFarV.x, tFarV.y), tFarV.z);
    if (tNear > tFar || tFar <= 0.0 || tNear >= tMax) return -1.0;
    if (tNear <= OCCLUSION_START_EPS) {
      if (tFar >= tMax) return -1.0;
      float depth = tFarV.x <= tFarV.y && tFarV.x <= tFarV.z ? tFarV.x * abs(ld.x)
        : tFarV.y <= tFarV.z ? tFarV.y * abs(ld.y) : tFarV.z * abs(ld.z);
      if (depth <= b.w) return -1.0;
    }
    return (max(tNear, 0.0) + min(tFar, tMax)) * 0.5;
  }

  float crossingShare(vec4 m, vec4 b, vec3 lo, vec3 ld, float t) {
    if (m.x < 0.5) return 0.0;
    if (m.x < 1.5) return scrimShare(abs(ld.z), m.y, m.z);
    vec2 us = unpackUnitPair(m.z);
    vec2 vs = unpackUnitPair(m.w);
    float u = us.x + ((lo.x + ld.x * t) / b.x + 1.0) * 0.5 * (us.y - us.x);
    float v = vs.x + ((lo.y + ld.y * t) / b.y + 1.0) * 0.5 * (vs.y - vs.x);
    ivec2 texel = clamp(ivec2(floor(vec2(u, v) * ${MASK_SIZE}.0)), ivec2(0), ivec2(${MASK_SIZE - 1}));
    float alpha = texelFetch(uMaskAtlas, ivec3(texel, int(m.y + 0.5)), 0).r;
    // A byte below MASK_HOLE_BELOW, read normalised: halfway between it and the byte under it.
    return alpha < ${((MASK_HOLE_BELOW - 0.5) / 255).toFixed(6)} ? 1.0 : 0.0;
  }
`

/**
 * The GLSL of [segmentTransmit], [occlusionReach] and the per-light loop, over [TRANSMIT_GLSL]:
 * `lightTransmit(i, p, toLamp, dist, cosAxis, near, landing)` is how much of light row [i] reaches
 * point [p] — the product of the shares of the boxes in its list the segment crosses, 0 at the first
 * solid one; or, for a light that overflowed its list, 0 behind its landing planes (`landing.ts`'s
 * `behindLanding`, which the caller's program must include, with `REACH_EPS`) and otherwise the
 * product over the transmitting boxes its row still lists. A box's texel 2 is fetched only once the
 * segment is known to cross it.
 */
export const OCCLUSION_GLSL = /* glsl */ `
  #define OCCLUSION_MIN_REACH ${MIN_REACH_M.toFixed(4)}
  uniform sampler2D uColliders;
  uniform sampler2D uLightColliders;
  ${TRANSMIT_GLSL}

  float boxTransmit(vec3 p, vec3 d, float tMax, int k) {
    vec4 b = texelFetch(uColliders, ivec2(1, k), 0);
    vec3 lo;
    vec3 ld;
    float t = segmentCrossing(p, d, tMax, texelFetch(uColliders, ivec2(0, k), 0), b, lo, ld);
    if (t < 0.0) return 1.0;
    return crossingShare(texelFetch(uColliders, ivec2(2, k), 0), b, lo, ld, t);
  }

  float lightTransmit(int i, vec3 p, vec3 toLamp, float dist, float cosAxis, float near, vec4 landing) {
    float n = texelFetch(uLightColliders, ivec2(0, i), 0).r;
    // Overflowed: the planes stand for the solid boxes, and its row lists the transmitting ones.
    if (n < 0.0 && behindLanding(landing, p, REACH_EPS)) return 0.0;
    float tMax = dist - near / max(cosAxis, 1e-6) - OCCLUSION_MIN_REACH;
    if (tMax <= 0.0) return 1.0;
    int count = n < 0.0 ? int(-n - 0.5) : int(n + 0.5);
    float share = 1.0;
    for (int j = 0; j < count; j++) {
      // The box's sphere first: a fragment outside its cone from the apex, or nearer the apex than
      // it, cannot be behind it.
      vec4 entry = texelFetch(uLightColliders, ivec2(1 + j, i), 0);
      vec3 u = vec3(entry.xy, 1.0 - abs(entry.x) - abs(entry.y));
      if (u.z < 0.0) u.xy = (1.0 - abs(u.yx)) * vec2(entry.x >= 0.0 ? 1.0 : -1.0, entry.y >= 0.0 ? 1.0 : -1.0);
      if (dot(-toLamp, normalize(u)) < entry.z) continue;
      float q = floor(entry.w / ${ENTRY_INDEX_BASE}.0);
      if (dist < q / 100.0) continue;
      share *= boxTransmit(p, toLamp, tMax, int(entry.w - q * ${ENTRY_INDEX_BASE}.0 + 0.5));
      if (share <= 0.0) return 0.0;
    }
    return share;
  }
`
