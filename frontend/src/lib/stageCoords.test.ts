import { describe, it, expect } from "vitest"
import { Euler, Group, MathUtils, Quaternion, Vector3 } from "three"
import {
  toThree,
  fromThree,
  panTiltToDir,
  headQuaternionFor,
  axisCentreDeg,
  dmxToSignedDegrees,
  normaliseSignedDeg,
  patchPlacementFromWorld,
  placementFromWorldLighting,
  rigAxisLighting,
  worldPositionFor,
  worldPositionLighting,
  dmxToDegrees,
} from "./stageCoords"
import { worldEndpointsFor } from "./stageGeometry"
import { STAGE_PROJECTIONS, project, unproject } from "./stageProjection"
import type { FixturePatch } from "../api/patchApi"
import type { RiggingDto } from "../api/riggingApi"
import type { SliderPropertyDescriptor } from "../store/fixtures"

const APPROX = 1e-9

function basePatch(overrides: Partial<FixturePatch> = {}): FixturePatch {
  return {
    id: 1,
    key: "p1",
    displayName: "P1",
    fixtureTypeKey: "tk",
    startChannel: 1,
    channelCount: 1,
    manufacturer: null,
    model: null,
    modeName: null,
    universe: 1,
    subnet: 0,
    sortOrder: 0,
    groups: [],
    stageX: null,
    stageY: null,
    stageZ: null,
    baseYawDeg: null,
    basePitchDeg: null,
    riggingUuid: null,
    beamAngleDeg: null,
    gelCode: null,
    kindOverride: null,
    stageHidden: false,
    ...overrides,
  }
}

function baseRig(overrides: Partial<RiggingDto> = {}): RiggingDto {
  return {
    id: 1,
    uuid: "rig-1",
    name: "Rig 1",
    kind: "truss",
    positionX: 0,
    positionY: 0,
    positionZ: 0,
    yawDeg: 0,
    pitchDeg: 0,
    rollDeg: 0,
    lengthM: 3,
    sortOrder: 0,
    ...overrides,
  }
}

function baseSlider(overrides: Partial<SliderPropertyDescriptor> = {}): SliderPropertyDescriptor {
  return {
    type: "slider",
    name: "pan",
    displayName: "Pan",
    category: "pan",
    channel: { universe: 1, channelNo: 1 },
    min: 0,
    max: 255,
    ...overrides,
  }
}

describe("toThree / fromThree", () => {
  it("round-trips arbitrary inputs", () => {
    const samples: [number, number, number][] = [
      [0, 0, 0],
      [1.5, -2.25, 4],
      [-3.7, 8.1, -0.001],
      [1000, -1000, 1000],
    ]
    for (const [x, y, z] of samples) {
      const r3f = toThree(x, y, z)
      const back = fromThree(r3f)
      expect(back.x).toBeCloseTo(x, 10)
      expect(back.y).toBeCloseTo(y, 10)
      expect(back.z).toBeCloseTo(z, 10)
    }
  })

  it("swizzles stage axes into R3F (X right, Y up, Z toward camera)", () => {
    const v = toThree(2, 5, 7)
    expect(v.x).toBeCloseTo(2, 10)
    expect(v.y).toBeCloseTo(7, 10)
    expect(v.z).toBeCloseTo(-5, 10)
  })

  it("toThree writes into a caller-provided target", () => {
    const target = new Vector3(99, 99, 99)
    const result = toThree(1, 2, 3, target)
    expect(result).toBe(target)
    expect(target.x).toBeCloseTo(1, 10)
    expect(target.y).toBeCloseTo(3, 10)
    expect(target.z).toBeCloseTo(-2, 10)
  })
})

// Signed angles about each axis's centre — what dmxToSignedDegrees produces.
const AIM_CASES: [number, number][] = [
  [0, 0],
  [90, 45],
  [180, 30],
  [-90, 90],
  [45, -60],
  [135, 75],
]

describe("panTiltToDir", () => {
  it("at pan 0 / tilt 0 points straight up the body axis", () => {
    const v = panTiltToDir(0, 0)
    expect(v.x).toBeCloseTo(0, APPROX)
    expect(v.y).toBeCloseTo(1, APPROX)
    expect(v.z).toBeCloseTo(0, APPROX)
  })

  it("tilts through the horizon to straight down at 180", () => {
    const level = panTiltToDir(0, 90)
    expect(level.y).toBeCloseTo(0, APPROX)
    expect(level.z).toBeCloseTo(1, APPROX) // downstage

    const back = panTiltToDir(0, -90)
    expect(back.z).toBeCloseTo(-1, APPROX) // upstage

    const down = panTiltToDir(0, 180)
    expect(down.y).toBeCloseTo(-1, APPROX)
  })

  it("pans a level beam toward stage right", () => {
    const v = panTiltToDir(90, 90)
    expect(v.x).toBeCloseTo(1, APPROX)
    expect(v.y).toBeCloseTo(0, APPROX)
    expect(v.z).toBeCloseTo(0, APPROX)
  })

  it("produces a unit vector for arbitrary inputs", () => {
    for (const [pan, tilt] of AIM_CASES) {
      const v = panTiltToDir(pan, tilt)
      expect(Math.hypot(v.x, v.y, v.z)).toBeCloseTo(1, 9)
    }
  })

  it("writes into a caller-provided target", () => {
    const target = new Vector3(99, 99, 99)
    const result = panTiltToDir(0, 0, target)
    expect(result).toBe(target)
    expect(target.y).toBeCloseTo(1, APPROX)
  })
})

describe("headQuaternionFor", () => {
  it("applied to the rest direction reproduces panTiltToDir", () => {
    for (const [pan, tilt] of AIM_CASES) {
      const rest = new Vector3(0, 1, 0).applyQuaternion(headQuaternionFor(pan, tilt))
      const expected = panTiltToDir(pan, tilt)
      expect(rest.x).toBeCloseTo(expected.x, 9)
      expect(rest.y).toBeCloseTo(expected.y, 9)
      expect(rest.z).toBeCloseTo(expected.z, 9)
    }
  })

  // useBeamDirector drives a real yoke as two nodes (yoke pans, head tilts) and
  // falls back to this single combined quaternion for bodies with no yoke. The
  // two paths must agree or a Scantastic would aim differently from a Fusion.
  it("equals a yoke pan about Y composed with a head tilt about X", () => {
    for (const [pan, tilt] of AIM_CASES) {
      const composed = new Quaternion()
        .setFromAxisAngle(new Vector3(0, 1, 0), MathUtils.degToRad(pan))
        .multiply(
          new Quaternion().setFromAxisAngle(new Vector3(1, 0, 0), MathUtils.degToRad(tilt)),
        )
      expect(headQuaternionFor(pan, tilt).angleTo(composed)).toBeCloseTo(0, 9)
    }
  })
})

describe("axisCentreDeg / dmxToSignedDegrees", () => {
  // Every pan/tilt range declared in the lighting7 fixture library.
  const RANGES: [number, number][] = [
    [0, 540], // pan, most movers
    [0, 630], // pan, Varytec Easymove XL 60
    [0, 180], // pan, Scantastic 4
    [0, 270], // tilt, several
    [0, 257], // tilt, Martin MAC 250 / Robe ColorSpot 575
    [0, 210], // tilt, Fusion 100 Spot MKII
    [0, 90], // tilt, Scantastic 4
  ]

  it("centres on the midpoint of the declared travel", () => {
    for (const [degMin, degMax] of RANGES) {
      expect(axisCentreDeg(baseSlider({ degMin, degMax }))).toBeCloseTo(
        (degMin + degMax) / 2,
        9,
      )
    }
  })

  it("is null without both bounds", () => {
    expect(axisCentreDeg(baseSlider())).toBeNull()
    expect(axisCentreDeg(baseSlider({ degMin: 0 }))).toBeNull()
    expect(dmxToSignedDegrees(128, baseSlider({ degMax: 540 }))).toBeNull()
  })

  it("reads zero at DMX centre and ±half-travel at the extremes", () => {
    for (const [degMin, degMax] of RANGES) {
      const s = baseSlider({ degMin, degMax })
      const half = (degMax - degMin) / 2
      expect(dmxToSignedDegrees(127.5, s)).toBeCloseTo(0, 9)
      expect(dmxToSignedDegrees(0, s)).toBeCloseTo(-half, 9)
      expect(dmxToSignedDegrees(255, s)).toBeCloseTo(half, 9)
    }
  })

  it("flips sign when inverted", () => {
    const s = baseSlider({ degMin: 0, degMax: 210, inverted: true })
    expect(dmxToSignedDegrees(127.5, s)).toBeCloseTo(0, 9)
    expect(dmxToSignedDegrees(0, s)).toBeCloseTo(105, 9)
    expect(dmxToSignedDegrees(255, s)).toBeCloseTo(-105, 9)
  })

  // Guards that centring didn't break 16-bit: combineFine hands in a fractional
  // coarse value, and one fine step must stay a sub-degree nudge off centre.
  it("keeps a 16-bit fine step as a hair off centre", () => {
    const s = baseSlider({ degMin: 0, degMax: 210 })
    const oneFineStep = dmxToSignedDegrees(127.5 + 1 / 256, s)!
    expect(oneFineStep).toBeGreaterThan(0)
    expect(oneFineStep).toBeLessThan(0.01)
  })
})

describe("normaliseSignedDeg", () => {
  it("wraps into (-180, 180], keeping a deliberate 180", () => {
    expect(normaliseSignedDeg(0)).toBe(0)
    expect(normaliseSignedDeg(180)).toBe(180)
    expect(normaliseSignedDeg(-180)).toBe(180)
    expect(normaliseSignedDeg(181)).toBeCloseTo(-179, 9)
    expect(normaliseSignedDeg(190)).toBeCloseTo(-170, 9)
    expect(normaliseSignedDeg(-190)).toBeCloseTo(170, 9)
    expect(normaliseSignedDeg(360)).toBe(0)
    expect(normaliseSignedDeg(540)).toBe(180)
  })
})

// The scene graph FixtureModel builds: body group carries the mount pose, yoke
// pans, head tilts. These assert the rendered model and the beam maths agree —
// the disagreement between them was the whole bug.
describe("fixture scene graph", () => {
  function aim(
    pitchDeg: number,
    yawDeg: number,
    panDeg: number,
    tiltDeg: number,
    emitAxis: 1 | -1,
  ): Vector3 {
    const root = new Group()
    const body = new Group()
    const yoke = new Group()
    const head = new Group()
    root.add(body)
    body.add(yoke)
    yoke.add(head)
    body.rotation.set(MathUtils.degToRad(pitchDeg), MathUtils.degToRad(yawDeg), 0, "YXZ")
    yoke.rotation.set(0, MathUtils.degToRad(panDeg), 0)
    head.rotation.set(MathUtils.degToRad(tiltDeg), 0, 0)
    root.updateMatrixWorld(true)
    return new Vector3(0, emitAxis, 0).transformDirection(head.matrixWorld)
  }

  it("aims a floor-standing mover straight up at DMX centre", () => {
    const v = aim(0, 0, 0, 0, 1)
    expect(v.y).toBeCloseTo(1, 9)
  })

  it("aims a hung mover (basePitch 180) straight down at DMX centre", () => {
    const v = aim(180, 0, 0, 0, 1)
    expect(v.y).toBeCloseTo(-1, 9)
  })

  it("matches panTiltToDir for an unrotated mount", () => {
    for (const [pan, tilt] of AIM_CASES) {
      const graph = aim(0, 0, pan, tilt, 1)
      const maths = panTiltToDir(pan, tilt)
      expect(graph.distanceTo(maths)).toBeCloseTo(0, 9)
    }
  })

  it("composes the mount pose on top of pan/tilt", () => {
    for (const [pitch, yaw] of [
      [35, 0],
      [-20, 47],
      [180, 120],
    ]) {
      for (const [pan, tilt] of AIM_CASES) {
        const graph = aim(pitch, yaw, pan, tilt, 1)
        const maths = panTiltToDir(pan, tilt).applyEuler(
          new Euler(MathUtils.degToRad(pitch), MathUtils.degToRad(yaw), 0, "YXZ"),
        )
        expect(graph.distanceTo(maths)).toBeCloseTo(0, 9)
      }
    }
  })

  // The migration contract for fixtures with no pan axis. The old code
  // subtracted a hardcoded 270 even with no pan property, so baseYawDeg
  // effectively meant "yaw - 270"; adding 90 reproduces the old aim exactly.
  it("reproduces a static fixture's old aim given baseYawDeg + 90", () => {
    const oldDir = (yaw: number, pitch: number) =>
      new Vector3(0, -1, 0).applyEuler(
        new Euler(MathUtils.degToRad(pitch), MathUtils.degToRad(yaw - 270), 0, "YXZ"),
      )
    for (const [yaw, pitch] of [
      [0, 35],
      [47, -20],
      [120, 80],
    ]) {
      const now = aim(pitch, yaw + 90, 0, 0, -1)
      expect(now.distanceTo(oldDir(yaw, pitch))).toBeCloseTo(0, 9)
    }
  })
})

describe("worldPositionFor", () => {
  it("composes stage offset with a matching rigging position", () => {
    const rig = baseRig({ uuid: "rig-X", positionX: 1, positionY: 2, positionZ: 3 })
    const patch = basePatch({
      stageX: 0.5,
      stageY: 0.25,
      stageZ: 0.1,
      riggingUuid: "rig-X",
    })
    const v = worldPositionFor(patch, [rig])
    expect(v.x).toBeCloseTo(1.5, 10)
    expect(v.y).toBeCloseTo(3.1, 10)
    expect(v.z).toBeCloseTo(-2.25, 10)
  })

  it("falls back to stage* when riggingUuid does not match", () => {
    const rig = baseRig({ uuid: "rig-X" })
    const patch = basePatch({
      stageX: 4,
      stageY: 1,
      stageZ: 2,
      riggingUuid: "rig-NOTFOUND",
    })
    const v = worldPositionFor(patch, [rig])
    expect(v.x).toBeCloseTo(4, 10)
    expect(v.y).toBeCloseTo(2, 10)
    expect(v.z).toBeCloseTo(-1, 10)
  })

  it("treats stage* as free-space world coords when no rigging is set", () => {
    const patch = basePatch({ stageX: 3, stageY: -2, stageZ: 1 })
    const v = worldPositionFor(patch, [])
    expect(v.x).toBeCloseTo(3, 10)
    expect(v.y).toBeCloseTo(1, 10)
    expect(v.z).toBeCloseTo(2, 10)
  })

  it("treats null stage* as zero", () => {
    const patch = basePatch()
    const v = worldPositionFor(patch, [])
    expect(v.x).toBeCloseTo(0, 10)
    expect(v.y).toBeCloseTo(0, 10)
    expect(v.z).toBeCloseTo(0, 10)
  })

  it("round-trips through patchPlacementFromWorld for arbitrary rig poses", () => {
    const rigs: RiggingDto[] = [
      baseRig({ uuid: "rig-yaw", yawDeg: 90 }),
      baseRig({ uuid: "rig-pitch", pitchDeg: 30 }),
      baseRig({ uuid: "rig-roll", rollDeg: 45 }),
      baseRig({ uuid: "rig-yp", yawDeg: 45, pitchDeg: 20 }),
      baseRig({ uuid: "rig-ypr", yawDeg: 45, pitchDeg: 20, rollDeg: 10 }),
      baseRig({
        uuid: "rig-off-origin",
        positionX: 2,
        positionY: -1.5,
        positionZ: 3,
        yawDeg: 60,
        pitchDeg: -25,
        rollDeg: 15,
      }),
    ]
    const stageOffsets: Array<[number, number, number]> = [
      [1, 0, 0],
      [0, 1, 0],
      [0, 0, 1],
      [-0.5, 1.2, 0.8],
      [0, 0, 0],
    ]
    for (const rig of rigs) {
      for (const [sx, sy, sz] of stageOffsets) {
        const patch = basePatch({ stageX: sx, stageY: sy, stageZ: sz, riggingUuid: rig.uuid })
        const world = worldPositionFor(patch, [rig])
        const offset = patchPlacementFromWorld(patch, world, [rig])
        expect(offset.stageX).toBeCloseTo(sx, 9)
        expect(offset.stageY).toBeCloseTo(sy, 9)
        expect(offset.stageZ).toBeCloseTo(sz, 9)
        const patch2 = basePatch({
          stageX: offset.stageX,
          stageY: offset.stageY,
          stageZ: offset.stageZ,
          riggingUuid: rig.uuid,
        })
        const world2 = worldPositionFor(patch2, [rig])
        expect(world2.x).toBeCloseTo(world.x, 9)
        expect(world2.y).toBeCloseTo(world.y, 9)
        expect(world2.z).toBeCloseTo(world.z, 9)
      }
    }
  })
})

describe("dmxToDegrees", () => {
  it("returns null when degMin or degMax is missing", () => {
    expect(dmxToDegrees(128, baseSlider())).toBeNull()
    expect(dmxToDegrees(128, baseSlider({ degMin: 0 }))).toBeNull()
    expect(dmxToDegrees(128, baseSlider({ degMax: 540 }))).toBeNull()
  })

  it("maps boundaries to degMin/degMax", () => {
    const s = baseSlider({ degMin: 0, degMax: 540 })
    expect(dmxToDegrees(0, s)).toBeCloseTo(0, 9)
    expect(dmxToDegrees(255, s)).toBeCloseTo(540, 9)
  })

  it("clamps below min and above max", () => {
    const s = baseSlider({ degMin: 0, degMax: 540 })
    expect(dmxToDegrees(-10, s)).toBeCloseTo(0, 9)
    expect(dmxToDegrees(999, s)).toBeCloseTo(540, 9)
  })

  it("interpolates linearly at the midpoint", () => {
    const s = baseSlider({ degMin: 0, degMax: 540 })
    expect(dmxToDegrees(127.5, s)).toBeCloseTo(270, 9)
  })

  it("honours the inverted flag", () => {
    const s = baseSlider({ degMin: 0, degMax: 540, inverted: true })
    expect(dmxToDegrees(0, s)).toBeCloseTo(540, 9)
    expect(dmxToDegrees(255, s)).toBeCloseTo(0, 9)
  })

  it("returns null when span is zero or negative", () => {
    expect(dmxToDegrees(0, baseSlider({ min: 10, max: 10, degMin: 0, degMax: 540 }))).toBeNull()
  })
})

describe("placementFromWorldLighting", () => {
  it("passes world coords straight through with no rig", () => {
    expect(placementFromWorldLighting({ x: 3, y: -2, z: 1 }, null)).toEqual({
      stageX: 3,
      stageY: -2,
      stageZ: 1,
    })
  })

  it("inverts worldPositionLighting for arbitrary rig poses", () => {
    const rigs = [
      baseRig({ yawDeg: 90 }),
      baseRig({ pitchDeg: 30 }),
      baseRig({ rollDeg: 45 }),
      baseRig({ yawDeg: 45, pitchDeg: 20, rollDeg: 10 }),
      baseRig({ positionX: 2, positionY: -1.5, positionZ: 3, yawDeg: 60, pitchDeg: -25, rollDeg: 15 }),
    ]
    const offsets: Array<[number, number, number]> = [
      [1, 0, 0],
      [0, 0, -0.5],
      [-0.5, 1.2, 0.8],
      [0, 0, 0],
    ]
    for (const rig of rigs) {
      for (const [sx, sy, sz] of offsets) {
        const patch = basePatch({ stageX: sx, stageY: sy, stageZ: sz, riggingUuid: rig.uuid })
        const world = worldPositionLighting(patch, [rig])!
        const back = placementFromWorldLighting(world, rig)
        expect(back.stageX).toBeCloseTo(sx, 9)
        expect(back.stageY).toBeCloseTo(sy, 9)
        expect(back.stageZ).toBeCloseTo(sz, 9)
      }
    }
  })

  it("re-frames a world point into a DIFFERENT rig than the patch names", () => {
    // The whole reason this function takes the rig explicitly: re-parenting must
    // project into the destination frame. patchPlacementFromWorld reads
    // patch.riggingUuid and so cannot express this.
    const from = baseRig({ uuid: "from", positionX: 0, yawDeg: 0 })
    const to = baseRig({ uuid: "to", positionX: 5, positionZ: 4, yawDeg: 90 })
    const patch = basePatch({ stageX: 1, stageY: 0, stageZ: 0, riggingUuid: from.uuid })
    const world = worldPositionLighting(patch, [from, to])!
    expect(world).toEqual({ x: 1, y: 0, z: 0 })

    const local = placementFromWorldLighting(world, to)
    // Re-composing those offsets against `to` must land back on the same world point.
    const moved = basePatch({ ...local, riggingUuid: to.uuid })
    const world2 = worldPositionLighting(moved, [from, to])!
    expect(world2.x).toBeCloseTo(world.x, 9)
    expect(world2.y).toBeCloseTo(world.y, 9)
    expect(world2.z).toBeCloseTo(world.z, 9)
  })

  it("keeps a front-elevation drag from shifting the fixture up or downstage", () => {
    // The correctness hotspot for the 2D elevation editor. Project a
    // truss-mounted fixture into the front view, move it in-plane, lift it back
    // out, and re-derive its rig-local offsets: the world Y it never touched
    // must survive the whole round trip.
    const rig = baseRig({
      uuid: "rig-compound",
      positionX: 1,
      positionY: 2.5,
      positionZ: 4,
      yawDeg: 45,
      pitchDeg: 0,
      rollDeg: 10,
    })
    const patch = basePatch({ stageX: 0.75, stageY: 0, stageZ: -0.4, riggingUuid: rig.uuid })

    const world = worldPositionLighting(patch, [rig])!
    const front = STAGE_PROJECTIONS.front
    const screen = project(world, front)
    // Drag: 1.2 m right on screen, 0.6 m up (v is screen-down, so subtract).
    const dragged = { h: screen.h + 1.2, v: screen.v - 0.6 }
    const world2 = unproject(dragged, front, world)

    // Y is the out-of-plane axis in the front view — bit-identical, not merely close.
    expect(world2.y).toBe(world.y)
    expect(world2.x).toBeCloseTo(world.x + 1.2, 12)
    expect(world2.z).toBeCloseTo(world.z + 0.6, 12)

    // And the rig-local offsets we'd persist must recompose to exactly that point.
    const next = placementFromWorldLighting(world2, rig)
    const after = worldPositionLighting(basePatch({ ...next, riggingUuid: rig.uuid }), [rig])!
    expect(after.x).toBeCloseTo(world2.x, 9)
    expect(after.y).toBeCloseTo(world.y, 9)
    expect(after.z).toBeCloseTo(world2.z, 9)
  })
})

describe("rigAxisLighting", () => {
  it("returns the world axes for an unrotated rig", () => {
    const rig = baseRig()
    expect(rigAxisLighting(rig, "x").x).toBeCloseTo(1, 12)
    expect(rigAxisLighting(rig, "y").y).toBeCloseTo(1, 12)
    expect(rigAxisLighting(rig, "z").z).toBeCloseTo(1, 12)
  })

  it("swings local X into upstage at 90° yaw", () => {
    const a = rigAxisLighting(baseRig({ yawDeg: 90 }), "x")
    expect(a.x).toBeCloseTo(0, 12)
    expect(a.y).toBeCloseTo(1, 12)
    expect(a.z).toBeCloseTo(0, 12)
  })

  it("tilts local X out of the horizontal with roll", () => {
    const a = rigAxisLighting(baseRig({ rollDeg: 90 }), "x")
    expect(a.z).toBeCloseTo(1, 12)
  })

  it("stays a unit vector and stays orthogonal under a compound pose", () => {
    const rig = baseRig({ yawDeg: 33, pitchDeg: -21, rollDeg: 47 })
    const ax = rigAxisLighting(rig, "x")
    const ay = rigAxisLighting(rig, "y")
    const az = rigAxisLighting(rig, "z")
    for (const a of [ax, ay, az]) {
      expect(Math.hypot(a.x, a.y, a.z)).toBeCloseTo(1, 10)
    }
    const dot = (p: typeof ax, q: typeof ax) => p.x * q.x + p.y * q.y + p.z * q.z
    expect(dot(ax, ay)).toBeCloseTo(0, 10)
    expect(dot(ax, az)).toBeCloseTo(0, 10)
    expect(dot(ay, az)).toBeCloseTo(0, 10)
  })

  it("agrees with the direction between the rig's own endpoints", () => {
    const rig = baseRig({ yawDeg: 33, rollDeg: 20, lengthM: 4 })
    const [a, b] = worldEndpointsFor(rig)
    const len = Math.hypot(b.x - a.x, b.y - a.y, b.z - a.z)
    const dir = { x: (b.x - a.x) / len, y: (b.y - a.y) / len, z: (b.z - a.z) / len }
    const axis = rigAxisLighting(rig, "x")
    expect(axis.x).toBeCloseTo(dir.x, 10)
    expect(axis.y).toBeCloseTo(dir.y, 10)
    expect(axis.z).toBeCloseTo(dir.z, 10)
  })
})
