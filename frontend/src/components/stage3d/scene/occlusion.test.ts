import { describe, expect, it } from 'vitest'
import { materialScene } from '../profileHarness'
import { boxCollider, elementColliders, MIN_REACH_M, type Collider } from './beamReach'
import { buildElement } from './builders'
import { REACH_EPS_M } from './landing'
import { LIGHT_TEXELS, LightTable, makeLightRow, MAX_LIGHT_BUDGET } from './lightTable'
import {
  COLLIDER_TEXELS,
  colliderSkin,
  cullLightColliders,
  ENTRY_ALL_DIRECTIONS,
  ENTRY_INDEX_BASE,
  entryMayShadow,
  listRowFloats,
  LIST_OVERFLOW,
  LIST_TEXELS,
  makeColliderSet,
  MAX_LIGHT_COLLIDERS,
  OCCLUSION_GLSL,
  OCCLUSION_START_EPS_M,
  occlusionReach,
  packColliders,
  packEntry,
  segmentBlocked,
  unpackCollider,
  unpackEntry,
  type PackedCollider,
} from './occlusion'

/** A seeded generator, so a failure names a case that can be run again. */
function rng(seed: number): () => number {
  let s = seed >>> 0
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0
    return s / 2 ** 32
  }
}

const unpacked = (): PackedCollider => ({ cx: 0, cy: 0, cz: 0, hx: 0, hy: 0, hz: 0, cos: 1, sin: 0, skin: 0 })

/** One collider, packed and read back as the shader reads it. */
function asShaderReads(c: Collider): PackedCollider {
  const set = makeColliderSet(1)
  packColliders([c], set)
  return unpackCollider(set.data, 0, unpacked())
}

/** A light row as the director writes it, packed through the light table into the shader's layout. */
function packLights(
  lights: ReadonlyArray<{ apex: [number, number, number]; dir: [number, number, number]; cosBound: number; near: number }>,
): Float32Array {
  const table = new LightTable(lights.length)
  lights.forEach((l, i) => {
    const row = makeLightRow()
    ;[row.ax, row.ay, row.az] = l.apex
    const len = Math.hypot(...l.dir)
    ;[row.dx, row.dy, row.dz] = l.dir.map((v) => v / len)
    row.cosBound = l.cosBound
    row.near = l.near
    row.r = 1
    table.set(i, row)
  })
  const out = new Float32Array(MAX_LIGHT_BUDGET * LIGHT_TEXELS * 4)
  expect(table.pack(lights.length, out)).toBe(lights.length)
  return out
}

const newLists = () => new Float32Array(LIST_TEXELS * 4 * MAX_LIGHT_BUDGET)

/** Where light row [k]'s entry [j] starts in a list texture's data. */
const entryAt = (k: number, j: number) => k * LIST_TEXELS * 4 + 4 + j * 4

function listOf(lists: Float32Array, k: number): number[] | 'overflow' {
  const n = lists[k * LIST_TEXELS * 4]
  if (n === LIST_OVERFLOW) return 'overflow'
  return Array.from({ length: n }, (_, j) => unpackEntry(lists, entryAt(k, j)).index)
}

describe('the collider texture', () => {
  it('packs a collider in two texels and reads it back as the shader does', () => {
    expect(COLLIDER_TEXELS).toBe(2)
    const random = rng(7)
    const colliders: Collider[] = []
    for (let i = 0; i < 200; i++) {
      const yaw = (random() * 2 - 1) * Math.PI * 1.5
      colliders.push(
        boxCollider(random() * 40 - 20, random() * 10 - 2, random() * -30, random() * 5 + 0.01, random() * 3 + 0.01, random() * 2 + 0.01, yaw, random() * 0.4, random() * 0.6),
      )
    }
    colliders.push(boxCollider(1, 2, 3, 0.5, 0.5, 0.5, 0), boxCollider(1, 2, 3, 0.5, 0.5, 0.5, Math.PI), boxCollider(1, 2, 3, 0.5, 0.5, 0.5, -Math.PI / 2))
    const set = makeColliderSet()
    packColliders(colliders, set)
    expect(set.count).toBe(colliders.length)
    colliders.forEach((c, i) => {
      const b = unpackCollider(set.data, i, unpacked())
      // The centre, the half-extents and the skin go through exactly, as float32s.
      expect([b.cx, b.cy, b.cz, b.hx, b.hy, b.hz]).toEqual([c.cx, c.cy, c.cz, c.hx, c.hy, c.hz].map(Math.fround))
      expect(b.skin).toBe(Math.fround(Math.max(c.skin, c.capSkin)))
      expect(b.skin).toBe(Math.fround(colliderSkin(c)))
      // The turn is folded into [0, π]: the same box, its sine never negative.
      expect(b.sin).toBeGreaterThanOrEqual(0)
      const sameTurn = Math.abs(b.cos - c.cos) < 1e-6 && Math.abs(b.sin - c.sin) < 1e-3
      const halfTurn = Math.abs(b.cos + c.cos) < 1e-6 && Math.abs(b.sin + c.sin) < 1e-3
      expect(sameTurn || halfTurn).toBe(true)
      // So its eight corners are the same eight points.
      const corners = (cos: number, sin: number) => {
        const out: string[] = []
        for (const sx of [-1, 1]) for (const sy of [-1, 1]) for (const sz of [-1, 1]) {
          const lx = sx * c.hx
          const lz = sz * c.hz
          // Out of the box's frame by +yaw, as `beamReach` turns a face's normal.
          out.push([c.cx + cos * lx + sin * lz, c.cy + sy * c.hy, c.cz - sin * lx + cos * lz].map((v) => v.toFixed(2)).join(','))
        }
        return out.sort()
      }
      expect(corners(b.cos, b.sin)).toEqual(corners(c.cos, c.sin))
    })
  })

  it('packs no more than the texture holds, and says how many it dropped', () => {
    const set = makeColliderSet(4)
    packColliders(Array.from({ length: 6 }, (_, i) => boxCollider(i, 0, 0, 0.1, 0.1, 0.1)), set)
    expect(set.count).toBe(4)
    expect(set.dropped).toBe(2)
  })
})

describe("each light's colliders", () => {
  /** Whether any of a grid of points in [c] lies in the cone, past the apex and within [length]. */
  function coneTouches(c: Collider, apex: number[], dir: number[], cosBound: number, length: number): boolean {
    const n = 6
    for (let i = 0; i <= n; i++) for (let j = 0; j <= n; j++) for (let k = 0; k <= n; k++) {
      const lx = (i / n * 2 - 1) * c.hx
      const ly = (j / n * 2 - 1) * c.hy
      const lz = (k / n * 2 - 1) * c.hz
      const x = c.cx + c.cos * lx + c.sin * lz - apex[0]
      const y = c.cy + ly - apex[1]
      const z = c.cz - c.sin * lx + c.cos * lz - apex[2]
      const d = Math.hypot(x, y, z)
      if (d > length || d < 1e-9) continue
      if ((x * dir[0] + y * dir[1] + z * dir[2]) / d >= cosBound) return true
    }
    return false
  }

  it('keeps every collider the cone reaches', () => {
    const random = rng(11)
    for (let trial = 0; trial < 40; trial++) {
      const colliders: Collider[] = []
      for (let i = 0; i < 24; i++) {
        colliders.push(boxCollider(random() * 30 - 15, random() * 8, random() * -30 + 5, random() * 2 + 0.05, random() * 2 + 0.05, random() * 0.5 + 0.02, random() * Math.PI * 2))
      }
      const apex = [random() * 10 - 5, 6 + random() * 3, random() * 6]
      const dir = [random() * 2 - 1, -random() - 0.2, -random()]
      const len = Math.hypot(...dir)
      const unit = dir.map((v) => v / len)
      const cosBound = Math.cos((5 + random() * 30) * (Math.PI / 180))
      const lights = packLights([{ apex: apex as [number, number, number], dir: unit as [number, number, number], cosBound, near: 0.2 }])
      const set = makeColliderSet()
      packColliders(colliders, set)
      const lists = newLists()
      cullLightColliders(lights, 1, set, 40, lists)
      const list = listOf(lists, 0)
      expect(list).not.toBe('overflow')
      colliders.forEach((c, i) => {
        if (coneTouches(c, apex, unit, Math.fround(cosBound), 40.2)) expect(list, `trial ${trial} collider ${i}`).toContain(i)
      })
    }
  })

  it('drops a collider behind the lamp, one wide of its cone and one past its throw', () => {
    const lights = packLights([{ apex: [0, 5, 0], dir: [0, 0, -1], cosBound: Math.cos(Math.PI / 18), near: 0.2 }])
    const colliders = [
      boxCollider(0, 5, -10, 1, 1, 0.1), // on the axis, 10 m out: kept
      boxCollider(0, 5, 3, 0.5, 0.5, 0.5), // behind the lamp
      boxCollider(8, 5, -10, 0.5, 0.5, 0.5), // 39° off the axis of a 10° cone
      boxCollider(0, 5, -60, 1, 1, 1), // past 40 m of throw
    ]
    const set = makeColliderSet()
    packColliders(colliders, set)
    const lists = newLists()
    cullLightColliders(lights, 1, set, 40, lists)
    expect(listOf(lists, 0)).toEqual([0])
  })

  it('tries a long wall in pieces: kept where the cone reaches its far end, dropped where it runs clear', () => {
    // A 10° cone from 5 m up, straight upstage, is 3.5 m across at 20 m out.
    const lights = packLights([{ apex: [0, 5, 0], dir: [0, 0, -1], cosBound: Math.cos(Math.PI / 18), near: 0.2 }])
    const colliders = [
      boxCollider(5, 5, -10, 0.01, 3, 10), // 20 m long, 5 m to the side: clear of the cone all along
      boxCollider(3, 5, -10, 0.01, 3, 10), // 3 m to the side: the cone reaches it from 17 m out
    ]
    const set = makeColliderSet()
    packColliders(colliders, set)
    // The sphere round either wall reaches the cone; only the pieces tell them apart.
    expect(set.spheres[3]).toBeGreaterThan(10)
    const lists = newLists()
    cullLightColliders(lights, 1, set, 40, lists)
    expect(listOf(lists, 0)).toEqual([1])
  })

  it('leaves out a box the aperture sits inside, as beam reach does', () => {
    // A head mounted in a deck's box, its aperture 0.2 m along the axis from the apex.
    const lights = packLights([{ apex: [0, 0.1, 0], dir: [0, 0, -1], cosBound: Math.cos(0.3), near: 0.2 }])
    const set = makeColliderSet()
    packColliders([boxCollider(0, 0, -0.1, 1, 0.3, 1), boxCollider(0, 0, -6, 2, 2, 0.1)], set)
    const lists = newLists()
    cullLightColliders(lights, 1, set, 40, lists)
    expect(listOf(lists, 0)).toEqual([1])
  })

  it('falls back to the landing planes past the cap, and when the scene had more colliders than fit', () => {
    const lights = packLights([
      { apex: [0, 5, 0], dir: [0, 0, -1], cosBound: Math.cos(0.5), near: 0.2 },
      { apex: [0, 5, 0], dir: [0, 0, 1], cosBound: Math.cos(0.5), near: 0.2 },
    ])
    const ahead = Array.from({ length: 5 }, (_, i) => boxCollider(0, 5, -2 - i, 0.5, 0.5, 0.1))
    const set = makeColliderSet()
    packColliders(ahead, set)
    const lists = newLists()
    cullLightColliders(lights, 2, set, 40, lists, 4)
    expect(listOf(lists, 0)).toBe('overflow')
    // The light pointing the other way reaches none, and keeps an empty list rather than falling back.
    expect(listOf(lists, 1)).toEqual([])
    cullLightColliders(lights, 2, set, 40, lists, 5)
    expect(listOf(lists, 0)).toEqual([0, 1, 2, 3, 4])

    const small = makeColliderSet(3)
    packColliders(ahead, small)
    cullLightColliders(lights, 2, small, 40, lists)
    expect(listOf(lists, 0)).toBe('overflow')
    expect(listOf(lists, 1)).toBe('overflow')
    expect(1 + MAX_LIGHT_COLLIDERS).toBe(LIST_TEXELS)
    // An overflowed row uploads its count's texel and nothing more.
    expect(listRowFloats(lists, 0)).toBe(4)
  })

  it("gives each entry its sphere's cone from the apex, which never skips a fragment the box blocks", () => {
    const random = rng(31)
    let blocked = 0
    let skipped = 0
    for (let trial = 0; trial < 60; trial++) {
      const colliders = Array.from({ length: 12 }, () =>
        boxCollider(random() * 10 - 5, random() * 4, random() * -12, random() * 1.5 + 0.05, random() * 1.5 + 0.05, random() * 0.6 + 0.02, random() * Math.PI * 2),
      )
      const apex: [number, number, number] = [random() * 4 - 2, 6, 3]
      const lights = packLights([{ apex, dir: [0, -0.5, -1], cosBound: Math.cos(0.6), near: 0.2 }])
      const set = makeColliderSet()
      packColliders(colliders, set)
      const lists = newLists()
      cullLightColliders(lights, 1, set, 40, lists)
      const list = listOf(lists, 0)
      if (list === 'overflow') continue
      expect(listRowFloats(lists, 0)).toBe(4 * (1 + list.length))
      for (let f = 0; f < 40; f++) {
        // A point on the floor or a wall somewhere in front of the lamp.
        const p = [random() * 12 - 6, random() * 4, -random() * 14]
        const v = p.map((x, i) => x - apex[i])
        const dist = Math.hypot(...v)
        const fromApex = v.map((x) => x / dist)
        list.forEach((index, j) => {
          const box = unpackCollider(set.data, index, unpacked())
          if (!segmentBlocked(p[0], p[1], p[2], -fromApex[0], -fromApex[1], -fromApex[2], dist - 0.25, box)) return
          blocked++
          expect(entryMayShadow(lists, entryAt(0, j), fromApex[0], fromApex[1], fromApex[2], dist), `trial ${trial}`).toBe(true)
        })
        list.forEach((_, j) => {
          if (!entryMayShadow(lists, entryAt(0, j), fromApex[0], fromApex[1], fromApex[2], dist)) skipped++
        })
      }
    }
    // The skip earns its place: most entries never stand between a fragment and the lamp.
    expect(blocked).toBeGreaterThan(50)
    expect(skipped).toBeGreaterThan(blocked * 4)
  })

  it('packs an entry in one texel and reads it back as the shader does', () => {
    const random = rng(37)
    const texel = new Float32Array(4)
    for (let i = 0; i < 2000; i++) {
      const d = [random() * 2 - 1, random() * 2 - 1, random() * 2 - 1]
      const len = Math.hypot(...d)
      const index = Math.floor(random() * ENTRY_INDEX_BASE)
      const near = random() * 120
      packEntry(d[0] / len, d[1] / len, d[2] / len, 0.9, index, near, texel, 0)
      const e = unpackEntry(texel, 0)
      // The direction to a few millionths, through float32; the index exactly; the near side
      // floored to the centimetre, so never further than it is.
      expect(Math.hypot(e.dx - d[0] / len, e.dy - d[1] / len, e.dz - d[2] / len)).toBeLessThan(5e-6)
      expect(e.index).toBe(index)
      expect(e.nearM).toBeLessThanOrEqual(near)
      expect(near - e.nearM).toBeLessThan(0.01 + 1e-9)
      expect(e.cos).toBe(Math.fround(0.9))
    }
  })

  it('marks an entry whose sphere holds the apex as every direction', () => {
    const lights = packLights([{ apex: [0, 1, 0], dir: [0, 0, -1], cosBound: Math.cos(0.5), near: 0.05 }])
    const set = makeColliderSet()
    // A long slab the lamp hangs beside, its aperture outside the box but inside its sphere.
    packColliders([boxCollider(0, 0.5, -3, 2, 0.4, 3)], set)
    const lists = newLists()
    cullLightColliders(lights, 1, set, 40, lists)
    expect(listOf(lists, 0)).toEqual([0])
    expect(lists[entryAt(0, 0) + 2]).toBe(ENTRY_ALL_DIRECTIONS)
    expect(entryMayShadow(lists, entryAt(0, 0), 0, 1, 0, 0.01)).toBe(true)
  })
})

describe('the segment test', () => {
  /** Where [p] lies in [b]'s own frame. */
  function local(b: PackedCollider, p: number[]): number[] {
    const rx = p[0] - b.cx
    const rz = p[2] - b.cz
    return [b.cos * rx - b.sin * rz, p[1] - b.cy, b.sin * rx + b.cos * rz]
  }

  function inside(b: PackedCollider, p: number[], grow: number): boolean {
    const l = local(b, p)
    return Math.abs(l[0]) <= b.hx + grow && Math.abs(l[1]) <= b.hy + grow && Math.abs(l[2]) <= b.hz + grow
  }

  function randomBox(random: () => number): PackedCollider {
    return asShaderReads(
      boxCollider(random() * 4 - 2, random() * 4 - 2, random() * 4 - 2, random() * 1.5 + 0.1, random() * 1.5 + 0.1, random() * 1.5 + 0.1, random() * Math.PI * 4 - Math.PI * 2, random() * 0.3, random() * 0.3),
    )
  }

  function randomDir(random: () => number): number[] {
    const d = [random() * 2 - 1, random() * 2 - 1, random() * 2 - 1]
    const len = Math.hypot(...d)
    return d.map((v) => v / len)
  }

  it('is blocked from outside exactly where a march along the segment enters the box', () => {
    const random = rng(23)
    const steps = 3000
    const margin = 2e-3
    let decided = 0
    for (let trial = 0; trial < 600; trial++) {
      const b = randomBox(random)
      const p = [random() * 12 - 6, random() * 12 - 6, random() * 12 - 6]
      if (inside(b, p, 0.05)) continue
      const d = randomDir(random)
      const tMax = random() * 10
      let surely = false
      let maybe = false
      for (let i = 0; i <= steps; i++) {
        const t = (tMax * i) / steps
        const q = [p[0] + d[0] * t, p[1] + d[1] * t, p[2] + d[2] * t]
        if (inside(b, q, -margin)) surely = true
        if (inside(b, q, margin)) maybe = true
      }
      if (surely !== maybe) continue
      decided++
      expect(segmentBlocked(p[0], p[1], p[2], d[0], d[1], d[2], tMax, b), `trial ${trial}`).toBe(surely)
    }
    expect(decided).toBeGreaterThan(400)
  })

  it('from inside, is blocked only past the skin of the face the segment leaves by', () => {
    const random = rng(29)
    let decided = 0
    let blocked = 0
    for (let trial = 0; trial < 600; trial++) {
      const b = randomBox(random)
      const l = [(random() * 2 - 1) * b.hx * 0.98, (random() * 2 - 1) * b.hy * 0.98, (random() * 2 - 1) * b.hz * 0.98]
      // Back into the world, by +yaw.
      const p = [b.cx + b.cos * l[0] + b.sin * l[2], b.cy + l[1], b.cz - b.sin * l[0] + b.cos * l[2]]
      const d = randomDir(random)
      // March out to find the face it leaves by, and the start's depth behind that face.
      let t = 0
      let q = p
      while (inside(b, q, 0)) {
        t += 5e-4
        q = [p[0] + d[0] * t, p[1] + d[1] * t, p[2] + d[2] * t]
      }
      const ql = local(b, q)
      const out = [Math.abs(ql[0]) - b.hx, Math.abs(ql[1]) - b.hy, Math.abs(ql[2]) - b.hz]
      const axis = out.indexOf(Math.max(...out))
      // Too near an edge to say which face, or too near the skin to say which side: not decided.
      if (out.filter((o) => o > -2e-3).length > 1) continue
      const half = [b.hx, b.hy, b.hz][axis]
      const depth = half - Math.sign(ql[axis]) * l[axis]
      if (Math.abs(depth - b.skin) < 5e-3) continue
      decided++
      const want = depth > b.skin
      if (want) blocked++
      expect(segmentBlocked(p[0], p[1], p[2], d[0], d[1], d[2], 50, b), `trial ${trial}`).toBe(want)
      // A segment that ends before it leaves the box is never blocked by it, whatever the depth: the
      // lamp is inside it too.
      expect(segmentBlocked(p[0], p[1], p[2], d[0], d[1], d[2], t * 0.9, b)).toBe(false)
    }
    expect(decided).toBeGreaterThan(400)
    expect(blocked).toBeGreaterThan(50)
    expect(decided - blocked).toBeGreaterThan(50)
  })

  it('lights a pleat in its trough and a wall on its face, and keeps the floor under a deck dark', () => {
    // A drape's box 10 cm deep, skin the pleat's depth: a trough 9 cm behind the front face is lit.
    const drape = asShaderReads(boxCollider(0, 2, -5, 3, 2, 0.05, 0, 0.13, 0.13))
    expect(segmentBlocked(0.3, 2, -5.04, 0.2, 0.3, 0.93, 20, drape)).toBe(false)
    // A wall's 2 cm slab, the fragment on its face.
    const wall = asShaderReads(boxCollider(0, 2, -6.01, 5, 2, 0.01))
    expect(segmentBlocked(1, 1, -6, 0, 0.6, 0.8, 20, wall)).toBe(false)
    // A deck 0.6 m high: the floor under it is 0.6 m behind the face the segment leaves by.
    const deck = asShaderReads(boxCollider(0, 0.3, -3, 1, 0.3, 1))
    expect(segmentBlocked(0.2, 0, -3, 0, 1, 0, 20, deck)).toBe(true)
    // A box ahead within a millimetre of the start is one the fragment is on, and decided by depth.
    const ahead = asShaderReads(boxCollider(0, 1 + OCCLUSION_START_EPS_M / 2 + 0.5, 0, 1, 0.5, 1))
    expect(segmentBlocked(0, 1, 0, 0, 1, 0, 20, ahead)).toBe(true)
  })

  it('reaches from the fragment to just short of the aperture', () => {
    // 10 m from the apex, 0.6 m to the aperture along the axis, 20° off it.
    const c = Math.cos(Math.PI / 9)
    expect(occlusionReach(10, c, 0.6)).toBeCloseTo(10 - 0.6 / c - MIN_REACH_M, 9)
  })

  it('writes the GLSL from the same constants and in the same steps as the twin', () => {
    expect(OCCLUSION_GLSL).toContain(`#define OCCLUSION_START_EPS ${OCCLUSION_START_EPS_M.toFixed(4)}`)
    expect(OCCLUSION_GLSL).toContain(`#define OCCLUSION_MIN_REACH ${MIN_REACH_M.toFixed(4)}`)
    expect(OCCLUSION_GLSL).toContain('float s = sqrt(max(0.0, 1.0 - a.w * a.w));')
    expect(OCCLUSION_GLSL).toContain('if (tNear > tFar || tFar <= 0.0 || tNear >= tMax) return false;')
    expect(OCCLUSION_GLSL).toContain('if (tNear > OCCLUSION_START_EPS) return true;')
    expect(OCCLUSION_GLSL).toContain('if (tFar >= tMax) return false;')
    expect(OCCLUSION_GLSL).toContain('return depth > b.w;')
    expect(OCCLUSION_GLSL).toContain('if (n < 0.0) return behindLanding(landing, p, REACH_EPS);')
    expect(OCCLUSION_GLSL).toContain('float tMax = dist - near / max(cosAxis, 1e-6) - OCCLUSION_MIN_REACH;')
    expect(OCCLUSION_GLSL).toContain('if (dot(-toLamp, normalize(u)) < entry.z) continue;')
    expect(OCCLUSION_GLSL).toContain(`float q = floor(entry.w / ${ENTRY_INDEX_BASE}.0);`)
    expect(OCCLUSION_GLSL).toContain('if (dist < q / 100.0) continue;')
  })
})

describe('the shadow scene', () => {
  it("throws the flat's shadow on the wall, clear of the flat, and lights the wall beside it", () => {
    const { spots, elements } = materialScene('shadow')
    const colliders = elements.flatMap((e) => elementColliders(e, buildElement(e)))
    // Lighting (x, y, z) → three (x, z, −y).
    const three = (p: { x: number; y: number; z: number }) => [p.x, p.z, -p.y]
    const spot = spots[0]
    const apex = three(spot.from)
    const at = three(spot.at)
    const dir = at.map((v, i) => v - apex[i])
    const lights = packLights([{ apex: apex as [number, number, number], dir: dir as [number, number, number], cosBound: Math.cos((spot.beamDeg / 2) * (Math.PI / 180)), near: 0.1 }])
    const set = makeColliderSet()
    packColliders(colliders, set)
    const lists = newLists()
    cullLightColliders(lights, 1, set, 40, lists)
    const list = listOf(lists, 0)
    if (list === 'overflow') throw new Error('overflow')
    const occluded = (p: number[]) => {
      const v = p.map((x, i) => apex[i] - x)
      const dist = Math.hypot(...v)
      const toLamp = v.map((x) => x / dist)
      const cosAxis = -(toLamp[0] * dir[0] + toLamp[1] * dir[1] + toLamp[2] * dir[2]) / Math.hypot(...dir)
      const tMax = occlusionReach(dist, cosAxis, 0.1)
      return list.some((k) => segmentBlocked(p[0], p[1], p[2], toLamp[0], toLamp[1], toLamp[2], tMax, unpackCollider(set.data, k, unpacked())))
    }
    // The wall's face is 5.95 m upstage. Behind the flat, as the lamp sees it: dark.
    expect(occluded(three({ x: -1.4, y: 5.95, z: 1 }))).toBe(true)
    expect(occluded(three({ x: -1.0, y: 5.95, z: 1.5 }))).toBe(true)
    // Beside the shadow and above it: lit.
    expect(occluded(three({ x: 1.5, y: 5.95, z: 1 }))).toBe(false)
    expect(occluded(three({ x: -1.4, y: 5.95, z: 2.4 }))).toBe(false)
    // The flat's own face, towards the lamp: lit.
    expect(occluded(three({ x: 0, y: 4.475, z: 1.2 }))).toBe(false)
    // Its shadow is clear of it from the house: the flat spans x ±0.6, its shadow −2.28…−0.63.
    expect(occluded(three({ x: -0.5, y: 5.95, z: 1 }))).toBe(false)
    expect(occluded(three({ x: -0.7, y: 5.95, z: 1 }))).toBe(true)
    expect(occluded(three({ x: -2.2, y: 5.95, z: 0.5 }))).toBe(true)
    expect(occluded(three({ x: -2.4, y: 5.95, z: 0.5 }))).toBe(false)
    expect(REACH_EPS_M).toBeGreaterThan(OCCLUSION_START_EPS_M)
  })
})
