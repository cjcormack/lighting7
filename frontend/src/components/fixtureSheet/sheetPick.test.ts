import { describe, expect, it, vi } from 'vitest'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { lightingApi } from '@/api/lightingApi'
import { chan, colourProp, element, makeFixture, makePixelBar, settingProp, sliderProp } from '@/test/fixtureFactories'
import {
  elementFilterFor,
  groupSheetMembers,
  headsOfFixture,
  addToPick,
  filterPick,
  normalisePick,
  pickWrite,
  rowsOverHeads,
  selectOnlyPick,
  stepPick,
  togglePick,
  type PickHead,
} from './sheetPick'
import { writePickColour, writePickPosition } from './sheetWrites'
import type { PositionResolution } from './sheetRows'

const keys = ['a', 'b', 'c', 'd']

describe('the pick', () => {
  it('is All when empty or when it covers every head, and drops unknown keys', () => {
    expect(normalisePick([], keys)).toBeNull()
    expect(normalisePick(keys, keys)).toBeNull()
    expect([...normalisePick(['a', 'z'], keys)!]).toEqual(['a'])
  })

  it('picks one head on a tap, adds a run’s next pip, and toggles a modified tap', () => {
    const one = selectOnlyPick('b', keys)
    expect([...one!]).toEqual(['b'])
    expect([...addToPick(one, 'c', keys)!]).toEqual(['b', 'c'])
    expect(addToPick(null, 'c', keys)).toBeNull()
    // All holds every head: toggling one there leaves the rest; toggling the last one off is All.
    expect([...togglePick(null, 'b', keys)!]).toEqual(['a', 'c', 'd'])
    expect(togglePick(one, 'b', keys)).toBeNull()
    expect(togglePick(togglePick(togglePick(one, 'a', keys), 'c', keys), 'd', keys)).toBeNull()
  })

  it('filters every head by the desk’s element filters, inverts the pick, and steps it along', () => {
    const five = ['a', 'b', 'c', 'd', 'e']
    const list = (p: ReturnType<typeof filterPick>) => (p == null ? null : [...p].sort())
    expect(list(filterPick(new Set(['b']), 'ODD', five))).toEqual(['a', 'c', 'e'])
    expect(list(filterPick(null, 'EVEN', five))).toEqual(['b', 'd'])
    expect(list(filterPick(null, 'FIRST_HALF', five))).toEqual(['a', 'b', 'c'])
    expect(list(filterPick(null, 'SECOND_HALF', five))).toEqual(['d', 'e'])
    expect(filterPick(new Set(['b']), 'ALL', five)).toBeNull()
    expect(list(filterPick(new Set(['a', 'c', 'e']), 'INVERT', five))).toEqual(['b', 'd'])
    // Invert of All would be nothing, which is All again.
    expect(filterPick(null, 'INVERT', five)).toBeNull()
    expect(list(stepPick(null, 1, five))).toEqual(['a'])
    expect(list(stepPick(null, -1, five))).toEqual(['e'])
    expect(list(stepPick(new Set(['d', 'e']), 1, five))).toEqual(['a', 'e'])
    expect(list(stepPick(new Set(['a']), -1, five))).toEqual(['e'])
  })

  it('writes a group entry on a group’s All, and members carrying the group on a subset', () => {
    const heads: PickHead[] = keys.map((key) => ({ key, name: key, properties: [] }))
    expect(pickWrite(heads, null, 'front')).toEqual({ kind: 'group', group: 'front', keys })
    expect(pickWrite(heads, new Set(['b']), 'front')).toEqual({ kind: 'heads', keys: ['b'], sourceGroup: 'front' })
    expect(pickWrite(heads, null)).toEqual({ kind: 'heads', keys, sourceGroup: undefined })
  })

  it('names a pick of heads by the desk’s element filter where one is exactly it', () => {
    const heads = headsOfFixture(makePixelBar('bar', 5))
    const pick = (...n: number[]) => new Set(n.map((i) => heads[i].key))
    expect(elementFilterFor(heads, pick(0, 2, 4))).toBe('ODD')
    expect(elementFilterFor(heads, pick(1, 3))).toBe('EVEN')
    // The desk's first half of five takes the middle head: (5 + 1) / 2.
    expect(elementFilterFor(heads, pick(0, 1, 2))).toBe('FIRST_HALF')
    expect(elementFilterFor(heads, pick(3, 4))).toBe('SECOND_HALF')
    expect(elementFilterFor(heads, pick(0, 1))).toBeNull()
    expect(elementFilterFor(heads, null)).toBeNull()
  })
})

describe('rows over heads', () => {
  it('draws only the rows every picked head has, each head with its own descriptor', () => {
    const a: PickHead = { key: 'a', name: 'A', properties: [sliderProp('dimmer', 'dimmer', chan(1)), sliderProp('zoom', 'zoom', chan(2))] }
    const b: PickHead = { key: 'b', name: 'B', properties: [sliderProp('dimmer', 'dimmer', chan(11))] }
    const groups = rowsOverHeads([a, b])
    const rows = groups.flatMap((g) => g.rows)
    expect(rows.map((r) => r.row.id)).toEqual(['dimmer'])
    const dimmer = rows[0].heads.map((h) => (h.row.kind === 'slider' ? h.row.property.channel.channelNo : null))
    expect(dimmer).toEqual([1, 11])
  })
})

describe('rows over heads — settings', () => {
  const options = (second: string) => [
    { name: 'open', level: 0, displayName: 'Open' },
    { name: second, level: 20, displayName: second },
  ]
  const head = (key: string, second: string): PickHead => ({ key, name: key, properties: [settingProp('mode', 'setting', chan(1), options(second))] })

  it('draws a setting only where every head has the same options, level for level', () => {
    expect(rowsOverHeads([head('a', 'auto'), head('b', 'auto')]).flatMap((g) => g.rows).map((r) => r.row.id)).toEqual(['mode'])
    // Two models with a `mode` whose second option differs: one level would be another option on b.
    expect(rowsOverHeads([head('a', 'auto'), head('b', 'sound')]).flatMap((g) => g.rows)).toEqual([])
  })
})

describe('a colour over a pick', () => {
  const rgb = (first: number, white?: number) =>
    colourProp('rgb', chan(first), chan(first + 1), chan(first + 2), white != null ? { whiteChannel: chan(white) } : {})
  const channelsAt = (values: Record<number, number>) => {
    ;(lightingApi as unknown as { channels: Record<string, unknown> }).channels = {
      update: vi.fn(),
      get: (_u: number, ch: number) => values[ch] ?? 0,
    }
  }

  it('fills an emitter the colour does not say from each head’s own channel, never 0', () => {
    channelsAt({ 14: 90 })
    const setColour = vi.spyOn(lightingApi.programmer, 'setColour')
    writePickColour(
      { kind: 'heads', keys: ['h1', 'h2'] },
      [
        { key: 'h1', property: rgb(1) },
        { key: 'h2', property: rgb(11, 14) },
      ],
      { r: 255, g: 0, b: 0 },
    )
    expect(setColour.mock.calls.map((c) => [c[1], c[3]])).toEqual([
      ['h1', { r: 255, g: 0, b: 0, w: undefined, a: undefined, uv: undefined }],
      ['h2', { r: 255, g: 0, b: 0, w: 90, a: undefined, uv: undefined }],
    ])
    setColour.mockRestore()
  })

  it('is one group entry over members with the same emitters, and each member’s own otherwise', () => {
    channelsAt({})
    const setColour = vi.spyOn(lightingApi.programmer, 'setColour')
    const same = [
      { key: 'm1', property: rgb(1, 4) },
      { key: 'm2', property: rgb(11, 14) },
    ]
    writePickColour({ kind: 'group', group: 'front', keys: ['m1', 'm2'] }, same, { r: 1, g: 2, b: 3, w: 4 })
    expect(setColour.mock.calls.map((c) => [c[0], c[1]])).toEqual([['group', 'front']])
    setColour.mockClear()
    const mixed = [
      { key: 'm1', property: rgb(1) },
      { key: 'm2', property: rgb(11, 14) },
    ]
    writePickColour({ kind: 'group', group: 'front', keys: ['m1', 'm2'] }, mixed, { r: 1, g: 2, b: 3, w: 4 })
    expect(setColour.mock.calls.map((c) => [c[0], c[1], c[5]])).toEqual([
      ['fixture', 'm1', 'front'],
      ['fixture', 'm2', 'front'],
    ])
    setColour.mockRestore()
  })
})

describe('a group’s members', () => {
  it('resolves a whole fixture and one head of a bar by key, never by parsing it', () => {
    const wash = makeFixture('wash-1', [sliderProp('dimmer', 'dimmer', chan(1))], { name: 'Wash 1' })
    const bar = makeFixture('bar', [], {
      name: 'Bar',
      elements: [element(0, 'bar~one', [colourProp('rgb', chan(20), chan(21), chan(22))]), element(1, 'bar~two', [])],
    })
    const members = groupSheetMembers(
      [
        { fixtureKey: 'wash-1', fixtureName: 'Wash 1' },
        { fixtureKey: 'bar~two', fixtureName: 'Bar · Head 2' },
        { fixtureKey: 'gone', fixtureName: 'Gone' },
      ],
      [wash, bar],
    )
    expect(members.map((m) => [m.key, m.fixture.key, m.elementIndex])).toEqual([
      ['wash-1', 'wash-1', null],
      ['bar~two', 'bar', 1],
    ])
  })
})

describe('a position over a pick', () => {
  const resolution = (panMax: number): PositionResolution =>
    ({ kind: 'position', pan: chan(1), tilt: chan(2), panMin: 0, panMax, tiltMin: 0, tiltMax: 255 }) as PositionResolution
  const heads = (a: number, b: number) => [
    { key: 'm1', resolution: resolution(a) },
    { key: 'm2', resolution: resolution(b) },
  ]

  it('is one group entry when every member lands on the same bytes', () => {
    const setPosition = vi.spyOn(lightingApi.programmer, 'setPosition')
    writePickPosition({ kind: 'group', group: 'front', keys: ['m1', 'm2'] }, heads(255, 255), { kind: 'position', pan: 100, tilt: 50 }, 500)
    expect(setPosition.mock.calls).toEqual([['group', 'front', 100, 50, 500]])
    setPosition.mockRestore()
  })

  it('is each member’s own entry, carrying the group, where their ranges put the point on different bytes', () => {
    const setPosition = vi.spyOn(lightingApi.programmer, 'setPosition')
    // The pad's point is half way across each member's own pan range.
    writePickPosition(
      { kind: 'group', group: 'front', keys: ['m1', 'm2'] },
      heads(255, 101),
      (r) => ({ kind: 'position', pan: Math.round(r.panMax / 2), tilt: 10 }),
    )
    expect(setPosition.mock.calls).toEqual([
      ['fixture', 'm1', 128, 10, undefined, 'front'],
      ['fixture', 'm2', 51, 10, undefined, 'front'],
    ])
    setPosition.mockRestore()
  })
})
