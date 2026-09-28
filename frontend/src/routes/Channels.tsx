import React, { useEffect, useState, useCallback, useMemo, useRef } from "react"
import { Card } from "@/components/ui/card"
import { Slider } from "@/components/ui/slider"
import { Input } from "@/components/ui/input"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Tooltip, TooltipTrigger, TooltipContent } from "@/components/ui/tooltip"
import {
  ContextMenu,
  ContextMenuTrigger,
  ContextMenuContent,
  ContextMenuItem,
} from "@/components/ui/context-menu"
import { useParams, useNavigate, useSearchParams, useLocation, Navigate } from "react-router"
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
} from "@/components/ui/dropdown-menu"
import { ChevronRight, Loader2, Lock, LockOpen, MoreHorizontal, SlidersHorizontal, Pencil, Check } from "lucide-react"
import { useUpdateChannelMutation } from "../store/channels"
import { useChannelValue } from "@/hooks/usePropertyValues"
import { useGetChannelMappingListQuery, type ChannelMappingEntry } from "../store/channelMapping"
import {
  useGetParkStateListQuery,
  useParkChannelMutation,
  useUnparkChannelMutation,
} from "../store/park"
import { useCurrentProjectQuery, useProjectQuery } from "../store/projects"
import { useIsDeskConnected } from "../store/status"
import { DESK_OFFLINE_LABEL } from "../api/wsGesture"
import { useGetUniverseQuery } from "../store/universes"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { EditModeProvider, useEditMode } from "@/components/fixtures/EditModeContext"
import { FixtureDetailModal } from "@/components/groups/FixtureDetailModal"
import { ChannelValueDialog } from "@/components/ChannelValueDialog"
import { useVirtualizer } from "@tanstack/react-virtual"
import { CurrentProjectRedirect } from "@/components/CurrentProjectRedirect"
import { CHANNELS_VIEW_KEY, ChannelsViewSwitcher, stickyRedirectsToList } from "@/components/ViewSwitcher"

// Pre-computed static channel groups: 64 groups of 8 channels each
const CHANNEL_GROUPS: number[][] = Array.from({ length: 64 }, (_, g) =>
  Array.from({ length: 8 }, (_, i) => g * 8 + i + 1),
)

/**
 * What the park control on a channel row will do, or why it won't.
 *
 * One function for the hover tooltip *and* the context-menu item, because they were two
 * near-identical ternary chains that disagreed: with a channel parked, Edit mode off and the
 * socket down, one blamed the socket and dropped the parked value, the other named the value and
 * blamed the socket, and neither mentioned Edit mode. The blocking reasons are ordered as the
 * operator has to clear them — reconnect first, since Edit mode won't help while the desk is
 * unreachable.
 */
function parkActionReason({
  connected,
  isParked,
  isEditing,
  value,
  parkedValue,
}: {
  connected: boolean
  isParked: boolean
  isEditing: boolean
  value: number
  parkedValue?: number
}): string {
  if (!isParked) {
    return connected
      ? `Park at current value (${value})`
      : `Park at current value — ${DESK_OFFLINE_LABEL.toLowerCase()}`
  }
  if (!connected) return `Parked at ${parkedValue} — ${DESK_OFFLINE_LABEL.toLowerCase()}`
  if (!isEditing) return `Parked at ${parkedValue} — enable Edit mode to unpark`
  return 'Unpark channel'
}

export const ChannelSlider = React.memo(function ChannelSlider({
  universe,
  id,
  isEditing,
  connected,
  mapping,
  parkedValue,
  onFixtureClick,
}: {
  universe: number
  id: number
  isEditing: boolean
  /**
   * The desk is reachable. Every write this row makes — the level, park, unpark — is a
   * WebSocket frame, so with the socket down each one is discarded and the row keeps painting
   * the last value the server sent. Passed in rather than read per row: a universe renders up
   * to 512 of these, and one subscription for the page is one subscription.
   */
  connected: boolean
  mapping?: ChannelMappingEntry
  parkedValue?: number
  onFixtureClick?: (fixtureKey: string) => void
}) {
  const value = useChannelValue({ universe, channelNo: id })

  const isParked = parkedValue !== undefined
  const displayValue = isParked ? parkedValue : value
  const percentage = Math.round((displayValue / 255) * 100)

  const [runUpdateChannelMutation] = useUpdateChannelMutation()
  const [runParkChannel] = useParkChannelMutation()
  const [runUnparkChannel] = useUnparkChannelMutation()

  const handleSliderChange = useCallback((values: number[]) => {
    if (values[0] !== undefined) {
      runUpdateChannelMutation({ universe, channelNo: id, value: values[0] })
    }
  }, [runUpdateChannelMutation, universe, id])

  const handleInputChange = useCallback((event: React.ChangeEvent<HTMLInputElement>) => {
    if (event.target.value === "") {
      runUpdateChannelMutation({ universe, channelNo: id, value: 0 })
      return
    }
    const valueNumber = Number(event.target.value)
    if (isNaN(valueNumber)) return
    const clamped = Math.max(0, Math.min(255, valueNumber))
    runUpdateChannelMutation({ universe, channelNo: id, value: clamped })
  }, [runUpdateChannelMutation, universe, id])

  // Unpark is only offered in Edit mode. Park locks output where it already is, so it is
  // always safe; releasing it hands a hard-powered fixture back to the show, so it needs a
  // deliberate mode switch rather than a hover-and-click.
  const canUnpark = isParked && isEditing && connected
  // Park is otherwise always offered — it locks output where it already is — but it is still a
  // wire write, so it needs the socket like everything else here.
  const canPark = !isParked && connected

  const parkReason = parkActionReason({ connected, isParked, isEditing, value, parkedValue })

  const handleParkToggle = useCallback(() => {
    if (isParked) {
      if (canUnpark) runUnparkChannel({ universe, channelNo: id })
    } else if (canPark) {
      runParkChannel({ universe, channelNo: id, value })
    }
  }, [isParked, canUnpark, canPark, universe, id, value, runParkChannel, runUnparkChannel])

  const channelContent = (
    <div className={`rounded px-1 py-0.5 ${isParked ? "bg-amber-50/70 dark:bg-amber-900/20" : ""}`}>
      <div className="flex items-center gap-2 group/channel">
        {/* Channel number + park badge */}
        <span className="text-xs font-medium w-8 shrink-0 text-muted-foreground relative">
          {id}
          {isParked && (
            <span className="absolute -top-1 -right-1 flex size-3 items-center justify-center rounded-full bg-amber-500 text-[7px] font-bold text-white leading-none">
              P
            </span>
          )}
        </span>

        {isEditing && !isParked ? (
          <>
            <Slider
              className="flex-1 min-w-12 shrink-0"
              value={[value]}
              max={255}
              step={1}
              onValueChange={handleSliderChange}
              disabled={!connected}
            />
            <Input
              type="number"
              value={value}
              onChange={handleInputChange}
              min={0}
              max={255}
              disabled={!connected}
              className="w-12 sm:w-14 h-7 text-xs px-1 shrink-0"
            />
          </>
        ) : isEditing && isParked ? (
          <>
            <Tooltip>
              <TooltipTrigger asChild>
                <div className="flex-1 min-w-12 shrink-0 h-2 bg-muted rounded-full overflow-hidden opacity-70">
                  <div
                    className="h-full bg-amber-500 transition-all"
                    style={{ width: `${percentage}%` }}
                  />
                </div>
              </TooltipTrigger>
              <TooltipContent>
                Parked at {parkedValue}. Right-click to unpark.
              </TooltipContent>
            </Tooltip>
            <span className="w-8 sm:w-10 text-xs text-right text-amber-600 dark:text-amber-400 font-medium shrink-0">
              {parkedValue}
            </span>
          </>
        ) : (
          <>
            <div className="flex-1 min-w-12 shrink-0 h-2 bg-muted rounded-full overflow-hidden">
              <div
                className={`h-full transition-all ${isParked ? "bg-amber-500" : "bg-primary"}`}
                style={{ width: `${percentage}%` }}
              />
            </div>
            <span className={`w-8 sm:w-10 text-xs text-right shrink-0 ${isParked ? "text-amber-600 dark:text-amber-400 font-medium" : "text-muted-foreground"}`}>
              {displayValue}
            </span>
          </>
        )}

        {/* Park/unpark button — visible on hover, in edit mode, or on touch devices.
            While parked outside Edit mode it is a disabled status indicator, not a
            one-click release. */}
        <Tooltip>
          <TooltipTrigger asChild>
            {isParked && !canUnpark ? (
              // Status only: a `button` here would be a disabled one, and a disabled
              // button swallows the hover that surfaces the "why" tooltip.
              <span className="shrink-0 p-0.5 text-amber-600 dark:text-amber-400">
                <Lock className="size-3" />
              </span>
            ) : (
              <button
                onClick={(e) => {
                  e.stopPropagation()
                  handleParkToggle()
                }}
                // `aria-disabled` rather than `disabled`, for the reason the status arm above
                // gives: a disabled button swallows the hover that surfaces the "why" tooltip,
                // and "why" is the whole point when the desk is unreachable. `handleParkToggle`
                // is the actual guard.
                aria-disabled={!canUnpark && !canPark}
                className={`shrink-0 p-0.5 rounded transition-opacity hover:text-foreground ${
                  isParked
                    ? "text-amber-600 dark:text-amber-400 opacity-100"
                    : isEditing
                      ? "text-muted-foreground opacity-70 hover:opacity-100"
                      : "text-muted-foreground opacity-0 group-hover/channel:opacity-100 pointer-coarse:opacity-70"
                } ${!connected ? "cursor-not-allowed opacity-40" : ""}`}
              >
                {canUnpark ? <LockOpen className="size-3" /> : <Lock className="size-3" />}
              </button>
            )}
          </TooltipTrigger>
          <TooltipContent>{parkReason}</TooltipContent>
        </Tooltip>
      </div>
      <div className="flex items-center gap-1 ml-8 text-[10px] truncate">
        {mapping ? (
          <>
            <button
              className="text-muted-foreground hover:text-foreground hover:underline shrink-0"
              title={`${mapping.fixtureName}: ${mapping.description}`}
              onClick={() => onFixtureClick?.(mapping.fixtureKey)}
            >
              {mapping.fixtureName}
            </button>
            {mapping.description && (
              <>
                <span className="text-muted-foreground/50">·</span>
                <span className="text-muted-foreground/50 truncate">{mapping.description}</span>
              </>
            )}
          </>
        ) : (
          <span className="text-muted-foreground/30">Unmapped</span>
        )}
      </div>
    </div>
  )

  return (
    <ContextMenu>
      <ContextMenuTrigger asChild>
        <div>{channelContent}</div>
      </ContextMenuTrigger>
      <ContextMenuContent>
        {isParked ? (
          <ContextMenuItem
            disabled={!canUnpark}
            onClick={() => canUnpark && runUnparkChannel({ universe, channelNo: id })}
          >
            <LockOpen className="size-4" />
            {canUnpark ? "Unpark" : parkReason}
          </ContextMenuItem>
        ) : (
          <ContextMenuItem
            disabled={!canPark}
            onClick={() => canPark && runParkChannel({ universe, channelNo: id, value })}
          >
            <Lock className="size-4" />
            {parkReason}
          </ContextMenuItem>
        )}
      </ContextMenuContent>
    </ContextMenu>
  )
})

/**
 * `/projects/:projectId/channels` — no universe in the URL. Lands on the project's first
 * patched universe rather than universe 0: rigs don't necessarily start at 0, and sending
 * the sidebar's "Channels" entry to an unpatched universe shows 512 empty channels.
 *
 * The universe list arrives over the WebSocket, so an empty list means "not yet" for the
 * first moment after mount. [UNIVERSE_WAIT_MS] bounds that wait; past it, an empty list is
 * taken at face value and reported as "no universes" instead of silently redirecting
 * somewhere equally empty.
 */
const UNIVERSE_WAIT_MS = 3000

export function ProjectChannelsDefaultUniverse() {
  const { projectId } = useParams()
  const { data: universes } = useGetUniverseQuery()
  const [waitedForUniverses, setWaitedForUniverses] = useState(false)

  useEffect(() => {
    const timer = setTimeout(() => setWaitedForUniverses(true), UNIVERSE_WAIT_MS)
    return () => clearTimeout(timer)
  }, [])

  const firstUniverse = universes?.[0]

  if (firstUniverse !== undefined) {
    return <Navigate to={`/projects/${projectId}/channels/${firstUniverse}`} replace />
  }

  if (waitedForUniverses) {
    return (
      <Card className="m-4 p-4">
        <p className="text-muted-foreground">
          This project has no DMX universes. Add one in Project Settings → Universes.
        </p>
      </Card>
    )
  }

  return (
    <Card className="m-4 p-4 flex items-center justify-center">
      <Loader2 className="size-6 animate-spin" />
    </Card>
  )
}

// Redirect component for /channels (no universe) — hands off to the project-scoped
// route, which resolves the first patched universe.
export function ChannelsBaseRedirect() {
  const { data: currentProject, isLoading } = useCurrentProjectQuery()

  if (isLoading) {
    return (
      <Card className="m-4 p-4 flex items-center justify-center">
        <Loader2 className="size-6 animate-spin" />
      </Card>
    )
  }

  if (!currentProject) return <Navigate to="/projects" replace />

  return <Navigate to={`/projects/${currentProject.id}/channels`} replace />
}

// Redirect component for /channels/:universe route
export function ChannelsRedirect() {
  const { universe } = useParams()
  return <CurrentProjectRedirect to={`channels/${universe ?? 0}`} />
}

// Main ProjectChannels route component
export function ProjectChannels() {
  const { projectId, universe } = useParams()
  const projectIdNum = Number(projectId)
  const universeNum = Number(universe ?? 0)
  const { data: currentProject, isLoading: currentLoading } = useCurrentProjectQuery()
  const { data: project, isLoading: projectLoading } = useProjectQuery(projectIdNum)
  const location = useLocation()

  // If viewing a non-current project, redirect to the current project
  if (!currentLoading && currentProject && projectIdNum !== currentProject.id) {
    return <Navigate to={`/projects/${currentProject.id}/channels/${universeNum}`} replace />
  }

  // Sticky view: the sidebar's single "Channels" entry points here, so honour the last-used view
  // — exactly the Fixtures / Groups wiring (`ProjectFixtures`). The switcher's Cards segment both
  // rewrites the preference and tags its navigation with link state, so Cards stays reachable
  // even when the localStorage write fails.
  if (stickyRedirectsToList(location.state, CHANNELS_VIEW_KEY)) {
    return <Navigate to={`/projects/${projectIdNum}/channels/${universeNum}/table`} replace />
  }

  if (projectLoading || currentLoading) {
    return (
      <Card className="m-4 p-4 flex items-center justify-center">
        <Loader2 className="size-6 animate-spin" />
      </Card>
    )
  }

  if (!project) {
    return (
      <Card className="m-4 p-4">
        <p className="text-destructive">Project not found</p>
      </Card>
    )
  }

  return (
    <EditModeProvider>
      <ProjectChannelsContent projectId={projectIdNum} projectName={project.name} universe={universeNum} />
    </EditModeProvider>
  )
}

/** Hook to track the number of grid columns via ResizeObserver */
function useGridColumns(ref: React.RefObject<HTMLDivElement | null>) {
  const [columns, setColumns] = useState(1)

  useEffect(() => {
    const el = ref.current
    if (!el) return

    const observer = new ResizeObserver((entries) => {
      const width = entries[0]?.contentRect.width ?? 0
      // Match Tailwind breakpoints: xl:4, lg:3, md:2, default:1
      // These are container widths, not viewport — the grid is inside a Card with padding
      if (width >= 1100) setColumns(4)
      else if (width >= 800) setColumns(3)
      else if (width >= 500) setColumns(2)
      else setColumns(1)
    })

    observer.observe(el)
    return () => observer.disconnect()
  }, [ref])

  return columns
}

function ProjectChannelsContent({ projectId, projectName, universe }: { projectId: number; projectName: string; universe: number }) {
  const navigate = useNavigate()
  const { isEditing, toggleEditing } = useEditMode()
  // Every write on this page is a WebSocket frame — levels, park, unpark. Read once here and
  // handed down, rather than per row: a universe is 512 rows.
  const connected = useIsDeskConnected()
  const [selectedFixtureKey, setSelectedFixtureKey] = useState<string | null>(null)
  const [searchParams] = useSearchParams()
  const parkedParam = searchParams.get("parked") === "true"
  const [showParkedOnly, setShowParkedOnly] = useState(parkedParam)
  const [channelDialogMode, setChannelDialogMode] = useState<"park" | "set" | null>(null)

  // `?parked=true` can arrive at an already-mounted view — the command palette's "Go to
  // Parked Channel" navigates here from here. Seeding state at mount alone would make that
  // jump a no-op. One-way on purpose: the effect only ever switches the filter *on*, so
  // clicking "Show All" afterwards isn't immediately undone by the unchanged param.
  useEffect(() => {
    if (parkedParam) setShowParkedOnly(true)
  }, [parkedParam])

  // Lifted queries — single subscription for all mappings and park states
  const { data: parkStateList } = useGetParkStateListQuery()
  const { data: mappingRecord } = useGetChannelMappingListQuery()
  const { data: universes } = useGetUniverseQuery()
  const [runUnparkChannel] = useUnparkChannelMutation()

  const parkedChannelSet = useMemo(
    () => new Set(parkStateList?.filter((p) => p.universe === universe).map((p) => p.channel) ?? []),
    [parkStateList, universe],
  )
  const parkedCount = parkedChannelSet.size

  // Build per-channel park value lookup for this universe
  const parkValueMap = useMemo(() => {
    const map = new Map<number, number>()
    parkStateList?.filter((p) => p.universe === universe).forEach((p) => map.set(p.channel, p.value))
    return map
  }, [parkStateList, universe])

  // Channel mappings for this universe
  const universeMappings = mappingRecord?.[universe]

  // Bulk release is Edit-mode-only *and* confirmed — it is the single most destructive
  // park action on the page.
  const canUnpark = isEditing && parkedCount > 0 && connected

  const handleUnparkAll = () => {
    if (!canUnpark) return
    if (confirm(`Unpark all ${parkedCount} channel(s) in universe ${universe}?`)) {
      parkStateList
        ?.filter((p) => p.universe === universe)
        .forEach((p) => runUnparkChannel({ universe: p.universe, channelNo: p.channel }))
    }
  }

  return (
    <>
      <Card className="m-4 p-4">
        {/* `@container`: the view switcher's labels are a container query, and a missing ancestor
            would drop them silently. See ViewSwitcher's LABEL_AT_* constants. */}
        <div className="@container flex items-start justify-between gap-2 mb-4">
          <ChannelsBreadcrumbs projectName={projectName} />
          <div className="flex items-center gap-2">
            {/* Inline buttons — hidden on narrow viewports. Kept mounted while the filter
                is on even at zero parked channels: unparking the last one would otherwise
                take the "Show All" button away with it, leaving an empty grid and no way
                back to the full channel list. */}
            {(parkedCount > 0 || showParkedOnly) && (
              <>
                <Button
                  variant={showParkedOnly ? "default" : "outline"}
                  size="sm"
                  onClick={() => setShowParkedOnly(!showParkedOnly)}
                  className="gap-1.5 hidden sm:inline-flex"
                >
                  <Lock className="size-3.5" />
                  {showParkedOnly ? "Show All" : `Parked`}
                  <Badge variant="secondary" className="ml-0.5 px-1.5 py-0 text-[10px]">
                    {parkedCount}
                  </Badge>
                </Button>
                {canUnpark && (
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={handleUnparkAll}
                    className="hidden sm:inline-flex"
                  >
                    <LockOpen className="size-3.5" />
                    Unpark All
                  </Button>
                )}
              </>
            )}
            <Button
              variant="outline"
              size="sm"
              onClick={() => setChannelDialogMode("set")}
              disabled={!connected}
              title={connected ? undefined : DESK_OFFLINE_LABEL}
              className="hidden sm:inline-flex"
            >
              <SlidersHorizontal className="size-3.5" />
              Set Value
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => setChannelDialogMode("park")}
              disabled={!connected}
              title={connected ? undefined : DESK_OFFLINE_LABEL}
              className="hidden sm:inline-flex"
            >
              <Lock className="size-3.5" />
              Park at Value
            </Button>
            {/* Edit — always visible, icon-only on narrow */}
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant={isEditing ? "default" : "outline"}
                  size="icon"
                  className="size-8 sm:hidden"
                  onClick={toggleEditing}
                >
                  {isEditing ? <Check className="size-3.5" /> : <Pencil className="size-3.5" />}
                </Button>
              </TooltipTrigger>
              <TooltipContent>{isEditing ? "Done editing" : "Edit"}</TooltipContent>
            </Tooltip>
            <Button
              variant={isEditing ? "default" : "outline"}
              size="sm"
              onClick={toggleEditing}
              className="hidden sm:inline-flex"
            >
              {isEditing ? "Done" : "Edit"}
            </Button>

            <ChannelsViewSwitcher current="cards" projectId={projectId} universe={universe} />

            {/* Overflow menu — visible on narrow viewports */}
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" size="icon" className="size-8 sm:hidden">
                  <MoreHorizontal className="size-4" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuItem
                  disabled={!connected}
                  onClick={() => setChannelDialogMode("set")}
                >
                  <SlidersHorizontal className="size-4" />
                  Set Channel Value
                </DropdownMenuItem>
                <DropdownMenuItem
                  disabled={!connected}
                  onClick={() => setChannelDialogMode("park")}
                >
                  <Lock className="size-4" />
                  Park Channel at Value
                </DropdownMenuItem>
                {/* Same reason as the inline buttons above: stays available while the
                    filter is on so it can always be switched back off. */}
                {(parkedCount > 0 || showParkedOnly) && (
                  <>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem onClick={() => setShowParkedOnly(!showParkedOnly)}>
                      <Lock className="size-4" />
                      {showParkedOnly ? "Show All Channels" : `Show Parked (${parkedCount})`}
                    </DropdownMenuItem>
                    <DropdownMenuItem disabled={!canUnpark} onClick={handleUnparkAll}>
                      <LockOpen className="size-4" />
                      {canUnpark
                        ? `Unpark All (${parkedCount})`
                        : connected
                          ? "Unpark All — Edit mode only"
                          : "Unpark All — not connected to the desk"}
                    </DropdownMenuItem>
                  </>
                )}
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>
        {universes && universes.length > 1 && (
          <Tabs
            value={String(universe)}
            onValueChange={(v) => navigate(`/projects/${projectId}/channels/${v}`)}
            className="mb-4"
          >
            <TabsList>
              {universes.map((u) => (
                <TabsTrigger key={u} value={String(u)}>
                  Universe {u}
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>
        )}
        <ChannelGroups
          universe={universe}
          isEditing={isEditing}
          connected={connected}
          onFixtureClick={setSelectedFixtureKey}
          filterParked={showParkedOnly ? parkedChannelSet : undefined}
          universeMappings={universeMappings}
          parkValueMap={parkValueMap}
        />
      </Card>
      <FixtureDetailModal
        fixtureKey={selectedFixtureKey}
        onClose={() => setSelectedFixtureKey(null)}
        isEditing={isEditing}
      />
      <ChannelValueDialog
        open={channelDialogMode !== null}
        onOpenChange={(open) => { if (!open) setChannelDialogMode(null) }}
        mode={channelDialogMode ?? "set"}
      />
    </>
  )
}

// Breadcrumbs component — shared with the table sibling (`routes/ChannelsTable.tsx`).
export function ChannelsBreadcrumbs({ projectName }: { projectName: string }) {
  const navigate = useNavigate()

  return (
    <nav className="flex items-center gap-1 text-sm flex-wrap">
      <button
        onClick={() => navigate("/projects")}
        className="text-muted-foreground hover:text-foreground transition-colors"
      >
        Projects
      </button>
      <ChevronRight className="size-4 text-muted-foreground flex-shrink-0" />
      <button
        onClick={() => navigate("/projects")}
        className="text-muted-foreground hover:text-foreground transition-colors flex items-center gap-2"
      >
        {projectName}
        <Badge variant="default" className="text-xs">
          active
        </Badge>
      </button>
      <ChevronRight className="size-4 text-muted-foreground flex-shrink-0" />
      <span className="font-medium">Channels</span>
    </nav>
  )
}

const ChannelGroups = ({
  universe,
  isEditing,
  connected,
  onFixtureClick,
  filterParked,
  universeMappings,
  parkValueMap,
}: {
  universe: number
  isEditing: boolean
  /** The desk is reachable — see `ChannelSlider`, which every row takes it from. */
  connected: boolean
  onFixtureClick?: (fixtureKey: string) => void
  filterParked?: Set<number>
  universeMappings?: Record<number, ChannelMappingEntry>
  parkValueMap: Map<number, number>
}) => {
  const gridRef = useRef<HTMLDivElement>(null)
  const columns = useGridColumns(gridRef)

  // Filter groups when showing parked-only
  const visibleGroups = useMemo(() => {
    if (!filterParked) return CHANNEL_GROUPS
    return CHANNEL_GROUPS.filter((channels) => channels.some((ch) => filterParked.has(ch)))
  }, [filterParked])

  // Arrange groups into rows based on column count
  const rows = useMemo(() => {
    const result: number[][] = []
    for (let i = 0; i < visibleGroups.length; i += columns) {
      // Store the indices into visibleGroups for this row
      result.push(Array.from({ length: Math.min(columns, visibleGroups.length - i) }, (_, j) => i + j))
    }
    return result
  }, [visibleGroups, columns])

  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => gridRef.current,
    estimateSize: () => (isEditing ? 340 : 280),
    overscan: 3,
    measureElement: (el) => el.getBoundingClientRect().height,
  })

  // Invalidate cached measurements when edit mode changes row heights
  useEffect(() => {
    virtualizer.measure()
  }, [isEditing, virtualizer])

  return (
    <div
      ref={gridRef}
      className="overflow-y-auto"
      style={{ maxHeight: "calc(100vh - 10rem)" }}
    >
      <div
        style={{
          height: `${virtualizer.getTotalSize()}px`,
          width: "100%",
          position: "relative",
        }}
      >
        {virtualizer.getVirtualItems().map((virtualRow) => {
          const rowGroupIndices = rows[virtualRow.index]
          return (
            <div
              key={virtualRow.index}
              ref={virtualizer.measureElement}
              data-index={virtualRow.index}
              className="grid gap-4 absolute w-full"
              style={{
                gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`,
                transform: `translateY(${virtualRow.start}px)`,
                paddingBottom: "1rem",
              }}
            >
              {rowGroupIndices.map((groupIdx) => {
                const channels = visibleGroups[groupIdx]
                return (
                  <Card key={groupIdx} className="p-4">
                    {channels.map((channelNo) => {
                      if (filterParked && !filterParked.has(channelNo)) return null
                      return (
                        <ChannelSlider
                          key={channelNo}
                          universe={universe}
                          id={channelNo}
                          isEditing={isEditing}
                          connected={connected}
                          mapping={universeMappings?.[channelNo]}
                          parkedValue={parkValueMap.get(channelNo)}
                          onFixtureClick={onFixtureClick}
                        />
                      )
                    })}
                  </Card>
                )
              })}
            </div>
          )
        })}
      </div>
    </div>
  )
}
