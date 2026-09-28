import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

/**
 * The 10px muted line under a panel's controls: what the desk holds, and what was skipped
 * (editor-kit plan D8, D9).
 *
 * The busk Colour tab's emitter line was the first — *Emitters on 6 of 14 heads · the rest take
 * RGB only* — and every value editor draws one now: the level editor's byte (*204 of 255 · 0–255
 * on every head*), the position editor's bytes, the setting editor's skip count (*2 heads have no
 * gobo · skipped*). A commit over a batch where two heads lack the property already skips them
 * silently at write time; this is where that is said.
 *
 * [lines] is the multi-line arm, which was `LandingLines`: the batch's landing **one head to a
 * line**, the last thing read before Apply on a surface whose whole point is that the operator can
 * see where every head is about to go — eight of them run together into a wrapped paragraph is
 * the shape that is hardest to scan. It scrolls rather than growing without limit, because a
 * marquee down a long patch list can name dozens of heads and a popover that outgrows the viewport
 * is one whose Apply cannot be reached. The address editor and the Key column's text editor share
 * it, so the two previews read alike.
 *
 * [error] is the problem, named, in the destructive tone — under the lines where there are any,
 * alone where there are none. A collision is what it most often says.
 */
export function EditorReadout({
  children,
  lines,
  error,
  className,
}: {
  /** The one-line read-out. Absent where the panel has only lines or an error to show. */
  children?: ReactNode
  lines?: readonly string[]
  error?: string | null
  className?: string
}) {
  const hasLines = lines != null && lines.length > 0
  if (children == null && !hasLines && !error) return null
  return (
    <div data-editor-readout className={cn('space-y-1', className)}>
      {children != null && (
        <div className="flex min-h-4 flex-wrap items-center gap-x-2 gap-y-0.5 text-[10px] text-muted-foreground">
          {children}
        </div>
      )}
      {hasLines && (
        <ul className="max-h-40 space-y-0.5 overflow-y-auto font-mono text-[11px] tabular-nums text-muted-foreground">
          {lines.map((line, i) => (
            // Index keys: the list is regenerated whole on every keystroke and never reordered, and
            // two heads of the same name can produce the same line.
            <li key={i} className="truncate" title={line}>
              {line}
            </li>
          ))}
        </ul>
      )}
      {error && <p className="text-xs text-destructive">{error}</p>}
    </div>
  )
}
