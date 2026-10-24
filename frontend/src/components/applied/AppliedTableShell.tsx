import { useCallback, useEffect, useMemo, useRef, type ReactNode } from 'react'
import { X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { SelectionBar } from '@/components/sheet/SelectionBar'
import { SheetPage } from '@/components/sheet/SheetPage'
import { SheetTable, type SheetSort, type SheetTableProps } from '@/components/sheet/SheetTable'
import { LibraryRow } from '@/components/sheet/LibraryRow'
import { nounPlural, type SheetColumn, type SheetRow } from '@/components/sheet/sheetModel'
import type { RowId } from '@/components/sheet/cellSelectionModel'
import type { CellActionCopy, CellKeyboardPermission } from '@/components/sheet/cellEntry'
import { CellSelectionActions } from '@/components/sheet/CellSelectionActions'
import { useSheet } from '@/components/sheet/useSheet'
import { useContainerBand } from '@/hooks/useContainerBand'
import { ProgrammerTableTabs } from '@/components/programmer/ProgrammerTableTabs'
import {
  nextSort,
  rememberScroll,
  rememberedScroll,
  type ProgrammerTable,
  type TableView,
} from '@/lib/programmerTables'

/**
 * The widths the tables fold at, widest first — the sheet's own width, so a folded sidebar and the
 * rail beside the programmer count. Band 0 draws every column, band 1 merges the
 * secondary ones, bands 2 and 3 lead with the name and what identifies a row and let the rest
 * scroll sideways under the sticky name column. Nothing is hidden.
 */
export const APPLIED_BAND_FLOORS: readonly number[] = [1100, 860, 480, 0]
export type AppliedBand = 0 | 1 | 2 | 3

/**
 * The partition chips' floor on a programmer table: the table tabs lead the row, so without it the
 * filter takes the slack and the chips' folded select overruns the create verb.
 */
export const PROGRAMMER_CHIPS_CLASS = 'min-w-32'

/** The scope of a table with no cell columns: no key edits one. */
const NO_KEYS = { entry: false, clear: false }
const NO_COPY = () => ({ setTitle: 'Nothing here is set in the table — use a row’s actions', clearTitle: 'Nothing here is cleared in the table — use a row’s actions' })

export interface AppliedTableShellProps<Row extends SheetRow, C extends string> {
  table: Exclude<ProgrammerTable, 'values'>
  /** The rows, already filtered and sorted by the caller from [view]. */
  rows: readonly Row[]
  /** Every row before the filter — the footer's total and the empty state's question. */
  total: number
  columnsFor: (band: AppliedBand) => { columns: SheetColumn<Row, C>[]; minWidth: number }
  firstColumnFor: (band: AppliedBand) => SheetTableProps<Row, C>['firstColumn']
  /** The keys a header press sorts by; the first column's is [firstSortKey]. */
  sortKeys: ReadonlySet<string>
  firstSortKey: string
  view: TableView
  onViewChange: (next: TableView) => void
  filterLabel: string
  /** After the table tabs — the FX tab's *Selection · All*. */
  leading?: ReactNode
  /** The partition chips, where the table has some. */
  chips?: ReactNode
  /** The library row's create verb — *+ Effect*, *+ Look or palette*. */
  create?: ReactNode
  /** A rule row under the selection bar, over the sheet's header. */
  rule?: ReactNode
  /** `SheetTable`'s row drag, where the table's order is one the operator can change. */
  rowDrag?: SheetTableProps<Row, C>['rowDrag']
  rowClass?: SheetTableProps<Row, C>['rowClass']
  /** What a row is called — `FX`, `look`, `palette`. */
  noun: string
  rowName: (row: Row) => string
  /** ⏎ over one row, and the name's double click. */
  onOpenRow: (row: Row) => void
  /**
   * Draw the sheet's pencil for [onOpenRow]. False where the row already carries the button that
   * opens it — a layer's settings button.
   */
  pencil?: boolean
  /** The verbs over the selected rows, after the count and before Deselect. */
  verbs: (selected: readonly Row[]) => ReactNode
  /**
   * The selected rows, reported as they change — the FX table publishes them for the Values grid's
   * rings — and the rows to select on mount, so a selection made before a tab switch is still the
   * table's when it comes back.
   */
  onSelectionChange?: (rows: readonly Row[]) => void
  initialSelection?: readonly RowId[]
  /**
   * The keys' gate and the bar's words, for a table whose columns hold cells — the Scenery table's
   * Shown · Open · Trim. Absent, no column is edited in the table and Set and Clear are not drawn.
   */
  cells?: {
    permission: CellKeyboardPermission
    copy: (cellCount: number) => CellActionCopy
    /** A cell the scope will not write: drawn inert, as on every sheet. */
    cellDisabled?: (row: Row, col: C) => boolean
  }
  /** Drawn in place of the sheet while there is nothing applied at all. */
  emptyNote: string
  /** The rows are still being read: the spinner, not [emptyNote]. */
  loading?: boolean
  footer: ReactNode
}

/**
 * **The one shell the programmer's Stack, FX and Scenery tables share** (venue-visit plan S14,
 * tables-colours plan D1): the list shell's library row (the table tabs · filter · chips · create),
 * the selection bar with the table's verbs, the sheet and the footer — the library sheets' anatomy
 * over the live programmer.
 *
 * **A column is a read-out unless the table says otherwise** ([cells]). What a row can change it
 * changes through its actions, which call the desk's mutations (`useFxControls`, the stack's
 * `LayerHandlers`); the Scenery table alone edits its state in cells, Set over a marquee as on any
 * sheet. The sheet selects rows — a click, a drag down the name column, ⌘A, ↑ / ↓ — for the bar's
 * verbs over several.
 *
 * Sort, filter and chip are the page's (`ProgrammerTables`), handed in as [view]; a header press is
 * `nextSort`. Scroll is remembered per window and table here, through the sheet's `scroll`.
 */
export function AppliedTableShell<Row extends SheetRow, C extends string>({
  table,
  rows,
  total,
  columnsFor,
  firstColumnFor,
  sortKeys,
  firstSortKey,
  view,
  onViewChange,
  filterLabel,
  leading,
  chips,
  create,
  rule,
  rowDrag,
  rowClass,
  noun,
  rowName,
  onOpenRow,
  pencil = true,
  verbs,
  onSelectionChange,
  initialSelection,
  cells,
  emptyNote,
  loading = false,
  footer,
}: AppliedTableShellProps<Row, C>) {
  const [measure, bandIndex] = useContainerBand(APPLIED_BAND_FLOORS)
  const band = bandIndex as AppliedBand
  const { columns, minWidth } = useMemo(() => columnsFor(band), [columnsFor, band])
  const firstColumn = useMemo(
    () => ({ ...firstColumnFor(band), onOpen: pencil ? onOpenRow : undefined }),
    [band, firstColumnFor, onOpenRow, pencil],
  )

  const sheet = useSheet<Row, C>({
    rows,
    columns,
    permission: cells?.permission ?? NO_KEYS,
    copy: cells?.copy ?? NO_COPY,
    cellDisabled: cells?.cellDisabled,
    noun,
    rowName,
    onOpenRow,
  })
  const { selectedRows, clearByLadder, setRows, cellCount } = sheet

  // Once, on mount: `setRows` changes identity with the rows, and re-applying the initial
  // selection on a refetch or a filter keystroke would snap the operator's selection back to it.
  const initial = useRef(initialSelection)
  useEffect(() => {
    const ids = initial.current
    initial.current = undefined
    if (ids != null && ids.length > 0) setRows(ids)
  }, [setRows])
  useEffect(() => {
    onSelectionChange?.(selectedRows)
  }, [onSelectionChange, selectedRows])

  const sort = useMemo<SheetSort>(
    () => ({
      key: view.sort,
      dir: view.dir,
      keys: sortKeys,
      firstKey: firstSortKey,
      onSort: (key) => onViewChange(nextSort(view, key)),
    }),
    [firstSortKey, onViewChange, sortKeys, view],
  )
  const scroll = useMemo(
    () => ({ initialTop: rememberedScroll(table), onScroll: (top: number) => rememberScroll(table, top) }),
    [table],
  )
  const setFilter = useCallback((q: string) => onViewChange({ ...view, q }), [onViewChange, view])

  const count = selectedRows.length
  const plural = count === 1 ? noun : nounPlural(noun)
  return (
    <>
      {/* The row's own `@container`: the tabs and the create verb fold their words by its width. */}
      <div className="@container">
        <LibraryRow
          leading={
            <>
              <ProgrammerTableTabs />
              {leading}
            </>
          }
          filter={view.q}
          onFilterChange={setFilter}
          filterLabel={filterLabel}
          chips={chips}
          create={create}
        />
      </div>
      <div className="@container">
        <SelectionBar
          rowLabel={count > 0 ? `${count} ${plural}` : null}
          cellLabel={cellCount > 0 ? `${cellCount} cell${cellCount === 1 ? '' : 's'}` : null}
          family={null}
          hints={{ entry: cellCount > 0 && sheet.permission.entry, clear: cellCount > 0 && sheet.permission.clear }}
          verbs={
            count > 0 ? (
              <div className="flex shrink-0 items-center gap-1.5 sm:gap-2">
                {cellCount > 0 && cells != null && (
                  <CellSelectionActions
                    copy={sheet.copy}
                    permission={sheet.permission}
                    setRef={sheet.setButtonRef}
                    onSet={sheet.toggleCellEditor}
                    onClear={sheet.clearSelectedCells}
                  />
                )}
                {verbs(selectedRows)}
                <Button variant="ghost" size="sm" onClick={clearByLadder} title="Deselect all">
                  <X className="size-3.5" />
                  <span className="hidden sm:inline">Deselect</span>
                </Button>
              </div>
            ) : null
          }
          marqueeDragging={sheet.marqueeDragging}
          foldForStrip={false}
        />
      </div>
      {rule}
      {/* The measured box is the sheet's column, so the band follows the room the sheet has. */}
      <div ref={measure} className="flex min-h-0 flex-1 flex-col" data-applied-band={band}>
        {loading ? (
          <SheetPage.Empty loading />
        ) : total === 0 ? (
          <SheetPage.Empty>{emptyNote}</SheetPage.Empty>
        ) : rows.length === 0 ? (
          <SheetPage.Empty>No {nounPlural(noun)} match the filter.</SheetPage.Empty>
        ) : (
          <SheetTable<Row, C>
            {...sheet.tableProps}
            minWidth={`${minWidth}px`}
            firstColumn={firstColumn}
            sort={sort}
            scroll={scroll}
            rowDrag={rowDrag}
            rowClass={rowClass}
          />
        )}
      </div>
      <SheetPage.Footer>{footer}</SheetPage.Footer>
    </>
  )
}
