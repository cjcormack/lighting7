import { useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router'
import { History, Upload } from 'lucide-react'
import { Button } from '@/components/ui/button'
import type { ProjectDetail, ProjectSummary } from '@/api/projectApi'
import { rigRowActions, type ProjectAction } from '@/lib/projectActions'
import { draftLineOf, venueShowsOf, venueVersionLineOf, type DraftTone, type VenueShowRow } from '@/lib/projectHome'
import { cn } from '@/lib/utils'
import { useIsNavAdmin } from '@/navigation'
import { useProjectListQuery } from '@/store/projects'
import { useVenueHistory } from '@/store/venues'
import { useVenueHold } from '@/store/venueHold'
import ProjectSwitchConfirmDialog from '@/ProjectSwitchConfirmDialog'
import { DiscardDraftDialog } from '../venue/DiscardDraftDialog'
import { VenueShowsList } from '../venue/VenueShowsList'
import { StatusRow, SyncRow } from './HomeParts'

/**
 * A venue's home under the shared header (navigation plan §5.2): its version, the draft and its
 * sync, then the shows on it. *History* and *Publish vN…* are the venue's own actions from
 * `projectActions`, opening the sheets the band and the ⋮ open; the band stays across the venue's
 * views, and this is History and Publish's second, permanent door. The draft is an admin's, as the
 * band's is: an operator reads the version and the shows.
 */
export function VenueStatus({ project, actions }: { project: ProjectDetail; actions: readonly ProjectAction[] }) {
  const isAdmin = useIsNavAdmin()
  // The band's own read, so the two count one draft.
  const { data: history } = useVenueHistory(project.id)
  const { data: projects } = useProjectListQuery()
  const summary = projects?.find((p) => p.id === project.id)?.venueSummary
  const historyAction = actions.find((a) => a.id === 'history' && a.visible)
  const publish = actions.find((a) => a.id === 'publish' && a.visible)
  // Neither read has landed: say so rather than that nothing is published.
  const reading = history == null && projects == null
  const versionLine = reading ? 'Reading…' : venueVersionLineOf(isAdmin ? history : null, summary)

  return (
    <dl className="divide-y rounded-lg border bg-card text-sm" data-project-status="">
      <StatusRow
        label="Version"
        fact="version"
        actions={historyAction && <ActionButton action={historyAction} icon={<History className="size-3.5" />} />}
      >
        {/* A note can run to a paragraph: two lines here, the whole of it on the hover and in History. */}
        <span
          className={cn('line-clamp-2 break-words', reading ? 'text-muted-foreground' : 'font-medium')}
          data-venue-version=""
          title={versionLine}
        >
          {versionLine}
        </span>
      </StatusRow>
      {isAdmin && history && <DraftRow venueId={project.id} history={history} publish={publish} />}
      {isAdmin && <SyncRow projectId={project.id} isVenue />}
    </dl>
  )
}

const DRAFT_TONE_CLASS: Record<DraftTone, string> = {
  none: 'text-muted-foreground',
  drawing: 'font-medium text-amber-700 dark:text-amber-300',
  output: 'font-medium text-orange-700 dark:text-orange-300',
}

function DraftRow({
  venueId,
  history,
  publish,
}: {
  venueId: number
  history: NonNullable<ReturnType<typeof useVenueHistory>['data']>
  publish?: ProjectAction
}) {
  const [discardOpen, setDiscardOpen] = useState(false)
  const line = draftLineOf(history)
  const installed = history.installed ?? null
  // History's rule: there is something to discard only once a version exists to go back to.
  const canDiscard = installed != null && history.draft.changes > 0
  // A draft emptied under an open dialog (another window published or discarded) closes it, so it
  // does not come back by itself when the draft refills.
  if (discardOpen && !canDiscard) setDiscardOpen(false)
  return (
    <StatusRow
      label="Draft"
      fact="draft"
      actions={
        <>
          {publish && <ActionButton action={publish} icon={<Upload className="size-3.5" />} primary />}
          {canDiscard && (
            <Button
              variant="outline"
              size="sm"
              className="h-7 text-destructive hover:text-destructive"
              onClick={() => setDiscardOpen(true)}
            >
              Discard
            </Button>
          )}
          {canDiscard && (
            <DiscardDraftDialog
              open={discardOpen}
              onOpenChange={setDiscardOpen}
              venueId={venueId}
              installed={installed}
              changes={history.draft.changes}
            />
          )}
        </>
      }
    >
      <span className={DRAFT_TONE_CLASS[line.tone]} data-venue-draft={line.tone}>
        {line.text}
      </span>
      {publish && !publish.enabled && publish.reason && (
        <span className="block text-xs text-muted-foreground" data-publish-refusal="">
          {publish.reason}
        </span>
      )}
    </StatusRow>
  )
}

/** One of the venue's actions as a line's button: refused, it is drawn disabled with the reason on its title. */
function ActionButton({ action, icon, primary = false }: { action: ProjectAction; icon: ReactNode; primary?: boolean }) {
  return (
    <Button
      variant={primary ? 'default' : 'outline'}
      size="sm"
      className="h-7"
      data-venue-action={action.id}
      disabled={!action.enabled}
      title={action.reason ?? undefined}
      onClick={action.run}
    >
      {icon}
      {action.label}
    </Button>
  )
}

/**
 * The shows on this venue (§5.2), from the project list, which says how far behind each is: each
 * with *Open* and *Make current* — the show's own *rig* actions, so a show whose pinned version is
 * not on this desk offers the refusal rather than a working press — through the one confirmation.
 */
export function VenueShows({ venueId }: { venueId: number }) {
  const navigate = useNavigate()
  const isAdmin = useIsNavAdmin()
  const { data: projects } = useProjectListQuery()
  const hold = useVenueHold()
  const [confirming, setConfirming] = useState<ProjectSummary | null>(null)
  const shows = venueShowsOf(venueId, projects, hold)

  const rowActions = (show: VenueShowRow) =>
    rigRowActions(show.project, {
      isAdmin,
      host: { navigate: (path) => navigate(path), open: () => setConfirming(show.project) },
    })

  return (
    <>
      <VenueShowsList
        shows={shows}
        loading={projects == null}
        className="rounded-lg border bg-card p-3"
        actions={(show) =>
          rowActions(show).map((action) =>
            action.enabled ? (
              <Button
                key={action.id}
                variant="outline"
                size="sm"
                className="h-7"
                data-venue-show-action={action.id}
                onClick={action.run}
              >
                {action.label}
              </Button>
            ) : (
              <span
                key={action.id}
                data-venue-show-refusal={action.id}
                className="text-xs text-muted-foreground"
                title={action.reason ?? undefined}
              >
                {action.refusalLabel ?? action.label}
              </span>
            ),
          )
        }
      />
      <ProjectSwitchConfirmDialog project={confirming} onClose={() => setConfirming(null)} />
    </>
  )
}
