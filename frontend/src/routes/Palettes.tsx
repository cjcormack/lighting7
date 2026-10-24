import { useCallback, useEffect, useMemo, useState } from 'react'
import { useParams, useSearchParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Plus } from 'lucide-react'
import { useCurrentProjectQuery, useProjectQuery } from '../store/projects'
import { useEditParam } from '../hooks/useEditParam'
import { useCreatePaletteMutation, useSavePaletteMutation, usePaletteListQuery } from '../store/palettes'
import { useSpeedMasterListQuery } from '../store/speedMasters'
import { useFxLibraryQuery } from '../store/fixtureFx'
import { PaletteEditor } from '../components/templates/PaletteEditor'
import { PaletteSheet, paletteRowId, type PaletteSheetRow } from '../components/templates/PaletteSheet'
import { usePaletteDelete } from '../components/templates/usePaletteDelete'
import { getStoredLookFamily, setStoredLookFamily, type LookFamilyFilter } from '../components/ViewSwitcher'
import { LibraryRow, PartitionChips } from '../components/sheet/LibraryRow'
import { SheetPage } from '../components/sheet/SheetPage'
import { groupRows } from '../components/sheet/groupRows'
import type { PaletteInput, PaletteSummary } from '../api/palettesApi'
import {
  FAMILY_LABELS,
  PALETTE_FAMILY_ORDER,
  familySlug,
  parseFamilySlug,
  type AttributeFamily,
} from '../lib/attributeFamily'
import { Breadcrumbs } from '../components/Breadcrumbs'
import { CurrentProjectRedirect } from '../components/CurrentProjectRedirect'

/** Redirect `/palettes` → `/projects/:projectId/palettes`. */
export function PalettesRedirect() {
  return <CurrentProjectRedirect to="palettes" preserveSearch />
}

/**
 * The template library as a **sheet** (library-sheets plan §3.2, session 2) — named values and
 * effects you build looks and cues out of, one family each. The list shell's header, the library row
 * (filter · family chips · *New template*), the selection bar, `PaletteSheet` and the footer. It
 * replaced a `Card` of `PaletteListRow`s under `LookFamilyFilterBar`; `PaletteEditor` stays, as
 * what a row's pencil — or ⏎ over one row — opens (D1).
 *
 * **Chips filter, dividers group** (D3). A family is an exact partition here (a template is in exactly
 * one), so the chips narrow the sheet to one family and, under *All*, the rows are grouped by family
 * divider in `PALETTE_FAMILY_ORDER`. The partition is a **view, never a route**: `?family=` deep-links
 * from Cmd+K and the choice is remembered under `looks.family` — the route's, so `ApplyPicker`
 * mounts the same `PartitionChips` with state of its own.
 *
 * **Still no stored order.** Within a family, and in a filtered view, the rows are the server's name
 * order. A template holds no position: the only order that means anything is a pad's place in a busk
 * bank, which the busk page owns.
 */
export function ProjectPalettes() {
  const { projectId } = useParams()
  const projectIdNum = Number(projectId)
  const { data: currentProject, isLoading: currentLoading } = useCurrentProjectQuery()
  const { data: project, isLoading: projectLoading } = useProjectQuery(projectIdNum)
  // Unfiltered: the filter runs in the browser so switching families is instant and *All* needs
  // them all anyway.
  const { data: templates, isLoading: palettesLoading } = usePaletteListQuery({ projectId: projectIdNum })
  // The Master column's options — this project's bank, as stored; a template names masters by uuid.
  const { data: masters } = useSpeedMasterListQuery({ projectId: projectIdNum })
  const { data: fxLibrary } = useFxLibraryQuery()
  const [createPalette, { isLoading: isCreating }] = useCreatePaletteMutation()
  const [savePalette, { isLoading: isSaving }] = useSavePaletteMutation()

  const [searchParams, setSearchParams] = useSearchParams()
  const [family, setFamily] = useState<LookFamilyFilter>(() => getStoredLookFamily())
  const [filter, setFilter] = useState('')
  const [editorOpen, setEditorOpen] = useState(false)
  /**
   * The template being edited, held **by id** and re-read from the list, so after a save the editor
   * does not measure `dirty` against the pre-save value.
   */
  const [editingId, setEditingId] = useState<number | null>(null)

  const isCurrentProject = currentProject?.id === projectIdNum

  const changeFamily = useCallback(
    (next: LookFamilyFilter) => {
      setFamily(next)
      setStoredLookFamily(next)
      // Keep the URL honest so a reload or a shared link lands in the same bank.
      const params = new URLSearchParams(searchParams)
      if (next === 'ALL') params.delete('family')
      else params.set('family', familySlug(next))
      setSearchParams(params, { replace: true })
    },
    [searchParams, setSearchParams],
  )

  // Deep links, from Cmd+K or a bookmark.
  useEffect(() => {
    const slug = searchParams.get('family')
    if (slug == null) return
    const parsed = slug.toLowerCase() === 'all' ? 'ALL' : parseFamilySlug(slug.toLowerCase())
    if (parsed == null) return
    setFamily(parsed)
    setStoredLookFamily(parsed)
  }, [searchParams])

  // `?action=new` opens the create editor and strips the param — the command-palette entry point.
  useEffect(() => {
    if (searchParams.get('action') === 'new' && isCurrentProject) {
      setEditingId(null)
      setEditorOpen(true)
      const params = new URLSearchParams(searchParams)
      params.delete('action')
      setSearchParams(params, { replace: true })
    }
  }, [searchParams, isCurrentProject, setSearchParams])

  // `?edit=<id>` opens that palette's editor — a chip's *Edit palette…* (the apply-model plan's hold
  // menu) and the cue editor's *Open* on a palette layer.
  useEditParam(templates, !currentLoading, isCurrentProject, (id) => {
    setEditingId(id)
    setEditorOpen(true)
  })

  const library = useMemo(() => templates ?? [], [templates])
  // Memoised like `library`: a fresh `[]` per render while the masters load would rebuild the
  // sheet's columns on every keystroke in the filter.
  const bank = useMemo(() => masters ?? [], [masters])
  const editing = useMemo(
    () => (editingId == null ? null : (library.find((t) => t.id === editingId) ?? null)),
    [library, editingId],
  )
  // Closed for real once the template is gone — a delete from another client.
  useEffect(() => {
    if (editingId != null && templates != null && editing == null) {
      setEditingId(null)
      setEditorOpen(false)
    }
  }, [editingId, templates, editing])

  const familyCounts = useMemo(() => {
    const counts = new Map<AttributeFamily, number>()
    for (const t of library) if (t.family != null) counts.set(t.family, (counts.get(t.family) ?? 0) + 1)
    return counts
  }, [library])

  /** The rows the sheet draws: the family and the text filter, then — under *All* — the dividers. */
  const sheetRows = useMemo<PaletteSheetRow[]>(() => {
    const needle = filter.trim().toLowerCase()
    const shown = library.filter(
      (t) =>
        (family === 'ALL' || t.family === family) &&
        (needle === '' || t.name.toLowerCase().includes(needle) || (t.notes ?? '').toLowerCase().includes(needle)),
    )
    const members: PaletteSheetRow[] = shown.map((t) => ({ id: paletteRowId(t.id), palette: t }))
    if (family !== 'ALL') return members
    const perFamily = new Map<string, number>()
    for (const t of shown) perFamily.set(t.family ?? '', (perFamily.get(t.family ?? '') ?? 0) + 1)
    return groupRows<PaletteSheetRow, AttributeFamily>(members, {
      partition: (row) => row.palette?.family ?? '',
      order: PALETTE_FAMILY_ORDER,
      divider: (key) => ({
        id: `family:${key || 'none'}`,
        divider: `${key in FAMILY_LABELS ? FAMILY_LABELS[key as AttributeFamily].singular : 'No family'} · ${perFamily.get(key) ?? 0}`,
      }),
      unlisted: '',
    })
  }, [family, filter, library])

  const { run: runDelete, busy: isDeleting, dialog: deleteDialog } = usePaletteDelete({
    projectId: projectIdNum,
    onDeleted: () => {
      setEditorOpen(false)
      setEditingId(null)
    },
  })

  const handleSave = async (input: PaletteInput) => {
    if (editingId != null) {
      await savePalette({ projectId: projectIdNum, paletteId: editingId, ...input }).unwrap()
    } else {
      await createPalette({ projectId: projectIdNum, ...input }).unwrap()
    }
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

  const memberCount = sheetRows.filter((row) => row.divider == null).length
  const openPalette = (template: PaletteSummary) => {
    setEditingId(template.id)
    setEditorOpen(true)
  }

  return (
    <SheetPage>
      <SheetPage.Header>
        <Breadcrumbs projectName={project.name} currentPage="Palettes" />
      </SheetPage.Header>
      <LibraryRow
        filter={filter}
        onFilterChange={setFilter}
        filterLabel="Filter by name or notes"
        chips={
          <PartitionChips<AttributeFamily>
            label="Family"
            value={family}
            onChange={changeFamily}
            allCount={library.length}
            options={PALETTE_FAMILY_ORDER.map((f) => ({
              value: f,
              label: FAMILY_LABELS[f].singular,
              count: familyCounts.get(f) ?? 0,
            }))}
          />
        }
        create={
          isCurrentProject ? (
            <Button
              size="sm"
              className="shrink-0 gap-1.5"
              onClick={() => {
                setEditingId(null)
                setEditorOpen(true)
              }}
            >
              <Plus className="size-3.5" />
              <span className="hidden sm:inline">New palette</span>
            </Button>
          ) : undefined
        }
      />
      {palettesLoading ? (
        <SheetPage.Empty loading />
      ) : library.length === 0 ? (
        <SheetPage.Empty>
          {isCurrentProject
            ? 'No palettes yet. Create one here, or record what you have selected from the programmer’s palette strip — or save a running FX as one.'
            : 'No palettes in this project.'}
        </SheetPage.Empty>
      ) : memberCount === 0 ? (
        <SheetPage.Empty>No palettes match.</SheetPage.Empty>
      ) : (
        <PaletteSheet
          projectId={projectIdNum}
          rows={sheetRows}
          library={library}
          masters={bank}
          fxLibrary={fxLibrary}
          isCurrentProject={isCurrentProject}
          projectName={project.name}
          onOpenPalette={openPalette}
        />
      )}
      <SheetPage.Footer>
        <span className="tabular-nums">
          {library.length} palette{library.length === 1 ? '' : 's'}
          {family === 'ALL' && filter.trim() === '' ? ' · showing all' : ` · showing ${memberCount}`}
        </span>
        <span className="ml-auto">
          {isCurrentProject
            ? 'Value edits the intent — the desk resolves it per head'
            : 'Not the running project · copy a palette here to use it'}
        </span>
      </SheetPage.Footer>

      <PaletteEditor
        open={editorOpen}
        onOpenChange={(next) => {
          setEditorOpen(next)
          if (!next) setEditingId(null)
        }}
        projectId={projectIdNum}
        palette={editingId == null ? null : editing}
        onSave={handleSave}
        isSaving={isCreating || isSaving}
        onDelete={editing == null ? undefined : () => void runDelete([editing])}
        isDeleting={isDeleting}
      />
      {deleteDialog}
    </SheetPage>
  )
}
