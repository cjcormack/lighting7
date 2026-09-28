import { useMemo, useState } from 'react'
import { useParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Loader2, Plus } from 'lucide-react'
import { Breadcrumbs } from '../components/Breadcrumbs'
import { SheetPage } from '../components/sheet/SheetPage'
import { LibraryRow } from '../components/sheet/LibraryRow'
import { SpeedMasterDetailSheet } from '../components/speedMasters/SpeedMasterDetailSheet'
import { SpeedMasterSheet } from '../components/speedMasters/SpeedMasterSheet'
import { useProjectQuery } from '../store/projects'
import { useCreateSpeedMasterMutation, useSpeedMasterListQuery } from '../store/speedMasters'
import { followRatioOf } from '../lib/speedMasterModel'
import type { SpeedMaster } from '../api/speedMastersApi'
import { CurrentProjectRedirect } from '../components/CurrentProjectRedirect'

// Redirect /speed-masters → /projects/:projectId/speed-masters
export function SpeedMastersRedirect() {
  return <CurrentProjectRedirect to="speed-masters" />
}

/**
 * The speed-master bank as a **sheet** (library-sheets plan §3.2, session 1): the list shell's
 * header, the library row (filter · *New master*), the selection bar, `SpeedMasterSheet` and the
 * footer. It replaced a `Card` of row cards (`SpeedMasterRow`); the detail sheet stays, as what a
 * row's pencil — or ⏎ over one row — opens (D1).
 *
 * Two sources, deliberately kept apart, joined in the sheet by uuid: the **list** query is the
 * persisted row — identity, name, notes, reference count, routing, the link and the *starting*
 * BPM — and the **live** query is the running bank. Off the current project there is no running
 * bank of this project's, so the live tempo is read-only there (D12): BPM and TAP write the
 * running show's clocks, and before the sheet a TAP on another project's master 1 tapped the live
 * one, since master 1 is written as a null uuid.
 *
 * The ShowBar's `SpeedMasters` surface lists every master too, master 1 included. What is only
 * here: renaming a master, annotating it, creating and deleting one, and editing the stored boot
 * tempo — now in the Start column as well as the sheet.
 */
export function ProjectSpeedMasters() {
  const { projectId } = useParams<{ projectId: string }>()
  const projectIdNum = Number(projectId)
  const { data: project, isLoading: projectLoading } = useProjectQuery(projectIdNum)
  const { data: masters, isLoading } = useSpeedMasterListQuery({ projectId: projectIdNum })
  const [createMaster, { isLoading: isCreating }] = useCreateSpeedMasterMutation()
  const [openMasterId, setOpenMasterId] = useState<number | null>(null)
  const [filter, setFilter] = useState('')

  // The open sheet follows the freshest row: the list refetches whenever a master is created,
  // renamed or deleted, and a stale snapshot would show the pre-edit name.
  const openMaster = openMasterId == null ? null : (masters?.find((m) => m.id === openMasterId) ?? null)

  const bank = useMemo(() => masters ?? [], [masters])
  const shown = useMemo(() => filterMasters(bank, filter), [bank, filter])
  const followerCount = bank.filter((m) => followRatioOf(m) != null).length

  if (projectLoading) {
    return (
      <SheetPage>
        <SheetPage.Header />
        <SheetPage.Empty loading />
      </SheetPage>
    )
  }
  if (!project) {
    return (
      <SheetPage>
        <SheetPage.Header />
        <SheetPage.Empty className="text-destructive">Project not found</SheetPage.Empty>
      </SheetPage>
    )
  }

  return (
    <SheetPage>
      <SheetPage.Header>
        <Breadcrumbs projectName={project.name} currentPage="Speed Masters" />
      </SheetPage.Header>
      <LibraryRow
        filter={filter}
        onFilterChange={setFilter}
        filterLabel="Filter by name, number or notes"
        create={
          <Button
            size="sm"
            className="shrink-0 gap-1.5"
            onClick={() => createMaster({ projectId: projectIdNum })}
            disabled={isCreating}
          >
            {isCreating ? <Loader2 className="size-3.5 animate-spin" /> : <Plus className="size-3.5" />}
            <span className="hidden sm:inline">New master</span>
          </Button>
        }
      />
      {isLoading ? (
        <SheetPage.Empty loading />
      ) : shown.length === 0 ? (
        <SheetPage.Empty>No masters match your filter.</SheetPage.Empty>
      ) : (
        <SpeedMasterSheet
          projectId={projectIdNum}
          masters={shown}
          bank={bank}
          isCurrentProject={project.isCurrent}
          onOpenMaster={(master) => setOpenMasterId(master.id)}
        />
      )}
      <SheetPage.Footer>
        <span className="tabular-nums">
          {bank.length} master{bank.length === 1 ? '' : 's'}
          {followerCount > 0 ? ` · ${followerCount} follow` : ''}
        </span>
        <span className="ml-auto">
          {project.isCurrent
            ? 'M1 is the global tempo · BPM is live, Start is stored'
            : 'Not the running project · BPM and TAP are read-only here'}
        </span>
      </SheetPage.Footer>

      <SpeedMasterDetailSheet
        open={openMaster != null}
        onOpenChange={(next) => !next && setOpenMasterId(null)}
        projectId={projectIdNum}
        master={openMaster}
      />
    </SheetPage>
  )
}

/** The library row's filter: a master's name, its `M<n>`, or its notes. */
export function filterMasters(masters: readonly SpeedMaster[], filter: string): SpeedMaster[] {
  const needle = filter.trim().toLowerCase()
  if (needle === '') return [...masters]
  return masters.filter(
    (m) =>
      m.name.toLowerCase().includes(needle) ||
      `m${m.masterIndex}` === needle ||
      (m.notes ?? '').toLowerCase().includes(needle),
  )
}
