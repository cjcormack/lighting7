import { coneReachesSphere } from '../beamLobes'
import { MIN_REACH_M, type Collider } from './beamReach'
import { LIGHT_TEXELS } from './lightTable'

/**
 * **Box occlusion**: a surface fragment is lit by a light only if the segment from it to the lamp
 * meets none of the colliders in that light's cone. Analytic, against the oriented boxes beam reach
 * casts at (`beamReach.ts`), so a flat shadows the wall behind it with no shadow map.
 *
 * Two textures beside the light table, which stays at six texels:
 *
 * - **The colliders**, one row each:
 *
 *   | texel | rgb | a |
 *   |---|---|---|
 *   | 0 | centre, three.js space | the yaw's cosine, folded so its sine is ≥ 0 |
 *   | 1 | half-extents along the box's own axes | skin ([colliderSkin]) |
 *
 *   A box turned by π is the same box, so the shader takes the sine as `√(1 − cos²)` rather than
 *   paying a `cos` and a `sin` per fragment.
 * - **Each light's list**, one row per packed light row: the count, then one texel per collider
 *   ([packEntry]) — its bounding sphere's direction and angular size from the apex, its index and
 *   the distance to the sphere's near side. The sphere lets a fragment that cannot be behind the box
 *   skip the box test on the fetch it has already paid for. [cullLightColliders] fills it; a light
 *   whose cone reaches more than [MAX_LIGHT_COLLIDERS] is [LIST_OVERFLOW] and falls back to its
 *   landing planes (`landing.ts`).
 *
 * The test ([segmentBlocked], [OCCLUSION_GLSL]) casts from the fragment towards the lamp, stopping
 * short of the aperture. A box the fragment sits inside — a pleated cloth in its own box, a wall's
 * face on its slab — passes it while the fragment lies within the box's skin of the face the segment
 * leaves by: a drape's troughs stay lit, the floor under a deck stays dark.
 */

/** RGBA texels a collider takes in [OCCLUSION_GLSL]'s `uColliders`. */
export const COLLIDER_TEXELS = 2
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
/** A light whose cone reached more colliders than [MAX_LIGHT_COLLIDERS]: its landing planes decide. */
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

/** Pack [colliders] into [set] (its rows past the count are left as they were: nothing reads them). */
export function packColliders(colliders: readonly Collider[], set: ColliderSet): void {
  const capacity = set.data.length / (COLLIDER_TEXELS * 4)
  const n = Math.min(colliders.length, capacity)
  const d = set.data
  const s = set.spheres
  for (let i = 0; i < n; i++) {
    const c = colliders[i]
    const o = i * COLLIDER_TEXELS * 4
    // Fold the turn into [0, π]: the same box, a non-negative sine.
    const flip = c.sin < 0 || (c.sin === 0 && c.cos < 0)
    d[o] = c.cx
    d[o + 1] = c.cy
    d[o + 2] = c.cz
    d[o + 3] = flip ? -c.cos : c.cos
    d[o + 4] = c.hx
    d[o + 5] = c.hy
    d[o + 6] = c.hz
    d[o + 7] = colliderSkin(c)
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
  return out
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
    APEX.x = ax
    APEX.y = ay
    APEX.z = az
    AXIS.x = dx
    AXIS.y = dy
    AXIS.z = dz
    const px = ax + dx * near
    const py = ay + dy * near
    const pz = az + dz * near
    const length = near + throwM
    const row = k * LIST_TEXELS * 4
    let n = 0
    let overflow = false
    for (let i = 0; i < set.count; i++) {
      if (!boxReachesCone(set, i, length, cosCone, sinCone)) continue
      if (containsPoint(set.data, i, px, py, pz)) continue
      if (n >= limit) {
        overflow = true
        break
      }
      writeEntry(out, row + 4 + n * 4, i, ax, ay, az, s)
      n++
    }
    if (!overflow && set.dropped > 0) overflow = true
    out[row] = overflow ? LIST_OVERFLOW : n
  }
}

/**
 * How many floats of light row [k]'s list are in use — the count's texel and its entries — so the
 * upload can stop there: a row is 516 floats long and a light's list is usually a few entries.
 */
export function listRowFloats(lists: Float32Array, k: number): number {
  const n = lists[k * LIST_TEXELS * 4]
  return 4 * (1 + (n > 0 ? n : 0))
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

const APEX = { x: 0, y: 0, z: 0 }
const AXIS = { x: 0, y: 0, z: 0 }
const CENTRE = { x: 0, y: 0, z: 0 }

/**
 * A sphere at most this wide for its distance from the apex is tight enough to trust: past it, a
 * box is halved along its longest axis and each half tried. A room's wall is 18 m long, and the
 * sphere round it reaches into every cone pointed anywhere near it.
 */
const SPLIT_ANGULAR_RADIUS = 0.05
/** How many halvings a box may take before its last sphere is trusted: 2¹⁰ pieces at most. */
const SPLIT_DEPTH = 10
/** The halving's stack: depth + 1 entries of a centre in the box's frame and half-extents. */
const SPLIT_STACK = new Float64Array((SPLIT_DEPTH + 1) * 7)

/**
 * Whether the cone from [APEX] along [AXIS] reaches collider [i] of [set] — conservatively, as
 * [coneReachesSphere] is, but tighter for a big box: a box whose sphere is wide for its distance is
 * halved along its longest axis, and each half tried in turn, until a piece the cone reaches is
 * small enough to trust or no piece is left.
 */
function boxReachesCone(set: ColliderSet, i: number, length: number, cosCone: number, sinCone: number): boolean {
  const s = set.spheres
  CENTRE.x = s[i * 4]
  CENTRE.y = s[i * 4 + 1]
  CENTRE.z = s[i * 4 + 2]
  if (!coneReachesSphere(APEX, AXIS, length, cosCone, sinCone, CENTRE, s[i * 4 + 3])) return false
  const b = unpackCollider(set.data, i, SCRATCH)
  const stack = SPLIT_STACK
  let top = 0
  stack[0] = 0
  stack[1] = 0
  stack[2] = 0
  stack[3] = b.hx
  stack[4] = b.hy
  stack[5] = b.hz
  stack[6] = 0
  top = 1
  while (top > 0) {
    top--
    const o = top * 7
    const lx = stack[o]
    const ly = stack[o + 1]
    const lz = stack[o + 2]
    const hx = stack[o + 3]
    const hy = stack[o + 4]
    const hz = stack[o + 5]
    const depth = stack[o + 6]
    // Out of the box's frame by +yaw, as `beamReach` turns a face's normal.
    CENTRE.x = b.cx + b.cos * lx + b.sin * lz
    CENTRE.y = b.cy + ly
    CENTRE.z = b.cz - b.sin * lx + b.cos * lz
    const r = Math.hypot(hx, hy, hz)
    if (!coneReachesSphere(APEX, AXIS, length, cosCone, sinCone, CENTRE, r)) continue
    const dist = Math.hypot(CENTRE.x - APEX.x, CENTRE.y - APEX.y, CENTRE.z - APEX.z)
    if (depth >= SPLIT_DEPTH || r <= SPLIT_ANGULAR_RADIUS * dist) return true
    // Halve along the longest axis: two pieces, depth-first, the stack never deeper than SPLIT_DEPTH.
    const axis = hx >= hy && hx >= hz ? 0 : hy >= hz ? 1 : 2
    for (const sign of SIGNS) {
      const p = top * 7
      stack[p] = axis === 0 ? lx + (sign * hx) / 2 : lx
      stack[p + 1] = axis === 1 ? ly + (sign * hy) / 2 : ly
      stack[p + 2] = axis === 2 ? lz + (sign * hz) / 2 : lz
      stack[p + 3] = axis === 0 ? hx / 2 : hx
      stack[p + 4] = axis === 1 ? hy / 2 : hy
      stack[p + 5] = axis === 2 ? hz / 2 : hz
      stack[p + 6] = depth + 1
      top++
    }
  }
  return false
}

const SIGNS = [-1, 1] as const

const SCRATCH: PackedCollider = { cx: 0, cy: 0, cz: 0, hx: 0, hy: 0, hz: 0, cos: 1, sin: 0, skin: 0 }

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

/**
 * Whether the segment from [px, py, pz] along the unit [dx, dy, dz] — towards the lamp — for
 * [tMax] metres is blocked by packed collider [b]: the twin of [OCCLUSION_GLSL]'s `boxBlocks`, step
 * for step. A box ahead of the start (entered past [OCCLUSION_START_EPS_M]) blocks it; a box the
 * start sits inside (or on) blocks it only when the start lies more than the box's skin behind the
 * face the segment leaves by — and not at all when the segment ends inside it.
 */
export function segmentBlocked(
  px: number, py: number, pz: number,
  dx: number, dy: number, dz: number,
  tMax: number,
  b: PackedCollider,
): boolean {
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
  if (tNear > tFar || tFar <= 0 || tNear >= tMax) return false
  if (tNear > OCCLUSION_START_EPS_M) return true
  // Inside, or on a face. A segment that never leaves the box ends inside it with the lamp: not blocked.
  if (tFar >= tMax) return false
  // How far behind the face it leaves by is the distance to that face's plane.
  const depth =
    farX <= farY && farX <= farZ ? farX * Math.abs(ldx) : farY <= farZ ? farY * Math.abs(ldy) : farZ * Math.abs(ldz)
  return depth > b.skin
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
 * The GLSL of [segmentBlocked], [occlusionReach] and the per-light loop: `lightOccluded(i, p, toLamp,
 * dist, cosAxis, near, landing)` is whether light row [i] is kept off point [p] — by a box in its
 * list, or, for a light that overflowed its list, by its landing planes (`landing.ts`'s
 * `behindLanding`, which the caller's program must include, with `REACH_EPS`).
 */
export const OCCLUSION_GLSL = /* glsl */ `
  #define OCCLUSION_START_EPS ${OCCLUSION_START_EPS_M.toFixed(4)}
  #define OCCLUSION_MIN_REACH ${MIN_REACH_M.toFixed(4)}
  #define OCCLUSION_MIN_DIR ${MIN_DIR.toExponential(1)}
  uniform sampler2D uColliders;
  uniform sampler2D uLightColliders;

  float occlusionNonZero(float v) {
    return abs(v) < OCCLUSION_MIN_DIR ? (v < 0.0 ? -OCCLUSION_MIN_DIR : OCCLUSION_MIN_DIR) : v;
  }

  bool boxBlocks(vec3 p, vec3 d, float tMax, int k) {
    vec4 a = texelFetch(uColliders, ivec2(0, k), 0);
    vec4 b = texelFetch(uColliders, ivec2(1, k), 0);
    float s = sqrt(max(0.0, 1.0 - a.w * a.w));
    vec3 rel = p - a.xyz;
    vec3 lo = vec3(a.w * rel.x - s * rel.z, rel.y, s * rel.x + a.w * rel.z);
    vec3 ld = vec3(
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
    if (tNear > tFar || tFar <= 0.0 || tNear >= tMax) return false;
    if (tNear > OCCLUSION_START_EPS) return true;
    if (tFar >= tMax) return false;
    float depth = tFarV.x <= tFarV.y && tFarV.x <= tFarV.z ? tFarV.x * abs(ld.x)
      : tFarV.y <= tFarV.z ? tFarV.y * abs(ld.y) : tFarV.z * abs(ld.z);
    return depth > b.w;
  }

  bool lightOccluded(int i, vec3 p, vec3 toLamp, float dist, float cosAxis, float near, vec4 landing) {
    float n = texelFetch(uLightColliders, ivec2(0, i), 0).r;
    if (n < 0.0) return behindLanding(landing, p, REACH_EPS);
    float tMax = dist - near / max(cosAxis, 1e-6) - OCCLUSION_MIN_REACH;
    if (tMax <= 0.0) return false;
    int count = int(n + 0.5);
    for (int j = 0; j < count; j++) {
      // The box's sphere first: a fragment outside its cone from the apex, or nearer the apex than
      // it, cannot be behind it.
      vec4 entry = texelFetch(uLightColliders, ivec2(1 + j, i), 0);
      vec3 u = vec3(entry.xy, 1.0 - abs(entry.x) - abs(entry.y));
      if (u.z < 0.0) u.xy = (1.0 - abs(u.yx)) * vec2(entry.x >= 0.0 ? 1.0 : -1.0, entry.y >= 0.0 ? 1.0 : -1.0);
      if (dot(-toLamp, normalize(u)) < entry.z) continue;
      float q = floor(entry.w / ${ENTRY_INDEX_BASE}.0);
      if (dist < q / 100.0) continue;
      if (boxBlocks(p, toLamp, tMax, int(entry.w - q * ${ENTRY_INDEX_BASE}.0 + 0.5))) return true;
    }
    return false;
  }
`
