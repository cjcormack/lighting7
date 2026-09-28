import { LayoutGrid, Lightbulb, Rows2, type LucideIcon } from 'lucide-react'
import { ToggleGroup, ToggleGroupItem } from '@/components/ui/toggle-group'
import { isBuskFocus, setBuskFocus, useBuskFocus, type BuskFocus } from '@/lib/buskWindow'
import { cn } from '@/lib/utils'

/**
 * The **Focus** segmented control — Split · Pads · Rig (busk-further plan D5–D6, `Focus.dc.html`).
 * It reads and writes the window's `busk.focus` and nothing else — and since 2026-09-22 it is
 * **the** control that does: the rig band's handle sets the split's height and never the focus
 * (it used to snap past its ends into Rig and Pads), the rig strip carries no chevron beside it,
 * and only Rig's folded page strip keeps a chevron back to Split (`BuskPageStrip`).
 *
 * **It sits at the end of the rig band's one row in every shape** (2026-09-21) — Split, Rig
 * and, on the desk board, Pads, where the band is drawn folded to that row; off the desk board
 * it is on the rig strip, or the short board's merged row. It used to be on the page strip — below
 * the band in Split and Pads, on the folded strip at the bottom in Rig — so it moved between the
 * middle and the bottom of the body as the operator pressed it, which read as confusing. The host
 * mounts it; this component only draws it.
 *
 * Labels by [labelClass], which the host sets from its own container query — the band folds them
 * with its verbs — glyphs alone otherwise, one control still. Disabled while editing: edit mode
 * forces Split for its duration, because a palette drag needs both regions on screen, and restores
 * the window's focus on Done — the fact is never written by entering edit mode, so there is nothing
 * to restore *to*.
 */
const FOCUSES: readonly { id: BuskFocus; label: string; icon: LucideIcon; title: string }[] = [
  { id: 'split', label: 'Split', icon: Rows2, title: 'Split: rig rows above, the page below' },
  { id: 'pads', label: 'Pads', icon: LayoutGrid, title: 'Pads: the page fills the body and the rig rows are put away' },
  { id: 'rig', label: 'Rig', icon: Lightbulb, title: 'Rig: every row at full size, the page folds to its strip' },
]

export function BuskFocusControl({
  disabled = false,
  className,
  labelClass = 'hidden sm:inline',
}: {
  disabled?: boolean
  className?: string
  /** The segment labels' class — the host's fold, since only the host knows how wide its row is. */
  labelClass?: string
}) {
  const focus = useBuskFocus()
  return (
    <ToggleGroup
      type="single"
      size="sm"
      value={focus}
      onValueChange={(next) => {
        // Radix answers '' for a press on the lit segment; a focus is never "none".
        if (isBuskFocus(next)) setBuskFocus(next)
      }}
      aria-label="Focus"
      disabled={disabled}
      className={cn('h-7 shrink-0 gap-0.5 p-0.5', className)}
    >
      {FOCUSES.map((entry) => (
        <ToggleGroupItem
          key={entry.id}
          value={entry.id}
          aria-label={entry.label}
          title={entry.title}
          className="h-6 gap-1 px-2 text-xs"
        >
          <entry.icon className="size-3.5" />
          <span className={labelClass}>{entry.label}</span>
        </ToggleGroupItem>
      ))}
    </ToggleGroup>
  )
}
