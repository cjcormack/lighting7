import { Link2 } from 'lucide-react'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { cn } from '@/lib/utils'
import { cuePlaceLabel, sharedCueLabel, type CuePlace } from '@/lib/cuePlaces'

/**
 * The shared-cue mark (lighting7 cue model plan §4): `in 2 stacks` on a cue entry whose cue sits in
 * more than one place, its other places on hover and in a popover on a tap. Draws nothing for a cue
 * in one place. A press never reaches the row underneath, which arms or expands on a click.
 */
export function SharedCueChip({
  places,
  entryId,
  interactive = true,
  className,
}: {
  /** Every place the cue sits, this entry's included. */
  places: readonly CuePlace[] | undefined
  /** This entry — left out of the list of others. */
  entryId: number
  /** False where the chip sits inside another control (a sheet cell's trigger): hover only. */
  interactive?: boolean
  className?: string
}) {
  const label = sharedCueLabel(places)
  if (label == null || places == null) return null
  const others = places.filter((p) => p.entryId !== entryId)
  const title = `Also ${others.map(cuePlaceLabel).join(', ')}`
  const chipClass = cn(
    'inline-flex shrink-0 items-center gap-1 rounded-full border border-sky-500/40 bg-sky-500/10 px-1.5 py-px text-[10px] font-medium text-sky-400',
    className,
  )
  if (!interactive) {
    return (
      <span data-shared-cue role="img" title={title} aria-label={`${label} — ${title}`} className={chipClass}>
        <Link2 className="size-3" />
        {label}
      </span>
    )
  }
  return (
    <Popover>
      <PopoverTrigger asChild>
        <button
          type="button"
          data-shared-cue
          title={title}
          aria-label={`${label} — ${title}`}
          onClick={(e) => e.stopPropagation()}
          onPointerDown={(e) => e.stopPropagation()}
          className={cn(chipClass, 'hover:bg-sky-500/20')}
        >
          <Link2 className="size-3" />
          {label}
        </button>
      </PopoverTrigger>
      <PopoverContent align="start" className="w-56 p-2" onClick={(e) => e.stopPropagation()}>
        <div className="mb-1 px-1 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
          Also in
        </div>
        <ul className="space-y-0.5">
          {others.map((p) => (
            <li key={p.entryId} className="truncate px-1 text-xs">
              {cuePlaceLabel(p)}
            </li>
          ))}
        </ul>
      </PopoverContent>
    </Popover>
  )
}
