import { useEffect, useState, useCallback } from "react"
import { useNavigate } from "react-router"
import { Command } from "cmdk"
import * as DialogPrimitive from "@radix-ui/react-dialog"
import {
  Settings, FolderOpen, PlusCircle, Search, TableProperties, Bookmark, Sparkles,
  Clapperboard, AudioWaveform, LayoutGrid, Layers, ArrowLeft, Lock,
  SlidersHorizontal,
} from "lucide-react"
import type { LucideIcon } from "lucide-react"
import { useProjectListQuery, useCurrentProjectQuery } from "@/store/projects"
import { useVisibleFixtureListQuery, type Fixture } from "@/store/fixtures"
import { useGroupListQuery } from "@/store/groups"
import type { GroupSummary } from "@/api/groupsApi"
import {
  useNavItems,
  useUniverseNavItems,
  useTemplateFamilyNavItems,
  useWindowCommands,
  filterNavItems,
  useIsNavAdmin,
} from "@/navigation"
import { fixtureSelectParam, groupSelectParam } from "@/components/fixtures-list/rowModel"
import { useViewedProject } from "@/ProjectSwitcher"
import type { FxTarget } from "@/components/fx/FxPicker"
import { useGetParkStateListQuery } from "@/store/park"
import { useGetChannelMappingListQuery } from "@/store/channelMapping"

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
  const [search, setSearch] = useState("")
  const navigate = useNavigate()
  const { data: projects } = useProjectListQuery(undefined, closed)
  const { data: currentProject } = useCurrentProjectQuery(undefined, closed)
  const { data: fixtures } = useVisibleFixtureListQuery(closed)
  const { data: groups } = useGroupListQuery(undefined, closed)
  const allNavItems = useNavItems()
  const universeNavItems = useUniverseNavItems()
  const templateFamilyNavItems = useTemplateFamilyNavItems()
  const isNavAdmin = useIsNavAdmin()

  const { data: parkStateList } = useGetParkStateListQuery(undefined, closed)
  const { data: channelMappings } = useGetChannelMappingListQuery(undefined, closed)
  const parkedCount = parkStateList?.length ?? 0

  const viewedProject = useViewedProject()
  const isViewingActiveProject = viewedProject?.id === currentProject?.id
  const windowCommands = useWindowCommands(viewedProject?.id ?? null)
  const visibleItems = filterNavItems(allNavItems, isViewingActiveProject, isNavAdmin)
  const visibleUniverseItems = filterNavItems(universeNavItems, isViewingActiveProject)
  const visibleTemplateFamilyItems = filterNavItems(templateFamilyNavItems, isViewingActiveProject)

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
                {[...visibleItems, ...visibleUniverseItems, ...visibleTemplateFamilyItems].map((item) => (
                  <Command.Item
                    key={item.id}
                    value={item.label}
                    onSelect={() => runAction(() => navigate(item.path(viewedProject.id)))}
                    className={itemClassName}
                  >
                    <item.icon className="size-4 text-muted-foreground" />
                    {item.label}
                  </Command.Item>
                ))}
              </Command.Group>
            )}

            {/* Actions */}
            <Command.Group heading="Actions" className={groupClassName}>
              {viewedProject && (
                <Command.Item
                  value="Project Settings"
                  keywords={["configure", "project", "settings", "general", "metadata"]}
                  onSelect={() => runAction(() => navigate(`/projects/${viewedProject.id}/settings`))}
                  className={itemClassName}
                >
                  <Settings className="size-4 text-muted-foreground" />
                  Project Settings
                </Command.Item>
              )}
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
                      the preset tables two sessions ago, so the entry had been dead. A template is
                      what that gesture means now: a named value, authored rather than captured,
                      which is why it has a create entry at all where a Look does not. */}
                  <Command.Item
                    value="New Template"
                    // `effect` and `chase` because a template can now hold one, and the palette
                    // keeps a *single* New Template action — the kind is chosen inside the sheet,
                    // so a second entry would offer a choice the sheet then asks for again.
                    keywords={["template", "value", "colour", "palette", "create", "effect", "chase"]}
                    onSelect={() => runAction(() => navigate(`/projects/${viewedProject.id}/templates?action=new`))}
                    className={itemClassName}
                  >
                    <Bookmark className="size-4 text-muted-foreground" />
                    New Template
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
                onSelect={() => runAction(() => navigate("/projects"))}
                className={itemClassName}
              >
                <PlusCircle className="size-4 text-muted-foreground" />
                Create New Project
              </Command.Item>
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

            {/* Projects */}
            {projects && projects.length > 0 && (
              <Command.Group heading="Projects" className={groupClassName}>
                <Command.Item
                  value="View All Projects"
                  onSelect={() => runAction(() => navigate("/projects"))}
                  className={itemClassName}
                >
                  <FolderOpen className="size-4 text-muted-foreground" />
                  View All Projects
                </Command.Item>
                {projects.map((project) => (
                  <Command.Item
                    key={project.id}
                    value={`Go to ${project.name}`}
                    keywords={[project.name]}
                    onSelect={() => runAction(() => navigate(`/projects/${project.id}`))}
                    className={itemClassName}
                  >
                    <FolderOpen className="size-4 text-muted-foreground" />
                    <span className="flex-1 truncate">{project.name}</span>
                    {project.isCurrent && (
                      <span className="text-xs text-muted-foreground">Active</span>
                    )}
                  </Command.Item>
                ))}
              </Command.Group>
            )}
          </>
        )}

        {/* Apply FX sub-page: pick a fixture or group */}
        {activePage === "apply-fx" && (
          <>
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
  )
}
