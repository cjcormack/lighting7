// @vitest-environment jsdom
import { act, fireEvent, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { COMMIT_INTERVAL_MS, useSheet } from './useSheet'
import type { SheetColumn, SheetRow } from './sheetModel'

/**
 * The sheet's live commit cadence through `useLivePush` (editor-kit plan D16): the throttle was
 * rewired onto the busk tabs' hook, and this pins that nothing about its timing moved — the first
 * commit goes at once, a second inside the floor waits and lands as the trailing call, a repeat is
 * sent rather than deduped, and the two rules the hook has no notion of (another cell's commit
 * lands the pending one first, an unmount lands it too) still hold. The floor is asserted as the literal 33 rather than through
 * the constant, so a change to the number is a change to this file.
 */

interface Row extends SheetRow {
  id: string
  level: number
}

const rows: Row[] = [
  { id: 'a', level: 0 },
  { id: 'b', level: 0 },
]

beforeEach(() => {
  vi.useFakeTimers()
})
afterEach(() => {
  vi.useRealTimers()
})

function harness() {
  const write = vi.fn<(rows: readonly Row[], value: unknown) => boolean>(() => true)
  const columns: SheetColumn<Row, 'level'>[] = [
    {
      key: 'level',
      label: 'Level',
      kind: 'level',
      width: '64px',
      value: (row) => row.level,
      cell: () => null,
      write,
    },
  ]
  const hook = renderHook(() =>
    useSheet<Row, 'level'>({
      rows,
      columns,
      permission: { entry: true, clear: true },
      copy: () => ({ setTitle: 'Set', clearTitle: 'Clear' }),
    }),
  )
  return { write, ...hook }
}

describe('useSheet commit cadence', () => {
  it('is ~30 Hz: the floor between two sends is 33 ms', () => {
    expect(COMMIT_INTERVAL_MS).toBe(33)
  })

  it('sends the first commit at once and holds the next until the floor lifts, then sends the latest', () => {
    const { write, result } = harness()
    const commit = result.current.tableProps.onCellCommit
    act(() => commit(rows[0], 'level', 10))
    expect(write).toHaveBeenCalledTimes(1)
    expect(write).toHaveBeenLastCalledWith([rows[0]], 10)
    act(() => commit(rows[0], 'level', 20))
    act(() => commit(rows[0], 'level', 30))
    expect(write).toHaveBeenCalledTimes(1)
    act(() => vi.advanceTimersByTime(32))
    expect(write).toHaveBeenCalledTimes(1)
    act(() => vi.advanceTimersByTime(1))
    // The trailing call: the latest value, not the first held one.
    expect(write).toHaveBeenCalledTimes(2)
    expect(write).toHaveBeenLastCalledWith([rows[0]], 30)
  })

  it('sends at once again once the floor has lapsed with nothing pending', () => {
    const { write, result } = harness()
    const commit = result.current.tableProps.onCellCommit
    act(() => commit(rows[0], 'level', 10))
    act(() => vi.advanceTimersByTime(40))
    act(() => commit(rows[0], 'level', 20))
    expect(write).toHaveBeenCalledTimes(2)
    expect(write).toHaveBeenLastCalledWith([rows[0]], 20)
  })

  it('sends a commit that repeats the last one — the sheet does not dedupe, since ⌫, Spread or the wire may have moved the value since', () => {
    const { write, result } = harness()
    const commit = result.current.tableProps.onCellCommit
    act(() => commit(rows[0], 'level', 10))
    act(() => vi.advanceTimersByTime(40))
    act(() => commit(rows[0], 'level', 10))
    expect(write).toHaveBeenCalledTimes(2)
    // And a held repeat lands too.
    act(() => commit(rows[0], 'level', 10))
    act(() => vi.advanceTimersByTime(33))
    expect(write).toHaveBeenCalledTimes(3)
  })

  it('never re-sends a commit the hook has already sent — not for another cell, not on unmount', () => {
    const { write, result, unmount } = harness()
    const commit = result.current.tableProps.onCellCommit
    act(() => commit(rows[0], 'level', 10))
    expect(write).toHaveBeenCalledTimes(1)
    // Nothing pending: another cell's commit flushes nothing, and lands itself at once since the
    // floor is measured from the last send and this is a fresh one after 40 ms.
    act(() => vi.advanceTimersByTime(40))
    act(() => commit(rows[1], 'level', 5))
    expect(write).toHaveBeenCalledTimes(2)
    act(() => vi.advanceTimersByTime(40))
    unmount()
    expect(write).toHaveBeenCalledTimes(2)
  })

  it('hands the table one commit function for the life of the sheet', () => {
    const { result, rerender } = harness()
    const first = result.current.tableProps.onCellCommit
    rerender()
    expect(result.current.tableProps.onCellCommit).toBe(first)
  })

  it('lands a pending commit at once when another cell commits, and holds the newcomer to the floor', () => {
    const { write, result } = harness()
    const commit = result.current.tableProps.onCellCommit
    act(() => commit(rows[0], 'level', 10))
    act(() => commit(rows[0], 'level', 20))
    expect(write).toHaveBeenCalledTimes(1)
    act(() => commit(rows[1], 'level', 5))
    // The first cell's held value went out at once; the second cell's waits for the floor, as it
    // always did — two cells' values are two writes, and neither is dropped.
    expect(write.mock.calls.map(([batch, value]) => [batch[0].id, value])).toEqual([
      ['a', 10],
      ['a', 20],
    ])
    act(() => vi.advanceTimersByTime(33))
    expect(write.mock.calls.map(([batch, value]) => [batch[0].id, value])).toEqual([
      ['a', 10],
      ['a', 20],
      ['b', 5],
    ])
  })

  it('lands a pending commit on unmount rather than dropping it', () => {
    const { write, result, unmount } = harness()
    const commit = result.current.tableProps.onCellCommit
    act(() => commit(rows[0], 'level', 10))
    act(() => commit(rows[0], 'level', 20))
    expect(write).toHaveBeenCalledTimes(1)
    unmount()
    expect(write).toHaveBeenCalledTimes(2)
    expect(write).toHaveBeenLastCalledWith([rows[0]], 20)
    // And nothing lands later from a timer left armed.
    act(() => vi.advanceTimersByTime(100))
    expect(write).toHaveBeenCalledTimes(2)
  })
})

/**
 * The row keys every sheet with a row axis answers (CLAUDE.md §Sheet kit): ⌘A, and ↑ / ↓ with
 * Shift extending — the programmer's keys, stepped by the same `arrowStepTarget`. They are heard on
 * the window in the bubble phase, so a key a control has already claimed is left alone.
 */
describe('useSheet row keys', () => {
  interface KeyRow extends SheetRow {
    id: string
  }
  const keyRows: KeyRow[] = [
    { id: 'a' },
    { id: 'div', divider: 'Section' },
    { id: 'b' },
    { id: 'c' },
    { id: 'd' },
  ]
  const keyColumns: SheetColumn<KeyRow, 'v'>[] = [
    { key: 'v', label: 'V', kind: 'v', width: '64px', value: () => 0, cell: () => null, write: () => true },
  ]

  function sheet(selectsRows?: boolean) {
    return renderHook(() =>
      useSheet<KeyRow, 'v'>({
        rows: keyRows,
        columns: keyColumns,
        permission: { entry: true, clear: true },
        copy: () => ({ setTitle: 'Set', clearTitle: 'Clear' }),
        selectsRows,
      }),
    )
  }
  const press = (key: string, init: KeyboardEventInit = {}, target: Element = document.body) =>
    act(() => {
      fireEvent.keyDown(target, { key, ...init })
    })
  const selected = (r: { current: ReturnType<typeof useSheet<KeyRow, 'v'>> }) => [...r.current.rowSelection.orderedSelected]

  it('steps the row selection with ↓ and ↑, skipping dividers and clamping at the ends', () => {
    const { result } = sheet()
    press('ArrowDown')
    expect(selected(result)).toEqual(['a'])
    press('ArrowDown')
    expect(selected(result)).toEqual(['b'])
    press('ArrowUp')
    press('ArrowUp')
    expect(selected(result)).toEqual(['a'])
  })

  it('lands ↑ on the last row with nothing selected, and scrolls the row it selects into view', () => {
    const { result } = sheet()
    press('ArrowUp')
    expect(selected(result)).toEqual(['d'])
    expect(result.current.tableProps.scrollToRowId).toBe('d')
  })

  it('extends with Shift, upward past two rows', () => {
    const { result } = sheet()
    act(() => result.current.selectRow('d'))
    press('ArrowUp', { shiftKey: true })
    press('ArrowUp', { shiftKey: true })
    expect(selected(result)).toEqual(['b', 'c', 'd'])
  })

  it('selects every row with ⌘A, dropping a cell marquee', () => {
    const { result } = sheet()
    act(() => result.current.tableProps.cellSelection.select([{ rowId: 'b', col: 'v' }], 'replace'))
    expect(result.current.cellCount).toBe(1)
    press('a', { metaKey: true })
    expect(result.current.cellCount).toBe(0)
    expect(selected(result)).toEqual(['a', 'b', 'c', 'd'])
  })

  it('leaves a key a control has already claimed, one from either dialog role, and ⌥ / ⌘ / Ctrl arrows alone', () => {
    const { result } = sheet()
    const menu = document.createElement('div')
    menu.setAttribute('role', 'menu')
    menu.tabIndex = 0
    menu.addEventListener('keydown', (e) => e.preventDefault())
    const dialog = document.createElement('div')
    dialog.setAttribute('role', 'dialog')
    const inDialog = document.createElement('button')
    dialog.appendChild(inDialog)
    // Radix's AlertDialog — the batch delete's confirm — is `alertdialog`, not `dialog`.
    const alert = document.createElement('div')
    alert.setAttribute('role', 'alertdialog')
    const inAlert = document.createElement('button')
    alert.appendChild(inAlert)
    document.body.append(menu, dialog, alert)
    try {
      press('ArrowDown', {}, menu)
      press('ArrowDown', {}, inDialog)
      press('ArrowDown', {}, inAlert)
      press('a', { metaKey: true }, inAlert)
      press('ArrowDown', { altKey: true })
      press('ArrowDown', { metaKey: true })
      press('ArrowDown', { ctrlKey: true })
      expect(result.current.rowSelection.count).toBe(0)
    } finally {
      menu.remove()
      dialog.remove()
      alert.remove()
    }
  })

  it('stands aside from a plain focused control outside the rows, and not from one inside a row', () => {
    const { result } = sheet()
    // A partition chip or a row verb: a plain button that claims no key of its own.
    const chip = document.createElement('button')
    // A row's rename button or cell trigger, which a click inside the row leaves focused.
    const row = document.createElement('div')
    row.setAttribute('data-row-id', 'a')
    const nameButton = document.createElement('button')
    row.appendChild(nameButton)
    document.body.append(chip, row)
    try {
      press('ArrowDown', {}, chip)
      press('a', { metaKey: true }, chip)
      expect(result.current.rowSelection.count).toBe(0)
      press('ArrowDown', {}, nameButton)
      expect(selected(result)).toEqual(['a'])
      press('ArrowDown', {}, nameButton)
      expect(selected(result)).toEqual(['b'])
    } finally {
      chip.remove()
      row.remove()
    }
  })

  it('hears no row key on a sheet with no row axis', () => {
    const { result } = sheet(false)
    press('ArrowDown')
    press('a', { metaKey: true })
    expect(result.current.rowSelection.count).toBe(0)
  })
})

/**
 * The arrows over cells (CLAUDE.md §Sheet kit): the rule is `cellArrowStep`, pinned on its own in
 * `cellSelectionModel.test.ts`; these pin the wiring — when the arrows are the cells' and when the
 * rows', the cursor a pointer selection implies, the reveal request, and the guards.
 */
describe('useSheet cell keys', () => {
  interface KeyRow extends SheetRow {
    id: string
  }
  type Col = 'x' | 'ro' | 'y'
  const keyRows: KeyRow[] = [{ id: 'a' }, { id: 'div', divider: 'Section' }, { id: 'b' }, { id: 'c' }]
  const cellColumn = (key: Col): SheetColumn<KeyRow, Col> => ({
    key,
    label: key,
    kind: 'v',
    width: '64px',
    value: () => 0,
    cell: () => null,
    write: () => true,
  })
  // A read-out between the two: no `cell`, so no marquee reaches it and no arrow does either.
  const keyColumns: SheetColumn<KeyRow, Col>[] = [
    cellColumn('x'),
    { key: 'ro', label: 'RO', kind: 'ro', width: '64px', value: () => 0, display: () => null },
    cellColumn('y'),
  ]
  function sheet(options: { selectsRows?: boolean; cellFlow?: 'grid' | 'linear' } = {}) {
    return renderHook(() =>
      useSheet<KeyRow, Col>({
        rows: keyRows,
        columns: keyColumns,
        permission: { entry: true, clear: true },
        copy: () => ({ setTitle: 'Set', clearTitle: 'Clear' }),
        ...options,
      }),
    )
  }
  const press = (key: string, init: KeyboardEventInit = {}, target: Element = document.body) =>
    act(() => {
      fireEvent.keyDown(target, { key, ...init })
    })
  type Result = { current: ReturnType<typeof useSheet<KeyRow, Col>> }
  const cells = (r: Result) => r.current.cellSelection.cells.map((c) => `${c.rowId}·${c.col}`).sort()

  it('moves a selected cell with every arrow, skipping dividers and read-out columns', () => {
    const { result } = sheet()
    act(() => result.current.tableProps.cellSelection.select([{ rowId: 'a', col: 'x' }], 'replace'))
    press('ArrowRight')
    expect(cells(result)).toEqual(['a·y'])
    press('ArrowDown')
    expect(cells(result)).toEqual(['b·y'])
    expect(result.current.tableProps.revealCell).toEqual({ rowId: 'b', col: 'y' })
    press('ArrowLeft')
    press('ArrowUp')
    expect(cells(result)).toEqual(['a·x'])
    expect(result.current.rowSelection.count).toBe(0)
  })

  it('extends a rectangle with Shift and shrinks it back', () => {
    const { result } = sheet()
    act(() => result.current.tableProps.cellSelection.select([{ rowId: 'a', col: 'x' }], 'replace'))
    press('ArrowDown', { shiftKey: true })
    press('ArrowRight', { shiftKey: true })
    expect(cells(result)).toEqual(['a·x', 'a·y', 'b·x', 'b·y'])
    press('ArrowUp', { shiftKey: true })
    expect(cells(result)).toEqual(['a·x', 'a·y'])
  })

  it('anchors after a marquee at its top-left, so a plain arrow steps from there', () => {
    const { result } = sheet()
    act(() =>
      result.current.tableProps.cellSelection.select(
        [
          { rowId: 'c', col: 'y' },
          { rowId: 'b', col: 'x' },
          { rowId: 'b', col: 'y' },
          { rowId: 'c', col: 'x' },
        ],
        'replace',
      ),
    )
    press('ArrowRight')
    expect(cells(result)).toEqual(['b·y'])
  })

  it('leaves ↑ / ↓ to the rows while no cell is selected, and ← / → to nobody', () => {
    const { result } = sheet()
    press('ArrowRight')
    expect(result.current.cellCount).toBe(0)
    expect(result.current.rowSelection.count).toBe(0)
    press('ArrowDown')
    expect(result.current.cellCount).toBe(0)
    expect([...result.current.rowSelection.orderedSelected]).toEqual(['a'])
  })

  it('walks a linear sheet in reading order, wrapping at the end of a row', () => {
    const { result } = sheet({ selectsRows: false, cellFlow: 'linear' })
    press('ArrowRight')
    expect(cells(result)).toEqual(['a·x'])
    press('ArrowRight')
    press('ArrowRight')
    expect(cells(result)).toEqual(['b·x'])
    // Shift does not wrap: it grows a rectangle, and b·x is already at the row's start.
    press('ArrowLeft', { shiftKey: true })
    expect(cells(result)).toEqual(['b·x'])
    press('ArrowUp', { shiftKey: true })
    press('ArrowRight', { shiftKey: true })
    expect(cells(result)).toEqual(['a·x', 'a·y', 'b·x', 'b·y'])
  })

  it('selects the last cell on ↑ with nothing selected on a sheet with no rows, and every cell with ⌘A', () => {
    const { result } = sheet({ selectsRows: false })
    press('ArrowUp')
    expect(cells(result)).toEqual(['c·y'])
    press('a', { metaKey: true })
    expect(cells(result)).toEqual(['a·x', 'a·y', 'b·x', 'b·y', 'c·x', 'c·y'])
    // …and ⌘A then Shift+← moves the head ⌘A leaves on the last cell, so the rectangle loses a
    // column.
    press('ArrowLeft', { shiftKey: true })
    expect(cells(result)).toEqual(['a·x', 'b·x', 'c·x'])
  })

  it('steps past a cell whose row has nothing to set, which the table draws blank', () => {
    // `y` is blank on `b` — an effect template's Fade, a snap cue's Curve.
    const blankColumns: SheetColumn<KeyRow, Col>[] = [
      cellColumn('x'),
      { ...cellColumn('y'), value: (row) => (row.id === 'b' ? undefined : 0) },
    ]
    const { result } = renderHook(() =>
      useSheet<KeyRow, Col>({
        rows: keyRows,
        columns: blankColumns,
        permission: { entry: true, clear: true },
        copy: () => ({ setTitle: 'Set', clearTitle: 'Clear' }),
      }),
    )
    act(() => result.current.tableProps.cellSelection.select([{ rowId: 'a', col: 'y' }], 'replace'))
    press('ArrowDown')
    expect(cells(result)).toEqual(['c·y'])
    press('ArrowUp')
    press('ArrowLeft')
    press('ArrowDown')
    expect(cells(result)).toEqual(['b·x'])
    press('ArrowRight')
    expect(cells(result)).toEqual(['b·x'])
  })

  it('keeps its state when an arrow held against an edge changes nothing', () => {
    const { result } = sheet()
    act(() => result.current.tableProps.cellSelection.select([{ rowId: 'a', col: 'x' }], 'replace'))
    press('ArrowLeft')
    const before = result.current.cellSelection
    press('ArrowLeft')
    press('ArrowUp')
    expect(result.current.cellSelection).toBe(before)
  })

  it('re-derives the anchor after a pointer reselects the cells a Shift-arrow session left', () => {
    const { result } = sheet()
    act(() => result.current.tableProps.cellSelection.select([{ rowId: 'b', col: 'y' }], 'replace'))
    press('ArrowUp', { shiftKey: true })
    press('ArrowLeft', { shiftKey: true })
    // Anchor b·y, head a·x. A marquee over the same four cells is a fresh selection, anchored at
    // its top-left — so a plain → steps from a·x, not from the old anchor.
    act(() =>
      result.current.tableProps.cellSelection.select(
        [
          { rowId: 'a', col: 'x' },
          { rowId: 'a', col: 'y' },
          { rowId: 'b', col: 'x' },
          { rowId: 'b', col: 'y' },
        ],
        'replace',
      ),
    )
    press('ArrowRight')
    expect(cells(result)).toEqual(['a·y'])
  })

  it('keeps the guards: claimed keys, dialogs, foreign controls, an open editor and modified arrows', () => {
    const { result } = sheet({ selectsRows: false })
    const claimed = document.createElement('div')
    claimed.tabIndex = 0
    claimed.addEventListener('keydown', (e) => e.preventDefault())
    const alert = document.createElement('div')
    alert.setAttribute('role', 'alertdialog')
    const inAlert = document.createElement('button')
    alert.appendChild(inAlert)
    const chip = document.createElement('button')
    const editor = document.createElement('div')
    editor.setAttribute('data-cell-editor-surface', '')
    document.body.append(claimed, alert, chip)
    try {
      press('ArrowDown', {}, claimed)
      press('ArrowDown', {}, inAlert)
      press('a', { metaKey: true }, inAlert)
      press('ArrowDown', {}, chip)
      press('ArrowDown', { altKey: true })
      press('ArrowDown', { metaKey: true })
      press('ArrowDown', { ctrlKey: true })
      expect(result.current.cellCount).toBe(0)
      document.body.append(editor)
      press('ArrowDown')
      expect(result.current.cellCount).toBe(0)
    } finally {
      claimed.remove()
      alert.remove()
      chip.remove()
      editor.remove()
    }
  })
})
