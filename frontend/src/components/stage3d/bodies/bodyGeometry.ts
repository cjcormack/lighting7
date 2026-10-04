import {
  BoxGeometry,
  BufferGeometry,
  CircleGeometry,
  CylinderGeometry,
  Euler,
  Matrix4,
  PlaneGeometry,
  Quaternion,
  Vector3,
} from 'three'
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js'
import type { BodySpec } from './archetype'
import type { Mount } from './mount'

/**
 * The meshes a body is built from, per **part** (stage-view plan session 6, the design record's
 * items 3 and 12): the **base** in the body's frame, the **yoke** that pans, the **head** that tilts
 * (its housing and accessories — shutters, barn doors, a colour frame), one **lens** per cell, and
 * a **hanger** up to the bar. `StageBodies` instances each part across every fixture that shares
 * it, so draw calls scale with the parts, not the rig.
 *
 * Two levels of detail: `full`, and `simple` — the same silhouette in a handful of low-segment
 * boxes and cylinders, for a body a few dozen pixels across. Past that a body is a billboard glyph
 * (`StageBodies`), which needs no geometry here.
 *
 * Frames, all three.js metres: the head's pivot is its origin and the beam leaves along its Y toward
 * `spec.emitAxis`, the cells (`archetype.ts`) sitting on that face. A mover's head pivots at
 * [BodyFrames.pivotY] above its yoke's origin, which is its base; a static lantern's pivot *is*
 * its placement point, and its yoke is drawn up to a clamp when it hangs and down to a plate when it
 * stands — lifted by [BodyFrames.standLiftM], so the plate rests on the ledge. The frames are
 * numbers (`bodyFrames`), read by `FixtureModel` every render; the geometry is built once per spec,
 * mount and level and shared, reference-counted by the canvases that draw it.
 */

export type BodyLod = 'full' | 'simple'

/** The body's GPU parts, per frame. */
export interface BodyGeometry {
  /** In the body's frame (a mover's mount). Null for a static lantern. */
  base: BufferGeometry | null
  /** In the yoke's frame. Null for a body without one (a downlight, a strip of tape). */
  yoke: BufferGeometry | null
  /** In the head's frame. */
  head: BufferGeometry
}

/** Where a body's frames sit and what it attaches by — numbers only, no geometry. */
export interface BodyFrames {
  /** Where the head pivots, in the yoke's frame (0 for a static lantern). */
  pivotY: number
  /** How far a standing static lantern's pivot is raised above its placement point. */
  standLiftM: number
  /**
   * Where a hanger meets the body, in the frame named: a hung static lantern's clamp top (yoke
   * frame), a mover's base face (mount frame). Null where no hanger can attach.
   */
  hangerAttach: { frame: 'yoke' | 'mount'; y: number } | null
  /** A box round the body at rest, for its hit proxy: size and centre, in the frame named. */
  hitBox: { frame: 'head' | 'mount'; size: [number, number, number]; centre: [number, number, number] }
}

const SCRATCH_Q = new Quaternion()
const SCRATCH_E = new Euler()
const ONE = new Vector3(1, 1, 1)

/** A matrix placing a part at (x, y, z), turned by (rx, ry, rz). */
function at(x = 0, y = 0, z = 0, rx = 0, ry = 0, rz = 0): Matrix4 {
  SCRATCH_Q.setFromEuler(SCRATCH_E.set(rx, ry, rz))
  return new Matrix4().compose(new Vector3(x, y, z), SCRATCH_Q, ONE)
}

type Part = [BufferGeometry, Matrix4]

function cyl(rTop: number, rBottom: number, h: number, seg: number): BufferGeometry {
  return new CylinderGeometry(Math.max(1e-4, rTop), Math.max(1e-4, rBottom), Math.max(1e-4, h), seg)
}

function box(w: number, h: number, d: number): BufferGeometry {
  return new BoxGeometry(Math.max(1e-4, w), Math.max(1e-4, h), Math.max(1e-4, d))
}

/** Merge parts into one geometry of position and normal only, which is all the housing reads. */
function merge(parts: Part[]): BufferGeometry | null {
  if (parts.length === 0) return null
  const geos = parts.map(([g, m]) => {
    const out = g.applyMatrix4(m)
    out.deleteAttribute('uv')
    return out.index ? out.toNonIndexed() : out
  })
  const merged = mergeGeometries(geos, false)
  for (const g of geos) g.dispose()
  if (!merged) return null
  merged.computeBoundingSphere()
  return merged
}

/** A colour frame: four thin bars round the gate, `side` across, at `y`. */
function frame(side: number, y: number): Part[] {
  const t = 0.01
  const h = side / 2
  return [
    [box(side, t, t), at(0, y, h)],
    [box(side, t, t), at(0, y, -h)],
    [box(t, t, side), at(h, y, 0)],
    [box(t, t, side), at(-h, y, 0)],
  ]
}

/** The head's housing and accessories, in the head frame. The lens face is at `y = e · front`. */
function headParts(spec: BodySpec, lod: BodyLod): Part[] {
  const { lengthM: L, widthM: W, heightM: H } = spec
  const D = Math.max(W, H)
  const e = spec.emitAxis
  const full = lod === 'full'
  const seg = full ? 20 : 8
  const lensR = spec.cells[0]?.halfWidthM ?? D * 0.3
  const parts: Part[] = []
  switch (spec.archetype) {
    case 'profile':
      if (full) {
        parts.push([cyl(D * 0.42, D * 0.3, L * 0.2, seg), at(0, L * 0.4)])
        parts.push([cyl(D * 0.5, D * 0.5, L * 0.3, seg), at(0, L * 0.15)])
        parts.push([cyl(lensR * 1.25, lensR * 1.25, L * 0.5, seg), at(0, -L * 0.25)])
        if (spec.accessories.shutters) {
          for (const [x, z] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
            parts.push([box(x ? 0.07 : 0.022, 0.018, z ? 0.07 : 0.022), at(x * (D * 0.5 + 0.03), -0.01, z * (D * 0.5 + 0.03))])
          }
        }
        if (spec.accessories.colourFrame) parts.push(...frame(lensR * 2.9, -L / 2 - 0.006))
      } else {
        parts.push([cyl(D * 0.45, D * 0.45, L * 0.5, seg), at(0, L * 0.25)])
        parts.push([cyl(lensR * 1.25, lensR * 1.25, L * 0.5, seg), at(0, -L * 0.25)])
      }
      break
    case 'boxProfile':
      parts.push([box(D, L * 0.6, D), at(0, L * 0.2)])
      parts.push([cyl(lensR * 1.3, lensR * 1.3, L * 0.4, seg), at(0, -L * 0.3)])
      if (full && spec.accessories.shutters) {
        for (const [x, z] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
          parts.push([box(x ? 0.07 : 0.022, 0.018, z ? 0.07 : 0.022), at(x * (D * 0.5 + 0.03), -0.02, z * (D * 0.5 + 0.03))])
        }
      }
      if (full && spec.accessories.colourFrame) parts.push(...frame(lensR * 2.9, -L / 2 - 0.006))
      break
    case 'fresnel':
      parts.push([cyl(D * 0.46, D * 0.34, L * 0.25, seg), at(0, L * 0.38)])
      parts.push([cyl(D * 0.5, D * 0.5, L * 0.75, seg), at(0, -L * 0.12)])
      if (full && spec.accessories.colourFrame) parts.push(...frame(D * 0.95, -L / 2 - 0.006))
      if (full && spec.accessories.barnDoors) {
        const l = D * 0.42
        const a = 0.45
        const hy = -L / 2 - 0.012
        parts.push([box(0.005, l, D * 0.95), at(D * 0.47 + (Math.sin(a) * l) / 2, hy - (Math.cos(a) * l) / 2, 0, 0, 0, a)])
        parts.push([box(0.005, l, D * 0.95), at(-D * 0.47 - (Math.sin(a) * l) / 2, hy - (Math.cos(a) * l) / 2, 0, 0, 0, -a)])
        parts.push([box(D * 0.6, l * 0.8, 0.005), at(0, hy - Math.cos(a) * l * 0.4, D * 0.47 + Math.sin(a) * l * 0.4, -a)])
        parts.push([box(D * 0.6, l * 0.8, 0.005), at(0, hy - Math.cos(a) * l * 0.4, -D * 0.47 - Math.sin(a) * l * 0.4, a)])
      }
      break
    case 'par':
      parts.push([cyl(D * 0.4, D * 0.5, L, seg), at(0, 0)])
      if (full) parts.push([cyl(D * 0.18, D * 0.38, L * 0.25, seg), at(0, L * 0.6)])
      if (full && spec.accessories.colourFrame) parts.push(...frame(D * 1.02, -L / 2 - 0.006))
      break
    case 'flood':
      parts.push([box(W, L, H * 0.85), at(0, 0)])
      if (full && spec.accessories.colourFrame) parts.push(...frame(Math.max(W, H) * 1.02, -L / 2 - 0.006))
      break
    case 'downlight':
      parts.push([cyl(D * 0.5, D * 0.5, L, seg), at(0, 0)])
      if (full) parts.push([cyl(D * 0.62, D * 0.62, 0.012, seg), at(0, L / 2)])
      break
    case 'mover': {
      const headDia = W * (spec.head === 'wash' ? 0.78 : 0.6)
      const headLen = H * (spec.head === 'wash' ? 0.34 : spec.head === 'bar' ? 0.2 : 0.52)
      if (spec.head === 'bar') {
        parts.push([box(W * 0.95, headLen, headDia * 0.55), at(0, 0)])
      } else if (spec.head === 'wash') {
        // Wider at the lens face (+Y at rest).
        parts.push([cyl(headDia * 0.5, headDia * 0.44, headLen, seg), at(0, 0)])
      } else {
        parts.push([cyl(headDia * 0.5, headDia * 0.5, headLen * 0.65, seg), at(0, -headLen * 0.17 * e)])
        parts.push([cyl(headDia * 0.45, lensR * 1.4, headLen * 0.35, seg), at(0, headLen * 0.33 * e)])
      }
      break
    }
    case 'batten':
    case 'blinder':
    case 'effect':
    case 'tape':
      parts.push([box(L, H, W), at(0, 0)])
      break
    case 'cannon': {
      // A low base with two tubes splayed either side of the head's axis.
      const tubeL = L * 0.8
      const r = Math.min(W, L) * 0.1
      parts.push([box(W, H * 0.5, W * 0.72), at(0, 0)])
      for (const sgn of [-1, 1]) {
        const th = sgn * 0.21
        const rootX = sgn * W * 0.25
        parts.push([cyl(r, r * 1.08, tubeL, full ? 14 : 6), at(rootX - (Math.sin(th) * tubeL) / 2, -(Math.cos(th) * tubeL) / 2 - H * 0.25, 0, 0, 0, th)])
      }
      break
    }
  }
  return parts
}

/** How wide a static lantern's yoke is across its trunnions, and how tall its arms. */
function staticYokeSize(spec: BodySpec): { armX: number; armLen: number } {
  if (spec.archetype === 'batten' || spec.archetype === 'blinder' || spec.archetype === 'effect' || spec.archetype === 'cannon') {
    return { armX: spec.lengthM / 2 + 0.02, armLen: Math.max(spec.widthM, spec.heightM) / 2 + 0.07 }
  }
  const D = Math.max(spec.widthM, spec.heightM)
  return { armX: D / 2 + 0.03, armLen: D / 2 + 0.09 }
}

/**
 * A mover's proportions: base height, pivot height and the yoke's half-width. The pivot height is
 * also the desk's (lighting7 `show/FixtureFocus.kt`'s `MoverLens`, which measures a focus from the
 * lens): `src/test/resources/stage/focusInverse.fixture.json`'s `heads` pins both.
 */
function moverSize(spec: BodySpec): { baseH: number; pivotY: number; armX: number } {
  const headDia = spec.widthM * (spec.head === 'wash' ? 0.78 : 0.6)
  return { baseH: Math.max(0.04, spec.heightM * 0.14), pivotY: spec.heightM * 0.6, armX: headDia / 2 + 0.035 }
}

function isLong(spec: BodySpec): boolean {
  return spec.archetype === 'batten' || spec.archetype === 'blinder' || spec.archetype === 'effect' || spec.archetype === 'tape'
}

/** Where a body's frames sit and what it attaches by, for one spec and mount. Cheap and pure. */
export function bodyFrames(spec: BodySpec, mount: Mount): BodyFrames {
  if (spec.archetype === 'mover') {
    const { baseH, pivotY } = moverSize(spec)
    const W = spec.widthM
    return {
      pivotY,
      standLiftM: 0,
      hangerAttach: { frame: 'mount', y: -baseH },
      hitBox: { frame: 'mount', size: [W, spec.heightM, W * 0.75], centre: [0, spec.heightM / 2 - baseH, 0] },
    }
  }
  const D = Math.max(spec.widthM, spec.heightM)
  const hitBox: BodyFrames['hitBox'] = isLong(spec)
    ? { frame: 'head', size: [spec.lengthM, spec.heightM, spec.widthM], centre: [0, 0, 0] }
    : { frame: 'head', size: [D, spec.lengthM, D], centre: [0, 0, 0] }
  if (!spec.yoke) return { pivotY: 0, standLiftM: 0, hangerAttach: null, hitBox }
  const { armLen } = staticYokeSize(spec)
  return mount === 'hang'
    ? { pivotY: 0, standLiftM: 0, hangerAttach: { frame: 'yoke', y: armLen + 0.08 }, hitBox }
    : // A standing lantern's pivot rides the yoke's height above the ledge, so its plate rests on it.
      { pivotY: 0, standLiftM: armLen + 0.02, hangerAttach: null, hitBox }
}

/** The whole body, per part, for one spec, mount and level. */
function build(spec: BodySpec, mount: Mount, lod: BodyLod): BodyGeometry {
  const full = lod === 'full'
  const head = merge(headParts(spec, lod)) ?? new BufferGeometry()
  if (spec.archetype === 'mover') {
    const { baseH, pivotY, armX } = moverSize(spec)
    const W = spec.widthM
    const yokeParts: Part[] = [
      [box(0.03, pivotY + 0.03, 0.06), at(armX, (pivotY + 0.03) / 2)],
      [box(0.03, pivotY + 0.03, 0.06), at(-armX, (pivotY + 0.03) / 2)],
      [box(armX * 2 + 0.03, 0.03, 0.08), at(0, 0.015)],
    ]
    if (full) yokeParts.push([cyl(0.03, 0.03, 0.02, 12), at(0, 0.01)])
    const base = merge([[box(W, baseH, W * 0.75), at(0, -baseH / 2)]])
    return { base, yoke: merge(yokeParts), head }
  }
  if (!spec.yoke) return { base: null, yoke: null, head }
  const D = Math.max(spec.widthM, spec.heightM)
  const { armX, armLen } = staticYokeSize(spec)
  const s = mount === 'stand' ? -1 : 1
  const depth = isLong(spec) ? Math.min(0.05, spec.widthM) : 0.05
  const yokeParts: Part[] = [
    [box(0.022, armLen, depth), at(armX, (s * armLen) / 2)],
    [box(0.022, armLen, depth), at(-armX, (s * armLen) / 2)],
    [box(armX * 2 + 0.022, 0.022, depth), at(0, s * armLen)],
  ]
  if (mount === 'hang') {
    // The clamp above the yoke, which the hanger runs up from.
    if (full) yokeParts.push([box(0.05, 0.07, 0.05), at(0, armLen + 0.045)])
  } else {
    // A plate the yoke stands on.
    yokeParts.push([box(armX * 2 + 0.08, 0.02, Math.max(0.12, D * 0.7)), at(0, -armLen - 0.01)])
  }
  return { base: null, yoke: merge(yokeParts), head }
}

/**
 * The shared geometry, **reference-counted by the canvases that draw it**. A key names a spec (its
 * size included, so every length a strip of tape is set to is a key of its own), a mount and a
 * level. `StageBodies` acquires what its layout draws and releases it when the layout changes or
 * the canvas goes, and a key no canvas holds is disposed — so editing a run's length does not leave
 * a geometry behind per length tried.
 */
const CACHE = new Map<string, { geometry: BodyGeometry; holders: number }>()

/** The geometry key of a body: its spec's, its mount and its level. */
export function bodyGeometryKey(spec: BodySpec, mount: Mount, lod: BodyLod): string {
  return `${spec.key}|${mount}|${lod}`
}

/**
 * The body's parts, built on first use and shared by every fixture drawn from the same spec and
 * mount. Unheld until [acquireBodyGeometry]; an unheld entry goes at the next [releaseUnheldBodyGeometry].
 */
export function bodyGeometry(spec: BodySpec, mount: Mount, lod: BodyLod): BodyGeometry {
  const key = bodyGeometryKey(spec, mount, lod)
  let entry = CACHE.get(key)
  if (!entry) {
    entry = { geometry: build(spec, mount, lod), holders: 0 }
    CACHE.set(key, entry)
  }
  return entry.geometry
}

/**
 * Hold a key's geometry — built by [bodyGeometry] — for a canvas. Given the geometry the build
 * drew with, so one swept away between another canvas's render and its commit is put back rather
 * than left drawn but unowned.
 */
export function acquireBodyGeometry(key: string, geometry: BodyGeometry): void {
  const entry = CACHE.get(key)
  if (entry && entry.geometry === geometry) entry.holders++
  else if (!entry) CACHE.set(key, { geometry, holders: 1 })
  else entry.holders++
}

/** Let go of a key's geometry; disposed once no canvas holds it. */
export function releaseBodyGeometry(key: string): void {
  const entry = CACHE.get(key)
  if (!entry) return
  entry.holders = Math.max(0, entry.holders - 1)
  if (entry.holders === 0) disposeEntry(key)
}

/**
 * Dispose every entry no canvas holds — a layout built and dropped before it committed (a
 * superseded render, React's development double-render), which acquired nothing.
 */
export function releaseUnheldBodyGeometry(): void {
  for (const [key, entry] of CACHE) if (entry.holders === 0) disposeEntry(key)
}

/** How many geometries are cached — for the test. */
export function cachedBodyGeometryCount(): number {
  return CACHE.size
}

function disposeEntry(key: string): void {
  const entry = CACHE.get(key)
  if (!entry) return
  entry.geometry.base?.dispose()
  entry.geometry.yoke?.dispose()
  entry.geometry.head.dispose()
  CACHE.delete(key)
}

/** A unit lens face: a disc of radius 1, or a 2 × 2 square a segment scales, facing +Z. */
export const LENS_DISC = new CircleGeometry(1, 24)
export const LENS_SEGMENT = new PlaneGeometry(2, 2)
/** A hanger: a unit-tall rod, base at its origin, scaled to its length. */
export const HANGER = (() => {
  const g = new CylinderGeometry(0.012, 0.012, 1, 6)
  g.translate(0, 0.5, 0)
  g.deleteAttribute('uv')
  return g
})()
