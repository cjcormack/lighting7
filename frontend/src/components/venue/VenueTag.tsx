import { cn } from '@/lib/utils'

/**
 * The `VENUE` tag beside a venue's name — on its breadcrumb, its rail header and its project card
 * (venue plan session 2; the boards' teal chip, `Venue.dc.html` and `Projects.dc.html`).
 */
export function VenueTag({ className }: { className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex h-4 items-center rounded border border-teal-500/50 bg-teal-500/10 px-1.5 text-[9px] font-bold uppercase tracking-[0.12em] text-teal-700 dark:text-teal-300',
        className,
      )}
    >
      Venue
    </span>
  )
}
