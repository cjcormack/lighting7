import { useEffect, useMemo, useState, useCallback } from "react"
import { useLocation, useNavigate } from "react-router"
import { Command } from "cmdk"
import * as DialogPrimitive from "@radix-ui/react-dialog"
import {
  FolderOpen, PlusCircle, Search, TableProperties, Bookmark, Sparkles,
  Clapperboard, AudioWaveform, LayoutGrid, Layers, ArrowLeft, Lock,
  ListOrdered, SlidersHorizontal, CirclePlay, Activity, Settings, Copy, Download,
  RefreshCw, Unlink2, Link2, PackageOpen, History, Upload, Trash2, Landmark,
} from "lucide-react"
import type { LucideIcon } from "lucide-react"
import { useProjectListQuery } from "@/store/projects"
import { useAuthStatusQuery } from "@/store/auth"
import { cn } from "@/lib/utils"
import { useVisibleFixtureListQuery, type Fixture } from "@/store/fixtures"
import { useGroupListQuery } from "@/store/groups"
import type { GroupSummary } from "@/api/groupsApi"
import {
  useNavItems,
  useUniverseNavItems,
  useTemplateFamilyNavItems,
  APPLIED_NAV_ID,
  appliedTableNavItems,
  useWindowCommands,
  filterNavItems,
  useIsNavAdmin,
  commandLabelOf,
  navItems,
} from "@/navigation"
import { fixtureSelectParam, groupSelectParam } from "@/components/fixtures-list/rowModel"
import { useViewedProject } from "@/ProjectSwitcher"
import type { FxTarget } from "@/components/fx/FxPicker"
import { effectTargetOf, useWindowSelectionTargets } from "@/components/fx/selectionEffectTarget"
import { useGetParkStateListQuery } from "@/store/park"
import { useGetChannelMappingListQuery } from "@/store/channelMapping"
import type { ProjectSummary } from "@/api/projectApi"
import { projectActions, type ProjectAction, type ProjectActionId } from "@/lib/projectActions"
import { useProjectActions } from "@/components/projectActions/ProjectActions"
import ProjectSwitchConfirmDialog from "@/ProjectSwitchConfirmDialog"
import CreateProjectDialog from "@/CreateProjectDialog"
import ImportProjectDialog from "@/ImportProjectDialog"

export interface ToggleState {
  label: string
  icon: LucideIcon
  isVisible: boolean
  onToggle: () => void
}

interface CommandPaletteProps {
  onApplyFx?: (target: FxTarget) => void
  onParkChannelAtValue?: () => void
  onSetChannelValue?: () => void
  toggles?: ToggleState[]
}

const itemClassName =
  "flex items-center gap-2 rounded-md px-2 py-1.5 text-sm cursor-pointer aria-selected:bg-accent aria-selected:text-accent-foreground"

const groupClassName =
  "[&_[cmdk-group-heading]]:px-2 [&_[cmdk-group-heading]]:py-1.5 [&_[cmdk-group-heading]]:text-xs [&_[cmdk-group-heading]]:font-medium [&_[cmdk-group-heading]]:text-muted-foreground"

function formatDmxAddress(universe: number, firstChannel: number): string {
  return `${universe}-${String(firstChannel).padStart(3, "0")}`
}

function fixtureKeywords(fixture: Fixture): string[] {
  return [fixture.name, fixture.manufacturer, fixture.model, ...(fixture.groups ?? [])].filter(
    (s): s is string => !!s
  )
}

// ─── Search filter ────────────────────────────────────────────────────────

function commandFilter(value: string, search: string, keywords?: string[]): number {
  const needle = search.toLowerCase()
  const sources = [value, ...(keywords ?? [])]
  let best = 0
  for (const source of sources) {
    best = Math.max(best, scoreSource(source.toLowerCase(), needle))
  }
  return best
}

function scoreSource(hay: string, needle: string): number {
  if (hay.startsWith(needle)) return 1
  const words = hay.split(/\s+/)
  if (words.some((w) => w.startsWith(needle))) return 0.9
  if (hay.includes(needle)) return 0.8
  if (matchWordPrefixes(needle, words, 0, 0)) return 0.7
  const initials = words.map((w) => w[0] ?? "").join("")
  if (initials.includes(needle)) return 0.6
  return 0
}

function matchWordPrefixes(needle: string, words: string[], needleIdx: number, wordIdx: number): boolean {
  if (needleIdx >= needle.length) return true
  if (wordIdx >= words.length) return false
  const word = words[wordIdx]
  if (matchWordPrefixes(needle, words, needleIdx, wordIdx + 1)) return true
  for (let take = 1; take <= word.length && needleIdx + take <= needle.length; take++) {
    if (word[take - 1] !== needle[needleIdx + take - 1]) break
    if (matchWordPrefixes(needle, words, needleIdx + take, wordIdx + 1)) return true
  }
  return false
}

// ─── Shared list items ────────────────────────────────────────────────────

function FixtureItem({ fixture, onSelect }: { fixture: Fixture; onSelect: () => void }) {
  return (
    <Command.Item
      key={fixture.key}
      value={`fixture-${fixture.key}`}
      keywords={fixtureKeywords(fixture)}
      onSelect={onSelect}
      className={itemClassName}
    >
      <LayoutGrid className="size-4 text-muted-foreground" />
      <span className="flex-1 truncate">{fixture.name}</span>
      <span className="text-xs text-muted-foreground truncate max-w-[200px]">
        {[fixture.model, formatDmxAddress(fixture.universe, fixture.firstChannel)].filter(Boolean).join(" · ")}
      </span>
    </Command.Item>
  )
}

function GroupItem({ group, onSelect }: { group: GroupSummary; onSelect: () => void }) {
  return (
    <Command.Item
      key={group.name}
      value={`Group ${group.name}`}
      keywords={[group.name, ...group.capabilities]}
      onSelect={onSelect}
      className={itemClassName}
    >
      <Layers className="size-4 text-muted-foreground" />
      <span className="flex-1 truncate">{group.name}</span>
      <span className="text-xs text-muted-foreground">
        {group.memberCount} fixture{group.memberCount !== 1 ? "s" : ""}
      </span>
    </Command.Item>
  )
}

// ─── Main component ───────────────────────────────────────────────────────

export default function CommandPalette({ onApplyFx, onParkChannelAtValue, onSetChannelValue, toggles }: CommandPaletteProps) {
  const [open, setOpen] = useState(false)
  /**
   * The palette is mounted on every route but draws nothing until it opens, so its data queries
   * are skipped until first open: subscribed while closed they re-render it on every fixture,
   * group, park and channel-mapping invalidation, on pages showing none of them, and pin those
   * caches so they can never be evicted. A latch rather than `open` itself, so reopening stays
   * instant — and the flag is set in the same batch as `open`, so the first open renders with the
   * cache entries (warm from the switcher and the routes) already readable.
   */
  const [everOpened, setEverOpened] = useState(false)
  const closed = { skip: !everOpened }
  const [pages, setPages] = useState<string[]>([])
  // *Make … current* and *Create New Project* open their dialogs over the closed palette.
  const [confirming, setConfirming] = useState<ProjectSummary | null>(null)
  const [createOpen, setCreateOpen] = useState(false)
  const [createKind, setCreateKind] = useState<"SHOW" | "VENUE">("SHOW")
  const [importOpen, setImportOpen] = useState(false)
  const [search, setSearch] = useState("")
  const navigate = useNavigate()
  const { data: projects } = useProjectListQuery(undefined, closed)
  const { data: fixtures } = useVisibleFixtureListQuery(closed)
  const { data: groups } = useGroupListQuery(undefined, closed)
  // One effect over this window's selection, in its order (effects plan D3) — the programmer's rows
  // on the programmer, the desk's or the busk view's own elsewhere.
  const { pathname } = useLocation()
  const windowSelection = useWindowSelectionTargets(pathname.includes("/programmer"))
  const selectionTarget = useMemo(
    () => effectTargetOf(windowSelection, fixtures, groups),
    [windowSelection, fixtures, groups],
  )
  const selectionLabel =
    selectionTarget == null
      ? ""
      : selectionTarget.type === "selection"
        ? selectionTarget.label
        : selectionTarget.type === "group"
          ? selectionTarget.group.name
          : selectionTarget.fixture.name
  const allNavItems = useNavItems()
  const universeNavItems = useUniverseNavItems()
  const templateFamilyNavItems = useTemplateFamilyNavItems()
  const isNavAdmin = useIsNavAdmin()
  // The telemetry card is drawn for a signed-in admin only (`InstallSettings`), so its command is too.
  const { data: authStatus } = useAuthStatusQuery()
  const isAdmin = authStatus?.user?.role === "ADMIN"

  const { data: parkStateList } = useGetParkStateListQuery(undefined, closed)
  const { data: channelMappings } = useGetChannelMappingListQuery(undefined, closed)
  const parkedCount = parkStateList?.length ?? 0

  const viewedProject = useViewedProject()
  // The viewed project's actions (navigation plan §6): the Project group, its dialogs drawn outside
  // the palette so closing it does not take them away.
  const { actions: viewedActions, dialogs: viewedDialogs } = useProjectActions(viewedProject, "palette", {
    // Deleting the project this window is on leaves nothing to show here.
    onDeleted: () => navigate("/projects"),
    skip: !everOpened,
  })
  // On the rig is the server's `isCurrent` (navigation plan P4), which agrees with the sidebar's answer.
  const isViewingActiveProject = viewedProject?.isCurrent === true
  const windowCommands = useWindowCommands(viewedProject?.id ?? null)
  // A venue's palette drops the show's views, as its rail does (venue plan §4).
  const isVenue = viewedProject?.kind === "VENUE"
  // The Settings row is the Project group's *Settings*, which takes its words.
  // The Applied row is offered as its three tables instead.
  const visibleItems = filterNavItems(allNavItems, isViewingActiveProject, isNavAdmin, isVenue).filter(
    (item) => item.id !== PROJECT_SETTINGS_NAV_ID && item.id !== APPLIED_NAV_ID,
  )
  const visibleAppliedItems = filterNavItems(appliedTableNavItems, isViewingActiveProject, true, isVenue)
  // A universe item names the live rig's universes, so only the project on the rig is offered them.
  const visibleUniverseItems = isViewingActiveProject ? filterNavItems(universeNavItems, true) : []
  const visibleTemplateFamilyItems = filterNavItems(templateFamilyNavItems, isViewingActiveProject, true, isVenue)

  const activePage = pages[pages.length - 1] ?? "root"

  // Cmd+K / Ctrl+K to open
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === "k" && (e.metaKey || e.ctrlKey)) {
        e.preventDefault()
        setOpen((o) => !o)
        setEverOpened(true)
      }
    }
    document.addEventListener("keydown", handler)
    return () => document.removeEventListener("keydown", handler)
  }, [])

  // Reset state when dialog closes
  const handleOpenChange = useCallback((next: boolean) => {
    setOpen(next)
    if (next) setEverOpened(true)
    if (!next) {
      setPages([])
      setSearch("")
    }
  }, [])

  const runAction = (fn: () => void) => {
    handleOpenChange(false)
    fn()
  }

  const pushPage = (page: string) => {
    setPages((p) => [...p, page])
    setSearch("")
  }

  const popPage = () => {
    setPages((p) => p.slice(0, -1))
    setSearch("")
  }

  return (
    <>
    <Command.Dialog
      open={open}
      onOpenChange={handleOpenChange}
      label="Command palette"
      filter={commandFilter}
      loop
      overlayClassName="fixed inset-0 z-50 bg-black/50"
      contentClassName="fixed top-[20%] left-1/2 z-50 w-full max-w-lg -translate-x-1/2 rounded-lg border bg-background shadow-lg"
    >
      <DialogPrimitive.Title className="sr-only">Command palette</DialogPrimitive.Title>
      <div className="flex items-center border-b px-3">
        {activePage !== "root" && (
          <button onClick={popPage} className="mr-2 text-muted-foreground hover:text-foreground">
            <ArrowLeft className="size-4" />
          </button>
        )}
        <Search className="size-4 text-muted-foreground shrink-0 mr-2" />
        <Command.Input
          placeholder={
            activePage === "apply-fx" ? "Select a fixture or group..."
            : activePage === "parked-channel" ? "Jump to a parked channel..."
            : "Type a command or search..."
          }
          value={search}
          onValueChange={setSearch}
          onKeyDown={(e) => {
            if (e.key === "Backspace" && !search && activePage !== "root") {
              e.preventDefault()
              popPage()
            }
          }}
          className="flex h-11 w-full bg-transparent text-sm outline-none placeholder:text-muted-foreground"
        />
      </div>

      <Command.List className="max-h-72 overflow-y-auto p-1">
        <Command.Empty className="py-6 text-center text-sm text-muted-foreground">
          No results found.
        </Command.Empty>

        {activePage === "root" && (
          <>
            {/* Navigation */}
            {viewedProject && (
              <Command.Group heading="Navigation" className={groupClassName}>
                {[...visibleItems, ...visibleAppliedItems, ...visibleUniverseItems, ...visibleTemplateFamilyItems].map((item) => (
                  <Command.Item
                    key={item.id}
                    // cmdk keys selection on `value`, so it must be unique: `commandLabel` names
                    // the rows whose sidebar label leans on a section heading.
                    value={commandLabelOf(item)}
                    keywords={item.keywords}
                    onSelect={() => runAction(() => navigate(item.path(viewedProject.id)))}
                    className={cn(itemClassName, item.offRig && "text-muted-foreground")}
                    data-off-rig={item.offRig ? "" : undefined}
                  >
                    <item.icon className="size-4 text-muted-foreground" />
                    <span className="flex-1">{commandLabelOf(item)}</span>
                    {/* Off the rig a row is still offered and opens its view's `NotOnRig` page,
                        as the sidebar's does (navigation plan D4, P2). */}
                    {item.offRig && <span className="text-xs text-muted-foreground">Not on the rig</span>}
                  </Command.Item>
                ))}
              </Command.Group>
            )}

            {/* Actions */}
            <Command.Group heading="Actions" className={groupClassName}>
              {viewedProject && isViewingActiveProject && (
                <>
                  <Command.Item
                    value="New Patch"
                    keywords={["patch", "fixture", "add"]}
                    onSelect={() => runAction(() => navigate(`/projects/${viewedProject.id}/patches?action=new`))}
                    className={itemClassName}
                  >
                    <TableProperties className="size-4 text-muted-foreground" />
                    New Patch
                  </Command.Item>
                  {/* Was "New FX Preset", pointing at `/presets?action=new` — a route retired with
                      the preset tables two sessions ago, so the entry had been dead. A palette is
                      what that gesture means now: a named value, authored rather than captured,
                      which is why it has a create entry at all where a Look does not. */}
                  <Command.Item
                    value="New Palette"
                    // `effect` and `chase` because a palette can hold one, and ⌘K keeps a *single*
                    // New Palette action — the kind is chosen inside the sheet,
                    // so a second entry would offer a choice the sheet then asks for again.
                    keywords={["template", "value", "colour", "palette", "create", "effect", "chase"]}
                    onSelect={() => runAction(() => navigate(`/projects/${viewedProject.id}/palettes?action=new`))}
                    className={itemClassName}
                  >
                    <Bookmark className="size-4 text-muted-foreground" />
                    New Palette
                  </Command.Item>
                  <Command.Item
                    value="New Cue Stack"
                    keywords={["cue", "stack", "effect", "create", "program"]}
                    onSelect={() => runAction(() => navigate(`/projects/${viewedProject.id}/show`))}
                    className={itemClassName}
                  >
                    <Clapperboard className="size-4 text-muted-foreground" />
                    New Cue Stack
                  </Command.Item>
                  <Command.Item
                    value="New FX"
                    keywords={["effect", "definition", "script", "create", "custom"]}
                    onSelect={() => runAction(() => navigate(`/projects/${viewedProject.id}/fx-library?action=new`))}
                    className={itemClassName}
                  >
                    <Sparkles className="size-4 text-muted-foreground" />
                    New FX
                  </Command.Item>
                  {onApplyFx && (
                    <Command.Item
                      value="Apply FX"
                      keywords={["effect", "fixture", "group"]}
                      onSelect={() => pushPage("apply-fx")}
                      className={itemClassName}
                    >
                      <AudioWaveform className="size-4 text-muted-foreground" />
                      Apply FX...
                    </Command.Item>
                  )}
                </>
              )}
              <Command.Item
                value="Create New Project"
                keywords={["new", "show", "project", "create"]}
                onSelect={() =>
                  runAction(() => {
                    setCreateKind("SHOW")
                    setCreateOpen(true)
                  })
                }
                className={itemClassName}
              >
                <PlusCircle className="size-4 text-muted-foreground" />
                Create New Project
              </Command.Item>
              {/* The Projects page's two admin buttons, here too (navigation plan §6). */}
              {isNavAdmin && (
                <>
                  <Command.Item
                    value="Import Project"
                    keywords={["import", "project", "folder", "show"]}
                    onSelect={() => runAction(() => setImportOpen(true))}
                    className={itemClassName}
                  >
                    <Upload className="size-4 text-muted-foreground" />
                    Import project…
                  </Command.Item>
                  <Command.Item
                    value="Add Venue"
                    keywords={["venue", "new", "create", "hall"]}
                    onSelect={() =>
                      runAction(() => {
                        setCreateKind("VENUE")
                        setCreateOpen(true)
                      })
                    }
                    className={itemClassName}
                  >
                    <Landmark className="size-4 text-muted-foreground" />
                    Add venue…
                  </Command.Item>
                </>
              )}
              {/* One switch inside General, so a command rather than a nav row (navigation plan §7). */}
              {isAdmin && (
                <Command.Item
                  value="Telemetry"
                  keywords={["diagnostics", "sentry", "send", "install"]}
                  onSelect={() => runAction(() => navigate("/install#telemetry"))}
                  className={itemClassName}
                >
                  <Activity className="size-4 text-muted-foreground" />
                  Telemetry…
                </Command.Item>
              )}
              {viewedProject && isViewingActiveProject && parkedCount > 0 && (
                <>
                  <Command.Item
                    value="View Parked Channels"
                    keywords={["park", "locked", "channels", "override"]}
                    onSelect={() => {
                      const firstUniverse = parkStateList?.[0]?.universe ?? 0
                      runAction(() => navigate(`/projects/${viewedProject.id}/channels/${firstUniverse}?parked=true`))
                    }}
                    className={itemClassName}
                  >
                    <Lock className="size-4 text-muted-foreground" />
                    <span className="flex-1">View Parked Channels</span>
                    <span className="text-xs text-muted-foreground">{parkedCount}</span>
                  </Command.Item>
                  {/* Jump-to, not unpark. Releasing park is deliberately confined to the
                      Channels view in Edit mode — a fuzzy match plus Enter is far too little
                      friction for dropping the override on a hard-powered fixture. */}
                  <Command.Item
                    value="Go to Parked Channel"
                    keywords={["unpark", "unlock", "release", "channel", "park"]}
                    onSelect={() => pushPage("parked-channel")}
                    className={itemClassName}
                  >
                    <Lock className="size-4 text-muted-foreground" />
                    Go to Parked Channel...
                  </Command.Item>
                </>
              )}
              {viewedProject && isViewingActiveProject && onParkChannelAtValue && (
                <Command.Item
                  value="Park Channel at Value"
                  keywords={["park", "lock", "channel", "set", "override"]}
                  onSelect={() => runAction(onParkChannelAtValue)}
                  className={itemClassName}
                >
                  <Lock className="size-4 text-muted-foreground" />
                  Park Channel at Value...
                </Command.Item>
              )}
              {viewedProject && isViewingActiveProject && onSetChannelValue && (
                <Command.Item
                  value="Set Channel Value"
                  keywords={["channel", "set", "dmx", "level"]}
                  onSelect={() => runAction(onSetChannelValue)}
                  className={itemClassName}
                >
                  <SlidersHorizontal className="size-4 text-muted-foreground" />
                  Set Channel Value...
                </Command.Item>
              )}
            </Command.Group>

            {/* Screens: full screen, the Screens sheet, and one window moving another
                (multi-screen plan §4, `Screens.dc.html` §3). Built from the registry the way the
                template-family items are built from the family list. */}
            <Command.Group heading="Screens" className={groupClassName}>
              {windowCommands.map((command) => (
                <Command.Item
                  key={command.id}
                  // The id, not the label: cmdk keys selection on `value`, and two windows can
                  // share a name — the label still matches through `keywords`.
                  value={command.id}
                  keywords={[command.label, ...command.keywords]}
                  onSelect={() => runAction(command.run)}
                  className={itemClassName}
                >
                  <command.icon className="size-4 text-muted-foreground" />
                  <span className="flex-1">{command.label}</span>
                  {command.detail && <span className="text-xs text-muted-foreground">{command.detail}</span>}
                </Command.Item>
              ))}
            </Command.Group>

            {/* View Toggles */}
            {toggles && toggles.length > 0 && (
              <Command.Group heading="View" className={groupClassName}>
                {toggles.map((toggle) => (
                  <Command.Item
                    key={toggle.label}
                    value={`Toggle ${toggle.label}`}
                    keywords={[toggle.label, "toggle", "show", "hide", "panel"]}
                    onSelect={() => runAction(toggle.onToggle)}
                    className={itemClassName}
                  >
                    <toggle.icon className="size-4 text-muted-foreground" />
                    <span className="flex-1">{toggle.label}</span>
                    <span className="text-xs text-muted-foreground">{toggle.isVisible ? "On" : "Off"}</span>
                  </Command.Item>
                ))}
              </Command.Group>
            )}

            {/* Fixtures & Groups */}
            {isViewingActiveProject && (fixtures?.length || groups?.length) ? (
              <Command.Group heading="Fixtures & Groups" className={groupClassName}>
                {/* Land on the list views with the row selected and scrolled
                    into view — the card pages have no way to find an item.
                    Fixtures go to the flat Fixtures list; groups to the
                    grouped Groups list (the only view with group rows). */}
                {fixtures?.map((fixture) => (
                  <FixtureItem
                    key={fixture.key}
                    fixture={fixture}
                    onSelect={() =>
                      runAction(() =>
                        navigate(
                          `/projects/${viewedProject!.id}/fixtures/list?select=${encodeURIComponent(fixtureSelectParam(fixture.key))}`,
                        ),
                      )
                    }
                  />
                ))}
                {groups?.map((group) => (
                  <GroupItem
                    key={group.name}
                    group={group}
                    onSelect={() =>
                      runAction(() =>
                        navigate(
                          `/projects/${viewedProject!.id}/groups/list?select=${encodeURIComponent(groupSelectParam(group.name))}`,
                        ),
                      )
                    }
                  />
                ))}
              </Command.Group>
            ) : null}

            {/* The viewed project's actions: the same set its card and its home's ⋮ offer. */}
            {viewedProject && (
              <Command.Group heading={`Project · ${viewedProject.name}`} className={groupClassName}>
                {viewedActions
                  .filter((action) => action.visible && action.id !== "open")
                  .map((action) => (
                    <ProjectActionItem key={action.id} action={action} projectName={viewedProject.name} onRun={runAction} />
                  ))}
              </Command.Group>
            )}

            {/* Projects */}
            {projects && projects.length > 0 && (
              <Command.Group heading="Projects" className={groupClassName}>
                {projects.map((project) => (
                  <ProjectCommandItems
                    key={project.id}
                    project={project}
                    isAdmin={isNavAdmin}
                    // The viewed project's Make current is in the Project group above.
                    offerMakeCurrent={project.id !== viewedProject?.id}
                    navigate={(path) => runAction(() => navigate(path))}
                    onMakeCurrent={() => runAction(() => setConfirming(project))}
                  />
                ))}
              </Command.Group>
            )}
          </>
        )}

        {/* Apply FX sub-page: the selection, or pick a fixture or group */}
        {activePage === "apply-fx" && (
          <>
            {selectionTarget != null && (
              <Command.Group heading="Selection" className={groupClassName}>
                <Command.Item
                  value={`On the selection ${selectionLabel}`}
                  keywords={["selection", "selected", "marquee"]}
                  onSelect={() => {
                    if (onApplyFx) runAction(() => onApplyFx(selectionTarget))
                  }}
                  className={itemClassName}
                >
                  <ListOrdered className="size-4 text-muted-foreground" />
                  <span className="flex-1 truncate">On the selection</span>
                  <span className="text-xs text-muted-foreground truncate max-w-[200px]">{selectionLabel}</span>
                </Command.Item>
              </Command.Group>
            )}
            {fixtures && fixtures.length > 0 && (
              <Command.Group heading="Fixtures" className={groupClassName}>
                {fixtures.map((fixture) => (
                  <FixtureItem
                    key={fixture.key}
                    fixture={fixture}
                    onSelect={() => {
                      if (onApplyFx) runAction(() => onApplyFx({ type: "fixture", fixture }))
                    }}
                  />
                ))}
              </Command.Group>
            )}
            {groups && groups.length > 0 && (
              <Command.Group heading="Groups" className={groupClassName}>
                {groups.map((group) => (
                  <GroupItem
                    key={group.name}
                    group={group}
                    onSelect={() => {
                      if (onApplyFx) runAction(() => onApplyFx({ type: "group", group }))
                    }}
                  />
                ))}
              </Command.Group>
            )}
          </>
        )}

        {/* Parked-channel sub-page: jump to a parked channel in the Channels view */}
        {activePage === "parked-channel" && viewedProject && parkStateList && parkStateList.length > 0 && (
          <Command.Group heading="Parked Channels" className={groupClassName}>
            {parkStateList.map((parked) => {
              const mapping = channelMappings?.[parked.universe]?.[parked.channel]
              const label = mapping
                ? `${mapping.fixtureName}${mapping.description ? ` · ${mapping.description}` : ""}`
                : "Unmapped"
              return (
                <Command.Item
                  key={`${parked.universe}:${parked.channel}`}
                  value={`${parked.universe}-${parked.channel} ${label}`}
                  keywords={[String(parked.channel), mapping?.fixtureName ?? "", mapping?.description ?? ""]}
                  onSelect={() =>
                    runAction(() =>
                      navigate(`/projects/${viewedProject.id}/channels/${parked.universe}?parked=true`),
                    )
                  }
                  className={itemClassName}
                >
                  <Lock className="size-4 text-muted-foreground" />
                  <span className="flex-1 truncate">
                    <span className="font-mono text-xs">{parked.universe}-{String(parked.channel).padStart(3, "0")}</span>
                    {" "}
                    <span className="text-muted-foreground">{label}</span>
                  </span>
                  <span className="text-xs text-amber-500 font-mono">{parked.value}</span>
                </Command.Item>
              )
            })}
          </Command.Group>
        )}

      </Command.List>

      <div className="border-t px-3 py-2 text-xs text-muted-foreground flex items-center gap-4">
        <span>
          <kbd className="rounded border bg-muted px-1 py-0.5 font-mono text-[10px]">↑↓</kbd> navigate
        </span>
        <span>
          <kbd className="rounded border bg-muted px-1 py-0.5 font-mono text-[10px]">↵</kbd> select
        </span>
        {activePage !== "root" && (
          <span>
            <kbd className="rounded border bg-muted px-1 py-0.5 font-mono text-[10px]">⌫</kbd> back
          </span>
        )}
        <span>
          <kbd className="rounded border bg-muted px-1 py-0.5 font-mono text-[10px]">esc</kbd> close
        </span>
      </div>
    </Command.Dialog>
    {/* Immersive mode hides the app bar's switcher, so this is the switch's door there (navigation plan §4.1). */}
    <ProjectSwitchConfirmDialog project={confirming} onClose={() => setConfirming(null)} />
    <CreateProjectDialog
      open={createOpen}
      setOpen={setCreateOpen}
      kind={createKind}
      onCreated={(project) => navigate(`/projects/${project.id}`)}
    />
    <ImportProjectDialog open={importOpen} setOpen={setImportOpen} />
    {viewedDialogs}
    </>
  )
}

const PROJECT_ACTION_ICONS: Record<ProjectActionId, LucideIcon> = {
  "make-current": CirclePlay,
  open: FolderOpen,
  settings: Settings,
  clone: Copy,
  export: Download,
  "update-venue": RefreshCw,
  detach: Unlink2,
  attach: Link2,
  extract: PackageOpen,
  history: History,
  publish: Upload,
  delete: Trash2,
}

const PROJECT_SETTINGS_NAV_ID = "project-settings"
const settingsRow = navItems.find((item) => item.id === PROJECT_SETTINGS_NAV_ID)

/** Words an action is also found by: the Settings row's, and what *Make … current* was found by. */
const ACTION_KEYWORDS: Partial<Record<ProjectActionId, (projectName: string) => string[]>> = {
  "make-current": (name) => [`Make ${name} current`, "switch", "rig"],
  settings: () => [settingsRow?.commandLabel ?? "Project Settings", ...(settingsRow?.keywords ?? [])],
}

/** One of the viewed project's actions; a refused one is drawn disabled with its reason. */
function ProjectActionItem({
  action,
  projectName,
  onRun,
}: {
  action: ProjectAction
  projectName: string
  onRun: (fn: () => void) => void
}) {
  const Icon = PROJECT_ACTION_ICONS[action.id]
  return (
    <Command.Item
      value={`project-action-${action.id}`}
      keywords={[action.label, projectName, ...(ACTION_KEYWORDS[action.id]?.(projectName) ?? [])]}
      disabled={!action.enabled}
      onSelect={() => onRun(action.run)}
      className={cn(itemClassName, "data-[disabled=true]:opacity-60", action.destructive && "text-destructive")}
      data-project-action={action.id}
    >
      <Icon className="size-4 text-muted-foreground" />
      <span className="flex-1 truncate">{action.label}</span>
      {action.reason && <span className="max-w-[60%] truncate text-xs text-muted-foreground">{action.reason}</span>}
    </Command.Item>
  )
}

/**
 * A project's two ⌘K rows: *Go to <name>* (its home) and, for a project not on the rig, *Make <name>
 * current* through the one confirmation — the *Open* and *Make current* of `lib/projectActions.ts`,
 * so a show whose venue is not on this desk offers no make-current here either.
 */
function ProjectCommandItems({
  project,
  isAdmin,
  offerMakeCurrent,
  navigate,
  onMakeCurrent,
}: {
  project: ProjectSummary
  isAdmin: boolean
  offerMakeCurrent: boolean
  navigate: (path: string) => void
  onMakeCurrent: () => void
}) {
  const actions = projectActions(project, { place: "palette", isAdmin, host: { navigate, open: onMakeCurrent } })
  const open = actions.find((a) => a.id === "open")!
  const makeCurrent = actions.find((a) => a.id === "make-current")!
  return (
    <>
      <Command.Item
        value={`Go to ${project.name}`}
        keywords={[project.name]}
        onSelect={open.run}
        className={itemClassName}
      >
        <FolderOpen className="size-4 text-muted-foreground" />
        <span className="flex-1 truncate">Go to {project.name}</span>
        {project.isCurrent && <span className="text-xs text-muted-foreground">On the rig</span>}
      </Command.Item>
      {offerMakeCurrent && makeCurrent.visible && makeCurrent.enabled && (
        <Command.Item
          // The id keeps two projects of one name apart; the label still matches through keywords.
          value={`make-current-${project.id}`}
          keywords={[`Make ${project.name} current`, project.name, "switch", "rig"]}
          onSelect={makeCurrent.run}
          className={itemClassName}
        >
          <CirclePlay className="size-4 text-muted-foreground" />
          <span className="flex-1 truncate">Make {project.name} current</span>
        </Command.Item>
      )}
    </>
  )
}
