import { describe, it, expect } from "vitest"
import { MathUtils } from "three"
import type { StageRegionDto } from "../api/stageRegionApi"
import type { RiggingDto } from "../api/riggingApi"
import {
  DEFAULT_RIGGING_LENGTH_M,
  deriveFromDraggedCorner,
  deriveFromEndpoints,
  localCorners,
  localEdgeMidpoints,
  localRotationOffsets,
  localXAlongBar,
  pinnedIndexFor,
  rotateXY,
  worldCornersFor,
  worldEndpointsFor,
} from "./stageGeometry"
import { STAGE_PROJECTIONS, project } from "./stageProjection"
import { worldPositionLighting } from "./stageCoords"

function baseRegion(overrides: Partial<StageRegionDto> = {}): StageRegionDto {
  return {
    id: 1,
    uuid: "reg-1",
    name: "Region 1",
    centerX: 0,
    centerY: 0,
    centerZ: 0,
    widthM: 2,
    depthM: 4,
    heightM: 1,
    yawDeg: 0,
    sortOrder: 0,
    ...overrides,
  }
}

function baseRig(overrides: Partial<RiggingDto> = {}): RiggingDto {
  return {
    id: 1,
    uuid: "rig-1",
    name: "Rig 1",
    kind: "TRUSS",
    positionX: 0,
    positionY: 0,
    positionZ: 0,
    yawDeg: 0,
    pitchDeg: 0,
    rollDeg: 0,
    lengthM: 4,
    sortOrder: 0,
    ...overrides,
  }
}

describe("rotateXY", () => {
  it("is identity at 0", () => {
    expect(rotateXY(3, 4, 0)).toEqual([3, 4])
  })

  it("rotates +90° from +X to +Y", () => {
    const [x, y] = rotateXY(1, 0, Math.PI / 2)
    expect(x).toBeCloseTo(0, 12)
    expect(y).toBeCloseTo(1, 12)
  })

  it("preserves magnitude", () => {
    for (const deg of [17, 45, 123, -60]) {
      const [x, y] = rotateXY(2, -5, MathUtils.degToRad(deg))
      expect(Math.hypot(x, y)).toBeCloseTo(Math.hypot(2, -5), 12)
    }
  })
})

describe("localCorners / localEdgeMidpoints / localRotationOffsets", () => {
  it("orders corners CCW from front-left", () => {
    expect(localCorners(2, 4)).toEqual([
      [-1, -2],
      [1, -2],
      [1, 2],
      [-1, 2],
    ])
  })

  it("puts edge midpoints front, right, back, left", () => {
    expect(localEdgeMidpoints(2, 4)).toEqual([
      [0, -2],
      [1, 0],
      [0, 2],
      [-1, 0],
    ])
  })

  it("offsets rotation handles outward from each edge", () => {
    expect(localRotationOffsets(2, 4, 0.5)).toEqual([
      [0, -2.5],
      [1.5, 0],
      [0, 2.5],
      [-1.5, 0],
    ])
  })
})

describe("worldCornersFor", () => {
  it("returns 4 floor corners then 4 directly above them", () => {
    const c = worldCornersFor(baseRegion({ centerZ: 1, heightM: 3 }))
    expect(c).toHaveLength(8)
    for (let i = 0; i < 4; i++) {
      expect(c[i][2]).toBe(1)
      expect(c[i + 4][2]).toBe(4)
      // Same footprint, different height.
      expect(c[i + 4][0]).toBeCloseTo(c[i][0], 12)
      expect(c[i + 4][1]).toBeCloseTo(c[i][1], 12)
    }
  })

  it("treats centerZ as the floor of the box, not its midpoint", () => {
    const c = worldCornersFor(baseRegion({ centerZ: 2, heightM: 5 }))
    expect(Math.min(...c.map((p) => p[2]))).toBe(2)
    expect(Math.max(...c.map((p) => p[2]))).toBe(7)
  })

  it("offsets by the region centre", () => {
    const c = worldCornersFor(baseRegion({ centerX: 10, centerY: -3 }))
    const xs = c.map((p) => p[0])
    const ys = c.map((p) => p[1])
    expect(Math.min(...xs)).toBeCloseTo(9, 12)
    expect(Math.max(...xs)).toBeCloseTo(11, 12)
    expect(Math.min(...ys)).toBeCloseTo(-5, 12)
    expect(Math.max(...ys)).toBeCloseTo(-1, 12)
  })

  it("swaps the footprint's extents at 90° yaw", () => {
    const c = worldCornersFor(baseRegion({ yawDeg: 90 }))
    const xs = c.map((p) => p[0])
    const ys = c.map((p) => p[1])
    // width 2 along X, depth 4 along Y → yawed 90°, they trade places.
    expect(Math.max(...xs) - Math.min(...xs)).toBeCloseTo(4, 12)
    expect(Math.max(...ys) - Math.min(...ys)).toBeCloseTo(2, 12)
  })

  it("falls back to unit dimensions for null fields", () => {
    const c = worldCornersFor(
      baseRegion({ widthM: null, depthM: null, heightM: null, yawDeg: null }),
    )
    const xs = c.map((p) => p[0])
    expect(Math.max(...xs) - Math.min(...xs)).toBeCloseTo(1, 12)
    expect(Math.max(...c.map((p) => p[2]))).toBeCloseTo(1, 12)
  })
})

describe("pinnedIndexFor", () => {
  it("picks the diagonally opposite corner on the floor", () => {
    expect(pinnedIndexFor(0)).toBe(2)
    expect(pinnedIndexFor(1)).toBe(3)
    expect(pinnedIndexFor(2)).toBe(0)
    expect(pinnedIndexFor(3)).toBe(1)
  })

  it("stays within the top tier for top corners", () => {
    expect(pinnedIndexFor(4)).toBe(6)
    expect(pinnedIndexFor(5)).toBe(7)
    expect(pinnedIndexFor(6)).toBe(4)
    expect(pinnedIndexFor(7)).toBe(5)
  })

  it("is an involution", () => {
    for (let i = 0; i < 8; i++) expect(pinnedIndexFor(pinnedIndexFor(i))).toBe(i)
  })
})

describe("deriveFromDraggedCorner", () => {
  it("recovers the original region when the dragged corner hasn't moved", () => {
    const region = baseRegion({ centerX: 3, centerY: 2, yawDeg: 0 })
    const corners = worldCornersFor(region)
    for (let idx = 0; idx < 4; idx++) {
      const pinned = corners[pinnedIndexFor(idx)]
      const d = deriveFromDraggedCorner(
        corners[idx][0],
        corners[idx][1],
        pinned[0],
        pinned[1],
        region.yawDeg ?? 0,
      )
      expect(d.centerX).toBeCloseTo(3, 12)
      expect(d.centerY).toBeCloseTo(2, 12)
      expect(d.widthM).toBeCloseTo(2, 12)
      expect(d.depthM).toBeCloseTo(4, 12)
    }
  })

  it("round-trips through a yawed frame", () => {
    const region = baseRegion({ centerX: -1.5, centerY: 4, yawDeg: 37 })
    const corners = worldCornersFor(region)
    const pinned = corners[pinnedIndexFor(0)]
    const d = deriveFromDraggedCorner(
      corners[0][0],
      corners[0][1],
      pinned[0],
      pinned[1],
      37,
    )
    expect(d.centerX).toBeCloseTo(-1.5, 12)
    expect(d.centerY).toBeCloseTo(4, 12)
    expect(d.widthM).toBeCloseTo(2, 12)
    expect(d.depthM).toBeCloseTo(4, 12)
  })

  it("centres between the dragged and pinned corners", () => {
    const d = deriveFromDraggedCorner(4, 6, 0, 0, 0)
    expect(d.centerX).toBe(2)
    expect(d.centerY).toBe(3)
    expect(d.widthM).toBe(4)
    expect(d.depthM).toBe(6)
  })

  it("never yields negative dimensions when dragged past the pinned corner", () => {
    const d = deriveFromDraggedCorner(-4, -6, 0, 0, 0)
    expect(d.widthM).toBe(4)
    expect(d.depthM).toBe(6)
  })

  it("measures width/depth along the yawed axes, not the world axes", () => {
    // Diagonal purely along world +X, with the box yawed 90°: that world
    // displacement is entirely along the region's local -Y, so it's depth.
    const d = deriveFromDraggedCorner(6, 0, 0, 0, 90)
    expect(d.widthM).toBeCloseTo(0, 12)
    expect(d.depthM).toBeCloseTo(6, 12)
  })
})

describe("worldEndpointsFor", () => {
  it("centres the bar on the rig origin, spanning ±L/2 along local X", () => {
    const [a, b] = worldEndpointsFor(baseRig({ lengthM: 4 }))
    expect(a).toEqual({ x: -2, y: 0, z: 0 })
    expect(b).toEqual({ x: 2, y: 0, z: 0 })
  })

  it("defaults the length when lengthM is null", () => {
    const [a, b] = worldEndpointsFor(baseRig({ lengthM: null }))
    expect(b.x - a.x).toBeCloseTo(DEFAULT_RIGGING_LENGTH_M, 12)
  })

  it("offsets by the rig position", () => {
    const [a, b] = worldEndpointsFor(
      baseRig({ positionX: 1, positionY: 5, positionZ: 4, lengthM: 2 }),
    )
    expect(a).toEqual({ x: 0, y: 5, z: 4 })
    expect(b).toEqual({ x: 2, y: 5, z: 4 })
  })

  it("swings the bar into the upstage axis at 90° yaw", () => {
    const [a, b] = worldEndpointsFor(baseRig({ yawDeg: 90, lengthM: 4 }))
    expect(a.x).toBeCloseTo(0, 12)
    expect(a.y).toBeCloseTo(-2, 12)
    expect(b.x).toBeCloseTo(0, 12)
    expect(b.y).toBeCloseTo(2, 12)
  })

  it("tilts the bar out of the horizontal with roll", () => {
    const [a, b] = worldEndpointsFor(baseRig({ rollDeg: 90, lengthM: 4 }))
    expect(a.z).toBeCloseTo(-2, 12)
    expect(b.z).toBeCloseTo(2, 12)
  })

  it("keeps the endpoints L apart under any pose", () => {
    for (const pose of [
      { yawDeg: 30, pitchDeg: 0, rollDeg: 0 },
      { yawDeg: 0, pitchDeg: 20, rollDeg: 0 },
      { yawDeg: 0, pitchDeg: 0, rollDeg: 15 },
      { yawDeg: 45, pitchDeg: 12, rollDeg: 10 },
      { yawDeg: -110, pitchDeg: -35, rollDeg: 62 },
    ]) {
      const [a, b] = worldEndpointsFor(baseRig({ ...pose, lengthM: 4 }))
      expect(Math.hypot(b.x - a.x, b.y - a.y, b.z - a.z)).toBeCloseTo(4, 10)
    }
  })

  it("returns fresh objects rather than a shared scratch buffer", () => {
    // The module keeps a scratch Vector3 for the rotation; callers hold onto
    // endpoints across frames, so the returned literals must be independent.
    const first = worldEndpointsFor(baseRig({ positionX: 1, lengthM: 2 }))
    const second = worldEndpointsFor(baseRig({ positionX: 9, lengthM: 2 }))
    expect(first[0]).toEqual({ x: 0, y: 0, z: 0 })
    expect(second[0]).toEqual({ x: 8, y: 0, z: 0 })
    expect(first[0]).not.toBe(second[0])
  })
})

describe("localXAlongBar", () => {
  const A = { h: -2, v: 0 }
  const B = { h: 2, v: 0 }

  it("maps the ends to ∓L/2 and the middle to 0", () => {
    expect(localXAlongBar(A, A, B, 4)).toBeCloseTo(-2, 12)
    expect(localXAlongBar(B, A, B, 4)).toBeCloseTo(2, 12)
    expect(localXAlongBar({ h: 0, v: 0 }, A, B, 4)).toBeCloseTo(0, 12)
  })

  it("clamps a pointer past either end to the end of the bar", () => {
    expect(localXAlongBar({ h: -50, v: 0 }, A, B, 4)).toBeCloseTo(-2, 12)
    expect(localXAlongBar({ h: 50, v: 0 }, A, B, 4)).toBeCloseTo(2, 12)
  })

  it("projects perpendicular offset away — off-axis pointer slides along the bar", () => {
    expect(localXAlongBar({ h: 1, v: 7 }, A, B, 4)).toBeCloseTo(1, 12)
    expect(localXAlongBar({ h: 1, v: -7 }, A, B, 4)).toBeCloseTo(1, 12)
  })

  it("works for a diagonal (rolled) bar", () => {
    const a = { h: 0, v: 0 }
    const b = { h: 3, v: -4 } // projected length 5
    expect(localXAlongBar(a, a, b, 10)).toBeCloseTo(-5, 12)
    expect(localXAlongBar(b, a, b, 10)).toBeCloseTo(5, 12)
    // 3/5 of the way along.
    expect(localXAlongBar({ h: 1.8, v: -2.4 }, a, b, 10)).toBeCloseTo(1, 12)
  })

  it("returns 0 rather than NaN for a degenerate (end-on) bar", () => {
    // Callers are expected to refuse the drag first; this is the belt-and-braces.
    const p = { h: 5, v: 5 }
    expect(localXAlongBar(p, A, A, 4)).toBe(0)
  })

  it("keeps a real truss-mounted fixture ON its bar through a front-elevation drag", () => {
    // LX1 from a live show file: yawed 8.95° and rolled 5.85°, so its depth and
    // height both change along its length. This is the case where preserving the
    // out-of-plane axis (correct for a free fixture) would slide the fixture off
    // the bar — hence the along-bar constraint.
    const rig = baseRig({
      positionX: -1.693661952034864,
      positionY: -0.47584055518390256,
      positionZ: 5.608001270888884,
      yawDeg: 8.953616886766364,
      pitchDeg: 0,
      rollDeg: 5.846203310689311,
      lengthM: 12.605693242881367,
    })
    const [wa, wb] = worldEndpointsFor(rig)
    const front = STAGE_PROJECTIONS.front
    const pa = project(wa, front)
    const pb = project(wb, front)

    for (const target of [-6, -3, 0, 2.5, 6]) {
      // Pointer somewhere off the bar, at the height of the wanted position.
      const t = (target + rig.lengthM! / 2) / rig.lengthM!
      const pointer = {
        h: pa.h + (pb.h - pa.h) * t,
        v: pa.v + (pb.v - pa.v) * t + 1.5, // 1.5 m of perpendicular slop
      }
      const localX = localXAlongBar(pointer, pa, pb, rig.lengthM!)

      // Composing that local X back through the rig must land exactly on the bar:
      // the point must be collinear with the two endpoints in 3D.
      const world = worldPositionLighting(
        { stageX: localX, stageY: 0, stageZ: 0, riggingUuid: rig.uuid } as never,
        [rig],
      )!
      const along = {
        x: wb.x - wa.x,
        y: wb.y - wa.y,
        z: wb.z - wa.z,
      }
      const rel = { x: world.x - wa.x, y: world.y - wa.y, z: world.z - wa.z }
      // Cross product magnitude ~0 ⇒ on the line through the endpoints.
      const cross = Math.hypot(
        rel.y * along.z - rel.z * along.y,
        rel.z * along.x - rel.x * along.z,
        rel.x * along.y - rel.y * along.x,
      )
      expect(cross).toBeCloseTo(0, 8)
      expect(Math.abs(localX)).toBeLessThanOrEqual(rig.lengthM! / 2 + 1e-9)
    }
  })
})

describe("deriveFromEndpoints", () => {
  it("round-trips a rig's own endpoints back to its pose", () => {
    // pitchDeg is excluded: it's a twist along the bar's own axis and is not
    // recoverable from endpoint positions, so the inverse forces it to 0.
    for (const pose of [
      { yawDeg: 0, rollDeg: 0 },
      { yawDeg: 37, rollDeg: 0 },
      { yawDeg: 0, rollDeg: 22 },
      { yawDeg: -64, rollDeg: 11 },
      { yawDeg: 128, rollDeg: -40 },
    ]) {
      const rig = baseRig({
        ...pose,
        pitchDeg: 0,
        positionX: 1.5,
        positionY: 3,
        positionZ: 4.5,
        lengthM: 5,
      })
      const [a, b] = worldEndpointsFor(rig)
      const d = deriveFromEndpoints(a, b)
      expect(d.positionX).toBeCloseTo(1.5, 10)
      expect(d.positionY).toBeCloseTo(3, 10)
      expect(d.positionZ).toBeCloseTo(4.5, 10)
      expect(d.lengthM).toBeCloseTo(5, 10)
      expect(d.yawDeg).toBeCloseTo(pose.yawDeg, 10)
      expect(d.rollDeg).toBeCloseTo(pose.rollDeg, 10)
      expect(d.pitchDeg).toBe(0)
    }
  })

  it("puts the position at the endpoint midpoint", () => {
    const d = deriveFromEndpoints({ x: 0, y: 0, z: 0 }, { x: 4, y: 2, z: 6 })
    expect(d.positionX).toBe(2)
    expect(d.positionY).toBe(1)
    expect(d.positionZ).toBe(3)
  })

  it("derives yaw from the horizontal heading, measured from +X", () => {
    const d = deriveFromEndpoints({ x: 0, y: 0, z: 0 }, { x: 0, y: 3, z: 0 })
    expect(d.yawDeg).toBeCloseTo(90, 10)
  })

  it("derives roll as elevation above the horizontal", () => {
    const d = deriveFromEndpoints({ x: 0, y: 0, z: 0 }, { x: 1, y: 0, z: 1 })
    expect(d.rollDeg).toBeCloseTo(45, 10)
    expect(d.yawDeg).toBeCloseTo(0, 10)
  })

  it("flips yaw by 180° when the endpoints are swapped", () => {
    const a = { x: 0, y: 0, z: 0 }
    const b = { x: 2, y: 0, z: 0 }
    expect(deriveFromEndpoints(a, b).yawDeg).toBeCloseTo(0, 10)
    expect(Math.abs(deriveFromEndpoints(b, a).yawDeg)).toBeCloseTo(180, 10)
  })

  it("yields a zero length and no NaN for coincident endpoints", () => {
    const d = deriveFromEndpoints({ x: 1, y: 2, z: 3 }, { x: 1, y: 2, z: 3 })
    expect(d.lengthM).toBe(0)
    expect(Number.isNaN(d.yawDeg)).toBe(false)
    expect(Number.isNaN(d.rollDeg)).toBe(false)
  })
})
