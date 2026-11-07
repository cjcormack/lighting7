import { describe, expect, it } from 'vitest'
import vector from '../../../src/test/resources/fx/fxSpeed.fixture.json'
import {
  beatsLabel,
  cycleOf,
  cycleSeconds,
  cycleTicks,
  fxSpeedLabel,
  isPowerOfTwo,
  parseSpeed,
  runningSpeed,
  speedAtPosition,
  speedLabel,
  speedOf,
  speedReadout,
  stepSpeed,
  stickSpeed,
  trackPosition,
} from './fxSpeed'
import { createTapCounter } from './tapTempo'

/**
 * The shared vector, `src/test/resources/fx/fxSpeed.fixture.json`, is read here and by lighting7's
 * `FxSpeedPhaseTest` (fx-speed plan P2): the snaps the desk glides at, the whole ticks a beat cycle
 * runs as, and the taps this window counts for a tempo no desk clock holds.
 */
describe('the shared vector', () => {
  it('says the same powers of two as the desk', () => {
    for (const { cycle, snapped } of vector.snaps) expect(isPowerOfTwo(cycle), `cycle ${cycle}`).toBe(snapped)
  })

  it('writes whole ticks, and reads the Speed that runs', () => {
    for (const { speed, cycle, ticks, runsAt } of vector.speeds) {
      expect(cycleOf(speed, false), `×${speed}`).toBe(cycle)
      expect(cycleTicks(cycle), `×${speed}`).toBe(ticks)
      expect(runningSpeed(cycle, false)).toBeCloseTo(runsAt, 9)
    }
  })

  it('runs a step-timed cycle of whole ticks as those ticks, as the desk does', () => {
    for (let n = 1; n <= 1200; n += 7) for (let steps = 2; steps <= 16; steps++) expect(cycleTicks((n / 24) * steps)).toBe(n * steps)
    expect(cycleTicks(0.333)).toBe(7)
  })

  it('counts taps as the desk does', () => {
    for (const { times, bpm } of vector.taps) {
      const counter = createTapCounter()
      let last: number | null = null
      for (const t of times) last = counter.tap(t)
      if (bpm == null) expect(last, `taps ${times}`).toBeNull()
      else expect(last, `taps ${times}`).toBeCloseTo(bpm, 9)
    }
  })
})

describe('Speed is a view of the cycle (D1, D4)', () => {
  it('is one cycle a bar at ×1 on a beat FX and on the wall clock', () => {
    expect(speedOf(4, false)).toBe(1)
    expect(speedOf(2, false)).toBe(2)
    expect(speedOf(2, true)).toBe(1)
    // Today's default for a new wall-clock FX, four seconds, reads ×½.
    expect(speedOf(4, true)).toBe(0.5)
    expect(cycleOf(1, true)).toBe(2)
  })

  it('clamps a Speed to ×1/512–×32', () => {
    expect(cycleOf(1000, false)).toBe(0.125)
    expect(cycleOf(1 / 10_000, false)).toBe(2048)
  })
})

describe('the slider', () => {
  it('sticks at a power of two, unless ⌥ is held', () => {
    expect(stickSpeed(1.05)).toBe(1)
    expect(stickSpeed(0.48)).toBe(0.5)
    expect(stickSpeed(1.05, true)).toBe(1.05)
    expect(stickSpeed(1.5)).toBe(1.5)
  })

  it('steps a quarter of an octave, stopping on a power of two it would pass', () => {
    expect(stepSpeed(1, 1)).toBeCloseTo(2 ** 0.25, 12)
    expect(stepSpeed(1, 4)).toBe(2)
    expect(stepSpeed(1, -4)).toBe(0.5)
    expect(stepSpeed(1.5, 4)).toBe(2)
    expect(stepSpeed(1.5, -4)).toBe(1)
  })

  it('runs ×1/16 to ×16 on a log scale, a Speed off it at the end it passed', () => {
    expect(trackPosition(1).position).toBeCloseTo(0.5, 12)
    expect(speedAtPosition(0.5)).toBeCloseTo(1, 12)
    expect(speedAtPosition(0)).toBeCloseTo(1 / 16, 12)
    expect(trackPosition(32)).toEqual({ position: 1, off: 'high' })
    expect(trackPosition(1 / 64)).toEqual({ position: 0, off: 'low' })
  })
})

describe('the words', () => {
  it('says a Speed as ×, with the fractions an operator types', () => {
    expect([1, 2, 0.5, 0.25, 0.125, 1 / 16, 2 / 3, 1.5].map(speedLabel)).toEqual(['×1', '×2', '×½', '×¼', '×⅛', '×1/16', '×⅔', '×1.5'])
    expect(speedLabel(runningSpeed(cycleOf(5, false), false))).toBe('×5.05')
  })

  it('reads a typed fraction or decimal', () => {
    expect(parseSpeed('⅔')).toBeCloseTo(2 / 3, 12)
    expect(parseSpeed('2/3')).toBeCloseTo(2 / 3, 12)
    expect(parseSpeed('×2')).toBe(2)
    expect(parseSpeed('x0.5')).toBe(0.5)
    expect(parseSpeed('1 ½')).toBe(1.5)
    expect(parseSpeed('1000')).toBe(32)
    expect(parseSpeed('fast')).toBeNull()
    expect(parseSpeed('0')).toBeNull()
  })

  it('reads out the cycle in seconds at the tempo, and in bars or beats on a snap (D2)', () => {
    expect(speedReadout(4, false, 132)).toBe('1 bar · 1.82 s a cycle at 132 BPM')
    expect(speedReadout(2, false, 128)).toBe('2 beats · 0.94 s a cycle at 128 BPM')
    expect(speedReadout(6, false, 120)).toBe('3 s a cycle at 120 BPM')
    expect(speedReadout(2, true, 120)).toBe('1 bar · 2 s a cycle at 120 BPM')
    expect(cycleSeconds(4, true, 60)).toBe(8)
    expect([0.125, 1, 2, 4, 8, 16].map(beatsLabel)).toEqual(['⅛ beat', '1 beat', '2 beats', '1 bar', '2 bars', '4 bars'])
  })

  it('labels a chip with its Speed and its Own tempo (D2)', () => {
    expect(fxSpeedLabel(4, 'BEAT')).toBe('×1')
    expect(fxSpeedLabel(8, 'BEAT', 96)).toBe('×½ · Own 96')
    // A running FX on Own tempo reports the wall-clock loop it runs on; its cycle is still beats.
    expect(fxSpeedLabel(2, 'WALL_CLOCK', 120)).toBe('×2 · Own 120')
    expect(fxSpeedLabel(4, 'WALL_CLOCK')).toBe('×½')
    expect(fxSpeedLabel(4, null)).toBeNull()
    expect(fxSpeedLabel(4, 'SOMETHING_NEW')).toBe('×1')
  })
})
