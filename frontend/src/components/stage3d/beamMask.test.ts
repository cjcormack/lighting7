import { describe, expect, it } from 'vitest'
import {
  BEAM_HARDNESS_GLSL,
  BEAM_MASK_GLSL,
  BLADE_ANGLE_OFFSET,
  BLADE_DEPTH_STEPS,
  beamHardness,
  beamMask,
  focusBlur,
  bladeLine,
  MASK_EDGE_HARD,
  MASK_EDGE_SOFT,
  MASK_SOFT_CENTRE,
  packBlade,
  packBlades,
} from './beamMask'
import { resolveEdgeHardness } from './beamOptics'
import type { SliderPropertyDescriptor } from '../../store/fixtures'

/** Blades in the wire's order: top, bottom, left, right. */
function blades(...b: Array<[number, number]>) {
  return b.map(([depth, angleDeg]) => ({ depth, angleDeg }))
}

describe('the beam mask', () => {
  it('is full inside the field and nothing past its edge', () => {
    expect(beamMask(0, 0, 0, 1, 0)).toBeCloseTo(1, 9)
    expect(beamMask(0.5, 0, 0, 1, 0)).toBeCloseTo(1, 9)
    expect(beamMask(1.001, 0, 0, 1, 0)).toBe(0)
    expect(beamMask(0.8, 0.8, 0, 1, 0)).toBe(0)
  })

  it('cuts a profile hard and feathers a flood', () => {
    // At 0.9 of the field a hard edge has not begun to roll off; a soft one is well into it.
    expect(beamMask(0.9, 0, 0, 1, 0)).toBeCloseTo(1, 6)
    expect(beamMask(0.9, 0, 0, 1, 1)).toBeLessThan(0.5)
    // Softening only ever takes light away from the edge, never adds it past the field.
    for (const r of [0.2, 0.5, 0.8, 0.95]) {
      expect(beamMask(r, 0, 0, 1, 1)).toBeLessThanOrEqual(beamMask(r, 0, 0, 1, 0) + 1e-9)
    }
  })

  it('draws a segment as a rectangle: full to its corners, which a disc is not', () => {
    expect(beamMask(0.8, 0.8, 0.3, 1, 0)).toBeCloseTo(1, 6)
    expect(beamMask(0.8, 0.8, 0, 1, 0)).toBe(0)
    expect(beamMask(0, 1.01, 0.3, 1, 0)).toBe(0)
  })

  it('closes with the iris', () => {
    expect(beamMask(0.3, 0, 0, 0.42, 0)).toBeCloseTo(1, 6)
    expect(beamMask(0.5, 0, 0, 0.42, 0)).toBe(0)
    // Fully open is the same as no iris.
    expect(beamMask(0.7, 0, 0, 1, 0.4)).toBeCloseTo(beamMask(0.7, 0, 0, 0.9999, 0.4), 6)
  })

  it('writes the GLSL from the same constants as the twin', () => {
    expect(BEAM_MASK_GLSL).toContain(MASK_EDGE_HARD.toFixed(4))
    expect(BEAM_MASK_GLSL).toContain(MASK_EDGE_SOFT.toFixed(4))
    expect(BEAM_MASK_GLSL).toContain(MASK_SOFT_CENTRE.toFixed(4))
    expect(BEAM_MASK_GLSL).toContain(`${BLADE_DEPTH_STEPS}.0`)
    expect(BEAM_MASK_GLSL).toContain(`${BLADE_ANGLE_OFFSET}.0`)
    expect(BEAM_MASK_GLSL).toContain('float beamMask(vec2 uv, float aspect, float iris, float soft, vec2 blades)')
  })

  it('draws an oval as a circle once the caller divides v by |aspect|', () => {
    // A CP62's 44 × 21 field: along u the edge is at 1; along v it is at the tangent ratio.
    const ratio = Math.tan((21 * Math.PI) / 360) / Math.tan((44 * Math.PI) / 360)
    const oval = -ratio
    expect(beamMask(0.9, 0, oval, 1, 0)).toBeCloseTo(1, 6)
    expect(beamMask(0, 0.9 * ratio / Math.abs(oval), oval, 1, 0)).toBeCloseTo(1, 6)
    expect(beamMask(0, (1.05 * ratio) / Math.abs(oval), oval, 1, 0)).toBe(0)
    // Where a round beam of the wide field would still be lit, the oval is not.
    expect(beamMask(0, 0.9 / Math.abs(oval), oval, 1, 0)).toBe(0)
  })
})

describe('the blades', () => {
  it('pack two to a float, exactly, and none packs to zero', () => {
    expect(packBlades(null)).toEqual([0, 0])
    expect(packBlades(blades([0, 0], [0, 12], [0, 0], [0, 0]))).toEqual([0, 0])
    const [a, b] = packBlades(blades([1, 30], [1, -30], [1, 30], [1, -30]))
    // The largest a float32 must hold: 24 bits.
    expect(Math.max(a, b)).toBeLessThan(2 ** 24)
    expect(Math.fround(a)).toBe(a)
    expect(Math.fround(b)).toBe(b)
    expect(Math.floor(a / 4096)).toBe(packBlade(1, 30))
    expect(a - Math.floor(a / 4096) * 4096).toBe(packBlade(1, -30))
  })

  it('cut a straight edge: the top blade takes everything above its line, whatever the x', () => {
    const [a, b] = packBlades(blades([0.3, 0], [0, 0], [0, 0], [0, 0]))
    // Depth 0.3 of the diameter puts the edge at v = 1 − 0.6 = 0.4.
    for (const u of [-0.8, -0.3, 0, 0.3, 0.8]) {
      expect(beamMask(u, 0.36, 0, 1, 0, a, b)).toBeGreaterThan(0.99)
      expect(beamMask(u, 0.43, 0, 1, 0, a, b)).toBe(0)
    }
    // …and leaves the rest of the field alone.
    expect(beamMask(0, -0.9, 0, 1, 0, a, b)).toBeCloseTo(beamMask(0, -0.9, 0, 1, 0), 9)
  })

  it('name the edge of the light they cut: left from +u, right from −u, bottom from −v', () => {
    const [la, lb] = packBlades(blades([0, 0], [0, 0], [0.25, 0], [0, 0]))
    expect(beamMask(0.8, 0, 0, 1, 0, la, lb)).toBe(0)
    expect(beamMask(-0.8, 0, 0, 1, 0, la, lb)).toBeGreaterThan(0.99)
    const [ra, rb] = packBlades(blades([0, 0], [0, 0], [0, 0], [0.25, 0]))
    expect(beamMask(-0.8, 0, 0, 1, 0, ra, rb)).toBe(0)
    const [ba, bb] = packBlades(blades([0, 0], [0.25, 0], [0, 0], [0, 0]))
    expect(beamMask(0, -0.8, 0, 1, 0, ba, bb)).toBe(0)
    expect(beamMask(0, 0.8, 0, 1, 0, ba, bb)).toBeGreaterThan(0.99)
  })

  it('turn about the middle of their edge', () => {
    const [a, b] = packBlades(blades([0.25, 20], [0, 0], [0, 0], [0, 0]))
    // The middle of the edge stays where an unturned blade puts it…
    expect(beamMask(0, 0.46, 0, 1, 0, a, b)).toBeGreaterThan(0.5)
    expect(beamMask(0, 0.54, 0, 1, 0, a, b)).toBeLessThan(0.5)
    // …and either side of it the edge has tilted, so the two ends disagree.
    const left = beamMask(0.6, 0.5, 0, 1, 0, a, b)
    const right = beamMask(-0.6, 0.5, 0, 1, 0, a, b)
    expect(Math.abs(left - right)).toBeGreaterThan(0.9)
    // The preview's line is the same line (at a depth the packing holds exactly: 21 of 63 steps).
    const exact = 21 / 63
    const [ea, eb] = packBlades(blades([exact, 20], [0, 0], [0, 0], [0, 0]))
    const line = bladeLine(0, exact, 20)
    expect(line.px).toBeCloseTo(0, 9)
    expect(line.py).toBeCloseTo(1 / 3, 9)
    const onLine = beamMask(line.px - line.ny * 0.5, line.py + line.nx * 0.5, 0, 1, 0, ea, eb)
    expect(onLine).toBeGreaterThan(0.1)
    expect(onLine).toBeLessThan(0.9)
  })

  it('close the beam at full depth', () => {
    const [a, b] = packBlades(blades([1, 0], [0, 0], [0, 0], [0, 0]))
    expect(beamMask(0, -0.9, 0, 1, 0, a, b)).toBe(0)
  })
})

describe('the focus blur', () => {
  // A profile family's depth of field (`DEPTH_OF_FIELD`, pinned against the Revolution in
  // archetype.test.ts, which can import the archetype).
  const DOF = 3

  it('is nothing at the focal plane, or without a focus channel', () => {
    expect(focusBlur(6, 6, DOF)).toBe(0)
    expect(focusBlur(24, 24, DOF)).toBe(0)
    expect(focusBlur(3, -1, DOF)).toBe(0)
  })

  it('is the relative focus error times the depth of field, at any throw', () => {
    // Focused at 10 m, landing at 5 m: half the focal distance out.
    expect(focusBlur(5, 10, 3)).toBeCloseTo(1.5, 9)
    // The same relative error is the same blur at 4 m and at 24 m — a lens radius no longer scales it.
    expect(focusBlur(4.5, 4, 3)).toBeCloseTo(focusBlur(27, 24, 3), 9)
  })

  it('is symmetric in the relative error', () => {
    for (const f of [3, 10, 24]) {
      for (const e of [0.01, 0.05, 0.125, 0.3]) {
        expect(focusBlur(f * (1 + e), f, DOF)).toBeCloseTo(focusBlur(f * (1 - e), f, DOF), 9)
        expect(focusBlur(f * (1 + e), f, DOF)).toBeCloseTo(e * DOF, 9)
      }
    }
  })

  it('is softer for a larger depth of field', () => {
    expect(focusBlur(27, 24, 4.5)).toBeGreaterThan(focusBlur(27, 24, 3))
    expect(focusBlur(27, 24, 3)).toBeGreaterThan(focusBlur(27, 24, 1))
    expect(focusBlur(27, 24, 0)).toBe(0)
  })

  it('stays finite at and behind the aperture', () => {
    expect(Number.isFinite(focusBlur(0, 6, DOF))).toBe(true)
    expect(Number.isFinite(focusBlur(-1, 6, DOF))).toBe(true)
    expect(Number.isFinite(focusBlur(3, 0, DOF))).toBe(true)
  })
})

describe('the beam edge', () => {
  /** The mover:profile family's softness (`SOFTNESS` in bodies/archetype.ts): a 0.88 cap. */
  const PROFILE_SOFTNESS = 0.12

  it("keeps the family's hardness without a focus channel", () => {
    expect(beamHardness(0.8, -1, 3, MASK_EDGE_SOFT)).toBe(0.8)
    expect(resolveEdgeHardness(PROFILE_SOFTNESS, undefined, 0, false)).toBeCloseTo(0.88, 9)
  })

  it("lifts the family's cap at the focal plane, so the edge there is as hard as the mask draws", () => {
    const capped = resolveEdgeHardness(PROFILE_SOFTNESS, undefined, 0, false)
    const lifted = resolveEdgeHardness(PROFILE_SOFTNESS, undefined, 0, true)
    expect(capped).toBeCloseTo(0.88, 9)
    expect(beamHardness(lifted, 24, focusBlur(24, 24, 3), MASK_EDGE_SOFT)).toBe(1)
    // Off the plane the blur softens it below what the cap would have held.
    expect(beamHardness(lifted, 21, focusBlur(24, 21, 3), MASK_EDGE_SOFT)).toBeLessThan(capped)
  })

  it('sharpens only at the focal plane, and never past the frost-softened hardness', () => {
    // An unfrosted spot: hard at its focus, soft once the blur is a fully soft edge's width.
    expect(beamHardness(0.9, 5, 0, MASK_EDGE_SOFT)).toBeCloseTo(0.9, 9)
    expect(beamHardness(0.9, 5, MASK_EDGE_SOFT, MASK_EDGE_SOFT)).toBe(0)
    // Frost pulled the hardness down to 0.2: it stays soft even at the focus (the Robe ColorSpot
    // has both channels, and frost did nothing there before) — the lift does not lift frost.
    expect(beamHardness(0.2, 5, 0, MASK_EDGE_SOFT)).toBeCloseTo(0.2, 9)
    const frost: SliderPropertyDescriptor = {
      type: 'slider', name: 'frost', displayName: 'Frost', category: 'frost', channel: { universe: 1, channelNo: 1 }, min: 0, max: 255,
    }
    expect(resolveEdgeHardness(0.12, frost, 255, true)).toBeCloseTo(0, 9)
    expect(resolveEdgeHardness(0.12, frost, 0, true)).toBe(1)
  })

  it('is one chunk the haze and the surfaces both call', () => {
    expect(BEAM_HARDNESS_GLSL).toContain('float beamHardness(')
    expect(BEAM_HARDNESS_GLSL).toContain('float focusBlur(')
  })
})
