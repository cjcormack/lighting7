import { describe, expect, it } from 'vitest'
import type { StrobeBand } from '../store/fixtures'
import { chan, settingProp, sliderProp } from '../test/fixtureFactories'
import {
  FLASH_HZ_MAX,
  FLASH_S,
  RANDOM_HZ_MAX,
  SHIMMER_DEPTH,
  SHIMMER_LEVEL,
  strobeAnimates,
  strobeBandAt,
  strobeFactor,
  strobeLevelAt,
  strobeRateAt,
} from './strobeBands'

const STROBE: StrobeBand = { from: 10, to: 255, kind: 'STROBE', hzMin: 1, hzMax: 20 }
const FAST_TO_SLOW: StrobeBand = { from: 50, to: 72, kind: 'STROBE', hzMin: 1, hzMax: 10, inverted: true }

/** Flashes in [timeS, timeS + 1): rising edges of the sampled level, a sample a millisecond. */
function flashesInSecond(level: (t: number) => number, fromS: number): number {
  let flashes = 0
  let previous = level(fromS - 0.001)
  for (let ms = 0; ms < 1000; ms++) {
    const v = level(fromS + ms / 1000)
    // WCAG counts a flash as a pair of opposing changes of 10% or more; a rise of that size starts one.
    if (v - previous >= 0.1) flashes++
    previous = v
  }
  return flashes
}

describe('strobeBandAt', () => {
  const slider = sliderProp('strobe', 'strobe', chan(1), {
    strobeBands: [
      { from: 0, to: 19, kind: 'CLOSED' },
      { from: 20, to: 49, kind: 'OPEN' },
      FAST_TO_SLOW,
    ],
  })

  it("finds a slider's band by value, and nothing where none is declared", () => {
    expect(strobeBandAt(slider, 0)?.kind).toBe('CLOSED')
    expect(strobeBandAt(slider, 19)?.kind).toBe('CLOSED')
    expect(strobeBandAt(slider, 20)?.kind).toBe('OPEN')
    expect(strobeBandAt(slider, 60)).toBe(FAST_TO_SLOW)
    expect(strobeBandAt(slider, 210)).toBeUndefined()
    expect(strobeBandAt(sliderProp('strobe', 'strobe', chan(1)), 0)).toBeUndefined()
  })

  it("reads a setting's option as a band running to the next option", () => {
    const setting = settingProp('shutter', 'strobe', chan(2), [
      { name: 'SHUT', level: 0, displayName: 'Shut', strobeKind: 'CLOSED' },
      { name: 'OPEN', level: 64, displayName: 'Open', strobeKind: 'OPEN' },
      { name: 'FLASH', level: 128, displayName: 'Flash', strobeKind: 'STROBE', hzMin: 2, hzMax: 12, strobeInverted: true },
    ])
    expect(strobeBandAt(setting, 63)).toEqual({ from: 0, to: 63, kind: 'CLOSED', hzMin: undefined, hzMax: undefined, inverted: undefined })
    expect(strobeBandAt(setting, 64)?.kind).toBe('OPEN')
    expect(strobeBandAt(setting, 200)).toEqual({ from: 128, to: 255, kind: 'STROBE', hzMin: 2, hzMax: 12, inverted: true })
  })
})

describe('strobeRateAt', () => {
  it('runs hzMin to hzMax across the band, and the other way when inverted', () => {
    expect(strobeRateAt(STROBE, 10)).toBe(1)
    expect(strobeRateAt(STROBE, 255)).toBe(20)
    expect(strobeRateAt(FAST_TO_SLOW, 50)).toBe(10)
    expect(strobeRateAt(FAST_TO_SLOW, 72)).toBe(1)
    expect(strobeRateAt({ from: 5, to: 5, kind: 'RANDOM', hzMin: 4, hzMax: 4 }, 5)).toBe(4)
  })
})

describe('strobeLevelAt', () => {
  it('draws closed dark, open and undeclared lit, whatever the time', () => {
    for (const t of [0, 0.05, 1.7]) {
      expect(strobeLevelAt({ from: 0, to: 19, kind: 'CLOSED' }, 5, t)).toBe(0)
      expect(strobeLevelAt({ from: 20, to: 49, kind: 'OPEN' }, 25, t)).toBe(1)
      expect(strobeLevelAt(undefined, 210, t)).toBe(1)
    }
  })

  it('flashes a slow strobe at its rate, one short flash a cycle', () => {
    // DMX 10 is 1 Hz: lit for the flash at the top of each second, dark for the rest.
    expect(strobeLevelAt(STROBE, 10, 0)).toBe(1)
    expect(strobeLevelAt(STROBE, 10, FLASH_S / 2)).toBe(1)
    expect(strobeLevelAt(STROBE, 10, 0.5)).toBe(0)
    expect(strobeLevelAt(STROBE, 10, 3.02)).toBe(1)
    expect(flashesInSecond((t) => strobeLevelAt(STROBE, 10, t), 0.5)).toBe(1)
  })

  it('never draws more than three flashes in any second, at any rate, of any kind (WCAG 2.3.1)', () => {
    for (const kind of ['STROBE', 'RANDOM', 'PULSE'] as const) {
      for (const hz of [0.5, 1, 2, 2.5, 3, 3.5, 5, 10, 20, 30]) {
        const band: StrobeBand = { from: 0, to: 255, kind, hzMin: hz, hzMax: hz }
        for (const seed of [0, 7, 513]) {
          for (const from of [0, 0.33, 4.71, 12.05]) {
            const n = flashesInSecond((t) => strobeLevelAt(band, 128, t, seed), from)
            expect(n, `${kind} at ${hz} Hz from ${from} s, seed ${seed}`).toBeLessThanOrEqual(3)
          }
        }
      }
    }
  })

  it('flashes at full contrast up to the cap, and shimmers above it', () => {
    const at = (kind: StrobeBand['kind'], hz: number) => {
      const band: StrobeBand = { from: 0, to: 255, kind, hzMin: hz, hzMax: hz }
      let lo = 1
      let hi = 0
      for (let ms = 0; ms < 3000; ms++) {
        const v = strobeLevelAt(band, 0, ms / 1000, 3)
        lo = Math.min(lo, v)
        hi = Math.max(hi, v)
      }
      return { lo, hi }
    }
    expect(at('STROBE', FLASH_HZ_MAX)).toEqual({ lo: 0, hi: 1 })
    expect(at('RANDOM', RANDOM_HZ_MAX)).toEqual({ lo: 0, hi: 1 })
    const pulse = at('PULSE', 1)
    expect(pulse.lo).toBeCloseTo(0, 3)
    expect(pulse.hi).toBeCloseTo(1, 3)
    for (const [kind, hz] of [['STROBE', 20], ['RANDOM', 8], ['PULSE', 5]] as const) {
      const { lo, hi } = at(kind, hz)
      expect(hi, `${kind} at ${hz} Hz`).toBeLessThanOrEqual(SHIMMER_LEVEL + 1e-9)
      expect(lo, `${kind} at ${hz} Hz`).toBeGreaterThanOrEqual(SHIMMER_LEVEL * (1 - SHIMMER_DEPTH) - 1e-9)
      // Under WCAG's 10% change in luminance: not a flash.
      expect(hi - lo).toBeLessThan(0.1)
    }
  })

  it('is a function of the time handed in, and two random strobes keep their own dice', () => {
    const random: StrobeBand = { from: 0, to: 255, kind: 'RANDOM', hzMin: 2, hzMax: 2 }
    const trace = (seed: number) => Array.from({ length: 400 }, (_, i) => strobeLevelAt(random, 0, i / 100, seed))
    expect(trace(1)).toEqual(trace(1))
    expect(trace(1)).not.toEqual(trace(2))
  })
})

describe('strobeFactor and strobeAnimates', () => {
  const strobe = sliderProp('strobe', 'strobe', chan(1), { strobeBands: [{ from: 0, to: 0, kind: 'OPEN' }, { ...STROBE, from: 1 }] })
  const shutter = settingProp('shutter', 'strobe', chan(2), [
    { name: 'SHUT', level: 0, displayName: 'Shut', strobeKind: 'CLOSED' },
    { name: 'OPEN', level: 128, displayName: 'Open', strobeKind: 'OPEN' },
  ])
  type StrobeProp = typeof strobe | typeof shutter
  const props: StrobeProp[] = [strobe, shutter]

  it('multiplies every strobe channel: a closed shutter wins over an open strobe', () => {
    const levels = new Map<StrobeProp, number>([[strobe, 0], [shutter, 0]])
    const read = (p: StrobeProp) => levels.get(p) ?? 0
    expect(strobeFactor(props, read, 0)).toBe(0)
    levels.set(shutter, 128)
    expect(strobeFactor(props, read, 0)).toBe(1)
    expect(strobeFactor([], read, 0)).toBe(1)
  })

  it('animates only while a band moves with time', () => {
    const levels = new Map<StrobeProp, number>([[strobe, 0], [shutter, 128]])
    const read = (p: StrobeProp) => levels.get(p) ?? 0
    expect(strobeAnimates(props, read)).toBe(false)
    levels.set(strobe, 40)
    expect(strobeAnimates(props, read)).toBe(true)
    // A closed shutter holds the light dark whatever the strobe does, so nothing moves with time.
    levels.set(shutter, 0)
    expect(strobeAnimates(props, read)).toBe(false)
  })
})
