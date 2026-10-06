import { describe, expect, it } from 'vitest'
import { packBlades } from '../beamMask'
import { NO_SIDE_X } from '../beamShaders'
import type { StageElementDto } from '../../../api/stageElementApi'
import { beamReach, boxCollider, elementColliders, partBox, type BeamHit } from './beamReach'
import { buildElement } from './builders'
import { packGobos } from '../goboLayers'
import {
  BEAM_FRAME_GLSL,
  DOF_STEPS,
  EDGE_IRIS_STEPS,
  FOCUS_CM_BASE,
  frameFromBasis,
  frameInBasis,
  LIGHT_TEXELS,
  LightTable,
  makeLightRow,
  packEdgeIris,
  packFocus,
  UNPACK_EDGE_IRIS_GLSL,
  UNPACK_FOCUS_GLSL,
} from './lightTable'
import { LAND_NONE, LAND_UP, packLanding, REACH_EPS_M } from './landing'
import { CYC_DEPTH_MAX_M, PLEAT_DEPTH_MAX_M, PLEAT_DEPTH_MIN_M, pleatShape } from './pleat'
import type { Facing, PartGeometry } from './sceneParts'
import { partGeometry } from './StageSceneElements'
import { HAZE_TIERS, HazeGovernor, MAX_SAMPLE_MS, MIN_SAMPLES, RECOVER_AFTER_MS } from './hazeGovernor'
import { MAX_LIGHT_COLLIDERS } from './occlusion'
import { beamClipFor, drawsRoom, hazeClipFor, sceneBuilds, sceneColliders, sceneElementBounds } from './stageSurfaces'
import {
  BOX_SHADOW_CAPS,
  DEFAULT_BOX_SHADOWS,
  DEFAULT_GOBO_SURFACES,
  DEFAULT_SCENE_LAYERS,
  elementInLayers,
  goboLandsOnSurfaces,
  isBoxShadows,
  isGoboSurfaces,
  parseSceneLayers,
} from './sceneView'

function element(fields: Partial<StageElementDto>): StageElementDto {
  return {
    id: 1, uuid: 'e', name: 'E', kind: 'OBJECT', layer: 'VENUE',
    positionX: 0, positionY: 0, positionZ: 0, yawDeg: 0, widthM: 1, depthM: 1, heightM: 1,
    finishColour: null, finishPattern: null, emissive: false, params: {}, hidden: false, sortOrder: 0,
    ...fields,
  }
}

const hit = (): BeamHit => ({ t: 0, nx: 0, ny: 0, nz: 0, skin: 0, collider: null })

describe('the axial reach (stage-view plan session 3)', () => {
  const floor = boxCollider(0, -0.01, 0, 10, 0.01, 10)

  it('answers the first surface on the axis and the face it hit, towards the light', () => {
    const out = hit()
    expect(beamReach(0, 5, 0, 0, -1, 0, [floor], 40, out)).toBe(true)
    expect(out.t).toBeCloseTo(5, 9)
    expect([out.nx, out.ny, out.nz]).toEqual([0, 1, 0])
    // A wall in front of the floor stops the beam first.
    const wall = boxCollider(0, 2, -2, 5, 2, 0.05)
    const d = Math.SQRT1_2
    expect(beamReach(0, 3, 0, 0, -d, -d, [floor, wall], 40, out)).toBe(true)
    expect(out.t).toBeCloseTo(1.95 / d, 6)
    expect(out.nz).toBe(1)
  })

  it('misses what lies beyond its range, behind it, or around the lens it is mounted on', () => {
    const out = hit()
    expect(beamReach(0, 50, 0, 0, -1, 0, [floor], 40, out)).toBe(false)
    expect(beamReach(0, 5, 0, 0, 1, 0, [floor], 40, out)).toBe(false)
    // A head inside a deck's box is mounted on it, not blocked by it.
    const deck = boxCollider(0, -0.5, 0, 2, 0.5, 2)
    expect(beamReach(0, -0.2, 0, 0, -1, 0, [deck], 40, out)).toBe(false)
  })

  it('turns a box with its yaw, as the region shaders do', () => {
    // A thin wall turned 90°: its face now points along x.
    const turned = boxCollider(3, 1, 0, 2, 1, 0.05, Math.PI / 2)
    const out = hit()
    expect(beamReach(0, 1, 0, 1, 0, 0, [turned], 40, out)).toBe(true)
    expect(out.t).toBeCloseTo(2.95, 6)
    expect(out.nx).toBeCloseTo(-1, 9)
  })

  it("places an element's colliders by its pose — a room's floor at its base, lit from above", () => {
    const hall = element({ kind: 'ROOM', positionY: -9.2, positionZ: -0.95, widthM: 8.6, depthM: 18.4, heightM: 5.55 })
    const colliders = elementColliders(hall, buildElement(hall))
    expect(colliders).toHaveLength(6)
    const out = hit()
    // From 3 m over the stalls, straight down: the hall floor, 3.95 m below and a hair more.
    expect(beamReach(0, 3, 9.2, 0, -1, 0, colliders, 40, out)).toBe(true)
    expect(out.t).toBeCloseTo(3.954, 3)
    expect(out.ny).toBe(1)
  })
})

describe('a collider holds what it draws (stage-light plan D1)', () => {
  const FACINGS: Facing[] = ['up', 'down', 'upstage', 'downstage', 'left', 'right']
  // Cloth at every depth the drape's `depthM` draws, the cyc's stretched ripple, and past both ends.
  const cloth = (depthM: number, role = 'BACKCLOTH'): PartGeometry => ({
    shape: 'pleat', w: 2.3, h: 4, pleat: pleatShape(element({ kind: 'DRAPE', uuid: `cloth-${depthM}-${role}`, depthM, params: { role } })),
  })
  const SHAPES: PartGeometry[] = [
    { shape: 'box', w: 1.2, d: 0.6, h: 0.9 },
    { shape: 'cylinder', rTop: 0.2, rBottom: 0.2, h: 3 },
    { shape: 'cylinder', rTop: 0.14, rBottom: 0.25, h: 0.5 },
    { shape: 'disc', r: 0.5, d: 0.05 },
    ...[0.005, 0.05, 0.1, 0.2, 1].map((d) => cloth(d)),
    cloth(0.1, 'CYC'),
    { ...(cloth(0.1) as Extract<PartGeometry, { shape: 'pleat' }>), anchor: 'left' },
    ...FACINGS.map((facing): PartGeometry => ({ shape: 'quad', w: 2, h: 1.5, facing })),
  ]
  // The lighting-frame axes a beam lands on to light the part: every face of a solid, the broad
  // faces of a thin one. A beam landing on a cloth's or a disc's edge grazes along it, and the part
  // shadows itself past its first fold — session 3's boxes, not the skin, are what draw that.
  const LIT_AXES: Record<PartGeometry['shape'], number[]> = { box: [0, 1, 2], cylinder: [0, 1, 2], disc: [1], pleat: [1], quad: [] }
  const QUAD_AXIS: Record<Facing, number> = { left: 0, right: 0, upstage: 1, downstage: 1, up: 2, down: 2 }

  for (const shape of SHAPES) {
    const label = shape.shape === 'quad' ? ` facing ${shape.facing}` : shape.shape === 'pleat' ? ` ${(2 * shape.pleat.amplitudeM).toFixed(3)} m deep` : ''
    it(`holds a ${shape.shape}${label} within its skin`, () => {
      const g = partGeometry(shape)
      const box = partBox(shape)
      const centre = [box.ox, box.oy, box.oz]
      const half = [box.hx, box.hy, box.hz]
      const pos = g.attributes.position
      const nor = g.attributes.normal
      const axes = shape.shape === 'quad' ? [QUAD_AXIS[shape.facing]] : LIT_AXES[shape.shape]
      let deepest = 0
      let deepestCap = 0
      for (let i = 0; i < pos.count; i++) {
        // three.js (x, y, z) is the lighting frame's (x, −y, z) turned: lighting = (x, −z, y).
        const p = [pos.getX(i), -pos.getZ(i), pos.getY(i)]
        const n = [nor.getX(i), -nor.getZ(i), nor.getY(i)]
        // The positions are float32.
        for (let k = 0; k < 3; k++) expect(Math.abs(p[k] - centre[k])).toBeLessThanOrEqual(half[k] + 1e-6)
        for (const k of axes) {
          for (const side of [1, -1]) {
            // A cloth is drawn from both sides, so either side of it faces either face.
            const faces = shape.shape === 'pleat' || side * n[k] > 1e-6
            if (!faces) continue
            const depth = side * (centre[k] + side * half[k] - p[k])
            if (k === 2) deepestCap = Math.max(deepestCap, depth)
            else deepest = Math.max(deepest, depth)
          }
        }
      }
      expect(deepest).toBeLessThanOrEqual(box.skin + 1e-6)
      expect(deepestCap).toBeLessThanOrEqual(box.capSkin + 1e-6)
      g.dispose()
    })
  }

  it("takes a cloth's skin from its folds, a column's sides from its radius and a shade's top from its height", () => {
    const pleat = pleatShape(element({ kind: 'DRAPE', depthM: 0.12 }))
    expect(partBox({ shape: 'pleat', w: 2, h: 3, pleat })).toMatchObject({ hy: 0.06, skin: 0.12 + REACH_EPS_M })
    expect(partBox({ shape: 'cylinder', rTop: 0.3, rBottom: 0.3, h: 2 })).toMatchObject({ skin: 0.3, capSkin: REACH_EPS_M })
    expect(partBox({ shape: 'cylinder', rTop: 0.1, rBottom: 0.3, h: 0.5 })).toMatchObject({ skin: 0.3, capSkin: 0.5 })
    expect(partBox({ shape: 'box', w: 1, d: 1, h: 1 })).toMatchObject({ skin: REACH_EPS_M, capSkin: REACH_EPS_M })
    const drape = (depthM: number, role = 'BACKCLOTH') => element({ kind: 'DRAPE', widthM: 4, heightM: 3, depthM, params: { role } })
    const skinOf = (e: StageElementDto) => elementColliders(e, buildElement(e))[0].skin
    // The drape's depth is its folds', within what a drape can hang at; a cyc is stretched.
    expect(skinOf(drape(0.15))).toBeCloseTo(0.15 + REACH_EPS_M, 12)
    expect(skinOf(drape(0.001))).toBeCloseTo(PLEAT_DEPTH_MIN_M + REACH_EPS_M, 12)
    expect(skinOf(drape(4))).toBeCloseTo(PLEAT_DEPTH_MAX_M + REACH_EPS_M, 12)
    expect(skinOf(drape(0.15, 'CYC'))).toBeCloseTo(CYC_DEPTH_MAX_M + REACH_EPS_M, 12)
  })
})

describe('where a beam lands (stage-light plan D1)', () => {
  it("hands on the skin of the face it hit: a drum's side its radius, its flat top no more than a face's", () => {
    // A round rostrum 2 m across and 0.3 m tall, standing on the deck.
    const drum = element({ kind: 'OBJECT', widthM: 2, depthM: 2, heightM: 0.3, params: { shape: 'CYLINDER' } })
    const colliders = elementColliders(drum, buildElement(drum))
    const out = hit()
    expect(beamReach(0, 5, 0, 0, -1, 0, colliders, 40, out)).toBe(true)
    expect(out.ny).toBe(1)
    expect(out.skin).toBe(REACH_EPS_M)
    expect(beamReach(0, 0.15, 5, 0, 0, -1, colliders, 40, out)).toBe(true)
    expect(out.nz).toBe(1)
    expect(out.skin).toBe(1)
  })

  const face = (skin: number) => ({ px: 0, py: 0.5, pz: 0, nx: 0, ny: 1, nz: 0, skin })

  it('moves a plane back by its face\'s skin beyond REACH_EPS, and an ordinary face not at all', () => {
    const out = [0, 0, 0, 0]
    packLanding(face(REACH_EPS_M), null, out, 0)
    expect(out).toEqual([LAND_UP, 0.5, LAND_NONE, 1])
    packLanding(face(0.05 + REACH_EPS_M), { px: 0, py: 0, pz: 2, nx: 0, ny: 0, nz: 1, skin: 0.2 }, out, 0)
    expect(out[1]).toBeCloseTo(0.5 - 0.05, 9)
    expect(out[3]).toBeCloseTo(2 - (0.2 - REACH_EPS_M), 9)
  })

  it('never moves a plane forward, whatever skin it is handed', () => {
    const out = [0, 0, 0, 0]
    packLanding(face(0), null, out, 0)
    expect(out[1]).toBe(0.5)
  })
})

describe('the light table', () => {
  it('packs every lit slot while they fit, and the brightest under the budget, in slot order', () => {
    const table = new LightTable(4)
    const set = (i: number, level: number) =>
      table.set(i, { ...makeLightRow(), cosBound: 0.9, r: level, g: level, b: level })
    set(0, 0.2)
    set(1, 0.9)
    set(3, 0.5)
    const floats = LIGHT_TEXELS * 4
    const out = new Float32Array(4 * floats)
    expect(table.pack(64, out)).toBe(3)
    expect(table.dirty).toBe(false)
    // Two under a budget of two: slots 1 and 3, the brightest, kept in slot order.
    expect(table.pack(2, out)).toBe(2)
    expect(out[8]).toBeCloseTo(0.9, 6)
    expect(out[floats + 8]).toBeCloseTo(0.5, 6)
    table.clear(1)
    expect(table.dirty).toBe(true)
    expect(table.litCount()).toBe(2)
  })

  it("carries no reach plane for a beam that reaches nothing, and the hit's plane for one that does", () => {
    const table = new LightTable(1)
    const lit = { ...makeLightRow(), ay: 4, cosBound: 0.9, r: 1, g: 1, b: 1 }
    table.set(0, lit)
    expect(Array.from(table.staged.subarray(12, 16))).toEqual([LAND_NONE, -1, LAND_NONE, -1])
    table.set(0, { ...lit, hit: { px: 0, py: 0.5, pz: 0, nx: 0, ny: 1, nz: 0, skin: REACH_EPS_M } })
    expect(Array.from(table.staged.subarray(12, 16))).toEqual([LAND_UP, 0.5, LAND_NONE, 1])
    table.set(0, { ...lit, hit: { px: 0, py: 0.5, pz: 0, nx: 0, ny: 1, nz: 0, skin: REACH_EPS_M }, edgeHit: { px: 0, py: 0, pz: 2, nx: 0, ny: 0, nz: 1, skin: REACH_EPS_M } })
    expect(Array.from(table.staged.subarray(12, 14))).toEqual([LAND_UP, 0.5])
    expect(table.staged[14]).toBeCloseTo(Math.PI / 2, 6)
    expect(table.staged[15]).toBe(2)
  })

  it("carries the beam's frame, its gobos and its aperture: frame angle, gobo layers, tan, near, blades, aspect", () => {
    const table = new LightTable(1)
    const [bladesA, bladesB] = packBlades([
      { depth: 0.25, angleDeg: 0 },
      { depth: 0, angleDeg: 0 },
      { depth: 0.5, angleDeg: -12 },
      { depth: 1, angleDeg: 30 },
    ])
    const gobos = packGobos(5, 12, 1, 1.25)
    table.set(0, { ...makeLightRow(), r: 1, rx: 0, ry: 0, rz: 1, tanHalf: 0.17, near: 0.5, iris: 0.4, aspect: -0.25, bladesA, bladesB, gobos })
    // Texel 4 is the frame — (cos, sin) in the axis's basis — and the gobo layers, every packed word
    // held exactly by a float32; texel 5 the aperture — near, the (top, bottom) blades, aspect (an
    // oval is negative), the (left, right) blades.
    const row = Array.from(table.staged.subarray(16, 24))
    expect(row.slice(2)).toEqual([gobos, Math.fround(0.17), 0.5, bladesA, -0.25, bladesB])
    expect(Math.fround(gobos)).toBe(gobos)
    // The frame comes back as the head's right axis, from the float32s the shader reads.
    const [ux, uy, uz] = frameFromBasis(0, -1, 0, row[0], row[1])
    expect([ux, uy, uz].map((v) => Number(v.toFixed(5)) + 0)).toEqual([0, 0, 1])
  })

  it("turns any right axis into a direction in the beam's basis and back, whichever side of the basis's seam", () => {
    // The seam of the basis is the axis crossing z = 0: a beam straight down, one level across the
    // stage (z exactly 0 and −0), one a hair either side, one almost straight back.
    const axes: Array<[number, number, number]> = [
      [0, -1, 0], [1, 0, 0], [0, 0, -1], [0.6, -0.8, 0], [0.6, -0.8, -0], [0.6, -0.8, 1e-9], [0.6, -0.8, -1e-9],
      [0.3, -0.5, 0.81], [-0.2, 0.1, -0.97], [0, 0, 1], [1e-30, -1, -1e-30],
    ]
    const rights: Array<[number, number, number]> = [[1, 0, 0], [0, 0, 1], [0.3, 0.9, -0.2], [0, 1, 0]]
    for (const [ax, ay, az] of axes) {
      const n = Math.hypot(ax, ay, az)
      const d = [Math.fround(ax / n), Math.fround(ay / n), Math.fround(az / n)] as const
      for (const r of rights) {
        // The right axis at right angles to the beam, normalised: what the old texel 4 meant.
        const along = r[0] * d[0] + r[1] * d[1] + r[2] * d[2]
        const p = [r[0] - along * d[0], r[1] - along * d[1], r[2] - along * d[2]]
        const len = Math.hypot(p[0], p[1], p[2])
        if (len < 1e-3) continue
        const cs = new Float32Array(2)
        frameInBasis(d[0], d[1], d[2], r[0], r[1], r[2], cs)
        const [ux, uy, uz, vx, vy, vz] = frameFromBasis(d[0], d[1], d[2], cs[0], cs[1])
        expect(ux).toBeCloseTo(p[0] / len, 5)
        expect(uy).toBeCloseTo(p[1] / len, 5)
        expect(uz).toBeCloseTo(p[2] / len, 5)
        // And v is axis × u, as the shader's cross makes it: at right angles to both.
        expect(vx * ux + vy * uy + vz * uz).toBeCloseTo(0, 5)
        expect(vx * d[0] + vy * d[1] + vz * d[2]).toBeCloseTo(0, 5)
      }
    }
  })

  it('writes the frame GLSL from the same basis as the twin', () => {
    expect(BEAM_FRAME_GLSL).toContain('float s = n.z >= 0.0 ? 1.0 : -1.0;')
    expect(BEAM_FRAME_GLSL).toContain('b1 = vec3(1.0 + s * n.x * n.x * a, s * b, -s * n.x);')
    expect(BEAM_FRAME_GLSL).toContain('b2 = vec3(b, s + n.y * n.y * a, -n.y);')
    expect(BEAM_FRAME_GLSL).toContain('bx = cs.x * b1 + cs.y * b2;')
    expect(BEAM_FRAME_GLSL).toContain('by = cross(axis, bx);')
    // A stored pair, not an angle: no trig per light.
    expect(BEAM_FRAME_GLSL).not.toMatch(/\b(cos|sin)\(/)
  })

  it('packs the edge and the iris into texel 2, and the shader unpacks them exactly', () => {
    const table = new LightTable(1)
    table.set(0, { ...makeLightRow(), r: 1, edge: 0.8, iris: 0.4 })
    const packed = table.staged[11]
    expect(packed).toBe(packEdgeIris(0.8, 0.4))
    expect(Math.fround(packed)).toBe(packed)
    // The GLSL's arithmetic, in doubles: a power-of-two division is exact in a float32 too.
    const iq = Math.floor(packed / 1024)
    expect((packed - iq * 1024) / EDGE_IRIS_STEPS).toBeCloseTo(0.8, 3)
    expect(iq / EDGE_IRIS_STEPS).toBeCloseTo(0.4, 3)
    expect(UNPACK_EDGE_IRIS_GLSL).toContain(`${EDGE_IRIS_STEPS}.0`)
  })

  it('packs the focal distance and the depth of field into texel 0, and the shader unpacks them', () => {
    const table = new LightTable(1)
    table.set(0, { ...makeLightRow(), r: 1, focusDist: 24.07, dof: 3 })
    const packed = table.staged[3]
    expect(packed).toBe(packFocus(24.07, 3))
    expect(Math.fround(packed)).toBe(packed)
    // The GLSL's arithmetic: the depth of field above a power of two, the distance to the centimetre.
    const dq = Math.floor(packed / FOCUS_CM_BASE)
    expect(dq / DOF_STEPS).toBe(3)
    expect((packed - dq * FOCUS_CM_BASE) / 100).toBeCloseTo(24.07, 6)
    expect(UNPACK_FOCUS_GLSL).toContain(`${FOCUS_CM_BASE}.0`)
    expect(UNPACK_FOCUS_GLSL).toContain(`${DOF_STEPS}.0`)
  })

  it('keeps "always sharp" negative, and the largest packing exact in a float32', () => {
    expect(packFocus(-1, 3)).toBe(-1)
    expect(packFocus(Number.NaN, 3)).toBe(-1)
    const top = packFocus(1000, 1000)
    expect(top).toBe(2 ** 24 - 1)
    expect(Math.fround(top)).toBe(top)
    expect(packFocus(6, Number.NaN)).toBe(600)
  })

  it('treats a dark light as off', () => {
    const table = new LightTable(1)
    table.set(0, { ...makeLightRow(), ay: 4, cosBound: 0.9 })
    expect(table.litCount()).toBe(0)
  })
})

describe('haze degrades before frame rate', () => {
  it('steps the march down a tier on a slow run, and back up only after a fast while', () => {
    const governor = new HazeGovernor()
    let now = 0
    let changed = null
    for (let i = 0; i < MIN_SAMPLES; i++) changed = governor.sample(40, (now += 40)) ?? changed
    expect(changed).toEqual(HAZE_TIERS[1])
    // A fast run must hold for the recovery time before a tier comes back.
    changed = null
    for (let i = 0; i < MIN_SAMPLES; i++) changed = governor.sample(10, (now += 10)) ?? changed
    expect(changed).toBeNull()
    for (let t = 0; t < RECOVER_AFTER_MS; t += 10) changed = governor.sample(10, (now += 10)) ?? changed
    expect(changed).toEqual(HAZE_TIERS[0])
  })

  it('never passes its last tier, and takes a long gap as a pause rather than a frame', () => {
    const governor = new HazeGovernor()
    let now = 0
    for (let i = 0; i < 400; i++) governor.sample(60, (now += 60))
    expect(governor.tier).toBe(HAZE_TIERS.length - 1)
    const fresh = new HazeGovernor()
    for (let i = 0; i < 100; i++) fresh.sample(MAX_SAMPLE_MS + 1, (now += MAX_SAMPLE_MS + 1))
    expect(fresh.tier).toBe(0)
  })

  it('judges each run on its own frames: a gap between runs neither counts towards recovery nor carries an average over', () => {
    const governor = new HazeGovernor()
    let now = 0
    for (let i = 0; i < MIN_SAMPLES; i++) governor.sample(40, (now += 40))
    expect(governor.tier).toBe(1)
    // A fast run long enough to be judged but too short to recover, then the canvas goes idle.
    for (let i = 0; i < MIN_SAMPLES + 5; i++) governor.sample(10, (now += 10))
    expect(governor.tier).toBe(1)
    governor.endRun()
    now += 10_000
    // The next run's first fast frame does not step back up on the time spent idle.
    expect(governor.sample(10, (now += 10))).toBeNull()
    expect(governor.tier).toBe(1)
  })

  it('still governs a software renderer, whose every frame is slower than a quarter second', () => {
    const governor = new HazeGovernor()
    let now = 0
    for (let i = 0; i < MIN_SAMPLES; i++) governor.sample(700, (now += 700))
    expect(governor.tier).toBe(1)
  })
})

describe('what the view draws and casts at', () => {
  const stage = { width: 8.6, height: 4, depth: 11 }
  const hall = element({ uuid: 'hall', kind: 'ROOM', positionY: -9.2, positionZ: -0.95, widthM: 8.6, depthM: 18.4, heightM: 5.55 })
  const house = element({
    uuid: 'house', kind: 'ROOM', positionY: 5.675, widthM: 8.6, depthM: 10.65, heightM: 4.6,
    params: { omit: ['DOWNSTAGE', 'FLOOR'] },
  })
  const flat = element({ uuid: 'flat', kind: 'FLAT', layer: 'SET', positionY: 4.3, widthM: 4.6, depthM: 0.1, heightM: 2.8 })
  const stalls = element({ uuid: 'stalls', kind: 'SEATING', layer: 'VENUE', widthM: 0, depthM: 0, heightM: 0, params: { rows: 2, seatsPerRow: 2, rowPitchM: 1, seatPitchM: 0.5 } })

  it('draws an element by its layer, and a seating by Seating whatever its layer', () => {
    expect(elementInLayers(flat, { ...DEFAULT_SCENE_LAYERS, set: false })).toBe(false)
    expect(elementInLayers(stalls, { ...DEFAULT_SCENE_LAYERS, venue: false })).toBe(true)
    expect(elementInLayers(stalls, { ...DEFAULT_SCENE_LAYERS, seating: false })).toBe(false)
    const noSet = sceneBuilds([hall, flat, stalls], { ...DEFAULT_SCENE_LAYERS, set: false })
    expect(noSet.map((b) => b.element.uuid)).toEqual(['hall', 'stalls'])
  })

  it("retires the stage box's back wall and catch floor while a room is drawn, and casts at the room", () => {
    const empty = sceneColliders({ stage, regions: [], builds: [], catchSizeM: 20 })
    // The stage floor, its back wall and the catch floor.
    expect(empty).toHaveLength(3)
    const builds = sceneBuilds([hall, house, flat], DEFAULT_SCENE_LAYERS)
    expect(drawsRoom(builds)).toBe(true)
    const withRoom = sceneColliders({ stage, regions: [], builds, catchSizeM: 20 })
    // The stage floor, the hall's six faces, the house's four, and the flat.
    expect(withRoom).toHaveLength(1 + 6 + 4 + 1)
  })

  it('clips the beams in the air to the lowest room floor, the furthest upstage wall and the side walls', () => {
    expect(beamClipFor(stage, [])).toEqual({ floorZ: 0, wallY: 11, minX: -NO_SIDE_X, maxX: NO_SIDE_X })
    const builds = sceneBuilds([hall, house], DEFAULT_SCENE_LAYERS)
    const clip = beamClipFor(stage, builds)
    expect(clip.floorZ).toBe(-0.95)
    expect(clip.wallY).toBeCloseTo(5.675 + 10.65 / 2, 9)
    expect([clip.minX, clip.maxX]).toEqual([-4.3, 4.3])
    // A wider wing, off centre, widens only its own side.
    const wing = element({ uuid: 'wing', kind: 'ROOM', positionX: 5, positionY: 4, widthM: 4, depthM: 4, heightM: 4 })
    const widened = beamClipFor(stage, sceneBuilds([hall, wing], DEFAULT_SCENE_LAYERS))
    expect([widened.minX, widened.maxX]).toEqual([-4.3, 7])
  })

  it('bounds the drawn venue for how deep a section sees', () => {
    expect(sceneElementBounds([])).toBeNull()
    const bounds = sceneElementBounds(sceneBuilds([hall], DEFAULT_SCENE_LAYERS))!
    expect(bounds.min.z).toBeLessThanOrEqual(-0.95)
    expect(bounds.min.y).toBeLessThanOrEqual(-18.4)
    expect(bounds.max.z).toBeGreaterThanOrEqual(4.6)
  })
})

describe('how far the haze reaches', () => {
  const pros = (fields: Partial<StageElementDto>) => element({ kind: 'PROSCENIUM', ...fields })

  it('reaches the house only when asked to', () => {
    expect(hazeClipFor('everywhere', [pros({ positionY: 0.3 })])).toBeNull()
    expect(hazeClipFor('off', [])).toBeNull()
  })

  it("stops at the stage's downstage edge without a proscenium", () => {
    expect(hazeClipFor('stage', [element({ kind: 'ROOM', positionY: -9 })])).toEqual({ nx: 0, ny: 1, d: 0 })
  })

  it('stops at the most downstage proscenium that is shown', () => {
    const clip = hazeClipFor('stage', [
      pros({ uuid: 'inner', positionY: 2 }),
      pros({ uuid: 'gone', positionY: -1, hidden: true }),
      pros({ uuid: 'outer', positionY: 0.3 }),
    ])!
    expect(clip.nx).toBeCloseTo(0, 12)
    expect(clip.ny).toBe(1)
    expect(clip.d).toBeCloseTo(0.3, 12)
  })

  it("follows a turned proscenium's line, and points upstage whichever way round it stands", () => {
    const clip = hazeClipFor('stage', [pros({ positionX: 1, positionY: 2, yawDeg: 30 })])!
    const yaw = Math.PI / 6
    expect(clip.nx).toBeCloseTo(-Math.sin(yaw), 12)
    expect(clip.ny).toBeCloseTo(Math.cos(yaw), 12)
    // The wall's own origin is on the line.
    expect(clip.nx * 1 + clip.ny * 2 - clip.d).toBeCloseTo(0, 12)
    const flipped = hazeClipFor('stage', [pros({ positionX: 1, positionY: 2, yawDeg: 210 })])!
    expect(flipped.nx).toBeCloseTo(clip.nx, 12)
    expect(flipped.ny).toBeCloseTo(clip.ny, 12)
    expect(flipped.d).toBeCloseTo(clip.d, 12)
  })
})

describe('where gobos land (fixture-optics plan session 4)', () => {
  it('lands every gobo light by default, and only the selected heads under the fallback', () => {
    expect(DEFAULT_GOBO_SURFACES).toBe('all')
    expect(goboLandsOnSurfaces('all', false)).toBe(true)
    expect(goboLandsOnSurfaces('all', true)).toBe(true)
    expect(goboLandsOnSurfaces('selected', true)).toBe(true)
    expect(goboLandsOnSurfaces('selected', false)).toBe(false)
  })

  it('reads back only a mode this build offers', () => {
    expect(isGoboSurfaces('selected')).toBe(true)
    expect(isGoboSurfaces('none')).toBe(false)
    expect(isGoboSurfaces(true)).toBe(false)
  })
})

describe('box shadows (stage-light plan session 3)', () => {
  it('lets every light carry a full list by default, and caps it at 16 or at none', () => {
    expect(DEFAULT_BOX_SHADOWS).toBe('all')
    expect(BOX_SHADOW_CAPS).toEqual({ all: MAX_LIGHT_COLLIDERS, some: 16, off: 0 })
  })

  it('reads back only a mode this build offers', () => {
    expect(isBoxShadows('some')).toBe(true)
    expect(isBoxShadows('none')).toBe(false)
    expect(isBoxShadows(16)).toBe(false)
  })
})

describe('the stored scene layers', () => {
  it('keeps a haze extent, reads an old off as Off, and falls back to Stage', () => {
    expect(parseSceneLayers({ haze: 'everywhere' }).haze).toBe('everywhere')
    expect(parseSceneLayers({ haze: false }).haze).toBe('off')
    expect(parseSceneLayers({ haze: true }).haze).toBe('stage')
    expect(parseSceneLayers({ haze: 'fog' }).haze).toBe('stage')
    expect(parseSceneLayers(null)).toEqual(DEFAULT_SCENE_LAYERS)
    expect(parseSceneLayers({ venue: false, haze: 'off' })).toEqual({ ...DEFAULT_SCENE_LAYERS, venue: false, haze: 'off' })
  })
})
