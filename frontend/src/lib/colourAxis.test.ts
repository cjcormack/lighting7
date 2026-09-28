import { describe, expect, it } from 'vitest'
import type { FixturePropertyTarget } from '@/api/surfacesApi'
import {
  COLOUR_AXES,
  COLOUR_AXIS_LABELS,
  COLOUR_AXIS_SWATCHES,
  axisSuffix,
  effectiveAxis,
  isPlainHue,
  sameAxis,
  withAxis,
} from './colourAxis'

/**
 * Pins the client half of the colour-axis vocabulary against lighting7's `midi/ColourAxis.kt`, the
 * way `maskPicker.test.ts` pins the family lists: the four serial names are the wire, and a
 * divergence would be a chip the server refuses or a payload the client cannot label.
 */
describe('colourAxis', () => {
  it('has exactly the four axes the backend serialises, in display order', () => {
    expect(COLOUR_AXES).toEqual(['hue', 'hueFine', 'saturation', 'brightness'])
    for (const axis of COLOUR_AXES) {
      expect(COLOUR_AXIS_LABELS[axis]).toBeTruthy()
      expect(COLOUR_AXIS_SWATCHES[axis]).toMatch(/^linear-gradient/)
    }
  })

  it('treats null, absent and hue as one axis', () => {
    expect(isPlainHue(null)).toBe(true)
    expect(isPlainHue(undefined)).toBe(true)
    expect(isPlainHue('hue')).toBe(true)
    expect(isPlainHue('hueFine')).toBe(false)
    expect(effectiveAxis(null)).toBe('hue')
    expect(sameAxis(null, 'hue')).toBe(true)
    expect(sameAxis(undefined, null)).toBe(true)
    expect(sameAxis('saturation', 'saturation')).toBe(true)
    expect(sameAxis('saturation', null)).toBe(false)
  })

  it('suffixes a property label for every axis but hue', () => {
    expect(axisSuffix(null)).toBe('')
    expect(axisSuffix('hue')).toBe('')
    expect(axisSuffix('hueFine')).toBe(' · hue fine')
    expect(axisSuffix('saturation')).toBe(' · sat')
    expect(axisSuffix('brightness')).toBe(' · bright')
  })

  it('writes an axis onto a target, and leaves the field absent for hue', () => {
    const base: FixturePropertyTarget = { type: 'fixtureProperty', fixtureKey: 'hex-1', propertyName: 'rgbColour' }
    expect(withAxis(base, 'saturation')).toEqual({ ...base, colourAxis: 'saturation' })
    // Absent rather than null or 'hue': the form every pre-axis row has and the server writes.
    expect(withAxis(base, null)).toEqual(base)
    expect('colourAxis' in withAxis(base, 'hue')).toBe(false)
    expect('colourAxis' in withAxis({ ...base, colourAxis: 'brightness' }, null)).toBe(false)
  })
})
