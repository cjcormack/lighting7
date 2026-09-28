import { describe, expect, it } from 'vitest'
import {
  applyCellSelection,
  cellArrowStep,
  cellKey,
  cursorOfSelection,
  cellsByColumn,
  describeCellScope,
  parseCellKey,
  visibleCells,
} from './cellSelectionModel'
import type { ColumnKey } from '../fixtures-list/columns'
import type { CellArrow, CellCursor, CellGrid, CellRef, RowId } from './cellSelectionModel'

const rid = (s: string) => s as RowId
const col = (s: string) => s as ColumnKey
const ref = (r: string, c: string) => ({ rowId: rid(r), col: col(c) })

describe('cell keys', () => {
  it('round-trip a row id containing separators an operator could type', () => {
    // Group names are operator-typed: `group:Stage Left | Warm: 2` is a legal row id, so the key
    // separator has to be something that cannot occur in one.
    const rowId = rid('group:Stage Left | Warm: 2')
    expect(parseCellKey(cellKey(rowId, col('colour')))).toEqual({ rowId, col: 'colour' })
  })
})

describe('applyCellSelection', () => {
  const start = new Set([cellKey(rid('a'), col('dimmer'))])

  it('replaces on a plain drag', () => {
    const next = applyCellSelection(start, [ref('b', 'colour')], 'replace')
    expect([...next]).toEqual([cellKey(rid('b'), col('colour'))])
  })

  it('accumulates a second block on toggle or range-add', () => {
    for (const intent of ['toggle', 'range-add'] as const) {
      const next = applyCellSelection(start, [ref('b', 'colour')], intent)
      expect(next.size).toBe(2)
    }
  })

  it('treats range as replace — the rectangle IS the range', () => {
    expect(applyCellSelection(start, [ref('b', 'colour')], 'range').size).toBe(1)
  })
})

describe('cellsByColumn', () => {
  it('groups so each column becomes exactly one batch write', () => {
    // `planBatchWrites` takes ONE column plus targets, so a multi-column marquee has to be split
    // this way to keep per-target clamping and parent-first precedence intact.
    const selected = new Set([
      cellKey(rid('a'), col('colour')),
      cellKey(rid('b'), col('colour')),
      cellKey(rid('a'), col('dimmer')),
    ])
    const grouped = cellsByColumn(selected).sort((x, y) => x.col.localeCompare(y.col))
    expect(grouped).toEqual([
      { col: 'colour', rowIds: ['a', 'b'] },
      { col: 'dimmer', rowIds: ['a'] },
    ])
  })
})

describe('visibleCells', () => {
  it('drops cells whose row has been filtered away', () => {
    // Same rule row selection follows: a hidden row must not silently contribute to a write.
    const selected = new Set([cellKey(rid('a'), col('colour')), cellKey(rid('gone'), col('colour'))])
    expect(visibleCells(selected, new Set(['a']))).toEqual([ref('a', 'colour')])
  })

  it('leaves the stored state alone, so unhiding the row brings its cells back', () => {
    const selected = new Set([cellKey(rid('a'), col('colour'))])
    visibleCells(selected, new Set())
    expect(selected.size).toBe(1)
  })
})

describe('describeCellScope', () => {
  it('counts rows and names each distinct column once', () => {
    const cells = [ref('a', 'colour'), ref('b', 'colour'), ref('a', 'position')]
    expect(describeCellScope(cells, (c) => c)).toBe('2 × colour, position')
  })

  it('is empty for an empty selection', () => {
    expect(describeCellScope([], (c) => c)).toBe('')
  })
})

/**
 * The arrow keys' rule over cells (CLAUDE.md §Sheet kit). The DMX grids are built the way the
 * sheet builds them — rows of `width` addresses from `row:1`, columns `c0…c{width-1}` — so the
 * reading order is address order and a cell can be named by its address.
 */
describe('cellArrowStep', () => {
  function dmx(width: number): CellGrid {
    return {
      rows: Array.from({ length: 512 / width }, (_, r) => `row:${r * width + 1}`),
      cols: Array.from({ length: width }, (_, c) => `c${c}`),
    }
  }
  const at = (width: number, address: number): CellRef => ({
    rowId: `row:${Math.floor((address - 1) / width) * width + 1}`,
    col: `c${(address - 1) % width}`,
  })
  const addressOf = (width: number, cell: CellRef): number =>
    Number(cell.rowId.slice(4)) + Number(cell.col.slice(1))
  const cursorAt = (width: number, anchor: number, head = anchor): CellCursor => ({
    anchor: at(width, anchor),
    head: at(width, head),
  })
  /** Step from a cursor and answer the addresses selected. */
  function step(
    width: number,
    cursor: CellCursor | null,
    direction: CellArrow,
    extend = false,
  ): { anchor: number; head: number; addresses: number[] } {
    const next = cellArrowStep(dmx(width), 'linear', cursor, direction, extend)
    if (next == null) throw new Error('no step')
    return {
      anchor: addressOf(width, next.cursor.anchor),
      head: addressOf(width, next.cursor.head),
      addresses: next.cells.map((cell) => addressOf(width, cell)).sort((a, b) => a - b),
    }
  }

  it('selects the first address on ↓ or → and the last on ↑ or ← with nothing selected, Shift or not', () => {
    for (const width of [16, 8, 4]) {
      expect(step(width, null, 'down').addresses).toEqual([1])
      expect(step(width, null, 'right').addresses).toEqual([1])
      expect(step(width, null, 'up').addresses).toEqual([512])
      expect(step(width, null, 'left', true).addresses).toEqual([512])
    }
  })

  it('moves ← / → one address, wrapping across rows, at every row width', () => {
    expect(step(16, cursorAt(16, 16), 'right').addresses).toEqual([17])
    expect(step(16, cursorAt(16, 17), 'left').addresses).toEqual([16])
    expect(step(8, cursorAt(8, 8), 'right').addresses).toEqual([9])
    expect(step(8, cursorAt(8, 9), 'left').addresses).toEqual([8])
    expect(step(4, cursorAt(4, 4), 'right').addresses).toEqual([5])
    expect(step(4, cursorAt(4, 5), 'left').addresses).toEqual([4])
  })

  it('moves ↑ / ↓ by the row width in whichever layout is showing', () => {
    expect(step(16, cursorAt(16, 5), 'down').addresses).toEqual([21])
    expect(step(8, cursorAt(8, 5), 'down').addresses).toEqual([13])
    expect(step(4, cursorAt(4, 5), 'down').addresses).toEqual([9])
    expect(step(16, cursorAt(16, 21), 'up').addresses).toEqual([5])
  })

  it('stops at 1 and 512 on every arrow', () => {
    for (const width of [16, 8, 4]) {
      expect(step(width, cursorAt(width, 1), 'left').addresses).toEqual([1])
      expect(step(width, cursorAt(width, 1), 'up').addresses).toEqual([1])
      expect(step(width, cursorAt(width, 512), 'right').addresses).toEqual([512])
      expect(step(width, cursorAt(width, 512), 'down').addresses).toEqual([512])
      // ↓ on the last row stays put rather than jumping sideways to 512.
      expect(step(width, cursorAt(width, 512 - width + 2), 'down').addresses).toEqual([512 - width + 2])
    }
  })

  it('extends a rectangle of addresses with Shift, as on every sheet, and shrinks it back', () => {
    // The desk's gesture: Shift+↓ ×4 then Shift+→ ×3 from 005 at sixteen wide is five rows by four
    // columns — 005–008, 021–024, … 069–072 — not every address from 005 to 072.
    let cursor: CellCursor | null = cursorAt(16, 5)
    let s = step(16, cursor, 'down', true)
    expect(s).toMatchObject({ anchor: 5, head: 21, addresses: [5, 21] })
    for (const direction of ['down', 'down', 'down', 'down', 'right', 'right', 'right'] as const) {
      const moved: ReturnType<typeof cellArrowStep<string>> = cellArrowStep(dmx(16), 'linear', cursor, direction, true)
      cursor = moved?.cursor ?? null
    }
    expect(cursor).toEqual({ anchor: at(16, 5), head: at(16, 72) })
    // The last Shift+→ of the gesture, from 071: 005–008 down to 069–072, twenty addresses.
    const block = cellArrowStep(dmx(16), 'linear', { anchor: at(16, 5), head: at(16, 71) }, 'right', true)
    expect(block?.cells.map((cell) => addressOf(16, cell)).sort((a, b) => a - b)).toEqual(
      [5, 21, 37, 53, 69].flatMap((base) => [base, base + 1, base + 2, base + 3]),
    )
    // One Shift+↑ back: four rows by four columns.
    s = step(16, cursor, 'up', true)
    expect(s.addresses).toEqual([5, 6, 7, 8, 21, 22, 23, 24, 37, 38, 39, 40, 53, 54, 55, 56])
    const full = cellArrowStep(dmx(16), 'linear', cursor, 'right', true)
    expect(full?.cells).toHaveLength(5 * 5)
    // Shift back the other way shrinks towards the anchor, then grows past it.
    s = step(16, cursorAt(16, 5, 6), 'left', true)
    expect(s).toMatchObject({ anchor: 5, head: 5, addresses: [5] })
    s = step(16, cursorAt(16, 5, 5), 'left', true)
    expect(s).toMatchObject({ anchor: 5, head: 4, addresses: [4, 5] })
    s = step(8, cursorAt(8, 20), 'up', true)
    expect(s.addresses).toEqual([12, 20])
  })

  it('does not wrap a Shift step, though a plain one does', () => {
    // 016 is the end of its row at sixteen wide: a plain → goes on to 017, Shift+→ stays.
    expect(step(16, cursorAt(16, 16), 'right').addresses).toEqual([17])
    expect(step(16, cursorAt(16, 16), 'right', true)).toMatchObject({ anchor: 16, head: 16, addresses: [16] })
    expect(step(8, cursorAt(8, 9), 'left', true)).toMatchObject({ anchor: 9, head: 9, addresses: [9] })
  })

  it('steps a plain arrow from the anchor, collapsing an extended selection', () => {
    expect(step(16, cursorAt(16, 5, 40), 'right')).toMatchObject({ anchor: 6, head: 6, addresses: [6] })
  })

  it('walks a grid as a spreadsheet: no wrap, a rectangle from the anchor, clamped at every edge', () => {
    const grid: CellGrid<'a' | 'b' | 'c'> = { rows: ['r1', 'r2', 'r3'], cols: ['a', 'b', 'c'] }
    const cell = (rowId: string, col: 'a' | 'b' | 'c') => ({ rowId, col })
    const one = (rowId: string, col: 'a' | 'b' | 'c') => ({ anchor: cell(rowId, col), head: cell(rowId, col) })
    expect(cellArrowStep(grid, 'grid', one('r1', 'c'), 'right', false)?.cells).toEqual([cell('r1', 'c')])
    expect(cellArrowStep(grid, 'grid', one('r2', 'a'), 'left', false)?.cells).toEqual([cell('r2', 'a')])
    expect(cellArrowStep(grid, 'grid', one('r1', 'b'), 'up', false)?.cells).toEqual([cell('r1', 'b')])
    expect(cellArrowStep(grid, 'grid', one('r3', 'b'), 'down', false)?.cells).toEqual([cell('r3', 'b')])
    expect(cellArrowStep(grid, 'grid', one('r1', 'a'), 'down', false)?.cells).toEqual([cell('r2', 'a')])
    // Shift from r2·b: down then right is the 2×2 rectangle below and to the right.
    const down = cellArrowStep(grid, 'grid', one('r2', 'b'), 'down', true)
    const rect = cellArrowStep(grid, 'grid', down?.cursor ?? null, 'right', true)
    expect(rect?.cells).toEqual([cell('r2', 'b'), cell('r2', 'c'), cell('r3', 'b'), cell('r3', 'c')])
    // …and back up past the anchor flips it above.
    const up1 = cellArrowStep(grid, 'grid', rect?.cursor ?? null, 'up', true)
    const up2 = cellArrowStep(grid, 'grid', up1?.cursor ?? null, 'up', true)
    expect(up2?.cursor).toEqual({ anchor: cell('r2', 'b'), head: cell('r1', 'c') })
    expect(up2?.cells).toEqual([cell('r1', 'b'), cell('r1', 'c'), cell('r2', 'b'), cell('r2', 'c')])
  })

  it('steps past a cell the row does not take, in every direction, and stays put with none before the edge', () => {
    // r2 has nothing in b — a dimmer-only par's Colour, an effect template's Fade.
    const grid: CellGrid<'a' | 'b' | 'c'> = {
      rows: ['r1', 'r2', 'r3'],
      cols: ['a', 'b', 'c'],
      takes: (rowId, col) => !(rowId === 'r2' && col === 'b') && !(rowId === 'r3' && col === 'b'),
    }
    const one = (rowId: string, col: 'a' | 'b' | 'c') => ({ anchor: { rowId, col }, head: { rowId, col } })
    expect(cellArrowStep(grid, 'grid', one('r2', 'a'), 'right', false)?.cells).toEqual([{ rowId: 'r2', col: 'c' }])
    expect(cellArrowStep(grid, 'grid', one('r2', 'c'), 'left', false)?.cells).toEqual([{ rowId: 'r2', col: 'a' }])
    // ↓ in b from r1: r2 and r3 both skip, so there is nothing below and the arrow stays.
    expect(cellArrowStep(grid, 'grid', one('r1', 'b'), 'down', false)?.cells).toEqual([{ rowId: 'r1', col: 'b' }])
    // A Shift rectangle still covers the blanks between its corners, as the marquee's does.
    expect(cellArrowStep(grid, 'grid', one('r1', 'a'), 'right', true)?.cells).toEqual([
      { rowId: 'r1', col: 'a' },
      { rowId: 'r1', col: 'b' },
    ])
    // With nothing selected, the first and last cells that take.
    const firstBlank: CellGrid<'a' | 'b'> = { rows: ['r1', 'r2'], cols: ['a', 'b'], takes: (r, c) => !(r === 'r1' && c === 'a') && !(r === 'r2' && c === 'b') }
    expect(cellArrowStep(firstBlank, 'grid', null, 'down', false)?.cells).toEqual([{ rowId: 'r1', col: 'b' }])
    expect(cellArrowStep(firstBlank, 'grid', null, 'up', false)?.cells).toEqual([{ rowId: 'r2', col: 'a' }])
    expect(cellArrowStep({ rows: ['r1'], cols: ['a'], takes: () => false }, 'grid', null, 'down', false)).toBeNull()
  })

  it('answers null for an empty grid and treats a cursor off the grid as none', () => {
    expect(cellArrowStep({ rows: [], cols: ['a'] }, 'grid', null, 'down', false)).toBeNull()
    expect(cellArrowStep({ rows: ['r1'], cols: [] }, 'linear', null, 'down', false)).toBeNull()
    const gone = { anchor: { rowId: 'gone', col: 'a' }, head: { rowId: 'gone', col: 'a' } }
    expect(cellArrowStep({ rows: ['r1', 'r2'], cols: ['a'] }, 'grid', gone, 'up', false)?.cells).toEqual([
      { rowId: 'r2', col: 'a' },
    ])
  })
})

describe('cursorOfSelection', () => {
  it('anchors a pointer selection at its first cell in reading order and heads it at its last', () => {
    expect(cursorOfSelection([])).toBeNull()
    const cells = [
      { rowId: 'r1', col: 'a' },
      { rowId: 'r2', col: 'b' },
    ]
    expect(cursorOfSelection(cells)).toEqual({ anchor: cells[0], head: cells[1] })
  })
})
