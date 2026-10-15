import { describe, expect, it } from 'vitest'
import { beatsToSeconds, isOnSeconds, secondsToBeats } from './effectTiming'

/** Venue-visit plan D7: an effect follows a master, or runs on Seconds — read one way for both kinds. */
describe('isOnSeconds', () => {
  it('reads the flag on any effect', () => {
    expect(isOnSeconds({ seconds: true }, 'BEAT')).toBe(true)
    expect(isOnSeconds({ seconds: false }, 'BEAT')).toBe(false)
    expect(isOnSeconds({}, 'BEAT')).toBe(false)
  })

  it('reads a wall-clock effect with no rate master as Seconds, flag or not', () => {
    expect(isOnSeconds({ rateSpeedMasterUuid: null }, 'WALL_CLOCK')).toBe(true)
    expect(isOnSeconds({}, 'WALL_CLOCK')).toBe(true)
    expect(isOnSeconds({ rateSpeedMasterUuid: 'm2' }, 'WALL_CLOCK')).toBe(false)
  })

  it('says a beat effect with no master is on master 1, not Seconds', () => {
    expect(isOnSeconds({ rateSpeedMasterUuid: null }, 'BEAT')).toBe(false)
    expect(isOnSeconds({ rateSpeedMasterUuid: null }, null)).toBe(false)
  })
})

describe('the conversions that keep a speed across the move', () => {
  it('turns beats at a tempo into seconds and back', () => {
    expect(beatsToSeconds(4, 120)).toBe(2)
    expect(secondsToBeats(2, 120)).toBe(4)
    expect(secondsToBeats(beatsToSeconds(3, 90), 90)).toBe(3)
  })

  it('lands back on the beat grid after the millisecond rounding on the way out', () => {
    // One bar at 90 BPM is 2.667 s: back at 90 it is one bar, and at 120 it is 5⅓ beats.
    expect(beatsToSeconds(4, 90)).toBe(2.667)
    expect(secondsToBeats(beatsToSeconds(4, 90), 90)).toBe(4)
    expect(secondsToBeats(beatsToSeconds(4, 90), 120)).toBe(5.333)
    // A cycle the operator typed in seconds that lands off the grid is left as it is.
    expect(secondsToBeats(1.234, 120)).toBe(2.468)
  })

  it('leaves the number alone with no tempo to convert at', () => {
    expect(beatsToSeconds(4, 0)).toBe(4)
    expect(secondsToBeats(4, 0)).toBe(4)
  })
})
