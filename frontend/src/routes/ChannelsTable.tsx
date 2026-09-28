import { useEffect, useMemo } from "react"
import { useParams, useNavigate, Navigate } from "react-router"
import { SheetPage } from "@/components/sheet/SheetPage"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { Lock, LockOpen } from "lucide-react"
import { useGetChannelMappingListQuery } from "../store/channelMapping"
import { useGetParkStateListQuery, useUnparkChannelMutation } from "../store/park"
import { useCurrentProjectQuery, useProjectQuery } from "../store/projects"
import { useIsDeskConnected } from "../store/status"
import { DESK_OFFLINE_LABEL } from "../api/wsGesture"
import { useGetUniverseQuery } from "../store/universes"
import { CHANNELS_VIEW_KEY, ChannelsViewSwitcher, setStoredCardsListView } from "../components/ViewSwitcher"
import { DmxSheet } from "../components/channels/DmxSheet"
import { ChannelsBreadcrumbs } from "./Channels"

/**
 * `/projects/:projectId/channels/:universe/table` — the DMX sheet, the cards route's sibling
 * (CLAUDE.md §Sheet kit). Wired exactly like `/fixtures/list`: this route writes the sticky
 * preference on mount, and the cards route redirects here when the sticky says so.
 */
export function ProjectChannelsTable() {
  const { projectId, universe } = useParams()
  const projectIdNum = Number(projectId)
  const universeNum = Number(universe ?? 0)
  const { data: currentProject, isLoading: currentLoading } = useCurrentProjectQuery()
  const { data: project, isLoading: projectLoading } = useProjectQuery(projectIdNum)

  // Record this as the last-used channels view even when arriving by deep link, so the sidebar's
  // "Channels" entry keeps landing here.
  useEffect(() => {
    setStoredCardsListView(CHANNELS_VIEW_KEY, 'list')
  }, [])

  if (!currentLoading && currentProject && projectIdNum !== currentProject.id) {
    return <Navigate to={`/projects/${currentProject.id}/channels/${universeNum}/table`} replace />
  }

  // Loading and not-found keep the list's shape (CLAUDE.md §List shell): the same header row,
  // the body centred on the spinner or the sentence.
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

  return <ChannelsTableContent projectId={projectIdNum} projectName={project.name} universe={universeNum} />
}

function ChannelsTableContent({ projectId, projectName, universe }: { projectId: number; projectName: string; universe: number }) {
  const navigate = useNavigate()
  const connected = useIsDeskConnected()
  const { data: parkStateList } = useGetParkStateListQuery()
  const { data: mappingRecord } = useGetChannelMappingListQuery()
  const { data: universes } = useGetUniverseQuery()
  const [runUnparkChannel] = useUnparkChannelMutation()

  const parkValueMap = useMemo(() => {
    const map = new Map<number, number>()
    parkStateList?.filter((p) => p.universe === universe).forEach((p) => map.set(p.channel, p.value))
    return map
  }, [parkStateList, universe])
  const parkedCount = parkValueMap.size

  // Unpark All keeps its confirm dialog — the single most destructive park action on the page —
  // but not the cards' Edit gate: the table has no Edit mode, so the confirmation is the gate.
  const canUnpark = parkedCount > 0 && connected
  const handleUnparkAll = () => {
    if (!canUnpark) return
    if (confirm(`Unpark all ${parkedCount} channel(s) in universe ${universe}?`)) {
      parkStateList
        ?.filter((p) => p.universe === universe)
        .forEach((p) => runUnparkChannel({ universe: p.universe, channelNo: p.channel }))
    }
  }

  /**
   * **Full height, and one scroller.** The cards view is a `Card` in a scrolling page, which is
   * right for a page of cards; for the sheet it meant the page scrolled *and* the table scrolled
   * inside a `calc(100vh - 14rem)` cap — two bars for one list, and a 393px-tall landscape phone
   * got 169px of grid. The list shell instead (CLAUDE.md §List shell): a flex column that fills
   * `<main>`, a 48px header row, and the sheet taking the rest. That also closes the gap the
   * breadcrumbs left above the header, which was the `Card`'s padding plus two 16px margins around
   * a selection bar that usually says "Nothing selected". This page was the reference shape the
   * shell was drawn from; what it gained from the shell is the one line under the bar (the sheet
   * drew a second) and the legend's swatch.
   */
  return (
    <SheetPage>
      {/* The breadcrumb header — 48px, like `StackDetail`'s, **not** one of the 40px chrome rows
          (CLAUDE.md §the programmer's chrome): this row carries the page's identity. The
          `ShowHeader` did the same job at 48 until the busk-chrome plan's session B made it a 40px
          chrome row (D12); this one and `StackDetail`'s stay at 48. */}
      <SheetPage.Header>
        <ChannelsBreadcrumbs projectName={projectName} />
        <div className="flex-1" />
        {parkedCount > 0 && (
          <>
            <Badge variant="secondary" className="gap-1 px-1.5 py-0 text-[10px]">
              <Lock className="size-3" />
              {parkedCount} parked
            </Badge>
            <Button
              variant="outline"
              size="sm"
              onClick={handleUnparkAll}
              disabled={!canUnpark}
              title={connected ? undefined : DESK_OFFLINE_LABEL}
              // The word folds on a phone, so the name has to come from somewhere else: a lucide
              // glyph carries none, and without this the button is announced unnamed at the width
              // it is hardest to identify by sight.
              aria-label="Unpark All"
            >
              <LockOpen className="size-3.5" />
              <span className="hidden sm:inline">Unpark All</span>
            </Button>
          </>
        )}
        <ChannelsViewSwitcher current="list" projectId={projectId} universe={universe} />
      </SheetPage.Header>
      {universes && universes.length > 1 && (
        <Tabs
          value={String(universe)}
          onValueChange={(v) => navigate(`/projects/${projectId}/channels/${v}/table`)}
          className="shrink-0 border-b px-3 py-1.5"
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
      <DmxSheet
        universe={universe}
        connected={connected}
        mappings={mappingRecord?.[universe]}
        parkValueMap={parkValueMap}
      />
    </SheetPage>
  )
}
