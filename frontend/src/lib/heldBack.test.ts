import { describe, expect, it } from 'vitest'
import { isHeldBack } from './heldBack'

/**
 * The client's copy of the desk's `EffectSuppression.heldBackByProgrammer`. Each case is one clause
 * of the desk's rule; a mark that disagrees with the engine is worse than none.
 */
const holdsDimmerOnHex1 = (head: string, property: string) => head === 'hex-1' && property === 'dimmer'

describe('isHeldBack', () => {
  it('holds back a non-band effect on a key the programmer holds', () => {
    expect(isHeldBack({ programmerOwned: false, propertyName: 'dimmer' }, 'hex-1', holdsDimmerOnHex1, false)).toBe(true)
  })

  it('never holds back a programmer-band effect', () => {
    expect(isHeldBack({ programmerOwned: true, propertyName: 'dimmer' }, 'hex-1', holdsDimmerOnHex1, false)).toBe(false)
  })

  it('holds nothing back while blind', () => {
    expect(isHeldBack({ programmerOwned: false, propertyName: 'dimmer' }, 'hex-1', holdsDimmerOnHex1, true)).toBe(false)
  })

  it("reads the effect's own key on that head — another head, or a sibling key, holds nothing back", () => {
    expect(isHeldBack({ programmerOwned: false, propertyName: 'dimmer' }, 'hex-2', holdsDimmerOnHex1, false)).toBe(false)
    // A `pan` entry does not hold back a Circle keyed `position` (session 1 amendment, W4).
    const holdsPan = (_head: string, property: string) => property === 'pan'
    expect(isHeldBack({ programmerOwned: false, propertyName: 'position' }, 'spot-1', holdsPan, false)).toBe(false)
  })
})
