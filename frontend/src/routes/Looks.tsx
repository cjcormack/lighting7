import { useEffect, useMemo, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { ArrowRight, Circle } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useCurrentProjectQuery, useProjectQuery } from '../store/projects'
import { useCopyLookMutation, useLookListQuery } from '../store/looks'
import { LookDetailSheet } from '../components/looks/LookDetailSheet'
import { LookSheet } from '../components/looks/LookSheet'
import { RecordLookSheet } from '../components/programmer/RecordLookSheet'
import { LibraryRow } from '../components/sheet/LibraryRow'
import { SheetPage } from '../components/sheet/SheetPage'
import { duplicateName } from '../lib/duplicateName'
import { formatError } from '../lib/formatError'
import type { LookSummary } from '../api/looksApi'
import { useProgrammerSummaryQuery } from '../store/programmer'
import { Breadcrumbs } from '../components/Breadcrumbs'
import { CurrentProjectRedirect } from '../components/CurrentProjectRedirect'
import { toast } from 'sonner'

/** Redirect `/looks` → `/projects/:projectId/looks`. */
export function LooksRedirect() {
  return <CurrentProjectRedirect to="looks" />
}

/**
 * The Look library as a **sheet** (library-sheets plan §3.2, session 2): the list shell's header,
 * the library row (filter · *Record from programmer*), the selection bar, `LookSheet` and the footer.
 * It replaced a bordered list of `LookListRow`s with hover `…` menus; `LookDetailSheet` stays, as
 * what a row's pencil — or ⏎ over one row — opens (D1).
 *
 * **One kind of thing, and no New button.** A value you point at a selection is a *template*, with
 * its own page; what is left here is uniformly **recorded** — from the programmer, or by promoting a
 * selection with Make layer — which is why the library row's create verb is *Record from
 * programmer* (D9).
 *
 * **No family chips** (D3). A Look's families are *derived* and one may span several, so filtering by
 * a family would hide most of the library from most filters; a template is in exactly one family,
 * which is the case chips partition cleanly — on `/templates`.
 */
export function ProjectLooks() {
  const { projectId } = useParams()
  const projectIdNum = Number(projectId)
  const { data: currentProject, isLoading: currentLoading } = useCurrentProjectQuery()
  const { data: project, isLoading: projectLoading } = useProjectQuery(projectIdNum)
  const { data: looks, isLoading: looksLoading } = useLookListQuery({ projectId: projectIdNum })
  const [copyLook] = useCopyLookMutation()

  const [searchParams, setSearchParams] = useSearchParams()
  const [filter, setFilter] = useState('')
  /**
   * The Look the detail sheet is open on, held **by id** and re-read from the list — a held summary
   * would freeze at the moment the row was opened, and after a save the sheet would still measure
   * `dirty` against the pre-save name.
   */
  const [detailLookId, setDetailLookId] = useState<number | null>(null)
  /** Record-from-programmer — the only way a Look is made. */
  const [recordOpen, setRecordOpen] = useState(false)

  const isCurrentProject = currentProject?.id === projectIdNum

  // Gates Record. Recording an empty programmer would make a Look with no rows, which reads as a
  // broken save rather than as "there was nothing to record".
  const { data: programmerSummary } = useProgrammerSummaryQuery()
  const programmerEntryCount = programmerSummary?.entryCount ?? 0

  // `?action=record` opens the record sheet and strips the param — the command palette's way in.
  useEffect(() => {
    if (searchParams.get('action') === 'record' && isCurrentProject) {
      setRecordOpen(true)
      const params = new URLSearchParams(searchParams)
      params.delete('action')
      setSearchParams(params, { replace: true })
    }
  }, [searchParams, isCurrentProject, setSearchParams])

  const library = useMemo(() => looks ?? [], [looks])
  const shown = useMemo(() => filterLooks(library, filter), [library, filter])

  // Re-read rather than remembered — see `detailLookId`. Null once the Look is gone.
  const detailLook = useMemo(
    () => (detailLookId == null ? null : (library.find((l) => l.id === detailLookId) ?? null)),
    [library, detailLookId],
  )
  // A Look that leaves the list under an open sheet (deleted from another client) closes it for
  // real, rather than leaving Radix mounted-but-blank with focus never handed back.
  useEffect(() => {
    if (detailLookId != null && looks != null && detailLook == null) setDetailLookId(null)
  }, [detailLookId, looks, detailLook])

  /** The detail sheet's Duplicate — one Look, the sheet's own `(Copy n)` rule. */
  const duplicateOne = (look: LookSummary) => {
    const newName = duplicateName(look.name, new Set(library.map((l) => l.name)))
    copyLook({ projectId: projectIdNum, lookId: look.id, targetProjectId: projectIdNum, newName })
      .unwrap()
      // `copyLook` is in `SILENT_ENDPOINTS` — the sheets report their own copies (D14).
      .catch((err) => toast.error(formatError(err), { id: 'sheet-write:looks:duplicate' }))
  }

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

  const layerTotal = library.reduce((n, look) => n + look.layerCount, 0)

  return (
    <SheetPage>
      <SheetPage.Header>
        <Breadcrumbs projectName={project.name} currentPage="Looks" />
      </SheetPage.Header>
      <LibraryRow
        filter={filter}
        onFilterChange={setFilter}
        filterLabel="Filter by name or notes"
        chips={
          // The pointer across, which an operator looking for "Amber Key" needs more than most:
          // it used to live here, and a single value is a template now.
          <div className="flex min-w-0 flex-1 justify-end">
            <Link
              to={`/projects/${projectIdNum}/templates`}
              className="hidden items-center gap-1 whitespace-nowrap text-xs text-muted-foreground hover:text-foreground sm:inline-flex"
              title="A single value or effect — Amber Key, Slow Breathe — is a template, applied to a selection"
            >
              Templates
              <ArrowRight className="size-3" />
            </Link>
          </div>
        }
        create={
          isCurrentProject ? (
            <Button
              size="sm"
              className="shrink-0 gap-1.5"
              disabled={programmerEntryCount === 0}
              title={
                programmerEntryCount === 0 ? 'The programmer is empty — busk a state first' : 'Write the programmer into a look'
              }
              onClick={() => setRecordOpen(true)}
            >
              <Circle className="size-3.5" />
              <span className="hidden sm:inline">Record from programmer</span>
            </Button>
          ) : undefined
        }
      />
      {looksLoading ? (
        <SheetPage.Empty loading />
      ) : library.length === 0 ? (
        <SheetPage.Empty>
          {isCurrentProject
            ? 'No looks yet. Busk a state in the programmer, then record it — there is no hand-authored look.'
            : 'No looks in this project.'}
        </SheetPage.Empty>
      ) : shown.length === 0 ? (
        <SheetPage.Empty>No looks match your filter.</SheetPage.Empty>
      ) : (
        <LookSheet
          projectId={projectIdNum}
          looks={shown}
          library={library}
          isCurrentProject={isCurrentProject}
          projectName={project.name}
          onOpenLook={(look) => setDetailLookId(look.id)}
        />
      )}
      <SheetPage.Footer>
        <span className="tabular-nums">
          {library.length} look{library.length === 1 ? '' : 's'}
          {layerTotal > 0 ? ` · ${layerTotal} cue layer${layerTotal === 1 ? '' : 's'}` : ''}
        </span>
        <span className="ml-auto">
          {isCurrentProject
            ? 'Values are recorded — Include a look to change what it holds'
            : `Not the running project · copy a look here to use it`}
        </span>
      </SheetPage.Footer>

      <LookDetailSheet
        open={detailLookId != null}
        onOpenChange={(next) => {
          if (!next) setDetailLookId(null)
        }}
        projectId={projectIdNum}
        look={detailLook}
        onDuplicate={(look) => {
          setDetailLookId(null)
          duplicateOne(look)
        }}
      />
      <RecordLookSheet open={recordOpen} onOpenChange={setRecordOpen} projectId={projectIdNum} />
    </SheetPage>
  )
}

/** The library row's filter: a Look's name or its notes. */
export function filterLooks(looks: readonly LookSummary[], filter: string): LookSummary[] {
  const needle = filter.trim().toLowerCase()
  if (needle === '') return [...looks]
  return looks.filter((l) => l.name.toLowerCase().includes(needle) || (l.notes ?? '').toLowerCase().includes(needle))
}
