import { EyeOff } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import { useProgrammerBlind } from '@/hooks/useProgrammerBlind'

/**
 * The busk view's blind reporters — **reporters, never a toggle**. Blind is switched on the
 * programmer's action bar and nowhere else, and `ProgrammerIndicator` must not become the control
 * (CLAUDE.md §The show-editing lock); these two only say what that switch says.
 *
 * Why they exist: busk-chrome D10 put the programmer report on the side sheet's Show tab strip,
 * which is invisible with the sheet folded or another tab open — and an immersive window has no
 * app header to carry `ProgrammerIndicator` either. A blind programmer looks *exactly* like a
 * working one until you notice nothing is moving, and on a busk page every press is a programmer
 * write, so the state that matters most is said where the press is made:
 *
 * - [BlindPill] sits in the band's **state group beside the family pill** — the rig row in Split
 *   and Rig, the pad row in Pads, the compact strip and the short board's merged row off the desk
 *   board — the pill's own shape in the indicator's amber, drawn only while blind (D14's rule for
 *   the family pill: absent means nothing to say). It is the eye-off glyph plus the word, and
 *   **the word folds on a rung the host supplies** (`wordClass`: `RigBand`'s own
 *   `BLIND_WORD_CLASS` on the rig row, whose ladder has no slack for it; the Focus words' rung on
 *   the pad row and the compact rows, whose summaries absorb it), because both rows' fold ladders
 *   were measured to the pixel without it and the rig row is `flex-nowrap` above its floor — a
 *   pill that never gave would push the Focus control under the docked sheet in the band the
 *   ladders' docblocks name. `min-w-0 shrink` is the last resort under that: at the extreme it
 *   squashes rather than moving a control, the desk chip's rule.
 * - [BlindDot] marks the **Show tab's glyph**, in the tab strip and on the fold, so the sheet says
 *   there is something to open the Show tab for. **A mark only, `aria-hidden`**: the word is the
 *   host's, in the tab's and the fold button's accessible names (*Show — programmer blind*, *Open
 *   the Show tab — programmer blind*), because an `sr-only` span inside those buttons was silent on
 *   the fold (its `aria-label` wins the name) and *became* the name in the strip below 400px of
 *   sheet, where a non-open tab's word is `display: none`.
 *
 * Both read `useProgrammerBlind`, one narrowed subscription each, so neither re-renders on the
 * programmer's entry churn.
 */
export function BlindPill({ className, wordClass }: { className?: string; wordClass?: string }) {
  const blind = useProgrammerBlind()
  if (!blind) return null
  return (
    <Badge
      variant="outline"
      data-busk-blind
      title="Blind — the programmer is not reaching the stage. Switched on the programmer."
      className={cn(
        'min-w-0 shrink gap-1 overflow-hidden whitespace-nowrap border-amber-500/60 bg-amber-500/15 px-1.5 py-0 text-[10px] font-semibold uppercase tracking-wide text-amber-700 dark:text-amber-300',
        className,
      )}
    >
      <EyeOff className="size-3 shrink-0" aria-hidden />
      <span data-busk-blind-word className={cn('truncate', wordClass)}>
        Blind
      </span>
    </Badge>
  )
}

/** The dot on the Show tab's glyph. The host gives the glyph's wrapper `relative` and carries the word. */
export function BlindDot() {
  const blind = useProgrammerBlind()
  if (!blind) return null
  return (
    <span
      data-busk-blind-dot
      aria-hidden
      className="pointer-events-none absolute -right-1 -top-1 size-2 rounded-full bg-amber-500 ring-2 ring-background"
    />
  )
}

/** The suffix a host appends to the Show tab's or glyph's accessible name while blind. */
export const BLIND_NAME_SUFFIX = ' — programmer blind'
