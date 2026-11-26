// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { chan, makeFixture, settingProp } from '@/test/fixtureFactories'
import type { FxPreviewAnswer } from '@/store/fxPreview'
import { landsOnFrom, noteWords } from './landsOn'

const WHEEL = settingProp('colourWheel', 'colour', chan(5), [
  { name: 'RED', level: 156, displayName: 'Red', colourPreview: '#ff0000', continuousLevel: 12 },
  { name: 'YELLOW', level: 160, displayName: 'Yellow', colourPreview: '#ffff00', continuousLevel: 23 },
])
const MACS = ['m1', 'm2'].map((k) => makeFixture(k, [WHEEL], { typeKey: 'mac', model: 'MAC 250' }))

const SERIES = { planes: {} }
function answer(): FxPreviewAnswer {
  const row = (index: number) => ({
    row: index,
    underneath: SERIES,
    result: SERIES,
    fx: [{ fx: 0, output: SERIES, notes: [{ kind: 'TOO_FAST', needMs: 700, stepMs: 300 }] }],
    wheel: {
      states: [{ slot: 'RED', preview: '#ff0000' }, { slot: 'RED', splitWith: 'YELLOW', preview: '#ff8000' }, { slot: 'YELLOW', preview: '#ffff00' }],
      samples: [0, 0, 1, 2, 2, 1, 0],
    },
  })
  return {
    window: {} as never,
    clock: [],
    rows: MACS.map((f, i) => ({ key: f.key, fixtureKey: f.key, label: f.name, rigIndex: i })),
    lanes: [{ family: 'COLOUR', attributes: [{ attribute: 'colourWheel', kind: 'SLIDER', rows: [row(0), row(1)] }] }],
    fx: [],
  }
}

describe('landsOnFrom (wheel-colour-chase plan D8)', () => {
  it('groups the wheel heads by model, their states in step order and each note once', () => {
    expect(landsOnFrom(answer(), 0, MACS)).toEqual([
      {
        model: 'MAC 250',
        attribute: 'colourWheel',
        heads: 2,
        states: [
          { slot: 'Red', preview: '#ff0000' },
          { slot: 'Red', splitWith: 'Yellow', preview: '#ff8000', halves: ['#ff0000', '#ffff00'] },
          { slot: 'Yellow', preview: '#ffff00' },
        ],
        notes: ['Each step is 300 ms; the wheel needs 700 ms to get there'],
      },
    ])
  })

  it('starts the order at the head’s first cycle start, not where the window opens', () => {
    const a = answer()
    for (const row of a.lanes[0].attributes[0].rows) row.wheel = { ...row.wheel!, samples: [2, 2, 1, 0, 0, 1, 2] }
    a.fx = [{ starts: [[3], [3]] } as never]
    expect(landsOnFrom(a, 0, MACS)[0].states.map((s) => (s.splitWith != null ? `${s.slot}/${s.splitWith}` : s.slot))).toEqual(['Red', 'Red/Yellow', 'Yellow'])
  })

  it('leaves a split on its mix where a slot has no preview of its own', () => {
    const bare = settingProp('colourWheel', 'colour', chan(5), [
      { name: 'RED', level: 156, displayName: 'Red', colourPreview: '#ff0000' },
      { name: 'YELLOW', level: 160, displayName: 'Yellow' },
    ])
    const macs = ['m1', 'm2'].map((k) => makeFixture(k, [bare], { typeKey: 'mac', model: 'MAC 250' }))
    expect(landsOnFrom(answer(), 0, macs)[0].states[1]).toEqual({ slot: 'Red', splitWith: 'Yellow', preview: '#ff8000' })
  })

  it('counts heads, not wheel rows, and names each wheel’s slots from that wheel', () => {
    const wheel2 = settingProp('colourWheel2', 'colour', chan(6), [
      { name: 'RED', level: 0, displayName: 'Deep red', colourPreview: '#ff0000' },
      { name: 'YELLOW', level: 10, displayName: 'Amber', colourPreview: '#ffff00' },
    ])
    const robe = makeFixture('r1', [WHEEL, wheel2], { typeKey: 'robe', model: 'Robe' })
    const a = answer()
    a.rows = [{ key: 'r1', fixtureKey: 'r1', label: 'Robe', rigIndex: 0 }]
    const one = { ...a.lanes[0].attributes[0].rows[0], row: 0 }
    a.lanes[0].attributes = [
      { attribute: 'colourWheel', kind: 'SLIDER', rows: [one] },
      { attribute: 'colourWheel2', kind: 'SLIDER', rows: [one] },
    ]
    const models = landsOnFrom(a, 0, [robe])
    expect(models.map((m) => [m.heads, m.states[0].slot])).toEqual([
      [1, 'Red'],
      [1, 'Deep red'],
    ])
  })

  it('says nothing for another FX, or before the preview has answered', () => {
    expect(landsOnFrom(answer(), 1, MACS)).toEqual([])
    expect(landsOnFrom(null, 0, MACS)).toEqual([])
  })

  it('words each note', () => {
    const names = new Map([['RED', 'Red'], ['YELLOW', 'Yellow']])
    expect(noteWords({ kind: 'SAME_SLOT', colours: ['#ff0000', '#ff1100'], slot: 'RED' }, names)).toBe('#ff0000, #ff1100 all land on Red')
    expect(noteWords({ kind: 'NO_SPLIT', from: 'RED', to: 'YELLOW', reason: 'WHEEL' }, names)).toBe(
      'This wheel does not split: Red changes to Yellow at the split’s middle',
    )
    expect(noteWords({ kind: 'NO_SPLIT', from: 'RED', to: 'YELLOW', reason: 'NOT_NEIGHBOURS' }, names)).toBe(
      'Red and Yellow are not neighbours, so the wheel changes at the split’s middle',
    )
  })
})
