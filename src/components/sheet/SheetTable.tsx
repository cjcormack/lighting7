import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useVirtualizer } from '@tanstack/react-virtual'
import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core'
import { SortableContext, useSortable, verticalListSortingStrategy } from '@dnd-kit/sortable'
import { GripVertical, Pencil } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'
import { useScrollEdges } from '@/hooks/useScrollEdges'
import { useStableCallback } from '@/hooks/useStableCallback'
import { cellSelectionClass } from './cellSelection'
import { describeCellScope, type CellRef, type RowId } from './cellSelectionModel'
import { useCellMarquee } from './useCellMarquee'
import type { CellSelection } from './useCellSelection'
import { useRevealCell } from './revealCell'
import type { CellOpenRequest } from './useCellEditorRequests'
import { batchLabelOf, type SheetCellProps, type SheetColumn, type SheetRow } from './sheetModel'

/** Every row of every sheet is 36px, the DMX sheet's 56 — see `rowHeight`. */
export const SHEET_ROW_HEIGHT = 36
import {
  SHEET_DIVIDER_CLASS,
  SHEET_HEADER_CELL_CLASS,
  SHEET_HEADER_ROW_CLASS,
  SHEET_ROW_CLASS,
  SHEET_SCROLLER_CLASS,
  SHEET_STICKY_CELL_CLASS,
} from './sheetFrame'

/**
 * What a row hands its grip: dnd-kit's activator, spread onto the handle button.
 *
 * `attributes` and `listeners` go on the **handle** rather than the row, which is what makes the
 * drag handle-based — a press anywhere else on the row is still a selection or a marquee.
 */
export interface SheetDragHandle {
  ref: (el: HTMLElement | null) => void
  listeners: Record<string, unknown>
  attributes: Record<string, unknown>
  isDragging: boolean
}

export interface SheetTableProps<Row extends SheetRow, C extends string> {
  rows: readonly Row[]
  columns: readonly SheetColumn<Row, C>[]
  /**
   * The sticky first column: its header label, its track, what it draws for a row, and whether a
   * drag from it selects rows. The DMX sheet's row head is a label only — that grid is all cells,
   * so it has no row axis and a press on the head is a press on nothing.
   */
  firstColumn: {
    label: string
    width: string
    render: (row: Row, selected: boolean) => React.ReactNode
    /**
     * Open the row's record — the patch editor, a library record's sheet. Given, the column draws a
     * **pencil** after [render] (shown on hover, focus and selection) and a sheet mounting
     * `useSheet` with the same callback as `onOpenRow` opens it from **⏎ over one row** too
     * (library-sheets plan D4). The patch list hand-rolled this pencil until the library sheets.
     */
    onOpen?: (row: Row) => void
    /** The pencil's accessible name and tooltip — `Edit Front PAR`. Defaults to `Open`. */
    openLabel?: (row: Row) => string
  }
  /**
   * The sheet has a row axis: a click or a drag on the first column selects rows, and the first
   * column hangs `data-grid-name-header`. `useSheet`'s `tableProps` carries it from the one place a
   * surface states it — `useSheet`'s own `selectsRows`, which also decides whether ⌘A and ↑ / ↓
   * are heard — so the pointer and the keyboard cannot disagree about whether rows are selectable.
   * False is the DMX sheet, where every press is a cell press. Defaults to true.
   */
  selectsRows?: boolean
  /** The height of every row, dividers included. 36 everywhere but the DMX sheet's 56. */
  rowHeight?: number
  /** Below this the table needs its columns' minimum widths; the sum of the tracks' floors. */
  minWidth?: string
  isSelected: (id: RowId) => boolean
  /** First-column click — the caller derives the intent from the mouse event. */
  onRowClick: (id: RowId, e: React.MouseEvent) => void
  /** A click landed on this cell: the caller selects the one cell. */
  onBeginCellEdit: (row: Row, col: C) => void
  /** A cell's editor committed: the caller fans it over the selection. */
  onCellCommit: (row: Row, col: C, value: unknown) => void
  /** How many rows a commit from this cell lands on. */
  batchCountFor: (row: Row, col: C) => number
  /** Those rows, in visible order — for an editor that previews its landing. */
  batchRowsFor: (row: Row, col: C) => readonly Row[]
  /** The batch's skipped rows as a read-out sentence, or null — `SheetCellProps.skipped`. */
  skippedFor?: (row: Row, col: C) => string | null
  /**
   * What a row is called on this sheet — `cue`, `fixture`, `channel` — for the editors' label
   * line (`SheetCellProps.batchLabel`). Defaults to `row`.
   */
  batchNoun?: string
  /** The surface has made this cell inert — a locked show, an offline desk. */
  cellDisabled?: (row: Row, col: C) => boolean
  /** Classes on the whole row — the cue sheet's live green and next blue. */
  rowClass?: (row: Row) => string | undefined
  /**
   * Reorder rows by dragging a grip in the first column. Present makes this sheet sortable; the
   * grip is drawn (and the drag armed) only while [enabled], so a surface with a read-only mode
   * keeps the `DndContext` mounted and turns the rows off through dnd-kit's own `disabled` — see
   * `StackDetail`, which learned that unmounting the context breaks every row instead.
   *
   * `onReorder` gets **every** row id in its new order, dividers included: on the cue sheet a
   * separator is a row of the stack like any other and moves with them.
   */
  rowDrag?: {
    enabled: boolean
    onReorder: (ids: RowId[]) => void
  }
  cellSelection: CellSelection<C>
  onRowMarquee?: (ids: RowId[]) => void
  keyboardOpen?: CellOpenRequest<C> | null
  closeEditorCell?: CellRef<C> | null
  editorAnchorRef?: React.RefObject<HTMLElement | null>
  onMarqueeDragChange?: (dragging: boolean) => void
  onBackgroundClick?: () => void
  selectionEmpty?: boolean
  scrollToRowId?: RowId | null
  onScrolledToRow?: () => void
  /**
   * A cell to scroll into view by the least move — the arrow keys' target (`useRevealCell`),
   * cleared via [onRevealedCell]. Where [scrollToRowId] centres a row it was sent to, this leaves
   * the sheet still while the cell is already on screen, as a spreadsheet's arrows do.
   */
  revealCell?: CellRef<C> | null
  onRevealedCell?: () => void
}

/**
 * The sheet every surface mounts: a 30px uppercase header, 36px rows, a sticky first column with
 * the selection bar in it, the marquee, the scope chip and the sideways-scroll fade
 * (CLAUDE.md §Sheet kit). It is `FixturesTable`'s anatomy with the fixture-specific half — the
 * row model, ownership, scope — left to that table; the two share `useCellMarquee`, the selection
 * models, the editor surface and the DOM contract (`data-grid-header`, `data-column-header`,
 * `data-grid-name-header`, `data-row-id`, `data-cell`) so the keyboard and the marquee address
 * cells on every sheet the same way.
 *
 * Plain CSS-grid divs rather than a `<table>` — table semantics fight row virtualization.
 */
export function SheetTable<Row extends SheetRow, C extends string>({
  rows,
  columns,
  firstColumn,
  selectsRows = true,
  rowHeight = SHEET_ROW_HEIGHT,
  minWidth,
  isSelected,
  onRowClick,
  onBeginCellEdit,
  onCellCommit,
  batchCountFor,
  batchRowsFor,
  skippedFor,
  batchNoun = 'row',
  cellDisabled,
  rowClass,
  rowDrag,
  cellSelection,
  onRowMarquee,
  keyboardOpen,
  closeEditorCell,
  editorAnchorRef,
  onMarqueeDragChange,
  onBackgroundClick,
  selectionEmpty,
  scrollToRowId,
  onScrolledToRow,
  revealCell,
  onRevealedCell,
}: SheetTableProps<Row, C>) {
  const scrollRef = useRef<HTMLDivElement>(null)

  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => rowHeight,
    overscan: 8,
  })

  useEffect(() => {
    if (!scrollToRowId) return
    const index = rows.findIndex((row) => row.id === scrollToRowId)
    if (index >= 0) {
      virtualizer.scrollToIndex(index, { align: 'center' })
      onScrolledToRow?.()
    }
  }, [scrollToRowId, rows, virtualizer, onScrolledToRow])

  const gridTemplateColumns = useMemo(
    () => `${firstColumn.width} ${columns.map((c) => c.width).join(' ')}`,
    [firstColumn.width, columns],
  )
  const visibleColumns = useMemo(() => columns.map((c) => c.key), [columns])
  const columnByKey = useMemo(() => new Map(columns.map((c) => [c.key, c])), [columns])
  const labelFor = useCallback((col: C) => columnByKey.get(col)?.label ?? col, [columnByKey])

  // The one-shot open and close requests, dropped by this component rather than by the cell that
  // acts on them — see `FixturesTable`'s `autoOpenCell` for why a standing request is a trap.
  const [autoOpenCell, setAutoOpenCell] = useState<CellOpenRequest<C> | null>(null)
  useEffect(() => {
    if (autoOpenCell) setAutoOpenCell(null)
  }, [autoOpenCell])
  useEffect(() => {
    if (keyboardOpen) setAutoOpenCell({ ...keyboardOpen })
  }, [keyboardOpen])
  const [closeCell, setCloseCell] = useState<CellRef<C> | null>(null)
  useEffect(() => {
    if (closeCell) setCloseCell(null)
  }, [closeCell])
  useEffect(() => {
    if (closeEditorCell) setCloseCell({ ...closeEditorCell })
  }, [closeEditorCell])

  const onEmptyCellClick = useStableCallback(onBackgroundClick)

  const marquee = useCellMarquee<C, Row>({
    scrollRef,
    rows,
    rowHeight,
    isSelectableRow: (row) => row.divider == null,
    visibleColumns,
    cellSelection,
    isRowSelected: isSelected,
    onRowMarquee: selectsRows ? onRowMarquee : undefined,
    onDragChange: onMarqueeDragChange,
  })

  const { right: moreColumnsRight, attach: attachScroller } = useScrollEdges(scrollRef, {
    horizontalOnly: true,
  })

  // A distance constraint, not a delay: the grip is its own target, so a press on it is never
  // ambiguous — but a click that moves a pixel should still be a click.
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor),
  )
  const rowIds = useMemo(() => rows.map((row) => row.id), [rows])
  const scrollToIndexAuto = useCallback(
    (index: number) => virtualizer.scrollToIndex(index, { align: 'auto' }),
    [virtualizer],
  )
  useRevealCell(scrollRef, rowIds, scrollToIndexAuto, revealCell, onRevealedCell)
  const onReorder = rowDrag?.onReorder
  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event
      if (!over || active.id === over.id || !onReorder) return
      const from = rowIds.indexOf(String(active.id))
      const to = rowIds.indexOf(String(over.id))
      if (from < 0 || to < 0) return
      const next = [...rowIds]
      next.splice(to, 0, ...next.splice(from, 1))
      onReorder(next)
    },
    [onReorder, rowIds],
  )

  return (
    // Always the fill arm: the sheet owns the remaining height of a full-height column
    // (`SheetPage`) and is the page's one scroller. There was a second arm — `rounded-md border`
    // capped at `calc(100vh - 14rem)` for a sheet embedded in a scrolling page — and nothing
    // embeds a sheet in a scrolling page any more.
    <div className="relative flex min-h-0 flex-1 flex-col">
      <div
        ref={attachScroller}
        // The frame is `sheetFrame.ts`'s (CLAUDE.md §List shell): the sheet ground on the scroller,
        // so the sticky first column and the body are one colour, and no top line — the row
        // above owns it.
        className={SHEET_SCROLLER_CLASS}
        onClick={(e) => {
          if (!onBackgroundClick) return
          const target = e.target as Element
          // React bubbles a synthetic event up the *React* tree, portals included — and every
          // cell editor is portalled to `body`. The DOM subtree is the question here.
          if (!e.currentTarget.contains(target)) return
          if (target.closest('[data-row-id], [data-grid-header]')) return
          onBackgroundClick()
        }}
      >
        <div style={{ minWidth }}>
          <div
            data-grid-header
            className={SHEET_HEADER_ROW_CLASS}
            style={{ gridTemplateColumns }}
          >
            <div
              // The marquee measures the first column from this — see `useCellMarquee`. A sheet
              // whose first column selects no rows hangs no name header, and every press is a cell
              // press.
              {...(selectsRows ? { 'data-grid-name-header': true } : {})}
              // `px-2` *after* the header class — `cn` is tailwind-merge and the header class carries
              // `px-1.5`, so this order is what keeps the label on the row cells' 8px inset.
              className={cn(SHEET_STICKY_CELL_CLASS, SHEET_HEADER_CELL_CLASS, 'px-2')}
            >
              {firstColumn.label}
            </div>
            {columns.map((column) => (
              <div
                key={column.key}
                // The marquee measures its bands from this mark — see `useCellMarquee` — so a
                // read-out column hangs none: a rectangle over Book or Type selects nothing there,
                // rather than cells with no editor that inflate the count and refuse Clear.
                {...(column.cell != null ? { 'data-column-header': column.key } : {})}
                className={cn(SHEET_HEADER_CELL_CLASS, column.align === 'right' && 'text-right')}
              >
                {column.label}
              </div>
            ))}
          </div>

          <SheetRowsDnd
            enabled={rowDrag != null}
            sensors={sensors}
            rowIds={rowIds}
            onDragEnd={handleDragEnd}
          >
          <div
            className="select-none touch-manipulation [-webkit-touch-callout:none]"
            style={{ height: `${virtualizer.getTotalSize()}px`, position: 'relative' }}
            onPointerDown={marquee.onPointerDown}
            onPointerMove={marquee.onPointerMove}
            onPointerUp={marquee.onPointerUp}
            onPointerCancel={marquee.onPointerUp}
            onPointerLeave={marquee.onPointerLeave}
          >
            {marquee.band && (
              <div
                aria-hidden="true"
                data-testid="cell-marquee"
                className="pointer-events-none absolute z-30 rounded-sm border-2 border-foreground bg-foreground/5 shadow-[0_0_0_1px_var(--background)]"
                style={marquee.band}
              >
                <span className="absolute -left-px -top-px size-[7px] rounded-[1px] bg-foreground" />
                <span className="absolute -bottom-px -right-px size-[7px] rounded-[1px] bg-foreground" />
              </div>
            )}
            {virtualizer.getVirtualItems().map((virtualRow) => {
              const row = rows[virtualRow.index]
              const view = (dragHandle?: SheetDragHandle) => (
                <SheetRowView
                  row={row}
                  columns={columns}
                  firstColumn={firstColumn}
                  selectsRows={selectsRows}
                  gridTemplateColumns={gridTemplateColumns}
                  selected={row.divider == null && isSelected(row.id)}
                  rowClass={rowClass?.(row)}
                  onRowClick={onRowClick}
                  onBeginCellEdit={onBeginCellEdit}
                  onCellCommit={onCellCommit}
                  batchCountFor={batchCountFor}
                  batchNoun={batchNoun}
                  batchRowsFor={batchRowsFor}
                  skippedFor={skippedFor}
                  cellDisabled={cellDisabled}
                  cellSelection={cellSelection}
                  autoOpenCol={autoOpenCell?.rowId === row.id ? autoOpenCell.col : null}
                  autoOpenSeed={autoOpenCell?.rowId === row.id ? autoOpenCell.seed : null}
                  autoOpenAtButton={autoOpenCell?.rowId === row.id && autoOpenCell.atButton}
                  autoCloseCol={closeCell?.rowId === row.id ? closeCell.col : null}
                  selectionEmpty={selectionEmpty}
                  editorAnchorRef={editorAnchorRef}
                  onEmptyCellClick={onEmptyCellClick}
                  dragHandle={dragHandle}
                />
              )
              // The branch is the *sheet's*, not the row's — `rowDrag` is either given for the life
              // of this table or never — so the hooks inside `SortableVirtualRow` keep a stable
              // order across every render and every row.
              return rowDrag ? (
                <SortableVirtualRow
                  key={row.id}
                  id={row.id}
                  start={virtualRow.start}
                  height={rowHeight}
                  disabled={!rowDrag.enabled}
                  render={view}
                />
              ) : (
                <div
                  key={row.id}
                  className="absolute inset-x-0"
                  // The virtualiser's own recommendation, and untouched: a sheet with no row drag
                  // has nothing else competing for `transform`. See `SortableVirtualRow` for why the
                  // sortable branch cannot use it.
                  style={{ height: `${rowHeight}px`, transform: `translateY(${virtualRow.start}px)` }}
                >
                  {view()}
                </div>
              )
            })}
          </div>
          </SheetRowsDnd>
        </div>

        {/* The scope chip, following the pointer. Portalled to `body` so a `@container` ancestor
            cannot become its containing block — see `FixturesTable`. */}
        {marquee.chip &&
          (marquee.rowCount != null ? marquee.rowCount > 0 : cellSelection.count > 0) &&
          createPortal(
            <div
              className="pointer-events-none fixed z-50 flex items-center gap-1.5 rounded-full bg-primary px-2.5 py-1 text-[11px] font-medium text-primary-foreground shadow-lg"
              style={{ left: marquee.chip.x + 12, top: marquee.chip.y + 12 }}
            >
              {marquee.rowCount != null ? (
                <span className="font-mono tabular-nums">
                  {marquee.rowCount} row{marquee.rowCount === 1 ? '' : 's'}
                </span>
              ) : (
                <>
                  <span className="font-mono tabular-nums">
                    {cellSelection.count} cell{cellSelection.count === 1 ? '' : 's'}
                  </span>
                  <span className="opacity-60">·</span>
                  <span>{describeCellScope(cellSelection.cells, labelFor)}</span>
                </>
              )}
            </div>,
            document.body,
          )}
      </div>
      {moreColumnsRight && (
        <div
          aria-hidden="true"
          data-testid="column-scroll-fade"
          className="pointer-events-none absolute inset-y-0 right-0 w-6 bg-gradient-to-l from-background to-transparent"
        />
      )}
    </div>
  )
}

/**
 * The sortable half, mounted only where a surface asked for it.
 *
 * A component rather than a branch inside the table's body, so the `DndContext` is absent entirely
 * for the sheets that do not reorder — the patch list and the DMX sheet have no row order of their
 * own to change, and a context they never use is a context that can still swallow a pointer event.
 */
function SheetRowsDnd({
  enabled,
  sensors,
  rowIds,
  onDragEnd,
  children,
}: {
  enabled: boolean
  sensors: ReturnType<typeof useSensors>
  rowIds: readonly RowId[]
  onDragEnd: (event: DragEndEvent) => void
  children: React.ReactNode
}) {
  if (!enabled) return <>{children}</>
  return (
    <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={onDragEnd}>
      {/* Every row id, not just the virtualised window: a drag that scrolls past the window's edge
          still has to land somewhere dnd-kit knows about. */}
      <SortableContext items={rowIds as string[]} strategy={verticalListSortingStrategy}>
        {children}
      </SortableContext>
    </DndContext>
  )
}

/**
 * One virtualised row, sortable.
 *
 * The virtualiser positions the row with `translateY`, and dnd-kit wants to move it too, so the two
 * are **composed on one element** rather than nested — the sortable node has to be the element
 * dnd-kit measures, and that is the positioned wrapper. Order matters: the virtualiser's offset is
 * where the row lives, the sortable transform is where the drag has taken it from there.
 */
function SortableVirtualRow({
  id,
  start,
  height,
  disabled,
  render,
}: {
  id: RowId
  start: number
  height: number
  disabled: boolean
  render: (handle?: SheetDragHandle) => React.ReactNode
}) {
  const { setNodeRef, setActivatorNodeRef, listeners, attributes, transform, transition, isDragging } =
    useSortable({ id, disabled })
  return (
    <div
      ref={setNodeRef}
      className={cn('absolute inset-x-0', isDragging && 'z-30')}
      style={{
        height: `${height}px`,
        // **`top`, not the virtualiser's usual `translateY`.** This is the node dnd-kit measures,
        // and it measures droppables with transforms discounted — so rows positioned *only* by a
        // transform all measure at the container's origin, every centre-distance ties, and
        // `closestCenter`'s stable sort hands back the rows in DOM order on every frame. The
        // symptom is not "no drag": dragging *up* still lands, because for an upward drag DOM order
        // and distance order agree; dragging down picks the row you started on and drops nothing.
        // Positioning with `top` leaves `transform` to the sortable alone, which is what it wants.
        top: `${start}px`,
        transform: transform ? `translate3d(0, ${transform.y}px, 0)` : undefined,
        transition,
        opacity: isDragging ? 0.85 : undefined,
      }}
    >
      {/* No handle while the drag is off: a grip that cannot be dragged is worse than no grip, and
          `disabled` on the sortable is what makes the drag genuinely impossible rather than merely
          unadvertised — both halves are needed, as `StackDetail` found. */}
      {render(
        disabled
          ? undefined
          : {
              ref: setActivatorNodeRef,
              listeners: (listeners ?? {}) as Record<string, unknown>,
              attributes: attributes as unknown as Record<string, unknown>,
              isDragging,
            },
      )}
    </div>
  )
}

/**
 * The grip. Only where the surface armed the drag — a disabled sortable renders none at all, since
 * a handle that cannot be dragged is worse than no handle.
 *
 * `stopPropagation` on the press: the rows wrapper above is the marquee's `pointerdown` target, and
 * React bubbles a synthetic event whatever the DOM says, so without it starting a drag would also
 * start a rectangle.
 */
function RowGrip({ handle }: { handle: SheetDragHandle }) {
  return (
    <button
      type="button"
      ref={handle.ref}
      {...handle.attributes}
      {...handle.listeners}
      onPointerDown={(e) => {
        e.stopPropagation()
        ;(handle.listeners.onPointerDown as ((e: React.PointerEvent) => void) | undefined)?.(e)
      }}
      onClick={(e) => e.stopPropagation()}
      aria-label="Reorder row"
      title="Drag to reorder"
      className="relative -ml-1 flex h-full w-4 shrink-0 cursor-grab touch-none items-center justify-center text-muted-foreground/50 hover:text-foreground active:cursor-grabbing"
    >
      <GripVertical className="size-3.5" />
    </button>
  )
}

/**
 * The first column's pencil: opens the row's record (`firstColumn.onOpen`). Drawn on hover and
 * focus, as the patch list's hand-rolled one was, **and while the row is selected**, so a touch
 * screen — which has no hover — reaches it by tapping the row first.
 *
 * `data-row-open` is what keeps it out of the ⏎-opens-row arm's exemption (Enter on the pencil is
 * its own press), and the click stops at it so the press does not also select the row. The label is a
 * Radix tooltip, as the patch list's hand-rolled pencil had — themed, and shown on keyboard focus as
 * well as hover, which a native `title` is not.
 */
function RowOpenButton({ label, selected, onOpen }: { label: string; selected: boolean; onOpen: () => void }) {
  return (
    <span
      className={cn(
        'relative shrink-0 items-center',
        selected ? 'inline-flex' : 'hidden group-hover/row:inline-flex group-focus-within/row:inline-flex',
      )}
      onClick={(e) => e.stopPropagation()}
    >
      <Tooltip>
        <TooltipTrigger asChild>
          <Button data-row-open variant="outline" size="icon" className="size-7" onClick={onOpen} aria-label={label}>
            <Pencil className="size-3.5" />
          </Button>
        </TooltipTrigger>
        <TooltipContent>{label}</TooltipContent>
      </Tooltip>
    </span>
  )
}

interface SheetRowViewProps<Row extends SheetRow, C extends string> {
  row: Row
  columns: readonly SheetColumn<Row, C>[]
  firstColumn: SheetTableProps<Row, C>['firstColumn']
  selectsRows: boolean
  gridTemplateColumns: string
  selected: boolean
  rowClass?: string
  onRowClick: (id: RowId, e: React.MouseEvent) => void
  onBeginCellEdit: (row: Row, col: C) => void
  onCellCommit: (row: Row, col: C, value: unknown) => void
  batchCountFor: (row: Row, col: C) => number
  batchRowsFor: (row: Row, col: C) => readonly Row[]
  skippedFor?: (row: Row, col: C) => string | null
  batchNoun: string
  cellDisabled?: (row: Row, col: C) => boolean
  cellSelection: CellSelection<C>
  autoOpenCol: C | null
  autoOpenSeed: string | null
  autoOpenAtButton: boolean
  autoCloseCol: C | null
  selectionEmpty?: boolean
  editorAnchorRef?: React.RefObject<HTMLElement | null>
  onEmptyCellClick: () => void
  dragHandle?: SheetDragHandle
}

function SheetRowViewInner<Row extends SheetRow, C extends string>({
  row,
  columns,
  firstColumn,
  selectsRows,
  gridTemplateColumns,
  selected,
  rowClass,
  onRowClick,
  onBeginCellEdit,
  onCellCommit,
  batchCountFor,
  batchRowsFor,
  skippedFor,
  batchNoun,
  cellDisabled,
  cellSelection,
  autoOpenCol,
  autoOpenSeed,
  autoOpenAtButton,
  autoCloseCol,
  selectionEmpty,
  editorAnchorRef,
  onEmptyCellClick,
  dragHandle,
}: SheetRowViewProps<Row, C>) {
  if (row.divider != null) {
    return (
      // `data-row-id` here too: a divider is a row of the sheet, not empty space — a tap on it must
      // not drop the selection.
      <div
        className={cn(SHEET_DIVIDER_CLASS, 'gap-3 px-3')}
        data-row-id={row.id}
      >
        {dragHandle && <RowGrip handle={dragHandle} />}
        <span className="h-px flex-1 bg-border" />
        <span className="rounded border bg-card px-2 py-px text-xs font-medium text-muted-foreground">
          {row.divider}
        </span>
        <span className="h-px flex-1 bg-border" />
      </div>
    )
  }

  return (
    <div
      className={cn(
        // `auto-rows-[minmax(0,1fr)]`: the row is a fixed height the virtualiser positions, so the
        // track has to be that height and not the tallest cell's min-content. Without the floor of
        // zero a single cell with an outsized intrinsic height grows the track past the row box and
        // every cell in the row is laid out below the row's own centre.
        SHEET_ROW_CLASS,
        'auto-rows-[minmax(0,1fr)]',
        selected ? 'bg-foreground/[0.06]' : 'hover:bg-accent/30',
        rowClass,
      )}
      style={{ gridTemplateColumns }}
      data-state={selected ? 'selected' : undefined}
      data-row-id={row.id}
    >
      <div
        // The ⏎-opens-row arm's exemption reads this mark — see `firstColumnOwnsKeyTarget`.
        data-first-column
        className={cn(
          SHEET_STICKY_CELL_CLASS,
          'flex h-full items-center gap-1.5 px-2',
          selectsRows && 'cursor-pointer',
        )}
        onClick={selectsRows ? (e) => onRowClick(row.id, e) : undefined}
      >
        {/* The selection tint and the 3px edge survive the opaque sticky background by being
            drawn on this overlay rather than on the row — see `FixturesTable`. */}
        <div
          className={cn(
            'pointer-events-none absolute inset-0',
            selected ? 'bg-foreground/[0.06] shadow-[inset_3px_0_0_var(--foreground)]' : 'group-hover/row:bg-accent/30',
          )}
        />
        {dragHandle && <RowGrip handle={dragHandle} />}
        {firstColumn.render(row, selected)}
        {firstColumn.onOpen && (
          <RowOpenButton
            label={firstColumn.openLabel?.(row) ?? 'Open'}
            selected={selected}
            onOpen={() => firstColumn.onOpen?.(row)}
          />
        )}
      </div>

      {columns.map((column) => {
        const value = column.value(row)
        if (value === undefined || column.cell == null) {
          // A read-out, or nothing here to set. A blank cell clears the selection the way the
          // grid's own background does; a read-out is the column's own control.
          return (
            <div
              key={column.key}
              // `overflow-hidden`: a read-out draws whatever the column hands it, and a wrapped
              // line pushes the grid row past its fixed height and paints over the row below
              // (the cue sheet's "top of p. 8"). It cannot go on the *row* — an `overflow` there
              // makes it a scroll container and the sticky first column would stick to it.
              className={cn(
                'flex h-full min-w-0 items-center overflow-hidden',
                column.align === 'right' && 'justify-end',
              )}
              onClick={column.display == null ? onEmptyCellClick : undefined}
            >
              {column.display?.(row)}
            </div>
          )
        }
        const selectedCell = cellSelection.isSelected(row.id, column.key)
        const disabled = cellDisabled?.(row, column.key) ?? false
        const batchCount = batchCountFor(row, column.key)
        // Only a selected cell has a batch to skip from; the rest are a batch of themselves.
        const skipped = selectedCell || selected ? (skippedFor?.(row, column.key) ?? null) : null
        const props: SheetCellProps<unknown> = {
          value,
          label: column.label,
          batchCount,
          batchLabel: batchLabelOf(batchCount, batchNoun),
          batchRows: () => batchRowsFor(row, column.key),
          skipped,
          disabled,
          autoOpen: autoOpenCol === column.key,
          autoClose: autoCloseCol === column.key,
          anchorAtButton: autoOpenCol === column.key && autoOpenAtButton,
          keyboardSeed: autoOpenCol === column.key ? autoOpenSeed : null,
          selectionEmpty,
          editorAnchorRef,
          onBeginEdit: () => onBeginCellEdit(row, column.key),
          onCommit: (next) => onCellCommit(row, column.key, next),
        }
        return (
          <div
            key={column.key}
            data-cell={column.key}
            className={cn(
              // The marks gutter: 18px on the right for the corner glyphs, the same number the
              // programmer's cells reserve — see `FixturesTable`. A column that draws no glyph
              // asks for none and is padded evenly instead, so its own border and the selection
              // overlay are one box — see `SheetColumn.gutter`.
              'relative h-full min-w-0',
              column.gutter === false ? 'p-0.5' : 'py-0.5 pr-[18px]',
              column.cellClass?.(row),
              cellSelectionClass(selectedCell, column.gutter !== false),
              // Read-only for the pointer; the cell's trigger takes `disabled` for the keyboard.
              disabled && 'pointer-events-none',
            )}
            title={column.cellTitle?.(row)}
          >
            {column.cell(row, props)}
          </div>
        )
      })}
    </div>
  )
}

const SheetRowView = React.memo(SheetRowViewInner) as typeof SheetRowViewInner
