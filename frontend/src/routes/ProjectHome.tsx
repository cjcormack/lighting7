import { Navigate, useNavigate, useParams } from 'react-router'
import { Loader2, XCircle } from 'lucide-react'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Breadcrumbs } from '@/components/Breadcrumbs'
import { useProjectActions } from '@/components/projectActions/ProjectActions'
import { Sections, StatusRow, SyncRow } from '@/components/projectHome/HomeParts'
import { ProjectHomeHeader } from '@/components/projectHome/ProjectHomeHeader'
import { VenueShows, VenueStatus } from '@/components/projectHome/VenueHome'
import { BEHIND_ICON_CLASS, BEHIND_TONE_CLASS, VenueBehindActions } from '@/components/venue/VenueBehindBanner'
import type { ProjectDetail } from '@/api/projectApi'
import { useProgrammerBlind } from '@/hooks/useProgrammerBlind'
import { liveCuesOf, looseCuesOf, venueLineOf } from '@/lib/projectHome'
import { cn } from '@/lib/utils'
import { heldByVenue } from '@/lib/venueHold'
import { useIsNavAdmin } from '@/navigation'
import { useCueHolds } from '@/store/cueHolds'
import { useProjectCueStackListQuery } from '@/store/cueStacks'
import { useProjectCueListQuery } from '@/store/cues'
import { useCurrentProjectQuery, useProjectQuery } from '@/store/projects'
import { useVenueHold } from '@/store/venueHold'

/**
 * `/projects/:id`, a project's home (navigation plan D2, §5.1, §5.2), under one header and ⋮. A
 * show's leads with its status — on the rig or *Make current*, its venue, its sync, what is live; a
 * venue's with its version, its draft and its sync, then the shows on it. Both then draw the
 * registry's sections as cards.
 */
export default function ProjectHome() {
  const { projectId } = useParams<{ projectId: string }>()
  const navigate = useNavigate()
  const { data: project, isLoading, error } = useProjectQuery(Number(projectId), { skip: !projectId })
  const { actions, dialogs } = useProjectActions(project, 'home', { onDeleted: () => navigate('/projects') })

  if (!projectId) return <Navigate to="/projects" replace />

  if (isLoading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <Loader2 className="size-8 animate-spin text-muted-foreground" />
      </div>
    )
  }

  if (error || !project) {
    return (
      <div className="p-4">
        <Alert variant="destructive">
          <XCircle className="size-4" />
          <AlertDescription>Failed to load project</AlertDescription>
        </Alert>
      </div>
    )
  }

  return (
    <div className="space-y-6 p-4" data-project-home={project.kind === 'VENUE' ? 'venue' : 'show'}>
      <Breadcrumbs projectName={project.name} />
      <ProjectHomeHeader project={project} actions={actions} />
      {project.kind === 'VENUE' ? (
        <>
          <VenueStatus project={project} actions={actions} />
          <VenueShows venueId={project.id} />
        </>
      ) : (
        <StatusBlock project={project} attach={actions.find((a) => a.id === 'attach' && a.visible)} />
      )}
      <Sections project={project} />
      {dialogs}
    </div>
  )
}

/** One line per fact: Venue, Sync (an admin's) and, on the rig, Live. */
function StatusBlock({ project, attach }: { project: ProjectDetail; attach?: { label: string; reason: string | null } }) {
  const isAdmin = useIsNavAdmin()
  return (
    <dl className="divide-y rounded-lg border bg-card text-sm" data-project-status="">
      <VenueRow project={project} attach={attach} />
      {isAdmin && <SyncRow projectId={project.id} />}
      {project.isCurrent && <LiveRow project={project} />}
    </dl>
  )
}

function VenueRow({ project, attach }: { project: ProjectDetail; attach?: { label: string; reason: string | null } }) {
  const navigate = useNavigate()
  const hold = useVenueHold()
  const line = venueLineOf(project, hold)
  if (line == null) {
    return (
      <StatusRow
        label="Venue"
        fact="venue"
        actions={
          attach && (
            <>
              <Button variant="outline" size="sm" className="h-7" disabled>
                {attach.label}
              </Button>
              {attach.reason && <span className="text-xs text-muted-foreground">{attach.reason}</span>}
            </>
          )
        }
      >
        <span className="text-muted-foreground">No venue</span>
      </StatusRow>
    )
  }
  const venueId = project.venue?.id
  return (
    <StatusRow
      label="Venue"
      fact="venue"
      className={cn(line.tone !== 'neutral' && BEHIND_TONE_CLASS[line.tone])}
      actions={
        <>
          {line.banner && <VenueBehindActions projectId={project.id} showName={project.name} banner={line.banner} />}
          {venueId != null && (
            <Button variant="outline" size="sm" className="h-7" onClick={() => navigate(`/projects/${venueId}`)}>
              Open venue
            </Button>
          )}
        </>
      }
    >
      <span className={cn('font-medium', line.tone !== 'neutral' && BEHIND_ICON_CLASS[line.tone])} data-venue-line={line.tone}>
        {line.text}
      </span>
    </StatusRow>
  )
}

function LiveRow({ project }: { project: ProjectDetail }) {
  const { data: stacks } = useProjectCueStackListQuery(project.id)
  const { data: cues } = useProjectCueListQuery(project.id)
  const loose = looseCuesOf(useCueHolds(project.id), cues)
  const blind = useProgrammerBlind()
  const hold = useVenueHold()
  const held = heldByVenue(hold, project.id)
  const live = liveCuesOf(stacks)
  return (
    <StatusRow label="Live" fact="live">
      <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
        {live.length === 0 && loose.length === 0 ? (
          <span className="text-muted-foreground">No cue live</span>
        ) : (
          <>
            {live.map((cue) => (
              <span key={cue.stackId} data-live-cue={cue.stackId}>
                <span className="text-muted-foreground">{cue.stack}</span> {cue.cue}
              </span>
            ))}
            {loose.map((name, i) => (
              <span key={`loose-${i}`} data-loose-cue="">
                <span className="text-muted-foreground">Loose</span> {name}
              </span>
            ))}
          </>
        )}
        {blind && (
          <span className="rounded border border-amber-500/60 bg-amber-500/15 px-1.5 text-xs font-semibold text-amber-700 dark:text-amber-300">
            Blind
          </span>
        )}
        {held && (
          <span className="rounded border border-red-600/40 bg-red-500/10 px-1.5 text-xs font-semibold text-red-700 dark:text-red-300">
            Held by its venue
          </span>
        )}
      </span>
    </StatusRow>
  )
}

/** `/`: the project on the rig's home, else the projects list. */
export function ProjectHomeRedirect() {
  const { data: currentProject, isLoading } = useCurrentProjectQuery()
  if (isLoading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <Loader2 className="size-8 animate-spin text-muted-foreground" />
      </div>
    )
  }
  if (currentProject) return <Navigate to={`/projects/${currentProject.id}`} replace />
  return <Navigate to="/projects" replace />
}
