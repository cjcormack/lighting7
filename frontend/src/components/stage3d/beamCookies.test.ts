import { describe, it, expect, vi } from "vitest"
import { MathUtils, Vector3 } from "three"
import {
  computeLobeDirection,
  cullRegionCookies,
  updateFloorCookie,
  updateWallCookie,
} from "./beamCookies"

const HALF_ANGLE_RAD = MathUtils.degToRad(15)
const COS_CONE = Math.cos(HALF_ANGLE_RAD)
const SIN_CONE = Math.sin(HALF_ANGLE_RAD)
const LOBE = 0

function makeRegion(cookieCenter: Vector3, cookieBoundingRadius: number) {
  return { cookieCenter, cookieBoundingRadius }
}

describe("cullRegionCookies", () => {
  const origin = new Vector3(0, 0, 0)
  const dir = new Vector3(0, 0, 1)
  const beamLength = 8
  const slot = 3

  function makeCullWriter() {
    const writeRegionVisibility = vi.fn()
    return { emitters: { writeRegionVisibility }, writeRegionVisibility }
  }

  it("marks an aligned region within reach as visible", () => {
    const { emitters, writeRegionVisibility } = makeCullWriter()
    const regions = [makeRegion(new Vector3(0, 0, 4), 0.5)]
    cullRegionCookies(emitters, slot, LOBE, origin, dir, beamLength, COS_CONE, SIN_CONE, regions)
    expect(writeRegionVisibility).toHaveBeenCalledWith(slot, LOBE, 0, true)
  })

  it("hides a region behind the fixture (distance + radius > beamLength)", () => {
    const { emitters, writeRegionVisibility } = makeCullWriter()
    const regions = [makeRegion(new Vector3(0, 0, -10), 0.5)]
    cullRegionCookies(emitters, slot, LOBE, origin, dir, beamLength, COS_CONE, SIN_CONE, regions)
    expect(writeRegionVisibility).toHaveBeenCalledWith(slot, LOBE, 0, false)
  })

  it("hides a region on the cone axis but past beamLength", () => {
    const { emitters, writeRegionVisibility } = makeCullWriter()
    const regions = [makeRegion(new Vector3(0, 0, 12), 0.5)]
    cullRegionCookies(emitters, slot, LOBE, origin, dir, beamLength, COS_CONE, SIN_CONE, regions)
    expect(writeRegionVisibility).toHaveBeenCalledWith(slot, LOBE, 0, false)
  })

  it("marks visible when origin is inside the region's bounding sphere (fixture mounted on it)", () => {
    const { emitters, writeRegionVisibility } = makeCullWriter()
    const regions = [makeRegion(new Vector3(0.1, 0, 0), 1.0)]
    cullRegionCookies(emitters, slot, LOBE, origin, dir, beamLength, COS_CONE, SIN_CONE, regions)
    expect(writeRegionVisibility).toHaveBeenCalledWith(slot, LOBE, 0, true)
  })

  it("hides a region inside reach but outside the cone's angular boundary", () => {
    const { emitters, writeRegionVisibility } = makeCullWriter()
    const regions = [makeRegion(new Vector3(0, 0, -4), 0.5)]
    cullRegionCookies(emitters, slot, LOBE, origin, dir, beamLength, COS_CONE, SIN_CONE, regions)
    expect(writeRegionVisibility).toHaveBeenCalledWith(slot, LOBE, 0, false)
  })

  it("returns the visibility bits as the shadow mask", () => {
    const { emitters } = makeCullWriter()
    const regions = [
      makeRegion(new Vector3(0, 0, 4), 0.5), // visible → bit 0
      makeRegion(new Vector3(0, 0, -10), 0.5), // hidden
      makeRegion(new Vector3(0, 0, 6), 0.5), // visible → bit 2
    ]
    const mask = cullRegionCookies(
      emitters, slot, LOBE, origin, dir, beamLength, COS_CONE, SIN_CONE, regions,
    )
    expect(mask).toBe(0b101)
  })
})

describe("updateFloorCookie", () => {
  const side = 4
  const slot = 7

  function makeWriter() {
    const writeFloorMatrix = vi.fn()
    return { emitters: { writeFloorMatrix }, writeFloorMatrix }
  }

  it("hides the floor and skips repositioning when the beam is aimed up (dir.y >= sinCone)", () => {
    const { emitters, writeFloorMatrix } = makeWriter()
    const origin = new Vector3(0, 5, 0)
    const dir = new Vector3(0, 0.5, 0)
    updateFloorCookie(emitters, slot, LOBE, origin, dir, 8, SIN_CONE, side)
    expect(writeFloorMatrix).toHaveBeenCalledWith(slot, LOBE, false, 0, 0, 0)
  })

  it("projects straight-down beam to the fixture's XZ and scales by side", () => {
    const { emitters, writeFloorMatrix } = makeWriter()
    const origin = new Vector3(3, 5, -2)
    const dir = new Vector3(0, -1, 0)
    updateFloorCookie(emitters, slot, LOBE, origin, dir, 8, SIN_CONE, side)
    expect(writeFloorMatrix).toHaveBeenCalledTimes(1)
    const [argSlot, argLobe, visible, cx, cz, sideOut] = writeFloorMatrix.mock.calls[0]
    expect(argSlot).toBe(slot)
    expect(argLobe).toBe(LOBE)
    expect(visible).toBe(true)
    expect(cx).toBeCloseTo(3, 10)
    expect(cz).toBeCloseTo(-2, 10)
    expect(sideOut).toBe(side)
  })

  it("offsets along dir.xz when the beam is tilted toward the stage", () => {
    const { emitters, writeFloorMatrix } = makeWriter()
    const origin = new Vector3(0, 5, 0)
    const dir = new Vector3(0.6, -0.8, 0).normalize()
    const beamLength = 100
    updateFloorCookie(emitters, slot, LOBE, origin, dir, beamLength, SIN_CONE, side)
    const t = -origin.y / dir.y
    const [, , visible, cx, cz, sideOut] = writeFloorMatrix.mock.calls[0]
    expect(visible).toBe(true)
    expect(cx).toBeCloseTo(origin.x + t * dir.x, 9)
    expect(cz).toBeCloseTo(origin.z + t * dir.z, 9)
    expect(sideOut).toBe(side)
  })

  it("clamps to beamLength when -origin.y / dir.y exceeds it (dir.y tiny)", () => {
    const { emitters, writeFloorMatrix } = makeWriter()
    const origin = new Vector3(0, 5, 0)
    const dir = new Vector3(0.6, -0.01, 0.8)
    const beamLength = 4
    updateFloorCookie(emitters, slot, LOBE, origin, dir, beamLength, SIN_CONE, side)
    const [, , visible, cx, cz] = writeFloorMatrix.mock.calls[0]
    expect(visible).toBe(true)
    expect(cx).toBeCloseTo(origin.x + beamLength * dir.x, 9)
    expect(cz).toBeCloseTo(origin.z + beamLength * dir.z, 9)
  })
})

describe("computeLobeDirection", () => {
  const dir = new Vector3(0, -1, 0)
  const bx = new Vector3(1, 0, 0)
  const by = new Vector3(0, 0, 1)
  const SPLAY = MathUtils.degToRad(12)

  it("tips each lobe exactly splayRad off the beam axis, unit length", () => {
    for (let i = 0; i < 3; i++) {
      const out = computeLobeDirection(dir, bx, by, SPLAY, (Math.PI * 2 * i) / 3, new Vector3())
      expect(out.length()).toBeCloseTo(1, 9)
      expect(out.angleTo(dir)).toBeCloseTo(SPLAY, 9)
    }
  })

  it("spaces N lobes evenly around the axis", () => {
    const a = computeLobeDirection(dir, bx, by, SPLAY, 0, new Vector3())
    const b = computeLobeDirection(dir, bx, by, SPLAY, (Math.PI * 2) / 3, new Vector3())
    const c = computeLobeDirection(dir, bx, by, SPLAY, (Math.PI * 4) / 3, new Vector3())
    // Pairwise separations are equal for an even split.
    expect(a.angleTo(b)).toBeCloseTo(b.angleTo(c), 9)
    expect(b.angleTo(c)).toBeCloseTo(c.angleTo(a), 9)
  })

  it("rotates the arrangement with the phase angle", () => {
    const zero = computeLobeDirection(dir, bx, by, SPLAY, 0, new Vector3())
    const quarter = computeLobeDirection(dir, bx, by, SPLAY, Math.PI / 2, new Vector3())
    // Phase 0 tips toward bx; phase π/2 tips toward by.
    expect(zero.x).toBeGreaterThan(0.01)
    expect(Math.abs(zero.z)).toBeLessThan(1e-9)
    expect(quarter.z).toBeGreaterThan(0.01)
    expect(Math.abs(quarter.x)).toBeLessThan(1e-9)
  })

  it("writes into the caller's vector so the frame loop allocates nothing", () => {
    const out = new Vector3()
    expect(computeLobeDirection(dir, bx, by, SPLAY, 1, out)).toBe(out)
  })
})

describe("updateWallCookie", () => {
  const side = 4
  const slot = 2
  // A 10 m wide × 6 m tall wall at the upstage boundary z = -8.
  const wall = { z: -8, halfWidth: 5, height: 6 }

  function makeWriter() {
    const writeWallMatrix = vi.fn()
    return { emitters: { writeWallMatrix }, writeWallMatrix }
  }

  it("hides the wall cookie when the beam points downstage (away from the wall)", () => {
    const { emitters, writeWallMatrix } = makeWriter()
    const origin = new Vector3(0, 4, -4)
    const dir = new Vector3(0, 0, 1)
    updateWallCookie(emitters, slot, LOBE, origin, dir, 8, SIN_CONE, side, wall)
    expect(writeWallMatrix).toHaveBeenCalledWith(slot, LOBE, false, 0, 0, 0, 0)
  })

  it("hides the wall cookie when the wall is beyond beam reach", () => {
    const { emitters, writeWallMatrix } = makeWriter()
    const origin = new Vector3(0, 4, 4) // 12 m from the wall, beam reaches 8
    const dir = new Vector3(0, 0, -1)
    updateWallCookie(emitters, slot, LOBE, origin, dir, 8, SIN_CONE, side, wall)
    expect(writeWallMatrix).toHaveBeenCalledWith(slot, LOBE, false, 0, 0, 0, 0)
  })

  it("centres the footprint where the beam axis meets the wall plane", () => {
    const { emitters, writeWallMatrix } = makeWriter()
    const origin = new Vector3(1, 3, -2)
    const dir = new Vector3(0, 0, -1)
    updateWallCookie(emitters, slot, LOBE, origin, dir, 8, SIN_CONE, side, wall)
    const [, , visible, cx, cy, sx, sy] = writeWallMatrix.mock.calls[0]
    expect(visible).toBe(true)
    expect(cx).toBeCloseTo(1, 9)
    expect(cy).toBeCloseTo(3, 9)
    expect(sx).toBe(side)
    expect(sy).toBe(side)
  })

  it("clamps the footprint to the wall rectangle instead of glowing past its edge", () => {
    const { emitters, writeWallMatrix } = makeWriter()
    // Aimed at the top-right corner: the raw side×side quad would overhang.
    const origin = new Vector3(4.5, 5.5, -2)
    const dir = new Vector3(0, 0, -1)
    updateWallCookie(emitters, slot, LOBE, origin, dir, 8, SIN_CONE, side, wall)
    const [, , visible, cx, cy, sx, sy] = writeWallMatrix.mock.calls[0]
    expect(visible).toBe(true)
    // x span [2.5, 6.5] clamps to [2.5, 5]; y span [3.5, 7.5] clamps to [3.5, 6].
    expect(cx).toBeCloseTo(3.75, 9)
    expect(sx).toBeCloseTo(2.5, 9)
    expect(cy).toBeCloseTo(4.75, 9)
    expect(sy).toBeCloseTo(2.5, 9)
  })

  it("hides the cookie when the footprint misses the wall rectangle entirely", () => {
    const { emitters, writeWallMatrix } = makeWriter()
    // Beam axis crosses the wall plane far outside the wall's width.
    const origin = new Vector3(20, 3, -2)
    const dir = new Vector3(0, 0, -1)
    updateWallCookie(emitters, slot, LOBE, origin, dir, 8, SIN_CONE, side, wall)
    expect(writeWallMatrix).toHaveBeenCalledWith(slot, LOBE, false, 0, 0, 0, 0)
  })
})
