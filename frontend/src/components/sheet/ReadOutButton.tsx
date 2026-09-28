import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

/**
 * A read-out whose display is a press — a sheet column with no `cell` (so never in the marquee)
 * that still does something when clicked: the cue sheet's Book, Layers and FX open a card or the
 * Prompt Book; the Speed Masters sheet's TAP taps a clock (library-sheets plan §3.1). It was
 * `CueSheet`'s private `ReadOut` until a second sheet needed one.
 *
 * It is a plain button rather than a kit cell: it opens no editor and takes no batch, so a marquee
 * over it selects nothing and a press on it acts on this row alone.
 */
export function ReadOutButton({
  onClick,
  title,
  className,
  disabled,
  children,
  'aria-label': ariaLabel,
}: {
  onClick: () => void
  title: string
  className?: string
  /** The host gave this read-out nowhere to go, or it cannot act on this row — legible but inert. */
  disabled?: boolean
  children: ReactNode
  'aria-label'?: string
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      title={title}
      aria-label={ariaLabel}
      className={cn(
        // `min-w-0` + `whitespace-nowrap`: the cell is a fixed grid track and the row a fixed
        // height, so a read-out that wraps grows the row and paints over its neighbour.
        'mx-1 inline-flex h-7 min-w-0 items-center gap-1.5 whitespace-nowrap rounded px-1.5 text-xs text-muted-foreground',
        'enabled:hover:bg-accent enabled:hover:text-foreground disabled:cursor-default',
        className,
      )}
    >
      {children}
    </button>
  )
}
