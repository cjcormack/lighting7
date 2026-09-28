import { Suspense, useState } from 'react'
import { useParams, useLocation, Navigate } from 'react-router'
import { Card } from '@/components/ui/card'
import { Loader2 } from 'lucide-react'
import { useCurrentProjectQuery, useProjectQuery } from '../store/projects'
import { useGroupListQuery } from '../store/groups'
import { GroupCard } from '../components/groups/GroupCard'
import { FixtureDetailModal } from '../components/groups/FixtureDetailModal'
import { Breadcrumbs } from '../components/Breadcrumbs'
import { CurrentProjectRedirect } from '../components/CurrentProjectRedirect'
import {
  GROUPS_VIEW_KEY,
  GroupsViewSwitcher,
  stickyRedirectsToList,
} from '../components/ViewSwitcher'

// Redirect component for /groups route
export function GroupsRedirect() {
  return <CurrentProjectRedirect to="groups" />
}

// Main ProjectGroups route component
export function ProjectGroups() {
  const { projectId } = useParams()
  const projectIdNum = Number(projectId)
  const { data: currentProject, isLoading: currentLoading } = useCurrentProjectQuery()
  const { data: project, isLoading: projectLoading } = useProjectQuery(projectIdNum)
  const location = useLocation()

  // If viewing a non-current project, redirect to the current project
  if (!currentLoading && currentProject && projectIdNum !== currentProject.id) {
    return <Navigate to={`/projects/${currentProject.id}/groups`} replace />
  }

  // Sticky view: the sidebar's "Groups" entry points here, so honour the
  // last-used view. The switcher's Cards segment both rewrites the preference
  // and tags its navigation with link state, so Cards stays reachable even
  // when the localStorage write fails.
  if (stickyRedirectsToList(location.state, GROUPS_VIEW_KEY)) {
    return <Navigate to={`/projects/${projectIdNum}/groups/list`} replace />
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
      {/* `@container` — see ViewSwitcher's LABEL_AT_* constants. */}
      <div className="@container mb-4 flex items-center justify-between gap-3">
        <Breadcrumbs projectName={project.name} currentPage="Groups" />
        <GroupsViewSwitcher current="cards" projectId={projectIdNum} />
      </div>
      <Suspense fallback={<GroupsLoading />}>
        <GroupsContainer />
      </Suspense>
    </Card>
  )
}

function GroupsLoading() {
  return (
    <div className="flex justify-center py-8">
      <Loader2 className="size-6 animate-spin" />
    </div>
  )
}

function GroupsContainer() {
  const { data: groups, isLoading } = useGroupListQuery()
  const [selectedFixture, setSelectedFixture] = useState<string | null>(null)

  if (isLoading) {
    return <GroupsLoading />
  }

  if (!groups || groups.length === 0) {
    return (
      <div className="text-center text-muted-foreground py-8">
        No fixture groups configured.
      </div>
    )
  }

  return (
    <>
      <div className="grid gap-4" style={{ gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))' }}>
        {groups.map((group) => (
          <GroupCard
            key={group.name}
            group={group}
            onFixtureClick={setSelectedFixture}
          />
        ))}
      </div>

      <FixtureDetailModal
        fixtureKey={selectedFixture}
        onClose={() => setSelectedFixture(null)}
      />
    </>
  )
}
