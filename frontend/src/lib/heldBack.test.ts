import { describe, expect, it } from 'vitest'
import { cellHoldsBack, heldBackKey, heldBackReach, isHeldBack, isHeldByBlind, type ReachingFx } from './heldBack'

/**
 * The client's copy of the desk's `FxSuppression.heldBackByProgrammer`. Each case is one clause
 * of the desk's rule; a mark that disagrees with the engine is worse than none.
 */
const holdsDimmerOnHex1 = (head: string, attribute: string) => head === 'hex-1' && attribute === 'dimmer'

describe('isHeldBack', () => {
  it('holds back a non-band effect on a key the programmer holds', () => {
    expect(isHeldBack({ programmerOwned: false, attribute: 'dimmer' }, 'hex-1', holdsDimmerOnHex1, false)).toBe(true)
  })

  it('never holds back a programmer-band effect', () => {
    expect(isHeldBack({ programmerOwned: true, attribute: 'dimmer' }, 'hex-1', holdsDimmerOnHex1, false)).toBe(false)
  })

  it('holds nothing back while blind', () => {
    expect(isHeldBack({ programmerOwned: false, attribute: 'dimmer' }, 'hex-1', holdsDimmerOnHex1, true)).toBe(false)
  })

  it("reads the effect's own key on that head — another head, or a sibling key, holds nothing back", () => {
    expect(isHeldBack({ programmerOwned: false, attribute: 'dimmer' }, 'hex-2', holdsDimmerOnHex1, false)).toBe(false)
    // A `pan` entry does not hold back a Circle keyed `position` (session 1 amendment, W4).
    const holdsPan = (_head: string, attribute: string) => attribute === 'pan'
    expect(isHeldBack({ programmerOwned: false, attribute: 'position' }, 'spot-1', holdsPan, false)).toBe(false)
  })
})

describe('isHeldByBlind — the desk’s BlindHold (show-cue-editing plan D7)', () => {
  it('holds a programmer-band effect while blind', () => {
    expect(isHeldByBlind({ programmerOwned: true }, true)).toBe(true)
  })

  it('holds nothing while not blind', () => {
    expect(isHeldByBlind({ programmerOwned: true }, false)).toBe(false)
  })

  it('never holds a cue’s, a manual or a script effect', () => {
    expect(isHeldByBlind({ programmerOwned: false }, true)).toBe(false)
  })

  it('is never true of an effect the programmer’s values hold back — the two marks are exclusive', () => {
    for (const programmerOwned of [true, false]) {
      for (const blind of [true, false]) {
        const effect = { programmerOwned, attribute: 'dimmer' }
        expect(isHeldByBlind(effect, blind) && isHeldBack(effect, 'hex-1', holdsDimmerOnHex1, blind)).toBe(false)
      }
    }
  })
})

describe('the grid’s reach and its cell rule (D19)', () => {
  const bar = { key: 'bar', groups: ['Bars'], elements: [{ key: 'bar.1' }, { key: 'bar.2' }] }
  const spot = { key: 'spot', groups: [] }
  const effect = (over: Partial<ReachingFx>): ReachingFx => ({
    programmerOwned: false,
    attribute: 'dimmer',
    targetKey: 'bar',
    isGroupTarget: false,
    ...over,
  })

  it('reaches a fixture’s heads, a group’s members and theirs, a head alone — and never a band effect', () => {
    const reach = heldBackReach(
      [
        effect({}),
        effect({ targetKey: 'Bars', isGroupTarget: true, attribute: 'rgbColour' }),
        effect({ targetKey: 'bar.2', attribute: 'uv' }),
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

  it('reaches a selection’s items — a group’s members, a fixture’s heads — and never its key', () => {
    const targets = [
      { type: 'fixture' as const, key: 'spot' },
      { type: 'group' as const, key: 'Bars' },
    ]
    const key = JSON.stringify(targets.map((t) => ({ key: t.key, type: t.type })))
    const reach = heldBackReach([effect({ targetKey: key, targetType: 'selection', targets })], [bar, spot])
    expect([...reach].sort()).toEqual(
      ['spot', 'bar', 'bar.1', 'bar.2'].map((head) => heldBackKey(head, 'dimmer')).sort(),
    )
  })

  it('marks a cell only where the programmer holds a reached key, and never while blind', () => {
    const reach = new Set([heldBackKey('bar.1', 'dimmer')])
    const keys = [
      { targetKey: 'bar.1', attribute: 'dimmer' },
      { targetKey: 'bar.2', attribute: 'dimmer' },
    ]
    const holds = (head: string) => head === 'bar.1'
    expect(cellHoldsBack(keys, reach, holds, false)).toBe(true)
    expect(cellHoldsBack(keys, reach, () => false, false)).toBe(false)
    expect(cellHoldsBack(keys, reach, holds, true)).toBe(false)
    expect(cellHoldsBack([{ targetKey: 'bar.2', attribute: 'dimmer' }], reach, () => true, false)).toBe(false)
  })
})
