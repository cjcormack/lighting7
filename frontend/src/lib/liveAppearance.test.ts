import { describe, expect, it } from 'vitest'
import { chan, colourProp, groupSummary, makeFixture, sliderProp, whiteMixProp } from '@/test/fixtureFactories'
import type { BuskRigRow } from '@/api/buskRigApi'
import { rigHeadOrder, targetHeads } from './liveAppearance'

describe('targetHeads', () => {
  it('counts a warm/cold white pair as a head the picker writes, as it does an RGB head', () => {
    const cell = (i: number) => ({ key: `blinder.cell-${i + 1}`, properties: [whiteMixProp(chan(5 + i * 2), chan(6 + i * 2))] })
    const heads = targetHeads([
      { key: 'par-1', properties: [colourProp('rgbColour', chan(1), chan(2), chan(3))] },
      { key: 'blinder', properties: [sliderProp('dimmer', 'dimmer', chan(4))], elements: [0, 1].map(cell).map((c, i) => ({ index: i, key: c.key, displayName: `Head ${i + 1}`, properties: c.properties })) },
      { ...cell(1), fixtureKey: 'blinder', cellIndex: 1 },
      { key: 'dim', properties: [sliderProp('dimmer', 'dimmer', chan(9))] },
    ])
    expect(heads).toEqual([
      { fixtureKey: 'par-1', cellIndex: null },
      { fixtureKey: 'blinder', cellIndex: null },
      { fixtureKey: 'blinder', cellIndex: 1 },
    ])
  })
})

describe('rigHeadOrder', () => {
  const fixtures = ['a', 'b', 'c'].map((key) => makeFixture(key, [], { groups: ['G'] }))
  const rowWith = (memberKeys?: string[]): BuskRigRow[] => [
    { id: 0, uuid: 'r0', name: 'Row', tiles: [{ id: 0, uuid: 't0', kind: 'GROUP', group: { ...groupSummary('G', 3), memberKeys }, cellMode: 'PIPS' }] },
  ]

  it('draws a group tile’s members in the group’s own order (venue-visit plan S7)', () => {
    expect(rigHeadOrder(rowWith(['c', 'a', 'b']), fixtures)).toEqual(['c', 'a', 'b'])
  })

  it('falls back to the fixture list’s order where the desk sent none', () => {
    expect(rigHeadOrder(rowWith(), fixtures)).toEqual(['a', 'b', 'c'])
  })
})
