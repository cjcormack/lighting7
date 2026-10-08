import { describe, expect, it } from 'vitest'
import { cellHoldsBack, heldBackKey, heldBackReach, isHeldBack, type ReachingEffect } from './heldBack'

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

describe('the grid’s reach and its cell rule (D19)', () => {
  const bar = { key: 'bar', groups: ['Bars'], elements: [{ key: 'bar.1' }, { key: 'bar.2' }] }
  const spot = { key: 'spot', groups: [] }
  const effect = (over: Partial<ReachingEffect>): ReachingEffect => ({
    programmerOwned: false,
    propertyName: 'dimmer',
    targetKey: 'bar',
    isGroupTarget: false,
    ...over,
  })

  it('reaches a fixture’s heads, a group’s members and theirs, a head alone — and never a band effect', () => {
    const reach = heldBackReach(
      [
        effect({}),
        effect({ targetKey: 'Bars', isGroupTarget: true, propertyName: 'rgbColour' }),
        effect({ targetKey: 'bar.2', propertyName: 'uv' }),
        effect({ targetKey: 'spot', programmerOwned: true }),
      ],
      [bar, spot],
    )
    expect([...reach].sort()).toEqual(
      [
        heldBackKey('bar', 'dimmer'),
        heldBackKey('bar.1', 'dimmer'),
        heldBackKey('bar.2', 'dimmer'),
        heldBackKey('bar', 'rgbColour'),
        heldBackKey('bar.1', 'rgbColour'),
        heldBackKey('bar.2', 'rgbColour'),
        heldBackKey('bar.2', 'uv'),
      ].sort(),
    )
  })

  it('marks a cell only where the programmer holds a reached key, and never while blind', () => {
    const reach = new Set([heldBackKey('bar.1', 'dimmer')])
    const keys = [
      { targetKey: 'bar.1', propertyName: 'dimmer' },
      { targetKey: 'bar.2', propertyName: 'dimmer' },
    ]
    const holds = (head: string) => head === 'bar.1'
    expect(cellHoldsBack(keys, reach, holds, false)).toBe(true)
    expect(cellHoldsBack(keys, reach, () => false, false)).toBe(false)
    expect(cellHoldsBack(keys, reach, holds, true)).toBe(false)
    expect(cellHoldsBack([{ targetKey: 'bar.2', propertyName: 'dimmer' }], reach, () => true, false)).toBe(false)
  })
})
