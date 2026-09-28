// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, renderHook, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/hooks/useMediaQuery', () => ({ useMediaQuery: () => false }))
vi.mock('@tanstack/react-virtual', () => ({
  useVirtualizer: ({ count, estimateSize }: { count: number; estimateSize: () => number }) => ({
    getTotalSize: () => count * estimateSize(),
    getVirtualItems: () =>
      Array.from({ length: count }, (_, index) => ({ index, key: index, start: index * estimateSize(), size: estimateSize() })),
    scrollToIndex: () => {},
  }),
}))

import { commitToSelectedCells, listNames, skippedNote, splitBatch, takesValue, type SheetColumn, type SheetRow } from './sheetModel'
import { groupRows } from './groupRows'
import { libraryPermission } from './libraryScope'
import { sheetWriteFailureMessage } from './reportSheetWriteFailure'
import { useSheet } from './useSheet'
import { SheetTable } from './SheetTable'
import { libraryNameColumn } from './LibraryNameColumn'
import { resetEditorSurfaceMedia } from '../editor/EditorSurface'

interface Row extends SheetRow {
  name: string
  /** Undefined: nothing to set in the Level column — a follower's BPM, a snap cue's Curve. */
  level?: number
}

const ROWS: Row[] = [
  { id: 'a', name: 'M1', level: 10 },
  { id: 'b', name: 'M2' },
  { id: 'c', name: 'M3', level: 30 },
  { id: 'd', name: 'M4' },
]

function levelColumn(write = vi.fn<(rows: readonly Row[], value: unknown) => boolean>(() => true)) {
  const column: SheetColumn<Row, 'level'> = {
    key: 'level',
    label: 'Level',
    kind: 'level',
    width: '64px',
    value: (row) => row.level,
    cell: () => null,
    write,
    clear: vi.fn(),
    spread: (rows) => ({ kind: 'raw', col: 'level', label: 'Level', count: rows.length } as never),
  }
  return column
}

describe('the skipped-rows rule (library-sheets plan D12)', () => {
  it('a row whose value is undefined does not take the column’s value; a divider never does', () => {
    const column = levelColumn()
    expect(takesValue(column, ROWS[0])).toBe(true)
    expect(takesValue(column, ROWS[1])).toBe(false)
    expect(takesValue(column, { id: 'x', name: 'x', level: 1, divider: 'Group' })).toBe(false)
    expect(splitBatch(column, ROWS)).toEqual({ taken: [ROWS[0], ROWS[2]], skipped: [ROWS[1], ROWS[3]] })
  })

  it('commitToSelectedCells drops the skipped rows before `write`, and skips a column left with none', () => {
    const write = vi.fn(() => true)
    const column = levelColumn(write)
    expect(commitToSelectedCells([{ col: 'level' as const, rows: ROWS }], [column], 'level', 50)).toBe(1)
    expect(write).toHaveBeenCalledWith([ROWS[0], ROWS[2]], 50)
    write.mockClear()
    expect(commitToSelectedCells([{ col: 'level' as const, rows: [ROWS[1], ROWS[3]] }], [column], 'level', 50)).toBe(0)
    expect(write).not.toHaveBeenCalled()
  })

  it('names what it skipped — two by name, three with commas, past four a count', () => {
    expect(skippedNote([])).toBe('')
    expect(skippedNote(['M2'])).toBe('M2 · skipped')
    expect(skippedNote(['M2', 'M4'])).toBe('M2 and M4 · skipped')
    expect(skippedNote(['M2', 'M3', 'M4'])).toBe('M2, M3 and M4 · skipped')
    expect(listNames(['a', 'b', 'c', 'd', 'e'], 'master')).toBe('5 masters')
  })
})

describe('useSheet — skips in the batch count, the read-out, Clear and Spread', () => {
  function harness(onOpenRow?: (row: Row) => void) {
    const column = levelColumn()
    const hook = renderHook(() =>
      useSheet<Row, 'level'>({
        rows: ROWS,
        columns: [column],
        permission: { entry: true, clear: true },
        copy: () => ({ setTitle: 'Set', clearTitle: 'Clear' }),
        rowName: (row) => row.name,
        onOpenRow,
      }),
    )
    return { column, ...hook }
  }

  it('a marquee over four rows counts two, names the other two, and writes, clears and spreads the two', () => {
    const { column, result } = harness()
    act(() => result.current.tableProps.cellSelection.select(ROWS.map((r) => ({ rowId: r.id, col: 'level' as const })), 'replace'))
    const { batchCountFor, skippedFor, onCellCommit } = result.current.tableProps
    expect(batchCountFor(ROWS[0], 'level')).toBe(2)
    expect(skippedFor(ROWS[0], 'level')).toBe('M2 and M4 · skipped')

    act(() => onCellCommit(ROWS[0], 'level', 77))
    expect(column.write).toHaveBeenCalledWith([ROWS[0], ROWS[2]], 77)

    act(() => result.current.clearSelectedCells())
    expect(column.clear).toHaveBeenCalledWith([ROWS[0], ROWS[2]])

    expect(result.current.spreadPlans).toEqual([expect.objectContaining({ count: 2 })])
  })

  it('the row-selection path skips as the marquee does', () => {
    const { column, result } = harness()
    act(() => result.current.setRows(['a', 'b']))
    const { batchCountFor, skippedFor, onCellCommit } = result.current.tableProps
    expect(batchCountFor(ROWS[0], 'level')).toBe(1)
    expect(skippedFor(ROWS[0], 'level')).toBe('M2 · skipped')
    act(() => onCellCommit(ROWS[0], 'level', 5))
    expect(column.write).toHaveBeenCalledWith([ROWS[0]], 5)
  })

  it('a column’s own skipNote says why, in place of the names', () => {
    const column = { ...levelColumn(), skipNote: (rows: readonly Row[]) => `${rows.length} follow M1 · skipped` }
    const { result } = renderHook(() =>
      useSheet<Row, 'level'>({
        rows: ROWS,
        columns: [column],
        permission: { entry: true, clear: true },
        copy: () => ({ setTitle: 'Set', clearTitle: 'Clear' }),
      }),
    )
    act(() => result.current.tableProps.cellSelection.select(ROWS.map((r) => ({ rowId: r.id, col: 'level' as const })), 'replace'))
    expect(result.current.tableProps.skippedFor(ROWS[0], 'level')).toBe('2 follow M1 · skipped')
  })
})

describe('⏎ over one row opens it (D4)', () => {
  beforeEach(() => {
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        disconnect() {}
        unobserve() {}
      },
    )
    resetEditorSurfaceMedia()
  })
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  function Harness({ onOpen, rename }: { onOpen: (row: Row) => void; rename?: (row: Row, next: string) => void }) {
    const columns = [levelColumn()]
    const sheet = useSheet<Row, 'level'>({
      rows: ROWS,
      columns,
      permission: { entry: true, clear: true },
      copy: () => ({ setTitle: 'Set', clearTitle: 'Clear' }),
      onOpenRow: onOpen,
    })
    return (
      <SheetTable<Row, 'level'>
        {...sheet.tableProps}
        firstColumn={libraryNameColumn<Row>({
          noun: 'master',
          name: (row) => row.name,
          rename,
          onOpen,
          openLabel: (row) => `Edit ${row.name}`,
        })}
      />
    )
  }

  function rowEl(id: string) {
    return document.querySelector(`[data-row-id="${id}"]`) as HTMLElement
  }

  it('opens the one selected row, from the window', () => {
    const onOpen = vi.fn()
    render(<Harness onOpen={onOpen} />)
    fireEvent.click(rowEl('c').querySelector('[data-first-column]')!)
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(onOpen).toHaveBeenCalledWith(ROWS[2])
  })

  it('does nothing over two rows, or with a modifier held', () => {
    const onOpen = vi.fn()
    render(<Harness onOpen={onOpen} />)
    fireEvent.click(rowEl('a').querySelector('[data-first-column]')!)
    fireEvent.click(rowEl('c').querySelector('[data-first-column]')!, { metaKey: true })
    fireEvent.keyDown(window, { key: 'Enter' })
    expect(onOpen).not.toHaveBeenCalled()
    fireEvent.click(rowEl('c').querySelector('[data-first-column]')!)
    fireEvent.keyDown(window, { key: 'Enter', shiftKey: true })
    expect(onOpen).not.toHaveBeenCalled()
  })

  it('exempts the selected row’s own name trigger — clicking a name focuses it, and ⏎ still opens the row', () => {
    const onOpen = vi.fn()
    render(<Harness onOpen={onOpen} rename={() => {}} />)
    const name = within(rowEl('b')).getByTitle('Double-click to rename').closest('button')!
    fireEvent.click(name)
    name.focus()
    fireEvent.keyDown(name, { key: 'Enter' })
    expect(onOpen).toHaveBeenCalledWith(ROWS[1])
  })

  it('does not exempt the pencil — ⏎ on it is its own press — nor a name on a row that is not selected', () => {
    const onOpen = vi.fn()
    render(<Harness onOpen={onOpen} rename={() => {}} />)
    fireEvent.click(rowEl('b').querySelector('[data-first-column]')!)
    const pencil = screen.getByRole('button', { name: 'Edit M2' })
    pencil.focus()
    fireEvent.keyDown(pencil, { key: 'Enter' })
    expect(onOpen).not.toHaveBeenCalled()
    const other = within(rowEl('d')).getByTitle('Double-click to rename').closest('button')!
    other.focus()
    fireEvent.keyDown(other, { key: 'Enter' })
    expect(onOpen).not.toHaveBeenCalled()
  })

  it('the pencil opens its row and does not select it', () => {
    const onOpen = vi.fn()
    render(<Harness onOpen={onOpen} />)
    fireEvent.click(rowEl('b').querySelector('[data-first-column]')!)
    fireEvent.click(screen.getByRole('button', { name: 'Edit M2' }))
    expect(onOpen).toHaveBeenCalledWith(ROWS[1])
    expect(rowEl('b')).toHaveAttribute('data-state', 'selected')
  })
})

describe('groupRows', () => {
  interface Script extends SheetRow {
    type: string
  }
  const divider = (key: string): Script => ({ id: `div:${key}`, type: key, divider: key.toUpperCase() })

  it('interleaves a divider per partition present, in declared order, keeping row order within one', () => {
    const rows: Script[] = [
      { id: '1', type: 'fx' },
      { id: '2', type: 'general' },
      { id: '3', type: 'fx' },
    ]
    expect(groupRows(rows, { partition: (r) => r.type, order: ['general', 'hook', 'fx'], divider }).map((r) => r.id)).toEqual([
      'div:general',
      '2',
      'div:fx',
      '1',
      '3',
    ])
  })

  it('never drops a row whose partition is undeclared — last, under `unlisted` when given', () => {
    const rows: Script[] = [
      { id: '1', type: 'new-kind' },
      { id: '2', type: 'fx' },
    ]
    expect(groupRows(rows, { partition: (r) => r.type, order: ['fx'], divider }).map((r) => r.id)).toEqual(['div:fx', '2', '1'])
    expect(
      groupRows(rows, { partition: (r) => r.type, order: ['fx'], divider, unlisted: 'other' }).map((r) => r.id),
    ).toEqual(['div:fx', '2', 'div:other', '1'])
  })
})

describe('libraryPermission — another project’s library is read-only (D12)', () => {
  it('lets the current project edit and names nothing', () => {
    const scope = libraryPermission(true, 'Experiment')
    expect(scope.permission).toEqual({ entry: true, clear: true })
    expect(scope.reason).toBeNull()
    expect(scope.copy(2).setTitle).toBe('Set the 2 selected cells (Enter)')
  })

  it('refuses both gestures elsewhere, with the one reason on every verb', () => {
    const scope = libraryPermission(false, 'Rehearsal Room')
    expect(scope.permission).toEqual({ entry: false, clear: false })
    expect(scope.readOnly).toBe(true)
    expect(scope.reason).toBe('Rehearsal Room’s library — copy it here to edit')
    expect(scope.copy(3)).toEqual({ setTitle: scope.reason, clearTitle: scope.reason })
  })
})

describe('reportSheetWriteFailure (D14)', () => {
  it('phrases a refusal by its code, and falls back to the desk’s own message', () => {
    const err = { status: 409, data: { error: 'Colour is taken by M3', code: 'SPEED_MASTER_USAGE_TAKEN' } }
    expect(sheetWriteFailureMessage(err, { SPEED_MASTER_USAGE_TAKEN: (m) => `Usage refused: ${m}` })).toBe(
      'Usage refused: Colour is taken by M3',
    )
    expect(sheetWriteFailureMessage(err)).toBe('Colour is taken by M3')
    expect(sheetWriteFailureMessage({ status: 400, data: { error: 'nope' } }, {})).toBe('nope')
  })
})
