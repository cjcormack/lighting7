import { Link } from 'react-router'
import { Landmark } from 'lucide-react'
import { Button } from '@/components/ui/button'
import type { ProjectVenue } from '@/api/projectApi'

/**
 * A show's venue on its header (venue plan session 3, `Show.dc.html`): the venue and the version
 * the show is pinned to, and **Open venue**, which goes to the venue's own Patch list — opening it
 * there, not switching the desk's live project to it. A newer installed version is named, plainly;
 * what that means for the show (behind, held) is session 6's. A venue this desk does not hold reads
 * as its uuid's start and opens nothing.
 */
export function VenueChip({ venue, to = 'patches' }: { venue: ProjectVenue; to?: string }) {
  const name = venue.name ?? `Venue ${venue.uuid.slice(0, 8)}`
  const newer = venue.installed != null && venue.installed > venue.version ? venue.installed : null
  return (
    <span className="inline-flex min-w-0 shrink items-center gap-2" data-venue-chip>
      <span
        className="inline-flex h-7 min-w-0 items-center gap-1.5 rounded-md border px-2.5 text-xs"
        title={`This show sits on ${name} at v${venue.version}${newer != null ? ` · v${newer} is published` : ''}`}
      >
        <Landmark className="size-3.5 shrink-0" aria-hidden />
        <span className="min-w-0 truncate font-medium">{name}</span>
        <span className="shrink-0 font-mono tabular-nums text-muted-foreground">· v{venue.version}</span>
        {newer != null && <span className="hidden shrink-0 text-muted-foreground sm:inline">· v{newer} published</span>}
      </span>
      {venue.id != null && (
        <Button asChild variant="outline" size="sm" className="shrink-0">
          <Link to={`/projects/${venue.id}/${to}`}>Open venue</Link>
        </Button>
      )}
    </span>
  )
}
