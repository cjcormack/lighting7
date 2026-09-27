import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { labelUnlessCompact } from '@/lib/utils'
import { FIXTURE_FILTER_HINT, FIXTURE_FILTER_PLACEHOLDER } from '@/lib/fixtureFilterCopy'
import { Lightbulb, Search } from 'lucide-react'
import { useVisibleFixtureListQuery } from '../../store/fixtures'
import { useGroupListQuery } from '../../store/groups'
import { usePersistentState } from '../../hooks/usePersistentState'
import { stepCellSelection, useCellSelection, type CellSelection } from '../sheet/useCellSelection'
import { useEscapeEditorSnapshot } from '../sheet/useEscapeEditorSnapshot'
import { useCellEditorRequests } from '../sheet/useCellEditorRequests'
import { useProgrammerScope } from '../programmer/ProgrammerScope'
import { useLookRowStore } from '../programmer/LookRowStore'
import { useFocusedTemplateLayer } from '../programmer/FocusedTemplateLayer'
import { useRailTabClaim } from '../programmer/railTab'
import { usePublishMarquee, type MarqueeSnapshot } from './marqueeContext'
import {
  cellActionCopy,
  cellKeyboardPermission,
  marqueeOwnsKeyTarget,
  orderedSelectedCells,
} from './cellEntry'
import { isForeignControl, keyTargetIsGuarded } from '../sheet/cellEntry'
import { editorIsOpen } from '../editor/EditorSurface'
import { resolutionPropertyNames } from './columns'
import { cellEffectKey } from './cellEffects'
import { useClearCellEffects } from './useClearCellEffects'
import { arrowOfKey, type CellArrow, type CellRef } from '../sheet/cellSelectionModel'

/** This list's cell, over its closed column vocabulary. */
type FixtureCellRef = CellRef<ColumnKey>
import { arrowStepTarget, type ListSelectIntent } from '../sheet/listSelectionModel'
import type { AttributeFamily } from '../../lib/attributeFamily'
import {
  ColumnsMenu,
  useColumnVisibility,
  visibleColumnsFrom,
  type ColumnVisibility,
} from './ColumnsMenu'
import {
  buildRows,
  countFixtureRows,
  expandSelectionToTargets,
  fixtureRowId,
  groupRowId,
  memberRowId,
  parseSelectParam,
  batchForTargets,
  mergePositionCommits,
  planBatchWrites,
  resolveTargetCells,
  selectedRowTargets,
  rowWriteTargets,
  targetFamilies,
  targetEmitters,
  templateTargetsFor,
  treeKeyAction,
} from './rowModel'
import { buildRowCells } from './useRowValues'
import { useIncludeSelectionRequest } from '../../store/includeSelection'
import {
  listSelectionIntentFor,
  useListSelection,
  usePublishSelectionTargets,
} from './useListSelection'
import type { SelectionScope } from '../../store/selectionSlice'
import { applyPlannedWrite, useCellWriters, type CellWriters } from './useCellWriters'
import { useLitFixtureKeys } from './useLitFixtureKeys'
import { FixturesTable } from './FixturesTable'
import { SelectionToolbar } from './SelectionToolbar'
import { SelectionBar as ListSelectionBar } from '../programmer/SelectionBar'
import { SheetPage } from '../sheet/SheetPage'
import { PHONE_FOLDED_CLASS } from '../sheet/toolbarFolds'
import { CellSelectionActions } from '../sheet/CellSelectionActions'
import { SpreadPopover, spreadColumnsForTargets, type SpreadColumn } from './SpreadPopover'
import type { SpreadSeed } from '../editor/SpreadPanel'
import type { CellOpenRequest } from '../sheet/useCellEditorRequests'
import { FixtureDetailModal } from '../groups/FixtureDetailModal'
import { GroupDetailModal } from '../fixtures/GroupDetailModal'
import type { ColumnKey } from './columns'
import { useDeskSelectionBridge } from './useDeskSelectionBridge'
import { useDeskFollow } from '@/lib/deskFollow'
import type {
  CellBatch,
  CellCommit,
  FixtureRow,
  GroupRow,
  InfoRow,
  Row,
  RowId,
  WriteTarget,
} from './rowModel'
import type { LocateTarget } from '../../store/locate'
import type { Fixture } from '../../store/fixtures'
import type { GroupSummary } from '../../api/groupsApi'

const EMPTY_FIXTURES: Fixture[] = []
const EMPTY_GROUPS: GroupSummary[] = []
const EMPTY_TARGETS: readonly WriteTarget[] = []

/** One pending throttled commit: the key it is deduped under, and how to land it when it is due. */
interface PendingCommit {
  key: string
  commit: CellCommit
  apply: (commit: CellCommit) => void
}

export interface FixturesListContainerProps {
  /** Group rows + members + "Ungrouped" (true), or a flat fixture list (false). */
  grouped: boolean
  /**
   * Which list's selection this is, in `store/selectionSlice`.
   *
   * Required rather than defaulted: the three lists must not share a selection (their row ids
   * collide without meaning the same rows), and a wrong default would be invisible until two of
   * them were mounted at once.
   */
  selectionScope: SelectionScope
  /**
   * Colour cells by owning layer and show blind-staged values — the programmer sheet.
   *
   * **Not the cell-selection gate any more.** It was, which made one flag mean two things: every
   * list this container mounts now drag-selects cells, types into them and clears them, and this
   * says only whether provenance is drawn. The one behaviour still keyed off it is the *row* Spread
   * below, which the programmer trades away for the marquee's.
   */
  showOwnership?: boolean
  /**
   * Consume the `?select=` deep-link param. Off for the programmer sheet: those links are
   * minted by Cmd+K for the fixtures/groups pair, and the forwarding branch here would bounce
   * a group select out of the programmer and onto /groups/list.
   */
  enableDeepLinkSelect?: boolean
  /**
   * React to Include by selecting the fixtures it pulled in ("Select Heads on Include").
   * Opt-in so the plain Fixtures and Groups lists don't have their selection yanked by a
   * programmer action happening elsewhere.
   */
  respondToIncludeSelection?: boolean
  /** Take ownership of the column menu's state — see `useColumnVisibility`. Pass both or neither. */
  columnVisibility?: ColumnVisibility
  onColumnVisibilityChange?: (next: ColumnVisibility) => void
  /**
   * The filter field's placeholder. Defaults to the long form; the programmer passes
   * `FIXTURE_FILTER_PLACEHOLDER_SHORT` because its row B keeps the field down to 360px of row and
   * a placeholder cannot switch by container query (`lib/fixtureFilterCopy.ts`). The `title` and
   * `aria-label` carry the whole hint whichever is shown.
   */
  filterPlaceholder?: string
  /**
   * Replace the built-in chrome — one 40px toolbar row (filter · spacer · Lit · Columns) and the
   * selection bar under it — receiving the controls this container owns as ready-made nodes so a
   * caller can re-arrange them without re-implementing their state.
   *
   * Exists because the programmer view scatters them: the scope band and Groups join the filter on
   * row B, and the template strip rides the selection bar. Absent — the two plain list routes —
   * draws the shell's own two rows (CLAUDE.md §List shell), which are the same rows on the same
   * classes, so a plain list and the programmer cannot differ by a pixel above the grid.
   */
  renderToolbar?: (parts: {
    filter: React.ReactNode
    lit: React.ReactNode
    columns: React.ReactNode
    /**
     * Null when nothing *visible* is selected; the caller should render nothing rather than a
     * shell. Rows or cells — the two are one selection, and this toolbar serves both, with the
     * cell verbs (Set · Clear · Spread) drawn first when it is cells.
     */
    selection: React.ReactNode | null
    /** The marquee's cells, for a scope label beside the fixture count. Empty when none. */
    cells: readonly FixtureCellRef[]
    /**
     * True when Enter (or a digit) — or the toolbar's Set — opens the marquee's editor: cells are
     * selected and the scope can take a value (`cellKeyboardPermission`). The editor itself is the
     * first selected cell's own; the caller only draws the hint, from this flag, so the hint and
     * the key agree by construction.
     */
    cellEntryKey: boolean
    /** True when Backspace / Delete — or the toolbar's Clear — would take the selected cells out of Local. */
    cellClearKey: boolean
    /**
     * Where a template press lands: the cells' fixtures when there is a marquee, otherwise the
     * selected rows'. Already `{type: 'fixture', key}`, so the strip sends it as it is.
     */
    templateTargets: readonly LocateTarget[]
    /** The families those targets can take at all — the capability half of the strip's filter. */
    targetFamilies: readonly AttributeFamily[]
    /**
     * The bundled colour emitters those targets have — `white` / `amber` / `uv`.
     *
     * The rest of that filter. A template naming an emitter refuses on a head without it *whole*,
     * and the family cannot say which: every emitter is COLOUR.
     */
    targetEmitters: readonly string[]
    /**
     * A marquee drag is in flight.
     *
     * For a toolbar whose own arrival or departure would move the grid under the pointer that is
     * drawing the marquee — the programmer's selection bar, which holds its place for the duration
     * (`selectionBandState`). Two changes per gesture, not one per pointer move.
     */
    marqueeDragging: boolean
  }) => React.ReactNode
  /**
   * Replace nothing — *add* a footer strip under the table, receiving the two counts only this
   * component can compute.
   *
   * Exists for the programmer's ownership legend, which the space plan turns into a 22px footer
   * reading `24 fixtures · 4 selected` beside its swatches. The legend used to be a sibling of
   * this container, which is why it could not say either number; both come from `rows` and the
   * selection, and lifting them out would mean a second row build or a second selection read.
   *
   * `fixtureCount` counts **fixture rows after filtering** — not groups, elements or dividers, and
   * not the whole patch: the number answers "how much is in front of me", which is what a filtered
   * list changes. `groupCount` is the group rows the same way, for the grouped list's footer.
   * `selectedCount` is the *visible* selection, the same set the selection toolbar gates on, so a
   * filter that hides every selected row reads 0 rather than lying.
   *
   * Every mount passes one since the list shell: the footer is a row of the shell, and a list with
   * no count was one of the four inconsistencies the shell exists to close.
   */
  renderFooter?: (parts: {
    fixtureCount: number
    groupCount: number
    selectedCount: number
  }) => React.ReactNode
  /**
   * Draw `Lit` and `Columns` as icons alone, whatever the viewport says.
   *
   * The two carry `sm:inline` words, which asks the viewport whether there is room — and a caller
   * can know better. The programmer's short-height arm does (space plan D8): rows A and B are one
   * 36px line under `max-height: 500px`, so on an 852×393 landscape phone the viewport is wide
   * and the *row* is not, and those two words plus the scope pills' were 130px of the room the
   * source box needs to name the cue you are about to overwrite. Both keep their `title`.
   */
  compactControls?: boolean
}

/** A divider row's batch: nothing lands on it. */
const EMPTY_BATCH: CellBatch = { count: 0, skipped: 0, resolutions: [] }

/**
 * The spreadsheet view shared by Fixtures → List (flat, `grouped: false`),
 * Groups → List (group rows + members + Ungrouped, `grouped: true`), and the
 * programmer sheet (`showOwnership`, either grouping).
 *
 * **All three select cells and edit them the same way.** Drag a rectangle over the value columns,
 * click to narrow it to one cell, double click / ⏎ / a typed character to open that column's
 * editor over the whole marquee, ⌫ to take those cells out of Local. Until this session the
 * marquee, the keyboard and the Set · Clear · Spread verbs were the programmer's alone and the two
 * plain lists had click-to-open and a row selection; the gestures were the same component's either
 * way, so the split was one prop rather than a design, and it made the same grid answer a click
 * two ways depending on the route it was mounted on.
 */
export function FixturesListContainer({
  grouped,
  selectionScope,
  showOwnership = false,
  enableDeepLinkSelect = true,
  respondToIncludeSelection = false,
  columnVisibility: controlledColumnVisibility,
  onColumnVisibilityChange,
  filterPlaceholder = FIXTURE_FILTER_PLACEHOLDER,
  renderToolbar,
  renderFooter,
  compactControls = false,
}: FixturesListContainerProps) {
  const { data: maybeFixtures, isLoading: fixturesLoading } = useVisibleFixtureListQuery()
  const { data: maybeGroups, isLoading: groupsLoading } = useGroupListQuery()
  const [searchParams, setSearchParams] = useSearchParams()
  const { projectId } = useParams()
  const navigate = useNavigate()

  // Module-level constants keep the fallback identity stable across renders.
  const fixtures = maybeFixtures ?? EMPTY_FIXTURES
  const groups = maybeGroups ?? EMPTY_GROUPS

  const [filter, setFilter] = useState('')
  const [onlyLit, setOnlyLit] = usePersistentState('fixturesList.onlyLit', false)
  // Controlled when the caller owns the menu (the programmer renders it in a band above this
  // component); otherwise this owns both, and the two paths share one persisted key either way.
  const ownColumns = useColumnVisibility()
  const columnVisibility = controlledColumnVisibility ?? ownColumns[0]
  const setColumnVisibility = onColumnVisibilityChange ?? ownColumns[1]
  const [expandedGroups, setExpandedGroups] = useState<ReadonlySet<string>>(new Set<string>())
  const [expandedFixtures, setExpandedFixtures] = useState<ReadonlySet<string>>(new Set<string>())
  const [scrollToRowId, setScrollToRowId] = useState<RowId | null>(null)
  const [infoFixtureKey, setInfoFixtureKey] = useState<string | null>(null)
  const [infoGroupName, setInfoGroupName] = useState<string | null>(null)

  const visibleColumns = useMemo(
    () => visibleColumnsFrom(columnVisibility),
    [columnVisibility],
  )

  const litFixtureKeys = useLitFixtureKeys(onlyLit ? fixtures : EMPTY_FIXTURES)

  const rows = useMemo(
    () =>
      buildRows({
        fixtures,
        groups,
        expandedGroups,
        expandedFixtures,
        textFilter: filter,
        litFixtureKeys: onlyLit ? litFixtureKeys : undefined,
        groupByGroups: grouped,
      }),
    [fixtures, groups, expandedGroups, expandedFixtures, filter, onlyLit, litFixtureKeys, grouped],
  )

  // Dividers aren't selectable — they're excluded from the selection order so
  // shift-ranges and select-all never touch them.
  const selectableOrder = useMemo(
    () => rows.filter((row) => row.kind !== 'divider').map((row) => row.id),
    [rows],
  )
  const selection = useListSelection(selectableOrder, selectionScope)
  const visibleRowIds = useMemo(() => new Set(rows.map((r) => r.id)), [rows])
  const cellSelection = useCellSelection<ColumnKey>(visibleRowIds)
  const { count: cellCount, clear: clearCells, isSelected: isCellSelected } = cellSelection

  // ── One selection, two shapes ─────────────────────────────────────────────────────────────
  //
  // Rows and cells used to be two independent states — cells a transient edit scope drawn over a
  // row selection that kept its checkboxes and kept driving Record — and an operator had to hold
  // both in their head to know what the next gesture would reach. They are one now: selecting
  // cells clears the rows, selecting rows clears the cells, and every consumer below reads
  // `selectedRowIds`, which is the cells' rows when there is a marquee and the row selection
  // otherwise. So a marquee *is* the fixture selection, narrowed to some of their attributes —
  // Record, Locate, the desk's select LEDs and the template strip all see the same heads.
  //
  // Enforced at the two doors rather than by an effect reconciling after the fact, because an
  // effect would have to guess which of two non-empty states was the newer one. The cell door
  // clears the rows only when a hit arrives and there are rows to clear, so an idle marquee frame
  // costs no dispatch; the row doors clear the cells, whose `clear` bails when there are none.
  const rowCountRef = useRef(selection.count)
  rowCountRef.current = selection.count
  const { select: selectRowRaw, selectAll: selectAllRaw, setSelection: setRowsRaw, clear: clearRows } = selection
  const selectCells = useCallback(
    (hits: readonly FixtureCellRef[], intent: ListSelectIntent) => {
      if (hits.length > 0 && rowCountRef.current > 0) clearRows()
      cellSelection.select(hits, intent)
    },
    [cellSelection, clearRows],
  )
  const tableCellSelection = useMemo<CellSelection<ColumnKey>>(
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
  const selectAllRows = useCallback(() => {
    clearCells()
    selectAllRaw()
  }, [clearCells, selectAllRaw])
  // Stable, which `useDeskSelectionBridge` depends on: it is a dep of both of that hook's effects.
  const setRows = useCallback(
    (ids: readonly RowId[]) => {
      clearCells()
      setRowsRaw(ids)
    },
    [clearCells, setRowsRaw],
  )
  /**
   * The fourth door, and the only one no gesture opens: **drop the marquee, keeping its heads.**
   *
   * Every other `clearCells()` here is paired with a row write in the same breath, so the selection
   * is never left empty by one. This one exists because something can change *under* a standing
   * marquee — today only the programmer's scope — and clearing the cells alone would empty
   * `selectedRowIds`, since the rows were cleared when the cells were selected. That published
   * `set([])` to the desk and took every other screen's target band with it. Named rather than
   * inlined at its one call site so that the next automatic clear has something to reach for: a
   * bare `clearCells()` from an effect is the shape of that bug, and nothing else marks it.
   *
   * Takes the marquee's *visible* rows, which is what `cellRowIds` holds, so a marquee whose rows
   * have all been filtered out has none and this is a plain clear. That case reaches the desk as an
   * empty selection — but it does so through the **filter**, with or without a scope switch, and it
   * does so on the commit before this one, so it is a separate bug from the one this door fixes and
   * not one the door can close.
   */
  const dropMarqueeToRows = useCallback(
    (marqueeRows: ReadonlySet<RowId>) => {
      if (marqueeRows.size > 0) setRows([...marqueeRows])
      else clearCells()
    },
    [setRows, clearCells],
  )
  /**
   * The rows the marquee's cells sit on, identity-stable while the *set* is unchanged. A drag
   * mints a fresh `cells` array per pointer move, and everything derived from these ids — the
   * desk publish, the locate targets, the Record scope — would otherwise recompute and re-send on
   * every move rather than only when the rectangle crosses a row boundary.
   *
   * Held in state and re-derived during render (React's own "derive state from props" idiom,
   * which re-renders at once) rather than through a ref written inside `useMemo`: a memo body can
   * run for a render that is never committed, and a ref bumped there would hand the next committed
   * render a fresh identity for an unchanged set — one redundant desk publish.
   */
  const nextCellRowIds = useMemo(
    () => new Set(cellSelection.cells.map((cell) => cell.rowId)),
    [cellSelection.cells],
  )
  const [cellRowIds, setCellRowIds] = useState<ReadonlySet<RowId>>(nextCellRowIds)
  if (cellRowIds !== nextCellRowIds && !sameSet(cellRowIds, nextCellRowIds)) {
    setCellRowIds(nextCellRowIds)
  }
  const selectedRowIds = cellRowIds.size > 0 ? cellRowIds : selection.selectedIds

  // Null outside the programmer, so the plain fixtures and groups lists are unaffected.
  const scope = useProgrammerScope()
  // The editors' label line says where a value lands: *Local*, or the focused Look's name, which
  // rides on the row store (it is the layer frame's `lookName`, so no second query). A null
  // scope — the plain lists — writes to Local too: `useCellWriters` has no other live arm.
  const lookStore = useLookRowStore()
  const scopeLabel = scope?.kind === 'layer' ? (lookStore?.lookName ?? 'Layer') : 'Local'
  /**
   * The marquee's rows, read by the scope effect below without being one of its deps. A drag mints
   * a new rectangle many times a second and that must not re-run an effect keyed on the scope.
   */
  const cellRowIdsRef = useRef(cellRowIds)
  cellRowIdsRef.current = cellRowIds
  // ── A scope switch drops the marquee to its rows ──────────────────────────────────────────────
  //
  // A marquee is a scope-local edit target: "these eight cells" means eight of *your* values in
  // Local and eight of a Look's rows in a layer, so carrying one across a switch would aim the
  // next edit at cells the operator picked while looking at something else. The *heads* are not
  // scope-local, though, and since the two selections became one they are the same state: clearing
  // the cells outright emptied `selectedRowIds` — the rows had been cleared when the cells were
  // selected — so the bridge published `set([])` and every other screen's band went dark and its
  // family pill with it. **Never publish an empty selection the operator did not make.** So the
  // switch converts rather than clears, through `dropMarqueeToRows` — which goes through the row
  // door, so the one-selection rule still has one owner: the heads survive everywhere and only the
  // mask is dropped, which is the same reading the bridge gives a mask another window made.
  // Switching *back* re-mints nothing — there are no cells left to convert.
  //
  // `scope` is identity-stable while unchanged (`ProgrammerScopeProvider` dedupes through
  // `scopesEqual`), so this fires on a genuine switch and on mount, where there is no marquee and
  // the door's clear arm bails without a dispatch.
  //
  // **This effect must stay declared above `useDeskSelectionBridge`.** React runs a component's
  // effects in hook order, and that is what makes a scope switch and a `selection.state` frame
  // arriving in the same commit resolve with the *desk* winning: this writes first, the bridge's
  // desk→list effect then overwrites with what the desk said. Move it below the bridge and a local
  // scope switch would silently win a race against a genuine desk-originated change. Nothing in
  // the type system says so, so it is said here.
  useEffect(() => {
    dropMarqueeToRows(cellRowIdsRef.current)
  }, [scope, dropMarqueeToRows])

  // Whichever shape the selection is in. The two are exclusive now, so this is no longer a ladder
  // with two rungs — but it stays one function for Escape, for a click on the grid's empty
  // background (`PD-CLEAR-SELECTION-TOUCH`) and for the toolbar's Deselect, so the three cannot
  // drop different things.
  //
  // **A blank cell reaches it too, and on the two plain lists that is new.** A column a row
  // resolves nothing for — a dimmer-only par's Colour — is grid background wearing a cell's
  // position, so clicking it clears, which with a rows-only selection means dropping the rows.
  // That was refused on those two routes while `onEmptyCellClick` was withheld there, on the
  // reasoning that their ladder had only a row rung and so the click could only ever be
  // destructive. The rung is no longer the only one, and the programmer has answered a blank cell
  // this way throughout — so the refusal expired with the reason for it rather than being
  // overruled. Stated here because it is the one part of the unification that *removes* something
  // rather than adding it, and pinned in `FixturesListContainer.test.tsx`.
  const clearByLadder = useCallback(() => {
    if (cellCount > 0) clearCells()
    else clearRows()
  }, [cellCount, clearCells, clearRows])

  // Selected ids whose rows are hidden (collapsed group, active filter) are
  // inert everywhere below — every consumer intersects with `rows` — so no
  // aggressive reconcile is needed when visibility changes.
  const selectedTargets = useMemo(
    () => expandSelectionToTargets(rows, selectedRowIds),
    [rows, selectedRowIds],
  )

  // Publish the expansion for consumers outside this container — RecordSheet's "selected
  // fixtures only". They can't derive it themselves: it needs `rows`, which needs this
  // component's filter, group-expansion and rollup state.
  const selectedTargetKeys = useMemo(
    () => selectedTargets.map((target) => target.key),
    [selectedTargets],
  )
  usePublishSelectionTargets(selectionScope, selectedTargetKeys)

  // The dedupe and the element-row drop live in `selectedRowTargets`, which the desk-selection
  // bridge below is the third caller of: locate and the desk must never disagree about what a
  // selected group row *is*.
  // Memoized on `rows` alongside every other derivation of it: this component re-renders on each
  // pointermove of a marquee drag, and an inline reduce in the return would rescan the whole row
  // list per frame to feed one footer string.
  const fixtureCount = useMemo(() => countFixtureRows(rows), [rows])
  // The grouped list's footer says `1 group · 7 fixtures`; the flat list has no group rows and says
  // the fixtures alone.
  const groupCount = useMemo(() => rows.filter((row) => row.kind === 'group').length, [rows])

  const locateTargets = useMemo<LocateTarget[]>(
    () => selectedRowTargets(rows, selectedRowIds),
    [rows, selectedRowIds],
  )

  // Where a template press lands, and what those heads can take — the one selection's rows, which
  // under a marquee are the cells' rows. Published through `renderToolbar` rather than read from
  // Redux by the strip: `selectTargetKeys` is already flattened to member keys.
  const templateTargets = useMemo(
    () => templateTargetsFor(rows, selectedRowIds),
    [rows, selectedRowIds],
  )
  // From the **same list** the press is sent to, resolved back to whole fixtures, so the two
  // cannot disagree: a lone element row lands on its fixture above, and its families are the
  // fixture's — parent properties and every element's — not the one element's. Deriving them from
  // `selectedTargets` instead (which holds the element) under-reported a bar whose dimmer sits on
  // the parent, and offered no intensity template for a press that would have set it.
  const fixtureByKey = useMemo(() => new Map(fixtures.map((f) => [f.key, f])), [fixtures])
  const templateWriteTargets = useMemo(
    () =>
      templateTargets.flatMap((target) => {
        const fixture = fixtureByKey.get(target.key)
        return fixture ? [fixture] : []
      }),
    [templateTargets, fixtureByKey],
  )
  const templateFamilies = useMemo(() => targetFamilies(templateWriteTargets), [templateWriteTargets])
  // The family alone cannot filter a template that names an emitter: white, amber, UV and the hex
  // are all COLOUR. Derived from the same list for the same reason the families are.
  const templateEmitters = useMemo(() => targetEmitters(templateWriteTargets), [templateWriteTargets])

  // One desk, one selection (plan D2) — the programmer scope only, and only while this tab
  // follows the desk (multi-screen plan D8); see the hook. It sees the unified ids, so a marquee
  // lights the strip's select LEDs for its rows, and a select button pressed on the desk lands as
  // a row selection (which drops the marquee, as any row door does). The cells go with the ids:
  // a marquee is targets × families, and the families are the desk's attribute mask (D2, D3).
  const followingDesk = useDeskFollow()
  useDeskSelectionBridge(
    selectionScope === 'programmer' && followingDesk,
    rows,
    selectedRowIds,
    cellSelection.cells,
    setRows,
  )

  const writers = useCellWriters()

  const handleRowClick = useCallback(
    (id: RowId, e: React.MouseEvent) => {
      selectRow(id, listSelectionIntentFor(e))
    },
    [selectRow],
  )

  // Element rows open the PARENT fixture's sheet — FixtureDetailModal resolves
  // by fixture-list key, and element keys aren't in that list.
  const handleShowInfo = useCallback((row: InfoRow) => {
    if (row.kind === 'group') setInfoGroupName(row.name)
    else setInfoFixtureKey(row.fixture.key)
  }, [])

  const handleToggleExpand = useCallback((row: GroupRow | FixtureRow) => {
    const toggled = (prev: ReadonlySet<string>, key: string): ReadonlySet<string> => {
      const next = new Set(prev)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    }
    if (row.kind === 'group') setExpandedGroups((prev) => toggled(prev, row.name))
    else setExpandedFixtures((prev) => toggled(prev, row.fixture.key))
  }, [])

  // **A click on a cell selects that one cell, and does nothing else.** Not its row (the
  // spreadsheet feel from when rows had checkboxes, which put a row wash under a click that was
  // about one attribute), and — since this session — not the editor either: on a grid that can
  // select cells the trigger no longer opens, so the whole of a click is this. Set, Enter and a
  // typed character are how an editor is opened, over whatever the selection has arrived at.
  //
  // That is also why there is no longer an exception for a cell already in the marquee. It used to
  // short-circuit, on the reasoning that clicking one of your own selected cells was the *whole*
  // marquee's editor and collapsing to one cell would silently discard the rest — true while the
  // click was the way in. With the editor elsewhere, a click inside the marquee is an operator
  // narrowing it to one cell, which is the one thing a rectangle cannot express.
  //
  // **Every list this container mounts answers a click the same way**, including the two plain
  // ones. They used to select the clicked *row* instead — the spreadsheet feel from when a click
  // was also the way into the editor, and the only honest answer while they had no cell selection
  // for the toolbar, `commitNow` and `batchCountFor` to read. They have one now, so the row rule
  // and its exception went with the gate: one gesture vocabulary across the three lists, which is
  // the whole point of their sharing this component.
  const handleBeginCellEdit = useCallback(
    (row: Row, col: ColumnKey) => {
      if (row.kind === 'divider') return
      selectCells([{ rowId: row.id, col }], 'replace')
    },
    [selectCells],
  )

  /**
   * The marquee by column, each with the heads its cells stand for in visible row order. The one
   * expansion behind every per-column consumer — the commit, Backspace, the batch count and Spread
   * — so a spread and a typed value cannot reach different heads for one selection.
   */
  //
  // **Held by content, not by identity.** `rows` is rebuilt on every filter keystroke and every
  // group or fixture expand, and `cellSelection` on every marquee frame, so the memo below mints a
  // fresh list far more often than the heads change — and everything keyed on it (the per-column
  // batches, the snapshot the rail's tabs subscribe to) would follow. `useContentStable` keeps the
  // held list while the columns and their heads are the same.
  const nextColumnTargets = useMemo<SpreadColumn[]>(
    () =>
      cellSelection.byColumn().map(({ col, rowIds }) => ({
        col,
        targets: expandSelectionToTargets(rows, new Set(rowIds)),
      })),
    [cellSelection, rows],
  )
  const columnTargets = useContentStable(nextColumnTargets, sameColumns)

  /**
   * One commit, every selected cell. Grouped BY COLUMN so each column is exactly one
   * `planBatchWrites` call, keeping `resolveTargetCells`' parent-first precedence and per-target
   * clamping intact. A commit whose shape doesn't fit a column (a colour dragged across Colour and
   * Position, or `127` typed at a marquee that includes Colour) is filtered out inside
   * `planBatchWrites` — so the chip's cell count is an upper bound on what any ONE commit writes,
   * which the design's wording already allows for.
   *
   * One caller: an editor opened on a cell inside the marquee — by a click, by Enter, or by the
   * bar's Set, which are one request. It is the same write however it was opened, and that is the
   * point: the keyboard and the bar add ways into the marquee, not a second path to the rig.
   */
  const commitToCells = useCallback(
    (commit: CellCommit): number => {
      let written = 0
      for (const { col: c, targets } of columnTargets) written += writeBatch(writers, targets, c, commit)
      // How many writes were planned. A popover's caller has no use for it; the typed field does,
      // because a value that fitted no selected column looks exactly like one that landed.
      return written
    },
    [columnTargets, writers],
  )

  const commitNow = useCallback(
    (row: Row, col: ColumnKey, commit: CellCommit) => {
      // Three scopes, most specific first. The marquee wins over the row selection because it is
      // the narrower, more deliberate statement of what this edit is for.
      if (cellSelection.isSelected(row.id, col)) {
        commitToCells(commit)
        return
      }
      const targets =
        row.kind !== 'divider' && selection.isSelected(row.id)
          ? selectedTargets
          : rowWriteTargets(row)
      // planBatchWrites clamps the commit to each target's own ranges.
      writeBatch(writers, targets, col, commit)
    },
    [cellSelection, commitToCells, selectedTargets, selection, writers],
  )

  // ── The keyboard half of the marquee ──────────────────────────────────────────────────────
  //
  // Select cells, press Enter (or just start typing): the cell editor for the first selected cell
  // opens, focused on its first field, and what it commits lands on every selected cell through
  // `commitToCells`. There is no second editor any more — see `cellEntry.ts` for what was deleted
  // and why one column's editor is the right answer for a selection that spans several. The window
  // handler below names the cell and seeds it; it does nothing else.
  //
  // **The scope gate is `cellKeyboardPermission`**, and it is the fourth place "read-only" has to
  // be said (see CLAUDE.md §The programmer's scoped grid): the marquee arms in Output and on a
  // focused template layer too, and `useCellWriters` would take a commit from either as a live
  // write. Both keys read the same answer, and no editor is opened where Enter would be refused,
  // so the hint beside it cannot promise a key that does nothing.
  const focusedTemplate = useFocusedTemplateLayer()
  const keys = cellKeyboardPermission(scope, focusedTemplate != null)
  // Whether the table's marquee is mid-gesture. Kept here rather than in the table because the
  // only consumer is a *toolbar* — a sibling above the rows, which `renderToolbar` builds — and
  // the table sets it twice a drag, so nothing renders at pointer rate for it.
  const [marqueeDragging, setMarqueeDragging] = useState(false)
  /**
   * The cell whose editor the keyboard has asked for — a one-shot handed to `FixturesTable`, which
   * folds it into the same signal a released marquee uses. `seed` is the character that opened it,
   * `''` for a bare Enter.
   *
   * Dropped on the commit after it is set, for `autoOpenCell`'s reason and one of its own: the
   * request names a cell by `(rowId, col)`, and a request left standing would re-open that editor
   * the next time the row it names is re-rendered into the virtualiser's window.
   */
  /**
   * The selection bar's **Set** button, which is where a requested editor opens.
   *
   * Every editor on this grid is opened by Set, by its key, or by a double click on the cell now —
   * a single click selects — and anchoring the panel at the cell put it wherever in the grid the
   * first selected cell happened to be, which for a marquee near the bottom of a long list is
   * nowhere near the hand that pressed Set. Only Set anchors here: a double click is made at the
   * cell, so its editor opens there, like Enter's.
   * Spread already opened at its own button; this is the two behaving alike. Threaded down to the
   * cells rather than resolved here, because the popover belongs to the cell that owns the editor.
   */
  const setButtonRef = useRef<HTMLButtonElement | null>(null)

  /**
   * The cell Set, Enter and a typed character all name: first in display order **that has an
   * editor**. Shared with the close request, so the press that shuts the panel cannot name a
   * different cell from the press that opened it.
   *
   * The "that has one" is not a nicety. A marquee is geometric, so a rectangle drawn across a rig
   * of mixed heads covers Colour cells on dimmer-only pars and Position cells on everything that
   * cannot move — and taking the display-first cell flatly would leave Enter doing nothing on a
   * perfectly ordinary selection. `buildRowCells` omits a column a row resolves nothing for, which
   * is the same answer the grid draws by, and it is memoised per row here because the search stops
   * at the first hit and usually never leaves the top row.
   *
   * Opening one column's editor for a selection that spans several is not a narrowing — see
   * `cellEntry.ts` and `commitToCells`.
   */
  const rowById = useMemo(() => new Map(rows.map((row) => [row.id, row])), [rows])
  const firstEditableSelectedCell = useCallback((): FixtureCellRef | undefined => {
    const ordered = orderedSelectedCells(
      cellSelection.cells,
      rows.map((row) => row.id),
      visibleColumns,
    )
    const editableCols = new Map<RowId, ReadonlySet<ColumnKey>>()
    return ordered.find((cell) => {
      let cols = editableCols.get(cell.rowId)
      if (!cols) {
        const row = rowById.get(cell.rowId)
        cols = new Set(
          row == null || row.kind === 'divider'
            ? []
            : buildRowCells(row, visibleColumns).map((rowCell) => rowCell.col),
        )
        editableCols.set(cell.rowId, cols)
      }
      return cols.has(cell.col)
    })
  }, [cellSelection.cells, rows, rowById, visibleColumns])

  // The two one-shots handed to `FixturesTable` — open this cell's editor, close it — and the
  // gestures behind them: Enter and a typed character (`openCellEditor`, beside the cell) and the
  // bar's Set (`toggleCellEditor`, at the button, or closing what it opened). The rule is the sheet
  // kit's, shared with the patch list, the DMX sheet and the cue sheet; see the hook for why a
  // request is a one-shot and why an off-screen cell is scrolled to rather than opened.
  // **A rail tab claims its own column's open gesture** (editor-kit plan session 4, call 9): with
  // the programmer rail's Colour tab open, Enter, a typed character and Set over a marquee whose
  // first editable cell is a Colour cell land in the tab — R focused, the character seeded — and
  // no popover opens, since two colour editors over one marquee is the double the kit refuses.
  // Every other column opens its popover as before. Null on the plain lists, which have no rail.
  // The double click is the Colour cell's own door, and it asks the same claim (`ColourCell`).
  const railClaim = useRailTabClaim()
  const interceptOpen = useCallback(
    (request: CellOpenRequest<ColumnKey>) => {
      if (railClaim?.tab !== 'colour' || request.col !== 'colour') return false
      railClaim.focusColour(request.seed)
      return true
    },
    [railClaim],
  )
  const { keyboardOpen, closeEditorCell, openCellEditor, toggleCellEditor, closeCellEditor } =
    useCellEditorRequests<ColumnKey>({
      firstEditableCell: firstEditableSelectedCell,
      onScrollTo: setScrollToRowId,
      intercept: interceptOpen,
    })

  // ── A scope switch closes an open cell editor ────────────────────────────────────────────────
  //
  // An editor is open *for* a selection, and `selectionEmpty` closes it when that selection goes
  // away. A scope switch is the case that rule cannot see: the selection still exists — the
  // marquee above just converted it to its rows — but the grid is pointed at something else now,
  // and in Output or on a focused template layer that something else is **read-only**.
  //
  // It used to be closed by accident. Under a marquee the row selection was empty, so the old
  // `clearCells()` took `selectionEmpty` across its false→true edge and `useEditorOpen` shut
  // the panel. Converting to rows keeps that flag false, so the edge never comes — and an open
  // popover is not inert in a read-only scope: `disabled` reaches the cell's *trigger*, never the
  // fields inside an already-open panel, and `useCellWriters` has no Output or template arm, so a
  // commit from one falls through to a live write and puts literals in Local. Same hole the Spread
  // and keyboard gates are written against, reached through a stale panel instead.
  //
  // **Not merely Radix's job.** A press on the scope band is an outside press, so at the desk the
  // panel is usually dismissed before the click that flips the scope is handled. But the provider
  // falls back to Output on a *broadcast* when another desk removes the focused layer
  // (`ProgrammerScopeProvider`), and there is no pointer event in that path at all. The precedent
  // is `EditorSurface`'s double-click guard: an environment dismissal is not a rule this code
  // states.
  //
  // Deliberately a **second** effect rather than folded into the one above, which must stay
  // declared above `useDeskSelectionBridge` — see its note. This one reads the editor requests, so
  // it can only be declared here; the two are independent, so their relative order does not matter.
  //
  // **The first run is skipped, because a mount is not a switch.** `openCellEditorTarget` queries
  // the whole document, and a grid that has only just mounted cannot have opened anything — so
  // anything it matched on the first run would be some other surface's open editor, and this would
  // close it. The conversion effect above needs no such guard: it has nothing to convert on mount.
  const scopeSwitchedRef = useRef(false)
  useEffect(() => {
    if (!scopeSwitchedRef.current) {
      scopeSwitchedRef.current = true
      return
    }
    closeCellEditor()
  }, [scope, closeCellEditor])

  /**
   * Backspace / Delete on a marquee: take the selected cells out of Local — the spreadsheet's
   * "clear contents", and the one gesture the popovers have no button for. A cleared entry is
   * what stops a value being recorded, so this is how a busked colour that should *not* go into
   * the cue is un-busked without touching the fixtures around it.
   *
   * Local only (`cellKeyboardPermission`): Output is a read, and a Look layer's row draft has no
   * removal — `LookRowStore` exposes `setValue` alone — so rather than a key that silently does
   * nothing there, the gesture is not offered, and `cellClearKey` tells the toolbar so the hint
   * names it only where it works.
   *
   * **It clears the cells' local effects too**, which is not a second gesture bolted on: the
   * programmer's own whole-desk Clear has always swept values and programmer-band FX together, and
   * an effect busked onto three cells was otherwise only removable one instance at a time in the
   * rail. `cellEffects.ts` owns which effects qualify and why a partly-covered one is left alone;
   * the same `fixtureKey|propertyName` pairs this loop clears are what it matches on, collected
   * here rather than re-derived so the two halves cannot reach different heads.
   */
  const canClearCells = keys.clear
  const canTypeCells = keys.entry
  const clearCellEffects = useClearCellEffects(canClearCells)
  const clearSelectedCells = useCallback(() => {
    if (!canClearCells) return
    const cleared = new Set<string>()
    for (const { col, targets } of columnTargets) {
      for (const outer of targets) {
        for (const { target, resolution } of resolveTargetCells(outer, col)) {
          for (const propertyName of resolutionPropertyNames(resolution)) {
            writers.clearValue(target.key, propertyName)
            cleared.add(cellEffectKey(target.key, propertyName))
          }
        }
      }
    }
    clearCellEffects(cleared)
  }, [canClearCells, clearCellEffects, columnTargets, writers])

  // Continuous drag commits (slider/colour/position editors fire per pointer
  // move) are throttled to ~30Hz with a trailing call, because each commit
  // fans out to one WebSocket frame per channel per selected fixture — a
  // select-all colour drag would otherwise emit thousands of frames a second.
  // A commit for a different cell flushes the pending one first so nothing is
  // ever dropped, and the timer flushes on unmount.
  //
  // Two callers share the one throttle: a cell's editor (keyed by its row and column) and the
  // programmer rail's tabs (keyed by the column alone — `commitColumn` below). Each pending commit
  // carries how to land it, reading the *current* writer through a ref at landing time, so a
  // commit held across a re-render still lands through this render's targets and scope.
  const commitNowRef = useRef(commitNow)
  commitNowRef.current = commitNow
  const pendingCommitRef = useRef<PendingCommit | null>(null)
  const commitTimerRef = useRef<number | null>(null)

  const flushPendingCommit = useCallback(function flushPendingCommit() {
    const pending = pendingCommitRef.current
    pendingCommitRef.current = null
    if (pending) {
      pending.apply(pending.commit)
      commitTimerRef.current = window.setTimeout(flushPendingCommit, 33)
    } else {
      commitTimerRef.current = null
    }
  }, [])

  useEffect(
    () => () => {
      if (commitTimerRef.current != null) {
        window.clearTimeout(commitTimerRef.current)
        commitTimerRef.current = null
      }
      const pending = pendingCommitRef.current
      pendingCommitRef.current = null
      if (pending) pending.apply(pending.commit)
    },
    [],
  )

  const scheduleCommit = useCallback(
    (key: string, next: CellCommit, apply: (commit: CellCommit) => void) => {
      let commit = next
      const pending = pendingCommitRef.current
      if (pending && pending.key !== key) {
        pendingCommitRef.current = null
        pending.apply(pending.commit)
      }
      if (commitTimerRef.current == null) {
        apply(commit)
        commitTimerRef.current = window.setTimeout(flushPendingCommit, 33)
      } else {
        // Position commits are per-axis; merge so a pan tick doesn't discard
        // a pending tilt tick (or vice versa) within the same window.
        const prev = pendingCommitRef.current
        if (prev && prev.commit.kind === 'position' && commit.kind === 'position') {
          commit = mergePositionCommits(prev.commit, commit)
        }
        pendingCommitRef.current = { key, commit, apply }
      }
    },
    [flushPendingCommit],
  )

  const handleCellCommit = useCallback(
    (row: Row, col: ColumnKey, commit: CellCommit) =>
      scheduleCommit(`cell\u0000${row.id}\u0000${col}`, commit, (c) => commitNowRef.current(row, col, c)),
    [scheduleCommit],
  )

  /**
   * A commit to one column of the selection, from outside the grid — the programmer rail's Colour
   * tab. The marquee's cells in that column when there are some, the selected rows' heads when
   * there are no cells, and nothing otherwise; planned the way a cell's commit is
   * (`planBatchWrites`, per-target clamping and shape filtering) through this container's writers,
   * which are the grid's scope-aware ones — Local, or the focused Look layer's draft.
   */
  const commitColumn = useCallback(
    (col: ColumnKey, commit: CellCommit) => {
      const column = columnTargets.find((c) => c.col === col)
      const targets = column?.targets ?? (cellCount === 0 ? selectedTargets : EMPTY_TARGETS)
      writeBatch(writers, targets, col, commit)
    },
    [columnTargets, cellCount, selectedTargets, writers],
  )
  const commitColumnRef = useRef(commitColumn)
  commitColumnRef.current = commitColumn
  // Stable for the mount, so the published snapshot's `commit` never moves: through the throttle,
  // keyed by column, landing through whatever `commitColumn` is current when it lands.
  const publishedCommit = useCallback(
    (col: ColumnKey, commit: CellCommit) =>
      scheduleCommit(`rail\u0000${col}`, commit, (c) => commitColumnRef.current(col, c)),
    [scheduleCommit],
  )

  // The plain routes' whole-selection spread, memoised so `SpreadPopover`'s per-column plans are
  // not re-probed on every render of the container.
  const rowSpreadColumns = useMemo(() => spreadColumnsForTargets(selectedTargets), [selectedTargets])

  // The colour editor's *Spread…*: a one-shot the way `keyboardOpen` is one — the seed opens the
  // row C panel on Colour with *From* set to the editor's RGB (`SpreadSeed`), and the panel asks
  // for it to be dropped once read, so a later open by any other door is not re-seeded.
  //
  // With the rail's Spread tab open the hand-over goes there instead: the tab is row C's Spread
  // while it is open (the same claim as the Colour tab's), so seeding the popover behind it would
  // hand the colour to a panel the operator is not looking at.
  const [spreadSeed, setSpreadSeed] = useState<SpreadSeed | null>(null)
  const onSpreadFromColour = useCallback(
    (from: { r: number; g: number; b: number }) => {
      if (railClaim?.tab === 'spread') {
        railClaim.spreadFrom(from)
        return
      }
      setSpreadSeed((prev) => ({ from: { r: from.r, g: from.g, b: from.b }, key: (prev?.key ?? 0) + 1 }))
    },
    [railClaim],
  )
  const consumeSpreadSeed = useCallback(() => setSpreadSeed(null), [])
  const routeProjectId = projectId != null ? Number(projectId) : undefined

  // The marquee has to be counted, or the editor's label line says "1 head" while the commit
  // writes six hundred. **One batch per marquee column**, keyed by column: a commit from a cell in
  // column X lands on column X's targets alone (`commitToCells` shape-filters the rest), so what an
  // editor says about the batch — the count, the skipped heads, the ranges — must be that column's
  // and not the marquee's total. Summing the columns said "1 head has no dimmer · skipped" in the
  // Dimmer editor for a par the *Gobo* column skipped. Hoisted into one memo rather than recomputed
  // per rendered cell — `batchFor` runs once per visible cell per render, and each recompute here
  // is itself O(rows × columns).
  const marqueeBatches = useMemo(
    () => new Map(columnTargets.map(({ col, targets }) => [col, batchForTargets(targets, col)] as const)),
    [columnTargets],
  )

  // Counts write RESOLUTIONS for the column, not rows — a collapsed 12-head bar's colour cell
  // must say "12 heads", matching what planBatchWrites will actually expand the commit into; and
  // carries the heads that resolve nothing, for the read-out's skip count (`CellBatch`).
  const batchFor = useCallback(
    (row: Row, col: ColumnKey): CellBatch => {
      if (row.kind === 'divider') return EMPTY_BATCH
      const marquee = cellSelection.isSelected(row.id, col) ? marqueeBatches.get(col) : undefined
      if (marquee) return marquee
      const targets =
        selection.isSelected(row.id) ? selectedTargets : rowWriteTargets(row)
      return batchForTargets(targets, col)
    },
    [cellSelection, marqueeBatches, selection, selectedTargets],
  )

  // ── The marquee, published for the programmer rail's tabs ─────────────────────────────────────
  //
  // What the cells' own editors are handed — the per-column batches, the columns' heads, the
  // scope's gate and word — plus the rows' heads for a rows-only selection and the throttled
  // column commit (editor-kit plan session 4). Into `ProgrammerPage`'s store; the plain lists
  // mount none and this publishes into nothing. Memoised on what it holds, so a render that moved
  // none of them publishes nothing: the permission is rebuilt from its two booleans because
  // `cellKeyboardPermission` answers a fresh object per call.
  // Held by content for `columnTargets`' reason: `selectedTargets` follows `rows`' identity.
  const railRows = useContentStable(cellCount > 0 ? EMPTY_TARGETS : selectedTargets, sameTargets)
  const permissionEntry = keys.entry
  const permissionClear = keys.clear
  const marqueeSnapshot = useMemo<MarqueeSnapshot>(
    () => ({
      batches: marqueeBatches,
      columns: columnTargets,
      rows: railRows,
      permission: { entry: permissionEntry, clear: permissionClear },
      scopeLabel,
      commit: publishedCommit,
    }),
    [marqueeBatches, columnTargets, railRows, permissionEntry, permissionClear, scopeLabel, publishedCommit],
  )
  usePublishMarquee(marqueeSnapshot)

  // ?select=fixture:<key> / ?select=group:<name> deep-link (Cmd+K lands here):
  // select the row, expand its group if needed, scroll it into view, then
  // consume the param so a refresh doesn't re-pin. Waits for BOTH queries (a
  // group link must not be judged against a groups list that hasn't loaded),
  // and clears the filters — including the persisted Lit toggle — because
  // "navigate to this fixture" must show the fixture, not silently lose it to
  // a filter left on last session.
  const selectParam = searchParams.get('select')
  useEffect(() => {
    if (!enableDeepLinkSelect) return
    if (!selectParam || fixturesLoading || groupsLoading) return
    const parsed = parseSelectParam(selectParam)
    // Group rows only exist on the grouped list. Links minted before the
    // fixtures/groups list split pointed group selects at /fixtures/list, so
    // forward them to /groups/list instead of silently consuming the param.
    if (parsed?.kind === 'group' && !grouped) {
      navigate(`/projects/${projectId}/groups/list?select=${encodeURIComponent(selectParam)}`, {
        replace: true,
      })
      return
    }
    let rowId: RowId | null = null
    if (parsed?.kind === 'group' && groups.some((g) => g.name === parsed.key)) {
      rowId = groupRowId(parsed.key)
    } else if (parsed?.kind === 'fixture') {
      const fixture = fixtures.find((f) => f.key === parsed.key)
      if (fixture) {
        // Flat mode has no member rows — the fixture's own row is the target.
        const parentGroup = grouped
          ? fixture.groups.find((name) => groups.some((g) => g.name === name))
          : undefined
        if (parentGroup) {
          setExpandedGroups((prev) => new Set(prev).add(parentGroup))
          rowId = memberRowId(parentGroup, parsed.key)
        } else {
          rowId = fixtureRowId(parsed.key)
        }
      }
    }
    if (rowId) {
      setFilter('')
      setOnlyLit(false)
      selectRow(rowId, 'replace')
      setScrollToRowId(rowId)
    }
    setSearchParams(
      (prev) => {
        prev.delete('select')
        return prev
      },
      { replace: true },
    )
    // selectRow is referentially stable (a useCallback over the slice's own stable select and
    // the cell clear); setOnlyLit/setSearchParams are stable setters;
    // navigate/projectId only feed the group-forwarding branch, which leaves
    // this route anyway; groups/fixtures/loading flags/grouped cover
    // everything else read here.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    selectParam,
    fixtures,
    groups,
    fixturesLoading,
    groupsLoading,
    grouped,
    enableDeepLinkSelect,
    setSearchParams,
  ])

  // "Select Heads on Include": pick up the fixtures the last Include pulled in. Keyed on the
  // request's nonce, not its contents — including the same cue twice must re-select, since the
  // operator may have changed the selection in between.
  const includeSelection = useIncludeSelectionRequest()
  const includeNonce = respondToIncludeSelection ? includeSelection.nonce : 0
  // Each nonce is applied at most once. The effect also depends on `fixtures`/`groups`/
  // `grouped` — it has to, to map keys onto row ids — and any of those can change long after
  // the Include: a background fixture-list refetch, or the operator hitting the Groups toggle.
  // Without this guard that would silently re-apply the old Include's selection over whatever
  // they had since selected, and reset their filter and scroll position with it.
  const appliedIncludeNonceRef = useRef(0)
  useEffect(() => {
    if (includeNonce === 0 || appliedIncludeNonceRef.current === includeNonce) return
    const { fixtureKeys, groupKeys } = includeSelection
    // Prefer the group row when the sheet is in rollup mode and the whole group came in —
    // that is the shape the operator was working in, and it keeps the row count readable.
    const wanted: RowId[] = []
    if (grouped) {
      const groupSet = new Set(groupKeys)
      for (const name of groupKeys) {
        if (groups.some((g) => g.name === name)) wanted.push(groupRowId(name))
      }
      for (const key of fixtureKeys) {
        const fixture = fixtures.find((f) => f.key === key)
        if (!fixture) continue
        const parent = fixture.groups.find((name) => groups.some((g) => g.name === name))
        // Skip members already covered by a selected group row.
        if (parent && groupSet.has(parent)) continue
        if (parent) {
          setExpandedGroups((prev) => (prev.has(parent) ? prev : new Set(prev).add(parent)))
          wanted.push(memberRowId(parent, key))
        } else {
          wanted.push(fixtureRowId(key))
        }
      }
    } else {
      for (const key of fixtureKeys) {
        if (fixtures.some((f) => f.key === key)) wanted.push(fixtureRowId(key))
      }
    }
    // Nothing resolved — the fixtures may still be loading, so leave the nonce unapplied and
    // let the next run (when they arrive) do it.
    if (wanted.length === 0) return
    appliedIncludeNonceRef.current = includeNonce
    // A filter would hide most of what we just selected, and the operator did not ask for it.
    setFilter('')
    setOnlyLit(false)
    setRows(wanted)
    setScrollToRowId(wanted[0])
    // `includeSelection` is read fresh rather than depended on: its identity changes with every
    // publish, and the arrays inside it are the same data the nonce already tracks.
    // `setRows` and `setOnlyLit` are referentially stable (the former is a useCallback over the
    // slice's own stable setter and the cell clear, both fixed for the mount).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [includeNonce, fixtures, groups, grouped])

  // View-level shortcuts: Escape clears, ⌘/Ctrl+A selects all visible rows,
  // ↑/↓ move the selection (Shift extends the range from the anchor), and →/← open and close the
  // anchor row — a group over its members, a multi-head fixture over its elements — with ← on a
  // member or element climbing to its parent (`treeKeyAction`). Guarded so typing in inputs or
  // interacting inside popovers/dialogs never triggers.
  /** Was a cell editor open when Escape was pressed? See the Escape arm below, and the hook. */
  const escapeFoundEditorRef = useEscapeEditorSnapshot()

  // **The arrows over a cell marquee move it**, as a spreadsheet's do and as every kit sheet's do —
  // `cellArrowStep`'s `grid` rule over the selectable rows and the visible columns, Shift growing
  // a rectangle from the anchor. A step goes through the cell door (`selectCells`' rule, rows
  // cleared first), so the desk bridge publishes it like any marquee, and the table brings the
  // head into view by the least move.
  const [revealCell, setRevealCell] = useState<FixtureCellRef | null>(null)
  const onRevealedCell = useCallback(() => setRevealCell(null), [])
  // A column the row resolves nothing for is drawn blank — no `data-cell`, no ring — so the arrows
  // step past it (`CellGrid.takes`): the same `buildRowCells` answer `firstEditableSelectedCell`
  // reads, cached per row for the life of the grid.
  const cellGrid = useMemo(() => {
    const byRow = new Map<RowId, ReadonlySet<ColumnKey>>()
    const takes = (rowId: RowId, col: ColumnKey): boolean => {
      let cols = byRow.get(rowId)
      if (!cols) {
        const row = rowById.get(rowId)
        cols = new Set(
          row == null || row.kind === 'divider' ? [] : buildRowCells(row, visibleColumns).map((rowCell) => rowCell.col),
        )
        byRow.set(rowId, cols)
      }
      return cols.has(col)
    }
    return { rows: selectableOrder, cols: visibleColumns, takes }
  }, [rowById, selectableOrder, visibleColumns])
  const stepCells = useCallback(
    (direction: CellArrow, extend: boolean): boolean => {
      const head = stepCellSelection(cellSelection, cellGrid, 'grid', direction, extend, () => {
        if (rowCountRef.current > 0) clearRows()
      })
      if (head == null) return false
      setRevealCell(head)
      return true
    },
    [cellGrid, cellSelection, clearRows],
  )

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      // A key another handler has already claimed — ⇧F on `document`'s capture phase, which is the
      // full-screen toggle — is not this grid's to seed an editor with. Same posture as
      // `useTransportKeys`; a bubble listener on `window` is the last to run, so the answer is here.
      if (e.defaultPrevented) return
      if (keyTargetIsGuarded(e.target)) return

      if (e.key === 'Escape') {
        // **An open editor takes Escape first, and keeps the selection.** The clear used to be
        // guarded only by *where the key was pressed* (`keyTargetIsGuarded` above), which is a
        // different question and answers wrongly the moment focus is not inside
        // the panel — on the Set button that opened it, say. Escape then closed the editor **and**
        // took the selection it was opened for.
        //
        // **The answer is snapshotted in the capture phase, not read here.** Radix listens on the
        // *document* and this handler is on the *window*, so Radix runs first on the way up and has
        // already closed the panel — and React has already flushed the unmount, this being a
        // discrete event — by the time this line runs. Asking now would always answer "nothing
        // open". See `escapeFoundEditorRef`.
        if (escapeFoundEditorRef.current) return
        clearByLadder()
        return
      }
      if (cellCount > 0) {
        // Enter moves focus into the field; a character the grammar can start with is carried in
        // as its first character, so typing at the grid just works the way it does in a
        // spreadsheet. Plain keys only — ⌘/Ctrl combinations are someone else's shortcut.
        //
        // **Not from a focused control**, for every arm: a cell trigger is tabbable and
        // Tab-then-Enter selecting that cell is a path the grid already promises, and a Radix menu
        // is `role="menu"`, not `dialog`, so the guard above does not cover a menu item. Backspace
        // is the destructive one — a live `clearEntry` per cell — so it is the arm that most needs
        // to know a chip, the bar's own Set or a menu item had the focus.
        //
        // **Except a cell trigger the marquee itself covers** — `marqueeOwnsKeyTarget`, which is
        // where the whole of that exception is written down and pinned.
        const onControl =
          !marqueeOwnsKeyTarget(e.target, isCellSelected) &&
          e.target instanceof HTMLElement &&
          e.target.closest('button, a, [role="menuitem"], [role="menu"]') != null
        if (e.metaKey || e.ctrlKey || e.altKey || onControl) {
          // fall through to the row shortcuts below
        } else if (e.key === 'Enter') {
          if (!canTypeCells) return
          e.preventDefault()
          openCellEditor('')
          return
        } else if (/^[0-9a-z.]$/i.test(e.key)) {
          // A digit or a dot is the start of a number, and a letter is the start of a gobo
          // wheel's type-ahead — so the character that opens an editor is anything a field on the
          // other side could want, and each editor takes the ones it can use (`numericSeed`).
          // `#` and `,` went with the typed-value grammar: there is no hex to start, and comma is
          // now the step-to-the-next-field key *inside* an editor.
          if (!canTypeCells) return
          e.preventDefault()
          openCellEditor(e.key)
          return
        } else if (e.key === 'Backspace' || e.key === 'Delete') {
          if (canClearCells) {
            e.preventDefault()
            clearSelectedCells()
          }
          return
        }
      }
      // The row keys — ⌘A, ↑/↓ and →/← — stand aside from a focused control outside the rows (a
      // template chip, a verb, a menu): the kit's rule, `isForeignControl`, which exempts the name
      // and cell triggers a click inside a row leaves focused.
      if (isForeignControl(e.target)) return
      if ((e.metaKey || e.ctrlKey) && e.key === 'a') {
        e.preventDefault()
        selectAllRows()
        return
      }
      const arrow = arrowOfKey(e.key)
      if (arrow != null && cellCount > 0) {
        // Over a cell marquee every arrow is the marquee's; →/← open and close the tree only over
        // a row selection. Plain and Shift only, and not under an open editor — the kit's guards.
        if (e.metaKey || e.ctrlKey || e.altKey || editorIsOpen()) return
        if (stepCells(arrow, e.shiftKey)) e.preventDefault()
        return
      }
      if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
        // Not from a focused control either *inside* a row — a chip or a cell trigger does not
        // want its row opened under it — on top of the foreign-control rule above.
        if (
          e.target instanceof HTMLElement &&
          e.target.closest('button, a, [role="menuitem"], [role="menu"]') != null
        ) {
          return
        }
        const anchorRow = selection.anchor ? rowById.get(selection.anchor) : undefined
        const action = anchorRow ? treeKeyAction(anchorRow, e.key) : null
        if (!action) return
        e.preventDefault()
        if (action.kind === 'select-parent') {
          selectRow(action.rowId, 'replace')
          setScrollToRowId(action.rowId)
        } else {
          handleToggleExpand(action.row)
        }
        return
      }
      if ((e.key === 'ArrowUp' || e.key === 'ArrowDown') && !e.metaKey && !e.ctrlKey && !e.altKey) {
        // The kit's rule (`arrowStepTarget`), so this list and every sheet step alike — plain and
        // Shift arrows only, as the kit's: ⌘, Ctrl and ⌥ arrows are the browser's and the OS's.
        const next = arrowStepTarget(selectableOrder, selection, e.key === 'ArrowDown' ? 'down' : 'up', e.shiftKey)
        if (next == null) return
        e.preventDefault()
        selectRow(next, e.shiftKey ? 'range' : 'replace')
        setScrollToRowId(next)
      }
    }
    window.addEventListener('keydown', onKeyDown)
    return () => window.removeEventListener('keydown', onKeyDown)
    // Narrowed to the two fields this reads rather than the whole `cellSelection`: its identity
    // changes with the selection, and re-binding a window listener on every marquee tick is a cost
    // with no payoff. `clearCells` is stable; `cellCount` is the only value that has to be fresh.
    // `clearSelectedCells` is the exception this accepts: it closes over `cellSelection`, `rows`
    // and `writers`, so it rebinds on every marquee change, a filter or expansion change, and a
    // scope change — more often than `cellCount` — because the alternative is reading the current
    // selection through a ref inside a handler that also has to plan writes against `rows`, and a
    // rebind is cheaper than that second copy of the state. `openCellEditor` follows the same cadence
    // for the same reason. `canTypeCells` is a boolean, and `isCellSelected` is stable for the
    // mount — it reads `useCellSelection`'s own ref, which is why the marquee test above costs
    // this listener no extra rebinds.
  }, [selection, selectRow, selectAllRows, selectableOrder, rowById, handleToggleExpand, cellCount, clearByLadder, escapeFoundEditorRef, canClearCells, canTypeCells, clearSelectedCells, isCellSelected, openCellEditor, stepCells])

  if (fixturesLoading || groupsLoading) {
    return <SheetPage.Empty loading />
  }

  // The container owns these controls' state, so a caller re-arranging the toolbar gets them as
  // ready-made nodes rather than re-implementing them. See `renderToolbar`.
  const filterControl = (
    <div className="relative min-w-0 flex-1">
      <Search className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
      <Input
        placeholder={filterPlaceholder}
        title={FIXTURE_FILTER_HINT}
        aria-label={FIXTURE_FILTER_HINT}
        value={filter}
        onChange={(e) => setFilter(e.target.value)}
        className="h-8 pl-9"
      />
    </div>
  )

  const litControl = (
    <Button
      variant={onlyLit ? 'default' : 'outline'}
      size="sm"
      onClick={() => setOnlyLit(!onlyLit)}
      title="Show only fixtures with intensity above zero"
    >
      <Lightbulb className="size-3.5" />
      {/* Icon-only on phones: the toolbar is already several rows deep there, and both of these
          carry a title/tooltip. `compactControls` is the same answer asked for by a caller whose
          toolbar is short of width for a reason the viewport cannot see — see the prop. */}
      <span className={labelUnlessCompact(compactControls, 'sm:inline')}>Lit</span>
    </Button>
  )

  const columnsControl = (
    <ColumnsMenu
      visibility={columnVisibility}
      onChange={setColumnVisibility}
      compact={compactControls}
    />
  )

  // The cell verbs — Set, Clear, Spread — with the container's own gate and words behind them, so
  // a button cannot promise a gesture the keyboard refuses. Set *is* Enter: the same request, the
  // same first cell, the same commit to every selected cell.
  //
  // On the programmer a rows-only selection gets none of them: Spread reads the marquee there,
  // which is what the selection is for. The two plain list routes keep the *row* Spread they
  // always had beside the marquee's — over the whole selection, column chosen in the panel. That
  // is the one gesture this session did not fold into the marquee: a row selection there is made
  // by dragging the name column or by ⌘A, and spreading a colour across eight whole heads without
  // first drawing a rectangle over one of their columns is a real gesture those two views already
  // offered.
  const selectionActions =
    cellCount > 0 ? (
      <CellSelectionActions
        copy={cellActionCopy(scope, focusedTemplate != null, cellCount)}
        permission={keys}
        setRef={setButtonRef}
        onSet={toggleCellEditor}
        onClear={clearSelectedCells}
        spread={
          <SpreadPopover
            columns={columnTargets}
            projectId={routeProjectId}
            desk={selectionScope === 'programmer'}
            scopeLabel={scopeLabel}
            seed={spreadSeed}
            onSeedConsumed={consumeSpreadSeed}
            // The rail's Spread tab, when it is open, is this verb's panel: the press focuses its
            // From rather than opening a second Spread over the same marquee.
            onClaimed={railClaim?.tab === 'spread' ? railClaim.focusSpread : undefined}
            className={PHONE_FOLDED_CLASS}
          />
        }
      />
    ) : !showOwnership && selectedTargets.length > 0 ? (
      <SpreadPopover columns={rowSpreadColumns} projectId={routeProjectId} desk={selectionScope === 'programmer'} scopeLabel={scopeLabel} />
    ) : null

  // Gate on VISIBLE selected rows, not the raw selection count — filtering away every selected row
  // must not leave a live toolbar acting on an empty set.
  const selectionControl =
    locateTargets.length > 0 ? (
      <SelectionToolbar
        locateTargets={locateTargets}
        targets={selectedTargets}
        onClear={clearByLadder}
        actions={selectionActions}
      />
    ) : null

  return (
    // One arm: a flex column the grid fills, under a row or two of chrome and over a footer that
    // sits hard against the grid's last row (CLAUDE.md §List shell). There was a second arm — a
    // `space-y-3` rhythm for the two plain lists inside their `Card` — and the Card is gone.
    <div className="flex min-h-0 flex-1 flex-col">
      {renderToolbar ? (
        renderToolbar({
          filter: filterControl,
          lit: litControl,
          columns: columnsControl,
          selection: selectionControl,
          cells: cellSelection.cells,
          cellEntryKey: cellCount > 0 && keys.entry,
          cellClearKey: cellCount > 0 && keys.clear,
          templateTargets,
          targetFamilies: templateFamilies,
          targetEmitters: templateEmitters,
          marqueeDragging,
        })
      ) : (
        <>
          {/* The shell's toolbar row: the filter takes the slack before the spacer does and is
              capped at 340 (the programmer's row B rule), Lit and Columns at the right. On a phone
              the field simply gives — the hint rides its `title` — rather than taking a row of its
              own, since a row here is a 40px rung and not a wrapping strip. */}
          <SheetPage.Row>
            <div className="flex min-w-0 max-w-[340px] flex-[999_1_0%] items-center">{filterControl}</div>
            <span className="flex-1" />
            {litControl}
            {columnsControl}
          </SheetPage.Row>
          {/* Row C, reserved when nothing is selected — the programmer's bar with no template strip
              (no `projectId`), so the two cannot disagree about what a marquee names. Its own
              `@container`, with the queries on the child — the wrapper trap `ProgrammerWorkspace`
              documents. */}
          <div className="@container">
            <ListSelectionBar
              selection={selectionControl}
              cells={cellSelection.cells}
              cellEntryKey={cellCount > 0 && keys.entry}
              cellClearKey={cellCount > 0 && keys.clear}
              templateTargets={templateTargets}
              marqueeDragging={marqueeDragging}
            />
          </div>
        </>
      )}

      {rows.length === 0 ? (
        // The shell's body for an empty list, so the footer stays at the bottom of the column
        // rather than riding up under the sentence (CLAUDE.md §List shell).
        <SheetPage.Empty>
          {fixtures.length === 0
            ? 'No fixtures available'
            : onlyLit && !filter.trim()
              ? 'No fixtures are currently lit'
              : 'No fixtures match your filter'}
        </SheetPage.Empty>
      ) : (
        <FixturesTable
          rows={rows}
          visibleColumns={visibleColumns}
          // The row slice, deliberately — not `selectedRowIds`. Under a marquee the cells' outline
          // is the selection, and the rows they sit on draw no wash, edge or bold name even
          // though Locate, Highlight, Record and the count act on them: drawing both would be the
          // two vocabularies for one fact that removed the checkbox. Decided at the desk pass.
          isSelected={selection.isSelected}
          onRowClick={handleRowClick}
          onToggleExpand={handleToggleExpand}
          onBeginCellEdit={handleBeginCellEdit}
          onCellCommit={handleCellCommit}
          batchFor={batchFor}
          scopeLabel={scopeLabel}
          // The route's, as a number, for the **programmer alone** — what the colour editor's
          // leaves, Recent and Save need. All three routes are under `/projects/:projectId`, but the
          // two plain lists deliberately draw no template strip (§List shell) and Save records from
          // the programmer, so their colour cells get none of the three, like the strip.
          projectId={selectionScope === 'programmer' && projectId != null ? Number(projectId) : undefined}
          onSpread={onSpreadFromColour}
          onShowInfo={handleShowInfo}
          scrollToRowId={scrollToRowId}
          onScrolledToRow={() => setScrollToRowId(null)}
          revealCell={revealCell}
          onRevealedCell={onRevealedCell}
          showOwnership={showOwnership}
          // An open cell editor belongs to whatever is selected — the marquee it sits in, or the
          // row selection its own click created. Deselect while one is open and it stays on
          // screen still writing, to something narrower than its own "Applying to N" line just
          // claimed. Both selections, because either is enough to keep an editor honest.
          selectionEmpty={selection.count === 0 && cellCount === 0}
          cellSelection={tableCellSelection}
          // A drag from the name column selects rows, on every list this table serves: it is what
          // the checkbox column was for, and a list with no way to accumulate a selection by
          // touch would be a regression on the two plain routes.
          onRowMarquee={setRows}
          editorAnchorRef={setButtonRef}
          keyboardOpen={keyboardOpen}
          closeEditorCell={closeEditorCell}
          onMarqueeDragChange={setMarqueeDragging}
          onBackgroundClick={clearByLadder}
        />
      )}

      {renderFooter?.({ fixtureCount, groupCount, selectedCount: locateTargets.length })}

      <FixtureDetailModal fixtureKey={infoFixtureKey} onClose={() => setInfoFixtureKey(null)} />
      <GroupDetailModal groupName={infoGroupName} onClose={() => setInfoGroupName(null)} />
    </div>
  )
}

/**
 * One column's commit over some targets: planned (`planBatchWrites` — per-target clamping and the
 * commit's shape filter) and applied through the writers. Answers how many writes it planned. The
 * three commits here — a marquee's, a row selection's and the rail's column commit — all land
 * through it.
 */
function writeBatch(writers: CellWriters, targets: readonly WriteTarget[], col: ColumnKey, commit: CellCommit): number {
  let written = 0
  for (const planned of planBatchWrites(targets, col, commit)) {
    applyPlannedWrite(writers, planned)
    written += 1
  }
  return written
}

/**
 * `next`, unless it says what the held value already says — then the held one, so what is keyed on
 * it does not move. React's derive-state-during-render idiom (the `cellRowIds` one above): a change
 * is adopted in this render and stored for the next, with no effect and no ref written in a memo.
 */
function useContentStable<T>(next: T, same: (a: T, b: T) => boolean): T {
  const [held, setHeld] = useState(next)
  if (held === next || same(held, next)) return held
  setHeld(next)
  return next
}

/**
 * Two target lists name the same heads with the same descriptors. An element row's target is a
 * fresh object per expansion (`rowWriteTargets` stamps its parent), so this compares what a write
 * reads rather than identity; a refetched patch hands new descriptor arrays and so reads as a change.
 */
function sameTargets(a: readonly WriteTarget[], b: readonly WriteTarget[]): boolean {
  return (
    a.length === b.length &&
    a.every((t, i) => {
      const u = b[i]
      return t === u || (t.key === u.key && t.properties === u.properties && t.elements === u.elements && t.fixtureKey === u.fixtureKey && t.cellIndex === u.cellIndex)
    })
  )
}

function sameColumns(a: readonly SpreadColumn[], b: readonly SpreadColumn[]): boolean {
  return a.length === b.length && a.every((c, i) => c.col === b[i].col && sameTargets(c.targets, b[i].targets))
}

function sameSet(a: ReadonlySet<RowId>, b: ReadonlySet<RowId>): boolean {
  if (a.size !== b.size) return false
  for (const id of a) if (!b.has(id)) return false
  return true
}
