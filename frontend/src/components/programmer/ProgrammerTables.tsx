import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, type ReactNode } from 'react'
import { useSearchParams } from 'react-router'
import { cn } from '@/lib/utils'
import { useStableCallback } from '@/hooks/useStableCallback'
import { AppliedFxTable } from '@/components/applied/AppliedFxTable'
import { SceneryTable } from '@/components/applied/SceneryTable'
import { StackTable } from '@/components/applied/StackTable'
import { HandProgrammerLayerStrip } from '@/components/hand/HandLayerTargets'
import {
  DEFAULT_TABLE_VIEW,
  getProgrammerTable,
  parseProgrammerTable,
  programmerSearch,
  rememberView,
  rememberedView,
  setProgrammerTable,
  useProgrammerTable,
  viewFromSearch,
  PROGRAMMER_TABLE_LABELS,
  type ProgrammerTable,
  type TableView,
} from '@/lib/programmerTables'
import {
  ProgrammerTablesContext,
  programmerTablePanelId,
  useProgrammerTableCounts,
  type ProgrammerTablesChoice,
} from './ProgrammerTableTabs'
import { ProgrammerAddFxDoor } from './ProgrammerAddFx'
import { ReplacementNotesProvider } from './useReplacementNotes'

/**
 * **The programmer's four tables in one frame** (tables-colours plan D1): the Values grid, always
 * mounted, and Stack, FX or Scenery mounted while chosen.
 *
 * **The grid never unmounts.** `useListSelection` clears its Redux scope on unmount, so a tab that
 * mounted the grid on demand would throw away the selection Record scopes on. It is hidden and
 * `inert` while another table shows — the grid's window keyboard stands down under `inert` — and
 * the [values] element is the page's, created above this frame, so a tab switch re-renders this
 * frame and never the grid.
 *
 * **Which table is the window's** (`lib/programmerTables.ts`): a `?table=` arriving wins and is
 * remembered; the window's table moving from elsewhere (the Screens sheet) is mirrored into the
 * address with `replace`; a press on a tab replaces the address with that table and the view the
 * window left it on.
 */
export function ProgrammerTables({ projectId, values }: { projectId: number; values: ReactNode }) {
  const [searchParams, setSearchParams] = useSearchParams()
  // The store is what is drawn, so `ProgrammerBody` — which reads it to place row A — and this frame
  // switch in one commit. The address follows it, and an address arriving moves it before paint.
  const table = useProgrammerTable()
  const arrived = parseProgrammerTable(searchParams.get('table'))
  const settled = arrived == null || arrived === table
  const lastStored = useRef(table)

  useLayoutEffect(() => {
    if (arrived == null) return
    lastStored.current = arrived
    setProgrammerTable(arrived)
  }, [arrived])

  // The window's table moved from elsewhere (the Screens sheet): follow it into the address. Not
  // from a render the store has already moved past — an arrival's first, before the layout effect.
  useEffect(() => {
    if (table !== getProgrammerTable() || lastStored.current === table) return
    lastStored.current = table
    if (arrived !== table) setSearchParams(new URLSearchParams(programmerSearch(table, rememberedView(table))), { replace: true })
  }, [arrived, setSearchParams, table])

  // Stable, so the tabs re-render on a switch and not on every keystroke in a table's filter.
  const select = useStableCallback((next: ProgrammerTable) => {
    lastStored.current = next
    setProgrammerTable(next)
    setSearchParams(new URLSearchParams(programmerSearch(next, rememberedView(next))), { replace: true })
  })
  const counts = useProgrammerTableCounts(projectId)
  const choice = useMemo<ProgrammerTablesChoice>(() => ({ table, select, counts }), [counts, select, table])
  const [view, setView] = useTableView(table, settled, searchParams, setSearchParams)

  return (
    <ProgrammerTablesContext.Provider value={choice}>
      <ReplacementNotesProvider>
        <div
          className={cn('flex min-h-0 min-w-0 flex-1 flex-col', table !== 'values' && 'hidden')}
          inert={table !== 'values'}
          id={programmerTablePanelId('values')}
          role="tabpanel"
          aria-label={PROGRAMMER_TABLE_LABELS.values}
          data-programmer-table="values"
        >
          {values}
        </div>
        {table !== 'values' && (
          <div
            className="flex min-h-0 min-w-0 flex-1 flex-col"
            id={programmerTablePanelId(table)}
            role="tabpanel"
            aria-label={PROGRAMMER_TABLE_LABELS[table]}
            data-programmer-table={table}
          >
            {table === 'stack' ? (
              <StackTable key="stack" projectId={projectId} view={view} onViewChange={setView} />
            ) : table === 'fx' ? (
              <AppliedFxTable key="fx" projectId={projectId} view={view} onViewChange={setView} />
            ) : (
              <SceneryTable key="scenery" projectId={projectId} view={view} onViewChange={setView} />
            )}
          </div>
        )}
        {/* The hand's place on the programmer's stack, under whichever table shows — drawn only while
            something that can become a layer is held — and ⌘K's *+ Effect*, which draws nothing until
            asked. Both outlive a tab switch. */}
        <HandProgrammerLayerStrip className="mx-3 my-1.5 w-auto shrink-0" />
        <ProgrammerAddFxDoor projectId={projectId} />
      </ReplacementNotesProvider>
    </ProgrammerTablesContext.Provider>
  )
}

/**
 * [table]'s view from the address, else the one this window left it on — mirrored into the address
 * with `replace` when the address stated none, so a reload or a copied link lands on the same view.
 * Values states none: a bare `/programmer` stays bare.
 */
function useTableView(
  table: ProgrammerTable,
  /** The address names [table], or names none: until then its parameters are another table's. */
  settled: boolean,
  searchParams: URLSearchParams,
  setSearchParams: ReturnType<typeof useSearchParams>[1],
): [TableView, (next: TableView) => void] {
  const stated = useMemo(() => (settled ? viewFromSearch(searchParams, table) : null), [searchParams, settled, table])
  const view = useMemo(() => stated ?? rememberedView(table), [stated, table])

  useEffect(() => {
    if (stated != null) rememberView(table, stated)
  }, [stated, table])

  const urlTable = searchParams.get('table')
  useEffect(() => {
    if (!settled) return
    if (table === 'values' && (urlTable == null || urlTable === 'values')) return
    if (urlTable == null || (stated == null && !isDefaultView(view))) {
      setSearchParams(new URLSearchParams(programmerSearch(table, view)), { replace: true })
    }
  }, [setSearchParams, settled, stated, table, urlTable, view])

  const change = useCallback(
    (next: TableView) => {
      rememberView(table, next)
      setSearchParams(new URLSearchParams(programmerSearch(table, next)), { replace: true })
    },
    [setSearchParams, table],
  )
  return [view, change]
}

function isDefaultView(view: TableView): boolean {
  return (
    view.sort === DEFAULT_TABLE_VIEW.sort &&
    view.dir === DEFAULT_TABLE_VIEW.dir &&
    view.q === DEFAULT_TABLE_VIEW.q &&
    view.chip === DEFAULT_TABLE_VIEW.chip
  )
}
