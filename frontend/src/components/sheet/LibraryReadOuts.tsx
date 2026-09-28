import type { ReactNode } from 'react'
import { Lock } from 'lucide-react'

/**
 * The read-only scope's reason on a library sheet's bar (library-sheets plan D12) — the cue lock's
 * note, in its shape: a lock and the sentence `libraryPermission` gives.
 */
export function ReadOnlyNote({ reason }: { reason: string }) {
  return (
    <span className="inline-flex shrink-0 items-center gap-1.5 border-l pl-3 text-xs text-muted-foreground">
      <Lock className="size-3" />
      {reason}
    </span>
  )
}

/**
 * A count read-out — cue layers, busk pages — with an icon beside it. **A faint em-dash at zero**,
 * as the Speed Masters sheet's Used by draws it and the boards' `count()` does: a zero is a
 * read-out with nothing in it, not an empty settable cell.
 */
export function CountReadOut({ count, icon, title }: { count: number; icon?: ReactNode; title?: string }) {
  if (count === 0) return <span className="mx-2 text-xs text-muted-foreground/45">—</span>
  return (
    <span className="mx-2 inline-flex items-center gap-1.5 text-xs tabular-nums text-muted-foreground" title={title}>
      {icon}
      {count}
    </span>
  )
}
