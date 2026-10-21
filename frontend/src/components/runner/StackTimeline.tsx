import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from 'react'
import { ChevronLeft, ChevronRight, Crosshair, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import type { CueStack, CueStackCueEntry, CueStackCueRow } from '@/api/cueStacksApi'
import { isCueRow } from '@/api/cueStacksApi'
import type { CueTarget } from '@/api/cuesApi'
import type { SceneryState } from '@/api/sceneryApi'
import { useCueStackTimelineQuery } from '@/store/cueStacks'
import { useCueEdited } from '@/store/cueHolds'
import type { TimelineEditOp } from '@/api/stackTimelineApi'
import { useVisibleFixtureListQuery } from '@/store/fixtures'
import { useGroupListQuery } from '@/store/groups'
import { useTemplateListQuery } from '@/store/templates'
import { templateRowsSwatch } from '@/lib/templateIntent'
import { useStageElementListQuery } from '@/store/stageElements'
import { useDeskSelection } from '@/store/selection'
import { useCueFade } from '@/hooks/useCueFade'
import { formatFadeDuration } from '@/lib/cueUtils'
import { formatMs } from '@/lib/formatMs'
import { clockWords, shortSceneryWords } from '@/lib/scenery'
import { AUTO_CUE_NUMBER_CLASS } from '@/lib/cueNumber'
import { FAMILY_LABELS, type AttributeFamily } from '@/lib/attributeFamily'
import type { CuePlace } from '@/lib/cuePlaces'
import {
  cellAt,
  cells as deriveCells,
  columns as deriveColumns,
  cueRangeWords,
  dragOps,
  dragPlan,
  editsColumn,
  fillDownOps,
  filter as filterColumns,
  kindCounts,
  markerMarkAt,
  moveColumn,
  offOps,
  onOps,
  recordColumnId,
  runMarkAt,
  runs as deriveRuns,
  sceneryColumnId,
  selectedLayers,
  TIMELINE_KINDS,
  toggleOps,
  withExtraColumns,
  type TimelineCellAddress,
  type TimelineCueRef,
  type TimelineCell,
  type TimelineCells,
  type TimelineColumn,
  type TimelineKind,
  type TimelineNames,
  type TimelineRunMark,
  type TimelineRuns,
} from '@/lib/stackTimeline'
import { timelineColumnOrderStore, timelineExtraColumnsStore, timelineFiltersStore } from '@/lib/stackTimeline/columnOrder'
import type { CueEditorTab } from '@/components/cues/editor/cueEditorTab'
import { CueStatePip } from '@/components/cues/CueRowParts'
import { EditedSinceGoChip } from '@/components/cues/EditedSinceGo'
import { SheetTable } from '@/components/sheet/SheetTable'
import { useSheet } from '@/components/sheet/useSheet'
import { SelectionBar } from '@/components/sheet/SelectionBar'
import { LegendSwatch, SheetPage } from '@/components/sheet/SheetPage'
import { CHROME_ROW_CLASS } from '@/components/sheet/sheetFrame'
import type { SheetColumn, SheetRow } from '@/components/sheet/sheetModel'
import { activeKeyRegion } from '@/components/sheet/keyRegion'
import { keyTargetIsGuarded } from '@/components/sheet/cellEntry'
import { editorIsOpen } from '@/components/editor/EditorSurface'
import type { SheetKeyRefusal } from '@/components/sheet/useSheetKeyboard'
import { CueEditorPane } from './CueEditorPane'
import { UnlockShowDialog } from './UnlockShowDialog'
import { useSharedCueActionDialogs } from './cueActionDialogsContext'
import {
  describeEdit,
  TimelineAddColumn,
  TimelineReplacePopover,
  TimelineSceneryPopover,
  TimelineSettingsPopover,
  TimelineVerbs,
  useTimelineCommandKeys,
  useTimelineEdits,
  type TimelineVerbState,
} from './TimelineEditing'
import { SharedCueChip } from './SharedCueChip'
import { entryRowId } from './CueSheet'

/** The live and next rows' edges, as the Table draws them. */
const LIVE_ROW_CLASS = 'bg-green-500/[0.07] shadow-[inset_3px_0_0_rgb(34,197,94)]'
const NEXT_ROW_CLASS = 'bg-blue-500/[0.05] shadow-[inset_3px_0_0_rgb(59,130,246)]'

/** Each kind's bar and its left strip, one constant each so the legend wears the same classes. */
const RUN_CLASS: Record<TimelineKind, string> = {
  look: 'bg-teal-400/20 before:bg-teal-300',
  palette: 'bg-zinc-400/20 before:bg-zinc-300',
  effect: 'bg-violet-400/25 before:bg-violet-300',
  scenery: 'bg-amber-300/25',
}
const KIND_SWATCH: Record<TimelineKind, string> = {
  look: 'bg-teal-300',
  palette: 'bg-zinc-300',
  effect: 'bg-violet-300',
  scenery: 'bg-amber-300',
}
const KIND_LABEL: Record<TimelineKind, string> = { look: 'Looks', palette: 'Palettes', effect: 'Effects', scenery: 'Scenery' }
/** The tracked state's line down a scenery column. */
const TRACK_LINE_CLASS = 'pointer-events-none absolute inset-y-[-1px] left-1/2 -ml-px w-0.5 bg-amber-300/40'

/** The column's tab in the cue editor (C2). */
const PANE_TAB: Record<TimelineKind, CueEditorTab> = { look: 'looks', palette: 'looks', effect: 'fx', scenery: 'scenery' }

const FAMILY_ORDER: readonly AttributeFamily[] = ['INTENSITY', 'COLOUR', 'POSITION', 'BEAM']

export interface TimelineRow extends SheetRow {
  entry: CueStackCueEntry
}

function cueOf(row: TimelineRow): CueStackCueRow {
  if (!isCueRow(row.entry)) throw new Error(`marker row ${row.id} reached a cue column`)
  return row.entry
}

export interface StackTimelineProps {
  stack: CueStack
  projectId: number
  activeEntryId: number | null
  standbyEntryId?: number | null
  /** The playhead's stack, or null when this one is not running: the live row reads its fade from it. */
  fadeStackId?: number | null
  onSetStandby?: (cueId: number, entryId: number) => void
  locked?: boolean
  openedEntryId?: number | null
  onRequestUnlock?: () => void
  onReorder?: (entryIds: number[]) => void
  placesByCue?: ReadonlyMap<number, readonly CuePlace[]>
}

/**
 * A stack's **Timeline** (stack-timeline plan D1–D11, §4.2, §4.4): the Table's rows — cues, markers
 * as divider rows, row drag, the lock and the bottom pane — against a column per Look, palette,
 * effect and scenery element the stack applies, derived from the desk's read
 * (`GET …/cue-stacks/{id}/timeline`) by `lib/stackTimeline/`. A filled cell is that cue's own
 * layer, row or change; neighbouring cells draw as one bar, an effect's only on a shared run key.
 *
 * **Editing Looks, palettes and scenery** (§4.4): a click selects a cell and a second click — or ⏎ —
 * toggles it (a scenery cell's second click opens its popover), ⌫ turns the selection off, a run's
 * end drags to fill or empty the cells it crosses, and the bar's *On · Off · Fill ↓ · Replace with…
 * · Settings…* act on a range. **Every gesture is one request** to `timeline/edit`
 * (`useTimelineEdits`), nothing is sent to the stage, and its answer is one Undo (⌘Z, or the toast).
 * Locked, the cells read and every write asks to unlock. Effect cells select and read only.
 *
 * One selected cell opens `CueEditorPane` on its cue at its column's tab; a range over several cues
 * folds it (C2).
 */
export function StackTimeline({
  stack,
  projectId,
  activeEntryId,
  standbyEntryId,
  fadeStackId,
  onSetStandby,
  locked = false,
  openedEntryId,
  onRequestUnlock,
  onReorder,
  placesByCue,
}: StackTimelineProps) {
  const { data: timeline } = useCueStackTimelineQuery({ projectId, stackId: stack.id })
  const { data: fixtures } = useVisibleFixtureListQuery()
  const { data: groups } = useGroupListQuery()
  const { data: elements } = useStageElementListQuery(projectId)
  const deskSelection = useDeskSelection()
  const { data: palettes } = useTemplateListQuery({ projectId })
  /** A generic colour palette's colour, as its busk pad draws it; a per-fixture one has none. */
  const swatches = useMemo(
    () => new Map((palettes ?? []).flatMap((p) => (p.isGeneric ? [[p.id, templateRowsSwatch(p.rows)] as const] : []))),
    [palettes],
  )

  const orderStore = timelineColumnOrderStore(stack.id)
  const order = useSyncExternalStore(orderStore.subscribe, orderStore.getSnapshot, orderStore.getServerSnapshot)
  const filters = useSyncExternalStore(timelineFiltersStore.subscribe, timelineFiltersStore.getSnapshot, timelineFiltersStore.getServerSnapshot)
  const extraStore = timelineExtraColumnsStore(stack.id)
  const extras = useSyncExternalStore(extraStore.subscribe, extraStore.getSnapshot, extraStore.getServerSnapshot)

  /** The rig as the timeline reads it: each cell's parent, each fixture's cells, each group's members. */
  const rig = useMemo(() => {
    const parentByCell = new Map<string, string>()
    const cellsOf = new Map<string, string[]>()
    for (const fixture of fixtures ?? []) {
      const cells = (fixture.elements ?? []).map((e) => e.key)
      cellsOf.set(fixture.key, cells)
      for (const cell of cells) parentByCell.set(cell, fixture.key)
    }
    const members = new Map((groups ?? []).map((g) => [g.name, g.memberKeys ?? []]))
    return { parentByCell, cellsOf, members }
  }, [fixtures, groups])

  const names = useMemo<TimelineNames>(() => {
    const fixtureNames = new Map((fixtures ?? []).map((f) => [f.key, f.name]))
    const byUuid = new Map((elements ?? []).map((e) => [e.uuid, e]))
    return {
      target: (t: CueTarget) => (t.type === 'fixture' ? (fixtureNames.get(t.key) ?? t.key) : t.key),
      element: (uuid) => {
        const element = byUuid.get(uuid)
        return element == null ? undefined : { name: element.name, kind: element.kind.toLowerCase() }
      },
      members: (group) => rig.members.get(group),
      parentOf: (key) => rig.parentByCell.get(key),
    }
  }, [elements, fixtures, rig])

  /**
   * The desk selection's heads, groups expanded through the cached group list, as the desk's
   * parent↔cell rule meets a layer's: a selected fixture reaches its cells, and a selected cell is
   * reached by a layer on its parent.
   */
  const selectionHeads = useMemo(() => {
    const heads = new Set<string>()
    const add = (key: string) => {
      heads.add(key)
      for (const cell of rig.cellsOf.get(key) ?? []) heads.add(cell)
      const parent = rig.parentByCell.get(key)
      if (parent != null) heads.add(parent)
    }
    for (const target of deskSelection) {
      if (target.type === 'group') for (const key of rig.members.get(target.key) ?? [target.key]) add(key)
      else add(target.key)
    }
    return heads
  }, [deskSelection, rig])
  const selectionOn = filters.selectionOnly && selectionHeads.size > 0

  const derivedColumns = useMemo(() => (timeline == null ? [] : deriveColumns(timeline, names, order)), [names, order, timeline])
  const allColumns = useMemo(() => withExtraColumns(derivedColumns, extras, names), [derivedColumns, extras, names])
  // An added column the stack now fills is derived like any other: the window lets go of it.
  useEffect(() => {
    if (extras.length === 0 || timeline == null) return
    const derived = new Set(derivedColumns.map((c) => c.id))
    const kept = extras.filter((e) => !derived.has(recordColumnId(e.kind, e.sourceId, e.target)))
    if (kept.length !== extras.length) extraStore.set(kept)
  }, [derivedColumns, extraStore, extras, timeline])
  const facet = useMemo(
    () => ({ family: filters.family, selectionHeads: selectionOn ? selectionHeads : null }),
    [filters.family, selectionHeads, selectionOn],
  )
  const kinds = useMemo(() => new Set(filters.kinds), [filters.kinds])
  const shown = useMemo(() => filterColumns(allColumns, { kinds, ...facet }), [allColumns, facet, kinds])
  const counts = useMemo(() => kindCounts(allColumns, facet), [allColumns, facet])
  const grid = useMemo<TimelineCells>(() => (timeline == null ? new Map() : deriveCells(timeline, shown)), [shown, timeline])
  /** Each cue entry's cue entry above it, markers skipped: what an effect cell's ◆ names the change against. */
  const previousEntry = useMemo(() => {
    const out = new Map<number, number>()
    const cues = stack.cues.filter(isCueRow)
    cues.forEach((c, i) => i > 0 && out.set(c.entryId, cues[i - 1].entryId))
    return out
  }, [stack.cues])
  const marks = useMemo<TimelineRuns>(
    () => (timeline == null ? { cue: new Map(), marker: new Map() } : deriveRuns(timeline, shown, grid)),
    [grid, shown, timeline],
  )

  const rows = useMemo<TimelineRow[]>(
    () =>
      stack.cues.map((entry) =>
        isCueRow(entry) ? { id: entryRowId(entry.entryId), entry } : { id: entryRowId(entry.entryId), entry, divider: entry.name },
      ),
    [stack.cues],
  )

  // ── Editing (§4.4) ────────────────────────────────────────────────────────────────────────────
  const cueOrder = useMemo<TimelineCueRef[]>(() => stack.cues.filter(isCueRow).map((c) => ({ entryId: c.entryId, cueId: c.id })), [stack.cues])
  const byId = useMemo(() => new Map(allColumns.map((c) => [c.id, c])), [allColumns])
  const cueLabel = useCallback(
    (cueId: number) => {
      const cue = stack.cues.find((c): c is CueStackCueRow => isCueRow(c) && c.id === cueId)
      return cue == null ? `cue ${cueId}` : cue.cueNumber ? `Q${cue.cueNumber}` : cue.name
    },
    [stack.cues],
  )
  const cuesWords = useCallback((ids: readonly number[]) => cueRangeWords(cueOrder, ids, cueLabel), [cueLabel, cueOrder])
  const edits = useTimelineEdits(projectId, stack.id)
  const columnOfOp = useCallback(
    (op: TimelineEditOp) => {
      if (op.op === 'layerOn' || op.op === 'layerOff') {
        return byId.get(recordColumnId(op.source.kind === 'LOOK' ? 'look' : 'palette', op.source.id, op.target ?? null))
      }
      if (op.op === 'scenerySet' || op.op === 'sceneryClear') return byId.get(sceneryColumnId(op.elementUuid))
      return undefined
    },
    [byId],
  )
  const { send } = edits
  const gesture = useCallback(
    (ops: TimelineEditOp[]) => void send(ops, (answer) => describeEdit(ops, answer, columnOfOp, cuesWords)),
    [columnOfOp, cuesWords, send],
  )

  const [unlockAsked, setUnlockAsked] = useState(false)
  const askToUnlock = useCallback(() => setUnlockAsked(true), [])
  /** Locked, a write asks to unlock — where the lock is the operator's to lift. */
  const refuseVerb = locked && onRequestUnlock ? askToUnlock : undefined

  const [sceneryEdit, setSceneryEdit] = useState<{ entryId: number; columnId: string } | null>(null)
  const [drag, setDrag] = useState<{ columnId: string; fromEntryId: number; toEntryId: number; x: number; y: number } | null>(null)
  const dragColumn = drag == null ? null : (byId.get(drag.columnId) ?? null)
  const dragGhosts = useMemo(
    () => (drag == null || dragColumn == null ? null : dragPlan(cueOrder, grid, dragColumn, drag.fromEntryId, drag.toEntryId)),
    [cueOrder, drag, dragColumn, grid],
  )

  /** A cell's click: select it, or — when it is already the one selected cell — toggle it. */
  const clickCellRef = useRef<(row: TimelineRow, column: TimelineColumn, select: () => void) => void>(() => {})
  const startDragRef = useRef<(column: TimelineColumn, entryId: number, e: React.PointerEvent) => void>(() => {})

  const move = useCallback(
    (id: string, direction: -1 | 1) => orderStore.set(moveColumn(allColumns, id, direction, shown)),
    [allColumns, orderStore, shown],
  )

  const columns = useMemo<SheetColumn<TimelineRow, string>[]>(() => {
    const groupCounts = new Map<TimelineKind, number>()
    for (const c of shown) groupCounts.set(c.kind, (groupCounts.get(c.kind) ?? 0) + 1)
    const fixed: SheetColumn<TimelineRow, string>[] = [
      {
        key: '__fade',
        label: 'Fade',
        width: '52px',
        value: () => undefined,
        display: (row) => (
          <span className="px-1.5 font-mono text-[11px] tabular-nums">{formatFadeDuration(cueOf(row).fadeDurationMs) || 'SNAP'}</span>
        ),
      },
      {
        key: '__follow',
        label: 'Follow',
        width: '64px',
        value: () => undefined,
        display: (row) =>
          cueOf(row).autoAdvance ? (
            <span className="inline-flex items-center gap-1 px-1.5 text-[11px]">
              <span className="text-[9px] uppercase tracking-wide text-blue-500">auto</span>
              <span className="font-mono tabular-nums">{formatMs(cueOf(row).autoAdvanceDelayMs ?? 0)}</span>
            </span>
          ) : (
            <span className="px-1.5 text-xs text-muted-foreground/60">—</span>
          ),
      },
    ]
    return [
      ...fixed,
      ...shown.map((column, index): SheetColumn<TimelineRow, string> => {
        const swatch = column.kind === 'palette' && column.sourceId != null ? (swatches.get(column.sourceId) ?? null) : null
        const dragging = drag?.columnId === column.id
        return {
        key: column.id,
        label: `${column.name} · ${column.sub}`,
        width: column.kind === 'effect' || column.kind === 'scenery' ? '92px' : '84px',
        gutter: false,
        group: {
          key: column.kind,
          label: (
            <>
              <i aria-hidden className={cn('size-2 shrink-0 rounded-[2px]', KIND_SWATCH[column.kind])} />
              {KIND_LABEL[column.kind]} · {groupCounts.get(column.kind)}
            </>
          ),
        },
        header: (
          <TimelineHeader
            column={column}
            swatch={swatch}
            canLeft={index > 0 && shown[index - 1].kind === column.kind}
            canRight={index < shown.length - 1 && shown[index + 1].kind === column.kind}
            onMove={move}
          />
        ),
        // Every cue row has the cell, filled or not: an empty one selects too, and opens its cue.
        value: (row) => (isCueRow(row.entry) ? row.entry.entryId : undefined),
        cell: (row, props) => (
          <TimelineCellView
            onClick={() => clickCellRef.current(row, column, props.onBeginEdit)}
            swatch={swatch}
            column={column}
            cell={cellAt(grid, row.entry.entryId, column.id)}
            mark={runMarkAt(marks, row.entry.entryId, column.id)}
            previous={previousEntry.has(row.entry.entryId) ? cellAt(grid, previousEntry.get(row.entry.entryId)!, column.id) : null}
            ghost={dragging ? (dragGhosts?.get(row.entry.entryId) ?? null) : null}
            onHandle={
              !locked && (column.kind === 'look' || column.kind === 'palette')
                ? (e) => startDragRef.current(column, row.entry.entryId, e)
                : undefined
            }
          />
        ),
        dividerCell: (row) => <TimelineMarkerCell column={column} entryId={row.entry.entryId} marks={marks} />,
        cellTitle: (row) => cellTitle(column, cellAt(grid, row.entry.entryId, column.id), cueOf(row)),
        }
      }),
    ]
  }, [drag?.columnId, dragGhosts, grid, locked, marks, move, previousEntry, shown, swatches])

  const permission = useMemo(() => ({ entry: !locked, clear: !locked }), [locked])
  const copy = useCallback(
    () =>
      locked
        ? { setTitle: LOCKED_REASON, clearTitle: LOCKED_REASON }
        : { setTitle: 'Toggle the selected cells (Enter)', clearTitle: 'Take the selected cells off (Backspace)' },
    [locked],
  )
  /**
   * Locked, ⏎ asks to unlock — Enter alone, as on the Table: while a show is locked
   * `useTransportKeys` owns Backspace (BACK) and a typed `l` (the lock toggle).
   */
  const refuseKey = useMemo(() => {
    if (!locked || !onRequestUnlock) return undefined
    return ({ key }: SheetKeyRefusal) => {
      if (key !== 'Enter') return false
      setUnlockAsked(true)
      return true
    }
  }, [locked, onRequestUnlock])

  const activateRef = useRef<() => void>(() => {})
  const clearRef = useRef<() => void>(() => {})
  const onActivate = useCallback(() => activateRef.current(), [])
  const onClearCells = useCallback(() => clearRef.current(), [])
  const sheet = useSheet<TimelineRow, string>({
    rows,
    columns,
    permission,
    copy,
    onRefused: refuseKey,
    onActivate,
    onClearCells,
    noun: 'cue',
    rowName: (row) => (cueOf(row).cueNumber ? `Q${cueOf(row).cueNumber}` : cueOf(row).name),
  })
  const { selectedRows, cellCount, setRows, scrollTo, cellSelection } = sheet

  /** The selected cells this session edits — Look, palette and scenery cells of cue rows. */
  const addresses = useMemo<TimelineCellAddress[]>(
    () =>
      cellSelection.cells.flatMap((cell) => {
        const entryId = Number(String(cell.rowId).slice('entry:'.length))
        const column = byId.get(cell.col)
        return Number.isFinite(entryId) && column != null && editsColumn(column) ? [{ entryId, columnId: cell.col }] : []
      }),
    [byId, cellSelection.cells],
  )
  const verbState = useMemo<TimelineVerbState>(() => {
    const layerColumns = new Set(addresses.map((a) => a.columnId).filter((id) => {
      const kind = byId.get(id)?.kind
      return kind === 'look' || kind === 'palette'
    }))
    let replace: TimelineVerbState['replace'] = null
    if (layerColumns.size === 1 && addresses.every((a) => layerColumns.has(a.columnId))) {
      const column = byId.get([...layerColumns][0])!
      const cueIds = addresses.flatMap((a) =>
        cellAt(grid, a.entryId, a.columnId).filled != null ? cueOrder.filter((c) => c.entryId === a.entryId).map((c) => c.cueId) : [],
      )
      if (cueIds.length > 0 && column.sourceId != null) replace = { column, cueIds }
    }
    return {
      on: onOps(cueOrder, grid, byId, addresses),
      off: offOps(cueOrder, grid, byId, addresses),
      fillDown: fillDownOps(cueOrder, grid, byId, addresses),
      replace,
      layers: selectedLayers(cueOrder, grid, byId, addresses),
    }
  }, [addresses, byId, cueOrder, grid])

  activateRef.current = () => {
    if (addresses.length === 0) return
    const allFilled = addresses.every((a) => cellAt(grid, a.entryId, a.columnId).filled != null)
    gesture(allFilled ? verbState.off : verbState.on)
  }
  clearRef.current = () => gesture(verbState.off)

  const soleCell = cellCount === 1 ? `${cellSelection.cells[0].rowId}|${cellSelection.cells[0].col}` : null
  clickCellRef.current = (row, column, select) => {
    if (soleCell !== `${row.id}|${column.id}`) {
      select()
      return
    }
    if (!editsColumn(column)) return
    if (locked) {
      refuseVerb?.()
      return
    }
    if (column.kind === 'scenery') {
      setSceneryEdit({ entryId: row.entry.entryId, columnId: column.id })
      return
    }
    gesture(toggleOps(cueOrder, grid, column, row.entry.entryId))
  }

  startDragRef.current = (column, entryId, e) => {
    e.preventDefault()
    e.stopPropagation()
    setDrag({ columnId: column.id, fromEntryId: entryId, toEntryId: entryId, x: e.clientX, y: e.clientY })
  }
  // The drag follows the pointer over the rows; its release is one request.
  const dragRef = useRef(drag)
  dragRef.current = drag
  const finishDragRef = useRef<() => void>(() => {})
  finishDragRef.current = () => {
    const current = dragRef.current
    setDrag(null)
    if (current == null) return
    const column = byId.get(current.columnId)
    if (column != null) gesture(dragOps(cueOrder, grid, column, current.fromEntryId, current.toEntryId))
  }
  const dragging = drag != null
  useEffect(() => {
    if (!dragging) return
    const onMove = (e: PointerEvent) => {
      const row = document.elementFromPoint(e.clientX, e.clientY)?.closest('[data-row-id]')?.getAttribute('data-row-id') ?? null
      const entryId = row?.startsWith('entry:') ? Number(row.slice('entry:'.length)) : null
      setDrag((d) =>
        d == null ? d : { ...d, x: e.clientX, y: e.clientY, toEntryId: entryId != null && cueOrder.some((c) => c.entryId === entryId) ? entryId : d.toEntryId },
      )
    }
    const onUp = () => finishDragRef.current()
    const onCancel = () => setDrag(null)
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setDrag(null)
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    window.addEventListener('pointercancel', onCancel)
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
      window.removeEventListener('pointercancel', onCancel)
      window.removeEventListener('keydown', onKey)
    }
  }, [cueOrder, dragging])

  useTimelineCommandKeys({
    guarded: (e) => activeKeyRegion() != null || keyTargetIsGuarded(e.target) || editorIsOpen(),
    onFillDown: () => {
      if (cellCount === 0) return false
      if (locked) refuseVerb?.()
      else gesture(verbState.fillDown)
      return true
    },
    onUndo: () => {
      if (!edits.canUndo) return false
      if (locked) refuseVerb?.()
      else void edits.undo()
      return true
    },
  })

  // ── The pane (C2): one selected cue opens it, at the selected cell's column's tab. ──
  const columnRef = useRef<HTMLDivElement>(null)
  const sheetRef = useRef<HTMLDivElement>(null)
  const paneRow = selectedRows.length === 1 && isCueRow(selectedRows[0].entry) ? selectedRows[0] : null
  const singleCell = cellCount === 1 ? cellSelection.cells[0] : null
  const cellKind = singleCell == null ? null : (shown.find((c) => c.id === singleCell.col)?.kind ?? null)
  const [tabRequest, setTabRequest] = useState<{ key: string; tab: CueEditorTab; seq: number } | null>(null)
  const cellKey = singleCell == null || cellKind == null ? null : `${singleCell.rowId}|${singleCell.col}`
  useEffect(() => {
    if (cellKey == null || cellKind == null) return
    setTabRequest((prev) => (prev?.key === cellKey ? prev : { key: cellKey, tab: PANE_TAB[cellKind], seq: (prev?.seq ?? 0) + 1 }))
  }, [cellKey, cellKind])

  const paneRowId = paneRow?.id ?? null
  const keepPaneRowInView = useCallback(() => {
    if (paneRowId == null) return
    sheetRef.current?.querySelector(`[data-row-id="${paneRowId}"]`)?.scrollIntoView?.({ block: 'nearest' })
  }, [paneRowId])
  useEffect(keepPaneRowInView, [keepPaneRowInView])

  // The deep link, once per id — the Table's rule.
  const consumedRef = useRef<number | null>(null)
  useEffect(() => {
    if (openedEntryId == null) {
      consumedRef.current = null
      return
    }
    if (consumedRef.current === openedEntryId) return
    const row = rows.find((r) => r.divider == null && r.entry.entryId === openedEntryId)
    if (row == null) return
    consumedRef.current = openedEntryId
    setRows([row.id])
    scrollTo(row.id)
  }, [openedEntryId, rows, scrollTo, setRows])

  /** Arming is what a click on the cue column means while locked, as on the Table. */
  const onRowClick = useCallback(
    (id: string, e: React.MouseEvent) => {
      if (locked && onSetStandby) {
        const row = rows.find((r) => r.id === id && r.divider == null)
        if (row && row.entry.entryId !== activeEntryId) onSetStandby(cueOf(row).id, row.entry.entryId)
        return
      }
      sheet.tableProps.onRowClick(id, e)
    },
    [activeEntryId, locked, onSetStandby, rows, sheet.tableProps],
  )

  // The live row selected and edited since GO: the bar offers *Update stage…* (show-cue-editing D4).
  const dialogs = useSharedCueActionDialogs()
  const liveRow = activeEntryId == null ? null : (selectedRows.find((r) => isCueRow(r.entry) && r.entry.entryId === activeEntryId) ?? null)
  const liveCue = liveRow == null ? null : cueOf(liveRow)
  const liveEdited = useCueEdited(projectId, liveCue?.id ?? -1)

  const setFilters = (patch: Partial<typeof filters>) => timelineFiltersStore.set({ ...filters, ...patch })
  const toggleKind = (kind: TimelineKind) =>
    setFilters({ kinds: filters.kinds.includes(kind) ? filters.kinds.filter((k) => k !== kind) : [...filters.kinds, kind] })

  const standardCount = rows.filter((row) => row.divider == null).length
  const markerCount = rows.length - standardCount
  const rowNoun = selectedRows.length === 1 ? 'cue' : 'cues'

  const elementsByUuid = useMemo(() => new Map((elements ?? []).map((e) => [e.uuid, e])), [elements])
  const sceneryColumn = sceneryEdit == null ? null : (byId.get(sceneryEdit.columnId) ?? null)
  const sceneryElement = sceneryColumn?.elementUuid ? elementsByUuid.get(sceneryColumn.elementUuid) : undefined
  const sceneryCue = sceneryEdit == null ? undefined : cueOrder.find((c) => c.entryId === sceneryEdit.entryId)
  const sceneryCell = sceneryEdit == null ? null : cellAt(grid, sceneryEdit.entryId, sceneryEdit.columnId)
  const sceneryChange = sceneryCell?.filled?.kind === 'scenery' ? sceneryCell.filled : null

  const dragScope =
    drag == null || dragColumn == null || dragGhosts == null || dragGhosts.size === 0
      ? null
      : `${dragColumn.name} · ${dragColumn.sub} ${[...dragGhosts.values()][0] === 'fill' ? 'on' : 'off'} in ${cuesWords(
          [...dragGhosts.keys()].flatMap((entryId) => cueOrder.filter((c) => c.entryId === entryId).map((c) => c.cueId)),
        )}`

  return (
    <div ref={columnRef} className="flex min-h-0 flex-1 flex-col" data-stack-timeline>
      {/* Row B: the filters (§4.3), per window, and + Column. */}
      <div className={cn(CHROME_ROW_CLASS, 'overflow-x-auto')} data-timeline-filters>
        <span className="shrink-0 text-[11px] uppercase tracking-wider text-muted-foreground">Show</span>
        {TIMELINE_KINDS.map((kind) => (
          <Button
            key={kind}
            variant="outline"
            size="sm"
            aria-pressed={filters.kinds.includes(kind)}
            className={cn('h-7 shrink-0 gap-1.5 px-2 text-xs', !filters.kinds.includes(kind) && 'text-muted-foreground opacity-60')}
            onClick={() => toggleKind(kind)}
          >
            <i aria-hidden className={cn('size-2 rounded-[2px]', KIND_SWATCH[kind])} />
            {KIND_LABEL[kind]}
            <span className="font-mono tabular-nums text-muted-foreground">{counts[kind]}</span>
          </Button>
        ))}
        <span aria-hidden className="mx-1 h-5 w-px shrink-0 bg-border" />
        <div role="group" aria-label="Family" className="inline-flex shrink-0 items-center gap-0.5 rounded-md border bg-card p-0.5">
          {[null, ...FAMILY_ORDER].map((family) => (
            <button
              key={family ?? 'all'}
              type="button"
              aria-pressed={filters.family === family}
              onClick={() => setFilters({ family })}
              className={cn(
                'rounded px-2 py-0.5 text-xs',
                filters.family === family ? 'bg-muted font-semibold text-foreground' : 'text-muted-foreground hover:text-foreground',
              )}
            >
              {family == null ? 'All' : FAMILY_LABELS[family].singular}
            </button>
          ))}
        </div>
        <span aria-hidden className="mx-1 h-5 w-px shrink-0 bg-border" />
        <Button
          variant="outline"
          size="sm"
          aria-pressed={filters.selectionOnly}
          disabled={selectionHeads.size === 0 && !filters.selectionOnly}
          className={cn('h-7 shrink-0 gap-1.5 px-2 text-xs', filters.selectionOnly && 'border-primary text-foreground')}
          onClick={() => setFilters({ selectionOnly: !filters.selectionOnly })}
          title={selectionHeads.size === 0 ? 'Nothing is selected on the desk' : 'Only the columns that reach the desk selection'}
        >
          <Crosshair className="size-3.5" />
          Selection only
        </Button>
        <span className="shrink-0 truncate text-[11px] text-muted-foreground">
          {selectionHeads.size === 0 ? 'no desk selection' : `desk selection: ${selectionHeads.size} head${selectionHeads.size === 1 ? '' : 's'}`}
        </span>
        <span aria-hidden className="mx-1 h-5 w-px shrink-0 bg-border" />
        <TimelineAddColumn
          projectId={projectId}
          deskTargets={deskSelection}
          targetName={names.target}
          onAdd={(column) => extraStore.set([...extras.filter((e) => recordColumnId(e.kind, e.sourceId, e.target) !== recordColumnId(column.kind, column.sourceId, column.target)), column])}
        />
      </div>
      <div className="@container">
        <SelectionBar
          rowLabel={selectedRows.length > 0 ? `${selectedRows.length} ${rowNoun}` : null}
          cellLabel={cellCount > 0 ? `${cellCount} cell${cellCount === 1 ? '' : 's'}` : null}
          cellTitle={`${cellCount} cell${cellCount === 1 ? '' : 's'}`}
          family={null}
          hints={{ entry: addresses.length > 0 && !locked, clear: addresses.length > 0 && !locked }}
          hintWords={{ entry: 'toggle', clear: 'off' }}
          verbs={
            selectedRows.length > 0 ? (
              <>
                {addresses.length > 0 && (
                  <TimelineVerbs
                    state={verbState}
                    locked={locked}
                    lockedReason={LOCKED_REASON}
                    onRefused={refuseVerb}
                    onSend={(ops) => gesture(ops)}
                    replacePopover={(trigger) =>
                      verbState.replace == null || timeline == null ? (
                        trigger
                      ) : (
                        <TimelineReplacePopover
                          trigger={trigger}
                          projectId={projectId}
                          column={verbState.replace.column}
                          cueIds={verbState.replace.cueIds}
                          timeline={timeline}
                          deskTargets={deskSelection}
                          targetName={names.target}
                          cues={cuesWords}
                          onReplace={(op) => gesture([op])}
                        />
                      )
                    }
                    settingsPopover={(trigger) =>
                      verbState.layers.length === 0 ? (
                        trigger
                      ) : (
                        <TimelineSettingsPopover
                          trigger={trigger}
                          projectId={projectId}
                          layers={verbState.layers}
                          cues={cuesWords}
                          onWrite={(ops) => gesture(ops)}
                        />
                      )
                    }
                  />
                )}
                {liveCue != null && liveEdited && dialogs != null && (
                  <Button
                    variant="outline"
                    size="sm"
                    className="border-amber-500/60 text-amber-300"
                    disabled={locked && onRequestUnlock == null}
                    title={`${cueLabel(liveCue.id)} is live and edited since GO`}
                    onClick={() =>
                      locked ? refuseVerb?.() : dialogs.openUpdateStage({ projectId, cueId: liveCue.id, name: liveCue.name })
                    }
                  >
                    Update stage…
                  </Button>
                )}
                <Button variant="ghost" size="sm" onClick={sheet.clearByLadder} title="Deselect all">
                  <X className="size-3.5" />
                  <span className="hidden sm:inline">Deselect</span>
                </Button>
              </>
            ) : null
          }
          marqueeDragging={sheet.marqueeDragging}
        />
      </div>
      <div ref={sheetRef} className="flex min-h-0 flex-1 flex-col" data-timeline-body>
        <SheetTable<TimelineRow, string>
          {...sheet.tableProps}
          onRowClick={onRowClick}
          rowDrag={
            onReorder
              ? {
                  enabled: !locked,
                  onReorder: (ids) => onReorder(ids.map((id) => Number(id.slice('entry:'.length))).filter(Number.isFinite)),
                }
              : undefined
          }
          minWidth={`${260 + 116 + shown.reduce((n, c) => n + (c.kind === 'effect' || c.kind === 'scenery' ? 92 : 84), 0)}px`}
          rowClass={(row) =>
            !isCueRow(row.entry)
              ? undefined
              : row.entry.entryId === activeEntryId
                ? LIVE_ROW_CLASS
                : row.entry.entryId === standbyEntryId
                  ? NEXT_ROW_CLASS
                  : undefined
          }
          firstColumn={{
            label: 'Cue',
            width: '260px',
            render: (row) => (
              <TimelineCueLead
                cue={cueOf(row)}
                projectId={projectId}
                active={row.entry.entryId === activeEntryId}
                standby={row.entry.entryId === standbyEntryId && row.entry.entryId !== activeEntryId}
                fadeStackId={fadeStackId}
                places={placesByCue?.get(cueOf(row).id)}
              />
            ),
          }}
        />
      </div>
      {paneRow != null && (
        <CueEditorPane
          projectId={projectId}
          cueId={cueOf(paneRow).id}
          entryId={paneRow.entry.entryId}
          locked={locked}
          onRequestUnlock={onRequestUnlock}
          tabRequest={tabRequest != null && singleCell?.rowId === paneRow.id ? tabRequest : null}
          columnRef={columnRef}
          sheetRef={sheetRef}
          onResized={keepPaneRowInView}
        />
      )}
      <TimelineLegend cues={standardCount} markers={markerCount} columns={shown.length} />
      {sceneryEdit != null && sceneryElement != null && sceneryCue != null && sceneryColumn?.elementUuid != null && (
        <TimelineSceneryPopover
          key={`${sceneryEdit.entryId}|${sceneryEdit.columnId}`}
          anchor={() =>
            sheetRef.current?.querySelector(`[data-row-id="${entryRowId(sceneryEdit.entryId)}"] [data-cell="${CSS.escape(sceneryEdit.columnId)}"]`) ?? null
          }
          element={sceneryElement}
          cueLabel={cueLabel(sceneryCue.cueId)}
          state={sceneryChange?.state ?? null}
          tracked={sceneryCell?.tracked ?? null}
          transitionMs={sceneryChange?.transitionMs ?? null}
          onClose={() => setSceneryEdit(null)}
          onSet={(state, transitionMs) =>
            gesture([{ op: 'scenerySet', cueId: sceneryCue.cueId, elementUuid: sceneryColumn.elementUuid!, state, transitionMs }])
          }
          onClear={() => {
            gesture([{ op: 'sceneryClear', cueId: sceneryCue.cueId, elementUuid: sceneryColumn.elementUuid! }])
            setSceneryEdit(null)
          }}
        />
      )}
      {dragScope != null && drag != null && (
        <div
          className="pointer-events-none fixed z-50 rounded-md border bg-popover px-2 py-1 text-xs shadow-md"
          style={{ left: drag.x + 14, top: drag.y + 14 }}
          data-timeline-drag-scope
        >
          {dragScope}
        </div>
      )}
      <UnlockShowDialog open={unlockAsked} onOpenChange={setUnlockAsked} onUnlock={() => onRequestUnlock?.()} />
    </div>
  )
}

/** Why a cell is read-only while the show is locked. */
const LOCKED_REASON = 'The show is locked — unlock to edit'

function TimelineCueLead({
  cue,
  projectId,
  active,
  standby,
  fadeStackId,
  places,
}: {
  cue: CueStackCueRow
  projectId: number
  active: boolean
  standby: boolean
  fadeStackId?: number | null
  places?: readonly CuePlace[]
}) {
  const { fadeProgress } = useCueFade(fadeStackId, cue.id, cue.fadeDurationMs, cue.entryId)
  return (
    <span className="flex min-w-0 flex-1 items-center gap-2" data-cue-row={cue.id} data-entry-row={cue.entryId}>
      <CueStatePip isActive={active} isStandby={standby} />
      <span className={cn('w-11 shrink-0 truncate font-mono text-sm', cue.cueNumberAuto ? AUTO_CUE_NUMBER_CLASS : 'font-semibold')}>
        {cue.cueNumber ? `Q${cue.cueNumber}` : '—'}
      </span>
      <span
        className={cn(
          'min-w-0 truncate text-sm',
          active ? 'font-semibold text-green-300' : standby ? 'font-semibold text-blue-300' : 'font-medium',
        )}
      >
        {cue.name}
      </span>
      <SharedCueChip places={places} entryId={cue.entryId} interactive={false} />
      <EditedSinceGoChip projectId={projectId} cueId={cue.id} />
      {fadeProgress != null && (
        <i
          aria-hidden
          data-testid="timeline-fade"
          className="pointer-events-none absolute bottom-0 left-0 h-0.5 bg-green-500"
          style={{ width: `${Math.round(fadeProgress * 100)}%` }}
        />
      )}
    </span>
  )
}

function TimelineHeader({
  column,
  swatch,
  canLeft,
  canRight,
  onMove,
}: {
  column: TimelineColumn
  swatch: string | null
  canLeft: boolean
  canRight: boolean
  onMove: (id: string, direction: -1 | 1) => void
}) {
  return (
    <span className="group/head relative flex min-w-0 flex-col leading-tight" title={`${column.name} · ${column.sub}`}>
      <b className="flex min-w-0 items-center gap-1 text-[11.5px] font-semibold text-foreground">
        {swatch != null && <PaletteSwatch colour={swatch} />}
        <span className="truncate">{column.name}</span>
      </b>
      <span className="truncate text-[10px] text-muted-foreground">{column.sub}</span>
      <span className="absolute -top-0.5 right-0 hidden gap-0.5 group-hover/head:inline-flex">
        {canLeft && (
          <button type="button" aria-label={`Move ${column.name} left`} onClick={() => onMove(column.id, -1)} className="rounded bg-background/80 text-muted-foreground hover:text-foreground">
            <ChevronLeft className="size-3" />
          </button>
        )}
        {canRight && (
          <button type="button" aria-label={`Move ${column.name} right`} onClick={() => onMove(column.id, 1)} className="rounded bg-background/80 text-muted-foreground hover:text-foreground">
            <ChevronRight className="size-3" />
          </button>
        )}
      </span>
    </span>
  )
}

/** What changed inside an effect's run, as the ◆ says it — the first setting that moved. */
export function settingsChangeLabel(before: Record<string, unknown>, after: Record<string, unknown>): string | null {
  for (const key of Object.keys(after)) {
    const a = JSON.stringify(before[key])
    const b = JSON.stringify(after[key])
    if (a === b) continue
    const value = after[key]
    if (key === 'beatDivision' && typeof value === 'number') return `${value} beat${value === 1 ? '' : 's'}`
    if (key === 'size' && typeof value === 'number') return `${Math.round(value * 100)}%`
    if (key === 'parameters') return 'params'
    if (key.endsWith('Uuid')) return 'master'
    return typeof value === 'string' || typeof value === 'number' ? `${key} ${value}` : key
  }
  return null
}

function runSegmentClass(kind: TimelineKind, mark: Pick<TimelineRunMark, 'start' | 'end'>, extra?: string): string {
  return cn(
    'absolute inset-x-2 z-[1] flex items-center gap-1 overflow-hidden whitespace-nowrap pl-2 pr-1.5 text-[10px]',
    'before:absolute before:inset-y-0 before:left-0 before:w-[3px]',
    RUN_CLASS[kind],
    mark.start ? 'top-1 rounded-t-md' : '-top-px',
    mark.end ? 'bottom-1 rounded-b-md' : '-bottom-px',
    extra,
  )
}

/**
 * One cell, drawn from its run mark. A click selects it — the kit's cells select through
 * `onBeginEdit` — or, on the one selected cell, toggles it ([onClick] decides). A run's last cell
 * carries the drag handle ([onHandle]), and a cell a drag would change draws its ghost.
 */
function TimelineCellView({
  column,
  cell,
  mark,
  previous,
  swatch,
  ghost,
  onClick,
  onHandle,
}: {
  column: TimelineColumn
  cell: TimelineCell
  mark: TimelineRunMark
  previous: TimelineCell | null
  swatch: string | null
  ghost: 'fill' | 'empty' | null
  onClick: () => void
  onHandle?: (e: React.PointerEvent) => void
}) {
  const handle = onHandle != null && mark.end && cell.filled?.kind === 'layer'
  return (
    // The whole cell, over the kit's 2px padding, so a run's segments meet across the row line.
    <div className="group/cell absolute inset-0" onClick={onClick}>
      <TimelineCellFace column={column} cell={cell} mark={mark} previous={previous} swatch={swatch} />
      {ghost === 'fill' && (
        <span aria-hidden data-timeline-ghost="fill" className={cn('absolute inset-x-2 inset-y-1 z-[2] rounded-md border border-dashed', RUN_CLASS[column.kind], 'opacity-60')} />
      )}
      {ghost === 'empty' && (
        <span aria-hidden data-timeline-ghost="empty" className="absolute inset-x-2 inset-y-1 z-[2] rounded-md border border-dashed border-destructive/70 bg-background/60" />
      )}
      {handle && (
        <button
          type="button"
          aria-label={`Drag ${column.name}'s run`}
          data-timeline-handle
          className="absolute bottom-0 left-1/2 z-[4] h-2 w-6 -translate-x-1/2 cursor-ns-resize rounded-full border border-background bg-foreground/60 opacity-0 transition-opacity group-hover/cell:opacity-100 focus-visible:opacity-100"
          onPointerDown={onHandle}
          onClick={(e) => e.stopPropagation()}
        />
      )}
    </div>
  )
}

function TimelineCellFace({
  column,
  cell,
  mark,
  previous,
  swatch,
}: {
  column: TimelineColumn
  cell: TimelineCell
  mark: TimelineRunMark
  previous: TimelineCell | null
  swatch: string | null
}) {
  const filled = cell.filled
  if (column.kind === 'scenery') {
    return (
      <span className="relative block h-full" data-timeline-cell={filled ? 'change' : mark.trackedLine ? 'tracked' : 'empty'}>
        {mark.trackedLine && <i aria-hidden className={TRACK_LINE_CLASS} />}
        {filled?.kind === 'scenery' && (
          <span className={cn('absolute inset-x-2 inset-y-1.5 z-[1] flex items-center overflow-hidden whitespace-nowrap rounded-md px-1.5 text-[10.5px] text-foreground', RUN_CLASS.scenery)}>
            {sceneryWords(filled.state, filled.transitionMs)}
          </span>
        )}
      </span>
    )
  }
  if (filled == null) return <span className="block h-full" data-timeline-cell="empty" />
  let body: ReactNode = null
  let off = false
  if (filled.kind === 'layer') {
    off = !filled.layer.enabled
    body = (
      <>
        {swatch != null && <PaletteSwatch colour={swatch} />}
        <i className="font-mono text-[10px] not-italic text-foreground/75" data-rank>
          {filled.rank}
        </i>
        {filled.glyphs.map((glyph, i) => (
          <i key={glyph} className={cn('text-[9.5px] not-italic text-muted-foreground', i === 0 && 'ml-auto', glyph === 'S' && 'font-bold text-amber-400')}>
            {glyph}
          </i>
        ))}
      </>
    )
  } else if (filled.kind === 'effect') {
    const before = previous?.filled?.kind === 'effect' ? previous.filled.effect.settings : null
    const change = mark.changed && before ? settingsChangeLabel(before, filled.effect.settings) : null
    body = (
      <>
        {mark.restart && <i className="not-italic text-violet-300" data-restart>↺</i>}
        {mark.start && <i className="font-mono not-italic text-foreground/75">∿</i>}
        {mark.changed && <i className="ml-auto not-italic text-violet-300" data-changed>◆ {change}</i>}
      </>
    )
  }
  return (
    <span className="relative block h-full" data-timeline-cell="filled">
      <span className={runSegmentClass(column.kind, mark, off ? 'border border-teal-300/60 bg-transparent text-muted-foreground before:hidden' : undefined)}>
        {body}
      </span>
      {column.kind === 'effect' && mark.restart && (
        <i aria-hidden className="absolute inset-x-3.5 -top-px z-[3] border-t-2 border-dotted border-violet-300" />
      )}
      {column.kind === 'effect' && mark.changed && (
        <i aria-hidden className="absolute left-1/2 -top-[5px] z-[3] -ml-1 size-[9px] rotate-45 border border-background bg-violet-300" />
      )}
    </span>
  )
}

function PaletteSwatch({ colour }: { colour: string }) {
  return <i aria-hidden data-swatch className="size-[9px] shrink-0 rounded-[2px]" style={{ background: colour }} />
}

function TimelineMarkerCell({ column, entryId, marks }: { column: TimelineColumn; entryId: number; marks: TimelineRuns }) {
  const mark = markerMarkAt(marks, entryId, column.id)
  if (column.kind === 'scenery') return mark.trackedLine ? <i aria-hidden className={TRACK_LINE_CLASS} data-timeline-through /> : null
  if (!mark.through) return null
  return <span aria-hidden data-timeline-through className={runSegmentClass(column.kind, { start: false, end: false })} />
}

function sceneryWords(state: SceneryState, transitionMs: number | null): string {
  const words = shortSceneryWords(undefined, state) || 'no state'
  const clock = clockWords(transitionMs)
  return clock == null ? words : `${words} · ${clock}`
}

function cellTitle(column: TimelineColumn, cell: TimelineCell, cue: CueStackCueRow): string {
  const at = cue.cueNumber ? `Q${cue.cueNumber}` : cue.name
  const filled = cell.filled
  if (filled == null) {
    if (cell.tracked != null) return `${column.name} — ${shortSceneryWords(undefined, cell.tracked)}, tracked into ${at}`
    return `${column.name} · ${column.sub} — not in ${at}`
  }
  if (filled.kind === 'layer') {
    return `${column.name} · ${column.sub} in ${at} — rank ${filled.rank}${filled.glyphs.length ? ` · ${filled.glyphs.join(' ')}` : ''}`
  }
  if (filled.kind === 'effect') return `${column.name} · ${column.sub} in ${at}${filled.effect.runUuid ? '' : ' — in no run'}`
  return `${column.name} in ${at} — ${sceneryWords(filled.state, filled.transitionMs)}`
}

function TimelineLegend({ cues, markers, columns }: { cues: number; markers: number; columns: number }) {
  return (
    <SheetPage.Footer>
      <span>
        {cues} cue{cues === 1 ? '' : 's'}
        {markers > 0 ? ` · ${markers} marker${markers === 1 ? '' : 's'}` : ''} · {columns} column{columns === 1 ? '' : 's'}
      </span>
      {(['look', 'palette', 'effect'] as const).map((kind) => (
        <span key={kind} className="inline-flex items-center gap-1.5">
          <LegendSwatch className={cn('relative overflow-hidden before:absolute before:inset-y-0 before:left-0 before:w-0.5', RUN_CLASS[kind])} />
          {kind === 'effect' ? 'Effect run' : kind === 'look' ? 'Look' : 'Palette'}
        </span>
      ))}
      <span className="inline-flex items-center gap-1.5">
        <LegendSwatch className={RUN_CLASS.scenery} />
        Scenery change, <i aria-hidden className="inline-block h-2.5 w-0.5 bg-amber-300/60" /> tracked
      </span>
      <span>
        <span className="font-mono">2</span> rank, 1 at the bottom
      </span>
      <span>
        <span className="text-violet-300">◆</span> changed in place · <span className="text-violet-300">↺</span> restarts
      </span>
      <span>
        <b className="text-amber-400">S</b> stomp · 60% amount · C mask · ⏱ timed · M master
      </span>
    </SheetPage.Footer>
  )
}
