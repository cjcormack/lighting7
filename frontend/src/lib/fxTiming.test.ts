import { describe, expect, it } from 'vitest'
import {
  clampOwnBpm,
  followsNoMaster,
  isOnNatural,
  isOnOwnTempo,
  isWallClock,
  newFxDirection,
  newFxDivision,
  swappedTiming,
} from './fxTiming'

/** Fx-speed plan D3, D4: a beat FX follows a master or its own tempo; a wall-clock FX a rate master or Natural. */
describe('an FX’s timing', () => {
  it('reads Own tempo from the tempo alone', () => {
    expect(isOnOwnTempo({ ownBpm: 96 })).toBe(true)
    expect(isOnOwnTempo({ ownBpm: null })).toBe(false)
    expect(isOnOwnTempo({})).toBe(false)
  })

  it('reads a running Own tempo FX’s cycle as beats, though it reports the wall-clock loop', () => {
    expect(isWallClock({ ownBpm: 96 }, 'WALL_CLOCK')).toBe(false)
    expect(isWallClock({}, 'WALL_CLOCK')).toBe(true)
    expect(isWallClock({}, 'BEAT')).toBe(false)
    expect(isOnNatural({ ownBpm: 96 }, 'WALL_CLOCK')).toBe(false)
  })

  it('reads a wall-clock FX with no rate master as Natural', () => {
    expect(isOnNatural({ rateSpeedMasterUuid: null }, 'WALL_CLOCK')).toBe(true)
    expect(isOnNatural({}, 'WALL_CLOCK')).toBe(true)
    expect(isOnNatural({ rateSpeedMasterUuid: 'm2' }, 'WALL_CLOCK')).toBe(false)
  })

  it('says a beat FX with no master is on master 1, not free of one', () => {
    expect(followsNoMaster({ rateSpeedMasterUuid: null }, 'BEAT')).toBe(false)
    expect(followsNoMaster({}, null)).toBe(false)
    expect(followsNoMaster({ ownBpm: 120 }, 'BEAT')).toBe(true)
    expect(followsNoMaster({}, 'WALL_CLOCK')).toBe(true)
  })

  it('holds a typed tempo to the desk’s bounds', () => {
    expect(clampOwnBpm(96.04)).toBe(96)
    expect(clampOwnBpm(5)).toBe(20)
    expect(clampOwnBpm(400)).toBe(300)
  })
})

/** Terminology plan D24: an FX that bounces starts at its own half bar, Bounce; every other one bar, Forward. */
describe('a new FX\'s cycle and Direction', () => {
  const sweep = { defaultCycle: 2, defaultDirection: 'BOUNCE' }
  const sine = { defaultCycle: 4, defaultDirection: 'FORWARD' }

  it('is its type\'s own, else one bar and Forward', () => {
    expect([newFxDivision(sweep), newFxDirection(sweep)]).toEqual([2, 'BOUNCE'])
    expect([newFxDivision({}), newFxDirection({})]).toEqual([4, 'FORWARD'])
    expect([newFxDivision(undefined), newFxDirection({ defaultDirection: 'SIDEWAYS' })]).toEqual([4, 'FORWARD'])
  })

  it('follows a swap where it stood at the old type\'s default, and stays where the operator put it', () => {
    expect(swappedTiming({ cycle: 4, direction: 'FORWARD' }, sine, sweep, true)).toEqual({ cycle: 2, direction: 'BOUNCE' })
    expect(swappedTiming({ cycle: 8, direction: 'BACKWARD' }, sine, sweep, true)).toEqual({ cycle: 8, direction: 'BACKWARD' })
    expect(swappedTiming({ cycle: 8, direction: 'FORWARD' }, sine, sweep, false)).toEqual({ cycle: 2, direction: 'BOUNCE' })
  })
})
