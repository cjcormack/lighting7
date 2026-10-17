import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import { X } from 'lucide-react'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { useCurrentProjectQuery, useProjectQuery } from '../store/projects'
import { useProjectCueListQuery } from '../store/cues'
import { useProjectCueStackListQuery, useProjectProgramStateQuery } from '../store/cueStacks'
import { useEditLock } from '../hooks/useEditLock'
import { Breadcrumbs } from '../components/Breadcrumbs'
import { CurrentProjectRedirect } from '../components/CurrentProjectRedirect'
import { CueLibrarySheet } from '../components/cues/CueLibrarySheet'
import { ShowLockControl } from '../components/runner/ShowLockControl'
import { LibraryRow, PartitionChips } from '../components/sheet/LibraryRow'
import { SheetPage } from '../components/sheet/SheetPage'
import { CueEditorSheet } from '../components/stage/OwnerEditor'
import {
  CUES_SHOWS,
  CUES_SHOW_LABELS,
  cueOpenPath,
  cuesSearch,
  cuesShown,
  cuesViewFromSearch,
  filterBeforeShow,
  isLoose,
  showCounts,
  type CuesShow,
  type CuesView,
} from '../lib/cuesTable'
import type { Cue } from '../api/cuesApi'

/** `/cues` → the current project's Cues table. */
export function CuesRedirect() {
  return <CurrentProjectRedirect to="cues" />
}

const LOCKED_REASON = 'Locked — unlock the show to edit'
const ALL_STACKS = 'all'

/**
 * **The Cues table** (lighting7 cue model plan §3.7, D13): `/projects/:id/cues`, every cue of the
 * project once — a Library view beside Looks and Palettes, and where a cue in no stack is found.
 *
 * - **The view is the address** (`lib/cuesTable.ts`): the filter, a stack, the *Loose · Shared · On
 *   a pad* chips, one cue (`?cue=`, a loose cue's busk pad lands here) and the sort, each mirrored
 *   with `replace`.
 * - **A row opens the cue in its first entry's stack** on the Show view; a loose cue opens
 *   `CueEditor` in a right-hand sheet here, saying it has no number or follow.
 * - **The show-editing lock holds while the show runs** (§10, 20), as on the cue sheet: the lock
 *   control is in the header, locked the cells are inert and the actions ask to unlock. Another
 *   project's cues read only, with no actions.
 */
export function ProjectCues() {
  const { projectId } = useParams()
  const projectIdNum = Number(projectId)
  const navigate = useNavigate()
  const { data: currentProject, isLoading: currentLoading } = useCurrentProjectQuery()
  const { data: project, isLoading: projectLoading } = useProjectQuery(projectIdNum)
  const { data: cues, isLoading: cuesLoading } = useProjectCueListQuery(projectIdNum)
  const { data: stacks } = useProjectCueStackListQuery(projectIdNum)
  const isCurrentProject = currentProject?.id === projectIdNum
  const { data: programState } = useProjectProgramStateQuery(projectIdNum, { skip: !isCurrentProject })
  const isShowActive = isCurrentProject && programState?.activeStackId != null
  const editLock = useEditLock({ canEdit: isCurrentProject, isShowActive })

  const [searchParams, setSearchParams] = useSearchParams()
  const view = useMemo(() => cuesViewFromSearch(searchParams), [searchParams])
  const setView = useCallback(
    (next: CuesView) => setSearchParams(new URLSearchParams(cuesSearch(next)), { replace: true }),
    [setSearchParams],
  )

  const library = useMemo(() => cues ?? [], [cues])
  const narrowed = useMemo(() => filterBeforeShow(library, view), [library, view])
  const shown = useMemo(() => cuesShown(library, view), [library, view])
  const counts = useMemo(() => showCounts(narrowed), [narrowed])
  const runnable = useMemo(() => (stacks ?? []).filter((s) => s.type === 'STACK'), [stacks])
  const onlyCue = view.cue != null ? library.find((c) => c.id === view.cue) : undefined

  /** The loose cue whose editor is open in the sheet, re-read from the list by id. */
  const [sheetCueId, setSheetCueId] = useState<number | null>(null)
  useEffect(() => {
    if (sheetCueId != null && cues != null && !cues.some((c) => c.id === sheetCueId)) setSheetCueId(null)
  }, [cues, sheetCueId])

  const openCue = useCallback(
    (cue: Cue) => {
      if ((cue.entries?.length ?? 0) > 0) navigate(cueOpenPath(projectIdNum, cue))
      else setSheetCueId(cue.id)
    },
    [navigate, projectIdNum],
  )

  if (projectLoading || currentLoading) {
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

  const readOnlyReason = !isCurrentProject
    ? `${project.name}’s cues — make it the current project to edit`
    : editLock.locked
      ? LOCKED_REASON
      : null
  const onRequestUnlock = editLock.lockRelevant ? editLock.toggleLock : undefined
  const looseCount = library.filter(isLoose).length

  return (
    <SheetPage>
      <SheetPage.Header>
        <Breadcrumbs projectName={project.name} currentPage="Cues" />
        {editLock.lockRelevant && (
          <div className="ml-auto shrink-0">
            <ShowLockControl
              locked={editLock.locked}
              onToggle={editLock.toggleLock}
              countdownSecondsLeft={editLock.countdownSecondsLeft}
              onStayUnlocked={editLock.stayUnlocked}
            />
          </div>
        )}
      </SheetPage.Header>
      <LibraryRow
        filter={view.q}
        onFilterChange={(q) => setView({ ...view, q })}
        filterLabel="Filter by name, notes, stack or number"
        chips={
          <div className="flex min-w-0 flex-1 items-center gap-2">
            {view.cue != null && (
              <button
                type="button"
                data-cue-filter={view.cue}
                onClick={() => setView({ ...view, cue: null })}
                title="Show every cue"
                className="inline-flex h-7 shrink-0 items-center gap-1 rounded-md border bg-card px-2 text-xs font-semibold"
              >
                {onlyCue != null ? `Only ${onlyCue.name}` : 'A cue no longer here'}
                <X className="size-3" />
              </button>
            )}
            <Select
              value={view.stack != null ? String(view.stack) : ALL_STACKS}
              onValueChange={(next) => setView({ ...view, stack: next === ALL_STACKS ? null : Number(next) })}
            >
              <SelectTrigger className="h-8 w-40 shrink-0 text-xs" aria-label="Stack">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL_STACKS}>Every stack</SelectItem>
                {runnable.map((s) => (
                  <SelectItem key={s.id} value={String(s.id)}>
                    {s.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <PartitionChips<CuesShow>
              label="Show"
              options={CUES_SHOWS.map((show) => ({ value: show, label: CUES_SHOW_LABELS[show], count: counts[show] }))}
              value={view.show ?? 'ALL'}
              onChange={(next) => setView({ ...view, show: next === 'ALL' ? null : next })}
              allCount={narrowed.length}
            />
          </div>
        }
      />
      {/* Any press or key in the body counts as editing, so the idle re-lock does not fire mid-edit. */}
      <div className="flex min-h-0 flex-1 flex-col" onPointerDownCapture={editLock.noteEdit} onKeyDownCapture={editLock.noteEdit}>
        {cuesLoading ? (
          <SheetPage.Empty loading />
        ) : library.length === 0 ? (
          <SheetPage.Empty>No cues yet. Record the programmer into a cue — in a stack or in none.</SheetPage.Empty>
        ) : shown.length === 0 ? (
          <SheetPage.Empty>No cues match the filter.</SheetPage.Empty>
        ) : (
          <CueLibrarySheet
            projectId={projectIdNum}
            cues={shown}
            view={view}
            onViewChange={setView}
            readOnlyReason={readOnlyReason}
            otherProject={!isCurrentProject}
            locked={editLock.locked}
            onRequestUnlock={onRequestUnlock}
            onOpenCue={isCurrentProject ? openCue : undefined}
          />
        )}
      </div>
      <SheetPage.Footer>
        <span className="tabular-nums">
          {library.length} cue{library.length === 1 ? '' : 's'}
          {looseCount > 0 ? ` · ${looseCount} loose` : ''}
        </span>
        <span className="ml-auto">
          {isCurrentProject ? 'A cue is one record — every stack it is in plays the same cue' : 'Not the running project'}
        </span>
      </SheetPage.Footer>

      <CueEditorSheet
        projectId={projectIdNum}
        cueId={sheetCueId}
        onOpenChange={(open) => {
          if (!open) setSheetCueId(null)
        }}
        locked={editLock.locked}
        onRequestUnlock={onRequestUnlock}
      />
    </SheetPage>
  )
}
