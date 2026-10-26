// @vitest-environment jsdom
import { describe, expect, it } from 'vitest'
import { chan, colourProp, makeActiveFx, makeFixture, sliderProp } from '@/test/fixtureFactories'
import type { ElementDescriptor } from '@/store/fixtures'
import { runningCards, subjectFixtures, subjectForm } from './fxEditorHead'

const zones = (key: string): ElementDescriptor[] =>
  [1, 2, 3].map((n) => ({ key: `${key}.${n}`, displayName: `Zone ${n}`, attributes: [colourProp('rgbColour', chan(n), chan(n + 1), chan(n + 2))] }) as ElementDescriptor)

const SHEHDS = ['lx1-sl', 'lx1-sr', 'lx2-sl', 'lx2-sr'].map((key) =>
  makeFixture(key, [sliderProp('dimmer', 'dimmer', chan(1))], { name: `Shehds ${key}`, groups: ['shehds'], elements: zones(key) }),
)
const PAR = makeFixture('par-1', [sliderProp('dimmer', 'dimmer', chan(40))], { name: 'Par 1', groups: ['front'] })
const RIG = [...SHEHDS, PAR]

describe('subjectFixtures', () => {
  it('reaches a group by its members, a fixture whole and an element by its fixture, in order', () => {
    const list = subjectFixtures(
      [
        { type: 'fixture', key: 'lx2-sl.1' },
        { type: 'fixture', key: 'lx2-sl.3' },
        { type: 'group', key: 'front' },
        { type: 'fixture', key: 'lx1-sl' },
      ],
      RIG,
    )
    expect(list.map((f) => [f.fixture.key, f.elements, f.targets.map((t) => t.key)])).toEqual([
      ['lx2-sl', 2, ['lx2-sl.1', 'lx2-sl.3']],
      ['par-1', 0, ['par-1']],
      ['lx1-sl', 3, ['lx1-sl']],
    ])
  })

  it('counts a fixture reached whole and by its elements as whole', () => {
    const [only] = subjectFixtures(
      [
        { type: 'fixture', key: 'lx1-sl.2' },
        { type: 'fixture', key: 'lx1-sl' },
      ],
      RIG,
    )
    expect([only.elements, only.targets]).toEqual([3, [{ type: 'fixture', key: 'lx1-sl' }]])
  })
})

describe('subjectForm', () => {
  it('reads a group as its name and how many fixtures', () => {
    expect(subjectForm([{ type: 'group', key: 'shehds' }], RIG, 'Shehds')).toEqual({ kind: 'named', name: 'Shehds', count: '4 fixtures' })
  })

  it('reads one fixture as its name and its elements, or one head', () => {
    expect(subjectForm([{ type: 'fixture', key: 'lx2-sr' }], RIG, 'Shehds lx2-sr')).toEqual({ kind: 'named', name: 'Shehds lx2-sr', count: '3 elements' })
    expect(subjectForm([{ type: 'fixture', key: 'par-1' }], RIG, 'Par 1')).toEqual({ kind: 'named', name: 'Par 1', count: '1 head' })
  })

  it('reads several elements of one fixture as that fixture', () => {
    const targets = [
      { type: 'fixture' as const, key: 'lx1-sr.1' },
      { type: 'fixture' as const, key: 'lx1-sr.2' },
    ]
    expect(subjectForm(targets, RIG, 'Zone 1, Zone 2')).toEqual({ kind: 'named', name: 'Shehds lx1-sr', count: '2 elements' })
  })

  it('reads several fixtures, or elements across several, as a count of fixtures and elements', () => {
    const four = subjectForm(SHEHDS.map((f) => ({ type: 'fixture' as const, key: f.key })), RIG, 'joined')
    expect(four?.kind === 'several' && four.label).toBe('4 fixtures · 12 elements')
    const across = subjectForm(
      [
        { type: 'fixture', key: 'lx1-sl.1' },
        { type: 'fixture', key: 'lx2-sl.1' },
      ],
      RIG,
      'joined',
    )
    expect(across?.kind === 'several' && across.label).toBe('2 fixtures · 2 elements')
    const plain = subjectForm(
      [
        { type: 'group', key: 'front' },
        { type: 'fixture', key: 'par-1' },
        { type: 'fixture', key: 'lx1-sl' },
      ],
      [...RIG, makeFixture('par-2', [], { name: 'Par 2', groups: ['front'] })],
      'joined',
    )
    expect(plain?.kind === 'several' && plain.label).toBe('3 fixtures · 3 elements')
  })

  it('says nothing for an empty selection', () => {
    expect(subjectForm([], RIG, '')).toBeNull()
  })
})

describe('runningCards', () => {
  const name = (e: { fxType: string }) => e.fxType

  it('lists every FX not leaving, by family in the lanes’ order then by name, with what it runs on', () => {
    const cards = runningCards(
      [
        makeActiveFx({ id: 1, fxType: 'Pulse', targetKey: 'front', isGroupTarget: true, attribute: 'dimmer' }),
        makeActiveFx({ id: 2, fxType: 'Colour Cycle', targetType: 'selection', targetKey: '[]', targets: [{ type: 'fixture', key: 'lx1-sl' }, { type: 'fixture', key: 'lx2-sl.1' }], attribute: 'rgbColour' }),
        makeActiveFx({ id: 3, fxType: 'Breath', targetKey: 'par-1', attribute: 'dimmer' }),
        makeActiveFx({ id: 4, fxType: 'Gone', targetKey: 'par-1', attribute: 'dimmer', leaving: true }),
        makeActiveFx({ id: 5, fxType: 'Breath', targetKey: 'par-1', attribute: 'uv' }),
      ],
      [...RIG.slice(0, 4), makeFixture('par-1', [sliderProp('dimmer', 'dimmer', chan(40)), sliderProp('uv', 'uv', chan(41))], { name: 'Par 1', groups: ['front'] })],
      name,
    )
    expect(cards.map((c) => [c.effect.id, c.family, c.drives, c.on])).toEqual([
      [3, 'INTENSITY', 'Intensity', 'Par 1 · 1 fixture'],
      [1, 'INTENSITY', 'Intensity', 'front · 1 fixture'],
      [5, 'COLOUR', 'UV', 'Par 1 · 1 fixture'],
      [2, 'COLOUR', 'Colour', 'Shehds lx1-sl, Shehds lx2-sl Zone 1 · 2 fixtures'],
    ])
  })
})
