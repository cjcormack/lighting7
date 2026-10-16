import { useState } from 'react'
import { EyeOff, Landmark, Play } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { formatError } from '@/lib/formatError'
import { cn } from '@/lib/utils'
import { bannerMessage, behindBanner, behindTone, laterKey, type BehindBanner, type BehindTone } from '@/lib/venueHold'
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
  const [dismissed, setDismissed] = useState<string | null>(null)
  const banner = project?.kind === 'SHOW' || project?.kind == null ? behindBanner(projectId, project?.venue, hold) : null
  const drawing = banner?.kind === 'drawing'
  if (banner == null || projectId == null || project == null) return null
  const later = laterKey(projectId, banner.installed)
  if (drawing && (dismissed === later || laterAlready(later))) return null

  const tone = behindTone(banner)
  const Icon = banner.kind === 'held' ? EyeOff : banner.kind === 'running' ? Play : Landmark
  return (
    <div
      role="status"
      aria-label="Venue update"
      data-venue-behind={banner.kind}
      className={cn(
        'flex shrink-0 flex-wrap items-center gap-x-3 gap-y-1 border-b px-3 py-1.5 text-sm sm:px-4',
        BEHIND_TONE_CLASS[tone],
      )}
    >
      {/* A basis rather than flex-1, so on a phone the buttons wrap under the sentence rather than squeeze it. */}
      <span className="flex min-w-0 flex-[1_1_15rem] items-center gap-2">
        <Icon className={cn('size-4 shrink-0', BEHIND_ICON_CLASS[tone])} />
        <span className="min-w-0">{bannerMessage(banner, project.name, project.venue?.drawingChanges ?? undefined)}</span>
      </span>
      <span className="flex shrink-0 items-center gap-2">
        <VenueBehindActions
          projectId={projectId}
          showName={project.name}
          banner={banner}
          onLater={
            drawing
              ? () => {
                  rememberLater(later)
                  setDismissed(later)
                }
              : undefined
          }
        />
      </span>
    </div>
  )
}

export const BEHIND_TONE_CLASS: Record<BehindTone, string> = {
  red: 'border-red-600/30 bg-red-500/10',
  amber: 'border-amber-600/30 bg-amber-500/10',
  neutral: 'border-border bg-muted/60',
}

export const BEHIND_ICON_CLASS: Record<BehindTone, string> = {
  red: 'text-red-700 dark:text-red-300',
  amber: 'text-amber-700 dark:text-amber-300',
  neutral: 'text-muted-foreground',
}

/**
 * What a show behind its venue can do about it, for the banner and the show's home alike: *Review
 * update*; *Run against vN…* while held; *Hold output* while running against; and, where the host
 * passes [onLater], the drawing-only banner's *Later*. The update sheet and the run-against confirm
 * come with it.
 */
export function VenueBehindActions({
  projectId,
  showName,
  banner,
  onLater,
}: {
  projectId: number
  showName: string
  banner: BehindBanner
  onLater?: () => void
}) {
  const [reviewOpen, setReviewOpen] = useState(false)
  const [runOpen, setRunOpen] = useState(false)
  const [runVenueAgainst, { isLoading: restoring }] = useRunVenueAgainstMutation()
  return (
    <>
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
      {banner.kind === 'drawing' && onLater && (
        <Button variant="ghost" size="sm" className="h-7" onClick={onLater}>
          Later
        </Button>
      )}
      <VenueUpdateSheet open={reviewOpen} onOpenChange={setReviewOpen} projectId={projectId} showName={showName} />
      {banner.kind === 'held' && (
        <RunAgainstDialog open={runOpen} onOpenChange={setRunOpen} projectId={projectId} pinned={banner.pinned} installed={banner.installed} />
      )}
    </>
  )
}
