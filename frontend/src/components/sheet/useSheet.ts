import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { orderedSelectedCells, type CellActionCopy, type CellKeyboardPermission } from './cellEntry'
import type { CellFlow, CellRef, RowId } from './cellSelectionModel'
import { arrowStepTarget, type ListSelectIntent } from './listSelectionModel'
import { stepCellSelection, useCellSelection, type CellSelection } from './useCellSelection'
import { useCellEditorRequests } from './useCellEditorRequests'
import { useLocalListSelection } from './useLocalListSelection'
import { useSheetKeyboard, type SheetCellKeys, type SheetKeyRefusal, type SheetRowKeys } from './useSheetKeyboard'
import { useLivePush } from '../editor/useLivePush'
import {
  commitToSelectedCells,
  firstEditableCell,
  selectedRowsByColumn,
  skippedNote,
  splitBatch,
  takesValue,
  type SheetColumn,
  type SheetRow,
} from './sheetModel'
import type { SpreadPlan } from '../editor/SpreadPanel'
import type { SheetTableProps } from './SheetTable'

/**
 * How often a live editor's continuous commits reach the wire — ~30 Hz, trailing call.
 *
 * The floor `useLivePush` is given (editor-kit plan D16): the busk tabs' hook and this sheet's
 * throttle were two copies of one rule — a floor, a dedupe, a release that always lands — so the
 * sheet takes the hook and hands it the number it always used, and its cadence does not move.
 * Kept at 33 by argument rather than because 50 was tried; if a slider drag reads coarser, this is
 * the one number to move.
 */
export const COMMIT_INTERVAL_MS = 33

/** One cell's commit in flight — the row and column it names, and the value the editor gave. */
interface PendingCommit<Row extends SheetRow, C extends string> {
  row: Row
  col: C
  value: unknown
}

/**
 * `useLivePush`'s equality, answered **never equal** on purpose: the sheet does not dedupe.
 *
 * The hook dedupes a gesture's own moves against the value it last sent, and its `reset()` is how
 * a caller says a fresh gesture has begun. A sheet cannot say that: its value moves by routes the
 * hook never sees — ⌫ clears through `column.clear`, Spread and Park write through the surface, a
 * WebSocket frame or another tab moves the channel — so a value set, cleared and typed again would
 * read as "the value last sent" and never reach the rig while the field showed it (editor-kit
 * session 1 review, finding 2). The old throttle sent every commit; so does this one.
 */
function neverEqual(): boolean {
  return false
}

export interface UseSheetOptions<Row extends SheetRow, C extends string> {
  rows: readonly Row[]
  columns: readonly SheetColumn<Row, C>[]
  /**
   * The surface's gate on the two cell gestures — a locked show, an offline desk. Per-column
   * refusals (a column with no `clear`) are folded in below.
   */
  permission: CellKeyboardPermission
  /** The reasons the surface gives when [permission] refuses; the column's `clearRefusal` wins for Clear. */
  copy: (cellCount: number) => CellActionCopy
  /**
   * The surface has made every cell inert — a locked show, an offline desk. Read only for the
   * cell's rendered `disabled` and its wrapper's `pointer-events-none`; the keyboard and the bar
   * read [permission], which the surface keeps in step with this.
   */
  cellDisabled?: (row: Row, col: C) => boolean
  /**
   * The surface has a way out of its own refusal. Given, a refused key reaches it rather than being
   * swallowed — see `useSheetKeyboard`, which leaves it to the surface to decide whether to claim
   * the key. The bar's verbs are the other half and are wired separately, being presses rather than
   * keys (`CellSelectionActions`, `SpreadPanel`).
   */
  onRefused?: (refusal: SheetKeyRefusal) => boolean | void
  /**
   * What a row is called on this sheet — `cue`, `fixture`, `channel` — for the editors' label
   * line (`SheetCellProps.batchLabel`). Defaults to `row`.
   */
  noun?: string
  /**
   * What a row is called in a skip read-out — `M2`, `Q14`, `Front PAR` (library-sheets plan D12).
   * A column with its own `skipNote` does not need it. Defaults to the row id, which is honest but
   * rarely what an operator calls anything, so a sheet whose columns can skip should pass one.
   */
  rowName?: (row: Row) => string
  /**
   * Open one row's record — the sheet's `firstColumn.onOpen`, the pencil's target. Given, **⏎ with
   * exactly one row selected and no cells** calls it (library-sheets plan D4); absent, that key
   * does nothing with a row selection, as before.
   */
  onOpenRow?: (row: Row) => void
  /**
   * The sheet has a row axis. True (the default), a click or a drag on the first column selects
   * rows, and ⌘A and ↑ / ↓ step the row selection, Shift extending — the programmer's keys, on
   * every sheet (`useSheetKeyboard`'s `rowKeys`). False is the DMX sheet, where every press is a
   * cell press, there is no row selection to move, and the arrows and ⌘A are the cells'. Said once, here: `tableProps` carries it to
   * `SheetTable`, so the pointer and the keyboard cannot disagree.
   */
  selectsRows?: boolean
  /**
   * How a plain ← / → walks this sheet's cells (`CellFlow`): `grid` (the default) stops at a row's
   * ends; `linear` wraps from a row's end onto the next — the DMX sheet, whose reading order is
   * address order. Shift extends a rectangle on both. The kit derives the order itself from the
   * rows and the columns that hold cells, so this is all a surface says.
   */
  cellFlow?: CellFlow
}

/**
 * The container half of a sheet — one selection in two shapes, the keyboard, the editor requests,
 * the batch commit and Spread — written once for the patch list, the DMX sheet and the cue sheet
 * (CLAUDE.md §Sheet kit). `FixturesListContainer` is the same shape over the fixtures' row model
 * and scope, and is deliberately not rewired onto this: its tests pin it, and its selection lives
 * in Redux for readers outside the list.
 *
 * Rows and cells are one selection: selecting cells clears the rows, selecting rows clears the
 * cells, and `selectedRows` is the cells' rows under a marquee and the row selection otherwise.
 * Enforced at the doors, not by an effect.
 */
export function useSheet<Row extends SheetRow, C extends string>({
  rows,
  columns,
  permission,
  copy,
  cellDisabled,
  onRefused,
  noun = 'row',
  rowName,
  onOpenRow,
  selectsRows = true,
  cellFlow = 'grid',
}: UseSheetOptions<Row, C>) {
  const selectableOrder = useMemo(
    () => rows.filter((row) => row.divider == null).map((row) => row.id),
    [rows],
  )
  const rowSelection = useLocalListSelection(selectableOrder)
  const visibleRowIds = useMemo(() => new Set(rows.map((row) => row.id)), [rows])
  const cellSelection = useCellSelection<C>(visibleRowIds)
  const { count: cellCount, clear: clearCells, isSelected: isCellSelected } = cellSelection

  // ── One selection, two shapes ──
  const rowCountRef = useRef(rowSelection.count)
  rowCountRef.current = rowSelection.count
  const { select: selectRowRaw, setSelection: setRowsRaw, clear: clearRows } = rowSelection
  const selectCells = useCallback(
    (hits: readonly CellRef<C>[], intent: ListSelectIntent) => {
      if (hits.length > 0 && rowCountRef.current > 0) clearRows()
      cellSelection.select(hits, intent)
    },
    [cellSelection, clearRows],
  )
  const tableCellSelection = useMemo<CellSelection<C>>(
    () => ({ ...cellSelection, select: selectCells }),
    [cellSelection, selectCells],
  )
  const selectRow = useCallback(
    (id: RowId, intent?: ListSelectIntent) => {
      clearCells()
      selectRowRaw(id, intent)
    },
    [clearCells, selectRowRaw],
  )
  const setRows = useCallback(
    (ids: readonly RowId[]) => {
      clearCells()
      setRowsRaw(ids)
    },
    [clearCells, setRowsRaw],
  )

  const cellRowIds = useMemo(
    () => new Set(cellSelection.cells.map((cell) => cell.rowId)),
    [cellSelection.cells],
  )
  const selectedRowIds = cellRowIds.size > 0 ? cellRowIds : rowSelection.selectedIds
  /** The selection's rows, in visible order, whichever shape it is in. */
  const selectedRows = useMemo(
    () => rows.filter((row) => row.divider == null && selectedRowIds.has(row.id)),
    [rows, selectedRowIds],
  )

  const clearByLadder = useCallback(() => {
    if (cellCount > 0) clearCells()
    else clearRows()
  }, [cellCount, clearCells, clearRows])

  /** The marquee by column, rows in visible order — the one expansion behind every consumer. */
  const columnGroups = useMemo(
    () => selectedRowsByColumn(cellSelection, rows),
    [cellSelection, rows],
  )
  const columnByKey = useMemo(() => new Map(columns.map((c) => [c.key, c])), [columns])

  // ── The gate, with the columns' own refusals folded in ──
  const selectedColumns = useMemo(
    () => columnGroups.map(({ col }) => columnByKey.get(col)).filter((c): c is SheetColumn<Row, C> => c != null),
    [columnGroups, columnByKey],
  )
  const clearRefusal = selectedColumns.find((c) => c.clear == null)?.clearRefusal
  const effective: CellKeyboardPermission = useMemo(
    () => ({
      entry: permission.entry,
      clear: permission.clear && selectedColumns.length > 0 && selectedColumns.every((c) => c.clear != null),
    }),
    [permission.entry, permission.clear, selectedColumns],
  )
  const actionCopy: CellActionCopy = useMemo(() => {
    const base = copy(cellCount)
    return permission.clear && clearRefusal ? { ...base, clearTitle: clearRefusal } : base
  }, [cellCount, clearRefusal, copy, permission.clear])

  // ── Commits ──
  const commitToCells = useCallback(
    (origin: C, value: unknown) => commitToSelectedCells(columnGroups, columns, origin, value),
    [columnGroups, columns],
  )
  const commitNow = useCallback(
    (row: Row, col: C, value: unknown) => {
      if (cellSelection.isSelected(row.id, col)) {
        commitToCells(col, value)
        return
      }
      const column = columnByKey.get(col)
      if (!column?.write) return
      // The row-selection path skips as the marquee's does: a row with nothing to set in this
      // column never reaches its `write` (library-sheets plan D12).
      const targets = (rowSelection.isSelected(row.id) ? selectedRows : [row]).filter((r) => takesValue(column, r))
      if (targets.length > 0) column.write(targets, value)
    },
    [cellSelection, columnByKey, commitToCells, rowSelection, selectedRows],
  )
  // Continuous drag commits are throttled to ~30 Hz with a trailing call — a level slider spreads out
  // to one frame per selected channel — through `useLivePush`, the busk tabs' discipline (D16):
  // the first commit goes at once, a second inside the floor is held and sent when the floor
  // lifts. The hook's dedupe is switched off (`neverEqual`, above). `send` reads `commitNow`
  // through the hook's own ref, and `push` / `flush` are stable for the component's life, which is
  // what keeps `onCellCommit`'s identity stable for `SheetRowView`'s memo — the hook's *object* is
  // fresh per render, so it is the two functions that are depended on, never `live` itself.
  //
  // Two things the hook has no notion of are kept here. **A commit for a different cell lands the
  // pending one first**, at once: two cells' values are two writes, and holding the first behind
  // the second would drop it. And **an unmount lands whatever is pending**: the hook clears its
  // timer on unmount and lets the deferred value go, which is right for a fader whose control has
  // gone but not for a sheet whose last slider frame would otherwise never reach the rig. Both go
  // through `flush`, which bypasses the floor.
  //
  // `lastPushedRef` holds the commit **still waiting** in the hook, and only that: `send` clears
  // it the moment the hook sends it (the hook sends the very object `push` was given, so identity
  // is the test). With the dedupe off, a flush of a commit the hook had already sent would send it
  // twice — an address batch re-keyed on unmount, which is how this was found.
  const lastPushedRef = useRef<PendingCommit<Row, C> | null>(null)
  const { push: livePush, flush: liveFlush } = useLivePush<PendingCommit<Row, C>>(
    (pending) => {
      if (lastPushedRef.current === pending) lastPushedRef.current = null
      commitNow(pending.row, pending.col, pending.value)
    },
    { floorMs: COMMIT_INTERVAL_MS, equals: neverEqual },
  )
  useEffect(
    () => () => {
      const last = lastPushedRef.current
      lastPushedRef.current = null
      if (last) liveFlush(last)
    },
    [liveFlush],
  )
  const handleCellCommit = useCallback(
    (row: Row, col: C, value: unknown) => {
      const last = lastPushedRef.current
      if (last && (last.row.id !== row.id || last.col !== col)) liveFlush(last)
      const next: PendingCommit<Row, C> = { row, col, value }
      lastPushedRef.current = next
      livePush(next)
    },
    [liveFlush, livePush],
  )

  /**
   * Each selected column's batch split into the rows it writes and the rows it skips — once per
   * selection rather than once per visible cell, since every cell in the marquee reads its own
   * column's answer on every render.
   */
  const splitByColumn = useMemo(() => {
    const out = new Map<C, { taken: Row[]; skipped: Row[] }>()
    for (const { col, rows: group } of columnGroups) {
      const column = columnByKey.get(col)
      if (column) out.set(col, splitBatch(column, group))
    }
    return out
  }, [columnByKey, columnGroups])
  const splitFor = useCallback(
    (row: Row, col: C): { taken: readonly Row[]; skipped: readonly Row[] } => {
      if (cellSelection.isSelected(row.id, col)) {
        return splitByColumn.get(col) ?? { taken: [row], skipped: [] }
      }
      if (!rowSelection.isSelected(row.id)) return { taken: [row], skipped: [] }
      const column = columnByKey.get(col)
      return column ? splitBatch(column, selectedRows) : { taken: selectedRows, skipped: [] }
    },
    [cellSelection, columnByKey, rowSelection, selectedRows, splitByColumn],
  )
  /** The rows a commit from this cell lands on — the batch **less the rows it skips**. */
  const batchRowsFor = useCallback((row: Row, col: C): readonly Row[] => splitFor(row, col).taken, [splitFor])
  const batchCountFor = useCallback((row: Row, col: C) => batchRowsFor(row, col).length, [batchRowsFor])
  /** The skip read-out for this cell's batch, or null when every row takes the value. */
  const skippedFor = useCallback(
    (row: Row, col: C): string | null => {
      const { skipped } = splitFor(row, col)
      if (skipped.length === 0) return null
      const column = columnByKey.get(col)
      return column?.skipNote?.(skipped) ?? skippedNote(skipped.map((r) => rowName?.(r) ?? r.id))
    },
    [columnByKey, rowName, splitFor],
  )

  // ── The editor requests and the keyboard ──
  const [scrollToRowId, setScrollToRowId] = useState<RowId | null>(null)
  /** Bring a row into the virtualiser's window — a deep link's cue, a keyboard request. */
  const scrollTo = useCallback((rowId: RowId) => setScrollToRowId(rowId), [])
  const columnOrder = useMemo(() => columns.map((c) => c.key), [columns])
  const firstEditable = useCallback(
    () => firstEditableCell(orderedSelectedCells(cellSelection.cells, selectableOrder, columnOrder), rows, columns),
    [cellSelection.cells, columnOrder, columns, rows, selectableOrder],
  )
  const requests = useCellEditorRequests<C>({ firstEditableCell: firstEditable, onScrollTo: setScrollToRowId })
  const setButtonRef = useRef<HTMLButtonElement | null>(null)

  const clearSelectedCells = useCallback(() => {
    if (!effective.clear) return
    for (const { col, rows: group } of columnGroups) {
      const column = columnByKey.get(col)
      const taken = column ? group.filter((row) => takesValue(column, row)) : []
      if (taken.length > 0) column?.clear?.(taken)
    }
  }, [columnByKey, columnGroups, effective.clear])

  // ⏎ over one row opens its record. Read through the selected rows, so it names the row the
  // operator is looking at whichever way it was selected (a click, a name-column drag, ⌘A of one).
  const openSelectedRow = useMemo(() => {
    if (onOpenRow == null) return undefined
    return () => {
      if (selectedRows.length === 1) onOpenRow(selectedRows[0])
    }
  }, [onOpenRow, selectedRows])

  // ⌘A and ↑ / ↓ over the rows — the programmer's keys, stepped by its rule, through the row doors.
  // ⌘A over a cell marquee drops the cells, as a row click does; ↑ / ↓ there are the cells' keys
  // (`cellKeys` below), which `useSheetKeyboard` hands them first.
  const { selectAll: selectAllRaw, anchor: rowAnchor, orderedSelected: rowsOrdered } = rowSelection
  const rowKeys = useMemo<SheetRowKeys | undefined>(() => {
    if (!selectsRows) return undefined
    return {
      onSelectAll: () => {
        clearCells()
        selectAllRaw()
      },
      onStep: (direction, extend) => {
        const next = arrowStepTarget(
          selectableOrder,
          { anchor: rowAnchor, orderedSelected: rowsOrdered },
          direction,
          extend,
        )
        if (next == null) return false
        selectRow(next, extend ? 'range' : 'replace')
        setScrollToRowId(next)
        return true
      },
    }
  }, [clearCells, rowAnchor, rowsOrdered, selectAllRaw, selectRow, selectableOrder, selectsRows])

  // The arrows over the cells — `cellArrowStep`'s rule over the selectable rows and the columns
  // that hold cells (a read-out column hangs no `data-column-header`, so no marquee reaches it and
  // no arrow does either), stepping past a blank cell. A step goes through the cell door, so it drops a row selection as a
  // marquee does, and asks the table to bring the head into view by the least move.
  const selectableColumns = useMemo(() => columns.filter((c) => c.cell != null).map((c) => c.key), [columns])
  // A cell whose row has nothing to set in its column is drawn blank (library-sheets D12) — no
  // `data-cell`, no ring — so the arrows step past it, as they step past a read-out column.
  const cellGrid = useMemo(() => {
    const rowById = new Map(rows.map((row) => [row.id, row]))
    const takes = (rowId: RowId, col: C): boolean => {
      const row = rowById.get(rowId)
      const column = columnByKey.get(col)
      return row != null && column != null && takesValue(column, row)
    }
    return { rows: selectableOrder, cols: selectableColumns, takes }
  }, [columnByKey, rows, selectableOrder, selectableColumns])
  const [revealCell, setRevealCell] = useState<CellRef<C> | null>(null)
  const onRevealedCell = useCallback(() => setRevealCell(null), [])
  const { place: placeCells } = cellSelection
  const cellKeys = useMemo<SheetCellKeys>(() => {
    const clearRowsIfAny = () => {
      if (rowCountRef.current > 0) clearRows()
    }
    return {
      onStep: (direction, extend) => {
        const head = stepCellSelection(cellSelection, cellGrid, cellFlow, direction, extend, clearRowsIfAny)
        if (head == null) return false
        setRevealCell(head)
        return true
      },
      // ⌘A on a sheet with no rows to select is every cell — the DMX sheet's whole universe. A
      // sheet with rows keeps ⌘A for them (`useSheetKeyboard` prefers `rowKeys`).
      onSelectAll: selectsRows
        ? undefined
        : () => {
            const cells = cellGrid.rows.flatMap((rowId) => cellGrid.cols.map((col) => ({ rowId, col })))
            if (cells.length === 0) return
            clearRowsIfAny()
            placeCells(cells, { anchor: cells[0], head: cells[cells.length - 1] })
          },
    }
  }, [cellFlow, cellGrid, cellSelection, clearRows, placeCells, selectsRows])

  useSheetKeyboard<C>({
    cellCount,
    permission: effective,
    isCellSelected,
    onEscape: clearByLadder,
    onOpen: requests.openCellEditor,
    onClear: clearSelectedCells,
    onRefused,
    // The visible rows, not the selection's raw count: a selected id whose row has since gone (a
    // delete, a filter) must not make one row read as two.
    rowCount: cellCount > 0 ? 0 : selectedRows.length,
    onOpenRow: openSelectedRow,
    rowKeys,
    cellKeys,
  })

  const [marqueeDragging, setMarqueeDragging] = useState(false)

  const handleRowClick = useCallback(
    (id: RowId, e: React.MouseEvent) => selectRow(id, intentFor(e)),
    [selectRow],
  )
  const handleBeginCellEdit = useCallback(
    (row: Row, col: C) => {
      if (row.divider != null) return
      selectCells([{ rowId: row.id, col }], 'replace')
    },
    [selectCells],
  )

  // ── Spread ──
  const spreadPlans = useMemo<SpreadPlan[]>(
    () =>
      columnGroups.flatMap(({ col, rows: group }) => {
        const column = columnByKey.get(col)
        const taken = column ? group.filter((row) => takesValue(column, row)) : []
        const plan = taken.length > 0 ? column?.spread?.(taken) : null
        return plan ? [plan] : []
      }),
    [columnByKey, columnGroups],
  )

  // ── The bar's counts ──
  const family = useMemo(() => {
    const labels = [...new Set(selectedColumns.map((c) => c.label))]
    return labels.length > 0 ? labels.join(' · ') : null
  }, [selectedColumns])

  const tableProps = {
    rows,
    columns,
    isSelected: rowSelection.isSelected,
    onRowClick: handleRowClick,
    onBeginCellEdit: handleBeginCellEdit,
    onCellCommit: handleCellCommit,
    batchCountFor,
    batchRowsFor,
    skippedFor,
    batchNoun: noun,
    cellDisabled,
    cellSelection: tableCellSelection,
    onRowMarquee: setRows,
    keyboardOpen: requests.keyboardOpen,
    closeEditorCell: requests.closeEditorCell,
    editorAnchorRef: setButtonRef,
    onMarqueeDragChange: setMarqueeDragging,
    onBackgroundClick: clearByLadder,
    selectionEmpty: rowSelection.count === 0 && cellCount === 0,
    scrollToRowId,
    onScrolledToRow: () => setScrollToRowId(null),
    revealCell,
    onRevealedCell,
    selectsRows,
  } satisfies Partial<SheetTableProps<Row, C>>

  return {
    rowSelection,
    cellSelection,
    cellCount,
    selectedRows,
    selectedRowIds,
    columnGroups,
    /** The gate the bar's verbs and the keyboard both read. */
    permission: effective,
    copy: actionCopy,
    family,
    spreadPlans,
    setButtonRef,
    toggleCellEditor: requests.toggleCellEditor,
    clearSelectedCells,
    onRefused,
    clearByLadder,
    selectRow,
    setRows,
    scrollTo,
    marqueeDragging,
    tableProps,
  }
}

function intentFor(e: React.MouseEvent): ListSelectIntent {
  const cmd = e.metaKey || e.ctrlKey
  if (cmd && e.shiftKey) return 'range-add'
  if (e.shiftKey) return 'range'
  if (cmd) return 'toggle'
  return 'replace'
}
