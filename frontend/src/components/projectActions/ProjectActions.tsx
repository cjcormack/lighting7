import { Fragment, useMemo, useState, type ReactNode, type SetStateAction } from 'react'
import { useNavigate } from 'react-router'
import { MoreVertical } from 'lucide-react'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { cn } from '@/lib/utils'
import { formatError } from '@/lib/formatError'
import {
  menuGroupsOf,
  projectActions,
  type ProjectAction,
  type ProjectActionPlace,
  type ProjectDialog,
} from '@/lib/projectActions'
import type { ProjectSummary } from '@/api/projectApi'
import type { VenueHistory } from '@/api/venueApi'
import { useIsNavAdmin } from '@/navigation'
import { useDeleteProjectMutation, useProjectListQuery } from '@/store/projects'
import { useVenueHistoryQuery } from '@/store/venues'
import CloneProjectDialog from '@/CloneProjectDialog'
import DeleteProjectConfirmDialog from '@/DeleteProjectConfirmDialog'
import ExportProjectDialog from '@/ExportProjectDialog'
import ProjectSwitchConfirmDialog from '@/ProjectSwitchConfirmDialog'
import { AttachVenueSheet } from '../venue/AttachVenueSheet'
import { DetachVenueDialog } from '../venue/DetachVenueDialog'
import { ExtractVenueSheet } from '../venue/ExtractVenueSheet'
import { PublishVenueSheet } from '../venue/PublishVenueSheet'
import { VenueHistorySheet } from '../venue/VenueHistorySheet'
import { VenueUpdateSheet } from '../venue/VenueUpdateSheet'

type ActionProject = Pick<ProjectSummary, 'id' | 'name' | 'isCurrent' | 'kind' | 'venue'>

/**
 * One project's actions and the dialogs they open (navigation plan §6, P3): what the Projects card's
 * ⋮, the home's ⋮ and ⌘K's Project group render. The rules are `lib/projectActions.ts`'s; this is
 * the host — the navigation, the dialog state and the one venue read *Publish* needs.
 *
 * [onDeleted] runs after a delete lands, for a place that was showing the project (its home).
 */
export function useProjectActions(
  project: ActionProject | null | undefined,
  place: ProjectActionPlace,
  { onDeleted, skip = false }: { onDeleted?: () => void; skip?: boolean } = {},
): { actions: ProjectAction[]; dialogs: ReactNode } {
  const navigate = useNavigate()
  const isAdmin = useIsNavAdmin()
  const [dialog, setDialog] = useState<ProjectDialog | null>(null)
  // History is an admin's (the route answers an operator 403), and a show has none.
  const { data: venueHistory } = useVenueHistoryQuery(project?.id ?? 0, {
    skip: skip || !(project?.kind === 'VENUE' && isAdmin),
  })

  const actions = useMemo(
    () =>
      project == null
        ? []
        : projectActions(project, {
            place,
            isAdmin,
            venueHistory,
            host: { navigate: (path) => navigate(path), open: setDialog },
          }),
    [project, place, isAdmin, venueHistory, navigate],
  )

  const dialogs = project != null && (
    <ProjectActionDialogs
      project={project}
      dialog={dialog}
      onClose={() => setDialog(null)}
      venueHistory={venueHistory}
      onDeleted={onDeleted}
    />
  )
  return { actions, dialogs }
}

/**
 * The dialogs a project's actions open, for one project. Each is mounted only while its action has
 * asked for it, so a card in a list of thirty subscribes to nothing it is not showing.
 */
function ProjectActionDialogs({
  project,
  dialog,
  onClose,
  venueHistory,
  onDeleted,
}: {
  project: ActionProject
  dialog: ProjectDialog | null
  onClose: () => void
  venueHistory: VenueHistory | undefined
  onDeleted?: () => void
}) {
  const onOpenChange = (open: boolean) => {
    if (!open) onClose()
  }
  // The legacy dialogs take a state setter.
  const setOpenFor = (which: ProjectDialog) => (value: SetStateAction<boolean>) => {
    const next = typeof value === 'function' ? value(dialog === which) : value
    if (!next) onClose()
  }
  const venue = project.venue ?? null

  return (
    <>
      <ProjectSwitchConfirmDialog project={dialog === 'make-current' ? project : null} onClose={onClose} />
      {dialog === 'clone' && (
        <CloneProjectDialog open setOpen={setOpenFor('clone')} sourceProjectId={project.id} sourceProjectName={project.name} />
      )}
      {dialog === 'export' && (
        <ExportProjectDialog open setOpen={setOpenFor('export')} sourceProjectId={project.id} sourceProjectName={project.name} />
      )}
      {dialog === 'update-venue' && (
        <VenueUpdateSheet open onOpenChange={onOpenChange} projectId={project.id} showName={project.name} />
      )}
      {dialog === 'detach' && (
        <DetachVenueDialog
          open
          onOpenChange={onOpenChange}
          projectId={project.id}
          showName={project.name}
          venueName={venue?.name ?? 'its venue'}
        />
      )}
      {dialog === 'attach' && (
        <AttachVenueSheet open onOpenChange={onOpenChange} projectId={project.id} showName={project.name} />
      )}
      {dialog === 'extract' && <ExtractFromProject projectId={project.id} onOpenChange={onOpenChange} />}
      {dialog === 'history' && venueHistory && <VenueHistorySheet open onOpenChange={onOpenChange} history={venueHistory} />}
      {dialog === 'publish' && venueHistory && <PublishVenueSheet open onOpenChange={onOpenChange} history={venueHistory} />}
      {dialog === 'delete' && <DeleteProject project={project} onClose={onClose} onDeleted={onDeleted} />}
    </>
  )
}

function ExtractFromProject({ projectId, onOpenChange }: { projectId: number; onOpenChange: (open: boolean) => void }) {
  const { data: projects } = useProjectListQuery()
  // What an extract can start from: a show that is not on a venue already.
  const standalone = (projects ?? []).filter((p) => p.kind !== 'VENUE' && p.kind !== 'VENUE_VERSION' && p.venue == null)
  return <ExtractVenueSheet open onOpenChange={onOpenChange} shows={standalone} initialProjectId={projectId} />
}

function DeleteProject({
  project,
  onClose,
  onDeleted,
}: {
  project: ActionProject
  onClose: () => void
  onDeleted?: () => void
}) {
  const [deleteProject, { isLoading, error }] = useDeleteProjectMutation()
  const isVenue = project.kind === 'VENUE'
  // `deleteProject` is a silent endpoint: every refusal is said here, in the dialog.
  const onTheRig = error != null && !isVenue && 'status' in error && error.status === 409
  return (
    <DeleteProjectConfirmDialog
      open
      projectName={project.name}
      isCurrent={project.isCurrent}
      isDeleting={isLoading}
      error={error == null ? undefined : onTheRig ? 'Cannot delete the project on the rig' : formatError(error)}
      onConfirm={async () => {
        try {
          await deleteProject(project.id).unwrap()
        } catch {
          return
        }
        onClose()
        onDeleted?.()
      }}
      onCancel={onClose}
    />
  )
}

/**
 * The ⋮ menu: every visible action, in its group, a separator between groups. A refused action is
 * drawn disabled with its reason under its label — a disabled item takes no hover, so a title alone
 * would never be read.
 */
export function ProjectActionsMenu({
  actions,
  className,
  label,
}: {
  actions: readonly ProjectAction[]
  className?: string
  /** The trigger's accessible name. */
  label: string
}) {
  const groups = menuGroupsOf(actions)
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild onClick={(e) => e.stopPropagation()}>
        <Button variant="ghost" size="icon" className={className} aria-label={label} data-project-actions="">
          <MoreVertical className="size-4" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" onClick={(e) => e.stopPropagation()}>
        {groups.map((group, i) => (
          <Fragment key={group[0].group}>
            {i > 0 && <DropdownMenuSeparator />}
            {group.map((action) => (
              <DropdownMenuItem
                key={action.id}
                data-project-action={action.id}
                disabled={!action.enabled}
                title={action.reason ?? undefined}
                className={cn(action.destructive && 'text-destructive focus:text-destructive')}
                onSelect={action.enabled ? action.run : undefined}
              >
                <span className="flex flex-col">
                  {action.label}
                  {!action.enabled && action.reason && (
                    <span className="text-xs text-muted-foreground">{action.reason}</span>
                  )}
                </span>
              </DropdownMenuItem>
            ))}
          </Fragment>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
