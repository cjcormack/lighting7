import { describe, expect, it } from 'vitest'
import {
  DEFAULT_LAMP,
  driftedKelvin,
  kelvinToLinearRgb,
  lampFor,
  lampHex,
  lampLinearRgb,
  lampOutput,
  unitLamp,
  type Lamp,
} from './lamp'
import { indexLanterns, type Lantern } from './lanterns'
import libraryJson from '../../../src/main/resources/lanterns/library.json'

const library = libraryJson as Lantern[]
const lanterns = indexLanterns(library)

const tungsten = (kelvin: number, watts = 575): Lamp => ({ watts, kelvin, kind: 'TUNGSTEN' })

function rgbOf(hex: string): [number, number, number] {
  const n = parseInt(hex.slice(1), 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

describe('kelvinToLinearRgb', () => {
  it('is about white at D65 and warmer — less blue — the lower the temperature', () => {
    const d65 = kelvinToLinearRgb(6500)
    expect(Math.min(...d65)).toBeGreaterThan(0.9)
    const blues = [2000, 2700, 3200, 4000, 5600].map((k) => kelvinToLinearRgb(k)[2])
    expect(blues).toEqual([...blues].sort((a, b) => a - b))
    expect(kelvinToLinearRgb(3200)[0]).toBe(1)
  })
})

describe('lampLinearRgb', () => {
  it('draws the adapted white neutral and tungsten a warm cream, not white', () => {
    const neutral = lampLinearRgb(5000)
    expect(neutral.every((c) => c > 0.999)).toBe(true)
    const [r, g, b] = rgbOf(lampHex(tungsten(3200), 1))
    expect(r).toBe(255)
    expect(g).toBeLessThan(225)
    expect(b).toBeLessThan(175)
    // Warmer than the old fallback colour, `#fff8d5`.
    expect(b).toBeLessThan(0xd5)
  })
})

describe('driftedKelvin', () => {
  it('cools a tungsten lamp as it dims and leaves an LED alone', () => {
    const lamp = tungsten(3200)
    expect(driftedKelvin(lamp, 1)).toBe(3200)
    expect(driftedKelvin(lamp, 0.5)).toBeCloseTo(2937, -1)
    expect(driftedKelvin(lamp, 0.1)).toBeCloseTo(2408, -1)
    expect(driftedKelvin(lamp, 0)).toBeGreaterThan(1000)
    expect(driftedKelvin({ watts: 15, kelvin: 3000, kind: 'LED' }, 0.1)).toBe(3000)
  })

  it('makes a dimmed tungsten beam visibly warmer', () => {
    const full = rgbOf(lampHex(tungsten(3200), 1))
    const low = rgbOf(lampHex(tungsten(3200), 0.15))
    expect(low[2]).toBeLessThan(full[2])
    expect(low[1]).toBeLessThan(full[1])
  })
})

describe('lampOutput', () => {
  it('is 1 for a 575 W tungsten profile and scales with power and efficacy', () => {
    expect(lampOutput(DEFAULT_LAMP)).toBe(1)
    expect(lampOutput(tungsten(3200, 1150))).toBeCloseTo(2)
    const led = lampOutput({ watts: 15, kelvin: 3000, kind: 'LED' })
    expect(led).toBeGreaterThan(0.15)
    expect(led).toBeLessThan(0.25)
  })
})

describe('lampHex with a gel', () => {
  it('filters the lamp: a gel can only darken it, and a CTB on tungsten is bluer than the lamp', () => {
    const lamp = tungsten(3200)
    const bare = rgbOf(lampHex(lamp, 1))
    const ctb = rgbOf(lampHex(lamp, 1, '#7fa6e8'))
    for (let i = 0; i < 3; i++) expect(ctb[i]).toBeLessThanOrEqual(bare[i])
    expect(ctb[2] / ctb[0]).toBeGreaterThan(bare[2] / bare[0])
    expect(lampHex(lamp, 1, 'not a colour')).toBe(lampHex(lamp, 1))
  })
})

describe('lampFor and unitLamp', () => {
  it('take the patch over the lantern, the lantern over the default, and nothing for a DMX type', () => {
    const s4 = lanterns.byId.get('s4-26')!
    expect(lampFor({}, s4, true)).toEqual({ watts: 575, kelvin: 3200, kind: 'TUNGSTEN' })
    expect(lampFor({ lampWatts: 750, lampKelvin: 3000 }, s4, true)).toEqual({ watts: 750, kelvin: 3000, kind: 'TUNGSTEN' })
    expect(lampFor({ lampWatts: 0 }, null, true)).toEqual(DEFAULT_LAMP)
    expect(lampFor({ lampWatts: 750 }, s4, false)).toBeNull()
  })

  it('draws a generic dimmer that names no lantern with its kind’s default lantern', () => {
    expect(unitLamp({ lanternType: null }, true, 'GENERIC', lanterns)).toEqual({ watts: 15, kelvin: 3000, kind: 'LED' })
    expect(unitLamp({ lanternType: 'cantata-f' }, true, 'GENERIC', lanterns)?.watts).toBe(1200)
  })

  it('every library lantern declares a lamp the view can draw', () => {
    for (const l of library) {
      expect(l.watts, l.id).toBeGreaterThan(0)
      expect(l.kelvin, l.id).toBeGreaterThanOrEqual(1000)
    }
  })
})
