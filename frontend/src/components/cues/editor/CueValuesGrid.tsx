import { useCallback, useEffect, useMemo, useRef, useState, type RefObject } from 'react'
import { Filter, Grid3x3, Plus, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { cn } from '@/lib/utils'
import { isEditableTarget } from '@/lib/domUtils'
import { CueTargetPicker } from '@/components/cues/CueTargetPicker'
import { FixturesTable } from '@/components/fixtures-list/FixturesTable'
import { SpreadPopover, type SpreadColumn } from '@/components/fixtures-list/SpreadPopover'
import { COLUMN_DEFS, cellFamilies, resolutionPropertyNames, resolveCell } from '@/components/fixtures-list/columns'
import {
  batchForTargets,
  clampCommitToResolution,
  commitMatchesResolution,
  expandSelectionToTargets,
  mergePositionCommits,
  planBatchWrites,
  resolveTargetCells,
  rowWriteTargets,
  type CellBatch,
  type CellCommit,
  type FixtureRow,
  type GroupRow,
  type Row,
  type RowId,
} from '@/components/fixtures-list/rowModel'
import { buildCueRowCells } from '@/components/fixtures-list/useRowValues'
import { applyPlannedWrite, cueCellWriters, useCellWriters } from '@/components/fixtures-list/useCellWriters'
import { EditorContextProvider } from '@/components/programmer/EditorContext'
import { SelectionBar } from '@/components/sheet/SelectionBar'
import { CellSelectionActions } from '@/components/sheet/CellSelectionActions'
import { orderedSelectedCells, type CellActionCopy, type CellKeyboardPermission } from '@/components/sheet/cellEntry'
import { arrowStepTarget, listSelectionIntentFor } from '@/components/sheet/listSelectionModel'
import { stepCellSelection, type CellSelection } from '@/components/sheet/useCellSelection'
import { useCellEditorRequests } from '@/components/sheet/useCellEditorRequests'
import { useSheetKeyboard, type SheetCellKeys, type SheetKeyRefusal, type SheetRowKeys } from '@/components/sheet/useSheetKeyboard'
import { useCueActionDialogs } from '@/components/runner/cueActionDialogs'
import { useActiveKeyRegion } from '@/components/sheet/keyRegion'
import { FAMILY_LABELS } from '@/lib/attributeFamily'
import type { CellRef } from '@/components/sheet/cellSelectionModel'
import type { ColumnKey } from '@/components/fixtures-list/columns'
import type { CueTarget } from '@/api/cuesApi'
import { useCueRowStore, type CueRowStoreValue } from './CueRowStore'
import { useCueValuesView, type CueValuesView } from './CueValuesView'
import { CueEditorIsolation } from './editorIsolation'
import { useFixtureColourIndex } from '@/hooks/useFixtureColourIndex'

type CueCellRef = CellRef<ColumnKey>

const ALL_COLUMNS: readonly ColumnKey[] = COLUMN_DEFS.map((d) => d.key)

/**
 * The cue editor's **Values** tab (show-cue-editing plan D2, D14, §3.3, §4.1, §4.5): the
 * programmer's `FixturesTable` over the cue's `CueRowStore`, with the sheet kit's gestures.
 *
 * - **What it shows** is what GO lands — the cue's cook with the draft over it — each cell edged
 *   blue for one of the cue's own rows and teal for the Look that won it, the Look named on the
 *   hover. A typed value becomes an own row, which beats every layer; over a Look-won cell its hover
 *   says what it overrides. ⌫ takes the own row away and the layer shows again.
 * - **Which rows** (D14): every head an own row or a layer reaches, in rig order, a multi-head
 *   fixture's cells under it where the cue holds them, and a **group row** for each group the cue
 *   holds an own row on — drawn and edited there, as one group row. *+ Fixtures…* adds a fixture
 *   or a group, *Whole rig* lists every fixture, *Own only* narrows to the rows the cue holds itself.
 * - **It sends nothing** (P2). The grid mounts none of the surfaces that reach the rig or the desk —
 *   no desk-selection bridge, no Release, Locate or Highlight, no palette strip, no effect sweep on
 *   ⌫, no live writer: its `EditorContext` is the `cue` arm, whose writers land in the store and
 *   have no live fallback, and its rows and selection are the editor's (`CueValuesView`), never the
 *   desk's. It draws nothing outside a `CueRowStoreProvider` and a `CueValuesViewProvider`.
 * - **Keys are the region's** (§4.5): the editor root is a key region, and this grid's listener
 *   hears a key only while that region holds focus or took the last press. A grid that is not the
 *   region draws its selection dimmed.
 * - **Read-only** — locked, or another project's cue — the cells are inert and Set · Clear · Spread
 *   say why; the marquee still selects, as the cue sheet's does.
 */
export function CueValuesGrid({
  region,
  readOnlyReason,
  onRequestUnlock,
}: {
  /** The editor root, the key region this grid's keys belong to. */
  region: RefObject<HTMLElement | null>
  /** Why the grid is read-only, or null where it edits. */
  readOnlyReason: string | null
  /** Lift the show's lock, where it is the operator's: a refused ⌫ or Enter asks to. */
  onRequestUnlock?: () => void
}) {
  const store = useCueRowStore()
  const view = useCueValuesView()
  if (store == null || view == null) return null
  // The programmer's scope, focused layers and rail-tab claim would dim this grid's columns, refuse
  // its Spread or send its colour double click to the rail (P2): `CueEditorIsolation` clears them.
  return (
    <CueEditorIsolation>
      <EditorContextProvider value={{ kind: 'cue', cueId: store.cueId }}>
        <CueValuesGridBody store={store} view={view} region={region} readOnlyReason={readOnlyReason} onRequestUnlock={onRequestUnlock} />
      </EditorContextProvider>
    </CueEditorIsolation>
  )
}

function CueValuesGridBody({
  store,
  view,
  region,
  readOnlyReason,
  onRequestUnlock,
}: {
  store: CueRowStoreValue
  view: CueValuesView
  region: RefObject<HTMLElement | null>
  readOnlyReason: string | null
  onRequestUnlock?: () => void
}) {
  const {
    rows,
    wholeRig,
    setWholeRig,
    ownOnly,
    setOwnOnly,
    addTarget,
    toggleGroupExpanded,
    toggleFixtureExpanded,
    rowSelection,
    cellSelection,
    selectCells,
    selectRow,
    setRows,
    clearByLadder,
    rowById,
  } = view
  const [pickerOpen, setPickerOpen] = useState(false)
  const fixtureColours = useFixtureColourIndex(store.projectId)
  const visibleColumns = useMemo(() => {
    const taken = new Set<ColumnKey>()
    for (const row of rows) {
      if (row.kind === 'divider') continue
      for (const cell of buildCueRowCells(row, ALL_COLUMNS)) taken.add(cell.col)
    }
    return ALL_COLUMNS.filter((col) => taken.has(col))
  }, [rows])
  const selectableOrder = useMemo(() => rows.filter((row) => row.kind !== 'divider').map((row) => row.id), [rows])
  const { count: cellCount, clear: clearCells, isSelected: isCellSelected } = cellSelection
  const rowCountRef = useRef(rowSelection.count)
  rowCountRef.current = rowSelection.count
  const { selectAll: selectAllRaw, clear: clearRows } = rowSelection
  const tableCellSelection = useMemo<CellSelection<ColumnKey>>(() => ({ ...cellSelection, select: selectCells }), [cellSelection, selectCells])

  // ── Writes ─────────────────────────────────────────────────────────────────────────────────────
  const writers = useCellWriters()
  const groupWriters = useMemo(() => cueCellWriters(store, 'group'), [store])
  const readOnly = readOnlyReason != null

  /** One commit over these rows in one column: a group row writes the group's own row, every other row its heads. */
  const writeRows = useCallback(
    (targetRows: readonly Row[], col: ColumnKey, commit: CellCommit) => {
      if (readOnly) return
      const plain = new Set<RowId>()
      for (const row of targetRows) {
        if (row.kind === 'group') writeGroupRow(groupWriters, row, col, commit)
        else if (row.kind !== 'divider') plain.add(row.id)
      }
      if (plain.size === 0) return
      for (const planned of planBatchWrites(expandSelectionToTargets(rows, plain), col, commit)) {
        applyPlannedWrite(writers, planned)
      }
    },
    [readOnly, groupWriters, rows, writers],
  )

  const marqueeByColumn = useMemo(
    () =>
      cellSelection.byColumn().map(({ col, rowIds }) => ({
        col,
        rows: rowIds.map((id) => rowById.get(id)).filter((row): row is Row => row != null),
      })),
    [cellSelection, rowById],
  )

  const commitNow = useCallback(
    (row: Row, col: ColumnKey, commit: CellCommit) => {
      if (isCellSelected(row.id, col)) {
        for (const column of marqueeByColumn) writeRows(column.rows, column.col, commit)
        return
      }
      const targetRows =
        row.kind !== 'divider' && rowSelection.isSelected(row.id)
          ? rowSelection.orderedSelected.map((id) => rowById.get(id)).filter((r): r is Row => r != null)
          : [row]
      writeRows(targetRows, col, commit)
    },
    [isCellSelected, marqueeByColumn, rowSelection, rowById, writeRows],
  )
  const onCellCommit = useThrottledCommit(commitNow)

  const clearSelectedCells = useCallback(() => {
    if (readOnly) return
    for (const { col, rows: colRows } of marqueeByColumn) {
      const plain = new Set<RowId>()
      for (const row of colRows) {
        if (row.kind === 'group') {
          for (const name of groupPropertyNames(row, col)) groupWriters.clearValue(row.name, name)
        } else if (row.kind !== 'divider') plain.add(row.id)
      }
      for (const outer of expandSelectionToTargets(rows, plain)) {
        for (const { target, resolution } of resolveTargetCells(outer, col)) {
          for (const name of resolutionPropertyNames(resolution)) writers.clearValue(target.key, name)
        }
      }
    }
  }, [readOnly, marqueeByColumn, groupWriters, rows, writers])

  const batches = useMemo(
    () => new Map(marqueeByColumn.map(({ col, rows: colRows }) => [col, batchForTargets(expandRows(colRows), col)] as const)),
    [marqueeByColumn],
  )
  const batchFor = useCallback(
    (row: Row, col: ColumnKey): CellBatch => {
      const marquee = isCellSelected(row.id, col) ? batches.get(col) : undefined
      return marquee ?? batchForTargets(rowWriteTargets(row), col)
    },
    [batches, isCellSelected],
  )

  // ── The keyboard, in the region (§4.5) ───────────────────────────────────────────────────────────
  const permission = useMemo<CellKeyboardPermission>(() => ({ entry: !readOnly, clear: !readOnly }), [readOnly])
  const copy = useMemo<CellActionCopy>(
    () =>
      readOnlyReason != null
        ? { setTitle: readOnlyReason, clearTitle: readOnlyReason }
        : {
            setTitle: `Set the selected cells — writes ${store.cueLabel}’s own rows, nothing on stage (⏎)`,
            clearTitle: `Take the selected cells’ own rows away, so the Looks under them show (⌫)`,
          },
    [readOnlyReason, store.cueLabel],
  )
  const firstEditableCell = useCallback((): CueCellRef | undefined => {
    const ordered = orderedSelectedCells(cellSelection.cells, rows.map((row) => row.id), visibleColumns)
    return ordered.find((cell) => {
      const row = rowById.get(cell.rowId)
      return row != null && row.kind !== 'divider' && buildCueRowCells(row, [cell.col]).length > 0
    })
  }, [cellSelection.cells, rows, rowById, visibleColumns])
  const [scrollToRowId, setScrollToRowId] = useState<RowId | null>(null)
  const { keyboardOpen, closeEditorCell, openCellEditor, toggleCellEditor } = useCellEditorRequests<ColumnKey>({
    firstEditableCell,
    onScrollTo: setScrollToRowId,
  })
  const [revealCell, setRevealCell] = useState<CueCellRef | null>(null)
  const cellGrid = useMemo(() => {
    const byRow = new Map<RowId, ReadonlySet<ColumnKey>>()
    const takes = (rowId: RowId, col: ColumnKey) => {
      let cols = byRow.get(rowId)
      if (!cols) {
        const row = rowById.get(rowId)
        cols = new Set(row == null || row.kind === 'divider' ? [] : buildCueRowCells(row, visibleColumns).map((c) => c.col))
        byRow.set(rowId, cols)
      }
      return cols.has(col)
    }
    return { rows: selectableOrder, cols: visibleColumns, takes }
  }, [rowById, selectableOrder, visibleColumns])
  const { anchor: rowAnchor, orderedSelected: rowsOrdered } = rowSelection
  const rowKeys = useMemo<SheetRowKeys>(
    () => ({
      onSelectAll: () => {
        clearCells()
        selectAllRaw()
      },
      onStep: (direction, extend) => {
        const next = arrowStepTarget(selectableOrder, { anchor: rowAnchor, orderedSelected: rowsOrdered }, direction, extend)
        if (next == null) return false
        selectRow(next, extend ? 'range' : 'replace')
        setScrollToRowId(next)
        return true
      },
    }),
    [clearCells, selectAllRaw, selectableOrder, rowAnchor, rowsOrdered, selectRow],
  )
  const cellKeys = useMemo<SheetCellKeys>(
    () => ({
      onStep: (direction, extend) => {
        const head = stepCellSelection(cellSelection, cellGrid, 'grid', direction, extend, () => {
          if (rowCountRef.current > 0) clearRows()
        })
        if (head == null) return false
        setRevealCell(head)
        return true
      },
    }),
    [cellSelection, cellGrid, clearRows],
  )
  // **A refused ⌫ or Enter in the editor is the editor's, never the transport's.** Locked, the show
  // binds ⌫ to BACK (`useTransportKeys`, which stands aside from a claimed key), and a cell
  // selected in the editor is the operator pointing at the cue, not at the running show: so both
  // are claimed here and ask to unlock, as the cue action set's refused actions do — or, where the
  // lock is not the operator's (another project's cue), are swallowed with the reason on the cells.
  // A typed `l` is left alone: it is the keyboard's own way back to editing.
  const dialogs = useCueActionDialogs()
  const { openUnlock } = dialogs
  const onRefused = useCallback(
    ({ key }: SheetKeyRefusal) => {
      if (key !== 'Backspace' && key !== 'Delete' && key !== 'Enter') return false
      if (onRequestUnlock != null) openUnlock(onRequestUnlock)
      return true
    },
    [onRequestUnlock, openUnlock],
  )
  useSheetKeyboard<ColumnKey>({
    cellCount,
    permission,
    isCellSelected,
    onEscape: clearByLadder,
    onOpen: openCellEditor,
    onClear: clearSelectedCells,
    onRefused,
    rowKeys,
    cellKeys,
    region,
  })
  const activeRegion = useActiveKeyRegion()
  const isRegion = activeRegion != null && activeRegion === region.current

  // ⌘Z in the editor: the store's one-step Undo. A text field keeps its own.
  const { undo, canUndo } = store
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (!(e.metaKey || e.ctrlKey) || e.shiftKey || e.altKey || e.key.toLowerCase() !== 'z') return
      const mine = region.current
      if (mine == null) return
      const target = e.target instanceof Element ? e.target : null
      const inEditor = (target != null && mine.contains(target)) || activeRegion === mine
      if (!inEditor || isEditableTarget(target) || !canUndo) return
      e.preventDefault()
      undo()
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
  }, [region, activeRegion, canUndo, undo])

  // ── Rows' gestures ─────────────────────────────────────────────────────────────────────────────
  const onRowClick = useCallback((id: RowId, e: React.MouseEvent) => selectRow(id, listSelectionIntentFor(e)), [selectRow])
  const onToggleExpand = useCallback(
    (row: GroupRow | FixtureRow) => {
      if (row.kind === 'group') toggleGroupExpanded(row.name)
      else toggleFixtureExpanded(row.fixture.key)
    },
    [toggleGroupExpanded, toggleFixtureExpanded],
  )
  const onBeginCellEdit = useCallback(
    (row: Row, col: ColumnKey) => {
      if (row.kind !== 'divider') selectCells([{ rowId: row.id, col }], 'replace')
    },
    [selectCells],
  )
  const onAdd = useCallback(
    (target: CueTarget) => {
      addTarget(target)
      setPickerOpen(false)
    },
    [addTarget],
  )

  const setRef = useRef<HTMLButtonElement | null>(null)
  const [marqueeDragging, setMarqueeDragging] = useState(false)
  const spreadColumns = useMemo<SpreadColumn[]>(
    () => marqueeByColumn.map(({ col, rows: colRows }) => ({ col, targets: expandRows(colRows) })),
    [marqueeByColumn],
  )
  const families = useMemo(() => cellFamilies(cellSelection.cells), [cellSelection.cells])
  const hasSelection = cellCount > 0 || rowSelection.count > 0
  const scopeLabel = `${store.cueLabel} own`

  const viewVerbs = (
    <>
      <Button
        variant={ownOnly ? 'secondary' : 'outline'}
        size="sm"
        aria-pressed={ownOnly}
        onClick={() => setOwnOnly((v) => !v)}
        title="List only the rows this cue holds itself"
      >
        <Filter className="size-3.5" />
        <span className="hidden @[700px]:inline">Own only</span>
      </Button>
      <Popover open={pickerOpen} onOpenChange={setPickerOpen}>
        <PopoverTrigger asChild>
          <Button variant="outline" size="sm" title="Add a fixture or a group to the grid" aria-label="Add fixtures">
            <Plus className="size-3.5" />
            <span className="hidden @[700px]:inline">Fixtures…</span>
          </Button>
        </PopoverTrigger>
        <PopoverContent align="end" className="max-h-[min(24rem,60vh)] w-64 overflow-y-auto p-0">
          <CueTargetPicker onSelect={onAdd} />
        </PopoverContent>
      </Popover>
      <Button
        variant={wholeRig ? 'secondary' : 'outline'}
        size="sm"
        aria-pressed={wholeRig}
        onClick={() => setWholeRig((v) => !v)}
        title="List every fixture in the rig"
      >
        <Grid3x3 className="size-3.5" />
        <span className="hidden @[700px]:inline">Whole rig</span>
      </Button>
    </>
  )

  return (
    <div className="flex min-h-0 flex-1 flex-col" data-cue-values-grid data-key-active={isRegion ? '' : undefined}>
      {dialogs.local}
      <div className="@container">
        <SelectionBar
          rowLabel={rowSelection.count > 0 ? `${rowSelection.count} ${rowSelection.count === 1 ? 'row' : 'rows'}` : null}
          cellLabel={cellCount > 0 ? `${cellCount} ${cellCount === 1 ? 'cell' : 'cells'}` : null}
          family={families.length === 1 ? FAMILY_LABELS[families[0]].singular : null}
          hints={{ entry: !readOnly && cellCount > 0, clear: !readOnly && cellCount > 0 }}
          strip={readOnlyReason != null ? <span className="truncate text-xs text-muted-foreground">{readOnlyReason}</span> : undefined}
          foldForStrip={false}
          marqueeDragging={marqueeDragging}
          // With nothing selected the bar is the kit's reserved row, which draws no verbs — so the
          // three view verbs ride its chip slot there, and the verbs slot once something is.
          chip={hasSelection ? undefined : <div className="ml-auto flex shrink-0 items-center gap-2">{viewVerbs}</div>}
          verbs={
            !hasSelection ? null : (
            <div className="ml-auto flex shrink-0 items-center gap-2">
              {cellCount > 0 && (
                <CellSelectionActions
                  copy={copy}
                  permission={permission}
                  setRef={setRef}
                  onSet={toggleCellEditor}
                  onClear={clearSelectedCells}
                  spread={<SpreadPopover columns={spreadColumns} projectId={store.projectId} scopeLabel={scopeLabel} />}
                />
              )}
              {cellCount > 0 && <span aria-hidden className="h-5 w-px bg-border" />}
              {viewVerbs}
              {hasSelection && (
                <Button variant="ghost" size="sm" onClick={clearByLadder} title="Deselect all">
                  <X className="size-3.5" />
                  <span className="hidden @[700px]:inline">Deselect</span>
                </Button>
              )}
            </div>
            )
          }
        />
      </div>
      {store.cookFailed ? (
        <p className="px-3 py-4 text-xs text-muted-foreground">
          The cue’s composed values could not be read. The desk composes the current project’s cues
          only.
        </p>
      ) : !store.loaded ? (
        <p className="px-3 py-4 text-xs text-muted-foreground">Reading the cue…</p>
      ) : rows.length === 0 ? (
        <p className="px-3 py-4 text-xs text-muted-foreground">
          This cue holds no values. <span className="font-medium">+ Fixtures…</span> or{' '}
          <span className="font-medium">Whole rig</span> to give it some.
        </p>
      ) : (
        <div className={cn('flex min-h-[12rem] flex-1 flex-col', readOnly && 'cursor-default')}>
          <FixturesTable
            rows={rows}
            visibleColumns={visibleColumns}
            isSelected={rowSelection.isSelected}
            onRowClick={onRowClick}
            onToggleExpand={onToggleExpand}
            onBeginCellEdit={onBeginCellEdit}
            onCellCommit={onCellCommit}
            batchFor={batchFor}
            scopeLabel={scopeLabel}
            onShowInfo={noop}
            fixtureColours={fixtureColours}
            scrollToRowId={scrollToRowId}
            onScrolledToRow={() => setScrollToRowId(null)}
            revealCell={revealCell}
            onRevealedCell={() => setRevealCell(null)}
            selectionEmpty={!hasSelection}
            cellSelection={tableCellSelection}
            onRowMarquee={setRows}
            keyboardOpen={keyboardOpen}
            closeEditorCell={closeEditorCell}
            editorAnchorRef={setRef}
            onMarqueeDragChange={setMarqueeDragging}
            onBackgroundClick={clearByLadder}
            selectionDimmed={!isRegion}
          />
        </div>
      )}
    </div>
  )
}

function noop() {}

/**
 * A drag's commits at ~30 Hz with a trailing call — the programmer container's throttle
 * (`FixturesListContainer`'s `scheduleCommit`), for its reason: an editor fires per pointer move, and
 * every commit here re-renders the rows that read the draft. A commit for another cell lands the
 * pending one first, per-axis position commits merge within a window, and an unmount lands what is
 * pending, so nothing is dropped.
 */
function useThrottledCommit(commitNow: (row: Row, col: ColumnKey, commit: CellCommit) => void) {
  const commitNowRef = useRef(commitNow)
  commitNowRef.current = commitNow
  const pendingRef = useRef<{ key: string; row: Row; col: ColumnKey; commit: CellCommit } | null>(null)
  const timerRef = useRef<number | null>(null)
  const flush = useCallback(function flush() {
    const pending = pendingRef.current
    pendingRef.current = null
    if (pending) {
      commitNowRef.current(pending.row, pending.col, pending.commit)
      timerRef.current = window.setTimeout(flush, COMMIT_FLOOR_MS)
    } else {
      timerRef.current = null
    }
  }, [])
  useEffect(
    () => () => {
      if (timerRef.current != null) window.clearTimeout(timerRef.current)
      timerRef.current = null
      const pending = pendingRef.current
      pendingRef.current = null
      if (pending) commitNowRef.current(pending.row, pending.col, pending.commit)
    },
    [],
  )
  return useCallback(
    (row: Row, col: ColumnKey, next: CellCommit) => {
      const key = `${row.id}\u0000${col}`
      const pending = pendingRef.current
      if (pending && pending.key !== key) {
        pendingRef.current = null
        commitNowRef.current(pending.row, pending.col, pending.commit)
      }
      if (timerRef.current == null) {
        commitNowRef.current(row, col, next)
        timerRef.current = window.setTimeout(flush, COMMIT_FLOOR_MS)
        return
      }
      const prev = pendingRef.current
      const commit =
        prev && prev.commit.kind === 'position' && next.kind === 'position' ? mergePositionCommits(prev.commit, next) : next
      pendingRef.current = { key, row, col, commit }
    },
    [flush],
  )
}

/** The throttle's floor — the programmer grid's 33 ms. */
const COMMIT_FLOOR_MS = 33

/** Every head a set of rows stands for — a group row its members. */
function expandRows(rows: readonly Row[]) {
  return expandSelectionToTargets(rows, new Set(rows.map((row) => row.id)))
}

/**
 * A group row's commit, as **one group own row**: the value clamped against the first member that
 * takes the column, written under the group's name. A group whose members cannot take the commit's
 * shape writes nothing.
 *
 * A colour with a white component is written against a member **without** a white channel where
 * the group has one, so the writer folds the white into RGB: the desk writes a group's colour row
 * verbatim on every member and drops the white on a head that has none, so pure white written as
 * `w 255` over a mixed group would black out its RGB heads. The RGBW heads take the fold as an
 * RGB white, the live arm's answer for a mixed group too.
 */
function writeGroupRow(writers: ReturnType<typeof cueCellWriters>, row: GroupRow, col: ColumnKey, commit: CellCommit) {
  const foldsWhite = (member: GroupRow['members'][number]) => {
    const resolution = resolveCell(member.properties, col)
    return resolution?.kind === 'colour' && !resolution.property.whiteChannel
  }
  const members =
    commit.kind === 'colour' && (commit.w ?? 0) > 0
      ? [...row.members.filter(foldsWhite), ...row.members.filter((m) => !foldsWhite(m))]
      : row.members
  for (const member of members) {
    const resolution = resolveCell(member.properties, col)
    if (resolution == null || !commitMatchesResolution(commit, resolution)) continue
    applyPlannedWrite(writers, {
      target: { key: row.name, properties: member.properties },
      resolution,
      commit: clampCommitToResolution(commit, resolution),
    })
    return
  }
}

/** The property names a group row's own row is stored under in this column. */
function groupPropertyNames(row: GroupRow, col: ColumnKey): string[] {
  for (const member of row.members) {
    const resolution = resolveCell(member.properties, col)
    if (resolution != null) return resolutionPropertyNames(resolution)
  }
  return []
}

