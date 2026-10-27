import { describe, expect, it } from 'vitest'
import { DEFAULT_SPREAD_SHAPE, spreadOffsets, type SpreadShape } from '@/lib/spreadShape'
import { groupingSentence, laterBy, laterByPhrase, laterByReadout, laterByWords, mixedNumber, neighbourGap, startNumbers } from './acrossHeads'

const shape = (over: Partial<SpreadShape> = {}): SpreadShape => ({ ...DEFAULT_SPREAD_SHAPE, ...over })
const BEATS = { value: 4, seconds: false }

describe('startNumbers', () => {
  it('numbers heads by when they start, heads starting together sharing one', () => {
    expect(startNumbers([0, 0.25, 0.5, 0.75])).toEqual([1, 2, 3, 4])
    expect(startNumbers([0.5, 0, 0, 0.5])).toEqual([2, 1, 1, 2])
    expect(startNumbers([0, 0, 0])).toEqual([1, 1, 1])
    expect(startNumbers([0.25, null, 0])).toEqual([2, null, 1])
  })

  it('numbers the §1 chase from the desk’s rule: four heads, one step apart on three colours', () => {
    const { offsets } = spreadOffsets('NORMAL', 4, shape({ spreadUnit: 'STEP' }), 3)
    expect(startNumbers(offsets)).toEqual([1, 2, 3, 4])
  })
})

describe('groupingSentence', () => {
  it('says each count in the heads’ own terms', () => {
    expect(groupingSentence('NORMAL', shape(), 4)).toBe(
      'Segments 1: each head on its own · Parts off: one pattern across all four · Wings off: no mirror',
    )
    expect(groupingSentence('NORMAL', shape({ segments: 2 }), 8)).toContain('Segments 2: neighbours move in pairs')
    expect(groupingSentence('NORMAL', shape({ segments: 3 }), 12)).toContain('Segments 3: neighbours move in threes')
    expect(groupingSentence('NORMAL', shape({ segments: 6 }), 12)).toContain('Segments 6: neighbours move in sixes')
    expect(groupingSentence('NORMAL', shape({ segments: 13 }), 40)).toContain('Segments 13: neighbours move in groups of 13')
    expect(groupingSentence('NORMAL', shape({ segments: 4 }), 4)).toContain('Segments 4: all four move as one')
  })

  it('says how often Parts repeats, and when it has nothing to repeat', () => {
    expect(groupingSentence('NORMAL', shape({ parts: 2 }), 8)).toContain('Parts 2: the pattern repeats every 2 heads')
    expect(groupingSentence('NORMAL', shape({ parts: 2, segments: 2 }), 8)).toContain('Parts 2: the pattern repeats every 2 pairs')
    expect(groupingSentence('NORMAL', shape({ parts: 4 }), 4)).toContain('Parts 4: only four starts, so no repeat')
    expect(groupingSentence('NORMAL', shape({ parts: 3, segments: 2 }), 4)).toContain('Parts 3: only two starts, so no repeat')
  })

  it('says what Wings mirror, and that a mirrored Order is two wings already', () => {
    expect(groupingSentence('NORMAL', shape({ wings: 2 }), 8)).toContain('Wings 2: the second half mirrors the first')
    expect(groupingSentence('NORMAL', shape({ wings: 3 }), 9)).toContain('Wings 3: each wing mirrors the one before')
    expect(groupingSentence('CENTRE_OUT', shape({ wings: 2 }), 8)).toContain('Centre out is two wings already')
  })

  it('speaks of every head on an FX palette, which has none of its own', () => {
    expect(groupingSentence('NORMAL', shape({ parts: 9 }), null)).toContain('Parts 9: the pattern repeats every 9 heads')
    expect(groupingSentence('NORMAL', shape(), null)).toContain('one pattern across every head')
  })
})

describe('neighbourGap', () => {
  it('is the smallest gap between distinct starts, zero when every head starts together', () => {
    expect(neighbourGap([0, 0.25, 0.5, 0.75])).toBeCloseTo(0.25)
    expect(neighbourGap([0.5, 0, 0, 0.5])).toBeCloseTo(0.5)
    expect(neighbourGap([0, 0, 0])).toBe(0)
    expect(neighbourGap([0.25, null, 0])).toBeCloseTo(0.25)
  })

  it('reads Position’s ends a whole cycle apart, over one fewer gap than heads', () => {
    const { offsets } = spreadOffsets('POSITIONAL', 4, shape(), null)
    expect(neighbourGap(offsets)).toBeCloseTo(1 / 3)
  })
})

describe('later by', () => {
  const read = (s: SpreadShape, order: string, heads: number, steps: number | null, cycle = BEATS, known = true) => {
    const { offsets, slots } = spreadOffsets(order, heads, s, steps)
    return laterByReadout(s, neighbourGap(offsets), slots, steps, cycle, known)
  }

  it('reads the §1 case as a third of a cycle and one step, the desk running 133 %', () => {
    const s = shape({ spreadUnit: 'STEP' })
    expect(read(s, 'NORMAL', 4, 3)).toBe('⅓ cycle · 1 step · 1⅓ beats · 120° · spread 133 %')
    expect(laterByWords(1 / 3, 3)).toBe('⅓ cycle · 1 step')
    expect(laterBy(s, 1 / 3, 4, 3).deskSpread).toBeCloseTo(4 / 3)
  })

  it('reads Fit the cycle on the same chase as a quarter, three quarters of a step', () => {
    expect(read(shape(), 'NORMAL', 4, 3)).toBe('¼ cycle · ¾ step · 1 beat · 90°')
  })

  it('reads an FX with no steps in the cycle alone, in seconds where its cycle is', () => {
    expect(read(shape({ spread: 0.5 }), 'NORMAL', 4, null)).toBe('⅛ cycle · ½ beat · 45°')
    expect(read(shape(), 'NORMAL', 4, null, { value: 2, seconds: true })).toBe('¼ cycle · 0.5 s · 90°')
  })

  it('reads a mirrored Order and Position from where the heads start', () => {
    expect(read(shape(), 'CENTRE_OUT', 4, null)).toBe('½ cycle · 2 beats · 180°')
    expect(read(shape(), 'POSITIONAL', 4, null)).toBe('⅓ cycle · 1⅓ beats · 120°')
  })

  it('runs a STEP spread on an FX with no steps as a CYCLE one, as the desk does', () => {
    expect(read(shape({ spreadUnit: 'STEP' }), 'NORMAL', 4, null)).toBe('¼ cycle · 1 beat · 90°')
  })

  it('says which heads a palette’s gap is read over, and leaves the desk’s spread unsaid there', () => {
    expect(read(shape({ spread: 0.5 }), 'NORMAL', 8, null, BEATS, false)).toBe('0.06 cycle · ¼ beat · 23° · on eight heads')
    expect(read(shape({ spreadUnit: 'STEP' }), 'NORMAL', 8, 3, BEATS, false)).toBe('⅓ cycle · 1 step · 1⅓ beats · 120°')
  })

  it('says every head starts together over one start or no spread', () => {
    expect(read(shape(), 'NORMAL', 1, 3)).toBe('Every head starts together')
    expect(read(shape({ spread: 0 }), 'NORMAL', 4, 3)).toBe('Every head starts together')
    expect(laterByPhrase(0, null)).toBe('Every head together')
    expect(laterByPhrase(0.25, null)).toBe('Later by ¼ cycle')
  })

  it('says a step count past one in steps, and a gap too small to round as under a hundredth', () => {
    expect(laterByWords(2 / 3, 3)).toBe('⅔ cycle · 2 steps')
    expect(laterByWords(0.001, null)).toBe('<0.01 cycle')
  })
})

describe('mixedNumber', () => {
  it('writes the common fractions as glyphs and anything else to two places', () => {
    expect(mixedNumber(1 / 3)).toBe('⅓')
    expect(mixedNumber(4 / 3)).toBe('1⅓')
    expect(mixedNumber(2)).toBe('2')
    expect(mixedNumber(0.37)).toBe('0.37')
  })
})
