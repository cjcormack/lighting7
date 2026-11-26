import { describe, expect, it } from 'vitest'
import vector from '../../../src/test/resources/fixture/colourSplit.fixture.json'
import type { SettingOption } from '../store/fixtures'
import { splitLabel, splitLevel, splitReading, wheelSplits } from './colourSplit'
import { settingColourAt, settingSplitAt } from './colourBands'

/**
 * The desk's vector (`ColourSplitTest` reads it too): every wheel that splits, its splits, and the
 * reading of every level 0–255, so a level reads the same on both sides (wheel-colour-chase plan P2).
 */
interface VectorWheel {
  typeKey: string
  attribute: string
  options: { name: string; level: number; continuousLevel?: number | null }[]
  splits: { a: string; b: string; level: number }[]
  readings: (string | number | null)[][]
}

const WHEELS = (vector as { wheels: VectorWheel[] }).wheels

function optionsOf(wheel: VectorWheel): SettingOption[] {
  return wheel.options.map((o) => ({
    name: o.name,
    level: o.level,
    displayName: o.name,
    ...(o.continuousLevel != null ? { continuousLevel: o.continuousLevel } : {}),
  }))
}

describe('colourSplit over the desk’s vector', () => {
  it('holds every wheel of the vector', () => {
    expect(WHEELS.map((w) => `${w.typeKey}:${w.attribute}`)).toEqual([
      'martin-mac-250-mode-4:colourWheel',
      'robe-color-spot-575-mode-2:colour1',
      'robe-color-spot-575-mode-2:colour2',
    ])
  })

  for (const wheel of WHEELS) {
    const options = optionsOf(wheel)

    it(`${wheel.typeKey} ${wheel.attribute} splits`, () => {
      expect(wheelSplits(options)).toBe(true)
    })

    it(`${wheel.typeKey} ${wheel.attribute} reads every level as the desk does`, () => {
      expect(wheel.readings).toHaveLength(256)
      for (const [level, a, b, share] of wheel.readings) {
        const reading = splitReading(options, level as number)
        if (a == null) {
          expect(reading, `level ${level}`).toBeNull()
        } else {
          expect(reading?.a.name, `level ${level}`).toBe(a)
          expect(reading?.b.name, `level ${level}`).toBe(b)
          expect(reading?.share, `level ${level}`).toBeCloseTo(share as number, 5)
        }
      }
    })

    it(`${wheel.typeKey} ${wheel.attribute} splits each pair of neighbours where the desk does`, () => {
      for (const split of wheel.splits) {
        expect(splitLevel(options, split.a, split.b)).toBe(split.level)
        expect(splitLevel(options, split.b, split.a)).toBe(split.level)
      }
      const declared = options.filter((o) => o.continuousLevel != null)
      expect(splitLevel(options, declared[0].name, declared[2].name)).toBeNull()
      expect(splitLevel(options, declared[0].name, declared[0].name)).toBeNull()
    })
  }

  it('reads nothing on a wheel with fewer than two declared levels', () => {
    const one: SettingOption[] = [
      { name: 'OPEN', level: 0, displayName: 'Open' },
      { name: 'RED', level: 10, displayName: 'Red', continuousLevel: 4 },
    ]
    expect(wheelSplits(one)).toBe(false)
    expect(splitReading(one, 4)).toBeNull()
  })
})

describe('a split level in words and colour', () => {
  const OPTIONS: SettingOption[] = [
    { name: 'OPEN', level: 0, displayName: 'Open', colourPreview: '#ffffff', continuousLevel: 1 },
    { name: 'RED', level: 156, displayName: 'Red', colourPreview: '#ff0000', continuousLevel: 12 },
    { name: 'YELLOW', level: 160, displayName: 'Yellow', colourPreview: '#ffff00', continuousLevel: 23 },
    { name: 'SCROLL', level: 200, displayName: 'Scroll', noColour: true },
  ]

  it('reads a level between two slots as both, and a slot held whole as itself', () => {
    expect(splitLabel(OPTIONS, 17)).toBe('Red / Yellow')
    expect(splitLabel(OPTIONS, 12)).toBe('Red')
    expect(splitLabel(OPTIONS, 23)).toBe('Yellow')
    expect(splitLabel(OPTIONS, 156)).toBeNull()
  })

  it('mixes the two slots by share for the beam, and leaves the indexed bands as they were', () => {
    expect(settingSplitAt(OPTIONS, 17)).toEqual({ a: '#ff0000', b: '#ffff00', share: 5 / 11 })
    expect(settingColourAt(OPTIONS, 17, 0)).toBe('#ff7400')
    expect(settingColourAt(OPTIONS, 0, 0)).toBe('#ffffff')
    expect(settingColourAt(OPTIONS, 158, 0)).toBe('#ff0000')
    expect(settingSplitAt(OPTIONS, 158)).toBeNull()
  })
})
