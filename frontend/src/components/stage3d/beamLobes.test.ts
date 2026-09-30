import { describe, it, expect } from "vitest"
import { MathUtils, Vector3 } from "three"
import { computeLobeDirection, regionShadowMask } from "./beamLobes"

const HALF_ANGLE_RAD = MathUtils.degToRad(15)
const COS_CONE = Math.cos(HALF_ANGLE_RAD)
const SIN_CONE = Math.sin(HALF_ANGLE_RAD)

function makeRegion(boundingCenter: Vector3, boundingRadius: number) {
  return { boundingCenter, boundingRadius }
}

describe("regionShadowMask", () => {
  const origin = new Vector3(0, 0, 0)
  const dir = new Vector3(0, 0, 1)
  const beamLength = 8
  const mask = (center: Vector3, radius: number, length = beamLength) =>
    regionShadowMask(origin, dir, length, COS_CONE, SIN_CONE, [makeRegion(center, radius)])

  it("sets the bit of an aligned region within reach", () => {
    expect(mask(new Vector3(0, 0, 4), 0.5)).toBe(1)
  })

  it("leaves out a region behind the fixture", () => {
    expect(mask(new Vector3(0, 0, -10), 0.5)).toBe(0)
  })

  it("leaves out a region on the axis past the beam's drawn length, and takes it once the beam reaches it", () => {
    expect(mask(new Vector3(0, 0, 12), 0.5)).toBe(0)
    expect(mask(new Vector3(0, 0, 12), 0.5, 20)).toBe(1)
  })

  it("keeps a region whose bounding sphere holds the lens (a fixture mounted on it)", () => {
    expect(mask(new Vector3(0.1, 0, 0), 1.0)).toBe(1)
  })

  it("leaves out a region in reach but outside the cone's angular boundary", () => {
    expect(mask(new Vector3(0, 0, -4), 0.5)).toBe(0)
  })

  it("answers one bit per region, by index", () => {
    const regions = [
      makeRegion(new Vector3(0, 0, 4), 0.5), // reached → bit 0
      makeRegion(new Vector3(0, 0, -10), 0.5), // not
      makeRegion(new Vector3(0, 0, 6), 0.5), // reached → bit 2
    ]
    expect(regionShadowMask(origin, dir, beamLength, COS_CONE, SIN_CONE, regions)).toBe(0b101)
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
