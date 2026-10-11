import { useCallback, useEffect, useMemo, useState } from "react"
import { useParams, useSearchParams } from "react-router"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Columns3, Eye, EyeOff, Layers, Plus, Pencil, Check, Search } from "lucide-react"
import {
  useHiddenPatchListQuery,
  usePatchListQuery,
  useSetPatchHiddenMutation,
  useUniverseConfigListQuery,
  useUpdateUniverseConfigMutation,
  usePatchGroupListQuery,
} from "../store/patches"
import { ignoreReportedError } from "../store/errorToastMiddleware"
import { VenueChip } from "@/components/venue/VenueChip"
import type { ProjectVenue } from "../api/projectApi"
import { useRiggingListQuery } from "../store/riggings"
import { resolveFixtureKind, useFixtureTypeListQuery, type FixtureTypeInfo } from "../store/fixtures"
import { useLanternIndex } from "../hooks/useLanternIndex"
import { EMPTY_LANTERNS, effectiveLantern, lanternListLabel, type LanternIndex } from "../lib/lanterns"
import { usePersistentState } from "../hooks/usePersistentState"
import { AddFixtureSheet } from "@/components/patches/AddFixtureSheet"
import { EditPatchSheet } from "@/components/patches/EditPatchSheet"
import { EditGroupSheet } from "@/components/patches/EditGroupSheet"
import {
  DraftMarker,
  PATCH_COLUMN_LABELS,
  PATCH_COLUMN_ORDER,
  PatchSheet,
  patchRowId,
  type PatchColumnKey,
  type PatchSheetRow,
} from "@/components/patches/PatchSheet"
import { formatPatchAddress, universeFill } from "@/lib/patchAddress"
import { useVenueDiffQuery, useVenueHistory, useVenueMode } from "../store/venues"
import type { VenueChange } from "../api/venueApi"
import { isVenueOwned, type FixturePatch, type UniverseConfig } from "../api/patchApi"
import {
  DEFAULT_REFRESH_INTERVAL_MS,
  MAX_REFRESH_INTERVAL_MS,
  MIN_REFRESH_INTERVAL_MS,
} from "../api/patchApi"
import { parseNullableNumber } from "@/lib/utils"
import { CurrentProjectRedirect } from "@/components/CurrentProjectRedirect"
import { Breadcrumbs } from "@/components/Breadcrumbs"
import { SheetPage } from "@/components/sheet/SheetPage"
import { useProjectQuery } from "../store/projects"

// ─── Redirect ─────────────────────────────────────────────────────────

/** The bare `/patches` → the current project's patch list: part of the resource, so it lives here. */
export function PatchesRedirect() {
  return <CurrentProjectRedirect to="patches" preserveSearch />
}

// ─── Route ────────────────────────────────────────────────────────────

/**
 * `/projects/:projectId/patches` — the patch list as a routed page again (list-shell-design,
 * called 2026-09-15). It was the Patch List tab of Project Settings from the tabbed-settings
 * restructure until the list shell: a tab body under a settings heading was the one list that
 * could not have the 48px header row and the 12px gutter every other list has. The settings page
 * keeps General · Surfaces · Stage · Rigging · Sync, and `/settings/patches` redirects here.
 */
export function ProjectPatches() {
  const { projectId } = useParams()
  const projectIdNum = Number(projectId)
  const { data: project, isLoading: projectLoading } = useProjectQuery(projectIdNum)

  // No "not the current project → redirect" guard, unlike `/fixtures/list`: a patch is stored per
  // project and every query below takes the id, so an *inactive* project's patch list renders here
  // exactly as its settings tab did — which is what keeps the nav entry's `visibility: "always"`
  // honest. Only the bare `/patches` resolves "which project?" (`PatchesRedirect`).

  // Loading and not-found keep the list's shape (CLAUDE.md §List shell): the same header row,
  // the body centred on the spinner or the sentence.
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

  return <PatchListContent projectId={projectIdNum} projectName={project.name} venue={project.venue ?? null} />
}

// ─── Content ──────────────────────────────────────────────────────────

/**
 * A chip on the chips row: 28px, the tier of a control inside a control (CLAUDE.md §The
 * programmer's scoped grid — 32 on a row, 28 inside a control, 24 for a toggle item, 20 a pill).
 * `shrink-0`, since the row scrolls sideways rather than wrapping.
 */
const CHIP_CLASS =
  'inline-flex h-7 shrink-0 items-center gap-1.5 rounded-md border px-2.5 text-xs transition-colors hover:bg-accent'

const DEFAULT_COLUMNS: Record<PatchColumnKey, boolean> = {
  head: true,
  address: true,
  type: true,
  mode: true,
  ch: true,
  key: true,
  mount: true,
  lantern: true,
  angle: true,
  gel: true,
  groups: true,
  stage: true,
  role: true,
  // Never in the Columns menu: the route appends it in venue mode only.
  draft: true,
  // Nor this: the route leads with it in a show on a venue only (venue plan session 3).
  source: true,
  // Nor this: the route closes the row with it in a show on a venue only (session 5).
  facets: true,
}

/**
 * The patch list as a sheet (CLAUDE.md §Sheet kit) on the list shell (§List shell): the header row
 * with the breadcrumbs, the universe and group chips as a 40px chrome row of their own, row B — a
 * universe toggle, the filter, then Groups, Columns and `+ Patch` — the selection bar, the sheet,
 * and the footer. The universe chips carry a fill bar, and the group chips fold behind the Groups
 * button.
 */
function PatchListContent({
  projectId,
  projectName,
  venue,
}: {
  projectId: number
  projectName: string
  /** The venue this show sits on (venue plan session 3), or null for a standalone show and a venue. */
  venue: ProjectVenue | null
}) {
  const [searchParams, setSearchParams] = useSearchParams()
  const [addFixtureOpen, setAddFixtureOpen] = useState(false)
  const [editingPatchId, setEditingPatchId] = useState<number | null>(null)
  const [editingGroup, setEditingGroup] = useState<{ id: number; name: string } | null>(null)
  const [universeFilter, setUniverseFilter] = useState<'all' | number>('all')
  const [filter, setFilter] = useState('')
  const [showGroups, setShowGroups] = usePersistentState('patches.showGroups', true)
  const [columnVisibility, setColumnVisibility] = usePersistentState<Record<PatchColumnKey, boolean>>(
    'patches.columns',
    DEFAULT_COLUMNS,
    { merge: true },
  )
  const [selectedCount, setSelectedCount] = useState(0)

  // Open add-fixture sheet when navigated with ?action=new (e.g. from command palette)
  useEffect(() => {
    if (searchParams.get("action") === "new") {
      setAddFixtureOpen(true)
      setSearchParams({}, { replace: true })
    }
  }, [searchParams, setSearchParams])

  const { data: patches, isLoading: patchesLoading } = usePatchListQuery(projectId)
  // A show on a venue (venue plan session 3): whose each head is, filtered by a URL parameter like
  // the draft filter, and the venue's heads this show hides, offered back.
  const onVenue = venue != null
  const sourceFilter: SourceFilter = onVenue ? parseSourceFilter(searchParams.get('source')) : 'all'
  const setSourceFilter = (next: SourceFilter) =>
    setSearchParams(
      (prev) => {
        const params = new URLSearchParams(prev)
        if (next === 'all') params.delete('source')
        else params.set('source', next)
        return params
      },
      { replace: true },
    )
  const { data: hiddenPatches } = useHiddenPatchListQuery(projectId, { skip: !onVenue })
  const draft = useVenueDraftPatches(projectId)
  // An operator looks at a venue; only an admin changes one (venue plan D3).
  const venueMode = useVenueMode(projectId)
  const readOnly = venueMode.isVenue && !venueMode.canEdit
  const draftFilter: 'all' | 'changed' = draft.enabled && searchParams.get('draft') === 'changed' ? 'changed' : 'all'
  const setDraftFilter = (next: 'all' | 'changed') =>
    setSearchParams(
      (prev) => {
        const params = new URLSearchParams(prev)
        if (next === 'changed') params.set('draft', 'changed')
        else params.delete('draft')
        return params
      },
      { replace: true },
    )
  const { data: universeConfigs } = useUniverseConfigListQuery(projectId)
  const { data: patchGroups } = usePatchGroupListQuery(projectId)
  const { data: riggings } = useRiggingListQuery(projectId)
  const { data: fixtureTypes } = useFixtureTypeListQuery()
  const lanterns = useLanternIndex()

  const universes = useMemo(
    () => [...new Set((patches ?? []).map((p) => p.universe))].sort((a, b) => a - b),
    [patches],
  )
  const rows = useMemo(() => {
    const all = buildPatchRows(patches, riggings, fixtureTypes, universeFilter, filter, lanterns)
    const built =
      sourceFilter === 'hidden' ? [] :
      sourceFilter === 'venue' ? all.filter((row) => isVenueOwned(row.patch)) :
      sourceFilter === 'show' ? all.filter((row) => !isVenueOwned(row.patch)) :
      all
    if (!draft.enabled) return built
    const marked = built.map((row) => ({ ...row, draft: draft.byUuid.get(row.patch.uuid) ?? null }))
    return draftFilter === 'changed' ? marked.filter((row) => row.draft != null) : marked
  }, [patches, riggings, fixtureTypes, universeFilter, filter, lanterns, draft.enabled, draft.byUuid, draftFilter, sourceFilter])
  const hiddenRows = useMemo(
    () => (sourceFilter === 'all' || sourceFilter === 'hidden' || sourceFilter === 'venue'
      ? filterPatches(hiddenPatches ?? NO_PATCHES, universeFilter, filter)
      : NO_PATCHES),
    [hiddenPatches, sourceFilter, universeFilter, filter],
  )
  // The heads the draft removed are not the draft's rows: they are drawn from the installed version.
  const removedRows = useMemo(
    () => filterPatches(draft.removed, universeFilter, filter),
    [draft.removed, universeFilter, filter],
  )
  // The Draft marker leads the value columns rather than trailing them as the board draws it: the
  // real sheet has twice the board's columns, and a marker scrolled out of sight says nothing.
  const visibleColumns = useMemo(
    () => [
      ...(draft.enabled ? (['draft'] as const) : []),
      // Whose each head is leads the value columns in a show on a venue, as the board draws it.
      ...(onVenue ? (['source'] as const) : []),
      ...PATCH_COLUMN_ORDER.filter((key) => columnVisibility[key]),
      // What the show made its own of each venue head (session 5) closes the row, as the board's
      // *This show* column does.
      ...(onVenue ? (['facets'] as const) : []),
    ],
    [columnVisibility, draft.enabled, onVenue],
  )
  const onEditPatch = useCallback((id: number) => setEditingPatchId(id), [])
  const onEditGroup = useCallback((id: number, name: string) => setEditingGroup({ id, name }), [])

  const editingPatch = patches?.find(p => p.id === editingPatchId) ?? null
  const totalPatches = patches?.length ?? 0
  const totalGroups = patchGroups?.length ?? 0
  const heads = useMemo(
    () => (patches ?? []).map((p) => ({ id: p.id, name: p.displayName, universe: p.universe, channel: p.startChannel, footprint: p.channelCount ?? 1 })),
    [patches],
  )
  const addressesUsed = useMemo(
    () => universes.reduce((n, u) => n + universeFill(heads, u), 0),
    [heads, universes],
  )

  return (
    <SheetPage>
      <SheetPage.Header>
        <Breadcrumbs projectName={projectName} currentPage="Patch List" />
        {venue && (
          <>
            <div className="flex-1" />
            <VenueChip venue={venue} />
          </>
        )}
      </SheetPage.Header>

      {/* The chips: universes with a fill bar each, then the groups (which fold behind row B's
          Groups). A 40px chrome row of the shell with 28px chips — a control inside a control's
          tier — and its own line, above row B; it was a padded strip with no height and no line.
          A row is one line by definition, so past the width the chips scroll sideways rather than
          wrapping the row taller. */}
      {(universeConfigs?.length || (showGroups && patchGroups?.length)) ? (
        <SheetPage.Row className="overflow-x-auto [scrollbar-width:none]">
          {universeConfigs && universeConfigs.length > 0 && (
            <>
              <span className="shrink-0 text-[10px] font-medium uppercase tracking-wider text-muted-foreground/60">Universes</span>
              {universeConfigs.map((config) => (
                <UniverseChip
                  key={config.id}
                  config={config}
                  projectId={projectId}
                  fill={universeFill(heads, config.universe)}
                  readOnly={readOnly}
                />
              ))}
            </>
          )}
          {showGroups && patchGroups && patchGroups.length > 0 && (
            <>
              <span className="w-3 shrink-0" />
              <span className="shrink-0 text-[10px] font-medium uppercase tracking-wider text-muted-foreground/60">Groups</span>
              {patchGroups.map((group) => (
                <button
                  key={group.id}
                  onClick={() => setEditingGroup({ id: group.id, name: group.name })}
                  className={CHIP_CLASS}
                >
                  <span className="font-medium">{group.name}</span>
                  <span className="text-muted-foreground">{group.memberCount}</span>
                </button>
              ))}
            </>
          )}
        </SheetPage.Row>
      ) : null}

      {/* Row B: universe toggle · filter · spacer · Groups · Columns · + Patch. 40px, 32px controls. */}
      <SheetPage.Row>
        {universes.length > 1 && (
          <ToggleGroup
            type="single"
            size="sm"
            value={universeFilter === 'all' ? 'all' : String(universeFilter)}
            onValueChange={(v) => {
              if (!v) return
              setUniverseFilter(v === 'all' ? 'all' : Number(v))
            }}
            aria-label="Universe"
          >
            <ToggleGroupItem value="all">All</ToggleGroupItem>
            {universes.map((u) => (
              <ToggleGroupItem key={u} value={String(u)}>
                U{u}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
        )}
        {/* Whose each head is, in a show on a venue (venue plan session 3): a URL parameter. */}
        {onVenue && (
          <ToggleGroup
            type="single"
            size="sm"
            value={sourceFilter}
            onValueChange={(v) => {
              if (isSourceFilter(v)) setSourceFilter(v)
            }}
            aria-label="Source"
          >
            <ToggleGroupItem value="all">All</ToggleGroupItem>
            <ToggleGroupItem value="venue">Venue</ToggleGroupItem>
            <ToggleGroupItem value="show">This show</ToggleGroupItem>
            <ToggleGroupItem value="hidden">
              Hidden{hiddenPatches && hiddenPatches.length > 0 ? ` · ${hiddenPatches.length}` : ''}
            </ToggleGroupItem>
          </ToggleGroup>
        )}
        {/* A venue's draft filter (venue plan session 2): a URL parameter, like the universe toggle. */}
        {draft.enabled && (
          <ToggleGroup
            type="single"
            size="sm"
            value={draftFilter}
            onValueChange={(v) => {
              if (v === 'all' || v === 'changed') setDraftFilter(v)
            }}
            aria-label="Draft"
          >
            <ToggleGroupItem value="all">All</ToggleGroupItem>
            <ToggleGroupItem value="changed">Changed in draft</ToggleGroupItem>
          </ToggleGroup>
        )}
        <div className="relative min-w-0 max-w-[340px] flex-[999_1_0%]">
          <Search className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            placeholder="Filter…"
            title="Filter by name, head number, key, type or address"
            aria-label="Filter by name, head number, key, type or address"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            className="h-8 pl-9"
          />
        </div>
        <div className="flex-1" />
        <Button
          variant={showGroups ? 'default' : 'outline'}
          size="sm"
          onClick={() => setShowGroups(!showGroups)}
          title="Show the group chips"
          aria-pressed={showGroups}
        >
          <Layers className="size-3.5" />
          <span className="hidden sm:inline">Groups</span>
        </Button>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="outline" size="sm" title="Choose columns">
              <Columns3 className="size-3.5" />
              <span className="hidden sm:inline">Columns</span>
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            {PATCH_COLUMN_ORDER.map((key) => (
              <DropdownMenuCheckboxItem
                key={key}
                checked={columnVisibility[key]}
                onCheckedChange={(checked) =>
                  setColumnVisibility({ ...columnVisibility, [key]: checked === true })
                }
              >
                {PATCH_COLUMN_LABELS[key]}
              </DropdownMenuCheckboxItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
        {!readOnly && (
          <Button onClick={() => setAddFixtureOpen(true)} size="sm" className="gap-1.5 shrink-0">
            <Plus className="size-4" />
            <span className="hidden sm:inline">Patch</span>
          </Button>
        )}
      </SheetPage.Row>

      {patchesLoading ? (
        <SheetPage.Empty loading />
      ) : totalPatches === 0 && removedRows.length === 0 && hiddenRows.length === 0 ? (
        <SheetPage.Empty>
          {readOnly ? 'No fixtures patched in this venue yet.' : <>No fixtures patched yet. Click &ldquo;Patch&rdquo; to get started.</>}
        </SheetPage.Empty>
      ) : rows.length === 0 && removedRows.length === 0 && hiddenRows.length === 0 ? (
        <SheetPage.Empty>
          {draftFilter === 'changed'
            ? 'Nothing patched has changed in the draft.'
            : sourceFilter === 'hidden'
              ? 'This show hides none of the venue’s fixtures.'
              : 'No fixtures match your filter.'}
        </SheetPage.Empty>
      ) : rows.length === 0 ? (
        <>
          <RemovedInDraft patches={removedRows} />
          <HiddenInShow projectId={projectId} patches={hiddenRows} />
        </>
      ) : (
        <>
        <PatchSheet
          projectId={projectId}
          rows={rows}
          allPatches={patches ?? []}
          riggings={riggings ?? []}
          lanterns={lanterns}
          visibleColumns={visibleColumns}
          onEditPatch={onEditPatch}
          onEditGroup={onEditGroup}
          onCountsChange={setSelectedCount}
          readOnly={readOnly}
        />
        <RemovedInDraft patches={removedRows} />
        <HiddenInShow projectId={projectId} patches={hiddenRows} />
        </>
      )}

      {/* The footer: the counts, and how full the rig is. */}
      <SheetPage.Footer>
        <span className="tabular-nums">
          {totalPatches} fixture{totalPatches === 1 ? '' : 's'} patched
          {selectedCount > 0 ? ` · ${selectedCount} selected` : ''}
          {totalGroups > 0 ? ` · ${totalGroups} group${totalGroups === 1 ? '' : 's'}` : ''}
        </span>
        <span className="ml-auto tabular-nums">
          {universes.length} universe{universes.length === 1 ? '' : 's'} · {addressesUsed} of {universes.length * 512} addresses
        </span>
      </SheetPage.Footer>

      <AddFixtureSheet
        open={addFixtureOpen}
        onOpenChange={setAddFixtureOpen}
        projectId={projectId}
        existingPatches={patches ?? []}
      />
      <EditPatchSheet
        open={editingPatchId != null}
        onOpenChange={(open) => { if (!open) setEditingPatchId(null) }}
        patch={editingPatch}
        projectId={projectId}
        existingPatches={patches ?? []}
      />
      <EditGroupSheet
        open={editingGroup != null}
        onOpenChange={(open) => { if (!open) setEditingGroup(null) }}
        groupId={editingGroup?.id ?? null}
        groupName={editingGroup?.name ?? ''}
        projectId={projectId}
        patches={patches ?? []}
      />
    </SheetPage>
  )
}

// ─── A venue's draft ──────────────────────────────────────────────────

const NO_CHANGES = new Map<string, VenueChange>()
const NO_PATCHES: FixturePatch[] = []

/**
 * What a venue's draft changed on its patch, for an admin in venue mode (venue plan session 2): each
 * draft head's change by uuid, and the heads it removed — read from the installed version's frozen
 * project, since the draft no longer holds them. `enabled` is false for a show, for an operator (who
 * sees no draft), and until History has answered.
 */
function useVenueDraftPatches(projectId: number) {
  const { canEdit } = useVenueMode(projectId)
  const { data: history } = useVenueHistory(projectId)
  const enabled = canEdit && history != null
  const { data: diff } = useVenueDiffQuery(
    { venueId: projectId, from: history?.installed ?? 0, to: 'draft' },
    { skip: !enabled },
  )
  const patchChanges = useMemo(
    () => [...(diff?.output ?? []), ...(diff?.drawing ?? [])].filter((c) => c.kind === 'PATCH' && c.uuid),
    [diff],
  )
  const removedUuids = useMemo(
    () => new Set(patchChanges.filter((c) => c.change === 'REMOVED').map((c) => c.uuid!)),
    [patchChanges],
  )
  const installedId = history?.versions[0]?.snapshotProjectId ?? 0
  const { data: installedPatches } = usePatchListQuery(installedId, {
    skip: !enabled || installedId === 0 || removedUuids.size === 0,
  })
  const byUuid = useMemo(
    () => (enabled ? new Map(patchChanges.filter((c) => c.change !== 'REMOVED').map((c) => [c.uuid!, c])) : NO_CHANGES),
    [enabled, patchChanges],
  )
  const removed = useMemo(
    () => (enabled && installedPatches ? installedPatches.filter((p) => removedUuids.has(p.uuid)) : NO_PATCHES),
    [enabled, installedPatches, removedUuids],
  )
  return { enabled, byUuid, removed }
}

const REMOVED_CHANGE: VenueChange = {
  kind: 'PATCH',
  table: 'fixturePatches',
  name: '',
  change: 'REMOVED',
  output: true,
}

/**
 * The heads the draft removed, struck through under the sheet: they are no longer the venue's rows,
 * so nothing here edits them — Discard draft brings them back.
 */
function RemovedInDraft({ patches }: { patches: readonly FixturePatch[] }) {
  if (patches.length === 0) return null
  return (
    <section aria-label="Removed in the draft" className="border-t">
      <p className="px-3 pt-2 text-[10px] font-medium uppercase tracking-wider text-muted-foreground/60">Removed in the draft</p>
      <ul>
        {patches.map((p) => (
          <li key={p.uuid} className="flex h-9 items-center gap-3 border-b px-3 text-sm text-muted-foreground">
            <span className="w-[208px] truncate font-medium line-through">{p.displayName}</span>
            <span className="w-16 font-mono text-xs line-through">{formatPatchAddress(p.universe, p.startChannel)}</span>
            <span className="min-w-0 flex-1 truncate line-through">{[p.manufacturer, p.model].filter(Boolean).join(' ') || p.fixtureTypeKey}</span>
            <span className="hidden truncate font-mono text-xs line-through sm:inline">{p.key}</span>
            <DraftMarker change={REMOVED_CHANGE} />
          </li>
        ))}
      </ul>
    </section>
  )
}

// ─── A show on a venue ────────────────────────────────────────────────

/** The Patch list's source filter in a show on a venue — `?source=`, absent for All. */
type SourceFilter = 'all' | 'venue' | 'show' | 'hidden'

function isSourceFilter(value: string): value is SourceFilter {
  return value === 'all' || value === 'venue' || value === 'show' || value === 'hidden'
}

function parseSourceFilter(raw: string | null): SourceFilter {
  return raw != null && isSourceFilter(raw) ? raw : 'all'
}

/**
 * The venue's heads this show hides (venue plan session 3, `Show.dc.html` callout 4): under the
 * sheet, dimmed, each with **Show in this show** — the way a hidden house unit comes back. Rows in
 * cues and Looks that name a hidden head stay, marked *Missing fixture* as today.
 */
function HiddenInShow({ projectId, patches }: { projectId: number; patches: readonly FixturePatch[] }) {
  const [setHidden, { isLoading }] = useSetPatchHiddenMutation()
  if (patches.length === 0) return null
  return (
    <section aria-label="Hidden in this show" className="border-t">
      <p className="px-3 pt-2 text-[10px] font-medium uppercase tracking-wider text-muted-foreground/60">Hidden in this show</p>
      <ul>
        {patches.map((p) => (
          <li key={p.uuid} className="flex h-9 items-center gap-3 border-b px-3 text-sm text-muted-foreground">
            <EyeOff className="size-3.5 shrink-0" aria-hidden />
            <span className="w-[192px] truncate font-medium">{p.displayName}</span>
            <span className="w-16 font-mono text-xs">{formatPatchAddress(p.universe, p.startChannel)}</span>
            <span className="min-w-0 flex-1 truncate">{[p.manufacturer, p.model].filter(Boolean).join(' ') || p.fixtureTypeKey}</span>
            <span className="hidden truncate font-mono text-xs sm:inline">{p.key}</span>
            <Button
              variant="outline"
              size="sm"
              className="shrink-0"
              disabled={isLoading}
              onClick={() => {
                // A refusal (`KEY_TAKEN_BY_SHOW`: the show's own head took its key) is toasted.
                setHidden({ projectId, patchId: p.id, hidden: false }).unwrap().catch(ignoreReportedError)
              }}
            >
              <Eye className="size-3.5" />
              <span className="hidden sm:inline">Show in this show</span>
            </Button>
          </li>
        ))}
      </ul>
    </section>
  )
}

// ─── Universe chips ───────────────────────────────────────────────────

function UniverseChip({
  config,
  projectId,
  fill,
  readOnly = false,
}: {
  config: UniverseConfig
  projectId: number
  fill: number
  /** A venue an operator is looking at: the chip reads out and does not open its editor. */
  readOnly?: boolean
}) {
  const [editing, setEditing] = useState(false)
  const [address, setAddress] = useState(config.address ?? '')
  const [intervalMs, setIntervalMs] = useState(String(config.refreshIntervalMs))
  const [error, setError] = useState<string | null>(null)
  const [runUpdateConfig, { isLoading: isSaving }] = useUpdateUniverseConfigMutation()

  // Sync local state when config changes from server
  useEffect(() => {
    setAddress(config.address ?? '')
  }, [config.address])

  useEffect(() => {
    setIntervalMs(String(config.refreshIntervalMs))
  }, [config.refreshIntervalMs])

  // A failed save leaves the popover open showing why. Without this, dismissing it and
  // reopening re-renders that stale error against fields that no longer match it.
  useEffect(() => {
    if (!editing) setError(null)
  }, [editing])

  const parsedInterval = parseNullableNumber(intervalMs)
  const intervalValid =
    parsedInterval !== null &&
    Number.isInteger(parsedInterval) &&
    parsedInterval >= MIN_REFRESH_INTERVAL_MS &&
    parsedInterval <= MAX_REFRESH_INTERVAL_MS

  const save = async (body: { address?: string; refreshIntervalMs?: number; resetRefreshInterval?: boolean }) => {
    setError(null)
    try {
      await runUpdateConfig({ projectId, configId: config.id, ...body }).unwrap()
      setEditing(false)
    } catch (e) {
      // A 400 is reachable now that the interval is bounded, so the popover has to be able
      // to say why rather than silently discarding the edit.
      const detail = (e as { data?: { error?: string } })?.data?.error
      setError(detail ?? 'Could not save universe settings.')
    }
  }

  const handleSave = () => {
    if (!intervalValid) return
    void save({ address, refreshIntervalMs: parsedInterval })
  }

  const handleUseDefault = () => void save({ address, resetRefreshInterval: true })

  return (
    <Popover open={editing && !readOnly} onOpenChange={(open) => setEditing(open && !readOnly)}>
      <PopoverTrigger asChild>
        <button className={CHIP_CLASS} disabled={readOnly}>
          <span className="font-mono font-medium">U{config.universe}</span>
          {config.address ? (
            <span className="text-muted-foreground">{config.address}</span>
          ) : (
            <span className="text-muted-foreground/50 italic">no address</span>
          )}
          {/* The fill bar: how many of the universe's 512 addresses its heads occupy. Titan draws
              one per line; here it is the one thing about a universe an operator patching by
              hand wants to see at a glance. */}
          <span
            className="relative h-1.5 w-16 overflow-hidden rounded-full bg-muted"
            title={`${fill} of 512 addresses used`}
            role="img"
            aria-label={`${fill} of 512 addresses used`}
          >
            <span
              className="absolute inset-y-0 left-0 rounded-full bg-muted-foreground/70"
              style={{ width: `${Math.round((fill / 512) * 100)}%` }}
            />
          </span>
          {/* Only when pinned on this desk. A machine-local setting that lives behind a
              popover is one people forget they set — but showing the default on every
              chip would be noise on every rig that never touches it. */}
          {config.refreshIntervalOverridden && (
            <span className="text-muted-foreground font-mono">· {config.refreshIntervalMs}ms</span>
          )}
          {!readOnly && <Pencil className="size-2.5 text-muted-foreground/50" />}
        </button>
      </PopoverTrigger>
      <PopoverContent className="w-72 p-3" align="start">
        <div className="space-y-3">
          <p className="text-xs font-medium">Universe {config.universe}</p>

          <div className="space-y-1.5">
            <label className="text-xs text-muted-foreground" htmlFor={`u${config.id}-address`}>
              ArtNet address
            </label>
            <Input
              id={`u${config.id}-address`}
              value={address}
              onChange={(e) => setAddress(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') handleSave() }}
              placeholder="e.g. 192.168.1.100"
              className="text-xs h-8"
              autoFocus
            />
          </div>

          <div className="space-y-1.5">
            <label className="text-xs text-muted-foreground" htmlFor={`u${config.id}-interval`}>
              Refresh interval (ms)
            </label>
            <div className="flex gap-2">
              <Input
                id={`u${config.id}-interval`}
                type="number"
                min={MIN_REFRESH_INTERVAL_MS}
                max={MAX_REFRESH_INTERVAL_MS}
                step={1}
                value={intervalMs}
                onChange={(e) => setIntervalMs(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') handleSave() }}
                onFocus={(e) => e.target.select()}
                className="text-xs h-8"
              />
              <Button
                size="sm"
                variant="outline"
                className="h-8 px-2 text-xs whitespace-nowrap"
                disabled={isSaving || !config.refreshIntervalOverridden}
                onClick={handleUseDefault}
              >
                Use default
              </Button>
            </div>
            <p className="text-[11px] text-muted-foreground">
              {config.refreshIntervalOverridden
                ? `Set on this desk. Default is ${DEFAULT_REFRESH_INTERVAL_MS}ms.`
                : `Using the ${DEFAULT_REFRESH_INTERVAL_MS}ms default.`}{' '}
              This machine only — it does not travel with the show.
            </p>
          </div>

          {error && <p className="text-xs text-destructive">{error}</p>}

          <div className="flex justify-end">
            <Button size="sm" className="h-8 px-3" disabled={isSaving || !intervalValid} onClick={handleSave}>
              <Check className="size-3.5 mr-1" /> Save
            </Button>
          </div>
        </div>
      </PopoverContent>
    </Popover>
  )
}

// ─── Data transformation ──────────────────────────────────────────────

/** The universe toggle and the text filter, over patches — the sheet's rows and the removed heads alike. */
function filterPatches(
  patches: readonly FixturePatch[],
  universeFilter: 'all' | number,
  filter: string,
): FixturePatch[] {
  const needle = filter.trim().toLowerCase()
  return patches
    .filter((p) => universeFilter === 'all' || p.universe === universeFilter)
    .filter((p) => {
      if (needle === '') return true
      const address = `${p.universe}-${String(p.startChannel).padStart(3, '0')}`
      const head = p.headNumber == null ? null : String(p.headNumber)
      return [p.displayName, p.key, head, p.manufacturer, p.model, p.modeName, address]
        .some((field) => field?.toLowerCase().includes(needle))
    })
}

function buildPatchRows(
  patches: FixturePatch[] | undefined,
  riggings: { uuid: string; name: string; kind?: string | null }[] | undefined,
  fixtureTypes: FixtureTypeInfo[] | undefined,
  universeFilter: 'all' | number,
  filter: string,
  lanterns: LanternIndex = EMPTY_LANTERNS,
): PatchSheetRow[] {
  if (!patches) return []

  const riggingNames = new Map<string, string>()
  const riggingKinds = new Map<string, string | null>()
  for (const r of riggings ?? []) {
    riggingNames.set(r.uuid, r.name)
    riggingKinds.set(r.uuid, r.kind ?? null)
  }
  const typeByKey = new Map((fixtureTypes ?? []).map((t) => [t.typeKey, t]))

  const rows: PatchSheetRow[] = filterPatches(patches, universeFilter, filter)
    .map((p) => {
      const type = typeByKey.get(p.fixtureTypeKey)
      const acceptsLantern = type?.acceptsLantern === true
      // The fixture's lanterns — its own, then each placement's (its own, else the fixture's) — as
      // the Stage view draws them.
      const kind = resolveFixtureKind(p.kindOverride, type?.kind)
      const drawn = acceptsLantern
        ? [p.lanternType ?? null, ...(p.extraPlacements ?? []).map((pl) => pl.lanternType ?? p.lanternType ?? null)]
            .map((id) => effectiveLantern(lanterns, id, kind)?.name)
            .filter((name): name is string => name != null)
        : []
      const named = p.lanternType != null || (p.extraPlacements ?? []).some((pl) => pl.lanternType != null)
      return {
        id: patchRowId(p.id),
        patch: p,
        riggingName: p.riggingUuid ? riggingNames.get(p.riggingUuid) ?? null : null,
        riggingKind: p.riggingUuid ? riggingKinds.get(p.riggingUuid) ?? null : null,
        acceptsBeamAngle: type?.acceptsBeamAngle ?? false,
        acceptsGel: type?.acceptsGel ?? false,
        acceptsLantern,
        lanternLabel: acceptsLantern ? lanternListLabel(drawn) || null : null,
        lanternIsDefault: acceptsLantern && !named,
        bodyLabel: type?.body ? [type.body.archetype, type.body.head].filter(Boolean).join(' · ') : null,
      }
    })

  rows.sort((a, b) => a.patch.universe * 1000 + a.patch.startChannel - (b.patch.universe * 1000 + b.patch.startChannel))
  return rows
}
