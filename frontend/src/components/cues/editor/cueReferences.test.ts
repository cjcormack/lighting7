import { describe, expect, it } from 'vitest'
import { cueReferencesIn } from './cueReferences'
import type { CueReferenceRow } from './CueRowStore'

const DSR = { paletteId: 2, paletteUuid: 'dsr', name: 'DSR Focus' }
const ROWS: CueReferenceRow[] = [
  { uuid: 'r1', reference: DSR, targetType: 'fixture', targetKey: 'spot-1', heads: ['spot-1'] },
  { uuid: 'r2', reference: DSR, targetType: 'group', targetKey: 'spots', heads: ['spot-2', 'spot-3'] },
]

describe('cueReferencesIn', () => {
  it('picks nothing with nothing selected, so Copy the values takes every reference', () => {
    expect(cueReferencesIn(ROWS, [], [])).toEqual(new Set())
  })

  it('picks a row on a selected target', () => {
    expect(cueReferencesIn(ROWS, [{ type: 'group', key: 'spots' }], ['spot-2', 'spot-3'])).toEqual(new Set(['r2']))
    expect(cueReferencesIn(ROWS, [{ type: 'fixture', key: 'spot-1' }], ['spot-1'])).toEqual(new Set(['r1']))
  })

  it('picks a group row the desk resolved onto a selected head', () => {
    expect(cueReferencesIn(ROWS, [{ type: 'fixture', key: 'spot-3' }], ['spot-3'])).toEqual(new Set(['r2']))
  })

  it('picks a fixture row under a selected group, and a cell row under a selected parent', () => {
    const rows: CueReferenceRow[] = [
      ...ROWS,
      { uuid: 'r3', reference: DSR, targetType: 'fixture', targetKey: 'bar-1.2', heads: ['bar-1.2'] },
    ]
    // A group selected: its members are the heads it stands for.
    expect(cueReferencesIn(rows, [{ type: 'group', key: 'front' }], ['spot-1', 'spot-2'])).toEqual(new Set(['r1', 'r2']))
    // A parent selected: its cells are among them.
    expect(cueReferencesIn(rows, [{ type: 'fixture', key: 'bar-1' }], ['bar-1.1', 'bar-1.2'])).toEqual(new Set(['r3']))
  })

  it('leaves a row whose heads are not selected', () => {
    expect(cueReferencesIn(ROWS, [{ type: 'fixture', key: 'spot-9' }], ['spot-9'])).toEqual(new Set())
  })
})
