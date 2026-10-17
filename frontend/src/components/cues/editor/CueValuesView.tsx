import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react'
import { buildRows, selectedRowTargets, type Row, type RowId } from '@/components/fixtures-list/rowModel'
import type { ColumnKey } from '@/components/fixtures-list/columns'
import { useCellSelection, type CellSelection } from '@/components/sheet/useCellSelection'
import { useLocalListSelection, type LocalListSelection } from '@/components/sheet/useLocalListSelection'
import type { ListSelectIntent } from '@/components/sheet/listSelectionModel'
import type { CellRef } from '@/components/sheet/cellSelectionModel'
import { useFixtureLookup } from '@/hooks/useFixtureLookup'
import { useVisibleFixtureListQuery } from '@/store/fixtures'
import { useGroupListQuery } from '@/store/groups'
import type { CueTarget } from '@/api/cuesApi'
import { useCueRowStore, type CueRowStoreValue } from './CueRowStore'

/**
 * The Values grid's rows, view toggles and selection, held by the editor rather than the grid
 * (show-cue-editing plan §4.1): the Looks tab's *+ Look*, *+ Palette* and *Targets* and the FX tab's
 * *+ Effect* act on the Values selection, and a tab switch unmounts the grid. Local state, never the
 * desk's selection (P2).
 */
export interface CueValuesView {
  rows: Row[]
  wholeRig: boolean
  setWholeRig: (next: boolean | ((prev: boolean) => boolean)) => void
  ownOnly: boolean
  setOwnOnly: (next: boolean | ((prev: boolean) => boolean)) => void
  addTarget: (target: CueTarget) => void
  toggleGroupExpanded: (name: string) => void
  toggleFixtureExpanded: (key: string) => void
  rowSelection: LocalListSelection
  cellSelection: CellSelection<ColumnKey>
  /** A marquee's cells, clearing a row selection first — the one selection in two shapes. */
  selectCells: (hits: readonly CellRef<ColumnKey>[], intent: ListSelectIntent) => void
  selectRow: (id: RowId, intent?: ListSelectIntent) => void
  setRows: (ids: readonly RowId[]) => void
  /** Escape's ladder — cells, then rows — answering whether it cleared anything. */
  clearByLadder: () => boolean
  rowById: ReadonlyMap<RowId, Row>
  /**
   * What the selection stands for, in row order: a group row as its group, a fixture as itself, a
   * cell by its element key — the rows a marquee covers, else the selected rows.
   */
  selectionTargets: readonly CueTarget[]
}

const CueValuesViewContext = createContext<CueValuesView | null>(null)

export function useCueValuesView(): CueValuesView | null {
  return useContext(CueValuesViewContext)
}

const NO_TARGETS: readonly CueTarget[] = []

export function CueValuesViewProvider({ children }: { children: ReactNode }) {
  const store = useCueRowStore()
  if (store == null) return <>{children}</>
  return <ViewProvider store={store}>{children}</ViewProvider>
}

function ViewProvider({ store, children }: { store: CueRowStoreValue; children: ReactNode }) {
  const { fixtures: allFixtures } = useFixtureLookup()
  const { data: visibleFixtures } = useVisibleFixtureListQuery()
  const { data: groups } = useGroupListQuery()
  const [wholeRig, setWholeRig] = useState(false)
  const [ownOnly, setOwnOnly] = useState(false)
  const [added, setAdded] = useState<ReadonlySet<string>>(new Set())
  const [addedGroups, setAddedGroups] = useState<ReadonlySet<string>>(new Set())
  const [expandedGroups, setExpandedGroups] = useState<ReadonlySet<string>>(new Set())
  const [expandToggled, setExpandToggled] = useState<ReadonlySet<string>>(new Set())

  // ── The rows (D14) ─────────────────────────────────────────────────────────────────────────────
  const rows = useMemo<Row[]>(() => {
    const fixtures = allFixtures ?? []
    const visible = new Set((visibleFixtures ?? []).map((f) => f.key))
    const holds = (key: string) => store.heads.has(key)
    const ownHolds = (key: string) => store.ownTargets.has(key)
    const elementKeysOf = (f: (typeof fixtures)[number]) => (f.elements ?? []).map((e) => e.key)
    const held = (f: (typeof fixtures)[number]) => holds(f.key) || elementKeysOf(f).some(holds)
    const ownHeld = (f: (typeof fixtures)[number]) => ownHolds(f.key) || elementKeysOf(f).some(ownHolds)
    const shown = fixtures.filter((f) =>
      ownOnly ? ownHeld(f) : held(f) || added.has(f.key) || (wholeRig && visible.has(f.key)),
    )
    // Cells under a multi-head fixture where the cue holds them; the operator's toggle flips that.
    const expandedFixtures = new Set<string>()
    for (const f of shown) {
      const auto = elementKeysOf(f).some(holds)
      if (auto !== expandToggled.has(f.key)) expandedFixtures.add(f.key)
    }
    const groupNames = new Set([...store.ownGroups, ...(ownOnly ? [] : addedGroups)])
    const groupSummaries = (groups ?? []).filter((g) => groupNames.has(g.name))
    // A group row's members are the ones the rig shows — never an infrastructure fixture.
    const groupRows = buildRows({
      fixtures: visibleFixtures ?? [],
      groups: groupSummaries,
      expandedGroups,
      expandedFixtures,
      textFilter: '',
    }).filter((row) => row.kind === 'group' || (row.kind !== 'divider' && row.parentGroup !== undefined))
    const fixtureRows = buildRows({
      fixtures: shown,
      groups: [],
      expandedGroups: new Set(),
      expandedFixtures,
      textFilter: '',
      groupByGroups: false,
    })
    if (groupRows.length > 0 && fixtureRows.length > 0) {
      return [...groupRows, { kind: 'divider', id: 'divider:cue-fixtures', label: 'Fixtures' }, ...fixtureRows]
    }
    return [...groupRows, ...fixtureRows]
  }, [allFixtures, visibleFixtures, groups, store.heads, store.ownTargets, store.ownGroups, ownOnly, wholeRig, added, addedGroups, expandedGroups, expandToggled])

  // ── One selection, two shapes ──────────────────────────────────────────────────────────────────
  const selectableOrder = useMemo(() => rows.filter((row) => row.kind !== 'divider').map((row) => row.id), [rows])
  const visibleRowIds = useMemo(() => new Set(rows.map((row) => row.id)), [rows])
  const rowSelection = useLocalListSelection(selectableOrder)
  const cellSelection = useCellSelection<ColumnKey>(visibleRowIds)
  const { count: cellCount, clear: clearCells } = cellSelection
  const rowCountRef = useRef(rowSelection.count)
  rowCountRef.current = rowSelection.count
  const { select: selectRowRaw, setSelection: setRowsRaw, clear: clearRows } = rowSelection
  const selectCells = useCallback(
    (hits: readonly CellRef<ColumnKey>[], intent: ListSelectIntent) => {
      if (hits.length > 0 && rowCountRef.current > 0) clearRows()
      cellSelection.select(hits, intent)
    },
    [cellSelection, clearRows],
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
  const clearByLadder = useCallback(() => {
    if (cellCount > 0) {
      clearCells()
      return true
    }
    if (rowSelection.count > 0) {
      clearRows()
      return true
    }
    return false
  }, [cellCount, clearCells, clearRows, rowSelection.count])
  const rowById = useMemo(() => new Map(rows.map((row) => [row.id, row])), [rows])

  const selectionTargets = useMemo(() => {
    const ids = cellCount > 0 ? new Set(cellSelection.cells.map((cell) => cell.rowId)) : rowSelection.selectedIds
    if (ids.size === 0) return NO_TARGETS
    return selectedRowTargets(rows, ids) as CueTarget[]
  }, [cellCount, cellSelection.cells, rowSelection.selectedIds, rows])

  const addTarget = useCallback((target: CueTarget) => {
    if (target.type === 'group') setAddedGroups((prev) => new Set(prev).add(target.key))
    else setAdded((prev) => new Set(prev).add(target.key))
  }, [])
  const toggleGroupExpanded = useCallback((name: string) => setExpandedGroups((prev) => toggled(prev, name)), [])
  const toggleFixtureExpanded = useCallback((key: string) => setExpandToggled((prev) => toggled(prev, key)), [])

  const value = useMemo<CueValuesView>(
    () => ({
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
      selectionTargets,
    }),
    [rows, wholeRig, ownOnly, addTarget, toggleGroupExpanded, toggleFixtureExpanded, rowSelection, cellSelection, selectCells, selectRow, setRows, clearByLadder, rowById, selectionTargets],
  )
  return <CueValuesViewContext.Provider value={value}>{children}</CueValuesViewContext.Provider>
}

function toggled(prev: ReadonlySet<string>, key: string): ReadonlySet<string> {
  const next = new Set(prev)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  return next
}
