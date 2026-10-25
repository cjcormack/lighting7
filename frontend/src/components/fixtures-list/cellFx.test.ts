import { describe, expect, it, vi } from 'vitest'
import {
  EMPTY_CELL_FX_INDEX,
  buildCellFxIndex,
  cellFxKey,
  createCellFxStore,
  fxForCell,
  fxToStop,
  fixturesOfRows,
  isLocalFx,
  partialSweepMessage,
} from './cellFx'
import { chan, colourProp, makeActiveFx as effect, makeFixture, sliderProp } from '@/test/fixtureFactories'
import type { Fixture } from '../../store/fixtures'
import type { Row } from './rowModel'


const cleared = (...pairs: [string, string][]) =>
  new Set(pairs.map(([key, prop]) => cellFxKey(key, prop)))

const noMembers = () => []

describe('isLocalFx', () => {
  it('takes a busked programmer-band effect', () => {
    expect(isLocalFx(effect({}))).toBe(true)
  })

  it('leaves anything with an owner alone', () => {
    expect(isLocalFx(effect({ programmerOwned: false }))).toBe(false)
    expect(isLocalFx(effect({ lookId: 3 }))).toBe(false)
    expect(isLocalFx(effect({ paletteId: 3 }))).toBe(false)
    expect(isLocalFx(effect({ programmerLayerId: 3 }))).toBe(false)
    expect(isLocalFx(effect({ cueId: 3 }))).toBe(false)
  })

  it('leaves an effect a fade is taking off — it is nobody\'s', () => {
    expect(isLocalFx(effect({ leaving: true }))).toBe(false)
  })
})

describe('fxToStop', () => {
  it('stops one instance per cleared cell — the effect-palette press this exists for', () => {
    const effects = [
      effect({ id: 1, targetKey: 'hex-1', attribute: 'rgbColour' }),
      effect({ id: 2, targetKey: 'hex-2', attribute: 'rgbColour' }),
      effect({ id: 3, targetKey: 'hex-3', attribute: 'rgbColour' }),
    ]
    const { stop, partial } = fxToStop(
      effects,
      noMembers,
      cleared(['hex-1', 'rgbColour'], ['hex-2', 'rgbColour'], ['hex-3', 'rgbColour']),
    )
    expect(stop.map((e) => e.id)).toEqual([1, 2, 3])
    expect(partial).toEqual([])
  })

  it('matches on the property as well as the head', () => {
    const effects = [effect({ id: 1, targetKey: 'hex-1', attribute: 'position' })]
    expect(fxToStop(effects, noMembers, cleared(['hex-1', 'dimmer'])).stop).toEqual([])
  })

  it('leaves a Look layer’s effect running even where the cells are cleared', () => {
    const effects = [effect({ id: 9, lookId: 2, programmerLayerId: 4 })]
    const { stop, partial } = fxToStop(effects, noMembers, cleared(['hex-1', 'dimmer']))
    expect(stop).toEqual([])
    expect(partial).toEqual([])
  })

  it('stops a group effect only when the clear covers every member', () => {
    const members = (name: string) => (name === 'wash' ? ['hex-1', 'hex-2'] : [])
    const effects = [effect({ id: 5, targetKey: 'wash', isGroupTarget: true })]

    const half = fxToStop(effects, members, cleared(['hex-1', 'dimmer']))
    expect(half.stop).toEqual([])
    expect(half.partial.map((e) => e.id)).toEqual([5])

    const whole = fxToStop(
      effects,
      members,
      cleared(['hex-1', 'dimmer'], ['hex-2', 'dimmer']),
    )
    expect(whole.stop.map((e) => e.id)).toEqual([5])
    expect(whole.partial).toEqual([])
  })

  it('never sweeps a group with no members — "all of nothing" is not coverage', () => {
    const effects = [effect({ id: 5, targetKey: 'empty', isGroupTarget: true })]
    expect(fxToStop(effects, noMembers, cleared(['hex-1', 'dimmer'])).stop).toEqual([])
  })

  it('does nothing for an empty clear', () => {
    expect(fxToStop([effect({})], noMembers, new Set()).stop).toEqual([])
  })
})

describe('partialSweepMessage', () => {
  it('reads as one sentence in both numbers', () => {
    expect(partialSweepMessage([effect({})])).toContain('1 FX left running')
    expect(partialSweepMessage([effect({})])).toContain('it covers')
    expect(partialSweepMessage([effect({ id: 1 }), effect({ id: 2 })])).toContain(
      '2 FX left running',
    )
    expect(partialSweepMessage([effect({ id: 1 }), effect({ id: 2 })])).toContain('they cover')
  })
})

describe('the FX lens index', () => {
  const fixture = (key: string, groups: string[] = [], elements: string[] = []) =>
    ({ key, name: key, groups, elements: elements.map((k) => ({ key: k })) }) as unknown as Fixture
  const dimmer = (n: number) => sliderProp('dimmer', 'dimmer', chan(n))
  const bar = (key: string, groups: string[]) =>
    makeFixture(key, [], {
      name: key,
      groups,
      elements: [1, 2, 3, 4].map((n) => ({
        key: `${key}.${n}`,
        displayName: `Cell ${n}`,
        index: n - 1,
        attributes: [dimmer(10 + n)],
      })) as never,
    })

  it('keys an effect on the heads it paints: a bar’s cells, a group’s members', () => {
    const par = makeFixture('par', [dimmer(1), colourProp('rgbColour', chan(2), chan(3), chan(4))], { groups: ['wash'] })
    const spot = makeFixture('spot', [colourProp('rgbColour', chan(5), chan(6), chan(7))], { groups: [] })
    const index = buildCellFxIndex(
      [
        effect({ id: 1, targetKey: 'bar', attribute: 'dimmer' }),
        effect({ id: 2, targetKey: 'wash', isGroupTarget: true, targetType: 'group', attribute: 'rgbColour' }),
      ],
      [bar('bar', []), par, spot],
    )
    const ids = (key: string, prop: string) => fxForCell(index, [{ targetKey: key, attribute: prop }]).map((e) => e.id)
    expect(ids('bar.2', 'dimmer')).toEqual([1])
    expect(ids('par', 'rgbColour')).toEqual([2])
    expect(ids('spot', 'rgbColour')).toEqual([])
    // The property is half the key.
    expect(ids('par', 'dimmer')).toEqual([])
  })

  it('marks only the heads the head filter leaves an effect', () => {
    const index = buildCellFxIndex(
      [effect({ id: 1, targetKey: 'bar', attribute: 'dimmer', elementFilter: 'ODD' })],
      [bar('bar', [])],
    )
    const ids = (key: string) => fxForCell(index, [{ targetKey: key, attribute: 'dimmer' }]).map((e) => e.id)
    expect(['bar.1', 'bar.2', 'bar.3', 'bar.4'].map(ids)).toEqual([[1], [], [1], []])
  })

  it('lists a cell’s effects once each, in the desk’s order, whatever order its keys come in', () => {
    const index = buildCellFxIndex(
      [effect({ id: 7, targetKey: 'b' }), effect({ id: 3, targetKey: 'a' }), effect({ id: 9, targetKey: 'wash', isGroupTarget: true })],
      [makeFixture('a', [dimmer(1)], { groups: ['wash'] }), makeFixture('b', [dimmer(2)], { groups: ['wash'] })],
    )
    const keys = [
      { targetKey: 'a', attribute: 'dimmer' },
      { targetKey: 'b', attribute: 'dimmer' },
    ]
    expect(fxForCell(index, keys).map((e) => e.id)).toEqual([7, 3, 9])
  })

  it('draws its fixtures from the rows, a group row’s members included, once each', () => {
    const a = fixture('a')
    const b = fixture('b')
    const rows = [
      { kind: 'divider', id: 'd', label: 'Ungrouped' },
      { kind: 'group', id: 'g', name: 'wash', members: [a, b], isExpanded: false },
      { kind: 'fixture', id: 'f', fixture: a, isExpanded: false },
      { kind: 'element', id: 'e', fixture: b, element: { key: 'b.1' } },
    ] as unknown as Row[]
    expect(fixturesOfRows(rows).map((f) => f.key)).toEqual(['a', 'b'])
  })

  it('is empty with no effects, so a cell reads one stable answer', () => {
    expect(buildCellFxIndex([], [fixture('a')])).toBe(EMPTY_CELL_FX_INDEX)
    expect(fxForCell(EMPTY_CELL_FX_INDEX, [{ targetKey: 'a', attribute: 'dimmer' }])).toBe(
      fxForCell(EMPTY_CELL_FX_INDEX, [{ targetKey: 'b', attribute: 'dimmer' }]),
    )
  })

  it('notifies its readers only on a new index', () => {
    const store = createCellFxStore()
    const heard = vi.fn()
    store.subscribe(heard)
    store.set(EMPTY_CELL_FX_INDEX)
    expect(heard).not.toHaveBeenCalled()
    store.set(buildCellFxIndex([effect({ id: 1, targetKey: 'a' })], [fixture('a')]))
    expect(heard).toHaveBeenCalledTimes(1)
  })
})
