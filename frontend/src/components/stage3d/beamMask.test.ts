import { describe, expect, it } from 'vitest'
import { BEAM_HARDNESS_GLSL, BEAM_MASK_GLSL, beamHardness, beamMask, MASK_EDGE_HARD, MASK_EDGE_SOFT, MASK_SOFT_CENTRE } from './beamMask'

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
  })
})

describe('the beam edge', () => {
  it("keeps the family's hardness without a focus channel", () => {
    expect(beamHardness(0.8, -1, 3, 1.5)).toBe(0.8)
  })

  it('sharpens only at the focal plane, and never past the frost-softened hardness', () => {
    // An unfrosted spot: hard at its focus, soft well away from it.
    expect(beamHardness(0.9, 5, 0, 1.5)).toBeCloseTo(0.9, 9)
    expect(beamHardness(0.9, 5, 2, 1.5)).toBe(0)
    // Frost pulled the hardness down to 0.2: it stays soft even at the focus (the Robe ColorSpot
    // has both channels, and frost did nothing there before).
    expect(beamHardness(0.2, 5, 0, 1.5)).toBeCloseTo(0.2, 9)
  })

  it('is one chunk the haze and the surfaces both call', () => {
    expect(BEAM_HARDNESS_GLSL).toContain('float beamHardness(')
  })
})
