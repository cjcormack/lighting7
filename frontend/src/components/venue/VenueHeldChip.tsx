import { EyeOff } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import { heldByVenue } from '@/lib/venueHold'
import { useVenueHold } from '@/store/venueHold'

/**
 * *Blind · venue*, for an immersive window (venue plan P5, session 6): the app header that carries
 * `ProgrammerIndicator`'s venue reason is not drawn there, and a held show looks exactly like a
 * working one until you notice the rig never moved — so the live view's header says it, as it says
 * *Offline* and *Armed*. Drawn only while [projectId] is the live show and held.
 */
export function VenueHeldChip({ projectId, className }: { projectId: number; className?: string }) {
  const hold = useVenueHold()
  if (!heldByVenue(hold, projectId)) return null
  return (
    <Badge
      variant="outline"
      data-venue-held
      title={`Output held: this show uses v${hold.pinned} and the hall is v${hold.installed}`}
      className={cn(
        'h-5 shrink-0 gap-1 border-amber-500/60 bg-amber-500/15 px-2 text-[10px] font-semibold uppercase tracking-wide text-amber-700 dark:text-amber-300',
        className,
      )}
    >
      <EyeOff className="size-3" aria-hidden />
      Blind · venue
    </Badge>
  )
}
