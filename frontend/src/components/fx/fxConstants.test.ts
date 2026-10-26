import { describe, expect, it } from 'vitest'
import { SPEED_STEPS, elementModeSummary, fxSpeedLabel, getCycleLabel } from './fxConstants'

describe('elementModeSummary', () => {
  it('words Over so a summary never reads it as a target', () => {
    expect(['WHOLE', 'FLAT', 'PER_FIXTURE'].map(elementModeSummary)).toEqual(['over fixtures', 'over elements', 'each fixture'])
  })
})

/**
 * `cycle` carries two readings on one field, and they are a tempo apart. Every surface that
 * renders an effect's speed away from the editor's own fields (`FxFields`) goes through `fxSpeedLabel`, so the
 * rule lives here rather than in three components that could each get it half right.
 */
describe('fxSpeedLabel', () => {
  it('reads a beat effect through the division vocabulary', () => {
    expect(fxSpeedLabel(2, 'BEAT')).toBe(getCycleLabel(2))
    expect(fxSpeedLabel(4, 'BEAT')).toBe('1 bar')
    expect(fxSpeedLabel(0.5, 'BEAT')).toBe('½ beat')
  })

  it('reads a beat effect on Seconds (D7) as seconds, whatever its type', () => {
    expect(fxSpeedLabel(10, 'BEAT', true)).toBe('10 s')
  })

  it('reads the same number as seconds for a wall-clock effect', () => {
    // The whole reason the helper exists: 2 is "1/2" on the beat grid and "2s" on the clock, and
    // nothing about the number itself distinguishes them.
    expect(fxSpeedLabel(2, 'WALL_CLOCK')).toBe('2 s')
    expect(fxSpeedLabel(0.5, 'WALL_CLOCK')).toBe('0.5 s')
  })

  it('says nothing at all when the timing source is unknown', () => {
    // A stored `fxType` that no longer resolves in the registry — an import from a desk with
    // script-registered effects this one lacks. A confident "1/2" for a two-second cycle is worse
    // than an absent clause, so callers drop it rather than defaulting to beats.
    expect(fxSpeedLabel(2, null)).toBeNull()
    expect(fxSpeedLabel(2, undefined)).toBeNull()
  })

  it('treats anything that is not WALL_CLOCK as beat-timed', () => {
    // The wire default is BEAT and the backend only ever sends the two names; an unrecognised one
    // should still render a label rather than blanking the clause.
    expect(fxSpeedLabel(4, 'BEAT')).toBe('1 bar')
    expect(fxSpeedLabel(4, 'SOMETHING_NEW')).toBe('1 bar')
  })
})

/**
 * One spelling for a speed — beats a cycle — in the editor's segment and on every chip
 * (`FU-FE-FX-SPEED-SPELLING`, settled by venue-visit plan S15). A bar is four beats.
 */
describe('getCycleLabel', () => {
  it('says beats below a bar and bars from four, the segment\'s eight exactly', () => {
    expect([0.125, 0.25, 0.5, 1, 2, 4, 8, 16].map(getCycleLabel)).toEqual(
      SPEED_STEPS.map((step) => step.label),
    )
    expect(getCycleLabel(1)).toBe('1 beat')
    expect(getCycleLabel(16)).toBe('4 bars')
  })

  it('says a division off the eight in the same words', () => {
    expect(getCycleLabel(1 / 3)).toBe('⅓ beat')
    expect(getCycleLabel(0.333)).toBe('⅓ beat')
    expect(getCycleLabel(3)).toBe('3 beats')
    expect(getCycleLabel(12)).toBe('3 bars')
    expect(getCycleLabel(1.5)).toBe('1.5 beats')
  })
})
