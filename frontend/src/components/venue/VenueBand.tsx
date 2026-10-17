import { useState } from 'react'
import { History, Landmark, PencilLine, Upload } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { useVenueHistory, useVenueMode } from '@/store/venues'
import { draftChipLabel, nextVersion } from '@/lib/venue'
import { cn } from '@/lib/utils'
import { PublishVenueSheet } from './PublishVenueSheet'
import { VenueHistorySheet } from './VenueHistorySheet'

/**
 * Venue mode's band (venue plan session 2; `Venue.dc.html`'s callout 1): one row under the app
 * header on every view of a venue, saying that what is edited here is a draft no show sees until it
 * is published and taken. An admin's band carries the draft count — amber, red-amber when a change
 * would change output — History and *Publish vN…*; an operator's says the venue is read-only, with
 * none of the three (D3).
 *
 * The board draws the chip and the two buttons in the page's header row. That row belongs to each
 * page (a list's breadcrumbs, a live view's `ShowHeader`) and has no room on the Programmer, so here
 * they sit at the band's end, the same on every view.
 */
export function VenueBand({ projectId }: { projectId: number | undefined }) {
  const { isVenue, canEdit } = useVenueMode(projectId)
  const { data: history } = useVenueHistory(projectId)
  const [publishOpen, setPublishOpen] = useState(false)
  const [historyOpen, setHistoryOpen] = useState(false)
  if (!isVenue) return null

  const outputPending = (history?.draft.outputChanges ?? 0) > 0
  return (
    <div
      role="status"
      aria-label="Venue mode"
      className="flex shrink-0 flex-wrap items-center gap-x-3 gap-y-1 border-b border-teal-600/30 bg-teal-500/10 px-3 py-1.5 text-sm sm:px-4"
    >
      {/* A basis rather than flex-1, so where the buttons do not fit beside the sentence they wrap under it rather than squeeze it to a word a line. */}
      <span className="flex min-w-0 flex-[1_1_18rem] items-center gap-2">
        {canEdit ? (
          <PencilLine className="size-4 shrink-0 text-teal-700 dark:text-teal-300" />
        ) : (
          <Landmark className="size-4 shrink-0 text-teal-700 dark:text-teal-300" />
        )}
        {canEdit ? (
          <span className="min-w-0">
            <strong>Editing the venue.</strong>{' '}
            <span className="text-muted-foreground">
              Changes collect in a draft. No show sees them until you publish, and then only when each show takes the update.
            </span>
          </span>
        ) : (
          <span className="min-w-0">
            <strong>Viewing a venue.</strong>{' '}
            <span className="text-muted-foreground">Only an admin can change or publish it.</span>
          </span>
        )}
      </span>
      {canEdit && history && (
        // Wraps rather than widening the page: on a phone the chip and two buttons are wider than it.
        <span className="flex min-w-0 max-w-full flex-wrap items-center gap-2">
          <span
            className={cn(
              'inline-flex h-6 items-center gap-1 rounded-full border px-2 text-xs font-medium tabular-nums',
              outputPending
                ? 'border-orange-500/50 bg-orange-500/10 text-orange-700 dark:text-orange-300'
                : 'border-amber-500/50 bg-amber-500/10 text-amber-700 dark:text-amber-300',
            )}
            title={outputPending ? `${history.draft.outputChanges} of them change output` : undefined}
          >
            <PencilLine className="size-3" />
            {draftChipLabel(history)}
          </span>
          <Button variant="outline" size="sm" className="h-7" onClick={() => setHistoryOpen(true)}>
            <History className="size-3.5" />
            History
          </Button>
          <Button
            size="sm"
            className="h-7"
            disabled={history.draft.changes === 0}
            title={history.draft.changes === 0 ? 'Nothing to publish' : undefined}
            onClick={() => setPublishOpen(true)}
          >
            <Upload className="size-3.5" />
            Publish v{nextVersion(history)}…
          </Button>
          <PublishVenueSheet open={publishOpen} onOpenChange={setPublishOpen} history={history} />
          <VenueHistorySheet open={historyOpen} onOpenChange={setHistoryOpen} history={history} />
        </span>
      )}
    </div>
  )
}
