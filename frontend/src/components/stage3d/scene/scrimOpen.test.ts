import { describe, expect, it } from 'vitest'
import { SCRIM_OPEN_GLSL, SCRIM_THREAD_SHARE, scrimOpen, scrimShare } from './scrimOpen'

const cosDeg = (deg: number) => Math.cos((deg * Math.PI) / 180)

describe('open(θ) (scrim plan D3)', () => {
  it('passes 49 %, 40 % and 9 % of a sharkstooth at 0°, 45° and 70°', () => {
    const r = SCRIM_THREAD_SHARE.SHARKSTOOTH
    expect(Math.round(scrimOpen(cosDeg(0), r) * 100)).toBe(49)
    expect(Math.round(scrimOpen(cosDeg(45), r) * 100)).toBe(40)
    expect(Math.round(scrimOpen(cosDeg(70), r) * 100)).toBe(9)
    // Past cos θ = r the threads close every hole.
    expect(scrimOpen(cosDeg(80), r)).toBe(0)
    expect(scrimOpen(0, r)).toBe(0)
  })

  it('is the formula, from either side, and a finer net is more open', () => {
    for (const deg of [0, 10, 30, 60, 72]) {
      for (const r of [0.15, 0.3]) {
        const c = cosDeg(deg)
        expect(scrimOpen(c, r)).toBeCloseTo((1 - r) * Math.max(0, 1 - r / c), 12)
        expect(scrimOpen(-c, r)).toBe(scrimOpen(c, r))
      }
      expect(scrimOpen(cosDeg(deg), SCRIM_THREAD_SHARE.BOBBINET)).toBeGreaterThan(scrimOpen(cosDeg(deg), SCRIM_THREAD_SHARE.SHARKSTOOTH))
    }
  })

  it('stacks gathered net as open^c, and never below one layer', () => {
    const r = SCRIM_THREAD_SHARE.SHARKSTOOTH
    for (const c of [1, 1.5, 2, 3.4]) {
      expect(scrimShare(cosDeg(30), r, c)).toBeCloseTo(scrimOpen(cosDeg(30), r) ** c, 12)
    }
    expect(scrimShare(1, r, 2)).toBeCloseTo(0.49 * 0.49, 12)
    expect(scrimShare(1, r, 0.5)).toBe(scrimOpen(1, r))
    expect(scrimShare(cosDeg(85), r, 2)).toBe(0)
  })

  it('writes the GLSL twin in the same steps', () => {
    expect(SCRIM_OPEN_GLSL).toContain('float c = max(abs(cosTheta), 1.0e-6);')
    expect(SCRIM_OPEN_GLSL).toContain('return (1.0 - r) * max(0.0, 1.0 - r / c);')
    expect(SCRIM_OPEN_GLSL).toContain('float open = scrimOpen(cosTheta, r);')
    expect(SCRIM_OPEN_GLSL).toContain('return open > 0.0 ? pow(open, max(1.0, gather)) : 0.0;')
  })
})
