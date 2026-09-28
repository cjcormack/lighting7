import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { cn } from '@/lib/utils'
import {
  SHEET_DIVIDER_CLASS,
  SHEET_HEADER_CELL_CLASS,
  SHEET_HEADER_ROW_CLASS,
  SHEET_ROW_CLASS,
  SHEET_SCROLLER_CLASS,
  SHEET_STICKY_CELL_CLASS,
} from '../sheet/sheetFrame'
import { useVirtualizer } from '@tanstack/react-virtual'
import { useScrollEdges } from '@/hooks/useScrollEdges'
import { useStableCallback } from '@/hooks/useStableCallback'
import { AudioWaveform, ChevronDown, ChevronRight, Info, Layers } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { LocateButton } from '../fixtures/LocateButton'
import { COLUMN_DEFS, columnFamily, columnLabel } from './columns'
import { rowLocateTarget } from './rowModel'
import { buildRowCells, useRowValues } from './useRowValues'
import { useScopedRowValues } from './useScopedRowValues'
import { parsePropertyMask } from '../../lib/attributeFamily'
import { AddToTargetsButton } from '../programmer/AddToTargetsButton'
import { useLookRowStore } from '../programmer/LookRowStore'
import { useFocusedTemplateLayer } from '../programmer/FocusedTemplateLayer'
import { useProgrammerScope, useProgrammerScopeActions } from '../programmer/ProgrammerScope'
import { useEditorContext } from '../programmer/EditorContext'
import { effectSpeedLabel } from '../fx/fxConstants'
import { useIsDeskConnected } from '../../store/status'
import { DESK_OFFLINE_LABEL } from '../../api/wsGesture'
import { useRowOwnership } from './useRowOwnership'
import { applyStagedValue, layerCellClass, ownershipCellClass, ownershipTitle } from './ownership'
import { cellSelectionClass } from '../sheet/cellSelection'
import { useCellMarquee } from '../sheet/useCellMarquee'
import { describeCellScope, type CellRef } from '../sheet/cellSelectionModel'
import type { CellSelection } from '../sheet/useCellSelection'
import { useRevealCell } from '../sheet/revealCell'

/** This grid's cell, over its closed column vocabulary. */
type FixtureCellRef = CellRef<ColumnKey>
import type { CellClickBehaviour } from '../editor/EditorSurface'
import { SliderCell } from './cells/SliderCell'
import { ColourCell } from './cells/ColourCell'
import { PositionCell } from './cells/PositionCell'
import { SettingCell } from './cells/SettingCell'
import type { ColumnKey } from './columns'
import type { CellBatch, CellCommit, FixtureRow, GroupRow, InfoRow, Row, RowId } from './rowModel'
import type { RowCell } from './useRowValues'
import type { CellOwnership } from './useRowOwnership'

const ROW_HEIGHT = 36

/** Sticky name column: 260px on a desktop, but never more than 45% of a narrow viewport. */
const NAME_COLUMN_WIDTH = 'min(45vw, 260px)'

export interface FixturesTableProps {
  rows: Row[]
  visibleColumns: readonly ColumnKey[]
  isSelected: (id: RowId) => boolean
  /** Name-cell click — the caller derives the intent from the mouse event. */
  onRowClick: (id: RowId, e: React.MouseEvent) => void
  onToggleExpand: (row: GroupRow | FixtureRow) => void
  /**
   * A click landed on this cell, and that is the *whole* of what the click does: the caller
   * selects the one cell, and the editor is opened by Set, by Enter, by a typed character or by a
   * double click instead.
   */
  onBeginCellEdit: (row: Row, col: ColumnKey) => void
  onCellCommit: (row: Row, col: ColumnKey, commit: CellCommit) => void
  /**
   * What a commit from this row's cell in this column lands on (multi-head fixtures expand per
   * element) — the count, the skipped heads and their resolutions — for the editor's label line
   * and read-out. See `CellBatch`.
   */
  batchFor: (row: Row, col: ColumnKey) => CellBatch
  /**
   * Where an edit lands, for the editors' label line: *Local*, or the focused Look's name in layer
   * scope. The container reads the scope and the Look store once and hands the word down, so no
   * row subscribes to either for it.
   */
  scopeLabel: string
  /**
   * The project the rows belong to, for the colour editor alone: its hidden appearance leaves read
   * the patch list, Recent lists templates and *Save as template…* records into it. The container
   * passes it for the programmer scope only — the two plain lists draw no template strip and get
   * none of the three — and every test mounts this table bare; a cell with none draws no Recent, a
   * disabled Save and no leaves rather than reaching for a store it has no project in.
   */
  projectId?: number
  /**
   * The colour editor's *Spread…*: hand its RGB to the selection bar's Spread panel as *From*
   * (editor-kit plan session 3). Threaded to `ColourCell` alone; the container holds the one-shot.
   */
  onSpread?: (from: { r: number; g: number; b: number }) => void
  /** Open the detail sheet for a row (group → group sheet, fixture/element →
   *  fixture sheet). */
  onShowInfo: (row: InfoRow) => void
  /** Row to scroll into view (deep-link); cleared via onScrolledToRow. */
  scrollToRowId?: RowId | null
  onScrolledToRow?: () => void
  /**
   * A cell to bring into view by the least move — the arrow keys' target — cleared via
   * [onRevealedCell]. `SheetTable`'s prop of the same name, through the same `useRevealCell`.
   */
  revealCell?: CellRef<ColumnKey> | null
  onRevealedCell?: () => void
  /**
   * Colour each cell by which layer owns it, and show the programmer's staged value while
   * blind. Opt-in: the programmer sheet wants it, the plain Fixtures / Groups lists are
   * patch-management views where provenance tinting would just be noise.
   */
  showOwnership?: boolean
  /**
   * Neither a row nor a cell is selected any more. Any open cell editor closes on the crossing
   * into this — see `useEditorOpen`, which owns the rule and the reason.
   *
   * Passed as a plain boolean rather than as a one-shot the way `autoOpenCell` is: it changes only
   * on the 0 ↔ non-0 boundary, so the rows' memo holds through every ordinary selection change,
   * and nothing has to consume it.
   */
  selectionEmpty?: boolean
  /**
   * Drag-select across cells — and with it the whole cell vocabulary: a single click selects one,
   * a double click opens its editor, and the container's keyboard and Set · Clear · Spread act on
   * the rectangle.
   *
   * **Required.** It was optional while the two plain list routes had no marquee, which left this
   * component answering a click two ways depending on who mounted it; they select cells now, and
   * `FixturesListContainer` is this table's only caller.
   */
  cellSelection: CellSelection<ColumnKey>
  /**
   * Drag-select across **rows**: a press in the sticky name column that travels selects the rows
   * the rectangle covers, exactly as one in a value column selects cells. Called with the whole
   * selection the drag has arrived at — the modifiers are already folded in, so a ⌘-drag unions
   * with what was selected when it began — and only when that list changed since the last call.
   *
   * It is what replaced the row checkbox: accumulate by ⌘-click or by dragging, and a plain drag
   * replaces, the way a spreadsheet's row header does.
   */
  onRowMarquee?: (ids: RowId[]) => void
  /**
   * The keyboard asked for a cell's editor: Enter (or a character) over a cell selection, from
   * `FixturesListContainer`'s window handler. `seed` is the character that started it, or `''` for
   * a bare Enter.
   *
   * A one-shot, like `autoOpenCell` below, which is what it is folded into — so the keyboard and a
   * released marquee open an editor by exactly one mechanism. The container is the one that knows
   * *which* cell (it owns the selection and the scope gate); this knows how to open one.
   */
  keyboardOpen?: { rowId: RowId; col: ColumnKey; seed: string; atButton: boolean } | null
  /**
   * Close the editor on this cell — the selection bar's **Set** pressed a second time.
   *
   * A one-shot of its own rather than a `null` on [keyboardOpen], which means "nobody is asking"
   * rather than "shut it". Set is the only thing that can close what it opened: clicking the Set
   * button while the editor is open does not dismiss it the way clicking the grid does, because
   * the button is this popover's own anchor.
   */
  closeEditorCell?: { rowId: RowId; col: ColumnKey } | null
  /**
   * Where a requested editor should open — the selection bar's Set button.
   *
   * Only meaningful with [cellSelection], which is what makes a click select rather than open:
   * every editor on that grid is then opened by Set (or by its key), and anchoring at the cell
   * put the panel wherever in the grid the first selected cell happened to be. See `anchorRef` on
   * `EditorSurface` for the fallback when the button is not mounted.
   */
  editorAnchorRef?: React.RefObject<HTMLElement | null>
  /**
   * A marquee drag has started or ended.
   *
   * Fires twice per gesture, never per pointer move: the container keeps the answer so the
   * programmer's selection bar can hold its place while a drag is in flight
   * (`selectionBandState`). The gesture itself stays here — only the fact of it is lifted.
   */
  onMarqueeDragChange?: (dragging: boolean) => void
  /**
   * A click landed on the grid's own background — under the last row, or beside the last column —
   * on nothing that is a row or the header.
   *
   * The container answers it with the same ladder Escape runs (cells first, rows second). It
   * exists because a phone has no Escape and no "click off" (`PD-CLEAR-SELECTION-TOUCH`): the only
   * way to drop a selection there was the bar's Deselect, and on a short list the empty space
   * under the rows is the larger target. Not fired for the click that ends a marquee — that one
   * is swallowed before it reaches here, or a drag released over empty space would clear the
   * selection it just made.
   */
  onBackgroundClick?: () => void
}

/**
 * The virtualized spreadsheet grid. Plain CSS-grid divs rather than a <table>
 * — table semantics fight row virtualization — with a sticky header and a
 * sticky-left name column. Rows subscribe to their own channels via
 * useRowValues; cells are dumb value + onCommit components.
 */
export function FixturesTable({
  rows,
  visibleColumns,
  isSelected,
  onRowClick,
  onToggleExpand,
  onBeginCellEdit,
  onCellCommit,
  batchFor,
  scopeLabel,
  projectId,
  onSpread,
  onShowInfo,
  scrollToRowId,
  onScrolledToRow,
  revealCell,
  onRevealedCell,
  showOwnership = false,
  selectionEmpty,
  cellSelection,
  onRowMarquee,
  editorAnchorRef,
  keyboardOpen,
  closeEditorCell,
  onMarqueeDragChange,
  onBackgroundClick,
}: FixturesTableProps) {
  const scrollRef = useRef<HTMLDivElement>(null)
  // One subscription for the whole grid; every row takes the answer as a prop.
  const deskConnected = useIsDeskConnected()

  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => ROW_HEIGHT,
    overscan: 8,
  })

  useEffect(() => {
    if (!scrollToRowId) return
    const index = rows.findIndex((row) => row.id === scrollToRowId)
    // Only consume the pin once the row actually exists — it may be a render
    // behind (e.g. a deep-link that just expanded the target's group).
    if (index >= 0) {
      virtualizer.scrollToIndex(index, { align: 'center' })
      onScrolledToRow?.()
    }
  }, [scrollToRowId, rows, virtualizer, onScrolledToRow])

  const rowIds = useMemo(() => rows.map((row) => row.id), [rows])
  const scrollToIndexAuto = useCallback(
    (index: number) => virtualizer.scrollToIndex(index, { align: 'auto' }),
    [virtualizer],
  )
  useRevealCell(scrollRef, rowIds, scrollToIndexAuto, revealCell, onRevealedCell)

  // The name column is the single biggest consumer of width, and 260px of a 375px phone
  // leaves room for barely one property. `min()` scales it down with the viewport without
  // needing a JS breakpoint — inline styles can't carry media queries, but they can carry
  // CSS math.
  const gridTemplateColumns = useMemo(
    () => `${NAME_COLUMN_WIDTH} repeat(${visibleColumns.length}, minmax(96px, 1fr))`,
    [visibleColumns.length],
  )

  const columnLabels = useMemo(() => {
    const byKey = new Map(COLUMN_DEFS.map((d) => [d.key, d.label]))
    return visibleColumns.map((col) => ({ col, label: byKey.get(col) ?? col }))
  }, [visibleColumns])

  /**
   * The cell whose editor the container has asked for — Enter over a selection, or the selection
   * bar's Set — and null the rest of the time.
   *
   * **One-shot, and dropped by this component rather than by the cell that acts on it.** A signal
   * left standing is not merely untidy: the rows are virtualised and the list is filtered, so the
   * cell it names may not be mounted when the request is made, and nothing would then consume it.
   * Minutes later, on a scroll back up or a cleared filter, that row mounts with the signal still
   * set and its editor springs open unprompted, with no gesture behind it.
   *
   * Clearing it here cannot lose the open, either: effects belong to the commit that scheduled
   * them, so the named cell's effect still runs even though this one has already asked for the
   * signal to go.
   *
   * A released marquee used to feed this too (`PD-POPUP-AFTER-DRAG`): a single-column drag opened
   * its first cell's editor behind the release. That went when the selection bar gained a Set of
   * its own — the drag says *what* to edit and the bar says *do it* — so the request now comes
   * from the container alone, whichever way the operator made it.
   */
  const [autoOpenCell, setAutoOpenCell] = useState<
    (FixtureCellRef & { seed: string | null; atButton: boolean }) | null
  >(null)
  useEffect(() => {
    if (autoOpenCell) setAutoOpenCell(null)
  }, [autoOpenCell])
  useEffect(() => {
    if (keyboardOpen) setAutoOpenCell({ ...keyboardOpen })
  }, [keyboardOpen])

  /** The close request, dropped on the commit after it is delivered — `autoOpenCell`'s rule. */
  const [closeCell, setCloseCell] = useState<FixtureCellRef | null>(null)
  useEffect(() => {
    if (closeCell) setCloseCell(null)
  }, [closeCell])
  useEffect(() => {
    if (closeEditorCell) setCloseCell({ ...closeEditorCell })
  }, [closeEditorCell])

  /**
   * A click on a column this row resolves nothing for — the Colour cell of a dimmer-only par —
   * clears the selection, exactly as the empty space under the last row does.
   *
   * Stabilised because it is a *row* prop and `RowView` is memoized: the container's answer is a
   * ladder that re-reads the cell count, so it changes identity on every marquee frame, and passing
   * it straight down would re-render every visible row at pointer rate.
   */
  const onEmptyCellClick = useStableCallback(onBackgroundClick)

  const marquee = useCellMarquee<ColumnKey, Row>({
    scrollRef,
    rows,
    rowHeight: ROW_HEIGHT,
    // Dividers hold no values and cannot be selected, so they must not inflate either count.
    isSelectableRow: (row) => row.kind !== 'divider',
    visibleColumns,
    cellSelection,
    isRowSelected: isSelected,
    onRowMarquee,
    onDragChange: onMarqueeDragChange,
  })

  const columnLabelFor = useCallback((col: ColumnKey) => columnLabel(col), [])

  const inertColumns = useInertColumns(visibleColumns)
  // `horizontalOnly`: this scroller is the virtualizer's too, so most scroll events on it are
  // vertical and say nothing about the columns. See `useScrollEdges`.
  const { right: moreColumnsRight, attach: attachScroller } = useScrollEdges(scrollRef, {
    horizontalOnly: true,
  })

  return (
    /* The scroller's WRAPPER, and it exists for the fade below: a gradient drawn inside the
       scroller would scroll away with the columns it is meant to be covering, which on a phone
       means the one hint that there are more columns disappears the moment you use it. It also
       is its own flex column so the scroller keeps `min-h-0 flex-1`. */
    <div className="relative flex min-h-0 flex-1 flex-col">
      <div
        // `attach` rather than `scrollRef` — it fills that ref AND tells `useScrollEdges` the
        // node exists, which a `RefObject` alone cannot.
        ref={attachScroller}
        // The frame is `sheetFrame.ts`'s (CLAUDE.md §List shell), and there is one arm: the grid
        // owns the remaining height of a full-height column on every list it is mounted on, and
        // runs edge to edge under the row above — which owns the line between them. The other
        // arm, a `rounded-md border` capped at `calc(100vh - 14rem)` for a list embedded in a
        // scrolling page, went with the `Card` the two plain lists sat in. `bg-background` on the
        // scroller is the fix `SheetTable` already carried: the sticky name column is opaque, and
        // over a transparent body it read darker than the cells beside it.
        className={SHEET_SCROLLER_CLASS}
        // On the scroller, not the rows wrapper: a short list leaves the wrapper shorter than the
        // scroller, and the empty space under the last row — the target this is for — is the
        // scroller's own. The header and every row stop it by ancestry rather than by
        // `stopPropagation`, so a cell editor's trigger or a checkbox never has to know this
        // exists.
        onClick={(e) => {
          if (!onBackgroundClick) return
          const target = e.target as Element
          // React bubbles a synthetic event up the *React* tree, portals included — and every
          // cell editor is a Radix popover portalled to `body`. A click on the slider inside an
          // open editor therefore reaches this handler with a target that has no row above it in
          // the DOM, and read as background it would drop the marquee mid-edit. The DOM subtree
          // is the question here, so the DOM is what is asked.
          if (!e.currentTarget.contains(target)) return
          if (target.closest('[data-row-id], [data-grid-header]')) return
          onBackgroundClick()
        }}
      >
        <div style={{ minWidth: `calc(${NAME_COLUMN_WIDTH} + ${visibleColumns.length * 96}px)` }}>
          {/* Header */}
          <div
            data-grid-header
            className={SHEET_HEADER_ROW_CLASS}
            style={{ gridTemplateColumns }}
          >
            <div
              // The marquee measures the name column from this — see `useCellMarquee`.
              data-grid-name-header
              // `px-2` *after* the header class: the header cell class carries `px-1.5`, and
              // `cn` is tailwind-merge, so the later padding wins — the sticky name column is 8px
              // inset in every row, and its label has to be too.
              className={cn(SHEET_STICKY_CELL_CLASS, SHEET_HEADER_CELL_CLASS, 'px-2')}
            >
              Fixture
            </div>
            {columnLabels.map(({ col, label }) => (
              <div
                key={col}
                // The marquee measures its column bands from these — see `useCellMarquee`.
                data-column-header={col}
                className={cn(
                  SHEET_HEADER_CELL_CLASS,
                  // Greyed rather than hidden: an operator looking for Colour in a
                  // POSITION-masked layer needs to learn *why* it is unavailable, and a column that
                  // vanished would read as a broken grid. The cells beneath say the same thing.
                  inertColumns.has(col) && 'opacity-40',
                )}
                title={inertColumns.has(col) ? 'Outside this layer’s mask' : undefined}
              >
                {label}
              </div>
            ))}
          </div>

          {/* Virtualized rows. The marquee handlers live here rather than on the scroller so the
              sticky header is excluded by geometry rather than by a hit test.

              Three touch declarations, each for a browser default the marquee was losing to
              (`PD-MARQUEE-TOUCH`). `select-none`: a drag — mouse or finger — was selecting the
              text under it as well as the cells, and nothing in a grid of values wants text
              selection. `[-webkit-touch-callout:none]`: the hold that arms a touch marquee is the
              same hold iOS answers with its own callout. `touch-manipulation`: pan and pinch stay
              the browser's, which is the decision — a finger scrolls, and only a held one
              marquees — while the double-tap-to-zoom delay goes, so a tap on a cell is a click
              at once. The hold itself takes the pan away in the move handler, not here:
              `touch-action` is read once, at the start of the touch, and the start of this touch
              is a scroll until it has been held.

              Unconditional now, on every list this table serves. The two plain ones carried the
              narrower arm — text selection refused only while a *row* drag was live, so fixture
              names stayed selectable and copyable — which was right while they had the row marquee
              alone. With a cell marquee they lose the same three defaults the programmer does, and
              a name is still copyable from the detail sheet. */}
          <div
            className="select-none touch-manipulation [-webkit-touch-callout:none]"
            style={{ height: `${virtualizer.getTotalSize()}px`, position: 'relative' }}
            onPointerDown={marquee.onPointerDown}
            onPointerMove={marquee.onPointerMove}
            onPointerUp={marquee.onPointerUp}
            onPointerCancel={marquee.onPointerUp}
            onPointerLeave={marquee.onPointerLeave}
          >
            {/* The rubber band. **Neutral, not primary** (space plan D4): a solid 2px `--foreground`
                frame with foreground corner handles over a `foreground/5` fill. It was a dashed
                primary border over a `primary/[0.13]` fill, which put the accent colour on the one
                thing that is never a value — and dragged it across cells whose *rings* use the same
                accent to mean "you own this". A selection marquee and an ownership ring are the two
                facts the grid most needs to keep apart, so they no longer share a hue.

                The 1px `--background` ring is what keeps a near-white frame legible where it crosses
                a selected row's near-white wash: two neutrals a few percent apart need a dark line
                between them, and a heavier frame would have read as a border rather than a band. */}
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
              return (
                <div
                  key={row.id}
                  className="absolute inset-x-0"
                  style={{ height: `${ROW_HEIGHT}px`, transform: `translateY(${virtualRow.start}px)` }}
                >
                  <RowView
                    row={row}
                    visibleColumns={visibleColumns}
                    gridTemplateColumns={gridTemplateColumns}
                    selected={row.kind !== 'divider' && isSelected(row.id)}
                    onRowClick={onRowClick}
                    onToggleExpand={onToggleExpand}
                    onBeginCellEdit={onBeginCellEdit}
                    onCellCommit={onCellCommit}
                    batchFor={batchFor}
                    scopeLabel={scopeLabel}
                    projectId={projectId}
                    onSpread={onSpread}
                    onShowInfo={onShowInfo}
                    showOwnership={showOwnership}
                    cellSelection={cellSelection}
                    deskConnected={deskConnected}
                    autoOpenCol={autoOpenCell?.rowId === row.id ? autoOpenCell.col : null}
                    autoOpenSeed={autoOpenCell?.rowId === row.id ? autoOpenCell.seed : null}
                    autoOpenAtButton={autoOpenCell?.rowId === row.id && autoOpenCell.atButton}
                    autoCloseCol={closeCell?.rowId === row.id ? closeCell.col : null}
                    selectionEmpty={selectionEmpty}
                    editorAnchorRef={editorAnchorRef}
                    onEmptyCellClick={onEmptyCellClick}
                  />
                </div>
              )
            })}
          </div>
        </div>

        {/* Scope chip, following the pointer. `fixed`, so it is never clipped by the scroller, and
            `pointer-events-none` so it can sit under the cursor without eating the drag.

            **It stays primary**, alone among the selection affordances, and that is deliberate
            rather than an oversight of D4: it exists only while a drag is in flight, it moves with
            the pointer, and it never comes to rest beside an owned cell — so it cannot be confused
            with a ring the way a row wash sitting still under one could.

            PORTALLED to `document.body`, which is load-bearing rather than tidiness: its coordinates
            are the pointer's `clientX/clientY`, i.e. viewport space, and `ProgrammerWorkspace` is a
            Tailwind `@container`. `container-type: inline-size` applies layout containment, which
            makes that element the containing block for `fixed` descendants, so an in-tree chip would
            be offset by the workspace's own top-left (the header, source strip, action bar and
            `p-4`) and sit well below the cursor.

            All three lists select cells now, so the workspace is no longer the only host — and the
            other two are why the portal has to stay whatever their markup looks like today. Neither
            `/fixtures/list` nor `/groups/list` has an `@container` above this table (each route puts
            one on its *sibling* breadcrumb row, and `Layout`'s is the `<header>`, a sibling of
            `<main>`), so on those two the chip would land correctly in-tree by luck. One
            `@container` added to a page wrapper for an unrelated reason would move it, silently and
            only on that route. */}
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
                  <span>{describeCellScope(cellSelection.cells, columnLabelFor)}</span>
                </>
              )}
            </div>,
            document.body,
          )}
      </div>
      {/* "There are more columns to the right", 24px wide, on the wrapper rather than in the
          scroller — so it stays pinned to the grid's right edge instead of sliding away with the
          content it is describing. It is drawn only while there is something still to the right,
          which is why the overflow is measured rather than assumed: at a desk width with three
          columns showing there is nothing off-screen, and a permanent gradient there would read
          as a rendering fault. `pointer-events-none` so it never eats a press on the last
          column. */}
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

interface RowViewProps {
  row: Row
  visibleColumns: readonly ColumnKey[]
  gridTemplateColumns: string
  selected: boolean
  onRowClick: (id: RowId, e: React.MouseEvent) => void
  onToggleExpand: (row: GroupRow | FixtureRow) => void
  onBeginCellEdit: (row: Row, col: ColumnKey) => void
  onCellCommit: (row: Row, col: ColumnKey, commit: CellCommit) => void
  batchFor: (row: Row, col: ColumnKey) => CellBatch
  /**
   * Where an edit lands, for the editors' label line: *Local*, or the focused Look's name in layer
   * scope. Read once by the table — the scope and the Look store — and passed down rather than read
   * per row, like `deskConnected`.
   */
  scopeLabel: string
  /**
   * The project the rows belong to, for the colour editor alone: its hidden appearance leaves read
   * the patch list, Recent lists templates and *Save as template…* records into it. The container
   * passes it for the programmer scope only — the two plain lists draw no template strip and get
   * none of the three — and every test mounts this table bare; a cell with none draws no Recent, a
   * disabled Save and no leaves rather than reaching for a store it has no project in.
   */
  projectId?: number
  /** See `FixturesTableProps.onSpread`. */
  onSpread?: (from: { r: number; g: number; b: number }) => void
  onShowInfo: (row: InfoRow) => void
  showOwnership: boolean
  /** Required, like the table's own — `RowView` has one caller and it always passes it. */
  cellSelection: CellSelection<ColumnKey>
  /**
   * The desk is reachable. A cell edit in the `live` editor context is a `programmer.*`
   * WebSocket write, so with the socket down it goes nowhere — and because the grid reads its
   * values back from the server, the cell simply snaps to its old value with nothing said. The
   * cells go inert instead. Layer scope is unaffected: those edits land in a local Look draft.
   *
   * Read once by the table and passed down rather than read per row — a rig fills this grid.
   */
  deskConnected: boolean
  /**
   * The container asked for this row's cell in this column to open its editor — Enter over the
   * selection, or the selection bar's Set — and null on every other row, which is all of them but
   * one, so the memo still holds for the rest of the grid.
   */
  autoOpenCol: ColumnKey | null
  /**
   * That request came from a character typed at the grid, which the editor seeds its first field
   * with. `''` for a bare Enter or the bar's Set, and null for every row but the named one.
   */
  autoOpenSeed: string | null
  /** That request was the bar's Set rather than a key, so its editor opens at the button. */
  autoOpenAtButton: boolean
  /**
   * The container asked this row's cell in this column to close its editor — Set pressed a second
   * time — and null on every other row, so the memo holds for the rest of the grid.
   */
  autoCloseCol: ColumnKey | null
  /** Nothing is selected — any open cell editor in this row must go. See `useEditorOpen`. */
  selectionEmpty?: boolean
  /** Where a requested editor opens. See `FixturesTableProps`. */
  editorAnchorRef?: React.RefObject<HTMLElement | null>
  /**
   * A click on a column this row resolves nothing for. Stable, so the memo holds — see the
   * table's own `onEmptyCellClick`.
   */
  onEmptyCellClick?: () => void
}

const NO_INERT_COLUMNS: ReadonlySet<ColumnKey> = new Set()

/**
 * Which column *headers* the focused layer does not assert.
 *
 * Derived from the layer's `propertyMask` and the column's own canonical category, because a header
 * has no fixture to classify against — the cells beneath it still use each resolution's real
 * descriptor, so one column carrying different property kinds across fixture types is classified
 * per fixture where it matters. Empty outside layer scope, so the two plain list routes are
 * untouched.
 */
function useInertColumns(visibleColumns: readonly ColumnKey[]): ReadonlySet<ColumnKey> {
  const scope = useProgrammerScope()
  const store = useLookRowStore()
  const mask = store?.propertyMask
  const inLayerScope = scope?.kind === 'layer'
  return useMemo(() => {
    if (!inLayerScope) return NO_INERT_COLUMNS
    const families = parsePropertyMask(mask)
    if (families.length === 0) return NO_INERT_COLUMNS
    return new Set(visibleColumns.filter((col) => !families.includes(columnFamily(col))))
  }, [inLayerScope, mask, visibleColumns])
}

/** Name-cell indent per nesting depth (member rows 1, element rows 2). */
const INDENT_CLASS = ['', 'ml-5', 'ml-10']

/** Stable identity for the ownership-off path, so the hook's memos never churn. */
const EMPTY_CELLS: RowCell[] = []

const RowView = React.memo(function RowView({
  row,
  visibleColumns,
  gridTemplateColumns,
  selected,
  onRowClick,
  onToggleExpand,
  onBeginCellEdit,
  onCellCommit,
  batchFor,
  scopeLabel,
  projectId,
  onSpread,
  onShowInfo,
  showOwnership,
  cellSelection,
  deskConnected,
  autoOpenCol,
  autoOpenSeed,
  autoOpenAtButton,
  autoCloseCol,
  selectionEmpty,
  editorAnchorRef,
  onEmptyCellClick,
}: RowViewProps) {
  // Hooks run unconditionally; divider rows just have no cells.
  const cells = useMemo(() => buildRowCells(row, visibleColumns), [row, visibleColumns])
  // Always the live wire read: it is what the editor opens at, so a busk begins where the rig is
  // even in a scope that displays an em-dash. Free when the scope is Output, which reads the same
  // values through this same hook.
  const liveValues = useRowValues(cells)
  const scoped = useScopedRowValues(cells, liveValues)
  const scope = useProgrammerScope()
  // A cell edit reaches the wire in every context but `lookLayer`, where it lands in the Look row
  // draft instead. Derived from the context rather than from `scope.kind === 'layer'` so it cannot
  // disagree with `useCellWriters`, which routes on exactly this — layer scope without a row store
  // is still a live write.
  const editorContext = useEditorContext()
  const cellsInert = !deskConnected && editorContext.kind === 'live'
  // Passing an empty cell list is the "off" state: useRowOwnership then registers no
  // subscriptions and returns a constant, so the plain list views pay nothing for this.
  //
  // Layer scope switches it off too, and for a different reason: the engine's provenance describes
  // the *rig*, and what is on screen there is a Look's stored rows. A cue-blue ring around a row
  // in a library entity would be answering a question nobody asked. Its own tones say what matters
  // — outside the mask, outside the targets — via `layerCellClass`.
  const ownershipCells = showOwnership && scope?.kind !== 'layer' ? cells : EMPTY_CELLS
  const ownership = useRowOwnership(ownershipCells)
  // A focused **effect** template layer marks the cells its effect drives. Those are exactly the
  // cells `useScopedRowValues` gave a value to in that scope, so the test below is `state?.value`
  // rather than a second mask/target computation that could disagree with the one that painted the
  // ring. The label is null where the effect type no longer resolves in the registry — the wave
  // still draws, because "an effect drives this" is true whether or not its speed can be read.
  const focusedTemplate = useFocusedTemplateLayer()
  const effectDriven = scope?.kind === 'layer' && focusedTemplate?.kind === 'effect'
  const templateEffect = focusedTemplate?.template?.effect
  const effectDivision =
    templateEffect == null
      ? null
      : effectSpeedLabel(templateEffect.beatDivision, templateEffect.timingSource)
  const cellByCol = useMemo(() => new Map(cells.map((cell) => [cell.col, cell])), [cells])
  // Every fixture this row covers — a group row's members, a fixture row's own key. The same
  // expansion the cells were resolved through, so "is this row in the layer's targets?" and "what
  // would an edit here write?" cannot disagree.
  const rowTargetKeys = useMemo(
    () => [...new Set(cells.flatMap((cell) => cell.targetKeys))],
    [cells],
  )

  if (row.kind === 'divider') {
    return (
      // `data-row-id` here too: the scroller's background-click test reads it, and a divider is a
      // row of the list, not empty space — a tap on "Ungrouped" must not drop the selection.
      <div
        className={cn(SHEET_DIVIDER_CLASS, 'px-2')}
        data-row-id={row.id}
      >
        <span className={SHEET_HEADER_CELL_CLASS}>{row.label}</span>
      </div>
    )
  }

  const isGroup = row.kind === 'group'
  const isElement = row.kind === 'element'
  const elementCount = row.kind === 'fixture' ? (row.fixture.elements?.length ?? 0) : 0
  // Multi-head fixture rows expand like group rows do.
  const expandable = isGroup || (row.kind === 'fixture' && elementCount > 0)
  const isExpanded = row.kind !== 'element' && row.isExpanded
  // Member rows indent one level; element rows one more than their parent.
  const indentLevel =
    (row.kind !== 'group' && row.parentGroup !== undefined ? 1 : 0) + (isElement ? 1 : 0)
  const rowName = isGroup
    ? row.name
    : isElement
      ? row.element.displayName
      : row.fixture.name
  // Element display names are generic ("Head 1"); accessible names and locate
  // labels qualify them with the owning fixture so two expanded bars stay
  // distinguishable.
  const qualifiedName = isElement ? `${row.fixture.name} ${row.element.displayName}` : rowName
  const badgeCount = isGroup ? row.members.length : elementCount > 0 ? elementCount : undefined
  const locate = rowLocateTarget(row)

  // **Selection is neutral** (space plan D4). The wash was `bg-primary/10` — the same accent, at
  // nearly the same strength, as the `programmer` ownership ring sitting inside it — so a row you
  // had selected and a row you owned every value of were one picture. `--primary` now means
  // exactly one thing on this grid, "you own this value", and everything that means "selected" is
  // a neutral: a `foreground/6%` wash, a 3px foreground left edge, a bold name. The edge is drawn
  // on the sticky cell's overlay rather than here; see below for why.
  return (
    <div
      className={cn(SHEET_ROW_CLASS, selected ? 'bg-foreground/[0.06]' : 'hover:bg-accent/30')}
      style={{ gridTemplateColumns }}
      data-state={selected ? 'selected' : undefined}
      // With `data-cell` on each value cell, this is how the container finds the DOM cell to anchor
      // the marquee's typed-value editor at, without the table knowing that editor exists.
      data-row-id={row.id}
    >
      {/* Name cell (sticky left, carries selection affordances) */}
      <div
        className={cn(SHEET_STICKY_CELL_CLASS, 'flex h-full cursor-pointer items-center gap-1.5 px-2')}
        onClick={(e) => onRowClick(row.id, e)}
      >
        {/* Selection tint needs to survive the opaque sticky background — and so does the 3px
            edge, which is why it is here and not on the row. An inset shadow on the row paints on
            the row's own background layer, underneath every child, and this cell's `bg-background`
            is opaque: the edge would have been invisible at exactly the widths the name column is
            pinned at, which is all of them. Drawn on the overlay it sits above that background and
            below the name, which is where a selection edge belongs. */}
        <div
          className={`pointer-events-none absolute inset-0 ${
            selected
              ? 'bg-foreground/[0.06] shadow-[inset_3px_0_0_var(--foreground)]'
              : 'group-hover/row:bg-accent/30'
          }`}
        />
        {/* There was a checkbox here — the accumulating half of row selection, beside a name click
            that replaced. It went when the row and cell selections became one: a drag from this
            column selects rows now (`useCellMarquee`), ⌘-click still toggles, and a box that said
            "this row is selected" beside cells that say the same thing with an outline was two
            vocabularies for one fact. The indent it carried moves to the name.

            It was also the row's one tabbable, screen-reader-announced selector, and nothing here
            replaces it per row — decided, not overlooked: the keyboard path is the window-level
            ⌘A, ↑/↓ (Shift extends) and →/← (open and close the anchor row) in
            `FixturesListContainer`; the first two existed alongside the checkbox and are the
            whole of it now. A tabbable name cell would put a stop per row in
            the tab order for a gesture those keys already cover. */}
        {indentLevel > 0 && (
          <span aria-hidden="true" className={`shrink-0 ${INDENT_CLASS[indentLevel] ?? ''}`} />
        )}
        {/* `expandable` already narrows row to GroupRow | FixtureRow. */}
        {expandable && (
          <button
            type="button"
            className="relative shrink-0 rounded p-0.5 hover:bg-accent"
            onClick={(e) => {
              e.stopPropagation()
              onToggleExpand(row)
            }}
            aria-label={isExpanded ? `Collapse ${rowName}` : `Expand ${rowName}`}
          >
            {isExpanded ? (
              <ChevronDown className="size-3.5 text-muted-foreground" />
            ) : (
              <ChevronRight className="size-3.5 text-muted-foreground" />
            )}
          </button>
        )}
        {/* `font-semibold` when selected is the third of D4's three neutral signals, and the one
            that survives a colour-blind read and a badly calibrated house monitor: a 6% wash and a
            3px edge are both easy to lose, weight is not. It beats a group row's `font-medium`
            deliberately — selected is the louder fact. */}
        <span
          className={cn(
            'relative min-w-0 flex-1 truncate',
            isElement && 'text-muted-foreground',
            selected ? 'font-semibold' : isGroup && 'font-medium',
          )}
        >
          {rowName}
        </span>
        {badgeCount !== undefined && (
          <Badge variant="secondary" className="relative shrink-0 px-1 text-[10px]">
            {badgeCount}
          </Badge>
        )}
        {/* Hover actions: the span's stopPropagation keeps both buttons from
            reaching the name cell's selection click. focus-within keeps the
            buttons tabbable — once anything in the row has focus they display,
            entering the tab order for keyboard users. */}
        {locate && (
          <span
            className="relative hidden shrink-0 items-center gap-0.5 group-hover/row:inline-flex group-focus-within/row:inline-flex"
            onClick={(e) => e.stopPropagation()}
          >
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="outline"
                  size="icon"
                  className="size-8"
                  onClick={() => onShowInfo(row)}
                  aria-label={`Details for ${qualifiedName}`}
                >
                  <Info className="size-3.5" />
                </Button>
              </TooltipTrigger>
              <TooltipContent>Details for {qualifiedName}</TooltipContent>
            </Tooltip>
            <LocateButton type={locate.type} targetKey={locate.key} name={qualifiedName} iconOnly />
            {/* Renders nothing outside layer scope, and nothing for a row the layer already
                targets — so this span is unchanged on the two plain list routes. Element rows are
                left out: a Look row addresses the fixture, not one of its elements. */}
            {!isElement && (
              <AddToTargetsButton
                target={locate}
                fixtureKeys={rowTargetKeys}
                name={qualifiedName}
              />
            )}
          </span>
        )}
      </div>

      {/* Property cells */}
      {visibleColumns.map((col) => {
        const cell = cellByCol.get(col)
        // The live value is what the editor opens at; `state.value` is what the cell displays.
        // No live value means the column resolves to nothing on this fixture — a blank, not an
        // em-dash, because there is nothing here to set.
        const live = liveValues[col]
        const state = scoped[col]
        if (!cell || !live) {
          // Nothing here to set — and a click on it clears the selection, the way a click on the
          // grid's own background does. It is not background in the DOM (the row carries
          // `data-row-id`, which the scroller's handler stops at), so it has to say so itself;
          // without this a click on the Colour column of a dimmer-only par did nothing at all and
          // left the previous selection standing under a pointer that had plainly moved on.
          return <div key={col} className="h-full" onClick={onEmptyCellClick} />
        }
        const owned = ownership[col]
        const layer = owned?.layer
        // Layered OVER whatever ownership produced, as a fill rather than a seventh ring colour —
        // see `cellSelection.ts`.
        const selectedCell = cellSelection.isSelected(row.id, col)
        return (
          <div
            key={col}
            data-cell={col}
            className={cn(
              // The **marks gutter** (space plan D5). The value inside a cell is centred across the
              // whole width, and the effect badge and the layer glyph float over its right-hand
              // end — so a colour cell reading `255,157,74` had its last characters under a violet
              // wave badge, and the operator could not tell `74` from `7` on the one cell the
              // badge is there to draw attention to.
              //
              // **18px, not the plan's `pr-4`.** The widest mark is the effect badge at
              // `size-3.5` (14px), inset `right-1` (4px) — 18px from the edge — so a 16px gutter
              // left it overlapping the last 2px of the value's own space, which is the defect
              // D5 was written to remove. 18px is also what the artboard reserves
              // (`Main.dc.html`'s `.ci { padding: 0 18px 0 6px }`); the plan's `pr-4` was a
              // rounding of it that nobody checked against the badge's own size. Any new mark has
              // to fit `right-1` plus its own width inside this number, or widen both together.
              //
              // It goes on the *wrapper* rather than inside `PropertyCell`: padding is inside the
              // border box, so the ownership ring and the selection outline still trace the full
              // cell, and absolutely-positioned marks still measure `right` from the same edge
              // they always did. Only the content moves. That is the same choice
              // `ownershipCellClass` documents — the four cell editors encode value shape, and a
              // gutter carved inside one of them would have to be carved four times.
              //
              // The one mark it does **not** contain is the focused-template layer's division
              // label below: that is an icon *plus* text ("½", "1/4"), so no fixed gutter sized
              // for a badge could hold it. It is better off than before — it had no gutter at all
              // — and it draws only in a focused *template* layer, where ownership is switched off
              // and the corner is otherwise empty. Containing it would mean a scope-dependent
              // gutter, which is a design decision rather than a rounding fix.
              'relative h-full min-w-0 py-0.5 pr-[18px]',
              ownershipCellClass(owned),
              layerCellClass(scope?.kind === 'layer' ? state : undefined),
              cellSelectionClass(selectedCell),
              // Output is a read of the cook. Editing it would have to pick a destination, and
              // choosing one is what the scope switcher is for — so the cell reads and the
              // overlay button below takes the click instead.
              state?.editable === false && 'pointer-events-none',
              // Same mechanism as the read-only Output cell above, for a different reason: this
              // cell would take the edit and drop it. `OwnerJumpOverlay` sets `pointer-events-auto`
              // and so still navigates — reading why a cell is what it is stays available offline.
              cellsInert && 'pointer-events-none opacity-60',
            )}
            // Only when the *connection* is why this cell is inert. A cell that Output scope has
            // already made read-only stays inert after a reconnect, so blaming the socket there
            // would send the operator to fix the wrong thing.
            title={
              cellsInert && state?.editable !== false
                ? DESK_OFFLINE_LABEL
                : ownershipTitle(owned)
            }
          >
            {/* The winning Look layer, layered around the cell rather than inside it — the same
                choice `ownershipCellClass` documents. The four cell editors already encode value
                shape, and a marker drawn inside one of them would have to be drawn four times.
                It used to share the cell with a `ref:` marker (a left rail plus a `Link2` in the
                *opposite* corner, so the two icons stayed readable together); that retired with the
                grammar in session 4, so this glyph now has the cell to itself. Title-only detail —
                the hover text names the look, and a name would not fit here at this density. */}
            {layer && (
              <Layers
                className={`pointer-events-none absolute bottom-0.5 right-1 size-2.5 ${
                  layer.mixed ? 'text-muted-foreground/50' : 'text-muted-foreground'
                }`}
              />
            )}
            {/* An effect-driven cell wears the FX wave as a badge, top-right — the opposite corner
                from the Layers glyph, so a cell that is both (a Look layer's effect) shows both.
                The violet ring on its own was too quiet beside the blue ones: at a glance a cell
                the effect is animating and a cell you set read as one picture, and "this value is
                moving and Record will not take it" is the fact the operator most needs to see
                without hovering. A badge and not a louder ring, because the ring vocabulary is
                six colours already and a seventh weight would not have said *effect*. */}
            {owned?.source === 'effect' && (
              <span
                data-testid="effect-badge"
                className="pointer-events-none absolute right-1 top-0.5 flex size-3.5 items-center justify-center rounded-sm bg-violet-500/90 text-white"
              >
                <AudioWaveform className="size-2.5" />
              </span>
            )}
            {/* The same corner, and never both: ownership is switched off in layer scope, so the
                glyph above is undefined exactly where this one draws. Around the cell rather than
                inside it, for the reason that one documents — the four cell editors encode value
                shape, and a marker drawn inside one of them would have to be drawn four times. */}
            {effectDriven && state?.value != null && (
              <span
                className="pointer-events-none absolute bottom-0.5 right-1 flex items-center gap-0.5 text-[9px] leading-none text-muted-foreground"
                title={`Driven by “${focusedTemplate?.name ?? 'this template'}”`}
              >
                <AudioWaveform className="size-2.5" />
                {effectDivision}
              </span>
            )}
            <PropertyCell
              cell={cell}
              label={columnLabel(col)}
              // The staged overlay is applied to whatever the scope resolved, not only to the live
              // read: in Output — where `state.value` is always set — short-circuiting past
              // `applyStagedValue` dropped the optimistic feedback for a write still in flight, and
              // that cell then sat on its old value until the wire caught up. In layer scope
              // ownership is switched off, so `owned` is undefined there and this is a no-op.
              value={applyStagedValue(
                state?.value ?? live,
                owned?.staged,
                cell.resolutions,
              )}
              // Not `state == null`: a divider or a scope with no opinion is not the same as a
              // scope that has one and says "nothing here".
              placeholder={state !== undefined && state.value === undefined}
              batch={batchFor(row, col)}
              scopeLabel={scopeLabel}
              projectId={projectId}
              onSpread={onSpread}
              // Belt and braces with the wrapper's `pointer-events-none` below: that stops the
              // mouse, this stops the keyboard. The trigger is tabbable, so Tab-then-Enter would
              // otherwise walk straight past the guard and open an editor whose commit is dropped.
              // It also stops the trigger's own `onClick` — which on this grid is the selection —
              // so a read-only cell cannot be selected by keyboard either.
              //
              // **Both reasons a cell takes no edit, not just the offline one.** `editable: false`
              // is the *scope's* statement — Output is a read of the cook, and a focused template
              // layer is a read of a template — and it reached only the pointer. A commit through
              // the keyboard hole did not get dropped: `useCellWriters` has no scope arm for either
              // (a template layer mints no `lookLayer` context), so it landed in Local, on a grid
              // drawing itself as read-only.
              disabled={cellsInert || state?.editable === false}
              autoOpen={autoOpenCol === col}
              autoClose={autoCloseCol === col}
              anchorAtButton={autoOpenCol === col && autoOpenAtButton}
              keyboardSeed={autoOpenCol === col ? autoOpenSeed : null}
              selectionEmpty={selectionEmpty}
              // A **single** click on a value cell selects it; a **double** click opens its
              // editor — which is also opened by the selection bar's Set, by ⏎ and by typing. So
              // the drag, the click and the keys all say *what* to edit and one gesture says
              // *edit it*. Constant on this grid since every list it serves selects cells; the
              // flag stays on the cells themselves for `CueValueGrid`, which has no selection and
              // must keep click-to-open or lose every way in. See `CellClickBehaviour`.
              clickSelects
              editorAnchorRef={editorAnchorRef}
              onBeginEdit={() => onBeginCellEdit(row, col)}
              onCommit={(commit) => onCellCommit(row, col, commit)}
            />
            {scope?.kind === 'output' && <OwnerJumpOverlay owned={owned} />}
          </div>
        )
      })}
    </div>
  )
})

/**
 * In Output scope, a cell's tint is a *destination*: clicking it points the grid at whatever won
 * the cell. That is what finally makes the ownership colours navigational rather than decorative,
 * and it is why they were worth making learnable.
 *
 * An overlay rather than a change to the four cell editors. All four are Popover triggers, and the
 * marquee's whole design turns on click-versus-drag (`useCellMarquee`); pressing the jump into each
 * of them would mean four chances to break that. `pointer-events-none` on the cell content above
 * makes this the only thing under the cursor, while `pointerdown` still bubbles to the rows wrapper
 * so drag-select is untouched.
 *
 * **`pointer-events-auto` is mandatory here**, and its absence is invisible to a test.
 * `pointer-events` is an *inherited* property, so the wrapper's `pointer-events-none` — the very
 * thing that clears the cursor's path to this overlay — reaches this button too and made the jump
 * inert in a real browser. `fireEvent.click` dispatches straight at the node and never consults it,
 * so the suite passed throughout.
 *
 * Renders nothing when there is nowhere to go, so the cursor never promises a jump it won't make:
 * a `mixed` cell has no single owner to name, and a `layerId` belonging to a **cue's** layer is not
 * in this programmer's stack — `focusLayer` reports that and the click falls through.
 */
function OwnerJumpOverlay({ owned }: { owned?: CellOwnership }) {
  const actions = useProgrammerScopeActions()
  const layer = owned?.layer
  const layerId = layer && !layer.mixed ? layer.layerId : undefined
  const toLocal = layerId == null && owned?.source === 'programmer'
  if (!actions || (layerId == null && !toLocal)) return null

  const label = toLocal
    ? 'Show your own values'
    : `Show the look layer that set this${layer?.name ? ` — ${layer.name}` : ''}`

  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      className="pointer-events-auto absolute inset-0 cursor-zoom-in rounded-sm focus-visible:ring-1 focus-visible:ring-ring"
      onClick={() => {
        if (layerId != null && actions.focusLayer(layerId)) return
        if (toLocal) actions.setScope({ kind: 'local' })
      }}
    />
  )
}

function PropertyCell({
  cell,
  label,
  value,
  placeholder,
  batch,
  scopeLabel,
  projectId,
  onSpread,
  disabled,
  autoOpen,
  autoClose,
  anchorAtButton,
  keyboardSeed,
  selectionEmpty,
  clickSelects,
  editorAnchorRef,
  onBeginEdit,
  onCommit,
}: {
  cell: RowCell
  /**
   * The column's display name. Only the *sheet* form of a cell editor shows it — a popover has no
   * header — but it is threaded from here rather than derived in the cells, because a cell is
   * given a `CellResolution` and not a `ColumnKey`, and one `slider` cell is a dimmer where the
   * next is an iris. See `EditorSurface`.
   */
  label: string
  /**
   * What the editor opens at. In a scope that holds nothing here this is still the *live* value,
   * with [placeholder] suppressing its display — so clicking an em-dash starts the slider where
   * the rig is rather than at zero.
   */
  value: NonNullable<ReturnType<typeof useRowValues>[ColumnKey]>
  placeholder?: boolean
  /** What a commit lands on — see `CellBatch`. */
  batch: CellBatch
  /** *Local*, or the focused Look's name — see `FixturesTableProps`. */
  scopeLabel: string
  /**
   * The project the rows belong to, for the colour editor alone: its hidden appearance leaves read
   * the patch list, Recent lists templates and *Save as template…* records into it. The container
   * passes it for the programmer scope only — the two plain lists draw no template strip and get
   * none of the three — and every test mounts this table bare; a cell with none draws no Recent, a
   * disabled Save and no leaves rather than reaching for a store it has no project in.
   */
  projectId?: number
  /** The colour editor's *Spread…* — see `FixturesTableProps.onSpread`. */
  onSpread?: (from: { r: number; g: number; b: number }) => void
  /** The desk is unreachable, so an edit here would go nowhere. */
  disabled: boolean
  /**
   * Open this cell's editor without a click — the container's request (Enter over the selection,
   * or the bar's Set), and nothing else.
   *
   * Threaded to all four rather than solved once above them because the popover is each editor's
   * own — and because `CueValueGrid` mounts these same four components with no table over them.
   * The rule itself is shared, in `useEditorOpen`. A [disabled] cell ignores it, so Output
   * scope, a focused template layer and an unreachable desk stay read-only through this door as
   * much as through the pointer.
   *
   * On the programmer this is the only door the *container* holds: a single click there selects the
   * cell, and the only pointer gesture that opens an editor is a **double** click, which the
   * surface answers itself without passing through here. See `CellClickBehaviour`.
   */
  autoOpen: boolean
  /** The container asked this cell's editor to close — Set pressed again. */
  autoClose: boolean
  /**
   * That open came from the bar's **Set**, so the editor is anchored at that button. Enter and a
   * typed character leave it false: those are made at the selection, so the panel opens beside the
   * cell. See `useEditorOpen`.
   */
  anchorAtButton: boolean
  /**
   * That open came from a character typed at the grid, which the editor seeds its first field
   * with — see `useEditorKeyboard`. `''` for a bare Enter or Set; null for a click.
   */
  keyboardSeed: string | null
  /** Nothing is selected, so an open editor here has lost what it was editing for. */
  selectionEmpty?: boolean
  onBeginEdit: () => void
  onCommit: (commit: CellCommit) => void
} & CellClickBehaviour) {
  switch (value.kind) {
    case 'slider':
      return (
        <SliderCell
          value={value}
          resolutions={cell.resolutions}
          label={label}
          batch={batch}
          scopeLabel={scopeLabel}
          placeholder={placeholder}
          disabled={disabled}
          autoOpen={autoOpen}
          autoClose={autoClose}
          anchorAtButton={anchorAtButton}
          keyboardSeed={keyboardSeed}
          selectionEmpty={selectionEmpty}
          clickSelects={clickSelects}
          editorAnchorRef={editorAnchorRef}
          onCommit={onCommit}
          onBeginEdit={onBeginEdit}
        />
      )
    case 'colour':
      return (
        <ColourCell
          value={value}
          resolutions={cell.resolutions}
          label={label}
          batch={batch}
          scopeLabel={scopeLabel}
          projectId={projectId}
          onSpread={onSpread}
          placeholder={placeholder}
          disabled={disabled}
          autoOpen={autoOpen}
          autoClose={autoClose}
          anchorAtButton={anchorAtButton}
          keyboardSeed={keyboardSeed}
          selectionEmpty={selectionEmpty}
          clickSelects={clickSelects}
          editorAnchorRef={editorAnchorRef}
          onCommit={onCommit}
          onBeginEdit={onBeginEdit}
        />
      )
    case 'position':
      return (
        <PositionCell
          value={value}
          resolutions={cell.resolutions}
          label={label}
          batch={batch}
          scopeLabel={scopeLabel}
          placeholder={placeholder}
          disabled={disabled}
          autoOpen={autoOpen}
          autoClose={autoClose}
          anchorAtButton={anchorAtButton}
          keyboardSeed={keyboardSeed}
          selectionEmpty={selectionEmpty}
          clickSelects={clickSelects}
          editorAnchorRef={editorAnchorRef}
          onCommit={onCommit}
          onBeginEdit={onBeginEdit}
        />
      )
    case 'setting':
      return (
        <SettingCell
          value={value}
          resolutions={cell.resolutions}
          label={label}
          batch={batch}
          scopeLabel={scopeLabel}
          placeholder={placeholder}
          disabled={disabled}
          autoOpen={autoOpen}
          autoClose={autoClose}
          anchorAtButton={anchorAtButton}
          keyboardSeed={keyboardSeed}
          selectionEmpty={selectionEmpty}
          clickSelects={clickSelects}
          editorAnchorRef={editorAnchorRef}
          onCommit={onCommit}
          onBeginEdit={onBeginEdit}
        />
      )
  }
}
