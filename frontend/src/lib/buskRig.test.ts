import { describe, expect, it } from 'vitest'
import type { GroupSummary } from '@/api/groupsApi'
import type { CueTarget } from '@/api/cuesApi'
import type { BuskRig, BuskRigElement, BuskRigPatch, BuskRigRow, BuskRigTile } from '@/api/buskRigApi'
import type { Fixture } from '@/store/fixtures'
import fixture from '../../../src/test/resources/busk/rigOrder.fixture.json'
import {
  applyDrop,
  effectiveRig,
  expandTile,
  nextRowName,
  normaliseRig,
  parseRigDragId,
  recordsOnRig,
  removeRow,
  removeTile,
  relabelTile,
  renameRow,
  rigDropTargetFor,
  rigIdsFromPatches,
  rigPaletteId,
  rigRowBodyId,
  rigRowGapId,
  rigRowId,
  RIG_NEW_ROW_ID,
  RigRequestError,
  rigSteps,
  rigTileId,
  runsOf,
  setTile,
  tileOwnName,
  toRigRequest,
  type RigIds,
  rigLines,
  rowFlow,
  rowWidth,
  setRowLayout,
} from './buskRig'
import { parseBuskDragId } from './buskLayout'

/**
 * The rig document (busk-further plan session 3), `buskLayout.test.ts`'s sibling.
 *
 * The load-bearing pin is the last block: **the show-all fallback's order is the desk's**.
 * `state/BuskRigOrder.kt` walks the rig for `selection.subselect`'s *Next* / *Prev*, and with an
 * empty rig it answers every group then every fixture — which is exactly what `effectiveRig` draws.
 * The two must agree, so this file imports the server's own test fixture,
 * `src/test/resources/busk/rigOrder.fixture.json` — the one `BuskRigOrderTest` reads — from the
 * backend's tree. It was a hand-refreshed copy while the frontend was a separate repo; one commit
 * now changes the rule, the fixture and both pins together.
 */

// ─── Fixtures ───────────────────────────────────────────────────────────

const frontWash: GroupSummary = {
  name: 'front-wash',
  memberCount: 2,
  capabilities: [],
  symmetricMode: 'NONE',
  defaultDistribution: 'LINEAR',
  compatibleLookIds: [],
}

/** The 12-pixel bar's cells, as the fixture type names them — test data, not a parse. */
function barCells(key: string): BuskRigElement[] {
  return Array.from({ length: 12 }, (_, i) => ({ key: `${key}.pixel-${i}`, name: `Pixel ${i + 1}` }))
}

const barL: BuskRigPatch = { id: 11, key: 'bar-1', name: 'Bar L', elements: barCells('bar-1') }
const barR: BuskRigPatch = { id: 12, key: 'bar-2', name: 'Bar R', elements: barCells('bar-2') }
const hex2: BuskRigPatch = { id: 13, key: 'hex-2', name: 'Hex 2' }

let nextId = 100
function groupTile(group: GroupSummary): BuskRigTile {
  const id = nextId++
  return { id, uuid: `t${id}`, kind: 'GROUP', group, cellMode: 'PIPS' }
}
function fixtureTile(patch: BuskRigPatch, overrides: Partial<BuskRigTile> = {}): BuskRigTile {
  const id = nextId++
  return { id, uuid: `t${id}`, kind: 'FIXTURE', patch, cellMode: 'PIPS', ...overrides }
}
function row(name: string, tiles: BuskRigTile[]): BuskRigRow {
  const id = nextId++
  return { id, uuid: `r${id}`, name, tiles }
}

/** Row 0 `Wash`: front-wash · Hex 2 · Bar L (pips). Row 1 `Bars`: Bar R per cell. */
function sampleRig(): BuskRig {
  return {
    rows: [
      row('Wash', [groupTile(frontWash), fixtureTile(hex2), fixtureTile(barL)]),
      row('Bars', [fixtureTile(barR, { cellMode: 'PER_CELL' })]),
    ],
  }
}

function tileNames(rig: BuskRig, r: number): string[] {
  return (rig.rows?.[r]?.tiles ?? []).map((t) => t.group?.name ?? t.patch?.name ?? '?')
}

const ids: RigIds = {
  groupIdByName: new Map([['front-wash', 7]]),
  patchIdByKey: new Map([['bar-1', 11]]),
}

// ─── Drag ids ───────────────────────────────────────────────────────────

describe('rig drag ids', () => {
  it('round-trip every kind', () => {
    expect(parseRigDragId(rigTileId({ row: 1, tile: 4 }))).toEqual({ kind: 'rig-tile', at: { row: 1, tile: 4 } })
    expect(parseRigDragId(rigRowId(2))).toEqual({ kind: 'rig-row', row: 2 })
    expect(parseRigDragId(rigRowBodyId(2))).toEqual({ kind: 'rig-row-body', row: 2 })
    expect(parseRigDragId(rigRowGapId(3))).toEqual({ kind: 'rig-row-gap', row: 3 })
    expect(parseRigDragId(RIG_NEW_ROW_ID)).toEqual({ kind: 'rig-new-row' })
    expect(parseRigDragId(rigPaletteId({ kind: 'group', group: frontWash }))).toEqual({
      kind: 'rig-palette',
      recordKind: 'group',
      key: 'front-wash',
    })
    expect(parseRigDragId(rigPaletteId({ kind: 'cell', patch: barL, element: barL.elements![3] }))).toEqual({
      kind: 'rig-palette',
      recordKind: 'cell',
      key: 'bar-1.pixel-3',
    })
  })

  it('keep a group name with a colon whole — the key is everything after the second colon', () => {
    const odd = { ...frontWash, name: 'Wash: front' }
    expect(parseRigDragId(rigPaletteId({ kind: 'group', group: odd }))).toMatchObject({ key: 'Wash: front' })
  })

  it('read a render tile’s suffixed id back to its stored address', () => {
    // A PER_CELL tile is one stored tile drawn as several; each drawn tile registers its own id.
    expect(parseRigDragId(rigTileId({ row: 1, tile: 4 }, 't9:bar-1.pixel-3'))).toEqual({
      kind: 'rig-tile',
      at: { row: 1, tile: 4 },
    })
    expect(rigTileId({ row: 1, tile: 4 }, 'a')).not.toBe(rigTileId({ row: 1, tile: 4 }, 'b'))
  })

  it('are foreign to the page and the page’s are foreign to the rig', () => {
    expect(parseBuskDragId(rigTileId({ row: 0, tile: 0 }))).toBeNull()
    expect(parseBuskDragId(rigRowId(0))).toBeNull()
    expect(parseBuskDragId(RIG_NEW_ROW_ID)).toBeNull()
    expect(parseBuskDragId(rigPaletteId({ kind: 'fixture', patch: barL }))).toBeNull()
    expect(parseRigDragId('bpad:0.0.0.0')).toBeNull()
    expect(parseRigDragId('bnewrow')).toBeNull()
    expect(parseRigDragId('palette:look:9')).toBeNull()
    expect(parseRigDragId('slot-1-2')).toBeNull()
  })

  it('collapse a row body to an append, and a fallback row to nothing', () => {
    const rig = sampleRig()
    expect(rigDropTargetFor({ kind: 'rig-row-body', row: 0 }, rig)).toEqual({
      kind: 'tile',
      at: { row: 0, tile: 3 },
    })
    expect(rigDropTargetFor({ kind: 'rig-row-body', row: 9 }, rig)).toBeNull()
    expect(rigDropTargetFor({ kind: 'rig-palette', recordKind: 'group', key: 'x' }, rig)).toBeNull()
  })
})

// ─── Reading and minting ────────────────────────────────────────────────

describe('reading a rig', () => {
  it('names every record on it, a split tile as its fixture and a cell tile as its cell', () => {
    const rig = sampleRig()
    rig.rows![1].tiles!.push(fixtureTile(barL, { elementKey: 'bar-1.pixel-5', cellMode: 'WHOLE' }))
    expect([...recordsOnRig(rig)]).toEqual([
      'group:front-wash',
      'fixture:hex-2',
      'fixture:bar-1',
      'fixture:bar-2',
      'cell:bar-1.pixel-5',
    ])
  })

  it('mints `Row N` for the smallest free N', () => {
    expect(nextRowName({ rows: [] })).toBe('Row 1')
    expect(nextRowName({ rows: [row('Row 1', []), row('Row 3', [])] })).toBe('Row 2')
  })

  it('resolves group ids through the patch list, which is the one place the desk publishes them', () => {
    const resolved = rigIdsFromPatches([
      { id: 11, key: 'bar-1', groups: [{ id: 7, name: 'front-wash' }] } as never,
      { id: 12, key: 'bar-2', groups: [] } as never,
    ])
    expect(resolved.groupIdByName.get('front-wash')).toBe(7)
    expect(resolved.patchIdByKey.get('bar-2')).toBe(12)
  })
})

// ─── Normalising ────────────────────────────────────────────────────────

describe('normaliseRig', () => {
  it('drops an empty row and keeps an empty rig', () => {
    expect(normaliseRig({ rows: [row('Empty', []), row('Kept', [fixtureTile(hex2)])] }).rows).toHaveLength(1)
    expect(normaliseRig({ rows: [] })).toEqual({ rows: [] })
    expect(normaliseRig({})).toEqual({ rows: [] })
  })

  it('is applied by every mutator, so crossing the last tile off takes the row with it', () => {
    const rig = sampleRig()
    const next = removeTile(rig, { row: 1, tile: 0 })
    expect(next.rows).toHaveLength(1)
    expect(next.rows![0].name).toBe('Wash')
  })
})

// ─── The wire body ──────────────────────────────────────────────────────

describe('toRigRequest', () => {
  it('carries ids, resolves a group by name and a patch by its own id, and omits uuids', () => {
    const rig = sampleRig()
    const body = toRigRequest(rig, ids)
    expect(body.rows[0].rowId).toBe(rig.rows![0].id)
    expect(body.rows[0].tiles[0]).toEqual({ tileId: rig.rows![0].tiles![0].id, groupId: 7, cellMode: 'PIPS' })
    expect(body.rows[0].tiles[2]).toEqual({ tileId: rig.rows![0].tiles![2].id, patchId: 11, cellMode: 'PIPS' })
    expect(JSON.stringify(body)).not.toContain('uuid')
    expect(JSON.stringify(body)).not.toContain('localKey')
  })

  it('carries a label on either kind — a group tile’s must not be lost to the group arm’s early return', () => {
    const rig = sampleRig()
    rig.rows![0].tiles![0].label = 'Warm wash'
    rig.rows![0].tiles![2].label = ' Left bar '
    const body = toRigRequest(rig, ids)
    expect(body.rows[0].tiles[0]).toEqual({ tileId: rig.rows![0].tiles![0].id, groupId: 7, cellMode: 'PIPS', label: 'Warm wash' })
    expect(body.rows[0].tiles[2]).toMatchObject({ patchId: 11, label: 'Left bar' })
    expect(body.rows[0].tiles[1]).not.toHaveProperty('label')
  })

  it('sends cellSplit only with HALVES, and an element key only on a cell tile', () => {
    const rig: BuskRig = {
      rows: [
        row('R', [
          fixtureTile(barL, { cellMode: 'HALVES', cellSplit: 3 }),
          fixtureTile(barL, { cellMode: 'PER_CELL', cellSplit: 3 }),
          fixtureTile(barL, { elementKey: 'bar-1.pixel-2', cellMode: 'WHOLE', label: ' Left ' }),
        ]),
      ],
    }
    const tiles = toRigRequest(rig, ids).rows[0].tiles
    expect(tiles[0]).toMatchObject({ cellMode: 'HALVES', cellSplit: 3 })
    expect(tiles[1]).not.toHaveProperty('cellSplit')
    expect(tiles[2]).toMatchObject({ elementKey: 'bar-1.pixel-2', cellMode: 'WHOLE', label: 'Left' })
  })

  it('omits ids on a node this client minted', () => {
    const rig: BuskRig = { rows: [{ localKey: 'new-1', name: 'New', tiles: [{ localKey: 'new-2', kind: 'GROUP', group: frontWash, cellMode: 'PIPS' }] }] }
    const body = toRigRequest(rig, ids)
    expect(body.rows[0]).not.toHaveProperty('rowId')
    expect(body.rows[0].tiles[0]).toEqual({ groupId: 7, cellMode: 'PIPS' })
  })

  it('refuses, by name, a group the desk has published no id for — and says which way out', () => {
    const memberless = { ...frontWash, name: 'Empty group' }
    // A tile being placed: add a member first.
    const placing: BuskRigTile = { localKey: 'new-9', kind: 'GROUP', group: memberless, cellMode: 'PIPS' }
    expect(() => toRigRequest({ rows: [row('R', [placing])] }, ids)).toThrow(RigRequestError)
    expect(() => toRigRequest({ rows: [row('R', [placing])] }, ids)).toThrow(/before placing/)
    // A tile already on the rig blocks every gesture: name it, and offer removal.
    expect(() => toRigRequest({ rows: [row('R', [groupTile(memberless)])] }, ids)).toThrow(/row 1, tile 1.*remove that tile/)
  })
})

// ─── Mutators ───────────────────────────────────────────────────────────

describe('editing a rig', () => {
  it('renames and removes a row', () => {
    const rig = sampleRig()
    expect(renameRow(rig, 1, 'Pixels').rows![1].name).toBe('Pixels')
    expect(removeRow(rig, 0).rows!.map((r) => r.name)).toEqual(['Bars'])
  })

  it('never writes into the rig it was handed — the cache’s tiles are frozen', () => {
    // The desk crash this pins: `clone` copied the tiles *array* and not the tiles, so `setTile`'s
    // assignment landed on the RTK Query cache's own frozen object.
    const rig = sampleRig()
    for (const r of rig.rows!) {
      for (const t of r.tiles!) Object.freeze(t)
      Object.freeze(r.tiles)
      Object.freeze(r)
    }
    Object.freeze(rig.rows)
    Object.freeze(rig)
    const next = setTile(rig, { row: 0, tile: 2 }, { cellMode: 'HALVES', cellSplit: 3 })
    expect(next.rows![0].tiles![2]).toMatchObject({ cellMode: 'HALVES', cellSplit: 3 })
    expect(rig.rows![0].tiles![2].cellMode).toBe('PIPS')
    expect(() => renameRow(rig, 0, 'Frozen')).not.toThrow()
    expect(() => applyDrop(rig, { kind: 'rig-tile', at: { row: 0, tile: 0 } }, { kind: 'tile', at: { row: 1, tile: 0 } })).not.toThrow()
  })

  it('names what a tile wears with no label the way expandTile applies one — shown name where a label replaces, fixture name where it composes', () => {
    expect(tileOwnName(groupTile(frontWash))).toBe('front-wash')
    expect(tileOwnName(fixtureTile(barL))).toBe('Bar L')
    expect(tileOwnName(fixtureTile(barL, { cellMode: 'PER_CELL' }))).toBe('Bar L')
    expect(tileOwnName(fixtureTile(barL, { cellMode: 'HALVES', cellSplit: 2 }))).toBe('Bar L')
    const cell = fixtureTile(barL, { elementKey: 'bar-1.pixel-2', cellMode: 'WHOLE' })
    expect(tileOwnName(cell)).toBe(expandTile(cell, 'k')[0].name)
    // A label replaces the shown name on a cell tile and composes on a per-cell one.
    expect(expandTile({ ...cell, label: 'Left' }, 'k')[0].name).toBe('Left')
    expect(expandTile(fixtureTile(barL, { cellMode: 'PER_CELL', label: 'Left' }), 'k')[0].name).toContain('Left')
  })

  it('relabels a tile of either kind, and a blank or the record’s name clears the label', () => {
    const rig = sampleRig()
    expect(relabelTile(rig, { row: 0, tile: 0 }, ' Wash ').rows![0].tiles![0].label).toBe('Wash')
    expect(relabelTile(rig, { row: 0, tile: 2 }, 'Left bar').rows![0].tiles![2].label).toBe('Left bar')
    const cleared = relabelTile(relabelTile(rig, { row: 0, tile: 2 }, 'Left bar'), { row: 0, tile: 2 }, '  ')
    expect(cleared.rows![0].tiles![2].label).toBeNull()
    expect(relabelTile(rig, { row: 0, tile: 2 }, null).rows![0].tiles![2].label).toBeNull()
    expect(relabelTile(rig, { row: 9, tile: 0 }, 'x')).toBe(rig)
  })

  it('sets a cell mode, clears the split on leaving HALVES, and leaves a group alone', () => {
    const rig = sampleRig()
    const halved = setTile(rig, { row: 0, tile: 2 }, { cellMode: 'HALVES', cellSplit: 4 })
    expect(halved.rows![0].tiles![2]).toMatchObject({ cellMode: 'HALVES', cellSplit: 4 })
    const whole = setTile(halved, { row: 0, tile: 2 }, { cellMode: 'WHOLE' })
    expect(whole.rows![0].tiles![2]).toMatchObject({ cellMode: 'WHOLE', cellSplit: null })
    expect(setTile(rig, { row: 0, tile: 0 }, { cellMode: 'WHOLE' })).toBe(rig)
  })
})

describe('applyDrop', () => {
  it('inserts a palette record before the tile named, and appends at the row’s length', () => {
    const rig = sampleRig()
    const inserted = applyDrop(rig, { kind: 'rig-palette', record: { kind: 'fixture', patch: barR } }, { kind: 'tile', at: { row: 0, tile: 1 } })
    expect(tileNames(inserted!, 0)).toEqual(['front-wash', 'Bar R', 'Hex 2', 'Bar L'])
    const appended = applyDrop(rig, { kind: 'rig-palette', record: { kind: 'group', group: frontWash } }, { kind: 'tile', at: { row: 1, tile: 1 } })
    expect(tileNames(appended!, 1)).toEqual(['Bar R', 'front-wash'])
  })

  it('starts a new row from a palette record, named for the smallest free N', () => {
    const rig = sampleRig()
    const next = applyDrop(rig, { kind: 'rig-palette', record: { kind: 'cell', patch: barL, element: barL.elements![0] } }, { kind: 'new-row' })
    expect(next!.rows!.map((r) => r.name)).toEqual(['Wash', 'Bars', 'Row 1'])
    expect(next!.rows![2].tiles![0]).toMatchObject({ kind: 'FIXTURE', elementKey: 'bar-1.pixel-0', cellMode: 'WHOLE' })
  })

  it('moves a tile later within its row without overshooting — the slot is an insertion point', () => {
    const rig = sampleRig()
    // The slot is drawn after Bar L (index 3, past the last tile) with front-wash still in place.
    const next = applyDrop(rig, { kind: 'rig-tile', at: { row: 0, tile: 0 } }, { kind: 'tile', at: { row: 0, tile: 3 } })
    expect(tileNames(next!, 0)).toEqual(['Hex 2', 'Bar L', 'front-wash'])
    // And earlier needs no correction.
    const back = applyDrop(next!, { kind: 'rig-tile', at: { row: 0, tile: 2 } }, { kind: 'tile', at: { row: 0, tile: 0 } })
    expect(tileNames(back!, 0)).toEqual(['front-wash', 'Hex 2', 'Bar L'])
  })

  it('moves a tile between rows, and prunes the row it emptied', () => {
    const rig = sampleRig()
    const next = applyDrop(rig, { kind: 'rig-tile', at: { row: 1, tile: 0 } }, { kind: 'tile', at: { row: 0, tile: 0 } })
    expect(next!.rows).toHaveLength(1)
    expect(tileNames(next!, 0)).toEqual(['Bar R', 'front-wash', 'Hex 2', 'Bar L'])
  })

  it('moves a row onto a gap, and refuses the gaps beside itself', () => {
    const rig = { rows: [...sampleRig().rows!, row('Third', [fixtureTile(hex2)])] }
    expect(applyDrop(rig, { kind: 'rig-row', row: 0 }, { kind: 'row-gap', row: 3 })!.rows!.map((r) => r.name)).toEqual(['Bars', 'Third', 'Wash'])
    expect(applyDrop(rig, { kind: 'rig-row', row: 2 }, { kind: 'row-gap', row: 0 })!.rows!.map((r) => r.name)).toEqual(['Third', 'Wash', 'Bars'])
    expect(applyDrop(rig, { kind: 'rig-row', row: 1 }, { kind: 'row-gap', row: 1 })).toBeNull()
    expect(applyDrop(rig, { kind: 'rig-row', row: 1 }, { kind: 'row-gap', row: 2 })).toBeNull()
  })

  it('answers null for a pairing that is not a gesture, and for a drop that changes nothing', () => {
    const rig = sampleRig()
    expect(applyDrop(rig, { kind: 'rig-row', row: 0 }, { kind: 'tile', at: { row: 0, tile: 0 } })).toBeNull()
    expect(applyDrop(rig, { kind: 'rig-tile', at: { row: 0, tile: 1 } }, { kind: 'row-gap', row: 0 })).toBeNull()
    expect(applyDrop(rig, { kind: 'rig-tile', at: { row: 0, tile: 1 } }, { kind: 'tile', at: { row: 0, tile: 1 } })).toBeNull()
    expect(applyDrop(rig, { kind: 'rig-tile', at: { row: 0, tile: 1 } }, { kind: 'tile', at: { row: 0, tile: 2 } })).toBeNull()
  })

  it('mints documents the server would accept', () => {
    const rig = sampleRig()
    const next = applyDrop(rig, { kind: 'rig-palette', record: { kind: 'group', group: frontWash } }, { kind: 'new-row' })!
    const body = toRigRequest(next, ids)
    expect(body.rows.every((r) => r.name.trim().length > 0 && r.tiles.length > 0)).toBe(true)
  })
})

// ─── Expansion ──────────────────────────────────────────────────────────

describe('expandTile', () => {
  it('cuts runs the desk’s way: twelve cells in five runs are 3 · 3 · 2 · 2 · 2', () => {
    expect(runsOf(barCells('bar-1'), 5).map((run) => run.length)).toEqual([3, 3, 2, 2, 2])
    expect(runsOf(barCells('bar-1'), 2).map((run) => run.length)).toEqual([6, 6])
    expect(runsOf([], 3)).toEqual([])
  })

  it('draws a PIPS tile as the fixture with pips, a PER_CELL tile per cell, HALVES as runs, a cell tile as its cell', () => {
    expect(expandTile(fixtureTile(barL), 'k')).toMatchObject([{ kind: 'fixture', pips: true, target: { type: 'fixture', key: 'bar-1' } }])
    expect(expandTile(fixtureTile(barL, { cellMode: 'WHOLE' }), 'k')).toMatchObject([{ kind: 'fixture', pips: false }])
    const perCell = expandTile(fixtureTile(barL, { cellMode: 'PER_CELL' }), 'k')
    expect(perCell).toHaveLength(12)
    expect(perCell[3]).toMatchObject({ kind: 'cell', name: 'Bar L · Pixel 4', target: { type: 'fixture', key: 'bar-1.pixel-3' } })
    const halves = expandTile(fixtureTile(barL, { cellMode: 'HALVES', cellSplit: 2 }), 'k')
    expect(halves.map((t) => t.name)).toEqual(['Bar L 1–6', 'Bar L 7–12'])
    expect(halves[1]).toMatchObject({ kind: 'run', targets: barCells('bar-1').slice(6).map((c) => ({ type: 'fixture', key: c.key })) })
    expect(expandTile(fixtureTile(barL, { elementKey: 'bar-1.pixel-2', cellMode: 'WHOLE' }), 'k')).toMatchObject([
      { kind: 'cell', name: 'Bar L · Pixel 3', target: { type: 'fixture', key: 'bar-1.pixel-2' } },
    ])
    // The desk names an element `<Fixture> Element N`; the tile does not say the fixture twice.
    const named: BuskRigPatch = { id: 14, key: 'bar-3', name: 'Bar C', elements: [{ key: 'bar-3.e0', name: 'Bar C Element 1' }, { key: 'bar-3.e1', name: 'Bar C Element 2' }] }
    expect(expandTile(fixtureTile(named, { cellMode: 'PER_CELL' }), 'k').map((t) => t.name)).toEqual(['Bar C Element 1', 'Bar C Element 2'])
    expect(expandTile(fixtureTile(named, { elementKey: 'bar-3.e1', cellMode: 'WHOLE' }), 'k')[0].name).toBe('Bar C Element 2')
    // A single-head fixture has no cells to split, whatever its stored mode says.
    expect(expandTile(fixtureTile(hex2, { cellMode: 'PER_CELL' }), 'k')).toMatchObject([{ kind: 'fixture', pips: false }])
  })
})

describe('a row’s layout — the bank’s two facts, on the row', () => {
  it('reads an absent flow as SCROLL and an absent or foreign width as 12 — a desk that predates the fields serves none', () => {
    const row = { name: 'Wash', tiles: [] }
    expect(rowFlow(row)).toBe('SCROLL')
    expect(rowWidth(row)).toBe(12)
    expect(rowFlow({ ...row, flow: 'WRAP' })).toBe('WRAP')
    expect(rowWidth({ ...row, width: 6 })).toBe(6)
    expect(rowWidth({ ...row, width: 5 })).toBe(12)
  })

  it('cuts the rows into the lines a twelve-track grid draws them on, a row starting a new line when it does not fit', () => {
    const rows = [
      { name: 'a', width: 6, tiles: [] },
      { name: 'b', width: 6, tiles: [] },
      { name: 'c', width: 8, tiles: [] },
      { name: 'd', width: 4, tiles: [] },
      { name: 'e', width: 9, tiles: [] },
      { name: 'f', tiles: [] },
    ]
    expect(rigLines(rows)).toEqual([[0, 1], [2, 3], [4], [5]])
    expect(rigLines([])).toEqual([])
  })

  it('sets a row’s flow and width, refuses a width the write would 400, and sends only what differs from the defaults', () => {
    const rig = sampleRig()
    const laid = setRowLayout(setRowLayout(rig, 0, { flow: 'WRAP' }), 0, { width: 6 })
    expect(rowFlow(laid.rows![0])).toBe('WRAP')
    expect(rowWidth(laid.rows![0])).toBe(6)
    expect(setRowLayout(rig, 0, { width: 5 })).toBe(rig)
    expect(setRowLayout(rig, 9, { width: 6 })).toBe(rig)
    const request = toRigRequest(laid, ids)
    expect(request.rows[0]).toMatchObject({ flow: 'WRAP', width: 6 })
    // A row at the defaults carries neither key: a desk that predates the two columns must go on
    // accepting a rig nobody has re-laid-out, and its Json refuses a key it does not know.
    expect('flow' in request.rows[1]).toBe(false)
    expect('width' in request.rows[1]).toBe(false)
  })
})

// ─── The show-all fallback, pinned against the desk ─────────────────────

/** The server fixture's shape, as much of it as this side reads. */
interface RigOrderFixture {
  fixtures: { key: string; type: string }[]
  groups: { name: string; members: string[] }[]
  rigs: Record<string, { name: string; tiles: { group?: string; fixture?: string; elementKey?: string; cellMode?: string; cellSplit?: number }[] }[]>
  cases: { rig: string; note: string; stepsHeads: CueTarget[][] }[]
}

const serverFixture = fixture as RigOrderFixture

function fixtureCells(key: string, type: string): BuskRigElement[] {
  return type.includes('pixel') ? barCells(key) : []
}

/** The fixture's rig as the GET would serve it: unresolvable tiles absent, as the server drops them. */
function rowsOf(name: string): BuskRigRow[] {
  const known = new Map(serverFixture.fixtures.map((f) => [f.key, f]))
  const groups = new Map(serverFixture.groups.map((g) => [g.name, g]))
  return serverFixture.rigs[name].map((r, i) => ({
    id: i,
    uuid: `r${i}`,
    name: r.name,
    tiles: r.tiles.flatMap((t, j): BuskRigTile[] => {
      if (t.group != null) {
        const g = groups.get(t.group)
        return g == null ? [] : [{ id: j, uuid: `t${i}.${j}`, kind: 'GROUP', group: { ...frontWash, name: g.name, memberCount: g.members.length }, cellMode: 'PIPS' }]
      }
      const f = known.get(t.fixture!)
      if (f == null) return []
      return [
        {
          id: j,
          uuid: `t${i}.${j}`,
          kind: 'FIXTURE',
          patch: { id: j, key: f.key, name: f.key, elements: fixtureCells(f.key, f.type) },
          elementKey: t.elementKey ?? null,
          cellMode: (t.cellMode ?? 'PIPS') as BuskRigTile['cellMode'],
          cellSplit: t.cellSplit ?? null,
        },
      ]
    }),
  }))
}

describe('the show-all fallback is the desk’s rig order', () => {
  it('draws every group then every fixture, in list order — the server fixture’s `empty` case', () => {
    const groups: GroupSummary[] = serverFixture.groups.map((g) => ({ ...frontWash, name: g.name, memberCount: g.members.length }))
    const fixtures = serverFixture.fixtures.map(
      (f) => ({ key: f.key, name: f.key, typeKey: f.type, elements: fixtureCells(f.key, f.type).map((c, index) => ({ index, key: c.key, displayName: c.name, properties: [] })) }) as unknown as Fixture,
    )
    const effective = effectiveRig({ rows: [] }, groups, fixtures)
    expect(effective.fallback).toBe(true)
    expect(effective.rows.map((r) => r.name)).toEqual(['Groups', 'Fixtures'])
    const expected = serverFixture.cases.find((c) => c.rig === 'empty')!.stepsHeads
    expect(rigSteps(effective.rows)).toEqual(expected)
  })

  it('leaves an infrastructure fixture out of the fallback, as the desk’s order does (`BuskRigOrderTest`)', () => {
    const groups: GroupSummary[] = serverFixture.groups.map((g) => ({ ...frontWash, name: g.name, memberCount: g.members.length }))
    const fixtures = serverFixture.fixtures.map(
      (f) =>
        ({
          key: f.key,
          name: f.key,
          typeKey: f.type,
          infrastructure: f.key === 'hex-2',
          elements: fixtureCells(f.key, f.type).map((c, index) => ({ index, key: c.key, displayName: c.name, properties: [] })),
        }) as unknown as Fixture,
    )
    const hex2 = [{ type: 'fixture', key: 'hex-2' }]
    const all = serverFixture.cases.find((c) => c.rig === 'empty')!.stepsHeads
    expect(all).toContainEqual(hex2)
    expect(rigSteps(effectiveRig({ rows: [] }, groups, fixtures).rows)).toEqual(
      all.filter((step) => JSON.stringify(step) !== JSON.stringify(hex2)),
    )
  })

  it('keeps a built rig as built', () => {
    const rig = sampleRig()
    expect(effectiveRig(rig, [frontWash], [])).toEqual({ rows: rig.rows, fallback: false })
    expect(effectiveRig(undefined, [], []).rows).toEqual([])
  })

  it('walks a built rig the way the desk does — the fixture’s `built`, `cellFirst` and `groupOnly` cases', () => {
    for (const name of ['built', 'cellFirst', 'groupOnly']) {
      const expected = serverFixture.cases.find((c) => c.rig === name)!.stepsHeads
      expect(rigSteps(rowsOf(name)), name).toEqual(expected)
    }
  })
})
