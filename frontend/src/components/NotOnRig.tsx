import { useEffect, useState, type ReactNode } from 'react'
import { useLocation, useNavigate, useParams, type Params } from 'react-router'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { navItems } from '@/navigation'
import ProjectSwitchConfirmDialog from '@/ProjectSwitchConfirmDialog'
import { useProjectListQuery } from '@/store/projects'
import type { ProjectSummary } from '@/api/projectApi'
import { useHasUnsavedSheets } from '@/lib/unsavedSheets'
import { makeCurrentRefusal, venueMissing as isVenueMissing } from '@/lib/projectSwitch'
import { SheetPage } from './sheet/SheetPage'
import { ImmersiveEscape } from './ImmersiveEscape'

/**
 * What a view that needs the rig draws for a project that is not on it (navigation plan D4,
 * §3.2): the project's name, why the view needs the rig, the show that is on it, *Make current*
 * through the one switch confirmation, and a way to the same view on the show that is on it.
 *
 * "On the rig" is the server's `isCurrent` (P4). The route's URL is left alone — a `?cue=` deep
 * link survives, and *Make current* lands on exactly the address that was asked for.
 */
export function NotOnRig({
  project,
  navId,
  to,
  embedded = false,
}: {
  project: Pick<ProjectSummary, 'id' | 'name' | 'venue'>
  /** The [navItems] row whose view this stands in for; its `needsRig` is the sentence. */
  navId: string
  /** Where *Open …* goes on the project on the rig; the row's `path` when absent. */
  to?: (currentProjectId: number) => string
  /** Drawn inside another page (a settings tab) rather than as the route's whole page. */
  embedded?: boolean
}) {
  const navigate = useNavigate()
  const { data: projects } = useProjectListQuery()
  const [confirming, setConfirming] = useState(false)

  const item = navItems.find((i) => i.id === navId)
  const current = projects?.find((p) => p.isCurrent) ?? null
  const why = item?.needsRig ?? 'This view works on the rig that’s live'
  // A show whose venue is not on this desk cannot be made current (VENUE_MISSING).
  const venueMissing = isVenueMissing(project)
  const canOpenOnCurrent = item != null && current != null && viewOnProject(item.hiddenInVenue, current)

  const body = (
    <div className="mx-auto max-w-md space-y-3 text-center" data-not-on-rig="">
      <h2 className="text-base font-semibold">{project.name} isn&rsquo;t on the rig.</h2>
      <p className="text-sm text-muted-foreground">
        {why}, and {current ? <span className="font-medium text-foreground">{current.name}</span> : 'nothing'} is on it
        now.
      </p>
      {venueMissing && (
        <p className="text-sm text-muted-foreground">
          {makeCurrentRefusal(project)}, so it can&rsquo;t be made current.
        </p>
      )}
      <div className="flex flex-wrap justify-center gap-2 pt-1">
        {!venueMissing && (
          <Button onClick={() => setConfirming(true)}>Make {project.name} current</Button>
        )}
        {canOpenOnCurrent && (
          <Button variant="outline" onClick={() => navigate(to ? to(current.id) : item.path(current.id))}>
            Open {current.name}&rsquo;s {item.label}
          </Button>
        )}
      </div>
      {/* A refusal is toasted by `errorToastMiddleware`; the page stays as it is. */}
      <ProjectSwitchConfirmDialog project={confirming ? project : null} onClose={() => setConfirming(false)} />
    </div>
  )

  if (embedded) return <div className="p-6">{body}</div>

  return (
    <SheetPage>
      <SheetPage.Header>
        <span className="truncate text-sm font-medium">{item?.label}</span>
        <ImmersiveEscape bare />
      </SheetPage.Header>
      <div className="flex flex-1 items-center justify-center overflow-y-auto p-6">{body}</div>
    </SheetPage>
  )
}

/** Whether a view exists on [project] at all: a venue has none of the views a row hides in one. */
function viewOnProject(hiddenInVenue: boolean | undefined, project: Pick<ProjectSummary, 'kind'>): boolean {
  return !(hiddenInVenue && project.kind === 'VENUE')
}

/** A route's own address on another project, from this one's params and search. */
export type RigPath = (projectId: number, here: { params: Readonly<Params>; search: string }) => string

/**
 * The route half of D4: a `needsRig` view's route renders through this, which draws the view only
 * while the project in the URL is on the rig and [NotOnRig] otherwise, so none of the view's
 * queries run for a project that is not current. [to] is the route's own address on another
 * project — a list keeps its `?select=`, Channels its universe and table — for *Open …* and for
 * following a switch.
 *
 * **A view that was showing the project on the rig follows a switch made elsewhere** — the
 * Projects page, another window, MCP — to the same view on the new project, with `replace`, as the
 * routes' redirects to the current project once carried a desk screen. It waits while a sheet
 * holds unsaved edits (`windows.show`'s rule), keeping the view until the sheet is saved or let go.
 * A view opened on a project that is not on the rig never follows: that is D4's page.
 */
export function RigGate({ navId, to, children }: { navId: string; to?: RigPath; children: ReactNode }) {
  const params = useParams()
  const { search } = useLocation()
  const navigate = useNavigate()
  const id = Number(params.projectId)
  const { data: projects, isFetching, isError } = useProjectListQuery()
  const unsaved = useHasUnsavedSheets()

  const project = projects?.find((p) => p.id === id)
  const current = projects?.find((p) => p.isCurrent) ?? null
  const item = navItems.find((i) => i.id === navId)
  const onRig = project?.isCurrent === true

  // The project this gate last drew its view for while it was on the rig — adjusted during render,
  // so the follow below is decided on the render that first sees the switch.
  // Forgotten once the URL names another project off the rig, so a view opened there never follows.
  const [drewFor, setDrewFor] = useState<number | null>(null)
  if (onRig && drewFor !== id) setDrewFor(id)
  else if (!onRig && drewFor !== null && drewFor !== id) setDrewFor(null)

  const pathOn = (projectId: number) => (to ? to(projectId, { params, search }) : item?.path(projectId) ?? '/projects')
  const followTo =
    !onRig && drewFor === id && current != null && item != null && viewOnProject(item.hiddenInVenue, current)
      ? pathOn(current.id)
      : null

  useEffect(() => {
    if (followTo != null && !unsaved) navigate(followTo, { replace: true })
  }, [followTo, unsaved, navigate])

  if (projects == null) {
    return (
      <SheetPage>
        <SheetPage.Header>
          <ImmersiveEscape bare />
        </SheetPage.Header>
        {isError ? (
          <SheetPage.Empty className="text-destructive">Couldn&rsquo;t load the projects</SheetPage.Empty>
        ) : (
          <SheetPage.Empty loading />
        )}
      </SheetPage>
    )
  }
  // Never the view for an id the list does not hold — a venue version's snapshot is never listed —
  // since the view would draw the live rig under it.
  if (project == null) {
    return (
      <SheetPage>
        <SheetPage.Header>
          <ImmersiveEscape bare />
        </SheetPage.Header>
        {isFetching ? (
          <SheetPage.Empty loading />
        ) : (
          <SheetPage.Empty className="text-destructive">Project not found</SheetPage.Empty>
        )}
      </SheetPage>
    )
  }
  // Held while a switch is being followed, or while a sheet in it is unsaved.
  if (onRig || (drewFor === id && (followTo != null || unsaved))) return <>{children}</>
  return <NotOnRig project={project} navId={navId} to={pathOn} />
}

/**
 * D4's mark on a row, a tab or a card whose view needs the rig: a 6px hollow dot, where the Sync
 * row's attention dot sits, so it reads as "not here" rather than "needs you".
 */
export function OffRigDot({ className }: { className?: string }) {
  return (
    <span
      aria-hidden
      data-off-rig-dot=""
      className={cn('size-1.5 shrink-0 rounded-full border border-current', className)}
    />
  )
}
