import { describe, expect, it, vi } from 'vitest'

// useRowValues imports hooks/usePropertyValues, which opens a WebSocket at
// import time — replace it before anything imports it.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { buildRows } from './rowModel'
import { buildRowCells } from './useRowValues'
import { chan, groupSummary, makeFixture, makePixelBar, sliderProp } from '@/test/fixtureFactories'
import type { Row } from './rowModel'

const soloRow = (fixtures: Parameters<typeof buildRows>[0]['fixtures'], expanded: string[] = []) =>
  buildRows({
    fixtures,
    groups: [],
    expandedGroups: new Set(),
    expandedFixtures: new Set(expanded),
    textFilter: '',
  })

describe('buildRowCells with multi-head fixtures', () => {
  it('aggregates one resolution per element when the parent lacks the property', () => {
    const bar = makePixelBar('bar', 3)
    const [row] = soloRow([bar])
    const cells = buildRowCells(row, ['dimmer', 'colour'])
    expect(cells).toHaveLength(1)
    expect(cells[0].col).toBe('colour')
    expect(cells[0].resolutions).toHaveLength(3)
  })

  it('lets a parent-level property win outright over element fallbacks', () => {
    const master = makePixelBar('master', 2, [sliderProp('dimmer', 'dimmer', chan(200))])
    const [row] = soloRow([master])
    const dimmerCell = buildRowCells(row, ['dimmer'])[0]
    expect(dimmerCell.resolutions).toHaveLength(1)
    const colourCell = buildRowCells(row, ['colour'])[0]
    expect(colourCell.resolutions).toHaveLength(2)
  })

  it('resolves element rows against the element properties alone', () => {
    const bar = makePixelBar('bar', 2)
    const rows = soloRow([bar], ['bar'])
    const elementRow = rows.find((r): r is Extract<Row, { kind: 'element' }> => r.kind === 'element')!
    const cells = buildRowCells(elementRow, ['dimmer', 'colour'])
    expect(cells).toHaveLength(1)
    expect(cells[0].col).toBe('colour')
    expect(cells[0].resolutions).toHaveLength(1)
  })

  it('applies the element fallback per member on group rows', () => {
    const bar = makePixelBar('gbar', 3, [], { groups: ['A'] })
    const spot = makeFixture('spot', [sliderProp('dimmer', 'dimmer', chan(210))], {
      groups: ['A'],
    })
    const rows = buildRows({
      fixtures: [bar, spot],
      groups: [groupSummary('A', 2)],
      expandedGroups: new Set(),
      textFilter: '',
    })
    const groupRow = rows[0]
    expect(groupRow.kind).toBe('group')
    // Colour: three element resolutions from the bar; the spot has none.
    expect(buildRowCells(groupRow, ['colour'])[0].resolutions).toHaveLength(3)
    // Dimmer: only the spot's parent-level dimmer.
    expect(buildRowCells(groupRow, ['dimmer'])[0].resolutions).toHaveLength(1)
  })
})

describe('buildRowCells targetKeys', () => {
  it('pairs one target key with each resolution on a group row', () => {
    const fixtures = [
      makeFixture('a', [sliderProp('dimmer', 'dimmer', chan(10))], { groups: ['A'] }),
      makeFixture('b', [sliderProp('dimmer', 'dimmer', chan(20))], { groups: ['A'] }),
    ]
    const rows = buildRows({
      fixtures,
      groups: [groupSummary('A', 2)],
      expandedGroups: new Set(),
      textFilter: '',
    })
    const cell = buildRowCells(rows[0], ['dimmer'])[0]
    expect(cell.targetKeys).toEqual(['a', 'b'])
    expect(cell.targetKeys).toHaveLength(cell.resolutions.length)
  })

  it('names the element, not the parent, where a multi-head row falls back', () => {
    const bar = makePixelBar('bar', 3)
    const [row] = soloRow([bar])
    const cell = buildRowCells(row, ['colour'])[0]
    expect(cell.targetKeys).toEqual(['bar.pixel-0', 'bar.pixel-1', 'bar.pixel-2'])
  })

  it('stays index-parallel where `keys` cannot — a position paired from two sliders', () => {
    const mover = makeFixture('m', [
      sliderProp('pan', 'pan', chan(1), { axis: 'PAN' }),
      sliderProp('tilt', 'tilt', chan(2), { axis: 'TILT' }),
    ])
    const [row] = soloRow([mover])
    const cell = buildRowCells(row, ['position'])[0]
    // One resolution, one target key — but *two* keys. This mismatch is why `targetKeys` exists
    // rather than being derived from `keys`.
    expect(cell.resolutions).toHaveLength(1)
    expect(cell.targetKeys).toEqual(['m'])
    expect(cell.keys.map((k) => k.propertyName)).toEqual(['pan', 'tilt'])
  })
})
