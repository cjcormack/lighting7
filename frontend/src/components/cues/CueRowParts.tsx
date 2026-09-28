import { useMemo, useRef } from 'react'
import { Circle, Play } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import { useProjectCueQuery } from '@/store/cues'
import type { Cue, CueTarget } from '@/api/cuesApi'
import { collectCueTargets } from './targetUtils'

/**
 * The pieces a collapsed cue row is made of, in one place.
 *
 * Show and Run grew their own copies of every one of these, and they drifted: the swatches
 * assigned the stored string straight to `background` on one side and resolved gel names on the
 * other, so the same cue's colours rendered differently depending on which view you were in. The
 * target chips disagreed on a shade. Session 2b merges the two views, so the duplication has no
 * remaining excuse — and de-duplicating is a better fix than correcting one of two copies, which is
 * how they came to disagree in the first place.
 */

/** A fixture or group the cue asserts something about. Amber for a head, blue for a group. */
export function CueTargetChip({ target }: { target: CueTarget }) {
  return (
    <Badge
      variant="outline"
      className={cn(
        'text-[10px] px-1.5 py-0 gap-1 max-w-[120px]',
        target.type === 'fixture'
          ? 'border-amber-500/40 text-amber-400 bg-amber-500/10'
          : 'border-blue-500/40 text-blue-300 bg-blue-500/10',
      )}
    >
      <span className="truncate">{target.key}</span>
    </Badge>
  )
}

/**
 * Where this cue stands: outputting, armed for the next GO, or neither.
 *
 * Live wins over armed — a cue can be both when it is re-armed while still on stage, and what it is
 * *doing* matters more than what it is queued to do.
 */
export function CueStatePip({
  isActive,
  isStandby,
}: {
  isActive: boolean
  isStandby: boolean
}) {
  if (isActive) {
    return (
      <span className="size-[22px] rounded-full grid place-items-center bg-green-950 border border-green-900 text-green-400 shadow-[0_0_8px_rgba(74,222,128,0.4)]">
        <Play className="size-3 fill-current" strokeWidth={0} />
      </span>
    )
  }
  if (isStandby) {
    return (
      <span className="size-[22px] rounded-full grid place-items-center bg-blue-950 border border-blue-900 text-blue-300">
        <Circle className="size-1.5 fill-current" strokeWidth={0} />
      </span>
    )
  }
  return (
    <span className="size-[22px] rounded-full grid place-items-center bg-muted border border-border">
      <span className="size-2 rounded-full bg-muted-foreground/30" />
    </span>
  )
}

/**
 * The cue's full record, fetched only while its row is expanded, plus the targets derived from it.
 *
 * Two things here are load-bearing and were duplicated verbatim in both cue cards:
 *
 *  - **The fetch is skipped while collapsed.** A stack is a list of several hundred rows; subscribing
 *    every one of them to its own cue query would be a request per row.
 *  - **The last value is sticky.** A PATCH invalidates the cue, and during the refetch `data` is
 *    briefly undefined — so without this the expanded body flashes empty on every edit. It is
 *    cleared when the cue *id* changes, because the component slot is recycled across a reorder and
 *    would otherwise show the previous cue's body for one render.
 */
export function useExpandedCue(projectId: number, cueId: number, expanded: boolean) {
  const { data: fullCue, isFetching } = useProjectCueQuery(
    { projectId, cueId },
    { skip: !expanded },
  )

  const lastCueRef = useRef<Cue | null>(null)
  const lastCueIdRef = useRef<number>(cueId)
  if (lastCueIdRef.current !== cueId) {
    lastCueRef.current = null
    lastCueIdRef.current = cueId
  }
  if (fullCue) lastCueRef.current = fullCue
  const cueData = fullCue ?? lastCueRef.current

  const targets: CueTarget[] = useMemo(
    () => (cueData ? collectCueTargets(cueData) : []),
    [cueData],
  )

  return { cueData, targets, isFetching }
}
