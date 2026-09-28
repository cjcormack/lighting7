import { Fragment } from 'react'
import { useDroppable } from '@dnd-kit/core'
import { Plus } from 'lucide-react'
import { cn } from '@/lib/utils'
import type { BuskPage as BuskPageModel, BuskRow } from '@/api/buskApi'
import { addBankColumn, addRow, buskGutterId, BUSK_NEW_ROW_ID } from '@/lib/buskLayout'
import { useBuskEdit } from './BuskEditProvider'
import { BuskBankCluster } from './BuskBank'
import { DROP_DEPTH, type BuskDropData } from './buskDnd'
import type { PadBehaviour } from './padBehaviour'

/**
 * A page: rows of columns of banks.
 *
 * Widths are **shares of the row, in twelfths**, and this is the one place they become CSS — a
 * column of width 6 is a `6fr` track. They need not sum to twelve, so three quarter-columns simply
 * leave a quarter of the row empty, and a page drawn at desk width keeps its proportions on a
 * smaller screen until the columns stack.
 *
 * **They stack below {@link STACK_BELOW_PX} of the page body's width**, one column under the next
 * in document order, by a container query rather than a viewport one — the body is what the
 * columns share, and how much of the viewport the rail or the palette has taken is not the
 * viewport's business. The tracks travel as a CSS variable so the query can override them with a
 * class; an inline `grid-template-columns` could not be overridden by anything. The threshold: at
 * `md` beside the 288px speed rail the body is ~480px, and four quarter-columns need ~600px before
 * each can hold one 110px pad inside its padding and border. Edit mode stacks too (the 360px
 * palette leaves the body ~408px at `md`), and a gutter then reads as a strip *between* two stacked
 * columns — still "the new column before column N", which is all its drop id ever said.
 *
 * The gutters between columns are the *new column* drop zone. They are in the DOM for the whole of
 * edit mode rather than appearing when a bank is lifted — a droppable that mounts mid-drag has to
 * be measured mid-drag — and only their paint is conditional.
 */

/** Below this many pixels of body width the columns of a row stack. See the docblock above. */
export const STACK_BELOW_PX = 600

/** The narrowest an editing column may be drawn: the bank header's controls fit above it, not below. */
export const EDIT_COLUMN_MIN_PX = 150

function BuskGutter({ row, column }: { row: number; column: number }) {
  const { editing, source } = useBuskEdit()
  const draggingBank = source?.type === 'busk-bank'
  const { setNodeRef, isOver } = useDroppable({
    id: buskGutterId(row, column),
    data: {
      type: 'busk-drop',
      target: { kind: 'new-column', row, column },
      depth: DROP_DEPTH.gutter,
    } satisfies BuskDropData,
    disabled: !editing || !draggingBank,
  })

  return (
    <div
      ref={setNodeRef}
      aria-hidden
      className={cn(
        'rounded transition-colors @max-[600px]:h-5',
        draggingBank && 'border-2 border-dashed',
        draggingBank && (isOver ? 'border-primary bg-primary/10' : 'border-border/70'),
      )}
    />
  )
}

function PageRow({
  row,
  rowIndex,
  behaviour,
}: {
  row: BuskRow
  rowIndex: number
  behaviour: PadBehaviour
}) {
  const { editing, commit } = useBuskEdit()

  // Editing, every column has a **floor** under its share: the bank header's grip, its name field
  // and its controls need ~150px inside the bank's padding, and a quarter-width column of a
  // four-column row beside the palette was ~120 — the controls ran past the bank's border. With
  // the floor the row is wider than the body where it must be and the body scrolls sideways, which
  // is the honest failure. Play mode keeps bare shares: a pad wraps and a name truncates.
  const tracks = editing
    ? [
        ...row.columns.flatMap((column) => ['20px', `minmax(${EDIT_COLUMN_MIN_PX}px, ${column.width}fr)`]),
        '20px',
        // The `+ Bank` slot ends the row, and adds a *column* holding one empty bank — its label
        // says Bank because that is what the operator is making; its position says Column because
        // stacking inside an existing column is what the drag does.
        '3fr',
      ]
    : row.columns.map((column) => `${column.width}fr`)

  return (
    <div
      className="grid gap-3 [grid-template-columns:var(--busk-tracks)] @max-[600px]:grid-cols-1"
      style={{ '--busk-tracks': tracks.join(' ') } as React.CSSProperties}
      data-testid={`busk-row-${rowIndex}`}
    >
      {row.columns.map((column, columnIndex) => (
        <Fragment key={column.uuid ?? column.localKey ?? `col-${columnIndex}`}>
          {editing && <BuskGutter row={rowIndex} column={columnIndex} />}
          <div className="flex min-w-0 flex-col gap-3">
            {column.banks.map((bank, bankIndex) => (
              <BuskBankCluster
                key={bank.uuid ?? bank.localKey ?? `bank-${bankIndex}`}
                bank={bank}
                at={{ row: rowIndex, column: columnIndex, bank: bankIndex }}
                behaviour={behaviour}
              />
            ))}
          </div>
        </Fragment>
      ))}
      {editing && (
        <>
          <BuskGutter row={rowIndex} column={row.columns.length} />
          <button
            type="button"
            onClick={() => commit((page) => addBankColumn(page, rowIndex))}
            className="grid min-h-[100px] place-items-center rounded-[10px] border border-dashed text-[13px] font-medium text-muted-foreground hover:bg-accent/40 hover:text-foreground @max-[600px]:min-h-12"
          >
            <span className="flex items-center gap-1.5">
              <Plus className="size-3.5" /> Bank
            </span>
          </button>
        </>
      )}
    </div>
  )
}

function NewRowStrip() {
  const { editing, source, commit } = useBuskEdit()
  const draggingBank = source?.type === 'busk-bank'
  const { setNodeRef, isOver } = useDroppable({
    id: BUSK_NEW_ROW_ID,
    data: { type: 'busk-drop', target: { kind: 'new-row' }, depth: DROP_DEPTH.newRow } satisfies BuskDropData,
    disabled: !editing || !draggingBank,
  })
  if (!editing) return null

  return (
    <button
      ref={setNodeRef}
      type="button"
      onClick={() => commit((page) => addRow(page))}
      className={cn(
        'grid min-h-12 place-items-center rounded-[10px] border border-dashed text-xs font-medium text-muted-foreground hover:bg-accent/40 hover:text-foreground',
        draggingBank && 'border-2',
        draggingBank && (isOver ? 'border-primary bg-primary/10 text-primary' : 'border-border'),
      )}
    >
      <span className="flex items-center gap-1.5">
        <Plus className="size-3.5" /> Row
      </span>
    </button>
  )
}

export function BuskPageBody({
  page,
  behaviour,
}: {
  page: BuskPageModel
  behaviour: PadBehaviour
}) {
  const { editing } = useBuskEdit()

  return (
    <div data-busk-page-body className="@container flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto px-4 pt-3 pb-4">
      {page.rows.map((row, index) => (
        // A row has no id of its own — it is list position on both sides of the wire — so its key
        // comes from the first column, which the server's own rule guarantees exists. An index key
        // would remount every bank below a row that was spliced out of the middle.
        <PageRow
          key={row.columns[0]?.uuid ?? row.columns[0]?.localKey ?? `row-${index}`}
          row={row}
          rowIndex={index}
          behaviour={behaviour}
        />
      ))}
      {page.rows.length === 0 && !editing && (
        <p className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
          This page is empty. Choose <span className="font-medium">Edit layout</span> to put
          something on it.
        </p>
      )}
      <NewRowStrip />
    </div>
  )
}
