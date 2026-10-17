import { useCallback, useMemo, type ReactNode } from 'react'
import { X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { SelectionBar } from '@/components/sheet/SelectionBar'
import { SheetPage } from '@/components/sheet/SheetPage'
import { SheetTable, type SheetSort, type SheetTableProps } from '@/components/sheet/SheetTable'
import { LibraryRow } from '@/components/sheet/LibraryRow'
import type { SheetColumn, SheetRow } from '@/components/sheet/sheetModel'
import { useSheet } from '@/components/sheet/useSheet'
import { useContainerBand } from '@/hooks/useContainerBand'
import {
  nextSort,
  rememberScroll,
  rememberedScroll,
  type AppliedTable,
  type AppliedView,
} from '@/lib/appliedTables'

/**
 * The widths the three tables fold at, widest first — the sheet's own width, so a folded sidebar
 * counts. Measured against the desk: 1440 with the sidebar open leaves the sheet 1200, the
 * 1180×820 iPad 940, a 768 tablet 528, a phone 375. Band 0 draws every column, band 1 merges the
 * secondary ones, bands 2 and 3 lead with the name and what identifies a row and let the rest
 * scroll sideways under the sticky name column. Nothing is hidden.
 */
export const APPLIED_BAND_FLOORS: readonly number[] = [1100, 860, 480, 0]
export type AppliedBand = 0 | 1 | 2 | 3

/** The read-only scope every applied table is: no column holds a cell, so no key edits one. */
const NO_KEYS = { entry: false, clear: false }
const NO_COPY = () => ({ setTitle: 'Nothing here is set in the table — use a row’s actions', clearTitle: 'Nothing here is cleared in the table — use a row’s actions' })

export interface AppliedTableShellProps<Row extends SheetRow, C extends string> {
  table: AppliedTable
  /** The rows, already filtered and sorted by the caller from [view]. */
  rows: readonly Row[]
  /** Every row before the filter — the footer's total and the empty state's question. */
  total: number
  columnsFor: (band: AppliedBand) => { columns: SheetColumn<Row, C>[]; minWidth: number }
  firstColumnFor: (band: AppliedBand) => SheetTableProps<Row, C>['firstColumn']
  /** The keys a header press sorts by; the first column's is [firstSortKey]. */
  sortKeys: ReadonlySet<string>
  firstSortKey: string
  view: AppliedView
  onViewChange: (next: AppliedView) => void
  filterLabel: string
  /** The partition chips, where the table has some. */
  chips?: ReactNode
  /** What a row is called — `effect`, `look`, `palette`. */
  noun: string
  rowName: (row: Row) => string
  /** ⏎ over one row, and the name's double click: what the rail's row opens. */
  onOpenRow: (row: Row) => void
  /**
   * Draw the sheet's pencil for [onOpenRow]. False where the row already carries the button that
   * opens it — a layer's settings button.
   */
  pencil?: boolean
  /** The verbs over the selected rows, after the count and before Deselect. */
  verbs: (selected: readonly Row[]) => ReactNode
  /** Drawn in place of the sheet while there is nothing applied at all. */
  emptyNote: string
  footer: ReactNode
}

/**
 * **The one shell the three applied tables share** (venue-visit plan S14): the list shell's library
 * row (filter · chips), the selection bar with the table's verbs, the sheet and the footer — the
 * library sheets' anatomy over the live programmer.
 *
 * **Every column is a read-out.** What a row can change it changes through its actions, which are
 * the rail's own (`ProgrammerFxList`, `ProgrammerLookStack`) calling the rail's mutations, so a
 * later change to what a row can do (R3) moves the actions and never the table. The sheet still
 * selects rows — a click, a drag down the name column, ⌘A, ↑ / ↓ — for the bar's verbs over several.
 *
 * Sort, filter and chip are the route's (`useAppliedView`), handed in as [view]; a header press is
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
  chips,
  noun,
  rowName,
  onOpenRow,
  pencil = true,
  verbs,
  emptyNote,
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
    permission: NO_KEYS,
    copy: NO_COPY,
    noun,
    rowName,
    onOpenRow,
  })
  const { selectedRows, clearByLadder } = sheet

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
  const plural = count === 1 ? noun : `${noun}s`
  return (
    <>
      <LibraryRow filter={view.q} onFilterChange={setFilter} filterLabel={filterLabel} chips={chips} />
      <div className="@container">
        <SelectionBar
          rowLabel={count > 0 ? `${count} ${plural}` : null}
          cellLabel={null}
          family={null}
          hints={{ entry: false, clear: false }}
          verbs={
            count > 0 ? (
              <div className="flex shrink-0 items-center gap-1.5 sm:gap-2">
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
      {/* The measured box is the sheet's column, so the band follows the room the sheet has. */}
      <div ref={measure} className="flex min-h-0 flex-1 flex-col" data-applied-band={band}>
        {total === 0 ? (
          <SheetPage.Empty>{emptyNote}</SheetPage.Empty>
        ) : rows.length === 0 ? (
          <SheetPage.Empty>No {noun}s match the filter.</SheetPage.Empty>
        ) : (
          <SheetTable<Row, C>
            {...sheet.tableProps}
            minWidth={`${minWidth}px`}
            firstColumn={firstColumn}
            sort={sort}
            scroll={scroll}
          />
        )}
      </div>
      <SheetPage.Footer>{footer}</SheetPage.Footer>
    </>
  )
}
