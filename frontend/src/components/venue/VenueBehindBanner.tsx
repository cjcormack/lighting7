import { useState } from 'react'
import { EyeOff, Landmark, Play } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { formatError } from '@/lib/formatError'
import { cn } from '@/lib/utils'
import { bannerMessage, behindBanner, laterKey } from '@/lib/venueHold'
import { useProjectQuery } from '@/store/projects'
import { useRunVenueAgainstMutation, useVenueHold } from '@/store/venueHold'
import { RunAgainstDialog } from './RunAgainstDialog'
import { VenueUpdateSheet } from './VenueUpdateSheet'

function laterAlready(key: string): boolean {
  try {
    return sessionStorage.getItem(key) === '1'
  } catch {
    return false
  }
}

function rememberLater(key: string) {
  try {
    sessionStorage.setItem(key, '1')
  } catch {
    // A private window: Later lasts until the banner remounts.
  }
}

/**
 * A show that is behind its venue (venue plan D7, session 6; `Update.dc.html`'s three bands): one row
 * under the app header, beside venue mode's band, on every view of the show.
 *
 * - **Held** — behind on output and live: red, the board's sentence, *Review update* and *Run against
 *   vN…*. The app header's Blind chip says *Blind · venue* with it (`ProgrammerIndicator`).
 * - **Running against** — amber, *Review update* and *Hold output*, which puts the hold back: the old
 *   patch is live, the installed version's trigger and command channels guarded.
 * - **Drawing only** — neutral, the count of drawing changes (`ProjectVenue.drawingChanges`), *Review
 *   update* and *Later*, which hides
 *   it for the session until a newer version is installed.
 * - **Behind on output, not live** — opening it will hold it, said before it does.
 *
 * The live show's state is the `venue.hold` frame's; the rest is `ProjectVenue.behind`.
 */
export function VenueBehindBanner({ projectId }: { projectId: number | undefined }) {
  const { data: project } = useProjectQuery(projectId ?? 0, { skip: projectId == null || !(projectId > 0) })
  const hold = useVenueHold()
  const [reviewOpen, setReviewOpen] = useState(false)
  const [runOpen, setRunOpen] = useState(false)
  const [dismissed, setDismissed] = useState<string | null>(null)
  const banner = project?.kind === 'SHOW' || project?.kind == null ? behindBanner(projectId, project?.venue, hold) : null
  const drawing = banner?.kind === 'drawing'
  const [runVenueAgainst, { isLoading: restoring }] = useRunVenueAgainstMutation()
  if (banner == null || projectId == null || project == null) return null
  const later = laterKey(projectId, banner.installed)
  if (drawing && (dismissed === later || laterAlready(later))) return null

  const tone = banner.kind === 'held' ? 'red' : banner.kind === 'running' || banner.kind === 'output' ? 'amber' : 'neutral'
  const Icon = banner.kind === 'held' ? EyeOff : banner.kind === 'running' ? Play : Landmark
  return (
    <div
      role="status"
      aria-label="Venue update"
      data-venue-behind={banner.kind}
      className={cn(
        'flex shrink-0 flex-wrap items-center gap-x-3 gap-y-1 border-b px-3 py-1.5 text-sm sm:px-4',
        tone === 'red' && 'border-red-600/30 bg-red-500/10',
        tone === 'amber' && 'border-amber-600/30 bg-amber-500/10',
        tone === 'neutral' && 'border-border bg-muted/60',
      )}
    >
      <span className="flex min-w-0 flex-1 items-center gap-2">
        <Icon
          className={cn(
            'size-4 shrink-0',
            tone === 'red' && 'text-red-700 dark:text-red-300',
            tone === 'amber' && 'text-amber-700 dark:text-amber-300',
            tone === 'neutral' && 'text-muted-foreground',
          )}
        />
        <span className="min-w-0">{bannerMessage(banner, project.name, project.venue?.drawingChanges ?? undefined)}</span>
      </span>
      <span className="flex shrink-0 items-center gap-2">
        <Button variant={banner.kind === 'held' ? 'default' : 'outline'} size="sm" className="h-7" onClick={() => setReviewOpen(true)}>
          Review update
        </Button>
        {banner.kind === 'held' && (
          <Button variant="outline" size="sm" className="h-7" onClick={() => setRunOpen(true)}>
            Run against v{banner.pinned}…
          </Button>
        )}
        {/* The way back: hold the output again, as the show opened. */}
        {banner.kind === 'running' && (
          <Button
            variant="outline"
            size="sm"
            className="h-7"
            disabled={restoring}
            onClick={async () => {
              try {
                await runVenueAgainst({ projectId, on: false }).unwrap()
              } catch (e) {
                toast.error(`The hold was not restored: ${formatError(e)}`)
              }
            }}
          >
            Hold output
          </Button>
        )}
        {drawing && (
          <Button
            variant="ghost"
            size="sm"
            className="h-7"
            onClick={() => {
              rememberLater(later)
              setDismissed(later)
            }}
          >
            Later
          </Button>
        )}
      </span>
      <VenueUpdateSheet open={reviewOpen} onOpenChange={setReviewOpen} projectId={projectId} showName={project.name} />
      {banner.kind === 'held' && (
        <RunAgainstDialog open={runOpen} onOpenChange={setRunOpen} projectId={projectId} pinned={banner.pinned} installed={banner.installed} />
      )}
    </div>
  )
}
