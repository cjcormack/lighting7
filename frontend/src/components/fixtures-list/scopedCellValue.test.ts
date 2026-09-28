import { describe, expect, it, vi } from 'vitest'

// scopedCellValue reaches useRowValues, which imports hooks/usePropertyValues and opens a
// WebSocket at import time — replace it before anything imports it.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { buildRows } from './rowModel'
import { buildRowCells } from './useRowValues'
import { cellValueFromParts, isLocalEntry, stagedPartFor } from './scopedCellValue'
import {
  chan,
  colourProp,
  groupSummary,
  makeFixture,
  positionProp,
  sliderProp,
} from '@/test/fixtureFactories'
import type { Fixture } from '@/store/fixtures'
import type { RowCell } from './useRowValues'
import type { StagedValue } from './useRowOwnership'
import type { ColumnKey } from './columns'

const cellFor = (fixtures: Fixture[], col: ColumnKey, groups = false): RowCell => {
  const rows = buildRows({
    fixtures,
    groups: groups ? [groupSummary('A', fixtures.length)] : [],
    expandedGroups: new Set(),
    textFilter: '',
  })
  return buildRowCells(rows[0], [col])[0]
}

/** A lookup over a plain `{ 'key.property': StagedValue }` table. */
const lookupOver =
  (table: Record<string, StagedValue>) => (targetKey: string, propertyName: string) =>
    table[`${targetKey}.${propertyName}`]

const partsFor = (cell: RowCell, table: Record<string, StagedValue>) =>
  cell.resolutions.map((res, i) => stagedPartFor(res, cell.targetKeys[i], lookupOver(table)))

const level = (value: number): StagedValue => ({ kind: 'level', value })
const colour = (r: number, g: number, b: number, w = 0, a = 0, uv = 0): StagedValue => ({
  kind: 'colour',
  r,
  g,
  b,
  w,
  a,
  uv,
})

describe('cellValueFromParts', () => {
  it('has no value when nothing in the scope set the cell', () => {
    const cell = cellFor([makeFixture('spot', [sliderProp('dimmer', 'dimmer', chan(10))])], 'dimmer')
    expect(cellValueFromParts(cell.resolutions, partsFor(cell, {}))).toBeUndefined()
  })

  it('distinguishes a set zero from unset', () => {
    const cell = cellFor([makeFixture('spot', [sliderProp('dimmer', 'dimmer', chan(10))])], 'dimmer')
    const value = cellValueFromParts(cell.resolutions, partsFor(cell, { 'spot.dimmer': level(0) }))
    // The whole point of Local scope: a zero the operator set is a value, and reads as one.
    expect(value).toEqual({ kind: 'slider', min: 0, max: 0, isUniform: true })
  })

  it('aggregates a group row and reports uniformity across its members', () => {
    const fixtures = [
      makeFixture('a', [sliderProp('dimmer', 'dimmer', chan(10))], { groups: ['A'] }),
      makeFixture('b', [sliderProp('dimmer', 'dimmer', chan(20))], { groups: ['A'] }),
    ]
    const cell = cellFor(fixtures, 'dimmer', true)
    expect(cell.resolutions).toHaveLength(2)

    const same = cellValueFromParts(
      cell.resolutions,
      partsFor(cell, { 'a.dimmer': level(120), 'b.dimmer': level(120) }),
    )
    expect(same).toMatchObject({ min: 120, max: 120, isUniform: true })

    const differing = cellValueFromParts(
      cell.resolutions,
      partsFor(cell, { 'a.dimmer': level(120), 'b.dimmer': level(200) }),
    )
    expect(differing).toMatchObject({ min: 120, max: 200, isUniform: false })
  })

  it('drops members nobody set, and says so by reporting the cell non-uniform', () => {
    const fixtures = [
      makeFixture('a', [sliderProp('dimmer', 'dimmer', chan(10))], { groups: ['A'] }),
      makeFixture('b', [sliderProp('dimmer', 'dimmer', chan(20))], { groups: ['A'] }),
      makeFixture('c', [sliderProp('dimmer', 'dimmer', chan(30))], { groups: ['A'] }),
    ]
    const cell = cellFor(fixtures, 'dimmer', true)
    const value = cellValueFromParts(cell.resolutions, partsFor(cell, { 'a.dimmer': level(90) }))
    // 90 rather than 30: an unset member contributes nothing rather than a zero that would drag
    // the average down and invent a value for a head the scope says nothing about.
    expect(value).toMatchObject({ kind: 'slider', min: 90, max: 90, isUniform: false })
  })

  it('averages a colour across members and keeps undefined components undefined', () => {
    const fixtures = [
      makeFixture('a', [colourProp('rgbColour', chan(1), chan(2), chan(3))], { groups: ['A'] }),
      makeFixture('b', [colourProp('rgbColour', chan(4), chan(5), chan(6))], { groups: ['A'] }),
    ]
    const cell = cellFor(fixtures, 'colour', true)
    const value = cellValueFromParts(
      cell.resolutions,
      partsFor(cell, {
        // A staged colour always carries w/a/uv; neither fixture has those channels.
        'a.rgbColour': colour(200, 100, 0, 255),
        'b.rgbColour': colour(100, 100, 0, 255),
      }),
    )
    expect(value).toMatchObject({ kind: 'colour', r: 150, g: 100, b: 0, isUniform: false })
    // The picker hides W/A/UV for a fixture with no such channel — a staged value must not
    // conjure them back.
    expect(value).toMatchObject({ w: undefined, a: undefined, uv: undefined })
  })

  it('carries a white component through when the fixture has the channel', () => {
    const withWhite = makeFixture('w', [
      colourProp('rgbColour', chan(1), chan(2), chan(3), { whiteChannel: chan(4) }),
    ])
    const cell = cellFor([withWhite], 'colour')
    const value = cellValueFromParts(
      cell.resolutions,
      partsFor(cell, { 'w.rgbColour': colour(10, 20, 30, 200) }),
    )
    expect(value).toMatchObject({ kind: 'colour', r: 10, g: 20, b: 30, w: 200 })
  })
})

describe('stagedPartFor', () => {
  it('treats a value of the wrong shape as unset rather than coercing it', () => {
    const cell = cellFor([makeFixture('spot', [sliderProp('dimmer', 'dimmer', chan(10))])], 'dimmer')
    const parts = partsFor(cell, { 'spot.dimmer': colour(1, 2, 3) })
    expect(parts).toEqual([undefined])
  })

  it('reads a real position descriptor as one property', () => {
    const mover = makeFixture('m', [positionProp('position', chan(1), chan(2))])
    const cell = cellFor([mover], 'position')
    const value = cellValueFromParts(
      cell.resolutions,
      partsFor(cell, { 'm.position': { kind: 'position', pan: 40, tilt: 60 } }),
    )
    expect(value).toMatchObject({ kind: 'position', pan: 40, tilt: 60, isUniform: true })
  })

  it('pairs separate pan and tilt sliders into one position', () => {
    const mover = makeFixture('m', [
      sliderProp('pan', 'pan', chan(1), { axis: 'PAN' }),
      sliderProp('tilt', 'tilt', chan(2), { axis: 'TILT' }),
    ])
    const cell = cellFor([mover], 'position')
    const value = cellValueFromParts(
      cell.resolutions,
      partsFor(cell, { 'm.pan': level(40), 'm.tilt': level(60) }),
    )
    expect(value).toMatchObject({ kind: 'position', pan: 40, tilt: 60, isUniform: true })
  })

  it('shows a half-set pan/tilt pair, but never as a settled value', () => {
    const mover = makeFixture('m', [
      sliderProp('pan', 'pan', chan(1), { axis: 'PAN' }),
      sliderProp('tilt', 'tilt', chan(2), { axis: 'TILT' }),
    ])
    const cell = cellFor([mover], 'position')
    const value = cellValueFromParts(cell.resolutions, partsFor(cell, { 'm.pan': level(40) }))
    // The crosshair is drawn — hiding a pan the scope really holds would be the worse lie — but
    // the missing axis reads 0 and the cell is non-uniform so nothing claims tilt is 0.
    expect(value).toMatchObject({ kind: 'position', pan: 40, tilt: 0, isUniform: false })
  })
})

describe('isLocalEntry', () => {
  const entry = (owner: string) => ({
    entry: { targetKey: 'a', propertyName: 'dimmer', value: '120', owner, touched: true, owners: [owner] },
  })

  it('is true for a value the operator set', () => {
    expect(isLocalEntry(entry('web'))).toBe(true)
    expect(isLocalEntry(entry('surface'))).toBe(true)
  })

  it('is false for a value a Look layer put there', () => {
    expect(isLocalEntry(entry('layers'))).toBe(false)
  })

  it('is false when nothing holds the key', () => {
    expect(isLocalEntry(undefined)).toBe(false)
    expect(isLocalEntry({})).toBe(false)
  })

  it('does not consult provenance, which lies under blind and under park', () => {
    // Blind gates the programmer out of the merge, so provenance reports whatever is beneath it;
    // a parked property reports PARKED. The entry is still what Record would take.
    expect(
      isLocalEntry({
        ...entry('web'),
        provenance: { targetKey: 'a', propertyName: 'dimmer', source: 'CUE', cueId: 4 },
      }),
    ).toBe(true)
    expect(
      isLocalEntry({
        ...entry('web'),
        provenance: { targetKey: 'a', propertyName: 'dimmer', source: 'PARKED' },
      }),
    ).toBe(true)
  })
})
