import { describe, expect, it, vi } from 'vitest'

// useRowOwnership pulls in the lighting API, which opens a WebSocket at import time.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { buildRows } from './rowModel'
import { buildRowCells } from './useRowValues'
import { aggregateCellOwnership } from './useRowOwnership'
import { chan, colourProp, groupSummary, makeFixture, sliderProp } from '@/test/fixtureFactories'
import type { ProgrammerEntry, ProgrammerKeyState, ProvenanceSource } from '@/api/programmerWsApi'
import type { CellPropertyKey } from './useRowValues'

function entry(overrides: Partial<ProgrammerEntry> = {}): ProgrammerEntry {
  return {
    targetKey: 'f1',
    propertyName: 'dimmer',
    value: '200',
    owner: 'web',
    touched: true,
    owners: ['web'],
    ...overrides,
  }
}

/** Build a lookup from a plain `"key|prop" → state` map. */
function lookupFrom(states: Record<string, ProgrammerKeyState>) {
  return (targetKey: string, propertyName: string): ProgrammerKeyState =>
    states[`${targetKey}|${propertyName}`] ?? {}
}

function provenance(source: ProvenanceSource): ProgrammerKeyState {
  return { provenance: { targetKey: 'f1', propertyName: 'dimmer', source } }
}

const KEY: CellPropertyKey[] = [{ targetKey: 'f1', propertyName: 'dimmer' }]

function layerProvenance(
  layerId: number,
  lookName: string,
  propertyName = 'dimmer',
  targetKey = 'f1',
): ProgrammerKeyState {
  return {
    provenance: {
      targetKey,
      propertyName,
      source: 'CUE',
      cueId: 4,
      layerId,
      layerSource: { kind: 'LOOK', id: layerId + 100, uuid: `u${layerId}`, name: lookName },
    },
  }
}

describe('aggregateCellOwnership — the winning layer', () => {
  it('names the layer that won the cell', () => {
    // The answer `source` alone cannot give: "Cue" says which layer of the engine, and the
    // operator asking why a fixture is this colour wants the look they built it from.
    const result = aggregateCellOwnership(KEY, false, lookupFrom({
      'f1|dimmer': layerProvenance(1, 'Warm Wash'),
    }))
    expect(result?.layer).toEqual({
      layerId: 1,
      source: { kind: 'LOOK', id: 101, uuid: 'u1', name: 'Warm Wash' },
      name: 'Warm Wash',
      mixed: false,
    })
    // Independent of `source`, which still reports the engine layer.
    expect(result?.source).toBe('cue')
  })

  it('leaves the layer unset when no provenance names one', () => {
    expect(aggregateCellOwnership(KEY, false, lookupFrom({ 'f1|dimmer': provenance('CUE') }))?.layer)
      .toBeUndefined()
  })

  it('drops the name when the covered properties were won by different layers', () => {
    // Naming one layer over a group row where half the heads came from another is a confident lie
    // about what the cell is showing — the same rule `paletteRef` follows.
    const keys: CellPropertyKey[] = [
      { targetKey: 'f1', propertyName: 'dimmer' },
      { targetKey: 'f2', propertyName: 'dimmer' },
    ]
    const result = aggregateCellOwnership(keys, false, lookupFrom({
      'f1|dimmer': layerProvenance(1, 'Warm Wash'),
      'f2|dimmer': layerProvenance(2, 'Cool Wash', 'dimmer', 'f2'),
    }))
    expect(result?.layer).toEqual({ layerId: undefined, lookId: undefined, name: undefined, mixed: true })
  })

  it('counts some-but-not-all as mixed, keeping the name', () => {
    // One layer, but only covering half the cell. The name is still true; the claim that it
    // explains the whole cell is not.
    const keys: CellPropertyKey[] = [
      { targetKey: 'f1', propertyName: 'dimmer' },
      { targetKey: 'f2', propertyName: 'dimmer' },
    ]
    const result = aggregateCellOwnership(keys, false, lookupFrom({
      'f1|dimmer': layerProvenance(1, 'Warm Wash'),
    }))
    expect(result?.layer).toMatchObject({ name: 'Warm Wash', mixed: true })
  })
})

describe('aggregateCellOwnership — a colour carrying a bundled emitter', () => {
  const COLOUR_KEY: CellPropertyKey[] = [{ targetKey: 'f1', propertyName: 'rgbColour' }]

  /** A cue-won colour from layer [layerId] whose white came from [whiteLayerId]'s row. */
  function colourWithWhite(layerId: number | undefined, whiteLayerId: number | undefined): ProgrammerKeyState {
    return {
      provenance: {
        targetKey: 'f1',
        propertyName: 'rgbColour',
        source: 'CUE',
        cueId: 4,
        layerId,
        layerSource: layerId == null ? undefined : { kind: 'LOOK', id: 101, uuid: 'u1', name: 'Warm Wash' },
        bundled: [{
          propertyName: 'white',
          cueId: 5,
          layerId: whiteLayerId,
          layerSource: whiteLayerId == null ? undefined : { kind: 'LOOK', id: 102, uuid: 'u2', name: 'Bright White' },
        }],
      },
    }
  }

  it('stays one layer when the white came from the same layer', () => {
    const result = aggregateCellOwnership(COLOUR_KEY, false, lookupFrom({ 'f1|rgbColour': colourWithWhite(1, 1) }))
    expect(result?.layer).toMatchObject({ layerId: 1, name: 'Warm Wash', mixed: false })
  })

  it('reads mixed, unnamed, when the white came from another layer', () => {
    // Naming the RGB's layer would credit it with a white it never asserted.
    const result = aggregateCellOwnership(COLOUR_KEY, false, lookupFrom({ 'f1|rgbColour': colourWithWhite(1, 2) }))
    expect(result?.layer).toMatchObject({ layerId: undefined, name: undefined, mixed: true })
  })

  it('reads mixed, keeping the name, when the white came from a cue row outside any layer', () => {
    const result = aggregateCellOwnership(COLOUR_KEY, false, lookupFrom({ 'f1|rgbColour': colourWithWhite(1, undefined) }))
    expect(result?.layer).toMatchObject({ layerId: 1, name: 'Warm Wash', mixed: true })
  })

  it('shows the white\'s layer as a partial claim when the RGB belongs to none', () => {
    const result = aggregateCellOwnership(COLOUR_KEY, false, lookupFrom({ 'f1|rgbColour': colourWithWhite(undefined, 2) }))
    expect(result?.layer).toMatchObject({ layerId: 2, name: 'Bright White', mixed: true })
  })
})

describe('aggregateCellOwnership', () => {
  it('returns undefined for a cell backing no properties', () => {
    expect(aggregateCellOwnership([], false, lookupFrom({}))).toBeUndefined()
  })

  it('reads baseline when nothing asserts the property', () => {
    const result = aggregateCellOwnership(KEY, false, lookupFrom({}))
    expect(result).toMatchObject({ source: 'baseline', isUniform: true, touched: false })
  })

  it('maps each provenance source onto its cell source', () => {
    const cases: Array<[ProvenanceSource, string]> = [
      ['PARKED', 'parked'],
      ['PROGRAMMER', 'programmer'],
      ['EFFECT', 'effect'],
      ['CUE', 'cue'],
    ]
    for (const [wire, expected] of cases) {
      const result = aggregateCellOwnership(
        KEY,
        false,
        lookupFrom({ 'f1|dimmer': provenance(wire) }),
      )
      expect(result?.source).toBe(expected)
    }
  })

  it('surfaces the programmer owners and the group a write came through', () => {
    const result = aggregateCellOwnership(
      KEY,
      false,
      lookupFrom({
        'f1|dimmer': {
          entry: entry({ owners: ['locate', 'web'], sourceGroup: 'Wash' }),
          ...provenance('PROGRAMMER'),
        },
      }),
    )
    expect(result?.owners).toEqual(['locate', 'web'])
    expect(result?.sourceGroup).toBe('Wash')
    expect(result?.touched).toBe(true)
  })

  it('treats an untouched hand-down as programmer-owned but not an operator edit', () => {
    // An unpark hand-down is releasable like any manual write, but must never read as
    // something Record would pick up.
    const result = aggregateCellOwnership(
      KEY,
      false,
      lookupFrom({
        'f1|dimmer': {
          entry: entry({ owner: 'unpark', touched: false, owners: ['unpark'] }),
          ...provenance('PROGRAMMER'),
        },
      }),
    )
    expect(result?.source).toBe('programmer')
    expect(result?.touched).toBe(false)
  })

  it('reads programmer while blind even though provenance reports the layer underneath', () => {
    // Blind gates the programmer out of the merge, so the engine's provenance names the cue.
    // The cell is still the operator's to edit and must be coloured as theirs.
    const result = aggregateCellOwnership(
      KEY,
      true,
      lookupFrom({ 'f1|dimmer': { entry: entry() } }),
    )
    expect(result?.source).toBe('programmer')
  })

  it('flags a mixed cell and reports the most significant source', () => {
    const keys: CellPropertyKey[] = [
      { targetKey: 'f1', propertyName: 'dimmer' },
      { targetKey: 'f2', propertyName: 'dimmer' },
    ]
    const result = aggregateCellOwnership(
      keys,
      false,
      lookupFrom({
        'f1|dimmer': provenance('CUE'),
        'f2|dimmer': provenance('PARKED'),
      }),
    )
    // Park outranks everything: it is the one state where the rig ignores the operator.
    expect(result).toMatchObject({ source: 'parked', isUniform: false })
  })

  it('ranks programmer above effect and cue but below parked', () => {
    const keys: CellPropertyKey[] = [
      { targetKey: 'f1', propertyName: 'dimmer' },
      { targetKey: 'f2', propertyName: 'dimmer' },
    ]
    expect(
      aggregateCellOwnership(
        keys,
        false,
        lookupFrom({ 'f1|dimmer': provenance('EFFECT'), 'f2|dimmer': provenance('PROGRAMMER') }),
      )?.source,
    ).toBe('programmer')
  })

  it('stages a value only while blind, and only when the cell agrees on it', () => {
    const keys: CellPropertyKey[] = [
      { targetKey: 'f1', propertyName: 'dimmer' },
      { targetKey: 'f2', propertyName: 'dimmer' },
    ]
    const agreeing = lookupFrom({
      'f1|dimmer': { entry: entry({ value: '200' }) },
      'f2|dimmer': { entry: entry({ targetKey: 'f2', value: '200' }) },
    })

    expect(aggregateCellOwnership(keys, true, agreeing)?.staged).toEqual({
      kind: 'level',
      value: 200,
    })
    // Live: the wire value already is the programmer value, so no substitution.
    expect(aggregateCellOwnership(keys, false, agreeing)?.staged).toBeUndefined()

    const disagreeing = lookupFrom({
      'f1|dimmer': { entry: entry({ value: '200' }) },
      'f2|dimmer': { entry: entry({ targetKey: 'f2', value: '10' }) },
    })
    expect(aggregateCellOwnership(keys, true, disagreeing)?.staged).toBeUndefined()
  })

  // Five tests stood here, all about `paletteRef`: that the *resolved* literal was staged rather
  // than the `ref:` string (a group row on a POSITION palette holds the identical reference on every
  // head while resolving to a different pan/tilt for each, so comparing `value` reported the cell
  // uniform and painted one head's crosshair for all of them); that the referenced look was named
  // when every covered property agreed; that the cell was called *mixed* when only some held one;
  // that an unresolved reference was marked; and that a literal-only cell reported no reference at
  // all. The `ref:` value grammar retired in session 4 — an entry's `value` *is* its literal — and
  // `CellLayer` answers "where did this come from" instead, covered above.

  it('does not stage when only some covered properties are held', () => {
    const keys: CellPropertyKey[] = [
      { targetKey: 'f1', propertyName: 'dimmer' },
      { targetKey: 'f2', propertyName: 'dimmer' },
    ]
    const result = aggregateCellOwnership(
      keys,
      true,
      lookupFrom({ 'f1|dimmer': { entry: entry({ value: '200' }) } }),
    )
    expect(result?.staged).toBeUndefined()
  })
})

describe('buildRowCells keys', () => {
  it('keys a fixture cell by its own key and the backend property name', () => {
    const f = makeFixture('spot', [
      sliderProp('dimmer', 'dimmer', chan(1)),
      colourProp('rgbColour', chan(2), chan(3), chan(4)),
    ])
    const [row] = buildRows({
      fixtures: [f],
      groups: [],
      expandedGroups: new Set(),
      textFilter: '',
    })
    const cells = buildRowCells(row, ['dimmer', 'colour'])
    expect(cells.find((c) => c.col === 'dimmer')?.keys).toEqual([
      { targetKey: 'spot', propertyName: 'dimmer' },
    ])
    expect(cells.find((c) => c.col === 'colour')?.keys).toEqual([
      { targetKey: 'spot', propertyName: 'rgbColour' },
    ])
  })

  it('keys a group cell per member, matching how the backend fans group writes out', () => {
    const a = makeFixture('a', [sliderProp('dimmer', 'dimmer', chan(1))], { groups: ['Wash'] })
    const b = makeFixture('b', [sliderProp('dimmer', 'dimmer', chan(2))], { groups: ['Wash'] })
    const rows = buildRows({
      fixtures: [a, b],
      groups: [groupSummary('Wash', 2)],
      expandedGroups: new Set(),
      textFilter: '',
    })
    expect(buildRowCells(rows[0], ['dimmer'])[0].keys).toEqual([
      { targetKey: 'a', propertyName: 'dimmer' },
      { targetKey: 'b', propertyName: 'dimmer' },
    ])
  })
})
