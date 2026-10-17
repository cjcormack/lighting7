import { useCallback, useEffect, useMemo, useRef } from 'react'
import { useParams, useSearchParams } from 'react-router'
import { Breadcrumbs } from '../components/Breadcrumbs'
import { AppliedTableSwitcher } from '../components/ViewSwitcher'
import { SheetPage } from '../components/sheet/SheetPage'
import { AppliedFxTable } from '../components/applied/AppliedFxTable'
import { AppliedLayerTable } from '../components/applied/AppliedLayerTable'
import { useProjectQuery } from '../store/projects'
import {
  APPLIED_TABLE_LABELS,
  DEFAULT_APPLIED_VIEW,
  appliedSearch,
  parseAppliedTable,
  rememberView,
  rememberedView,
  setAppliedTable,
  useAppliedTable,
  viewFromSearch,
  type AppliedTable,
  type AppliedView,
} from '../lib/appliedTables'

/**
 * **The applied tables** (venue-visit plan S14): `/projects/:id/programmer/applied?table=fx` — what
 * the programmer is running and holding, full width, one tab per table (Chris's call, 2026-10-17:
 * one route with tabs, a child of the Programmer row). The rail lists the same things in 300px;
 * these are the room to read them in, sort them and act on several at once.
 *
 * **Which table is this window's** (`lib/appliedTables.ts`): the route reads `?table=` when one
 * arrives and remembers it, and mirrors the window's table back into the address when another
 * window moves it (the Screens sheet's *Table* segment). **A table's view is the address too** —
 * sort, filter and chip — remembered per window and table, so a tab lands where it was left.
 *
 * Behind `RigGate`: it reads the live programmer, so off the rig it draws `NotOnRig`.
 */
export function ProgrammerApplied() {
  const { projectId } = useParams()
  const projectIdNum = Number(projectId)
  const { data: project } = useProjectQuery(projectIdNum)
  const [searchParams, setSearchParams] = useSearchParams()

  const stored = useAppliedTable()
  const arrived = parseAppliedTable(searchParams.get('table'))
  const table = arrived ?? stored

  // A `?table=` arriving — a tab, a ⌘K deep link, a copied link — is this window's table now.
  useEffect(() => {
    if (arrived != null) setAppliedTable(arrived)
  }, [arrived])

  // The window's table moved from elsewhere (a `windows.viewOptions {table}`): follow it into the
  // address. Not on mount, where the address is the answer.
  const lastStored = useRef(stored)
  useEffect(() => {
    if (lastStored.current === stored) return
    lastStored.current = stored
    if (arrived !== stored) setSearchParams(new URLSearchParams(appliedSearch(stored, rememberedView(stored))), { replace: true })
  }, [arrived, setSearchParams, stored])

  const [view, setView] = useAppliedView(table, searchParams, setSearchParams)

  return (
    <SheetPage>
      <SheetPage.Header>
        <Breadcrumbs projectName={project?.name ?? ''} currentPage={APPLIED_TABLE_LABELS[table]} />
        <div className="ml-auto shrink-0">
          <AppliedTableSwitcher current={table} projectId={projectIdNum} />
        </div>
      </SheetPage.Header>
      {table === 'fx' ? (
        <AppliedFxTable key="fx" projectId={projectIdNum} view={view} onViewChange={setView} />
      ) : (
        <AppliedLayerTable
          key={table}
          projectId={projectIdNum}
          kind={table === 'looks' ? 'LOOK' : 'TEMPLATE'}
          view={view}
          onViewChange={setView}
        />
      )}
    </SheetPage>
  )
}

/**
 * [table]'s view from the address, else the one this window left it on — mirrored into the address
 * with `replace` when the address stated none, so a reload or a copied link lands on the same view.
 */
function useAppliedView(
  table: AppliedTable,
  searchParams: URLSearchParams,
  setSearchParams: ReturnType<typeof useSearchParams>[1],
): [AppliedView, (next: AppliedView) => void] {
  const stated = useMemo(() => viewFromSearch(searchParams, table), [searchParams, table])
  const view = useMemo(() => stated ?? rememberedView(table), [stated, table])

  useEffect(() => {
    if (stated != null) rememberView(table, stated)
  }, [stated, table])

  const urlTable = searchParams.get('table')
  useEffect(() => {
    // The address says nothing of the table, or nothing of a view this window remembers.
    if (urlTable == null || (stated == null && !isDefaultView(view))) {
      setSearchParams(new URLSearchParams(appliedSearch(table, view)), { replace: true })
    }
  }, [setSearchParams, stated, table, urlTable, view])

  const change = useCallback(
    (next: AppliedView) => {
      rememberView(table, next)
      setSearchParams(new URLSearchParams(appliedSearch(table, next)), { replace: true })
    },
    [setSearchParams, table],
  )
  return [view, change]
}

function isDefaultView(view: AppliedView): boolean {
  return (
    view.sort === DEFAULT_APPLIED_VIEW.sort &&
    view.dir === DEFAULT_APPLIED_VIEW.dir &&
    view.q === DEFAULT_APPLIED_VIEW.q &&
    view.chip === DEFAULT_APPLIED_VIEW.chip
  )
}
