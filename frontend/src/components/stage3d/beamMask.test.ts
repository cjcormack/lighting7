import { describe, expect, it } from 'vitest'
import {
  BEAM_HARDNESS_GLSL,
  BEAM_MASK_GLSL,
  BLADE_ANGLE_OFFSET,
  BLADE_ANGLE_STEP_DEG,
  BLADE_DEPTH_STEPS,
  beamHardness,
  beamMask,
  focusBlur,
  bladeLine,
  MASK_EDGE_HARD,
  MASK_EDGE_SOFT,
  MASK_SOFT_CENTRE,
  MAX_PACKED_BLADE_ANGLE_DEG,
  packBlade,
  packBlades,
  unpackBlade,
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
    expect(BEAM_MASK_GLSL).toContain(`* ${BLADE_ANGLE_STEP_DEG.toFixed(4)}`)
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
    // The preview's line is the same line (at a depth the packing holds exactly: 21 steps).
    const exact = 21 / BLADE_DEPTH_STEPS
    const [ea, eb] = packBlades(blades([exact, 20], [0, 0], [0, 0], [0, 0]))
    const line = bladeLine(0, exact, 20)
    expect(line.px).toBeCloseTo(0, 9)
    expect(line.py).toBeCloseTo(1 - 2 * exact, 9)
    const onLine = beamMask(line.px - line.ny * 0.5, line.py + line.nx * 0.5, 0, 1, 0, ea, eb)
    expect(onLine).toBeGreaterThan(0.1)
    expect(onLine).toBeLessThan(0.9)
  })

  it('pack the angle in 1.5° steps to ±45°, exactly in a float32, at every step', () => {
    expect(BLADE_ANGLE_STEP_DEG).toBe(1.5)
    expect(MAX_PACKED_BLADE_ANGLE_DEG).toBe(45)
    const steps = MAX_PACKED_BLADE_ANGLE_DEG / BLADE_ANGLE_STEP_DEG
    // 61 values in six bits, offset clear of 0 and 63.
    expect(steps * 2 + 1).toBe(61)
    expect(BLADE_ANGLE_OFFSET - steps).toBeGreaterThan(0)
    expect(BLADE_ANGLE_OFFSET + steps).toBeLessThan(64)
    for (let k = -steps; k <= steps; k++) {
      const angle = k * BLADE_ANGLE_STEP_DEG
      for (const depth of [1 / BLADE_DEPTH_STEPS, 0.5, 1]) {
        const [a, b] = packBlades(blades([depth, angle], [depth, 0 - angle], [depth, angle], [depth, 0 - angle]))
        expect(Math.max(a, b)).toBeLessThan(2 ** 24)
        expect(Math.fround(a)).toBe(a)
        expect(Math.fround(b)).toBe(b)
        // The decode the GLSL makes gives back the exact angle, on both halves of both floats.
        const top = Math.floor(a / 4096)
        const left = Math.floor(b / 4096)
        expect(unpackBlade(top)?.angleDeg).toBe(angle)
        expect(unpackBlade(a - top * 4096)?.angleDeg).toBe(0 - angle)
        expect(unpackBlade(left)?.angleDeg).toBe(angle)
        expect(unpackBlade(b - left * 4096)?.angleDeg).toBe(0 - angle)
        expect(unpackBlade(top)?.depth).toBeCloseTo(depth, 1)
      }
    }
  })

  it('round an angle to the nearest step and clamp it to ±45°', () => {
    // A lantern's whole degrees land on the nearest step; its ±30° is a step exactly.
    expect(unpackBlade(packBlade(1, 1))?.angleDeg).toBe(1.5)
    expect(unpackBlade(packBlade(1, 30))?.angleDeg).toBe(30)
    expect(unpackBlade(packBlade(1, -30))?.angleDeg).toBe(-30)
    expect(unpackBlade(packBlade(1, 60))?.angleDeg).toBe(45)
    expect(unpackBlade(packBlade(1, -90))?.angleDeg).toBe(-45)
    expect(unpackBlade(packBlade(1, Number.NaN))?.angleDeg).toBe(0)
    expect(unpackBlade(packBlade(0, 30))).toBeNull()
  })

  it('turn about the middle of their own edge at the full ±45°', () => {
    const exact = 21 / BLADE_DEPTH_STEPS
    for (const angle of [45, -45]) {
      const [a, b] = packBlades(blades([exact, angle], [0, 0], [0, 0], [0, 0]))
      const line = bladeLine(0, exact, angle)
      // The middle of the edge has not moved: on the axis, a third of the way in from the top.
      expect(line.px).toBeCloseTo(0, 9)
      expect(line.py).toBeCloseTo(1 - 2 * exact, 9)
      // Along the turned edge, both ways from its middle, the mask is on the edge…
      const tx = -line.ny
      const ty = line.nx
      for (const t of [-0.3, 0, 0.3]) {
        const m = beamMask(line.px + tx * t, line.py + ty * t, 0, 1, 0, a, b)
        expect(m).toBeGreaterThan(0.1)
        expect(m).toBeLessThan(0.9)
      }
      // …past it the light is cut, and short of it lit.
      expect(beamMask(line.px + line.nx * 0.1, line.py + line.ny * 0.1, 0, 1, 0, a, b)).toBe(0)
      expect(beamMask(line.px - line.nx * 0.1, line.py - line.ny * 0.1, 0, 1, 0, a, b)).toBeGreaterThan(0.99)
      // And the edge is turned 45° from square: its normal is half up, half across.
      expect(Math.abs(line.nx)).toBeCloseTo(Math.SQRT1_2, 9)
    }
  })

  it('turn clockwise for a positive angle, as seen from behind the lantern', () => {
    // The viewer's left is +u. A positive turn brings the top blade down on the viewer's right
    // (−u) and lifts it on their left — clockwise to someone behind the lantern.
    const [a, b] = packBlades(blades([0.25, 20], [0, 0], [0, 0], [0, 0]))
    expect(beamMask(-0.6, 0.4, 0, 1, 0, a, b)).toBe(0)
    expect(beamMask(0.6, 0.4, 0, 1, 0, a, b)).toBeGreaterThan(0.99)
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
