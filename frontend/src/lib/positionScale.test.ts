import { describe, expect, it } from 'vitest'
import {
  CENTRE_MAX,
  CENTRE_MIN,
  centreToDmx,
  clampCentre,
  dmxToCentre,
  formatCentre,
  legacyDegreesToCentre,
  positionOnScale,
} from './positionScale'

/**
 * The operator's position scale (venue-visit plan D3): centre-relative coarse DMX, `-128..127`,
 * 0 at DMX 128, one unit a step. The numbers are the Kotlin resolver's (`TemplateResolver`'s
 * centre arm and `centreIntentOf`), so a value typed here and a palette resolved there agree.
 */
describe('positionScale', () => {
  it('is exactly DMX − 128 on a 0–255 axis, both ways, at every byte', () => {
    expect([CENTRE_MIN, CENTRE_MAX]).toEqual([-128, 127])
    for (let dmx = 0; dmx <= 255; dmx++) {
      expect(dmxToCentre(dmx)).toBe(dmx - 128)
      expect(centreToDmx(dmx - 128)).toBe(dmx)
    }
    expect(dmxToCentre(128)).toBe(0)
    expect(dmxToCentre(1)).toBe(-127)
    expect(dmxToCentre(255)).toBe(127)
  })

  it('clamps to the scale and rounds to whole steps', () => {
    expect(clampCentre(300)).toBe(127)
    expect(clampCentre(-300)).toBe(-128)
    expect(clampCentre(12.6)).toBe(13)
    expect(centreToDmx(400)).toBe(255)
  })

  it('maps a narrower axis onto the scale as the same fraction of its own travel', () => {
    const range = { min: 10, max: 110 }
    expect(centreToDmx(-128, range)).toBe(10)
    expect(centreToDmx(127, range)).toBe(110)
    expect(dmxToCentre(110, range)).toBe(127)
  })

  it('reads which side of centre a value is on', () => {
    expect(formatCentre(12)).toBe('+12')
    expect(formatCentre(0)).toBe('0')
    expect(formatCentre(-12)).toBe('-12')
  })

  it('reads a stored degree through the old editor’s 540° / 270°, and a centre value as itself', () => {
    expect(legacyDegreesToCentre(270, 540)).toBe(0)
    expect(legacyDegreesToCentre(135, 270)).toBe(0)
    expect(positionOnScale({ pan: 0, tilt: 270, unit: 'degrees' })).toEqual({ pan: -128, tilt: 127 })
    expect(positionOnScale({ pan: -12, tilt: 30, unit: 'centre' })).toEqual({ pan: -12, tilt: 30 })
  })
})
