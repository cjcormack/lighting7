import { describe, expect, it } from 'vitest'
import { chan, sliderProp } from '@/test/fixtureFactories'
import { annotatesDegrees, degreesToDmx, dmxToDegrees } from './axisDegrees'

/** A 540° pan on 0–255, the common mover, and its inverted twin. */
const PAN = sliderProp('pan', 'pan', chan(1), { degMin: 0, degMax: 540 })
const INVERTED = sliderProp('pan', 'pan', chan(1), { degMin: 0, degMax: 540, inverted: true })
const SILENT = sliderProp('pan', 'pan', chan(1))

describe('axisDegrees', () => {
  it('maps a byte to travel degrees, with literals at the ends and the middle', () => {
    expect(dmxToDegrees(0, PAN)).toBe(0)
    expect(dmxToDegrees(255, PAN)).toBe(540)
    expect(dmxToDegrees(128, PAN)).toBeCloseTo(271.06, 2)
  })

  it('maps a degree back to the nearest byte, clamped to the range', () => {
    expect(degreesToDmx(0, PAN)).toBe(0)
    // 270 / 540 × 255 = 127.5, rounded up.
    expect(degreesToDmx(270, PAN)).toBe(128)
    expect(degreesToDmx(540, PAN)).toBe(255)
    expect(degreesToDmx(900, PAN)).toBe(255)
    expect(degreesToDmx(-10, PAN)).toBe(0)
  })

  it('honours an inverted axis both ways', () => {
    expect(dmxToDegrees(0, INVERTED)).toBe(540)
    expect(degreesToDmx(540, INVERTED)).toBe(0)
    expect(degreesToDmx(0, INVERTED)).toBe(255)
  })

  it('answers null both ways on a head that does not annotate, and says so', () => {
    expect(dmxToDegrees(128, SILENT)).toBeNull()
    expect(degreesToDmx(270, SILENT)).toBeNull()
    expect(annotatesDegrees(SILENT)).toBe(false)
    expect(annotatesDegrees(PAN)).toBe(true)
    expect(annotatesDegrees(undefined)).toBe(false)
  })
})
