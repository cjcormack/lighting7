import { EyeOff } from 'lucide-react'
import { cn } from '@/lib/utils'

/** The mark's words, and its hover — one statement for every row that draws it. */
export const HELD_BLIND_WORDS = 'held · Blind'
export const HELD_BLIND_TITLE =
  'Held by Blind — still running, its phase kept; it paints when Blind goes off'

/**
 * *held · Blind* on a running effect the desk is holding because the programmer is blind
 * (show-cue-editing plan D7, `isHeldByBlind` in `lib/heldBack.ts`): the FX tray's rows and the
 * programmer's FX table. A reporter in Blind's amber, the busk band's `BLIND` pill's shape — never a
 * control: Blind is switched on the programmer's action bar and nowhere else.
 *
 * The host decides whether to draw it (it reads `useProgrammerBlind` once for its list rather than one
 * subscription per row), so this takes no state of its own.
 */
export function HeldBlindMark({ className }: { className?: string }) {
  return (
    <span
      data-held-blind
      title={HELD_BLIND_TITLE}
      className={cn(
        'inline-flex h-4 shrink-0 items-center gap-0.5 rounded border border-amber-500/60 bg-amber-500/15 px-1 text-[9.5px] font-semibold whitespace-nowrap text-amber-700 dark:text-amber-300',
        className,
      )}
    >
      <EyeOff className="size-2.5 shrink-0" aria-hidden />
      {HELD_BLIND_WORDS}
    </span>
  )
}
