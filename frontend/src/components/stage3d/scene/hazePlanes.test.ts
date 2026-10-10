import { describe, expect, it } from 'vitest'
import { boxCollider, type Collider } from './beamReach'
import {
  fillHazePlanes,
  HAZE_NO_CROSSING,
  HAZE_PLANE_FLOATS,
  HAZE_PLANES_GLSL,
  hazeBeamReach,
  hazeEyeCrossings,
  hazeSampleShare,
  hazeSpanReaches,
  makeHazeEyeCrossings,
  makeHazePlaneList,
  MAX_HAZE_PLANES,
  type HazePlaneList,
} from './hazePlanes'
import { hazePlanesCrossed } from './landing'
import { emptyBoxFrame, emptyPackedCollider, makeColliderSet, packColliders, segmentCrossing, TRANSMIT_GLSL, unpackCollider } from './occlusion'
import { HAZE_TWIN_EYE, hazeTwinCases, hazeTwinScene, hazeTwinShare } from './hazeTwin'
import { twinAtlas, TWIN_MASK_IMAGE, TWIN_MASK_LAYER, TWIN_MASK_LAYERS } from './occlusionTwin'
import { SCRIM_THREAD_SHARE, scrimShare } from './scrimOpen'

/**
 * The haze splits at a cloth (scrim plan D10, session 5): the list of eight, nearest the eye first,
 * and the march's share at a sample — the eye's side and the beam's — through [hazeSampleShare], the
 * twin of the march's GLSL.
 */

const ST = SCRIM_THREAD_SHARE.SHARKSTOOTH
const cosDeg = (deg: number) => Math.cos((deg * Math.PI) / 180)
const open = (deg: number, gather = 1) => scrimShare(cosDeg(deg), ST, gather)

/** A 6 × 4 m sharkstooth gauze facing the house (three's +z), its centre [z] down the stage. */
function gauze(z: number, gather = 1, x = 0): Collider {
  const c = boxCollider(x, 2, z, 3, 2, 0.005, 0, 0.04, 0.04)
  c.transmit = { kind: 'angle', r: ST, gather }
  return c
}

/** A 6 × 4 m cut cloth facing the house: holes down its stage-right half (the twin atlas's layer). */
function cutCloth(z: number): Collider {
  const c = boxCollider(0, 2, z, 3, 2, 0.005, 0, 0.04, 0.04)
  c.transmit = { kind: 'mask', image: TWIN_MASK_IMAGE, uv: { u0: 0, u1: 1, v0: 0, v1: 1 } }
  return c
}

const LAYERS = { layerOf: (hash: string) => (hash === TWIN_MASK_IMAGE ? TWIN_MASK_LAYER : -1) }
const ATLAS = twinAtlas()

/** [colliders] packed as the canvas packs them, then the list filled from an eye at [eye]. */
function listFrom(colliders: Collider[], eye: [number, number, number], layers = LAYERS): HazePlaneList {
  const set = makeColliderSet()
  packColliders(colliders, set, layers)
  const list = makeHazePlaneList()
  fillHazePlanes(set, ...eye, list)
  return list
}

/** Nine gauzes, a metre apart, from 1 m to 9 m upstage of the setting line. */
const NINE = Array.from({ length: 9 }, (_, k) => gauze(-1 - k))

/** The eye 5 m out front at head height, looking straight up the stage. */
const EYE: [number, number, number] = [0, 2, 5]

/** The haze's share at the sample [z] up the stage on the eye's axis, for a beam whose row names [planes] from [apex]. */
function shareAt(
  list: HazePlaneList,
  z: number,
  { eye = EYE, planes = 0, apex = [0, 2, -12] as [number, number, number], near = 0.1 } = {},
): number {
  const [ex, ey, ez] = eye
  const px = 0
  const py = 2
  const len = Math.hypot(px - ex, py - ey, z - ez)
  const dx = (px - ex) / len
  const dy = (py - ey) / len
  const dz = (z - ez) / len
  const crossings = hazeEyeCrossings(list, ex, ey, ez, dx, dy, dz, makeHazeEyeCrossings(), ATLAS)
  // Towards the apex, to the aperture: the march's `-lightDir` and its reach.
  const ax = apex[0] - px
  const ay = apex[1] - py
  const az = apex[2] - z
  const relLen = Math.hypot(ax, ay, az)
  const cosAngle = 1
  return hazeSampleShare(list, crossings, len, px, py, z, ax / relLen, ay / relLen, az / relLen, hazeBeamReach(relLen, cosAngle, near), planes, ATLAS)
}

describe('the list of eight (D10)', () => {
  it('holds the transmitting planes nearest the eye first, whatever order the scene packs them', () => {
    const scene = [gauze(-6), gauze(-2), boxCollider(0, 2, -3, 3, 2, 0.1), gauze(-4), cutCloth(-1)]
    const list = listFrom(scene, EYE)
    // The flat at −3 is solid: it stops the haze by being where beams land, not by this list.
    expect(Array.from(list.index.slice(0, list.count))).toEqual([4, 1, 3, 0])
    expect(Array.from(list.dist.slice(0, list.count))).toEqual([6 - 0.005, 7 - 0.005, 9 - 0.005, 11 - 0.005].map((d) => expect.closeTo(d, 6)))
    // Each plane is its collider's three texels, as the march reads them back.
    const set = makeColliderSet()
    packColliders(scene, set, LAYERS)
    expect(Array.from(list.data.slice(0, HAZE_PLANE_FLOATS))).toEqual(Array.from(set.data.slice(4 * HAZE_PLANE_FLOATS, 5 * HAZE_PLANE_FLOATS)))
  })

  it('reorders as the eye moves, and says when the list changed', () => {
    const scene = [gauze(-6), gauze(-2), gauze(-4)]
    const set = makeColliderSet()
    packColliders(scene, set, LAYERS)
    const list = makeHazePlaneList()
    expect(fillHazePlanes(set, ...EYE, list)).toBe(true)
    expect(Array.from(list.index.slice(0, 3))).toEqual([1, 2, 0])
    // The same eye again: nothing moved.
    expect(fillHazePlanes(set, ...EYE, list)).toBe(false)
    // From upstage of them all, the order turns round.
    expect(fillHazePlanes(set, 0, 2, -12, list)).toBe(true)
    expect(Array.from(list.index.slice(0, 3))).toEqual([0, 2, 1])
  })

  it('keeps eight of nine, dropping the furthest, and counts the ninth', () => {
    const list = listFrom(NINE, EYE)
    expect(MAX_HAZE_PLANES).toBe(8)
    expect(list.count).toBe(8)
    expect(list.over).toBe(1)
    expect(Array.from(list.index.slice(0, 8))).toEqual([0, 1, 2, 3, 4, 5, 6, 7])
    // From the back of the stage, the ninth is the nearest and the first is the one dropped.
    const back = listFrom(NINE, [0, 2, -15])
    expect(Array.from(back.index.slice(0, 8))).toEqual([8, 7, 6, 5, 4, 3, 2, 1])
  })

  it('leaves out a cut cloth whose mask cuts no hole — loading, missing or opaque — which is packed solid', () => {
    const list = listFrom([cutCloth(-2), gauze(-4)], EYE, { layerOf: () => -1 })
    expect(Array.from(list.index.slice(0, list.count))).toEqual([1])
  })
})

describe("the march's share at a sample (D10)", () => {
  it('keeps a gauze’s open(θ_eye) behind it from the eye, square on and from the side', () => {
    const one = listFrom([gauze(-1)], EYE)
    // In front of it the haze is whole; behind it, the gauze's own blend's share.
    expect(shareAt(one, 0)).toBe(1)
    expect(shareAt(one, -3)).toBeCloseTo(open(0), 6)
    expect(open(0)).toBeCloseTo(0.49, 6)
    // From 45° off the axis the threads hide more of the holes: dimmer still.
    const side: [number, number, number] = [6, 2, 5]
    const oblique = (() => {
      const crossings = hazeEyeCrossings(one, ...side, -Math.SQRT1_2, 0, -Math.SQRT1_2, makeHazeEyeCrossings(), ATLAS)
      // A sample 4 m past the gauze along that ray.
      const t = 6 * Math.SQRT2 + 4
      return hazeSampleShare(one, crossings, t, side[0] - Math.SQRT1_2 * t, 2, side[2] - Math.SQRT1_2 * t, 0, 0, -1, 0, 0, ATLAS)
    })()
    expect(oblique).toBeCloseTo(open(45), 6)
    expect(oblique).toBeLessThan(open(0))
  })

  it('takes a gathered gauze once, at its ^gather', () => {
    const list = listFrom([gauze(-1, 2.5)], EYE)
    expect(shareAt(list, -3)).toBeCloseTo(open(0, 2.5), 6)
  })

  it('multiplies two planes behind each other, and only the ones in front of the sample', () => {
    const two = listFrom([gauze(-1), gauze(-3)], EYE)
    expect(shareAt(two, -2)).toBeCloseTo(open(0), 6)
    expect(shareAt(two, -5)).toBeCloseTo(open(0) ** 2, 6)
  })

  it('splits at eight of nine: the ninth, past the list, does not split the haze behind it', () => {
    const nine = listFrom(NINE, EYE)
    expect(shareAt(nine, -8.5)).toBeCloseTo(open(0) ** 8, 6)
    // Behind the ninth too: it passes light on the surfaces, but its haze is not split.
    expect(shareAt(nine, -10)).toBeCloseTo(open(0) ** 8, 6)
    expect(shareAt(nine, -10)).not.toBeCloseTo(open(0) ** 9, 6)
  })

  it("takes the beam's share of each plane its row names, between the sample and the aperture", () => {
    // A back light 12 m up the stage, shining at the house through all nine.
    const nine = listFrom(NINE, EYE)
    const apex: [number, number, number] = [0, 2, -12]
    const planes = hazePlanesCrossed(nine.data, nine.count, HAZE_PLANE_FLOATS, ...apex, 0, 0, 1, 20, cosDeg(5), Math.sin((5 * Math.PI) / 180))
    expect(planes).toBe(0xff)
    // A sample in front of every plane: the eye sees it whole, the beam has come through eight.
    expect(shareAt(nine, 0, { planes, apex })).toBeCloseTo(open(0) ** 8, 6)
    // Between the fourth and fifth from the house: four on the eye's side, and on the beam's the
    // four of the other five the list holds — the ninth is past it.
    expect(shareAt(nine, -4.5, { planes, apex })).toBeCloseTo(open(0) ** 4 * open(0) ** 4, 6)
    // A plane the row does not name is not the beam's to pay, whatever lies between.
    expect(shareAt(nine, 0, { planes: 0b1, apex })).toBeCloseTo(open(0), 6)
    // Nothing between the aperture and the apex counts: a plane behind the lens is not on the path.
    expect(shareAt(nine, 0, { planes, apex, near: 12 })).toBe(1)
  })

  it("passes a cut cloth's hole and stops at its cloth, on the eye's side and on the beam's", () => {
    const cut = listFrom([cutCloth(-2)], EYE)
    const sample = (x: number, z: number, planes = 0, apex: [number, number, number] = [x, 2, -12]) => {
      const crossings = hazeEyeCrossings(cut, x, 2, 5, 0, 0, -1, makeHazeEyeCrossings(), ATLAS)
      const relLen = Math.abs(apex[2] - z)
      return hazeSampleShare(cut, crossings, 5 - z, x, 2, z, 0, 0, -1, hazeBeamReach(relLen, 1, 0.1), planes, ATLAS)
    }
    // Stage right (−x) is the image's left half, a hole; stage left its right half, cloth.
    expect(sample(-1.5, -4)).toBe(1)
    expect(sample(1.5, -4)).toBe(0)
    // A shaft from behind it crosses at its holes and stops at its cloth.
    expect(sample(-1.5, 0, 1)).toBe(1)
    expect(sample(1.5, 0, 1)).toBe(0)
  })

  it('crosses nothing where the list is empty, and every crossing of none reads as past the march', () => {
    const empty = makeHazePlaneList()
    const crossings = hazeEyeCrossings(empty, ...EYE, 0, 0, -1, makeHazeEyeCrossings(), ATLAS)
    expect(Array.from(crossings.at)).toEqual(new Array(MAX_HAZE_PLANES).fill(HAZE_NO_CROSSING))
    expect(hazeSampleShare(empty, crossings, 10, 0, 2, -5, 0, 0, -1, 5, 0xff, ATLAS)).toBe(1)
  })
})

describe("the march's cull", () => {
  it('never drops a crossing: every segment that crosses a plane spans its slab', () => {
    // Turned planes, segments from everywhere: a seeded walk, so a failure reproduces.
    let seed = 7
    const rand = () => ((seed = (seed * 16807) % 2147483647) / 2147483647) * 2 - 1
    const set = makeColliderSet()
    const turned = Array.from({ length: 6 }, (_, k) => {
      const c = boxCollider(rand() * 3, 2 + rand(), rand() * 4, 2 + rand(), 2, 0.005 + 0.1 * Math.abs(rand()), rand() * Math.PI)
      c.transmit = { kind: 'angle', r: ST, gather: 1 + k * 0.3 }
      return c
    })
    packColliders(turned, set)
    const frame = emptyBoxFrame()
    let crossed = 0
    for (let n = 0; n < 20000; n++) {
      const b = unpackCollider(set.data, n % turned.length, emptyPackedCollider())
      const p = [rand() * 8, 2 + rand() * 3, rand() * 8]
      const d = [rand(), rand() * 0.5, rand()]
      const len = Math.hypot(d[0], d[1], d[2])
      const reach = 12 * Math.abs(rand())
      const u = [d[0] / len, d[1] / len, d[2] / len]
      if (segmentCrossing(p[0], p[1], p[2], u[0], u[1], u[2], reach, b, frame) < 0) continue
      crossed++
      expect(hazeSpanReaches(b, p[0], p[1], p[2], u[0], u[1], u[2], reach), `case ${n}`).toBe(true)
    }
    // Enough of them crossed for the property to mean something.
    expect(crossed).toBeGreaterThan(400)
  })
})

describe('the GLSL twin', () => {
  it('keeps the fixed twin scene: a gauze, a gathered gauze and a cut cloth, from the eye and the beam', () => {
    const list = listFrom(hazeTwinScene(), HAZE_TWIN_EYE, TWIN_MASK_LAYERS)
    expect(list.count).toBe(3)
    // The bench's `?only=haze` runs the GLSL over the same cases and prints both.
    for (const c of hazeTwinCases()) expect(hazeTwinShare(list, c, ATLAS), c.name).toBeCloseTo(c.want, 6)
  })

  it('runs the shadows’ own crossing and share, and adds only the loop', () => {
    // No copy of open(θ), of the crossing or of the mask lookup: those are TRANSMIT_GLSL's.
    expect(HAZE_PLANES_GLSL).not.toContain('float scrimOpen(')
    expect(HAZE_PLANES_GLSL).not.toContain('float segmentCrossing(')
    expect(HAZE_PLANES_GLSL).not.toContain('texelFetch(uMaskAtlas')
    expect(TRANSMIT_GLSL).toContain('float segmentCrossing(')
    expect(TRANSMIT_GLSL).toContain('float crossingShare(')
    // Step for step with the twin above.
    expect(HAZE_PLANES_GLSL).toContain('uniform vec4 uHazePlanes[MAX_HAZE_PLANES * 3];')
    expect(HAZE_PLANES_GLSL).toContain('float t = segmentCrossing(o, d, 1.0e+6, uHazePlanes[k * 3], b, lo, ld);')
    expect(HAZE_PLANES_GLSL).toContain('float keep = crossingShare(uHazePlanes[k * 3 + 2], b, lo, ld, t);')
    expect(HAZE_PLANES_GLSL).toContain('at1[k - 4] = t;')
    // Two vec4s hold the eight planes: the GLSL spells out the halves, so the list's size is pinned.
    expect(MAX_HAZE_PLANES).toBe(8)
    expect(HAZE_PLANES_GLSL).toContain('vec4 s0 = mix(vec4(1.0), share0, step(at0, vec4(t)));')
    expect(HAZE_PLANES_GLSL).toContain('vec4 s1 = mix(vec4(1.0), share1, step(at1, vec4(t)));')
    expect(HAZE_PLANES_GLSL).toContain('if (k >= uHazePlaneCount || (planes >> k) == 0 || keep <= 0.0) break;')
    expect(HAZE_PLANES_GLSL).toContain('if ((planes & (1 << k)) == 0) continue;')
    expect(HAZE_PLANES_GLSL).toContain('vec3 n = vec3(sqrt(max(0.0, 1.0 - a.w * a.w)), 0.0, a.w);')
    expect(HAZE_PLANES_GLSL).toContain('if (min(z0, z1) > b.z || max(z0, z1) < -b.z) continue;')
    // A list of none, or a row naming none, leaves each loop at once: no masked body to pay for.
    expect(HAZE_PLANES_GLSL).toContain('if (k >= uHazePlaneCount) break;')
    expect(HAZE_PLANES_GLSL).toContain('float c = segmentCrossing(p, toApex, reach, a, b, lo, ld);')
    expect(HAZE_PLANES_GLSL).toContain('if (c >= 0.0) keep *= crossingShare(uHazePlanes[k * 3 + 2], b, lo, ld, c);')
    expect(HAZE_PLANES_GLSL).toContain(`#define HAZE_NO_CROSSING ${HAZE_NO_CROSSING.toExponential(1)}`)
  })
})
