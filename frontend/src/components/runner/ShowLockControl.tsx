import { Lock, LockOpen } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

/**
 * The lock toggle and its re-lock countdown, for `ShowHeader`'s `actions` slot.
 *
 * Rendered while the lock is a live concern — a running show — or when the backend will not accept
 * edits at all, where the disabled control is the only thing saying why nothing can be changed.
 * With a stopped, editable show everything is simply editable and there is no state to warn about,
 * so a permanently-open padlock would be chrome describing nothing.
 *
 * Shared with the Prompt Book, which used to draw its own in its toolbar — so the same control sat
 * in a different place depending on which view you were on. Session 2b put it in `ShowHeader`'s
 * `actions` slot on both, beside the view switcher, because it changes what the whole view accepts.
 *
 * The countdown is not decoration. Re-locking silently mid-edit looks like the desk eating
 * keystrokes, so the operator gets ten seconds and a way to refuse.
 */
export function ShowLockControl({
  locked,
  onToggle,
  countdownSecondsLeft,
  onStayUnlocked,
  disabled = false,
}: {
  locked: boolean
  onToggle: () => void
  countdownSecondsLeft: number | null
  onStayUnlocked: () => void
  /** The backend will not accept an edit at all — shown, but inert, to say so. */
  disabled?: boolean
}) {
  return (
    <div className="flex items-center gap-2">
      {countdownSecondsLeft != null && (
        <span className="flex items-center gap-2 whitespace-nowrap rounded-md border border-amber-500 bg-amber-400/20 px-2 py-1 text-xs font-medium text-amber-600">
          Re-locking in {countdownSecondsLeft}s
          <Button size="sm" variant="outline" className="h-6 px-2 text-xs" onClick={onStayUnlocked}>
            Stay unlocked
          </Button>
        </span>
      )}
      <Button
        size="sm"
        variant={locked ? 'outline' : 'default'}
        aria-pressed={!locked}
        aria-label={locked ? 'Unlock for editing' : 'Lock the show'}
        onClick={onToggle}
        disabled={disabled}
        title={
          disabled
            ? 'This project is not the current one, so it cannot be edited'
            : locked
              ? 'Unlock to edit while the show runs (L)'
              : 'Unlocked — a stray click can change the show. Tap to lock (L)'
        }
        // Unlocked mid-show is the one state worth shouting about: believing you are locked when
        // you are not is how a show gets edited by accident.
        className={cn(
          'h-7 gap-1.5 text-xs',
          !locked &&
            !disabled &&
            'animate-pulse bg-amber-500 font-bold text-amber-950 [animation-duration:2.5s] hover:bg-amber-400',
        )}
      >
        {locked ? <Lock className="size-3.5" /> : <LockOpen className="size-3.5" />}
        {locked ? 'Locked' : 'Editing'}
      </Button>
    </div>
  )
}
