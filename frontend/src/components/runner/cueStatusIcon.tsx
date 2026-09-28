import { Check, Play, Circle } from 'lucide-react'

/**
 * Status icon for a cue row. Shared by CueRow (desktop) and MobileCueRow
 * so the icon logic and sizing stay in sync across both layouts.
 */
export function cueStatusIcon(
  isActive: boolean,
  isStandby: boolean,
  isDone: boolean,
  autoProgress: number | null,
): React.ReactNode {
  if (isActive && autoProgress != null) {
    return <Circle className="size-2.5 fill-blue-500 text-blue-500 animate-pulse" />
  }
  if (isActive) {
    return <Play className="size-3.5 fill-green-400 text-green-400" />
  }
  if (isStandby) {
    return <Circle className="size-3 fill-blue-500 text-blue-500" />
  }
  if (isDone) {
    return <Check className="size-3.5 text-muted-foreground" />
  }
  return null
}
