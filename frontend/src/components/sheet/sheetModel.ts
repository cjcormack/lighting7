import type { ReactNode, RefObject } from 'react'
import type { SpreadPlan } from '../editor/SpreadPanel'
import type { CellRef, RowId } from './cellSelectionModel'
import type { CellSelection } from './useCellSelection'

/**
 * A row of a sheet. A divider draws its label across the row and cannot be selected — the
 * fixtures list's "Ungrouped", the cue sheet's markers.
 */
export interface SheetRow {
  id: RowId
  divider?: string
}

/**
 * What a column hands the cell it draws — the standard set every kit cell takes, so a column
 * author wires the same props whichever editor the column uses.
 */
export interface SheetCellProps<V> {
  value: V
  /** The column's name, which titles the editor where it is a sheet. */
  label: string
  /** How many rows a commit from this cell lands on — the marquee's rows, or this row alone. */
  batchCount: number
  /**
   * The batch in the sheet's own noun — *4 cues*, *8 channels*, *1 fixture* — for the popover's
   * label line (editor-kit plan D8). The noun is the sheet's (`SheetTableProps.batchNoun`), so a
   * kit cell never has to know which sheet it is on.
   */
  batchLabel: string
  /** The rows that commit lands on, in visible order — for an editor that previews its landing. */
  batchRows: () => readonly SheetRow[]
  /**
   * The rows the marquee covers in this column that the commit will **not** reach, named — a
   * follower's BPM, master 1's Follows, a snap cue's Curve (library-sheets plan D12). A marquee is
   * geometric, so it sweeps up cells with nothing to set; the kit drops them before `write` and
   * leaves them out of [batchCount], and this is where the editor says so, on its `EditorReadout`
   * (*M2 and M4 follow M1 · skipped*). Null or absent when every row takes the value.
   */
  skipped?: string | null
  /** The surface has made this cell inert: a locked show, an offline desk. */
  disabled: boolean
  autoOpen: boolean
  autoClose: boolean
  anchorAtButton: boolean
  keyboardSeed: string | null
  selectionEmpty?: boolean
  editorAnchorRef?: RefObject<HTMLElement | null>
  onBeginEdit: () => void
  /** The editor's commit, which the container spreads over every selected cell of this column. */
  onCommit: (value: V) => void
}

const NO_ROWS: readonly SheetRow[] = []

/** *4 cues*, *1 fixture*: the count in the sheet's noun, pluralised by an `s`. */
export function batchLabelOf(count: number, noun: string): string {
  return `${count} ${count === 1 ? noun : `${noun}s`}`
}
const noop = () => {}

/**
 * The props for a kit cell mounted **outside the marquee** — the first column's own editor: the
 * patch list's fixture name, the cue sheet's cue number.
 *
 * The first column is the row axis, so it is never in a cell selection: no batch to spread over, no
 * keyboard seed, no Set to anchor at, and `onBeginEdit` does nothing — a single click on the
 * trigger simply bubbles to the sticky cell's `onRowClick` and selects the row, and a double click
 * opens the editor beside it. That is the same popover, in the same three forms, as every value
 * cell (CLAUDE.md §The cell editor's three forms). It was an `InlineEditField` until the list
 * shell's follow-up, which made the name the one editor on a sheet that was not a popover, in the
 * column every other gesture starts from.
 */
export function firstColumnCellProps<V>({
  value,
  label,
  noun = 'row',
  disabled = false,
  onCommit,
}: {
  value: V
  label: string
  /** The sheet's noun for its rows — see `SheetCellProps.batchLabel`. */
  noun?: string
  disabled?: boolean
  onCommit: (value: V) => void
}): SheetCellProps<V> {
  return {
    value,
    label,
    batchCount: 1,
    batchLabel: batchLabelOf(1, noun),
    batchRows: () => NO_ROWS,
    disabled,
    autoOpen: false,
    autoClose: false,
    anchorAtButton: false,
    keyboardSeed: null,
    onBeginEdit: noop,
    onCommit,
  }
}

/**
 * One column of a sheet: how to read a row, which editor it takes, whether it spreads, and what a
 * commit does (CLAUDE.md §Sheet kit).
 *
 * `write` takes the **rows** of the batch, not one row at a time, because some columns land a
 * batch as a whole — the patch list's Address lands N heads consecutively from the typed one. It
 * answers false when it refuses the value. A commit from one column's editor reaches the other
 * selected columns only where they share its [kind] — see `commitToSelectedCells` — which is the
 * kit's form of the shape test the programmer's `commitToCells` makes through `planBatchWrites`.
 *
 * A column with no `cell` is a read-out: it draws `display`, hangs no `data-column-header` for the
 * marquee to measure, and so takes no selection — Enter never opens it and Clear never blames it.
 */
export interface SheetColumn<Row extends SheetRow, C extends string = string, V = unknown> {
  key: C
  label: string
  /** The grid track: `104px`, `minmax(120px,1fr)`. */
  width: string
  /**
   * The value vocabulary this column takes — `level` on every DMX column, `fade` on the cue
   * sheet's Fade, and so on. **A commit spreads only to the selected columns that share its
   * origin's kind.** The programmer's columns tell their commits apart by shape (`CellCommit.kind`),
   * but a cue's name, its notes and its fade are all one `string`, so shape discriminates nothing
   * there: a `3s` typed into Fade over a Fade→Follow marquee would otherwise switch auto-advance
   * on for every cue, and a rigging picked over a Mount→Gel marquee would land as a gel code.
   * Absent means the column takes commits from its own editor alone.
   */
  kind?: string
  /** The value a cell shows and edits, or undefined for a row with nothing in this column. */
  value: (row: Row) => V | undefined
  /** Draws an editable cell. Absent for a read-out column. */
  cell?: (row: Row, props: SheetCellProps<V>) => ReactNode
  /** A read-out, drawn where there is no `cell`. */
  display?: (row: Row) => ReactNode
  /**
   * Commit a value to these rows. False when the column refuses it, having written nothing.
   *
   * **It never receives a row whose [value] is undefined** — the kit drops those first
   * (`takesValue`), in the commit, Clear and Spread alike, and names them on the editor's read-out
   * through [skipNote]. So a column states "nothing to set here" once, in `value`, rather than again
   * as a guard in every writer.
   */
  write?: (rows: readonly Row[], value: unknown) => boolean
  /**
   * The read-out sentence for the rows the kit dropped from a commit in this column —
   * *M2 and M4 follow M1 · skipped*. Absent, the kit names them through the sheet's `rowName`
   * (`skippedNote`). A column overrides it where *why* is worth saying: the rows it drops all fail
   * for one reason, and the reason is the column's to know.
   */
  skipNote?: (rows: readonly Row[]) => string
  /** Clear these rows' cells. Absent means Clear is refused here, with [clearRefusal] as the reason. */
  clear?: (rows: readonly Row[]) => void
  clearRefusal?: string
  /** The spread over these rows in visible order, or null where this column does not spread. */
  spread?: (rows: readonly Row[]) => SpreadPlan | null
  /**
   * Whether the cell reserves the **18px marks gutter** on its right for the corner glyphs (a
   * Look layer, an effect, a clash). True by default, which is every column that has one.
   *
   * The DMX sheet sets it false: it draws no corner glyph, and the gutter made the cell's own
   * ownership ring a box 18px narrower than the selection overlay around it — the two lines an
   * operator reads a channel by, disagreeing on three of four edges. Without it the cell is padded
   * 2px all round and the overlay is inset to match (`cellSelectionClass`).
   */
  gutter?: boolean
  /** Extra classes on the cell wrapper — an ownership ring, the overlap ring. */
  cellClass?: (row: Row) => string | undefined
  /** The wrapper's hover text. */
  cellTitle?: (row: Row) => string | undefined
  /** Right-align the content — numbers. */
  align?: 'left' | 'right'
}

/** A cell of a sheet, over the sheet's own column vocabulary. */
export type SheetCellRef<C extends string> = CellRef<C>

/**
 * The selected cells grouped by column, each with its rows in **visible order** — the one
 * expansion behind every per-column consumer (the commit, Clear, the batch count and Spread), so a spread
 * and a typed value cannot reach different rows for one selection.
 *
 * The programmer's `columnTargets` is this over write targets; the kit's is over rows, since a
 * sheet row is the unit its columns write.
 */
export function selectedRowsByColumn<Row extends SheetRow, C extends string>(
  selection: CellSelection<C>,
  rows: readonly Row[],
): { col: C; rows: Row[] }[] {
  const byId = new Map(rows.map((row) => [row.id, row]))
  return selection.byColumn().map(({ col, rowIds }) => {
    const wanted = new Set(rowIds)
    return { col, rows: rows.filter((row) => wanted.has(row.id)).map((row) => byId.get(row.id)!) }
  })
}

/**
 * Does this row have anything to set in this column? A row whose `value` is undefined is blank
 * there (or a read-out), and a commit, a Clear or a Spread that reaches it through a geometric
 * marquee **skips** it — the column's `write` never sees it (library-sheets plan D12).
 */
export function takesValue<Row extends SheetRow, C extends string>(column: SheetColumn<Row, C>, row: Row): boolean {
  return row.divider == null && column.value(row) !== undefined
}

/** A batch split into the rows a column writes and the rows it skips, both in visible order. */
export function splitBatch<Row extends SheetRow, C extends string>(
  column: SheetColumn<Row, C>,
  rows: readonly Row[],
): { taken: Row[]; skipped: Row[] } {
  const taken: Row[] = []
  const skipped: Row[] = []
  for (const row of rows) (takesValue(column, row) ? taken : skipped).push(row)
  return { taken, skipped }
}

/**
 * Names as prose for a one-line read-out — `M2`, `M2 and M4`, `M2, M3 and M4` — and past four a
 * count (`6 masters`), since a popover's read-out is one line. [noun] is the count's word.
 */
export function listNames(names: readonly string[], noun = 'row'): string {
  if (names.length > 4) return `${names.length} ${noun}s`
  if (names.length <= 1) return names[0] ?? ''
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
}

/** The default skip read-out: the rows by name, then *skipped* — `M2 and M4 · skipped`. */
export function skippedNote(names: readonly string[]): string {
  if (names.length === 0) return ''
  return `${listNames(names)} · skipped`
}

/**
 * One commit, every selected cell **of the origin's kind**. Grouped by column so each column
 * writes its batch once: the column the editor opened on always takes it, a sibling takes it
 * only when it declares the same `kind`, and a value the column cannot take is dropped inside
 * `write` besides — so the selection's cell count is an upper bound on what any one commit
 * writes. Returns how many columns took it.
 *
 * **Rows with nothing to set in a column are dropped before its `write`** (`takesValue`): a
 * follower's BPM, master 1's Follows, a snap cue's Curve. A column left with no rows is not
 * written at all.
 */
export function commitToSelectedCells<Row extends SheetRow, C extends string>(
  groups: readonly { col: C; rows: readonly Row[] }[],
  columns: readonly SheetColumn<Row, C>[],
  origin: C,
  value: unknown,
): number {
  const originKind = columns.find((c) => c.key === origin)?.kind
  let written = 0
  for (const { col, rows } of groups) {
    const column = columns.find((c) => c.key === col)
    if (!column?.write) continue
    if (col !== origin && (originKind == null || column.kind !== originKind)) continue
    const taken = rows.filter((row) => takesValue(column, row))
    if (taken.length === 0) continue
    if (column.write(taken, value)) written += 1
  }
  return written
}

/**
 * The first selected cell in display order **that has an editor** — a marquee is geometric and
 * happily covers a read-out column and a row with nothing in it.
 */
export function firstEditableCell<Row extends SheetRow, C extends string>(
  ordered: readonly CellRef<C>[],
  rows: readonly Row[],
  columns: readonly SheetColumn<Row, C>[],
): CellRef<C> | undefined {
  const byId = new Map(rows.map((row) => [row.id, row]))
  return ordered.find((cell) => {
    const row = byId.get(cell.rowId)
    const column = columns.find((c) => c.key === cell.col)
    return row != null && row.divider == null && column?.cell != null && column.value(row) !== undefined
  })
}
