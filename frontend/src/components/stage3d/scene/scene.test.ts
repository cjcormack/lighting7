import { describe, expect, it } from 'vitest'
import { packBlades } from '../beamMask'
import { NO_SIDE_X } from '../beamShaders'
import type { StageElementDto } from '../../../api/stageElementApi'
import { beamReach, boxCollider, elementColliders, type BeamHit } from './beamReach'
import { buildElement } from './builders'
import { EDGE_IRIS_STEPS, LIGHT_TEXELS, LightTable, makeLightRow, packEdgeIris, UNPACK_EDGE_IRIS_GLSL } from './lightTable'
import { HAZE_TIERS, HazeGovernor, MAX_SAMPLE_MS, MIN_SAMPLES, RECOVER_AFTER_MS } from './hazeGovernor'
import { beamClipFor, drawsRoom, hazeClipFor, sceneBuilds, sceneColliders, sceneElementBounds } from './stageSurfaces'
import { DEFAULT_SCENE_LAYERS, elementInLayers, parseSceneLayers } from './sceneView'

function element(fields: Partial<StageElementDto>): StageElementDto {
  return {
    id: 1, uuid: 'e', name: 'E', kind: 'OBJECT', layer: 'VENUE',
    positionX: 0, positionY: 0, positionZ: 0, yawDeg: 0, widthM: 1, depthM: 1, heightM: 1,
    finishColour: null, finishPattern: null, emissive: false, params: {}, hidden: false, sortOrder: 0,
    ...fields,
  }
}

const hit = (): BeamHit => ({ t: 0, nx: 0, ny: 0, nz: 0 })

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
    const colliders = elementColliders(hall, buildElement(hall, { drawnRegionUuids: new Set() }))
    expect(colliders).toHaveLength(6)
    const out = hit()
    // From 3 m over the stalls, straight down: the hall floor, 3.95 m below and a hair more.
    expect(beamReach(0, 3, 9.2, 0, -1, 0, colliders, 40, out)).toBe(true)
    expect(out.t).toBeCloseTo(3.954, 3)
    expect(out.ny).toBe(1)
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
    expect(Array.from(table.staged.subarray(12, 16))).toEqual([0, 0, 0, -1])
    table.set(0, { ...lit, hit: { px: 0, py: 0.5, pz: 0, nx: 0, ny: 1, nz: 0 } })
    expect(Array.from(table.staged.subarray(12, 16))).toEqual([0, 1, 0, 0.5])
  })

  it("carries the beam's frame and aperture for the surfaces' mask: right axis, tan, near, blades, aspect", () => {
    const table = new LightTable(1)
    const [bladesA, bladesB] = packBlades([
      { depth: 0.25, angleDeg: 0 },
      { depth: 0, angleDeg: 0 },
      { depth: 0.5, angleDeg: -12 },
      { depth: 1, angleDeg: 30 },
    ])
    table.set(0, { ...makeLightRow(), r: 1, rx: 0, ry: 0, rz: 1, tanHalf: 0.17, near: 0.5, iris: 0.4, aspect: -0.25, bladesA, bladesB })
    // Texel 4 is the frame; texel 5 the aperture — near, the (top, bottom) blades, aspect (an oval
    // is negative), the (left, right) blades — every packed blade word held exactly by a float32.
    expect(Array.from(table.staged.subarray(16, 24))).toEqual([0, 0, 1, Math.fround(0.17), 0.5, bladesA, -0.25, bladesB])
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
    const noSet = sceneBuilds([hall, flat, stalls], { ...DEFAULT_SCENE_LAYERS, set: false }, { drawnRegionUuids: new Set() })
    expect(noSet.map((b) => b.element.uuid)).toEqual(['hall', 'stalls'])
  })

  it("retires the stage box's back wall and catch floor while a room is drawn, and casts at the room", () => {
    const empty = sceneColliders({ stage, regions: [], builds: [], catchSizeM: 20 })
    // The stage floor, its back wall and the catch floor.
    expect(empty).toHaveLength(3)
    const builds = sceneBuilds([hall, house, flat], DEFAULT_SCENE_LAYERS, { drawnRegionUuids: new Set() })
    expect(drawsRoom(builds)).toBe(true)
    const withRoom = sceneColliders({ stage, regions: [], builds, catchSizeM: 20 })
    // The stage floor, the hall's six faces, the house's four, and the flat.
    expect(withRoom).toHaveLength(1 + 6 + 4 + 1)
  })

  it('clips the beams in the air to the lowest room floor, the furthest upstage wall and the side walls', () => {
    expect(beamClipFor(stage, [])).toEqual({ floorZ: 0, wallY: 11, minX: -NO_SIDE_X, maxX: NO_SIDE_X })
    const builds = sceneBuilds([hall, house], DEFAULT_SCENE_LAYERS, { drawnRegionUuids: new Set() })
    const clip = beamClipFor(stage, builds)
    expect(clip.floorZ).toBe(-0.95)
    expect(clip.wallY).toBeCloseTo(5.675 + 10.65 / 2, 9)
    expect([clip.minX, clip.maxX]).toEqual([-4.3, 4.3])
    // A wider wing, off centre, widens only its own side.
    const wing = element({ uuid: 'wing', kind: 'ROOM', positionX: 5, positionY: 4, widthM: 4, depthM: 4, heightM: 4 })
    const widened = beamClipFor(stage, sceneBuilds([hall, wing], DEFAULT_SCENE_LAYERS, { drawnRegionUuids: new Set() }))
    expect([widened.minX, widened.maxX]).toEqual([-4.3, 7])
  })

  it('bounds the drawn venue for how deep a section sees', () => {
    expect(sceneElementBounds([])).toBeNull()
    const bounds = sceneElementBounds(sceneBuilds([hall], DEFAULT_SCENE_LAYERS, { drawnRegionUuids: new Set() }))!
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
