import { describe, expect, it } from 'vitest'
import type { SettingOption } from '../store/fixtures'
import {
  BAND_STEP_S,
  cycleColour,
  isAnimatedAt,
  isAnimatedBand,
  isBlackout,
  settingColourAt,
  sourceBandColour,
  sourceBandLevel,
  wheelPalette,
} from './colourBands'

function opts(pairs: [string, number, Partial<SettingOption>?][]): SettingOption[] {
  return pairs.map(([name, level, over]) => ({ name, level, displayName: name, ...over }))
}

// The Robe ColorSpot 575's first wheel, cut down: open, three colours, the scroll and random bands.
const ROBE = opts([
  ['OPEN', 0, { colourPreview: '#FFFFFF' }],
  ['RED', 140, { colourPreview: '#FF0000' }],
  ['BLUE', 146, { colourPreview: '#0000FF' }],
  ['YELLOW', 160, { colourPreview: '#FFFF00' }],
  ['SCROLL_CW', 190, { noColour: true }],
  ['RANDOM', 244, { noColour: true }],
])

describe('a colour band', () => {
  it('is its preview on a colour, and animates only where it is marked as having none', () => {
    expect(settingColourAt(ROBE, 150, 0)).toBe('#0000FF')
    expect(isAnimatedAt(ROBE, 150)).toBe(false)
    expect(isAnimatedAt(ROBE, 200)).toBe(true)
    expect(isAnimatedAt(ROBE, 250)).toBe(true)
    // An option with no preview and no marker says nothing: it does not animate.
    expect(isAnimatedBand({ name: 'X', level: 0, displayName: 'X' })).toBe(false)
    expect(settingColourAt(opts([['X', 0]]), 0, 5)).toBeUndefined()
  })

  it("cycles a scroll band through the wheel's own colours, in level order, each once", () => {
    expect(wheelPalette(ROBE)).toEqual(['#ffffff', '#ff0000', '#0000ff', '#ffff00'])
    // Holding on each colour for most of its step.
    expect(settingColourAt(ROBE, 200, 0)).toBe('#ffffff')
    expect(settingColourAt(ROBE, 200, BAND_STEP_S * 1.1)).toBe('#ff0000')
    expect(settingColourAt(ROBE, 200, BAND_STEP_S * 2.1)).toBe('#0000ff')
    expect(settingColourAt(ROBE, 200, BAND_STEP_S * 3.1)).toBe('#ffff00')
    // And round again.
    expect(settingColourAt(ROBE, 200, BAND_STEP_S * 4.1)).toBe('#ffffff')
  })

  it('moves between two colours at the end of a step rather than snapping', () => {
    const mid = settingColourAt(ROBE, 200, BAND_STEP_S * 1.83)!
    expect(mid).not.toBe('#ff0000')
    expect(mid).not.toBe('#0000ff')
    // Red going to blue: red falling, blue rising, no green.
    const [r, g, b] = [mid.slice(1, 3), mid.slice(3, 5), mid.slice(5, 7)].map((h) => parseInt(h, 16))
    expect(g).toBe(0)
    expect(r).toBeGreaterThan(0)
    expect(b).toBeGreaterThan(0)
  })

  it('is a pure function of the time handed in', () => {
    for (const t of [0, 0.4, 1.9, 7.25, 100]) {
      expect(settingColourAt(ROBE, 200, t)).toBe(settingColourAt(ROBE, 200, t))
      expect(cycleColour(wheelPalette(ROBE), t)).toBe(settingColourAt(ROBE, 250, t))
    }
    // A time before the clock started, or no time at all, is the first colour.
    expect(settingColourAt(ROBE, 200, -3)).toBe('#ffffff')
    expect(settingColourAt(ROBE, 200, Number.NaN)).toBe('#ffffff')
  })

  it('leaves a blackout out of the cycle, and draws one dark', () => {
    const slender = opts([
      ['BLACKOUT', 0, { colourPreview: '#000000' }],
      ['RED', 8, { colourPreview: '#FF0000' }],
      ['GREEN', 25, { colourPreview: '#00FF00' }],
      ['AUTO', 200, { noColour: true }],
    ])
    expect(wheelPalette(slender)).toEqual(['#ff0000', '#00ff00'])
    expect(isBlackout(settingColourAt(slender, 0, 0))).toBe(true)
    expect(sourceBandLevel(settingColourAt(slender, 0, 0))).toBe(0)
    expect(sourceBandLevel(settingColourAt(slender, 8, 0))).toBe(1)
  })

  it('draws a source band that says nothing open white and lit — never black for want of data', () => {
    expect(sourceBandColour(undefined)).toBe('#FFFFFF')
    expect(sourceBandLevel(undefined)).toBe(1)
    // A scroll band on a wheel with no previews at all has nothing to cycle.
    expect(settingColourAt(opts([['SCROLL', 0, { noColour: true }]]), 0, 3)).toBeUndefined()
  })
})
