import { Suspense, useEffect } from 'react'
import { Navigate, useLocation, useParams } from 'react-router'
import { SheetPage } from '@/components/sheet/SheetPage'
import { Breadcrumbs } from '../components/Breadcrumbs'
import {
  GROUPS_VIEW_KEY,
  GroupsViewSwitcher,
  setStoredCardsListView,
} from '../components/ViewSwitcher'
import { useCurrentProjectQuery, useProjectQuery } from '../store/projects'
import { FixturesListContainer } from '../components/fixtures-list/FixturesListContainer'
import { CurrentProjectRedirect } from '../components/CurrentProjectRedirect'

// Redirect component for the bare /groups/list route
export function GroupsListRedirect() {
  return <CurrentProjectRedirect to="groups/list" preserveSearch />
}

export function ProjectGroupsList() {
  const { projectId } = useParams()
  const projectIdNum = Number(projectId)
  const { search } = useLocation()
  const { data: currentProject, isLoading: currentLoading } = useCurrentProjectQuery()
  const { data: project, isLoading: projectLoading } = useProjectQuery(projectIdNum)

  // Record this as the last-used groups view even when arriving via a deep
  // link (Cmd+K ?select=) rather than the switcher, so the sidebar's "Groups"
  // entry keeps landing here.
  useEffect(() => {
    setStoredCardsListView(GROUPS_VIEW_KEY, 'list')
  }, [])

  if (!currentLoading && currentProject && projectIdNum !== currentProject.id) {
    // Carry ?select= across, matching GroupsListRedirect — a shared link's
    // deep-link target shouldn't be dropped just because the project id in
    // the URL wasn't the active one.
    return <Navigate to={`/projects/${currentProject.id}/groups/list${search}`} replace />
  }

  // Loading and not-found keep the list's shape (CLAUDE.md §List shell): the same header row,
  // the body centred on the spinner or the sentence, so nothing changes shape when the list
  // arrives.
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

  // The list shell (CLAUDE.md §List shell): the header row with the breadcrumbs and the view
  // switcher, then the container's own toolbar row and selection bar, the grid as the page's one
  // scroller, and the footer with the count. No Card and no page scroll — the sheet is recessed
  // on the page ground, on the same rows as every other list.
  return (
    <SheetPage>
      <SheetPage.Header>
        <Breadcrumbs projectName={project.name} currentPage="Groups" />
        <span className="flex-1" />
        <GroupsViewSwitcher current="list" projectId={projectIdNum} />
      </SheetPage.Header>
      <Suspense fallback={<SheetPage.Empty loading />}>
        <FixturesListContainer
          grouped
          selectionScope="groups"
          renderFooter={({ fixtureCount, groupCount, selectedCount }) => (
            <SheetPage.Footer>
              <span className="tabular-nums">
                {groupCount} group{groupCount === 1 ? '' : 's'} · {fixtureCount} fixture
                {fixtureCount === 1 ? '' : 's'}
                {selectedCount > 0 ? ` · ${selectedCount} selected` : ''}
              </span>
            </SheetPage.Footer>
          )}
        />
      </Suspense>
    </SheetPage>
  )
}
