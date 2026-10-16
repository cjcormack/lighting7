import { useEffect, useState, type ReactNode } from 'react'
import { Navigate, useNavigate, useParams } from 'react-router'
import { Loader2, RefreshCw, XCircle } from 'lucide-react'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Breadcrumbs } from '@/components/Breadcrumbs'
import { OffRigDot } from '@/components/NotOnRig'
import { useSyncNow } from '@/components/cloudSync/useSyncNow'
import { useProjectActions } from '@/components/projectActions/ProjectActions'
import { ProjectHomeHeader } from '@/components/projectHome/ProjectHomeHeader'
import { VenueOverview } from '@/components/projectHome/VenueOverview'
import { useHomeCounts } from '@/components/projectHome/useHomeCounts'
import { BEHIND_ICON_CLASS, BEHIND_TONE_CLASS, VenueBehindActions } from '@/components/venue/VenueBehindBanner'
import type { ProjectDetail } from '@/api/projectApi'
import { useProgrammerBlind } from '@/hooks/useProgrammerBlind'
import { liveCuesOf, looseCuesOf, syncLineOf, venueLineOf } from '@/lib/projectHome'
import { cn } from '@/lib/utils'
import { heldByVenue } from '@/lib/venueHold'
import {
  OFF_RIG_LABEL,
  filterNavItems,
  homeSectionsOf,
  navItems,
  useIsNavAdmin,
  type HomeCounts,
  type ShownNavItem,
} from '@/navigation'
import { useCueHolds } from '@/store/cueHolds'
import { useProjectCueStackListQuery } from '@/store/cueStacks'
import { useProjectCueListQuery } from '@/store/cues'
import { useCurrentProjectQuery, useProjectQuery } from '@/store/projects'
import { useVenueHold } from '@/store/venueHold'

/**
 * `/projects/:id`, a project's home (navigation plan D2, §5.1). A show's leads with its status —
 * on the rig or *Make current*, its venue, its sync, what is live — then the registry's sections as
 * cards. A venue keeps the overview's cards under the same header until its own home (§5.2).
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
        <VenueOverview project={project} />
      ) : (
        <>
          <StatusBlock project={project} attach={actions.find((a) => a.id === 'attach' && a.visible)} />
          <Sections project={project} />
        </>
      )}
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

function StatusRow({
  label,
  className,
  children,
  actions,
  fact,
}: {
  label: string
  className?: string
  children: ReactNode
  actions?: ReactNode
  fact: string
}) {
  return (
    <div className={cn('flex flex-wrap items-center gap-x-3 gap-y-1.5 px-3 py-2', className)} data-status-row={fact}>
      <dt className="w-14 shrink-0 text-[10px] font-bold uppercase tracking-wider text-muted-foreground">{label}</dt>
      {/* A basis rather than flex-1, so on a phone the buttons wrap under the words rather than squeeze them. */}
      <dd className="min-w-0 flex-[1_1_12rem]">{children}</dd>
      {actions && <dd className="flex max-w-full flex-wrap items-center gap-2">{actions}</dd>}
    </div>
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

function SyncRow({ projectId }: { projectId: number }) {
  const { config, run, isSyncing, disabledReason } = useSyncNow(projectId)
  const now = useMinuteClock()
  return (
    <StatusRow
      label="Sync"
      fact="sync"
      actions={
        config?.repoUrl != null && (
          <Button
            variant="outline"
            size="sm"
            className="h-7"
            disabled={disabledReason != null || isSyncing}
            onClick={run}
          >
            <RefreshCw className={cn('size-3.5', isSyncing && 'animate-spin')} />
            {isSyncing ? 'Syncing…' : 'Sync now'}
          </Button>
        )
      }
    >
      <span className={cn('min-w-0 break-words', config?.synced !== true && 'text-muted-foreground')}>
        {config ? syncLineOf(config, now) : 'Reading…'}
      </span>
      {config?.repoUrl != null && disabledReason != null && (
        <span className="block text-xs text-muted-foreground" data-sync-refusal="">
          {disabledReason}
        </span>
      )}
    </StatusRow>
  )
}

/** The time, moved on once a minute, for *2 min ago*. */
function useMinuteClock(): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 60_000)
    return () => clearInterval(timer)
  }, [])
  return now
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

/** The registry's sections less Desk, as cards — the sidebar's rows for this project, never a hand-picked list. */
function Sections({ project }: { project: ProjectDetail }) {
  const isAdmin = useIsNavAdmin()
  const counts = useHomeCounts(project)
  const sections = homeSectionsOf(filterNavItems(navItems, project.isCurrent, isAdmin, project.kind === 'VENUE'))
  return (
    <div className="space-y-5">
      {sections.map((section) => (
        <section key={section.section} aria-labelledby={`home-section-${section.section}`} data-home-section={section.section}>
          <h2
            id={`home-section-${section.section}`}
            className="mb-2 text-xs font-bold uppercase tracking-wider text-muted-foreground"
          >
            {section.label}
          </h2>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
            {section.items.map((item) => (
              <SectionCard key={item.id} item={item} projectId={project.id} counts={counts} />
            ))}
          </div>
        </section>
      ))}
    </div>
  )
}

function SectionCard({ item, projectId, counts }: { item: ShownNavItem; projectId: number; counts: HomeCounts }) {
  const navigate = useNavigate()
  const count = item.homeCount?.(counts)
  return (
    <button
      type="button"
      data-home-card={item.id}
      data-off-rig={item.offRig ? '' : undefined}
      title={item.offRig ? OFF_RIG_LABEL : undefined}
      onClick={() => navigate(item.path(projectId))}
      className={cn(
        'flex min-h-16 flex-col items-start gap-1 rounded-lg border bg-card p-3 text-left transition-colors hover:bg-accent/50',
        item.offRig && 'text-muted-foreground',
      )}
    >
      <span className="flex w-full items-center gap-2 text-sm font-medium">
        <item.icon className={cn('size-4 shrink-0', !item.offRig && 'text-muted-foreground')} />
        <span className="min-w-0 flex-1 truncate">{item.label}</span>
        {item.offRig && <OffRigDot />}
      </span>
      {item.offRig ? (
        <span className="text-xs">{OFF_RIG_LABEL}</span>
      ) : (
        count != null && (
          <span className="text-xs tabular-nums text-muted-foreground" data-home-count="">
            {count}
          </span>
        )
      )}
    </button>
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
