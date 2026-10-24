import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams, useSearchParams } from 'react-router'
import { Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent } from '@/components/ui/sheet'
import { useCurrentProjectQuery, useProjectQuery } from '../store/projects'
import { useFxLibraryQuery, type FxLibraryEntry } from '../store/fixtureFx'
import { useCreateFxDefinitionMutation, useFxDefinitionListQuery } from '../store/fxDefinitions'
import { Breadcrumbs } from '@/components/Breadcrumbs'
import { CurrentProjectRedirect } from '../components/CurrentProjectRedirect'
import { LibraryRow, PartitionChips } from '../components/sheet/LibraryRow'
import { SheetPage } from '../components/sheet/SheetPage'
import { groupRows } from '../components/sheet/groupRows'
import { usePartitionView } from '../components/sheet/usePartitionView'
import { FxLibrarySheet, fxRowId, type FxMemberRow, type FxSheetRow } from '../components/fxLibrary/FxLibrarySheet'
import { FxDetailSheet } from '../components/fxLibrary/FxDetailSheet'
import { EditFxDefinitionSheet } from '../components/fxLibrary/EditFxDefinitionSheet'
import { NewFxDefinitionSheet } from '../components/fxLibrary/NewFxDefinitionSheet'
import { useFxDefinitionDelete } from '../components/fxLibrary/useFxDefinitionDelete'
import {
  FX_FAMILY_LABELS,
  FX_FAMILY_ORDER,
  definitionsByFxId,
  displayName,
  entryName,
  forkRefusal,
  forkRequest,
  fxSourceOf,
  isFxCategory,
  takenFxIds,
  type FxFamily,
  type FxSource,
} from '../components/fxLibrary/fxLibraryModel'

export function FxLibraryRedirect() {
  return <CurrentProjectRedirect to="fx-library" />
}

/** What the right-hand sheet is showing. */
type SheetMode =
  | { type: 'closed' }
  | { type: 'view'; entry: FxLibraryEntry; source: FxSource }
  | { type: 'edit'; definitionId: number; forkedFrom?: string }
  | { type: 'new' }

/** The URL and the remembered value spell a category as the registry does — `colour`. */
function parseCategory(raw: string): FxFamily | null {
  const lower = raw.toLowerCase()
  return isFxCategory(lower) ? lower : null
}

function categorySlug(category: FxFamily): string {
  return category
}

/**
 * The FX Library as a **sheet** (library-sheets plan §3.2, session 4): the list shell's header, the
 * library row (filter · category chips · *New effect*), the selection bar, `FxLibrarySheet` and the
 * footer. It replaced a shadcn `Table` whose categories collapsed; the three sheets it opens —
 * detail, edit, new — moved to `components/fxLibrary/` beside it (D1).
 *
 * **Chips filter, dividers group** (D3): *All* and the categories (six since Palette Steps added
 * *Palette*, effects plan session 7), `?category=` deep-links and
 * the choice is remembered under `fxLibrary.category`; under *All* the rows are grouped by category
 * divider in `FX_FAMILY_ORDER`, and the categories no longer collapse — the chips are how you
 * narrow.
 *
 * **The library is the running show's, whatever the URL's project.** `GET fx/library` and
 * `GET fx/definitions` have no project in them; that is why another project's page is the read-only
 * scope with nothing live (D12) rather than a different library, and why every row there opens the
 * read-only detail rather than an editor for the running show's record.
 */
export function ProjectFxLibrary() {
  const { projectId } = useParams()
  const projectIdNum = Number(projectId)
  const navigate = useNavigate()
  const { data: currentProject, isLoading: currentLoading } = useCurrentProjectQuery()
  const { data: project, isLoading: projectLoading } = useProjectQuery(projectIdNum)
  const { data: libraryData, isLoading: libraryLoading } = useFxLibraryQuery()
  const { data: definitionData, isLoading: definitionsLoading } = useFxDefinitionListQuery()
  const [createDefinition, { isLoading: forking }] = useCreateFxDefinitionMutation()

  const [searchParams, setSearchParams] = useSearchParams()
  const [category, changeCategory] = usePartitionView<FxFamily>({
    param: 'category',
    storageKey: 'fxLibrary.category',
    parse: parseCategory,
    slug: categorySlug,
  })
  const [filter, setFilter] = useState('')
  const [sheetMode, setSheetMode] = useState<SheetMode>({ type: 'closed' })

  const isCurrentProject = currentProject?.id === projectIdNum
  const library = useMemo(() => libraryData ?? [], [libraryData])
  const definitions = useMemo(() => definitionData ?? [], [definitionData])
  const byFxId = useMemo(() => definitionsByFxId(definitions), [definitions])

  // `?action=new` opens the create sheet and strips the param — the command-palette entry point.
  useEffect(() => {
    if (searchParams.get('action') === 'new' && isCurrentProject) {
      setSheetMode({ type: 'new' })
      const params = new URLSearchParams(searchParams)
      params.delete('action')
      setSearchParams(params, { replace: true })
    }
  }, [searchParams, isCurrentProject, setSearchParams])

  /** Every member row, before the filters — the source and the name worked out once. */
  const members = useMemo<FxMemberRow[]>(
    () =>
      library.map((entry) => {
        const definition = byFxId.get(entry.name)
        return {
          id: fxRowId(entry.name),
          entry,
          source: fxSourceOf(entry, byFxId),
          definition,
          name: entryName(entry, definition),
        }
      }),
    [byFxId, library],
  )

  const categoryCounts = useMemo(() => {
    const counts = new Map<string, number>()
    for (const row of members) counts.set(row.entry.family, (counts.get(row.entry.family) ?? 0) + 1)
    return counts
  }, [members])

  /** The rows the sheet draws: the category and the text filter, then — under *All* — the dividers. */
  const sheetRows = useMemo<FxSheetRow[]>(() => {
    const needle = filter.trim().toLowerCase()
    const shown = members
      .filter(
        (row) =>
          (category === 'ALL' || row.entry.family === category) &&
          (needle === '' ||
            row.name.toLowerCase().includes(needle) ||
            row.entry.name.toLowerCase().includes(needle) ||
            row.entry.compatibleAttributes.some((p) => p.toLowerCase().includes(needle))),
      )
      .sort((a, b) => a.name.localeCompare(b.name))
    if (category !== 'ALL') return shown
    const perCategory = new Map<string, number>()
    for (const row of shown) perCategory.set(row.entry.family, (perCategory.get(row.entry.family) ?? 0) + 1)
    return groupRows<FxSheetRow, FxFamily>(shown, {
      partition: (row) => row.entry?.family ?? '',
      order: FX_FAMILY_ORDER,
      divider: (key) => ({
        id: `category:${key || 'other'}`,
        divider: `${isFxCategory(key) ? FX_FAMILY_LABELS[key] : 'Other'} · ${perCategory.get(key) ?? 0}`,
      }),
      unlisted: 'other',
    })
  }, [category, filter, members])

  const openRow = useCallback(
    (row: FxMemberRow) => {
      if (!isCurrentProject) {
        setSheetMode({ type: 'view', entry: row.entry, source: row.source })
        return
      }
      if (row.source === 'custom' && row.definition) {
        setSheetMode({ type: 'edit', definitionId: row.definition.id })
      } else if (row.source === 'script' && row.entry.sourceDefinitionId != null) {
        // §1's first bug: a script's effect carries the **script's** id, so it opens the script.
        navigate(`/projects/${projectIdNum}/scripts/${row.entry.sourceDefinitionId}`)
      } else {
        setSheetMode({ type: 'view', entry: row.entry, source: row.source })
      }
    },
    [isCurrentProject, navigate, projectIdNum],
  )

  const fork = useCallback(
    async (entry: FxLibraryEntry) => {
      if (!isCurrentProject || forking) return
      try {
        const created = await createDefinition(forkRequest(entry, library, definitions)).unwrap()
        setSheetMode({ type: 'edit', definitionId: created.id, forkedFrom: displayName(entry.name) })
      } catch {
        // Reported by errorToastMiddleware — a 422 names the compile diagnostics.
      }
    },
    [createDefinition, definitions, forking, isCurrentProject, library],
  )

  const { run: runDelete, busy: deleting } = useFxDefinitionDelete({
    onDeleted: () => setSheetMode({ type: 'closed' }),
  })

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

  const memberCount = sheetRows.filter((row) => row.divider == null).length
  const yours = members.filter((row) => row.source !== 'builtIn').length

  return (
    <SheetPage>
      <SheetPage.Header>
        <Breadcrumbs projectName={project.name} currentPage="FX" />
      </SheetPage.Header>
      <LibraryRow
        filter={filter}
        onFilterChange={setFilter}
        filterLabel="Filter by name or what it drives"
        chips={
          <PartitionChips<FxFamily>
            label="Category"
            fold={640}
            value={category}
            onChange={changeCategory}
            allCount={library.length}
            options={FX_FAMILY_ORDER.map((c) => ({
              value: c,
              label: FX_FAMILY_LABELS[c],
              count: categoryCounts.get(c) ?? 0,
            }))}
          />
        }
        create={
          isCurrentProject ? (
            <Button size="sm" className="shrink-0 gap-1.5" onClick={() => setSheetMode({ type: 'new' })}>
              <Plus className="size-3.5" />
              <span className="hidden sm:inline">New FX</span>
            </Button>
          ) : undefined
        }
      />
      {libraryLoading || definitionsLoading ? (
        <SheetPage.Empty loading />
      ) : library.length === 0 ? (
        <SheetPage.Empty>No FX available.</SheetPage.Empty>
      ) : memberCount === 0 ? (
        <SheetPage.Empty>No FX match.</SheetPage.Empty>
      ) : (
        <FxLibrarySheet
          rows={sheetRows}
          isCurrentProject={isCurrentProject}
          projectName={project.name}
          onOpen={openRow}
          onFork={(entry) => void fork(entry)}
          forking={forking}
        />
      )}
      <SheetPage.Footer>
        <span className="tabular-nums">
          {library.length} FX · {yours} yours
          {category === 'ALL' && filter.trim() === '' ? '' : ` · showing ${memberCount}`}
        </span>
        <span className="ml-auto">
          {isCurrentProject
            ? 'Built-ins are read-only — Fork one to change it'
            : 'Not the running project · the running show’s FX, read-only'}
        </span>
      </SheetPage.Footer>

      <Sheet
        open={sheetMode.type !== 'closed'}
        onOpenChange={(open) => {
          if (!open) setSheetMode({ type: 'closed' })
        }}
      >
        {sheetMode.type === 'view' && (
          <SheetContent side="right" className="flex flex-col sm:max-w-lg">
            <FxDetailSheet
              effect={sheetMode.entry}
              source={sheetMode.source}
              onFork={
                isCurrentProject &&
                sheetMode.source === 'builtIn' &&
                sheetMode.entry.script &&
                forkRefusal(sheetMode.entry) == null
                  ? () => void fork(sheetMode.entry)
                  : undefined
              }
              forkRefusal={isCurrentProject && sheetMode.source === 'builtIn' ? forkRefusal(sheetMode.entry) : null}
              forking={forking}
            />
          </SheetContent>
        )}
        {sheetMode.type === 'edit' && (
          <SheetContent side="right" className="flex flex-col sm:max-w-lg">
            <EditFxDefinitionSheet
              definitionId={sheetMode.definitionId}
              forkedFrom={sheetMode.forkedFrom}
              isDeleting={deleting}
              onDelete={(definition) =>
                void runDelete([{ name: definition.name, source: 'custom', definition }])
              }
            />
          </SheetContent>
        )}
        {sheetMode.type === 'new' && (
          <SheetContent side="right" className="flex flex-col sm:max-w-lg">
            <NewFxDefinitionSheet
              takenIds={takenFxIds(library, definitions)}
              onCreated={(id) => setSheetMode({ type: 'edit', definitionId: id })}
            />
          </SheetContent>
        )}
      </Sheet>
    </SheetPage>
  )
}
