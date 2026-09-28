import React, { Suspense, useState, useMemo, createContext, useContext } from "react"
import { useParams, useLocation, Navigate } from "react-router"
import { Card, CardContent, CardHeader, CardTitle, CardAction } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { FIXTURE_FILTER_HINT, FIXTURE_FILTER_PLACEHOLDER } from '@/lib/fixtureFilterCopy'
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { Tooltip, TooltipTrigger, TooltipContent } from "@/components/ui/tooltip"
import { Search, Loader2, Settings2, SlidersHorizontal, Pencil, Check } from "lucide-react"
import { Fixture, useVisibleFixtureListQuery } from "../store/fixtures"
import { filterTerms, fixtureMatchesTerms } from "../lib/fixtureSearch"
import { EditModeProvider, useEditMode } from "../components/fixtures/EditModeContext"
import { FxBadge } from "../components/fx/FxBadge"
import { FixtureParkButton } from "../components/fixtures/FixtureParkButton"
import { LocateButton } from "../components/fixtures/LocateButton"
import { Breadcrumbs } from "../components/Breadcrumbs"
import {
  FIXTURES_VIEW_KEY,
  FixturesViewSwitcher,
  stickyRedirectsToList,
} from "../components/ViewSwitcher"
import { FixtureContent, FixtureViewMode } from "../components/fixtures/FixtureContent"
import { GroupDetailModal } from "../components/fixtures/GroupDetailModal"
import { useCurrentProjectQuery, useProjectQuery } from "../store/projects"
import { CurrentProjectRedirect } from "../components/CurrentProjectRedirect"

// Context for global view mode
const ViewModeContext = createContext<FixtureViewMode>('properties')
const useViewMode = () => useContext(ViewModeContext)

// Redirect component for /fixtures route
export function FixturesRedirect() {
  return <CurrentProjectRedirect to="fixtures" />
}

// Main ProjectFixtures route component
export function ProjectFixtures() {
  const { projectId } = useParams()
  const projectIdNum = Number(projectId)
  const { data: currentProject, isLoading: currentLoading } = useCurrentProjectQuery()
  const { data: project, isLoading: projectLoading } = useProjectQuery(projectIdNum)
  const location = useLocation()

  // If viewing a non-current project, redirect to the current project
  if (!currentLoading && currentProject && projectIdNum !== currentProject.id) {
    return <Navigate to={`/projects/${currentProject.id}/fixtures`} replace />
  }

  // Sticky view: the sidebar's single "Fixtures" entry points here, so honour
  // the last-used view. The switcher's Cards segment both rewrites the
  // preference and tags its navigation with link state, so Cards stays
  // reachable even when the localStorage write fails.
  if (stickyRedirectsToList(location.state, FIXTURES_VIEW_KEY)) {
    return <Navigate to={`/projects/${projectIdNum}/fixtures/list`} replace />
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
    <Card className="m-4 p-4">
      {/* `@container`: the view switcher's labels are a container query, and a missing ancestor
          would drop them silently. See ViewSwitcher's LABEL_AT_* constants. */}
      <div className="@container mb-4 flex items-center justify-between gap-3">
        <Breadcrumbs projectName={project.name} currentPage="Fixtures" />
        <FixturesViewSwitcher current="cards" projectId={projectIdNum} />
      </div>
      <Suspense fallback={<div>Loading...</div>}>
        <FixturesContainer />
      </Suspense>
    </Card>
  )
}

function FixturesContainer() {
  const { data: maybeFixtureList, isLoading } = useVisibleFixtureListQuery()
  const [filter, setFilter] = useState("")
  const [viewMode, setViewMode] = useState<FixtureViewMode>('properties')

  const fixtureList = useMemo(() => maybeFixtureList ?? [], [maybeFixtureList])

  const filteredFixtures = useMemo(() => {
    const terms = filterTerms(filter)
    if (terms.length === 0) return fixtureList
    return fixtureList.filter((fixture) => fixtureMatchesTerms(fixture, terms))
  }, [fixtureList, filter])

  if (isLoading) {
    return <div>Loading...</div>
  }

  return (
    <ViewModeContext.Provider value={viewMode}>
      {/* Search and view toggle */}
      <div className="flex gap-2 mb-4">
        <div className="relative flex-1">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 h-4 w-4 text-muted-foreground" />
          <Input
            placeholder={FIXTURE_FILTER_PLACEHOLDER}
            title={FIXTURE_FILTER_HINT}
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            className="pl-9"
          />
        </div>
        <ToggleGroup
          type="single"
          value={viewMode}
          onValueChange={(value) => value && setViewMode(value as FixtureViewMode)}
          className="shrink-0"
        >
          <ToggleGroupItem value="properties" aria-label="Show properties" title="Properties">
            <Settings2 className="h-4 w-4" />
          </ToggleGroupItem>
          <ToggleGroupItem value="channels" aria-label="Show channels" title="Channels">
            <SlidersHorizontal className="h-4 w-4" />
          </ToggleGroupItem>
        </ToggleGroup>
      </div>

      <AllFixturesView
        fixtureList={fixtureList}
        filteredFixtures={filteredFixtures}
      />
    </ViewModeContext.Provider>
  )
}

function AllFixturesView({
  fixtureList,
  filteredFixtures,
}: {
  fixtureList: Fixture[]
  filteredFixtures: Fixture[]
}) {
  const [selectedGroup, setSelectedGroup] = useState<string | null>(null)

  if (filteredFixtures.length === 0) {
    return (
      <p className="text-muted-foreground text-center py-8">
        {fixtureList.length === 0
          ? "No fixtures available"
          : "No fixtures match your filter"}
      </p>
    )
  }

  return (
    <>
      <div className="grid grid-cols-[repeat(auto-fill,minmax(min(340px,100%),1fr))] gap-4">
        {filteredFixtures.map((fixture) => (
          <FixtureCard fixture={fixture} key={fixture.key} onGroupClick={setSelectedGroup} />
        ))}
      </div>
      <GroupDetailModal
        groupName={selectedGroup}
        onClose={() => setSelectedGroup(null)}
      />
    </>
  )
}

const FixtureCard = React.memo(function FixtureCard({ fixture, onGroupClick }: { fixture: Fixture; onGroupClick: (groupName: string) => void }) {
  return (
    <EditModeProvider>
      <Card>
        <FixtureCardHeader fixture={fixture} />
        <CardContent>
          <FixtureCardContent fixture={fixture} onGroupClick={onGroupClick} />
        </CardContent>
      </Card>
    </EditModeProvider>
  )
})

function FixtureCardContent({
  fixture,
  onGroupClick,
}: {
  fixture: Fixture
  onGroupClick: (groupName: string) => void
}) {
  const { isEditing } = useEditMode()
  const viewMode = useViewMode()

  return (
    <FixtureContent
      fixture={fixture}
      isEditing={isEditing}
      onGroupClick={onGroupClick}
      viewMode={viewMode}
    />
  )
}

function FixtureCardHeader({ fixture }: { fixture: Fixture }) {
  const { isEditing, toggleEditing } = useEditMode()
  const hasElements = (fixture.elements?.length ?? 0) > 0

  return (
    <CardHeader className="pb-2">
      <CardTitle className="text-lg truncate">{fixture.name}</CardTitle>
      {(fixture.manufacturer || fixture.model) && (
        <p className="text-xs text-muted-foreground truncate">
          {[fixture.manufacturer, fixture.model].filter(Boolean).join(" ")}
        </p>
      )}
      <CardAction className="flex items-center gap-1">
        <LocateButton type="fixture" targetKey={fixture.key} name={fixture.name} iconOnly />
        <FixtureParkButton fixture={fixture} isEditing={isEditing} iconOnly />
        <Tooltip>
          <TooltipTrigger asChild>
            <Button
              variant={isEditing ? "default" : "outline"}
              size="icon"
              className="size-8"
              onClick={toggleEditing}
            >
              {isEditing ? <Check className="size-3.5" /> : <Pencil className="size-3.5" />}
            </Button>
          </TooltipTrigger>
          <TooltipContent>{isEditing ? "Done editing" : "Edit"}</TooltipContent>
        </Tooltip>
      </CardAction>
      <div className="flex flex-wrap gap-1">
        {hasElements && (
          <Badge variant="secondary" className="text-xs">
            {fixture.elements!.length} heads
          </Badge>
        )}
        {fixture.mode && (
          <Badge variant="outline" className="text-xs">
            {fixture.mode.modeName}
          </Badge>
        )}
        {fixture.capabilities?.map((cap) => (
          <Badge key={cap} variant="outline" className="text-xs capitalize">
            {cap}
          </Badge>
        ))}
        <FxBadge fixtureKey={fixture.key} fixtureGroups={fixture.groups} />
      </div>
    </CardHeader>
  )
}

